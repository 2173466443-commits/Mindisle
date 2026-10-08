package com.mindisle.notify.dto;

import java.util.List;

/**
 * 保存通知偏好的入参（任务 T3.16 后半 · 需求 FR9.4）。
 *
 * <p><b>只有开关，没有 user_id</b>：收件人只来自 JWT（需求 BR4/A9，与点赞、关注、举报同口径），
 * 所以这个入参里没有「替别人设偏好」的位置。</p>
 *
 * <p><b>可以只提交改动的那一两格</b>，不必每次把八格全发一遍：八个开关里改一个就发一条
 * {@code toggles=[{...}]}。代价是「空提交」必须报错而不是静默成功——
 * 静默会让前端漏传字段变成「我点了保存、它说成功了、什么都没变」。</p>
 *
 * @param toggles 要改的开关，至多八格（一类一格）
 */
public record NotifyPreferenceRequest(List<Toggle> toggles) {

  /**
   * 一格开关。
   *
   * @param type    通知类型码
   * @param enabled {@code Boolean} 而不是 {@code boolean}：JSON 里缺这个字段与显式传 false
   *                是两件事，缺字段必须是「参数不合法」，不能被读成「我要关掉这一类」
   */
  public record Toggle(String type, Boolean enabled) {
  }
}
