package com.mindisle.notify.dto;

import java.util.List;

/**
 * 标记已读的入参（任务 T3.11-b · 需求 FR9.2「一键已读」）。
 *
 * <p>两个字段是<b>二选一</b>而不是可以并存：{@code all=true} 时 ids 被忽略，
 * 因为「我把这一批点掉了，顺便也全标了吧」这种混合语义在重试时无法自证幂等，
 * 也不该让前端去猜优先级。</p>
 *
 * @param ids 要点掉的通知 id 列表（来自列表接口，上限 100 条一次）
 * @param all true 表示「全部已读」
 */
public record MarkReadRequest(List<Long> ids, Boolean all) {
}
