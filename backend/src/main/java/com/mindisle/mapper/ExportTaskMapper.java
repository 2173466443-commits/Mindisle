package com.mindisle.mapper;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.ExportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 隐私导出任务 Mapper（任务 T4.21 · 手册 §7.5）。
 *
 * <p><b>本文件唯一需要背的规矩：状态值一律走参数绑定，SQL 文本里不出现任何字面量。</b>
 * 这不是为了防注入（`#{}` 本身就是预编译占位符，注入面本来就是零），而是为了让
 * 「PENDING / RUNNING / SUCCESS / FAILED」这四个词只存在于 {@link ExportTask} 的常量里。
 * 写在 SQL 文本里就等于在第二个地方定义了同一件事，改一处漏一处的后果是
 * 「CAS 永远返回 0 行」这种既不报错也不干活的沉默故障。</p>
 *
 * <p><b>四条状态改写全部带前置态</b>（{@code WHERE id = ? AND status = ?}），返回 0 行表示
 * 这一枪没打上：要么被另一个线程抢走了，要么任务早已是终态。调用方据此<b>直接放弃</b>，
 * 不做第二次尝试。理由与 {@code PostMapper#compareAndSetStatus} 同一条教义 —— 后台线程若不判
 * 前置态，同一个任务就会跑两遍、生成两份文件、发出两个 token，而用户在列表里只会看到最后一份。</p>
 *
 * <p><b>一张表只归这一个 Mapper</b>：不跨表读、不 join。导出要取 34 张表的数据，
 * 那些读数全部走 {@link com.mindisle.privacy.PrivacyStore} 的注册表驱动 SQL，
 * 不在这里为每张业务表开一个方法 —— 否则「新增一张表要改三个地方」会重新变成现实。</p>
 */
@Mapper
public interface ExportTaskMapper extends BaseMapper<ExportTask> {

  /**
   * 按下载口令取任务。<b>WHERE 刻意不带 user_id</b>：归属判断放在服务层，
   * 好让「这串口令不存在」与「这串口令是别人的」在响应上长得一模一样（都是 404）。
   * 若这里带上 user_id，那么别人的 token 会稳定返回 404、而自己的错 token 也返回 404，
   * 看似一样；真正的泄露发生在时序与日志上 —— 一条 SQL 命中索引、另一条不命中，
   * 就能被用来验证「这串口令是真的」。这与需求 NFR7「不给探测留缝」同源。
   */
  @Select("SELECT * FROM export_task WHERE token = #{token} AND deleted = 0")
  ExportTask findByToken(String token);

  /** 最新一条（列表首行与「上一次导出现在怎么样了」都读它）。 */
  default ExportTask findLatest(Long userId) {
    return selectOne(new LambdaQueryWrapper<ExportTask>()
        .eq(ExportTask::getUserId, userId)
        .orderByDesc(ExportTask::getId)
        .last("limit 1"));
  }

  /** 该用户当前有没有未完成的任务，用于「重复提交只排队、不重复插行」。 */
  default boolean hasUnfinished(Long userId) {
    Long n = selectCount(new LambdaQueryWrapper<ExportTask>()
        .eq(ExportTask::getUserId, userId)
        .in(ExportTask::getStatus, ExportTask.PENDING, ExportTask.RUNNING));
    return n != null && n > 0;
  }

  /** 本人历史任务，新的在前。上限夹住列表，防止导出过几百次的账号把响应体撑爆。 */
  default List<ExportTask> listByUser(Long userId, int limit) {
    return selectList(new LambdaQueryWrapper<ExportTask>()
        .eq(ExportTask::getUserId, userId)
        .orderByDesc(ExportTask::getId)
        .last("limit " + Math.max(1, Math.min(limit, 200))));
  }

  /**
   * PENDING → RUNNING。返回 0 就放弃本次执行（详见类注释）。
   * 两个状态值由调用方从 {@link ExportTask} 的常量传入，本文件不写死。
   */
  @Update("UPDATE export_task SET status = #{toStatus} WHERE id = #{id} AND status = #{fromStatus}")
  int markRunning(long id, String fromStatus, String toStatus);

  /**
   * RUNNING → SUCCESS，并把产物信息一次性写全。
   *
   * <p>{@code error_text = NULL} 是为了支持「同一行先失败后重跑成功」这种极少见的情况：
   * 成功态不允许还挂着一条上次的错误文案，否则前端要靠 status 与 error_text 谁非空来猜状态。
   * token 只在这里写入 —— 链接只在产物真的存在之后才有意义。</p>
   */
  @Update("UPDATE export_task SET status = #{toStatus}, file_path = #{filePath},"
      + " file_bytes = #{fileBytes}, row_counts = #{rowCounts}, token = #{token},"
      + " expire_at = #{expireAt}, error_text = NULL"
      + " WHERE id = #{id} AND status = #{fromStatus}")
  int markSuccess(long id, String fromStatus, String toStatus, String filePath, long fileBytes,
      String rowCounts, String token, LocalDateTime expireAt);

  /**
   * RUNNING → FAILED。清掉 token 与 expire_at：一个没有产物的任务不该留下可用的下载口令。
   * {@code errorText} 是面向用户的短句，堆栈只进日志。
   */
  @Update("UPDATE export_task SET status = #{toStatus}, error_text = #{errorText},"
      + " token = NULL, expire_at = NULL WHERE id = #{id} AND status = #{fromStatus}")
  int markFailed(long id, String fromStatus, String toStatus, String errorText);

  /**
   * 到期未取走的成功任务：定时清理器按 {@code expire_at} 升序取一批，删文件 + 软删行。
   *
   * <p>有全序 {@code ORDER BY expire_at ASC, id ASC}。批次上限意味着「今天没排到的明天接着排」，
   * 而没有全序的话，明天可能重复处理同一批、也可能永远扫不到排在后面的那些 ——
   * 与周报批次的判据同一条（见 {@code EmotionRecordMapper#listCheckinUserIds}）。</p>
   */
  @Select("SELECT * FROM export_task WHERE status = #{status} AND expire_at IS NOT NULL"
      + " AND expire_at <= #{now} AND deleted = 0"
      + " ORDER BY expire_at ASC, id ASC LIMIT #{limit}")
  List<ExportTask> listExpired(String status, LocalDateTime now, int limit);

  /** 本人某条任务；带 user_id 是给「列表里点进来的那一行」用的，与 {@link #findByToken} 的口径不同。 */
  default ExportTask findByIdAndUser(Long id, Long userId) {
    return selectOne(new LambdaQueryWrapper<ExportTask>()
        .eq(ExportTask::getId, id)
        .eq(ExportTask::getUserId, userId));
  }
}
