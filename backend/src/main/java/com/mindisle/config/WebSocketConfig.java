package com.mindisle.config;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.pm.StompPrincipalHandshakeHandler;
import com.mindisle.pm.WsAuthHandshakeInterceptor;
import com.mindisle.security.JwtService;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket 的装配（任务 T5.1 · 需求 FR6.1、§7.2 #15 · 手册 §8.1）。
 *
 * <p>本类只负责「线路与身份」，一条业务判据都不写：业务全在 {@code PmService} 里。
 * 这样拆的理由与 REST 层把校验放在 Service 是同一个——将来换掉传输方式（HTTP 长轮询、
 * 或者阶段 8 真要上 MQ）时，判据不需要跟着搬家。</p>
 *
 * <h3>两个端点，不是一条</h3>
 * <ul>
 *   <li>{@code /ws}：带 SockJS。浏览器主链路走它，好处是代理不支持 Upgrade 时自动降级
 *       XHR-polling（手册 §8.3 Gate5 那条「关掉 WS 仍能收消息」的验证项靠的就是这个能力）。</li>
 *   <li>{@code /ws-native}：不带 SockJS 的裸 STOMP。给 Playwright 探针与非浏览器客户端用
 *       （阶段 5 的 {@code probe/pmgate.mjs} 直连这一条），顺带把「SockJS 层有没有 bug」
 *       与「STOMP 层有没有 bug」分成两个可独立复现的面。</li>
 * </ul>
 *
 * <h3>心跳为什么写在 enableSimpleBroker 的返回值上</h3>
 * <p>{@code configureEndpoint(WebSocketEndpointConfiguration)} 这一句在本项目依赖的
 * Spring 7.0.9 里<b>不存在</b>（javap 实测：{@link WebSocketMessageBrokerConfigurer} 只有
 * registerStompEndpoints / configureWebSocketTransport / configureClientInboundChannel /
 * configureClientOutboundChannel / addArgumentResolvers / addReturnValueHandlers /
 * configureMessageConverters / configureMessageBroker / getPhase 九个 default 方法）。
 * 手册 §8.1 那句「configureEndpointRuntimeScheduler + setHeartbeatValue」按现版本落成的正确写法是：
 * 服务端心跳挂 {@code enableSimpleBroker(...)} 返回的 {@code SimpleBrokerRegistration} 上，
 * SockJS 那一层的 {@code setHeartbeatTime} 单独设一次（两者是两套心跳：前者是 STOMP 协议心跳帧，
 * 后者是 SockJS 自己的 iframe/xhr 保活帧）。两侧都用 30s，与前端 {@code useWs.js} 的
 * 客户端心跳 30s 对齐——三个数字必须同值，否则总有一侧先判对方死了。</p>
 *
 * <h3>一个必须知道的副作用（本轮 javap 实测得出，不是抄来的）</h3>
 * <p>{@code @EnableWebSocketMessageBroker} 引入的 {@code AbstractMessageBrokerConfiguration}
 * 自带一个名为 {@code messageBrokerTaskScheduler}、类型 {@code TaskScheduler} 的 Bean。
 * 而 {@code MindisleApplication} 上有 {@code @EnableScheduling}（阶段 4 的周报
 * {@code WeeklyReportJob} 与 PIPL 的 {@code DataRetentionJob} 都在它上面跑）。
 * Spring 的调度器解析规则是「容器里有唯一 TaskScheduler 就用它，否则退回一个匿名单线程池」，
 * 于是从本类生效这一刻起，那两个 {@code @Scheduled} <b>改在框架这个线程池上跑</b>。
 * 处置有三条，都写死在这里：</p>
 * <ol>
 *   <li><b>不再注册第二个 TaskScheduler Bean</b>：多候选会让唯一性判定失败，{@code @Scheduled}
 *       反而退化成匿名单线程池——比现在更难排查；</li>
 *   <li>broker 的心跳用<b>方法内的局部</b>调度器（见下），与框架那个分开，心跳饿死不影响定时任务；</li>
 *   <li>这条已写进手册 §8 的收工口径与全局踩坑日志，阶段 6 若给审核 SLA 扫描再加 {@code @Scheduled}，
 *       要记得它们共用一个池。</li>
 * </ol>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  /** STOMP 服务端心跳间隔，与手册 §8.1「服务端 30s/30s」、前端 30s 三个数字必须同值。 */
  static final long HEARTBEAT_MILLIS = 30_000L;

  /** 带 SockJS 的端点（浏览器主链路）。 */
  static final String ENDPOINT_SOCKJS = "/ws";

  /** 裸 WebSocket 端点（探针与非浏览器客户端）。 */
  static final String ENDPOINT_NATIVE = "/ws-native";

  private final JwtService jwtService;
  private final MindisleProperties properties;
  private final ObjectMapper objectMapper;

  public WebSocketConfig(JwtService jwtService, MindisleProperties properties,
      ObjectMapper objectMapper) {
    this.jwtService = jwtService;
    this.properties = properties;
    // 这个 ObjectMapper 是 Jackson2Config 显式提供的那一个（Boot 4 默认自动配置的是
    // tools.jackson 三件套，容器里并没有 Jackson2 类型的 Bean）。见 configureMessageConverters。
    this.objectMapper = objectMapper;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint(ENDPOINT_SOCKJS)
        .setAllowedOriginPatterns(allowedOrigins())
        .addInterceptors(new WsAuthHandshakeInterceptor(jwtService))
        .setHandshakeHandler(new StompPrincipalHandshakeHandler())
        .withSockJS()
        .setHeartbeatTime(HEARTBEAT_MILLIS)
        // 不关 WebSocket 传输：关掉之后 SockJS 只会用 xhr-polling，实测时看不出降级链路是否真的可用。
        .setWebSocketEnabled(true);
    registry.addEndpoint(ENDPOINT_NATIVE)
        .setAllowedOriginPatterns(allowedOrigins())
        .addInterceptors(new WsAuthHandshakeInterceptor(jwtService))
        .setHandshakeHandler(new StompPrincipalHandshakeHandler());
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    // 局部调度器：只做 broker 心跳，刻意不做成 Bean（理由见类注释第 3 条）。
    ThreadPoolTaskScheduler heartbeatScheduler = new ThreadPoolTaskScheduler();
    heartbeatScheduler.setPoolSize(1);
    heartbeatScheduler.setThreadNamePrefix("ws-hb-");
    heartbeatScheduler.setDaemon(true);
    heartbeatScheduler.setRemoveOnCancelPolicy(true);
    heartbeatScheduler.initialize();
    registry.enableSimpleBroker("/queue", "/topic")
        .setHeartbeatValue(new long[] { HEARTBEAT_MILLIS, HEARTBEAT_MILLIS })
        .setTaskScheduler(heartbeatScheduler);
    registry.setApplicationDestinationPrefixes("/app");
    // /user 是点对点的前缀，配合 StompPrincipalHandshakeHandler 把 Principal name 定成用户 id：
    // 少这一句就用不了 convertAndSendToUser，而「点对点私信按 session id 寻址」是本阶段最贵的一种错。
    registry.setUserDestinationPrefix("/user");
  }

  /**
   * 给 STOMP 换成项目自己的 Jackson 2 映射器。
   *
   * <p>{@code return true} 而不是 false：返回 true 表示「自定义转换器排在默认那一串<b>前面</b>」，
   * 返回 false 会让默认转换器全部消失，字节数组、文本、Map 那几种载荷直接不认。
   * REST 侧看不到这个问题，是因为 WebMvc 的转换器由 Boot 自动配置好；
   * messaging 这套是自己 new 的，不接这一句就还是「用不带 JavaTimeModule 的默认映射器」——
   * {@code PmMessageView.readAt} / {@code createdAt} 都是 {@code LocalDateTime}，
   * 症状是发送成功、前端一条消息都渲染不出来，而且异常在 outbound 通道里被吞掉。</p>
   */
  @Override
  public boolean configureMessageConverters(List<MessageConverter> converters) {
    converters.add(new MappingJackson2MessageConverter(objectMapper));
    return true;
  }

  /**
   * 允许的跨域来源，复用 {@code SecurityConfig#corsConfigurationSource} 那一份白名单。
   *
   * <p>两处必须同源：WS 与 REST 的 origin 校验各读一份配置，改配置的人只会改一处，
   * 于是「网页能登录但私信连不上」这种只在生产域名下复现的问题就有了固定成因。
   * 用 {@code setAllowedOriginPatterns} 而不是 {@code setAllowedOrigins}：前者允许
   * {@code *} 通配（本地 5173/4173 这类端口会变的开发机），而 SockJS 的注册表里
   * 那两条 setAllowedOrigin* 是 protected，从配置类调不到——这也是本类把 origin
   * 设在 STOMP 端点层而不是 SockJS 层的原因。</p>
   */
  private String[] allowedOrigins() {
    List<String> origins = properties.getCors().getAllowedOrigins();
    return origins == null || origins.isEmpty() ? new String[0] : origins.toArray(new String[0]);
  }
}
