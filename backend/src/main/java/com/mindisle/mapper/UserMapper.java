package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.User;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 账号主表 Mapper（手册 §5.2 T2.5）。
 *
 * <p>刻意不写 XML：单表条件查询用 LambdaQueryWrapper 足够，且能避免 mybatis-plus
 * mapper-locations 配错导致的「绑定不报错、调用才 500」这一类隐蔽故障。
 * 需要多表连接与聚合的复杂查询（推荐召回、审核工作台）在阶段 3 再引入 XML。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

  /**
   * 按登录名取账号。@TableLogic 会自动追加 deleted = 0，所以这里查不到即「不存在或已注销」。
   */
  default User findByUsername(String username) {
    return selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username).last("limit 1"));
  }

  /**
   * 危机工单的通知接收人（任务 T4.13，需求 FR10.6「L3 除工单外还要在线巡班 30 分钟内响应」）。
   *
   * <p><b>只选 id，不选整行</b>：这里要的只是「该通知谁」，把 password、email 一起扒进内存是白白扩大泄露面。
   * <b>role 用 IN 而不是 &lt;&gt; 'USER'</b>：将来新增角色（如 MODERATOR、VOLUNTEER）不会被读成管理员。
   * status &lt;&gt; 'DISABLED' 是必须的：给被停用的账号发危机通知等于把事故派给一个看不到的人。
   * deleted 的条件写在这里而不是靠 @TableLogic：自定义 @Select 不会被逻辑删除拦截器改写。
   */
  @Select("SELECT id FROM user WHERE role IN ('ADMIN','SUPER') AND status <> 'DISABLED' AND deleted = 0")
  List<Long> listAdminIds();

  /**
   * 冷静期已届满、等待物理清除的账号 id（任务 T4.21 · {@code DataRetentionJob} 的候选读数）。
   *
   * <p>四个条件各有各的用处，缺一个都会跑错：
   * ① {@code status = #{status}} 由调用方传 {@code DELETED}，值走占位符而不写进 SQL 文本 ——
   * 与 {@code ExportTaskMapper} 四条状态改写同一条纪律（状态值的真值在实体常量里）。
   * ② {@code purge_at IS NOT NULL} 拦住「状态是 DELETED 但算不出到期时刻」的行：
   * 把它们当成「早就到期」直接删是危险的，宁可留在库里由人工核对。
   * ③ {@code deleted = 0} 写在这里而不是靠 {@code @TableLogic}：自定义 @Select 不会被逻辑删除拦截器改写
   * （{@link #listAdminIds()} 同一个理由）。
   * ④ {@code ORDER BY purge_at ASC, id ASC} 是全序。批次上限意味着「今天没排到的明天接着排」，
   * 没有全序就可能重复处理同一批、也可能永远扫不到排在后面的那些（判据抄自
   * {@code ExportTaskMapper#listExpired}）。走 sql/15 建的 idx_status_purge(status, purge_at)。</p>
   */
  @Select("SELECT id FROM user WHERE status = #{status} AND purge_at IS NOT NULL"
      + " AND purge_at <= #{now} AND deleted = 0 ORDER BY purge_at ASC, id ASC LIMIT #{limit}")
  List<Long> listDuePurgeUserIds(String status, LocalDateTime now, int limit);

  /**
   * 撤回注销：把账号点亮，并把两个冷静期时间列<b>显式写回 NULL</b>（任务 T4.21）。
   *
   * <p>🔴 不能用 {@code updateById}：MyBatis-Plus 的默认字段策略是 NOT_NULL，
   * 值为 null 的字段不会进 SET 子句 —— 于是 {@code CoolingState#restore} 改完内存、
   * {@code updateById} 只写回 status，deactivate_at 与 purge_at 仍是上一次注销的时间戳。
   * 后果不是报错而是「看起来撤回了」：这类账号下次再注销时，{@code CoolingState#isCooling}
   * 读到一个已经过去的 purge_at，会判定冷静期已过、直接把它送进清除队列。
   * 本方法就是这个坑的唯一补丁，也是本类唯一一条 @Update。</p>
   *
   * <p>表名不带反引号：MySQL 里 USER 是非保留字（{@link #listAdminIds()} 的 FROM user 已在真实路径跑通）。
   * WHERE 带 {@code status = #{fromStatus}} 前置态，返回 0 表示这一行已被别的入口改过，调用方放弃。</p>
   */
  @Update("UPDATE user SET status = #{toStatus}, deactivate_at = NULL, purge_at = NULL"
      + " WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
  int restoreActive(long id, String fromStatus, String toStatus);
}
