package com.mindisle.web;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.ai.ChatService;
import com.mindisle.ai.ConversationService;
import com.mindisle.ai.dto.ChatSendRequest;
import com.mindisle.ai.dto.ConversationView;
import com.mindisle.ai.dto.FeedbackRequest;
import com.mindisle.ai.dto.MessageView;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.Result;
import com.mindisle.config.MindisleProperties;
import com.mindisle.security.AuthUser;
import com.mindisle.track.UserActionCatalog;
import com.mindisle.track.UserActionRecorder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;

/**
 * AI 对话域的 6 条端点（需求 §9.1 AI 行 + §9.2 SSE · 任务 T4.2 / T4.17）。
 *
 * <p><b>本类唯一不寻常的地方是 {@code /chat/stream} 的线程模型</b>：它在请求线程上做完
 * 鉴权、取 traceId、注册回调之后立刻返回一个 {@code SseEmitter}，真正的活儿交给一个
 * <b>上限 5 的专用线程池</b>。三条理由：
 * <ol>
 *   <li>不放 Tomcat 线程：一次流式对话要占住线程十几秒（等首字 + 逐 token），
 *       NFR4 要的 50 并发用户会被五路对话抽干，代价是整个站点变慢，不只是本页。</li>
 *   <li>不用无界队列：NFR4 明写「5 路并发流式对话」。超出就直接回一帧 error
 *       （「同时提问的人有点多」），而不是让人对着一排气泡等一分钟 —— 
 *       排队会让 TTFT 变成一件不可解释的事，而 TTFT 是 NFR2 的验收指标。</li>
 *   <li>不用 {@code @Async} 公共池：全站没有第二个异步需求（现查：0 处 {@code @Async}），
 *       为一个端点引入全局异步配置，收益不抵「别处的异步会被这里的线程数影响」。</li>
 * </ol>
 * </p>
 *
 * <p><b>traceId 必须在请求线程上取</b>：MDC 是 ThreadLocal，换到工作线程再取就是 null，
 * 于是最需要排查的那条链路（AI）反而在日志里没有链路号。取完显式传进
 * {@code streamTurn}，并在工作线程上重新 {@code MDC.put}，见方法里的两行。</p>
 */
