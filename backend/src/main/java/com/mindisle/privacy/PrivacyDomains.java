package com.mindisle.privacy;

import java.util.List;
import java.util.Optional;

/**
 * 隐私域注册表：全站「哪些表算个人信息、一行怎么归属于某个人、物理清除时怎么处置」的<b>唯一真值</b>
 * （任务 T4.21 · 需求 FR1.5/FR1.6/BR11 · 手册 §7.5）。
 *
 * <p><b>为什么要把 36 张表写进一个枚举式的注册表，而不是在删除与导出时各写一遍 SQL</b>：
 * 隐私清除与数据导出是同一件事的两面 —— 一面要「把人身上长出来的行都复制走」，
 * 一面要「把人身上长出来的行都删干净」。这两件事的<b>表清单与归属谓词必须逐字相同</b>，
 * 否则就会出现「导出的包里有 12 条评论、注销后库里还留着 3 条」这种最要命的偏差。
 * 写两遍 SQL 迟早漂移；写在这里，读侧（{@link PrivacyStore}）、导出侧
 * （{@link PrivacyExportService}）、清除侧（{@link PrivacyPurgeService}）、
 * 对账侧（{@code PrivacyDomainsTest}）共用同一份定义。</p>
 *
 * <p><b>三种 link（一行怎么归属于「正在被处理的这个人」）</b>：
 * <ul>
 *   <li>{@link Link#USER}：{@code userCols} 里任一列等于该用户 id 即归属（多列取 OR，
 *       例如 private_message 的 from_user_id 与 to_user_id —— 收信人也有删除权）；</li>
 *   <li>{@link Link#POST}：表上没有任何人列，只有 post_id。归属要绕一跳：
 *       先 {@code SELECT id FROM post WHERE user_id = ?} 算出帖子集合，再按 post_id 过滤。
 *       <b>这一步必须在删 post 行之前完成</b>，否则 post_id 集合空了，附属行就再也找不到了
 *       （{@link PrivacyPurgeService} 逐条执行这个顺序）；</li>
 *   <li>{@link Link#NONE}：全局表（话题、敏感词、系统配置），不属于任何一个人，不参与读写。</li>
 * </ul>
 *
 * <p><b>三种 action（物理清除时的处置）</b>：{@link Action#DELETE} 连行删掉；
 * {@link Action#UNBIND} 只把身份列改写成哨兵 0、行留着（留痕表的行描述的是「某次操作行为」而不是
 * 「这个人的信息」，整行删掉会破坏审计链的完整性）；{@link Action#KEEP} 完全不动。</p>
 *
 * <p><b>🔴 计数与导出都不加 {@code deleted = 0}</b>：软删行仍然是个人数据 ——
 * 库里存着多少就该复制多少、也该删除多少。Gate4 的判据是「条数与库内一致」（需求 D11），
 * 加上软删过滤就会让「导出包里的条数」比「库里的条数」少一截，而那正是最需要一致的地方。</p>
 *
 * <p><b>🔴 两处必须写明的偏差（不藏）</b>：
 * <ol>
 *   <li>{@code item_similarity} 没有任何人列（现查：只有 item_id 与 deleted，见 dev-log）。
 *       它按 {@code Link.POST} + {@code postCol = item_id} 处理，而 item_id 存的就是 post.id
 *       且一帖一行。手册说「删掉该用户的行」在这里等于「删掉该用户内容对应的相似度行」，
 *       不是「删掉该用户产生的行为行」—— 语义差别如实记录。</li>
 *   <li>{@code alert_ticket} 纳入 {@link Action#DELETE}，与需求 BR9（L2/L3 证据留存 180 天）
 *       正面冲突。裁决是「用户主动行使删除权时，删除权优先」：BR9 约束的是平台<b>单方面</b>
 *       的留存义务，不能用来对抗本人要求删除。留痕没有全丢 —— audit_task 的行按 UNBIND 保留，
 *       「本系统产生过多少条 L3」这个统计口径照样能出，只是不再指向具体的人。
 *       这条裁决是答辩必答题，见 docs/dev-log.md 阶段 4。</li>
 * </ol>
 */
