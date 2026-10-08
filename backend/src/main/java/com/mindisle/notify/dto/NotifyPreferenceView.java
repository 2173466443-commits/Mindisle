package com.mindisle.notify.dto;

/**
 * 通知偏好的一个开关（任务 T3.16 后半 · 需求 FR9.4）。
 *
 * <p><b>八格一次全给</b>：读接口不会因为「这一类你没改过」就少一格——界面要画的是
 * 「你现在能调的所有开关长什么样」，缺格会让界面少画两行而没人能发现。
 * 缺行的翻译（没改过 = 接收）只在服务层做一次。</p>
 *
 * @param type       通知类型码（like / comment / follow / pm / system / audit / crisis / report）
 * @param label      中文标签，复用 {@code NotifyService.typeLabel}，前端不再维护第二份映射
 * @param enabled    当前是否接收
 * @param locked     true 表示这一格<b>不允许关</b>（审核结果 / 危机关怀 / 系统三档，需求 FR9.4 与 §15 T3.16）
 * @param lockReason 这一格的说明句，界面直接念、不另编：置灰的三档说的是<b>为什么不能关</b>；
 *                可关的 pm 与 report 说的是<b>关掉之后会发生什么</b>（提醒没了、记录照旧进列表）。
 *                其余可关的格子为 null —— 语义在 label 上已经读得出来，多一句就是多一行没人看的灰字
 */
public record NotifyPreferenceView(String type, String label, boolean enabled, boolean locked,
        String lockReason) {
}
