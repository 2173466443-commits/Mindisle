package com.mindisle.pm.dto;
import java.time.LocalDateTime;
import java.util.List;
/**
 * 一个会话的一页消息（任务 T5.6 · 需求 FR6.1）。
 *
 * <p>SQL 取的是「比 beforeId 更旧的 limit 条」，顺序 id DESC；本对象里的 {@code list}
 * 已经翻回<b>时间正序</b>（前端直接渲染，不再自己 reverse）。翻转放在服务端是因为
 * 「上滑加载更早的消息」这件事只有一种正确拼法：旧的一页接在现有列表<b>前面</b>。
 * 让每个页面各自决定要不要 reverse，是同类 bug 的标准来源。</p>
 *
 * @param peerId     对方 user.id
 * @param peerName   对方展示名（昵称空时兜底成用户名，口径同帖子列表 {@code displayNameOf}）
 * @param peerAvatar 对方头像，可为 null
 * @param list       本页消息，时间正序
 * @param nextCursor 再往前翻一页的游标（本页最早那条的 id）；没有更多时为 null
 * @param hasMore    是否还有更早的消息
 * @param blocked    这一对之间是否存在拉黑（<b>任一方向</b>）；前端据此禁用输入框（T5.6）
 * @param peerOnline 对方此刻在不在线；{@code presence-enabled=false} 时恒为 false
 * @param peerLastLoginAt 对方<b>上一次登录</b>的时刻（{@code user.last_login_at}）；在线时无值。
 *                        字段名带 {@code At} 而界面写「上次登录」而不是「最后在线」，
 *                        理由写在需求 FR6.5 的实现偏离里：本阶段没有任何地方持续写入
 *                        「最后一次真正在线的时间」，把登录时间改名成在线时间是<b>造假读数</b>
 *                        （见 {@code PmService#threadPage} 注释与手册 §8 收工口径）
 */
public record PmThreadPage(Long peerId, String peerName, String peerAvatar, List<PmMessageView> list,
                           Long nextCursor, boolean hasMore, boolean blocked, boolean peerOnline,
                           LocalDateTime peerLastLoginAt) {
}
