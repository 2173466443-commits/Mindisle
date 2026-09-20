package com.mindisle.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.mindisle.cache.CacheService;
import com.mindisle.ratelimit.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 装配：限流拦截器与上传目录静态映射。
 *
 * <p>限流用 HandlerInterceptor 而不是 Filter，因为这里需要 JwtAuthFilter 已解析出的登录身份；
 * 静态映射对应 §6.1 任务 3.1 的图片访问路径 /uploads/**，与安全白名单保持一致。</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final CacheService cacheService;
    private final MindisleProperties properties;

    public WebMvcConfig(CacheService cacheService, MindisleProperties properties) {
        this.cacheService = cacheService;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(cacheService, properties)).addPathPatterns("/api/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(properties.getUpload().getDir()).toAbsolutePath().normalize();
        registry.addResourceHandler("/uploads/**").addResourceLocations(dir.toUri().toString());
    }
}
