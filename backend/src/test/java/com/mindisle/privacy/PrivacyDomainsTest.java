package com.mindisle.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.privacy.PrivacyDomains.Action;
import com.mindisle.privacy.PrivacyDomains.Domain;
import com.mindisle.privacy.PrivacyDomains.Link;

/**
 * 隐私域注册表的测试（任务 T4.21）。
 *
 * <p><b>本类最值钱的一条是第 2 组：它不跟任何常量比对，而是现场解析 {@code sql/*.sql} 的
 * CREATE TABLE 清单，和注册表做双向对账</b>。注册表是隐私清除与数据导出的唯一真值，
 * 它最大的失效方式不是「写错一条」，而是<b>「新表建好了但没人登记」</b>——那种偏差的表现形式是
 * 「注销之后库里还留着三条评论」，接口照样返回成功、日志照样干净，只有监管复查才会发现。
 * 把「库里能建几张表」这件事交给 DDL 自己回答，加表不登记就当场跑红。</p>
 *
 * <p>其余各组钉的是「每条登记项内部的自洽」：归属列是不是 DDL 里真有的那一列、
 * link 与列集合是否互相咬合、三种处置是否互斥且穷尽、导出白名单与禁止名单是否等于需求原文。
 * 这些判断散落在 SQL 拼接、导出、清除三处，任何一处跟着注释改了而注册表没改，都由这里拦住。</p>
 *
 * <p>不用 {@code @SpringBootTest}：注册表是纯静态数据，连库反而会让「对账」变成对开发库现状的依赖
 * （开发库被人删过一张表，测试就会以另一个理由跑红，而那并不是代码的问题）。</p>
 */
@DisplayName("T4.21 隐私域注册表")
class PrivacyDomainsTest {

  /** 建表清单只解析一次：解析失败要在类初始化阶段就炸，而不是让十条断言各自炸一遍。 */
  private static final Set<String> DDL_TABLES = parseCreateTablesFromSqlDir();

  // ================================================================== 解析 DDL

  private static Set<String> parseCreateTablesFromSqlDir() {
    Path dir = locateSqlDir();
    Set<String> out = new LinkedHashSet<>();
    List<Path> sqls = new ArrayList<>();
    try (Stream<Path> files = Files.list(dir)) {
      files.filter(p -> p.getFileName().toString().endsWith(".sql")).forEach(sqls::add);
    } catch (IOException e) {
      throw new AssertionError("无法读取 sql 目录 " + dir + "：" + e, e);
    }
    sqls.sort(Comparator.comparing(p -> p.getFileName().toString()));
    for (Path sql : sqls) {
      List<String> lines;
      try {
        lines = Files.readAllLines(sql);
      } catch (IOException e) {
        throw new AssertionError("无法读取 " + sql + "：" + e, e);
      }
      for (String raw : lines) {
        String line = raw.trim();
        // 注释里的示例建表语句不算数：sql/09 的注释里就抄过一段 DDL
        if (line.startsWith("--") || line.startsWith("#")) {
          continue;
        }
        String name = parseCreateTable(line);
        if (name != null) {
          out.add(name);
        }
      }
    }
    assertTrue(out.size() > 30, "从 DDL 只解析出 " + out.size()
        + " 张表，解析本身已经失效 —— 此时双向对账全绿也不能算数，必须先修解析");
    return out;
  }

