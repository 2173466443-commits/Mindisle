package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 用户资料与偏好 user_profile（需求 §7.2 #2 · 手册 §5.1 表 2）。
 *
 * <p>与 user 一对一：主键 user_id 与 user.id 同值，因此主键策略是 INPUT 而不是 AUTO。
 *
 * <p>interest_tags 在库里是 JSON 列，这里按 String 存取（写入合法 JSON 数组字符串，读取后由业务层解析），
 * 避免为单个字段引入 JsonTypeHandler 配置项——记入 dev-log 偏差。
 */
@Data
@TableName("user_profile")
public class UserProfile {

  /** 与 user.id 同值，注册时一并插入。 */
  @TableId(value = "user_id", type = IdType.INPUT)
  private Long userId;

  /** 年级：FRESH / SOPH / JUNIOR / SENIOR / OTHER，推荐冷启动分群用。 */
  private String grade;

  /** 学校院系，仅用于同校话题聚合，可留空。 */
  private String school;

  /** 性别：M / F / OTHER / UNSET（敏感字段，须单独同意后方可采集）。 */
  private String gender;

  /** 冷启动兴趣标签，JSON 数组字符串，如 ["考研","音游"]。 */
  private String interestTags;

  /** 个性签名，同样要过内容安全链。 */
  private String bio;

  /** 新手引导是否完成（U1）。 */
  private Integer onboardingDone;

  /** 情绪内容分享单独同意；撤回时置 0 并写 user_consent。 */
  private Integer emotionShareConsent;

  /** 曾触发 L2/L3 的脱敏标记，仅计数不存内容。 */
  private Integer riskFlag;

  /** 关注数（Redis 计数定期回写，BR2）。 */
  private Integer followingCnt;

  /** 粉丝数。 */
  private Integer followerCnt;

  /** 发帖数。 */
  private Integer postCnt;

  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
