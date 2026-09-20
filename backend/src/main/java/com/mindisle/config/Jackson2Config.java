package com.mindisle.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 显式提供 Jackson 2 的 ObjectMapper Bean。
 *
 * <p>必要性（实测踩坑，见 docs/dev-log.md）：Spring Boot 4.1.1 的 Jackson 自动配置默认产出的是
 * Jackson 3 的 {@code tools.jackson.databind.ObjectMapper}，容器里因此不存在
 * {@code com.fasterxml.jackson.databind.ObjectMapper} 这个类型的 Bean；
 * 而本项目的 RedisCacheService / SecurityConfig / SystemController 注入的是 Jackson 2 类型
 * （jjwt-jackson、springdoc 等依赖仍走 Jackson 2），启动期直接报
 * "No qualifying bean of type 'com.fasterxml.jackson.databind.ObjectMapper'"。
 * 这里补一个用途明确的 Mapper，而不是把全部业务代码迁到 Jackson 3 —— 迁移收益小、涉及面广。
 */
@Configuration(proxyBeanMethods = false)
public class Jackson2Config {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
