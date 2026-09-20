package com.mindisle.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件链（§5.3 依赖 3.5.17 的 boot4 starter 与独立的 jsqlparser 包）。
 *
 * <p>分页插件显式声明 DbType.MYSQL：不写方言时插件会走运行时推断，
 * 一旦阶段 6 引入只读实例就可能生成错误的 LIMIT 语法，这里提前定死。</p>
 */
@Configuration
public class MybatisPlusConfig {

    /** 单页上限 100，与 PageQuery 的 50 共同构成一次性拉全表的防线（NFR7）。 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
