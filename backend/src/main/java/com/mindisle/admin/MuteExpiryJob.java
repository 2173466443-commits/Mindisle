package com.mindisle.admin;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mindisle.admin.AdminOpLogService.Ctx;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AdminOpLog;
import com.mindisle.mapper.UserMapper;
import com.mindisle.notify.NotifyService;

/**
 * 禁言到期自动解言（任务 T6.4 · 需求 FR8.3 的「到期自动恢复」半句）。
 *
 * <p><b>判据不在这里，在 {@link com.mindisle.post.PostingQuotaService#muteActiveNow}</b>：
 * 用户能不能发帖读的是「status=MUTED 且 mute_until 还没到」，所以这个作业迟到一秒都不会多关一个人一秒。
 * 作业存在的意义是<b>把库里的事实追平成它声称的样子</b>——不追平的话，
 * 「A6 用户管理」会列出一堆状态为 MUTED 却早已过期的账号，管理员会以为还有人没处理，
 * 而统计口径（被封禁/禁言账号数）也会一直虚高。</p>
 *
 * <p>通知当事人是刻意的：禁言是有始有终的处分，不告而解会让用户以为自己还得等着，
 * 而写闸门已经放开了——同一次处分给出两个互相矛盾的答案，比多一条通知贵得多。</p>
 */
@Component
public class MuteExpiryJob {

  private static final Logger log = LoggerFactory.getLogger(MuteExpiryJob.class);

  /** 单轮上限，配置只能调小：解言是一次 UPDATE + 一条通知 + 一条审计，不该在演示时间连做几百次。 */
  static final int MAX_BATCH = 200;

  /** 作业身份：operator_id 留空（不是人干的），role 写 SYSTEM，user_agent 写作业名，审计里一眼分辨自动与手动。 */
  static final Ctx JOB_CTX = new Ctx(null, "SYSTEM", null, "MuteExpiryJob");

  private final UserMapper userMapper;
  private final NotifyService notifyService;
  private final AdminOpLogService opLogService;
  private final MindisleProperties properties;

  public MuteExpiryJob(UserMapper userMapper, NotifyService notifyService,
      AdminOpLogService opLogService, MindisleProperties properties) {
    this.userMapper = userMapper;
    this.notifyService = notifyService;
    this.opLogService = opLogService;
    this.properties = properties;
  }

  /** 一轮解言。单个账号失败只影响它自己，其余继续（积压不会因为一个人卡住）。 */
  @Scheduled(cron = "${mindisle.schedule.mute-expiry-cron:20 * * * * ?}")
  public void release() {
    MindisleProperties.Schedule schedule = properties.getSchedule();
    if (!schedule.isMuteExpiryEnabled()) {
      log.info("禁言到期解言已被配置关闭（mindisle.schedule.mute-expiry-enabled=false），本轮跳过");
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    int limit = Math.min(Math.max(schedule.getMuteExpiryBatchLimit(), 1), MAX_BATCH);
    List<Long> ids = userMapper.listExpiredMuteIds(now, limit);
    if (ids.isEmpty()) {
      return;
    }
    int done = 0;
    for (Long id : ids) {
      try {
        if (userMapper.releaseMute(id, "MUTED", "ACTIVE", now) == 1) {
          notifyService.notifyAccountAction(id, "你的发布限制已到期解除",
              "禁言期已结束，你可以重新发帖、评论与发私信。社区欢迎你把接下来的记录写下来。");
          opLogService.success(JOB_CTX, AdminOpLog.ACTION_UNMUTE_USER, "user:" + id, id,
              "到期自动解言|判据时刻=" + now);
          done++;
        }
      } catch (RuntimeException e) {
        log.error("账号 {} 到期解言失败，跳过（下一轮重试）：{}", id, e.toString());
      }
    }
    log.info("禁言到期解言：候选 {} 个，实际解除 {} 个", ids.size(), done);
  }
}