public final class PrivacyDomains {

  /** 一行怎么归属于正在被处理的这个人。 */
  public enum Link {
    /** 表上有人列，按 userCols 任一列匹配。 */
    USER,
    /** 表上只有帖子列，靠该用户的帖子集合间接归属。 */
    POST,
    /** 全局表，与任何个人无关。 */
    NONE
  }

  /** 物理清除时的处置。 */
  public enum Action {
    /** 连行删除。 */
    DELETE,
    /** 身份列改写成哨兵 0（不是 NULL，理由见 {@link #UNBOUND}），行保留（审计留痕）。 */
    UNBIND,
    /** 不动。 */
    KEEP
  }

  /**
   * 一张表的登记项。
   *
   * @param table      物理表名，SQL 里唯一的表名来源
   * @param link       归属方式
   * @param userCols   {@link Link#USER} 的候选列（多列 OR）；其他 link 为空
   * @param postCol    {@link Link#POST} 的帖子列名；其他 link 为 null
   * @param unbindCols {@link Action#UNBIND} 要改写成 {@link #UNBOUND} 的列；其他 action 为空
   * @param exportable 是否进导出包。false 的三种理由写在各自的 note 里
   * @param action     清除处置
   * @param note       一句话理由（含偏差说明）
   */
  public record Domain(String table, Link link, List<String> userCols, String postCol,
      List<String> unbindCols, boolean exportable, Action action, String note) {
  }

  /**
   * 解绑哨兵值：{@link Action#UNBIND} 写进身份列的那个值。
   *
   * <p><b>为什么是 0 而不是 NULL</b>：三条留痕表里有一条的身份列是 {@code NOT NULL}
   * （{@code admin_op_log.operator_id}），写 NULL 会直接 1048 报错、清除做到一半失败；
   * 而另外两条本来就拿 NULL 表达别的业务含义（{@code audit_record.operator_id} 为 NULL = 机审、
   * {@code audit_task.assignee_id} 为 NULL = 无人认领），再拿 NULL 表达「人已注销」就会三种语义挤在一个值上。
   * 0 不是任何人的 id（{@code user.id} 是 AUTO_INCREMENT，从 1 起），所以它是一个干净的第三种状态：
   * 「这条留痕还在，但已经不指向任何人」。
   * <b>代价写明白</b>：解绑之后无法再从这三张表回答「哪个管理员处理的这一条」，
   * 这是删除权与审计留痕相互让一步的结果，不是实现缺陷（需求 BR9 与 BR11 的边界，见 dev-log）。
   */
  public static final long UNBOUND = 0L;

