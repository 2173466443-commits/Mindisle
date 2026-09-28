package com.mindisle.privacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.ExportTask;
import com.mindisle.entity.User;
import com.mindisle.mapper.ExportTaskMapper;
import com.mindisle.mapper.UserMapper;
import com.mindisle.privacy.PrivacyDomains.Domain;
import com.mindisle.security.JwtService;

/**
 * 冷静期届满后的物理清除（任务 T4.21 · 需求 FR1.6「到期自动删除」、BR11 · 手册 §7.5 状态机的 purged 一档）。
 *
 * <p><b>🔴 六步顺序不可调换，每一步的位置都有理由</b>：
 * <ol>
 *   <li>先算 {@code post} 集合 —— 第 3 步里那些只有帖子列的表（{@code post_image} / {@code post_topic} /
 *       {@code post_status_log} / {@code item_similarity}）靠它归属。反过来先删 {@code post} 主行，
 *       集合就空了，附属行再也找不到主人，变成一批「没有作者、也删不掉」的孤儿内容；</li>
 *   <li>再删导出产物<b>文件</b>，且必须排在删 {@code export_task} 行之前 —— {@code file_path} 只存在那一行里，
 *       行没了文件就再也无人记账。反过来的失败模式是「盘上躺着一个装满某人全部数据的 zip，
 *       而数据库里没有任何一条记录说它是谁的」，这比多留一行脏数据严重得多；</li>
 *   <li>逐 {@link PrivacyDomains.Action#DELETE} 表删行（跳过 {@code user} 主行）；</li>
 *   <li>最后删 {@code user} 主行 —— 它是其余一切的归属锚点，只要它还在，前面任何一步失败都能重试；
 *       它一删，这个人就不存在了；</li>
 *   <li>解绑三条审计留痕表的身份列（行保留，理由见 {@link PrivacyDomains#UNBOUND}）；</li>
 *   <li>作废会话令牌。</li>
 * </ol>
 *
 * <p><b>为什么整套动作放进一个事务</b>：物理清除最怕「删了一半」。一半的清除会留下两种
 * 谁都不认的数据 —— 人的行还在、帖子没了（看起来像被盗号），或帖子没了、评论还在（评论区里
 * 出现一个不存在的作者）。回滚的代价是这个人今晚没清除成功、明晚重试，而它<b>可观测</b>：
 * 到期队列里他还在，批次失败数会 +1。取舍写明白：<b>可用性换一致性</b>。
 * 唯一的例外是产物文件 —— 文件系统不参与事务，所以第 2 步失败时本类<b>抛出去让事务回滚</b>，
 * 于是「产物文件还锁着打不开」这个情况会让清除整批不生效并重试。选它而不是「记一条 warn 继续删行」，
 * 因为后者会精确地制造上面说的那个孤儿包。</p>
 *
 * <p><b>缓存与 Redis 侧的口径（不装作已经清干净）</b>：{@code CacheService} 刻意没有 {@code keys/scan}
 * 能力（一个能全库扫描的缓存入口本身就是事故面），所以这里不承诺「清缓存」。逐条说清：
 * ① 唯一持久的按人 key 是 {@code user:token:{id}}（{@code JwtService.WHITELIST_PREFIX}），第 6 步 {@code revoke} 已经覆盖；
 * ② 限流键 {@code rl:ai:{bucket}:u{id}} 与登录失败键 {@code login:fail:{ip}:{username}} 都带 TTL，
 * 而 id 已经不存在，最坏情况是计数器多活几分钟到几小时，随自然过期消失；
 * ③ MySQL 全文索引随行删除自动失效，无需单独处理。这一段之所以写进注释，是因为「删了行但缓存里还有」
 * 是这类系统最容易被忽略的偏差，答辩问到时不能只回答「我们用了 Redis」。</p>
 *
 * <p><b>两处必须说出口的偏差</b>（详见 dev-log）：BR9 要求危机证据留存 180 天，而 PIPL 第 47 条把本人删除权
 * 排在前面，本项目裁决为<b>本人删除权优先</b> —— {@code alert_ticket} 纳入物理清除，
 * 「本系统产生过多少条 L3」这类统计口径改由 {@code audit_task} 的留痕行（已去身份）承担；
 * 另一处是 {@code user_consent} 随之物理删除，「已告知且已同意」的举证责任转移到<b>用户自己导出的那一份包</b> ——
 * 这正是导出包必须包含授权流水的硬理由。</p>
 */
@Service
public class PrivacyPurgeService {

  private static final Logger log = LoggerFactory.getLogger(PrivacyPurgeService.class);

  /** 一个账号最多清理多少条导出产物（与 {@code ExportTaskMapper#listByUser} 内部的 200 上限一致）。 */
  private static final int EXPORT_TASK_SCAN_LIMIT = 200;

  /**
   * 一次清除的账。这些数字是 Gate4「清除后逐表复查为 0」的直接证据，也是管理端唯一能看见的东西 ——
   * 清除完成后这个人已经不存在了，任何只写进日志而不返回的计数都无法被复核。
   *
   * @param tablesTouched 真的删掉了至少一行的表数（0 是合法结果：一个从没发过帖的账号本来就没几张表可删）
   * @param rowsDeleted   跨表删掉的总行数，<b>含</b> {@code user} 主行那 1 行
   * @param rowsUnbound  解绑身份而保留的行数
   * @param filesRemoved 实际从盘上删掉的导出产物文件数
   */
  public record Result(long userId, int tablesTouched, int rowsDeleted, int rowsUnbound,
      int filesRemoved, long costMillis) {
  }