@RestController
@Tag(name = "10 AI 对话", description = "会话管理、SSE 流式对话与反馈（阶段 4）")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    /** NFR4 的并发流式上限。写死在这里而不是配置里：它同时是线程池的核心与最大线程数，改数要重启才自洽。 */
    private static final int MAX_CONCURRENT_STREAMS = 5;

    private static final AtomicInteger STREAM_SEQ = new AtomicInteger();

    private final ChatService chatService;
    private final ConversationService conversationService;
    private final MindisleProperties properties;
    private final ObjectMapper mapper;
    private final UserActionRecorder recorder;

    /**
     * 专用流式线程池：核心=最大=5，{@link SynchronousQueue}（不排队，满了立刻拒）。
     *
     * <p>daemon 线程：演示时 Ctrl+C 要能立刻退出去，不该等五条各自最长 30s 的 SSE 收场。</p>
     */
    private final ExecutorService streamPool = new ThreadPoolExecutor(MAX_CONCURRENT_STREAMS,
            MAX_CONCURRENT_STREAMS, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), runnable -> {
        Thread t = new Thread(runnable, "ai-sse-" + STREAM_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    public AiController(ChatService chatService, ConversationService conversationService,
            MindisleProperties properties, ObjectMapper mapper, UserActionRecorder recorder) {
        this.chatService = chatService;
        this.conversationService = conversationService;
        this.properties = properties;
        this.mapper = mapper;
        this.recorder = recorder;
    }

    // ============================================================ SSE 主链路

    /**
     * 流式对话（FR2.2 · T4.5/T4.6/T4.17）。事件序列 meta → delta* → done，异常时 error。
     *
     * <p><b>为什么不用 {@code produces = TEXT_EVENT_STREAM_VALUE}</b>：那条路径会让 Spring
     * 用 {@code ResponseBodyEmitter} 自己管内容协商；这里显式设置 {@code Cache-Control}
     * 与 {@code X-Accel-Buffering} 两个头（Nginx 一开缓冲，逐字上屏就变成「憋十几秒然后整段」，
     * 而这条 bug 在开发机上永远复现不了），所以直接把 response 拿在手里更可控。</p>
     */
    @PostMapping("/api/ai/chat/stream")
    @Operation(summary = "流式对话（SSE：meta/delta/done/error；停止生成 = 客户端断流）")
    public SseEmitter stream(@RequestBody(required = false) ChatSendRequest request,
            @AuthenticationPrincipal AuthUser current, HttpServletResponse response) {
        if (current == null || current.id() == null) {
            // 未登录走不到这里（不在 permitAll 白名单），这一行是白名单将来被改宽时的兜底
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        MindisleProperties.Llm cfg = properties.getLlm();
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
        SseEmitter emitter = new SseEmitter((long) cfg.getSseTimeoutMs());
        AtomicBoolean stop = new AtomicBoolean(false);
        emitter.onCompletion(() -> stop.set(true));
        emitter.onTimeout(() -> {
            stop.set(true);
            log.info("SSE 超时（{}ms），已请求停止上游流", cfg.getSseTimeoutMs());
        });
        emitter.onError(throwable -> {
            stop.set(true);
            log.info("SSE 连接出错，已请求停止上游流：{}", throwable.toString());
        });
        // ① 在请求线程上取（换线程后 MDC 就空了）
        final String traceId = Result.currentTraceId();
        final long userId = current.id();
        final ChatSendRequest body =
                request == null ? new ChatSendRequest(null, null, null) : request;
        final SseSink sink = new SseSink(emitter, stop);
        try {
            streamPool.execute(() -> {
                // ② 在工作线程上重建，让这一轮的所有日志都还带同一个链路号
                if (traceId != null) {
                    MDC.put(Result.TRACE_ID_KEY, traceId);
                }
                try {
                    chatService.streamTurn(userId, body, sink, traceId);
                } finally {
                    if (traceId != null) {
                        MDC.remove(Result.TRACE_ID_KEY);
                    }
                    sink.finish();
                }
            });
        } catch (RejectedExecutionException e) {
            // 五路都占着：说人话 + 收场。这里不排队，理由见类注释第 2 条。
            log.info("并发流式对话已达上限 {}，拒绝新请求 user={} traceId={}",
                    MAX_CONCURRENT_STREAMS, userId, traceId);
            sink.error(ErrorCode.AI_UNAVAILABLE.getCode(),
                    "同时和屿屿说话的人有点多，等几秒再发这一句。");
            sink.finish();
        }
        return emitter;
    }

    // ============================================================ 会话侧栏（FR2.1）

    /** 会话列表（U7 左侧栏）。 */
    @GetMapping("/api/ai/conversations")
    @Operation(summary = "我的 AI 会话列表（按最后一条消息倒序，最多 50 条）")
    public Result<List<ConversationView>> conversations(
            @RequestParam(name = "limit", required = false) Integer limit,
            @AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        return Result.ok(conversationService.list(current.id(), limit));
    }

    /**
     * 新建会话（可带初始标题与语气档位；两者都留空即「一条全新的『新的对话』」。
     *
     * <p>请求体可整个省略：{@code @RequestBody(required = false)}。点侧栏「新建」时
     * 前端还没有任何可发的内容，逼它发一个空对象只会多一处 {@code data: {}} 的猜测。</p>
     */
    @PostMapping("/api/ai/conversations")
    @Operation(summary = "新建 AI 会话（title/style 可省；省略即默认「新的对话」+ warm）")
    public Result<ConversationView> createConversation(
            @RequestBody(required = false) CreateConversationRequest request,
            @AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        String title = request == null ? null : request.title();
        String style = request == null ? null : request.style();
        return Result.ok(conversationService.create(current.id(), title, style));
    }

    /**
     * 重命名会话（需求 FR2.1「可重命名」· 任务 T4.2 的最后一格，v1.2.6 补）。
     *
     * <p>用 PATCH 而不是 POST：改的是「一条已有资源的一个字段」，语义上既不是新建，
     * 也不是整篇替换（PUT 要求带全量 representation，而前端手里只有标题）。
     * 需求 §9.1 的端点表原本没列这一条，属补齐而非契约漂移，已记 dev-log。</p>
     */
    @PatchMapping("/api/ai/conversations/{id:\\d+}")
    @Operation(summary = "重命名一条 AI 会话（不存在或不属于你 → 90006；标题为空 → 10001）")
    public Result<ConversationView> renameConversation(@PathVariable("id") long id,
            @RequestBody(required = false) RenameConversationRequest request,
            @AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        return Result.ok(conversationService.rename(current.id(), id,
                request == null ? null : request.title()));
    }

    /** 删除会话（逻辑删除，消息行留在库里等 T4.21 的物理清除）。 */
    @DeleteMapping("/api/ai/conversations/{id:\\d+}")
    @Operation(summary = "删除一条 AI 会话（不存在或不属于你 → 90006）")
    public Result<Void> deleteConversation(@PathVariable("id") long id,
            @AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        conversationService.delete(current.id(), id);
        return Result.ok();
    }

    /**
     * 回看会话内消息（任务 T4.2「chat_message CRUD」的 R）。
     *
     * <p>需求 §9.1 的端点表里没有这一条：它是 §9.1 的<b>概览省略</b>而非禁止 ——
     * U7 要求「左会话列表 + 右聊天区」，没有这条就只能永远看着最新那一条会话。
     * 已记进 dev-log 的契约漂移清单。</p>
     */
    @GetMapping("/api/ai/conversations/{id:\\d+}/messages")
    @Operation(summary = "回看某条会话的消息（按时间正序，最多 200 条）")
    public Result<List<MessageView>> messages(@PathVariable("id") long id,
            @RequestParam(name = "limit", required = false) Integer limit,
            @AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        return Result.ok(conversationService.messages(current.id(), id, limit));
    }

    /** 赞踩（FR2.7 / T4.19）。 */
    @PostMapping("/api/ai/messages/{id:\\d+}/feedback")
    @Operation(summary = "给一条 AI 回复打「有用 / 没被理解」（UP / DOWN / NONE）")
    public Result<Void> feedback(@PathVariable("id") long id,
            @RequestBody(required = false) FeedbackRequest request,
            @AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        String feedback = request == null ? null : request.feedback();
        conversationService.feedback(current.id(), id, feedback);
        // 埋点放在控制器而不是 ConversationService（任务 T4.19）。理由只有一条：
        // service 里有一句「值没变就 return」的早退路径，那是给 UPDATE 省一次写，
        // 而埋点要的是每一次点击。写在 service 里会让「连点两下同一个赞」少记一行——
        // 虽然 upsert 命中的是同一条 uk 本来也不增行，但口径应当是「每次调用都进 recorder」，
        // 不是「每次调用先看 chat_message 有没有变」。
        // 位置在 service 之后：消息不存在、不是助手消息、反馈值非法时 service 已经抛了 4xx，
        // 这条埋点不该替一个失败的请求记下「用户赞了它」。
        recorder.recordAiFeedback(current.id(), id, feedback, LocalDateTime.now());
        return Result.ok();
    }

    // ============================================================ 内部件

    /** 重命名会话的请求体：只认 title 一个字段，夹 30 字的口径在 ConversationService.TITLE_MAX。 */
    public record RenameConversationRequest(String title) {
    }

    /** 新建会话的请求体（两个字段都可省）。 */
    public record CreateConversationRequest(String title, String style) {
    }

    private static void requireLogin(AuthUser current) {
        if (current == null || current.id() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }

    /**
     * {@link ChatService.StreamSink} 的 SSE 实现。
     *
     * <p><b>每个 send 都吞 IOException</b>，这是 {@code StreamSink} 接口注释里写明的义务：
     * 用户关页面时 {@code emitter.send} 必抛，让它穿回 {@code ChatService} 会被
     * 当成「一次失败的模型调用」记进熔断计数 —— 三个人同时关页面就能把整站打成
     * AI_UNAVAILABLE，而这明明只是三个人走了。</p>
     *
     * <p>载荷一律先序列化成<b>单行</b> JSON 再发：Jackson 默认不插换行，但要是哪天有人
     * 打开 {@code INDENT_OUTPUT}（全局 ObjectMapper 的一个开关，很容易顺手改），
     * SSE 的 data 行会被拆成多行，前端的 {@code split('\\n')} 解析就只认第一行 ——
     * 表现为「气泡永远空白，Network 里却看得到完整 JSON」。所以这里显式压缩成一行，
     * 与全局美化配置解耦。</p>
     */
    private final class SseSink implements ChatService.StreamSink {

        private final SseEmitter emitter;
        private final AtomicBoolean stop;

        SseSink(SseEmitter emitter, AtomicBoolean stop) {
            this.emitter = emitter;
            this.stop = stop;
        }

        @Override
        public void meta(ChatService.MetaPayload payload) {
            send("meta", payload);
        }

        @Override
        public void delta(String content) {
            send("delta", new DeltaFrame(content));
        }

        @Override
        public void done(ChatService.DonePayload payload) {
            send("done", payload);
            finish();
        }

        @Override
        public void error(int code, String message) {
            send("error", new ErrorFrame(code, message));
            finish();
        }

        @Override
        public boolean isCancelled() {
            return stop.get();
        }

        /** 收场：complete 只该发生一次，重复调用会让 Spring 记一条「响应已提交」的 WARN。 */
        void finish() {
            if (!stop.getAndSet(true)) {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.info("SSE complete 失败（多半已断开），已忽略：{}", e.toString());
                }
            }
        }

        private void send(String event, Object payload) {
            if (stop.get()) {
                return;
            }
            String json;
            try {
                json = mapper.writeValueAsString(payload);
            } catch (JsonProcessingException e) {
                log.error("SSE 载荷序列化失败 event={}，改发 error 帧", event, e);
                // 载荷发不出去就等于这一帧永远到不了，宁可回一个错误也不能留半截流
                json = null;
            }
            if (json == null) {
                return;
            }
            json = json.replace('\r', ' ').replace('\n', ' ');
            try {
                emitter.send(SseEmitter.event().name(event).data(json, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                stop.set(true);
                log.info("SSE 帧下发失败 event={}，已标记这一路停止：{}", event, e.toString());
            }
        }
    }

    /** delta 帧：字段名固定为 content，前端 ChatView.vue 认的就是这个键。 */
    private record DeltaFrame(String content) {
    }

    /** error 帧：code/msg 两个键与统一响应体同名，前端一套读法到底。 */
    private record ErrorFrame(int code, String msg) {
    }
}
