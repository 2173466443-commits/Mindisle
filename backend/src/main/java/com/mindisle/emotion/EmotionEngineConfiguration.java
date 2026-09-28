package com.mindisle.emotion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.config.MindisleProperties;

/**
 * 词典情绪引擎的装配。
 *
 * <p>{@link DictEmotionEngine} 本身<b>刻意不是</b> {@code @Component}：
 * 它是个纯构造器注入的不可变对象，单测里 {@code new} 一个就能测，
 * 不需要 Spring 上下文，也不需要 mock 任何东西。把「变成 bean」这件事放到这里，
 * 引擎就同时具备「可脱离框架测试」和「可被注入」两种性质。</p>
 *
 * <p>装载是启动期一次性动作：27,315 个词面 + 31,291 个词条建索引，
 * 之后只读。放懒加载会让第一次对话承担建索引的延迟，那是 NFR2 的 TTFT 指标，不能背这个锅。</p>
 */
@Configuration
public class EmotionEngineConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EmotionEngineConfiguration.class);

    @Bean
    public DictEmotionEngine dictEmotionEngine(ObjectMapper mapper, MindisleProperties properties) {
        EmotionLexicon lexicon = EmotionLexicon.load();
        EmotionPrior prior = EmotionPrior.load(mapper);
        DictEmotionEngine engine = new DictEmotionEngine(lexicon, prior);
        log.info("词典情绪通道就绪：model={} 词面={} 词条={} 置信度下限={} LLM升级阈值={}",
                engine.modelVersion(), lexicon.dutFaceCount(), lexicon.dutEntryCount(),
                properties.getLlm().getEmotionConfidentMin(), properties.getLlm().getEmotionLlmFallbackBelow());
        return engine;
    }
}