  /** 36 条登记项。逐表的 owner 列由 {@code sql/*.sql} 的 DDL 与开发库 information_schema 现查确认。 */
  public static final List<Domain> ALL = List.of(
      // —— 账号与授权（sql/01 · sql/13）——
      new Domain("user", Link.USER, List.of("id"), null, List.of(), false, Action.DELETE,
          "主键即 owner 列（该表没有 user_id 列）。不进导出包：导出要复制的是这个人产出的数据，"
              + "而这一行里有 BCrypt 摘要，绝不能出包；账号本身的字段由导出侧的白名单单独成节"),
      new Domain("user_profile", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "年级、简介、授权冗余位等画像字段"),
      new Domain("user_consent", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "告知—同意流水。<b>随注销物理删除</b>，平台的举证责任因此转移到用户自己导出的那一份包里 —— "
              + "这是导出包必须含它全量（含 content_version）的硬理由，不是顺手加的"),
      new Domain("anonymous_alias", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "树洞马甲代号。它本身就是「可关联到自然人」的假名，PIPL 口径下属个人信息"),
      new Domain("user_follow", Link.USER, List.of("user_id", "follow_user_id"), null, List.of(), true,
          Action.DELETE, "两列 OR：被关注的那一方同样有权消除这条「有人关注了我」的事实"),
      new Domain("user_block", Link.USER, List.of("user_id", "block_user_id"), null, List.of(), true,
          Action.DELETE, "两列 OR，理由同 user_follow"),
      new Domain("topic_follow", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "sql/13 补表"),

      // —— AI 会话（sql/02）——
      new Domain("conversation", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "会话主行"),
      new Domain("chat_message", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "逐字对话内容，是最敏感的一类"),
      new Domain("ai_call_log", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "调用日志里带 prompt 摘要与 token 数，能还原「这个人哪天问了什么」"),

      // —— 情绪（sql/03）——
      new Domain("emotion_record", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "情绪识别结果与打卡文本；敏感个人信息"),
      new Domain("weekly_report", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "周报 JSON 全文"),

      // —— 社区内容（sql/04）——
      new Domain("post", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "帖子正文。<b>清除时必须最后删这一行</b>：post_id 集合要先从它算出来"),
      new Domain("comment", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "按作者归属。别人在我帖下的评论属别人的数据，帖子行删掉后成为孤儿行 —— "
              + "这条如实记进 dev-log 的已知边界"),
      new Domain("post_like", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "点赞即偏好"),
      new Domain("post_appeal", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "申诉理由文本是本人写的"),
      new Domain("content_report", Link.USER, List.of("author_id"), null, List.of(), true, Action.DELETE,
          "🔴 owner 列是 <b>author_id</b> 而不是 user_id（现查 DDL），它是举报人；"
              + "被举报的目标靠帖子行删除后悬空去标识"),
      new Domain("post_image", Link.POST, List.of(), "post_id", List.of(), true, Action.DELETE,
          "只有帖子列"),
      new Domain("post_topic", Link.POST, List.of(), "post_id", List.of(), true, Action.DELETE,
          "帖子与话题的关联行"),
      new Domain("post_status_log", Link.POST, List.of(), "post_id", List.of(), true, Action.DELETE,
          "状态流转日志里有 operator_id（审核人），本表按帖子归属；"
              + "它记录的是「这条内容怎么被处理的」，随内容一起消失是合理的"),

      // —— 行为与推荐（sql/05）——
      new Domain("user_action", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "隐式行为埋点 + 手册 T4.19 的 ai_feedback"),
      new Domain("recommend_result", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "推荐快照能反推「系统认为这个人喜欢什么」"),
      new Domain("item_similarity", Link.POST, List.of(), "item_id", List.of(), true, Action.DELETE,
          "🔴 偏差：该表无任何 user 列，item_id 存的是 post.id。按帖子归属 = 删掉「我的内容作为相似项」的行，"
              + "而不是删掉我的行为行（行为行在 user_action）"),

      // —— 私信（sql/06，阶段 5 表，今天 0 行仍先登记）——
      new Domain("private_message", Link.USER, List.of("from_user_id", "to_user_id"), null, List.of(), true,
          Action.DELETE, "两列 OR：收信人也有删除权，只按发信人删会把收信人的收件记录留成孤儿"),

      // —— 通知（sql/08）——
      new Domain("notify_message", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "站内通知正文"),
      new Domain("notify_preference", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "通知偏好（sql/18 · 需求 FR9.4）。<b>进导出包</b>：「这个人主动关过哪几类提醒」是他对自己数据做过的决定，"
              + "注销时该一起带走；复合主键没有 id 列，而导出与清除都只按 user_id 那一列出谓词，不受影响"),

      // —— 危机工单（sql/07）——
      new Domain("alert_ticket", Link.USER, List.of("user_id"), null, List.of(), true, Action.DELETE,
          "🔴 与 BR9 的 180 天证据留存冲突，裁决为「本人删除权优先」；"
              + "统计口径靠 audit_task 的留痕行仍然出得来（见类注释第 2 条偏差）"),

      // —— 审计留痕：只解绑身份，不删行 ——
      new Domain("admin_op_log", Link.USER, List.of("operator_id"), null, List.of("operator_id"), false,
          Action.UNBIND,
          "行描述的是「某个管理员做过什么」，不是被处理人的个人信息；删行会破坏管理员行为的可追溯性。"
              + "<b>解绑写哨兵 0 而不是 NULL</b>：DDL 里 admin_op_log.operator_id 是 NOT NULL，置 NULL 会直接 1048；"
              + "而 0 不是合法的 user.id（AUTO_INCREMENT 从 1 起），也不会与 audit_record 里「机审时 operator_id 为 NULL」"
              + "、audit_task 里「未认领时 assignee_id 为 NULL」这两种既有语义撞车。操作对象与时间戳全部保留。"),
      new Domain("audit_record", Link.USER, List.of("operator_id"), null, List.of("operator_id"), false,
          Action.UNBIND, "同上：审核机与人的处置流水"),
      new Domain("audit_task", Link.USER, List.of("assignee_id"), null, List.of("assignee_id"), false,
          Action.UNBIND, "解绑的是受理人。内容行的去标识靠删掉 post 行后 target_id 悬空达成 —— "
              + "这一行是「本系统产生过多少条 L3」的统计依据，所以留行不留身份"),

      // —— 导出任务本身 ——
      new Domain("export_task", Link.USER, List.of("user_id"), null, List.of(), false, Action.DELETE,
          "不进导出包：包不能包含包自己（否则注销导出会递归地把历史导出记录卷进新包）。"
              + "清除时连产物文件一起删，见 PrivacyPurgeService"),

      // —— 全局表：与任何个人无关 ——
      new Domain("topic", Link.NONE, List.of(), null, List.of(), false, Action.KEEP,
          "话题是公共对象，创建者身份不在这一行上"),
      new Domain("sys_config", Link.NONE, List.of(), null, List.of(), false, Action.KEEP, "系统配置"),
      new Domain("sensitive_word", Link.NONE, List.of(), null, List.of(), false, Action.KEEP, "敏感词库"),
      new Domain("sensitive_word_group", Link.NONE, List.of(), null, List.of(), false, Action.KEEP,
          "敏感词分组"),
      // sql/19 第 36 表。行里只有批次统计与耗时：没有 user.id、没有帖子正文、没有命中词面，
      // 所以它和 sys_config 同一类——平台的运行记录，不属于任何一个人。
      new Domain("rec_run_log", Link.NONE, List.of(), null, List.of(), false, Action.KEEP,
          "推荐重算台账：注销导出的是一个人自己的数据，而这张表记的是平台的作业是否活着"));