  /**
   * 只认真正的建表行：形如 CREATE TABLE [IF NOT EXISTS] 表名。
   *
   * <p>刻意不用正则，也刻意不把判据放宽成「行里有 CREATE 又有 TABLE」——sql/00 里有一句
   * GRANT ... CREATE TEMPORARY TABLES, LOCK TABLES ...，按「含 TABLE」去匹配会把 S 当成表名
   * 解析出来。而对账一旦掺进假表名，「两边集合相等」这句断言就退化成比谁更宽松。这里的判据是
   * 「CREATE 之后紧跟的单词必须是 TABLE，且 TABLE 后面不许再粘字母数字」。第一次跑这个测试时
   * 解析只出 1 张表，炸的就是这两条：表名外的反引号没剥、GRANT 那行被吃进来。</p>
   */
  private static String parseCreateTable(String line) {
    String upper = line.toUpperCase(Locale.ROOT);
    if (!upper.startsWith("CREATE")) {
      return null;
    }
    int p = 6;
    while (p < upper.length() && upper.charAt(p) == 32) {
      p++;
    }
    if (!upper.startsWith("TABLE", p)) {
      return null;
    }
    p += 5;
    if (p < upper.length() && Character.isLetterOrDigit(upper.charAt(p))) {
      return null;
    }
    String rest = line.substring(p).trim();
    if (rest.toUpperCase(Locale.ROOT).startsWith("IF NOT EXISTS")) {
      rest = rest.substring("IF NOT EXISTS".length()).trim();
    }
    // 表名可能被反引号、双引号或方括号包住，先剥掉左边的包装（本仓库 DDL 一律用反引号）
    while (rest.length() > 0 && (rest.charAt(0) == 96 || rest.charAt(0) == 34 || rest.charAt(0) == 91)) {
      rest = rest.substring(1);
    }
    StringBuilder name = new StringBuilder(rest.length());
    for (int i = 0; i < rest.length(); i++) {
      char ch = rest.charAt(i);
      if (Character.isLetterOrDigit(ch) || ch == 95) {
        name.append(Character.toLowerCase(ch));
      } else {
        break;
      }
    }
    return name.length() == 0 ? null : name.toString();
  }

  /** 按 surefire 的工作目录（backend）往上级找 sql/；找不到就抛，绝不静默跳过对账。 */
  private static Path locateSqlDir() {
    Path base = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    String[] tries = { "../sql", "sql", "../../sql" };
    for (String rel : tries) {
      Path p = base.resolve(rel).normalize();
      if (Files.isDirectory(p)) {
        return p;
      }
    }
    throw new AssertionError("找不到 sql 目录（从 " + base + " 找），隐私表清单与 DDL 的对账不能被跳过");
  }

  private static Domain domain(String table) {
    Optional<Domain> found = PrivacyDomains.byTable(table);
    assertTrue(found.isPresent(), "注册表里没有这张表：" + table);
    return found.get();
  }

  private static List<String> tablesOf(List<Domain> domains) {
    List<String> out = new ArrayList<>();
    for (Domain d : domains) {
      out.add(d.table());
    }
    return out;
  }

  // ================================================================== 1. 形状

  @Test
  @DisplayName("注册表共 35 条，表名不重复，每条都写了一句理由")
  void registryShapeIsFixed() {
    assertEquals(35, PrivacyDomains.ALL.size(),
        "登记项数量变了：加表必须同时登记归属方式与处置动作，删表要同步 DDL");
    Set<String> seen = new HashSet<>();
    for (Domain d : PrivacyDomains.ALL) {
      assertFalse(d.table().isBlank(), "表名不能为空");
      assertTrue(seen.add(d.table()), "同一张表登记了两次：" + d.table());
      assertFalse(d.note() == null || d.note().isBlank(),
          d.table() + " 缺一句处置理由 —— 隐私表的每一条登记都要能被答辩时念出来");
    }
  }

  // ================================================================== 2. 与 DDL 双向对账（核心）

  @Test
  @DisplayName("库里能建出来的表与注册表逐张相等：新表不登记就当场跑红")
  void registryMatchesDdlInBothDirections() {
    List<String> onlyInDdl = new ArrayList<>();
    for (String t : DDL_TABLES) {
      if (PrivacyDomains.byTable(t).isEmpty()) {
        onlyInDdl.add(t);
      }
    }
    List<String> onlyInRegistry = new ArrayList<>();
    for (Domain d : PrivacyDomains.ALL) {
      if (!DDL_TABLES.contains(d.table())) {
        onlyInRegistry.add(d.table());
      }
    }
    assertTrue(onlyInDdl.isEmpty(), "DDL 里有、注册表里没有（这张表的行既不会被导出也不会被清除）：" + onlyInDdl
        + "；sql 目录解析自 " + locateSqlDir());
    assertTrue(onlyInRegistry.isEmpty(), "注册表里有、DDL 里没有（表名写错或已被删）：" + onlyInRegistry);
    assertEquals(DDL_TABLES.size(), PrivacyDomains.ALL.size());
  }

  // ================================================================== 3. 归属列逐条钉死

