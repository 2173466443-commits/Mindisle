package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 通知偏好 notify_preference（任务 T3.16 后半 · 需求 FR9.4 · 手册 §15 T3.16 · sql/18 第 35 表）。
 *
 * <p><b>一行一个开关</b>：(user_id, type) 是复合主键，缺行即「没改过、按默认接收」。
 * 这个「缺行 = 默认」的翻译只写在 {@link com.mindisle.notify.NotifyPreferenceService} 一处，
 * 别读到这行就以为库里该有八行 —— 不该有，见 {@code sql/18} 文件头第 4 条。</p>
 *
 * <p><b>本类刻意不继承 {@code BaseMapper<NotifyPreference>}</b>：MyBatis-Plus 的通用 CRUD 假设
 * 单列主键（{@code selectById} 会拼成 {@code WHERE id = ?}），这张表没有 id 列。
 * 所以 {@link com.mindisle.mapper.NotifyPreferenceMapper} 是一个只用注解 SQL 的普通 {@code @Mapper}，
 * 而 {@code @TableName} 留在这里只作为「这个类对应哪张表」的书面契约（导出与清除按表名走
 * {@link com.mindisle.privacy.PrivacyDomains}，不经过这里）。</p>
 *
 * <p><b>没有 deleted 列</b>：偏好行的命运是「UPSERT 之后一直活着，注销时连行删掉」，
 * 软删会让「关掉的开关」和「从没设过的开关」两种状态在库里长得一样，而它们在界面上要说两句话。
 * 口径与 {@code topic_follow}、{@code user_follow} 逐字相同。</p>
 */
@Data
@TableName("notify_preference")
public class NotifyPreference {

  /** 接收人，逻辑外键 user.id。只来自 JWT。 */
  private Long userId;

  /** 八类之一，取值与 {@link NotifyMessage#TYPE_LIKE} 等常量同一套（对账钉在 NotifyPreferenceServiceTest）。 */
  private String type;

  /** 1=接收 0=关闭。关闭的含义是「不落红点、不实时推」，<b>不是</b>「不写通知行」（Gate3 判据）。 */
  private Integer enabled;

  /** 最后一次改动时刻。重复保存同一个值不会被刷新，所以它说的是「改过」而不是「提交过」。 */
  private LocalDateTime updatedAt;
}
