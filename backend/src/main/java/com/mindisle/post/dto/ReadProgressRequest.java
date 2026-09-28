package com.mindisle.post.dto;

/**
 * 停留时长上报入参（任务 T3.10 · 需求 FR5.1「停留时长 ≥3s 计 1 分、完读 2 分」）。
 *
 * <p><b>为什么需要这个 body，而不是后端在 {@code GET /api/posts/{id}} 里自己判断</b>：
 * 需求给的是「停留 ≥3 秒」，而「停留」只有浏览器知道。GET 详情是一次读取，
 * 服务端能观测到的只有「有人请求过」，它无法区分「看了一眼就退出去」和「读完了整篇」。
 * 把 GET 直接记成 view 会让浏览分的口径从「真的停了 3 秒」退化成「有人打开过」，
 * 而这个退化对协同过滤是致命的：正样本会被大量无意义的打开灌满，
 * 阶段 7 的 Precision 会莫名其妙地低，且没人能从指标反推出原因。</p>
 *
 * <p>所以详情接口不写埋点，停留由前端在离开页面时 POST 一次。两个字段都可为 null，
 * 语义是「没量到」而不是 0（{@code UserActionRecorder#recordWithDwell}）。
 * 不加 {@code @Min}/{@code @Positive}：一个乱传的 durationMs 最坏也只是这一行的
 * {@code duration_ms} 是个没意义的数，不值得为它返一个 400 让前端在 unload 时收个红叉。</p>
 *
 * @param durationMs 本次在该帖上停留的毫秒数，可为 null；≥3000 才够记一条 view
 * @param completed  是否读到了正文末尾（完读，FR5.1 记 2 分）
 */
public record ReadProgressRequest(Integer durationMs, Boolean completed) {
}