  @Test
  @DisplayName("归属列按 DDL 现查值钉死：author_id 不是 user_id，留痕表是 operator_id/assignee_id")
  void ownerColumnsArePinnedPerTable() {
    assertEquals(List.of("id"), domain("user").userCols(), "user 主行没有 user_id 列，owner 就是主键");
    assertEquals(Link.USER, domain("user").link());
    assertEquals(List.of("user_id"), domain("user_profile").userCols());
    assertEquals(List.of("author_id"), domain("content_report").userCols(),
        "content_report 的 owner 是举报人 author_id；写成 user_id 会命中不存在的列，清除时报 1054");
    assertEquals(List.of("from_user_id", "to_user_id"), domain("private_message").userCols(),
        "收信人也要能删掉自己收到的私信，只按发信人删会留下孤儿行");
    assertEquals(List.of("operator_id"), domain("admin_op_log").userCols());
    assertEquals(List.of("operator_id"), domain("audit_record").userCols());
    assertEquals(List.of("assignee_id"), domain("audit_task").userCols(),
        "audit_task 解绑的是受理人，不是被审内容的主人");
    assertEquals(List.of("user_id", "follow_user_id"), domain("user_follow").userCols());
    assertEquals(List.of("user_id", "block_user_id"), domain("user_block").userCols());
    // Link.POST：表上没有任何人列，靠帖子集合归属
    assertEquals("post_id", domain("post_image").postCol());
    assertEquals("post_id", domain("post_topic").postCol());
    assertEquals("post_id", domain("post_status_log").postCol());
    assertEquals("item_id", domain("item_similarity").postCol(),
        "🔴 已记录的偏差：item_similarity 无人列，item_id 存的是 post.id");
    assertTrue(domain("item_similarity").userCols().isEmpty(),
        "这张表要是哪天真加了 user 列，这条断言会红，提醒重新裁决归属方式");
  }

  @Test
  @DisplayName("link 与列集合互相咬合：USER 必有人列且无帖子列，POST 反之，NONE 必是全局表")
  void linkAndColumnsAgree() {
    for (Domain d : PrivacyDomains.ALL) {
      switch (d.link()) {
        case USER:
          assertFalse(d.userCols().isEmpty(), d.table() + " 标了 USER 却没有归属列");
          assertNull(d.postCol(), d.table() + " 标了 USER 就不该有帖子列");
          break;
        case POST:
          assertTrue(d.userCols().isEmpty(), d.table() + " 标了 POST 却带人列，归属谓词会两条都拼");
          assertFalse(d.postCol() == null || d.postCol().isBlank(), d.table() + " 标了 POST 却没有帖子列");
          break;
        case NONE:
          assertTrue(d.userCols().isEmpty(), d.table() + " 是全局表，不该有人列");
          assertNull(d.postCol(), d.table() + " 是全局表，不该有帖子列");
          assertEquals(Action.KEEP, d.action(), d.table() + " 是全局表，清除时必须不动它");
          assertFalse(d.exportable(), d.table() + " 是全局表，进导出包就是把别人的数据塞给这个人");
          break;
        default:
          throw new AssertionError("未预期的 link：" + d.link());
      }
    }
  }

  // ================================================================== 4. 导出白名单

  @Test
  @DisplayName("导出集 26 条、禁止集 9 条；user_consent 必须在包里，password 那行必须不在")
  void exportWhitelistMatchesRequirement() {
    List<String> exported = tablesOf(PrivacyDomains.exportable());
    assertEquals(26, exported.size(), "可导出域数量：35 条登记项减去 9 条禁止项（sql/18 的 notify_preference 跟着进导出包，一个人有权知道自己在平台上按过哪些开关）");
    assertTrue(exported.contains("user_consent"),
        "🔴 user_consent 必须随注销一起出包：这张表物理删除后，「已告知且已同意」唯一的举证副本就在用户手里");
    assertTrue(exported.contains("chat_message"));
    assertTrue(exported.contains("emotion_record"));
    List<String> forbidden = new ArrayList<>();
    for (Domain d : PrivacyDomains.ALL) {
      if (!d.exportable()) {
        forbidden.add(d.table());
      }
    }
    Set<String> expected = new HashSet<>(List.of("user", "export_task", "admin_op_log", "audit_record",
        "audit_task", "topic", "sys_config", "sensitive_word", "sensitive_word_group"));
    assertEquals(expected, new HashSet<>(forbidden),
        "禁止出包的那 9 张表逐条钉：user 因为有 password，export_task 因为包不能含包自己，"
            + "三张留痕表因为描述的是别人的操作，四张全局表因为不属于任何个人");
    assertEquals(35, exported.size() + forbidden.size());
  }