  private PrivacyDomains() {
  }

  /** 按表名取登记项。找不到即 Optional.empty()，由调用方决定是抛还是跳过 —— 注册表不做兜底。 */
  public static Optional<Domain> byTable(String table) {
    return ALL.stream().filter(d -> d.table().equals(table)).findFirst();
  }

  /** 进导出包的那些表（顺序即 ALL 的顺序，导出文件的顺序跟着它，便于逐条对账）。 */
  public static List<Domain> exportable() {
    return ALL.stream().filter(Domain::exportable).toList();
  }

  /** 要连行删掉的那些表。清除侧按它遍历，<b>user 主行必须最后删</b>（见 {@link #userRow()}）。 */
  public static List<Domain> deletable() {
    return ALL.stream().filter(d -> d.action() == Action.DELETE).toList();
  }

  /** 只解绑身份的那些表。 */
  public static List<Domain> unbindable() {
    return ALL.stream().filter(d -> d.action() == Action.UNBIND).toList();
  }

  /** user 主行，物理清除的最后一步。 */
  public static Domain userRow() {
    return byTable("user").orElseThrow(() -> new IllegalStateException("注册表缺少 user 行"));
  }

  /**
   * 归属谓词需要的所有列名（供测试做「列名白名单」检查，也防止 SQL 里出现拼接的任意标识符）。
   * 全部只含小写字母、下划线，与 DDL 一致。
   */
  public static List<String> safeIdentifiers() {
    return ALL.stream()
        .flatMap(d -> d.userCols().stream())
        .filter(c -> c.matches("[a-z_][a-z0-9_]*"))
        .distinct()
        .toList();
  }
}
