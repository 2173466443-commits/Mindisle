package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.admin.dto.DictRow;
import com.mindisle.admin.dto.WordGroupRow;
import com.mindisle.entity.SensitiveWord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 敏感词词条 Mapper（任务 T6.2 · 手册 §9.1「词库 CRUD + 热更新」）。
 *
 * <p>三条自定义 SQL 各自解决一个 {@code Wrapper} 表达不出来的问题：
 * ① {@link #findByWordAny} 要<b>绕过</b> {@code @TableLogic}，唯一键 {@code uk_word}
 * 不看 deleted 位，绕不过就会撞 1062（见实体类注释）；
 * ② {@link #pageWords} 与 {@link #listSnapshotRows} 都要 join 组表才拿得到 level/action/scope，
 * 单表 Wrapper 拿不到；③ {@link #bumpHitCnt} 是 {@code hit_cnt = hit_cnt + 1} 的原子累加，
 * 这条<b>不</b>适用「按真相表重算」那条纪律——它的真相就是「被命中过多少次」，
 * 没有别的地方可以重算出来，读改写反而会丢并发。</p>
 */
@Mapper
public interface SensitiveWordMapper extends BaseMapper<SensitiveWord> {

  /** 含软删行的按词查询（{@code @TableLogic} 会往 Wrapper 查询里自动加 deleted=0，这里必须绕开）。 */
  @Select("SELECT * FROM sensitive_word WHERE word = #{word} LIMIT 1")
  SensitiveWord findByWordAny(@Param("word") String word);

  default List<SensitiveWord> listEnabled() {
    return selectList(new LambdaQueryWrapper<SensitiveWord>()
        .eq(SensitiveWord::getDeleted, 0)
        .eq(SensitiveWord::getStatus, SensitiveWord.STATUS_ENABLED)
        .orderByAsc(SensitiveWord::getGroupId)
        .orderByAsc(SensitiveWord::getId));
  }

  /** A8 列表：join 出组的处置口径。keyword 走前缀匹配（{@code uk_word} 是索引，LIKE 'x%' 能用上）。 */
  @Select("<script>"
      + "SELECT w.id AS id, w.group_id AS group_id, g.name AS group_name, g.level AS level, "
      + "g.action AS action, g.hit_scope AS hit_scope, w.match_type AS match_type, "
      + "w.word AS word, w.hit_cnt AS hit_cnt, w.status AS status "
      + "FROM sensitive_word w JOIN sensitive_word_group g ON g.id = w.group_id "
      + "WHERE w.deleted = 0 AND g.deleted = 0 "
      + "<if test=\"groupId != null\"> AND w.group_id = #{groupId} </if>"
      + "<if test=\"status != null\"> AND w.status = #{status} </if>"
      + "<if test=\"keyword != null and keyword != ''\"> AND w.word LIKE #{like} </if>"
      + "ORDER BY w.group_id, w.id DESC LIMIT #{size} OFFSET #{offset}"
      + "</script>")
  List<DictRow> pageWords(@Param("keyword") String keyword, @Param("like") String like,
      @Param("groupId") Long groupId, @Param("status") Integer status,
      @Param("offset") long offset, @Param("size") int size);

  /** 与 {@link #pageWords} 同一套 WHERE，条件必须逐条对齐，否则翻页会数不上一致。 */
  @Select("<script>"
      + "SELECT COUNT(*) FROM sensitive_word w JOIN sensitive_word_group g ON g.id = w.group_id "
      + "WHERE w.deleted = 0 AND g.deleted = 0 "
      + "<if test=\"groupId != null\"> AND w.group_id = #{groupId} </if>"
      + "<if test=\"status != null\"> AND w.status = #{status} </if>"
      + "<if test=\"keyword != null and keyword != ''\"> AND w.word LIKE #{like} </if>"
      + "</script>")
  long countWords(@Param("keyword") String keyword, @Param("like") String like,
      @Param("groupId") Long groupId, @Param("status") Integer status);

  /**
   * 重建 6 列 TSV 快照的数据源：启用词 + 组口径，按组、id 稳定排序。
   *
   * <p>排序不是装饰：同一份数据两次重建必须得到<b>逐字节相同</b>的文件，
   * 否则 diff 会把「词没变但顺序变了」误报成词库变更，热更新的版本号也会被无谓抬高。</p>
   */
  @Select("SELECT w.id AS id, w.group_id AS group_id, g.name AS group_name, g.level AS level, "
      + "g.action AS action, g.hit_scope AS hit_scope, w.match_type AS match_type, "
      + "w.word AS word, w.hit_cnt AS hit_cnt, w.status AS status "
      + "FROM sensitive_word w JOIN sensitive_word_group g ON g.id = w.group_id "
      + "WHERE w.deleted = 0 AND w.status = 1 AND g.deleted = 0 "
      + "ORDER BY w.group_id, w.id")
  List<DictRow> listSnapshotRows();

  /** 组列表 + 实时词数（A8 左栏）。stored_word_cnt 用来暴露账本漂移。 */
  @Select("SELECT g.id AS id, g.name AS name, g.level AS level, g.action AS action, "
      + "g.hit_scope AS hit_scope, g.word_cnt AS stored_word_cnt, g.remark AS remark, "
      + "SUM(CASE WHEN w.status = 1 THEN 1 ELSE 0 END) AS word_cnt "
      + "FROM sensitive_word_group g LEFT JOIN sensitive_word w "
      + "  ON w.group_id = g.id AND w.deleted = 0 "
      + "WHERE g.deleted = 0 GROUP BY g.id ORDER BY g.level, g.id")
  List<WordGroupRow> listGroups();

  /** 命中累加（人工确认为真命中时调用；空列表直接跳过，不拼 IN()）。 */
  @Update("<script>UPDATE sensitive_word SET hit_cnt = hit_cnt + 1, updated_at = #{now} "
      + "WHERE deleted = 0 AND word IN "
      + "<foreach collection=\"words\" item=\"w\" open=\"(\" separator=\",\" close=\")\">#{w}</foreach>"
      + "</script>")
  int bumpHitCnt(@Param("words") List<String> words, @Param("now") LocalDateTime now);

  /** 停用词启用（不是删除重建，hit_cnt 要留着算误报率）。 */
  @Update("UPDATE sensitive_word SET status = #{status}, updated_at = #{now} "
      + "WHERE id = #{id} AND deleted = 0")
  int updateStatus(@Param("id") long id, @Param("status") int status,
      @Param("now") LocalDateTime now);

  /**
   * 救活一条被软删的同名词条：唯一键不看 deleted 位，不救活就只能报「这个词已存在」，
   * 而管理员看得见的是「列表里没有这个词」，两边各说各话。
   */
  @Update("UPDATE sensitive_word SET deleted = 0, status = 1, group_id = #{groupId}, "
      + "match_type = #{matchType}, hit_cnt = 0, updated_at = #{now} WHERE id = #{id}")
  int resurrect(@Param("id") long id, @Param("groupId") long groupId,
      @Param("matchType") String matchType, @Param("now") LocalDateTime now);
}