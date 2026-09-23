package com.mindisle.topic.dto;

/**
 * 创建话题入参（任务 3.8 · 手册 §6.1 行 3.8 · 需求 FR1.7「用户可创建兴趣话题」）。
 *
 * <p>不加 {@code @NotBlank/@Size}：与 {@code ReportRequest}、{@code CreatePostRequest} 同一口径
 * —— 上限走配置（NFR10 不许在业务代码里写死，而注解值必须是编译期常量），
 * 且「参数不对」这句话只由服务层说一次、说成一句能直接念给用户的话。</p>
 *
 * @param name 话题名，去首尾空白后不能为空，上限 {@code mindisle.topic.max-name-chars}
 * @param desc 话题简介，可空（DDL 给的是 {@code NOT NULL DEFAULT ''}），上限
 *             {@code mindisle.topic.max-desc-chars}
 */
public record TopicCreateRequest(String name, String desc) {
}