  // ================================================================== 5. 三种处置互斥且穷尽

  @Test
  @DisplayName("DELETE / UNBIND / KEEP 三分区互斥且覆盖全部 35 张表")
  void threeActionsPartitionTheRegistry() {
    List<String> del = tablesOf(PrivacyDomains.deletable());
    List<String> unbind = tablesOf(PrivacyDomains.unbindable());
    List<String> keep = new ArrayList<>();
    for (Domain d : PrivacyDomains.ALL) {
      if (d.action() == Action.KEEP) {
        keep.add(d.table());
      }
    }
    assertEquals(35, del.size() + unbind.size() + keep.size(), "三分区没覆盖全部登记项");
    Set<String> all = new HashSet<>();
    all.addAll(del);
    all.addAll(unbind);
    all.addAll(keep);
    assertEquals(35, all.size(), "三分区之间有重叠：同一张表既是删掉又是保留，清除顺序就成了运气");
    assertEquals(3, unbind.size(), "留痕表就三条，多出来的一定是有人把业务表改成了 UNBIND");
    assertTrue(unbind.containsAll(List.of("admin_op_log", "audit_record", "audit_task")));
    assertTrue(del.contains("alert_ticket"),
        "🔴 与 BR9 冲突的裁决点：alert_ticket 纳入物理清除。改回 UNBIND/KEEP 之前先回去读需求 BR9 与 PIPL 第 47 条");
    assertTrue(del.contains("post"), "post 是清除时最要紧的一张表，它不在 DELETE 里就意味着注销根本没删内容");
    for (Domain d : PrivacyDomains.ALL) {
      if (d.action() == Action.UNBIND) {
        assertFalse(d.unbindCols().isEmpty(), d.table() + " 标了 UNBIND 却没有解绑列，UPDATE 会拼不出 SET");
        assertTrue(d.userCols().containsAll(d.unbindCols()),
            d.table() + " 的解绑列必须是归属列之一，否则改写的不是筛出来的那批行");
      } else {
        assertTrue(d.unbindCols().isEmpty(), d.table() + " 不是 UNBIND 却带着解绑列");
      }
    }
  }

  @Test
  @DisplayName("user 主行：不导出、要删、且与 byTable 取回的是同一个实例")
  void userRowIsTheSpecialOne() {
    Domain byLookup = PrivacyDomains.byTable("user").orElseThrow();
    assertSame(byLookup, PrivacyDomains.userRow(), "userRow() 应当直接命中同一条登记项，而不是复制一份定义");
    assertEquals(Link.USER, PrivacyDomains.userRow().link());
    assertEquals(Action.DELETE, PrivacyDomains.userRow().action());
    assertFalse(PrivacyDomains.userRow().exportable(),
        "user 整行绝不能出包：那里面有 BCrypt 摘要。账号字段走 AccountFacts 白名单单独成节");
  }

  // ================================================================== 6. 常量与列名安全

  @Test
  @DisplayName("解绑哨兵是 0：admin_op_log.operator_id 是 NOT NULL，写 NULL 会当场 1048")
  void unbindSentinelIsZero() {
    assertEquals(0L, PrivacyDomains.UNBOUND,
        "0 不是任何人的 id（user.id 自增从 1 起），又不与「NULL=机审 / NULL=无人认领」这两种既有语义撞车");
  }

  @Test
  @DisplayName("归属列名全部是小写下划线：拼进 SQL 的标识符只能来自这份白名单")
  void safeIdentifiersAreLowerSnakeCase() {
    List<String> ids = PrivacyDomains.safeIdentifiers();
    assertFalse(ids.isEmpty());
    for (String col : ids) {
      assertTrue(col.matches("[a-z][a-z0-9_]*"), "非法标识符进了归属列清单：" + col);
    }
    assertTrue(ids.contains("user_id"));
    assertTrue(ids.contains("author_id"));
    assertTrue(ids.contains("operator_id"));
    assertTrue(ids.contains("assignee_id"));
    assertTrue(ids.contains("from_user_id") && ids.contains("to_user_id"));
  }

  @Test
  @DisplayName("查不存在的表返回空 Optional：注册表不做兜底，兜底的位置只有一个")
  void unknownTableIsNotGuessed() {
    assertTrue(PrivacyDomains.byTable("no_such_table").isEmpty());
    assertTrue(PrivacyDomains.byTable("po st").isEmpty());
  }
}
