package com.mindisle.post.dto;

/**
 * 停留上报的回执（任务 T3.10）。
 *
 * <p><b>为什么要把「记没记」告诉前端</b>：这条接口最典型的失败不是报错，而是<b>够不上记分条件</b>
 * （停留不足 3 秒）。两个布尔位让开发期能在控制台直接看出「我的计时器到底有没有跨过阈值」，
 * 也让 Gate 取证能拿一条 HTTP 层判据——否则「埋点有没有生效」只剩查库一条路，
 * 而答辩现场不方便开 mysql。</p>
 *
 * <p><b>这里刻意没有「落库成功」这个字段</b>：{@code UserActionRecorder.recordXxx} 返回 void，
 * 写失败按设计只打 WARN、不上抛（见它的类注释第 1 条），所以 Controller 根本无从知道那行
 * 有没有真进了库。加一个 {@code recorded=true} 就等于承诺一个自己测不到的东西——
 * 需要确证时查 user_action 表，别在响应体里假装知道。</p>
 *
 * <p>两个位都是「本次请求的判定结果」，不是「库里的最终状态」：同一用户同一天第二次上报
 * 更长的停留会命中同一行并更新 duration_ms，此时 viewRecorded 仍是 true 而行数不变
 * （幂等口径见 {@code UserActionMapper#upsert}）。前端不该拿它做「加一分」的动画。</p>
 *
 * @param postId              哪条帖子
 * @param thresholdMs         服务端认定的停留阈值，直接回显 {@code UserActionCatalog.VIEW_MIN_DURATION_MS}
 *                            ——口径只有一份且写在服务端，前端拿它决定还要不要继续计时
 * @param viewRecorded        本次是否够格记一条 view（停留 ≥ 阈值）
 * @param readThroughRecorded 本次是否记了完读（请求里 completed 为 true）
 */
public record ReadProgressView(Long postId, Integer thresholdMs, Boolean viewRecorded,
    Boolean readThroughRecorded) {
}