  private final PrivacyStore store;
  private final UserMapper userMapper;
  private final ExportTaskMapper taskMapper;
  private final JwtService jwtService;
  private final MindisleProperties properties;

  public PrivacyPurgeService(PrivacyStore store, UserMapper userMapper, ExportTaskMapper taskMapper,
      JwtService jwtService, MindisleProperties properties) {
    this.store = store;
    this.userMapper = userMapper;
    this.taskMapper = taskMapper;
    this.jwtService = jwtService;
    this.properties = properties;
  }

  /**
   * 清除一个人。
   *
   * <p><b>入口先做一次「是否真的到期」复核</b>，而不信任调用方给的 id：候选读数由
   * {@code UserMapper#listDuePurgeUserIds} 产生，从读数到执行之间可能有几秒到几分钟，
   * 而这几秒里那个人完全可能登录（冷静期内登录 = 自动撤回注销）。
   * 少这一道校验的后果不是报错而是「把刚撤回注销的人删了」——那是一条不可逆的错误，
   * 也是本类唯一一处值得为「多一次 selectById」付费的地方。</p>
   */
  @Transactional(rollbackFor = Exception.class)
  public Result purgeOne(long userId) {
    long begin = System.currentTimeMillis();
    requireDue(userId);

    List<Long> postIds = store.postIdsOf(userId);
    int filesRemoved = deleteExportArtifacts(userId);

    Domain userRow = PrivacyDomains.userRow();
    int tablesTouched = 0;
    int rowsDeleted = 0;
    for (Domain domain : PrivacyDomains.deletable()) {
      if (domain.table().equals(userRow.table())) {
        continue;
      }
      int n = store.deleteRows(domain, userId, postIds);
      rowsDeleted += n;
      if (n > 0) {
        tablesTouched++;
      }
    }
    int ownRow = store.deleteRows(userRow, userId, List.of());
    rowsDeleted += ownRow;
    if (ownRow == 0) {
      // 不是错误：说明这一行在 requireDue 之后被别的入口删掉了。附属行已经删干净，
      // 结果仍然符合「这个人不存在」。之所以只 warn 而不抛，是为了不把一次幂等的重复清除
      // 变成一条永远失败的批次（重试同样得到 0）；但把它记进日志，是为了留下「为什么这次少一行」的线索。
      log.warn("物理清除：user 主行未被删除（可能已被并发清除）id={}", userId);
    }

    int rowsUnbound = 0;
    for (Domain domain : PrivacyDomains.unbindable()) {
      rowsUnbound += store.unbind(domain, userId);
    }

    jwtService.revoke(userId);
    long cost = System.currentTimeMillis() - begin;
    log.info("物理清除完成 id={} 表数={} 删行={} 解绑={} 删产物={} 耗时={}ms"
        + "（postIds={} 条）", userId, tablesTouched, rowsDeleted, rowsUnbound, filesRemoved, cost,
        postIds.size());
    return new Result(userId, tablesTouched, rowsDeleted, rowsUnbound, filesRemoved, cost);
  }

  /**
   * 删除该用户所有导出任务的产物文件。
   *
   * <p><b>只删导出目录里的文件</b>：{@code file_path} 是库里的一个字符串，而不是一个受控的标识符 ——
   * 一旦它被写坏（或有人直接改库），拿着它去 {@code Files.delete} 就等于让删除动作跟着数据走。
   * 越界的 path 一律视为「这次清除不能继续」并抛出，而不是跳过：跳过会让 {@code export_task} 行照常被删、
   * 而那个位于别处的文件永久失去记录。</p>
   */
  private int deleteExportArtifacts(long userId) {
    Path exportDir = Paths.get(properties.getPrivacy().getExportDir())
        .toAbsolutePath().normalize();
    int removed = 0;
    for (ExportTask task : taskMapper.listByUser(userId, EXPORT_TASK_SCAN_LIMIT)) {
      String stored = task.getFilePath();
      if (stored == null || stored.isBlank()) {
        continue;
      }
      Path file;
      try {
        file = Paths.get(stored).toAbsolutePath().normalize();
      } catch (RuntimeException e) {
        throw new IllegalStateException("导出产物路径无法解析，清除中止 id=" + userId
            + " task=" + task.getId() + " 原因=" + e.getMessage(), e);
      }
      if (!file.startsWith(exportDir)) {
        throw new IllegalStateException("导出产物路径不在导出目录内，清除中止 id=" + userId
            + " task=" + task.getId());
      }
      try {
        if (Files.deleteIfExists(file)) {
          removed++;
        }
      } catch (IOException e) {
        throw new IllegalStateException("导出产物文件删不掉，清除中止 id=" + userId
            + " task=" + task.getId() + " 原因=" + e.getMessage(), e);
      }
    }
    return removed;
  }

  /** 到期才允许清除：状态是 DELETED、purge_at 有值且不晚于现在。三个条件与候选 SQL 逐条对应。 */
  private void requireDue(long userId) {
    if (userId <= 0) {
      throw new BizException(ErrorCode.PARAM_INVALID, "缺少有效的用户 id");
    }
    User user = userMapper.selectById(userId);
    if (user == null) {
      throw new BizException(ErrorCode.USER_NOT_FOUND);
    }
    if (!CoolingState.DELETED.equals(user.getStatus())) {
      throw new BizException(ErrorCode.PARAM_INVALID, "该账号未处于注销冷静期，不能执行物理清除");
    }
    if (user.getPurgeAt() == null || user.getPurgeAt().isAfter(LocalDateTime.now())) {
      throw new BizException(ErrorCode.PARAM_INVALID, "冷静期尚未届满，不能执行物理清除");
    }
  }
}
