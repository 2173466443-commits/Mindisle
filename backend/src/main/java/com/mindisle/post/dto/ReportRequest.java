package com.mindisle.post.dto;

import java.util.List;

/**
 * 提交举报入参（任务 T3.11 · 需求 FR4.7 · 接口清单见需求 §9.1「{@code POST /posts/{id}/actions}」）。
 *
 * <p>不加 {@code @NotBlank/@Size}：与 {@link CreatePostRequest}、{@link CommentCreateRequest}
 * 同一口径——上限走配置（NFR10 不许在业务代码里写死，而注解值必须是编译期常量），
 * 且报错要成一句能直接念给用户的话，校验注解的字段错误会走
 * {@code GlobalExceptionHandler} 的另一条分支，同一个「参数不对」出现两种 msg 格式。</p>
 *
 * @param reason       理由分类，六选一：spam / abuse / sexual / privacy / self-harm / other
 *                     （取值集合由 {@code ReportService.REASONS} 唯一决定，见其注释与手册 §6.5 第 6 条）
 * @param description  补充描述，可空；上限走 {@code mindisle.report.max-description-chars}
 * @param evidenceUrls 截图证据的相对地址（先走 {@code POST /api/files/image} 拿到 url 再回传），
 *                     只收本站 {@code /uploads/} 下的路径，张数上限走配置
 */
public record ReportRequest(String reason, String description, List<String> evidenceUrls) {
}
