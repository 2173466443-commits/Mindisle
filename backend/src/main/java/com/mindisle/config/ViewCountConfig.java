package com.mindisle.config;

import com.mindisle.cache.CacheService;
import com.mindisle.mapper.PostMapper;
import com.mindisle.post.ViewCountService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 浏览量计数装配（任务 3.5）。
 *
 * <p><b>为什么不在 {@link ViewCountService} 上直接打 {@code @Service}</b>：
 * 它构造时需要一个「把增量写回库」的端口实现，而端口只能在这里用 {@code postMapper::increaseViewCnt}
 * 适配。若把注解打在类上，构造参数就得直接依赖 {@code PostMapper}——
 * 那个类是 {@code BaseMapper}，任何单测想 new 出 ViewCountService 都得先 mock 一层 MyBatis 接口，
 * 而这恰好是本项目的既定测试策略明确不做的（见 docs/dev-log.md：Service 依赖 BaseMapper 时改测纯逻辑 +
 * 真 HTTP 冒烟 + 真库取证）。现在 ViewCountService 零 Spring 依赖，单测直接 {@code new} 一个假 Flusher。
 * 同一个手法在 {@code AnonymousAliasRepositoryAdapter}（端口—适配器）上已经用过一次。
 * {@code FileController}/{@code PostingQuotaService} 同理：装配归装配，业务类保持可裸测。</p>
 *
 * <p>回写窗口用 {@code ViewCountService} 的默认值 5 分钟，与手册 §6.1 行 3.5 逐字一致；
 * 不额外开配置项——没有任何运维场景需要改它，需求 §12 的过度设计红线。</p>
 */
@Configuration(proxyBeanMethods = false)
public class ViewCountConfig {

    @Bean
    public ViewCountService viewCountService(CacheService cache, PostMapper postMapper) {
        return new ViewCountService(cache, postMapper::increaseViewCnt);
    }
}
