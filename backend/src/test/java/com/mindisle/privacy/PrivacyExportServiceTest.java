package com.mindisle.privacy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.ExportTask;
import com.mindisle.entity.User;
import com.mindisle.mapper.ExportTaskMapper;
import com.mindisle.mapper.UserMapper;
import com.mindisle.privacy.PrivacyDomains.Domain;

/**
 * 个人信息导出的单测（任务 T4.21 · 需求 FR1.5/FR1.6/D11）。
 *
 * <p>被测的是一条<b>异步</b>链路：提交 → PENDING → 后台跑 → 成功才发口令。测试里执行器换成
 * {@code Runnable::run}（走包级构造的第 6 个参数），于是一次 {@code submit} 返回时任务已经跑完 ——
 * 「提交即完成」这句话因此可以被直接断言，而不是靠轮询加 sleep（那种写法红的时候没人知道是
 * 逻辑错了还是机器慢了）。要测「还在 PENDING 时重复提交」就把执行器换成一个只攒不跑的，
 * 两种执行器各对应一组用例。</p>
 *
 * <p>读数端口用内存假实现，Mapper 用 JDK {@code Proxy} 手写替身 ——
 * <b>不连真库、不引 mock 框架</b>（仓库里 38 个测试类无一处用 mock 框架，这里跟着走）。
 * 替身的 {@code markSuccess} 会真的按 CAS 语义改内存行，因为本类最值钱的三条断言
 * （口令只在成功时写入、状态被别人改过时产物要回收、过期的下载链接要作废那一行）
 * 全都取决于 CAS 返回值，替身若无条件返 1，这三条就都是假的。</p>
 *
 * <p>断言的口味：① <b>包里没有 password</b>不是「记得排除」，而是「没有任何路径能把它带出去」，
 * 所以既查字段名也查摘要原文；② <b>条数可对账</b>（D11）要求截断本身可见，于是恰好等于上限
 * 也要记进 {@code _truncated}；③ <b>CSV 公式注入</b>是导出场景特有的风险，
 * 审核员用 Excel 打开这个包时 {@code =HYPERLINK(...)} 会被执行，所以钉住「单元格首个非空字符是单引号」。
 * 替身一律「未预期的调用就抛」，防止测了半天其实测的是空气。</p>
 */
@DisplayName("T4.21 个人信息导出")
class PrivacyExportServiceTest {

  private static final long UID = 42L;

  /** 双引号与换行造自字符码：CSV 断言里满是转义引号会让「引号奇偶」这类自检彻底失效。 */
  private static final String DQ = String.valueOf((char) 34);

  private static final String NL = String.valueOf((char) 10);

  /** 单引号：CSV 公式防护给它加的前缀，同样只由字符码造出来。 */
  private static final String SQ = String.valueOf((char) 39);

  private static final String SECRET = "Sup3rS3cret-ShouldNeverLeave";

  // ============================================================ 内存假读数端口

  private static final class FakeStore implements PrivacyStore {

    private final Map<String, List<Map<String, Object>>> byTable = new LinkedHashMap<>();

    private final List<String> readTables = new ArrayList<>();

    private final List<Integer> limits = new ArrayList<>();

    private final List<Long> postIds = new ArrayList<>(List.of(11L, 12L, 13L));

    /** 非 0 表示第 N 次读数抛错，用来测失败分支。 */
    private int failOnNthRead;

    private int reads;

    private int postIdsCalls;

    void put(String table, List<Map<String, Object>> rows) {
      byTable.put(table, rows);
    }

    void failOn(int nth) {
      failOnNthRead = nth;
    }

    private List<Map<String, Object>> rowsOf(String table) {
      List<Map<String, Object>> rows = byTable.get(table);
      return rows == null ? List.of() : rows;
    }

    @Override
    public List<Long> postIdsOf(long userId) {
      postIdsCalls++;
      return postIds;
    }

    @Override
    public List<Map<String, Object>> selectRows(Domain domain, long userId, List<Long> postIds, int limit) {
      reads++;
      if (failOnNthRead > 0 && reads == failOnNthRead) {
        throw new IllegalStateException("读数中断：连接被重置");
      }
      readTables.add(domain.table());
      limits.add(limit);
      return rowsOf(domain.table());
    }

    @Override
    public long countRows(Domain domain, long userId, List<Long> postIds) {
      return rowsOf(domain.table()).size();
    }

    @Override
    public int deleteRows(Domain domain, long userId, List<Long> postIds) {
      throw new UnsupportedOperationException("导出链路绝不允许删数据：" + domain.table());
    }

    @Override
    public int unbind(Domain domain, long userId) {
      throw new UnsupportedOperationException("导出链路绝不允许改数据：" + domain.table());
    }
  }

  /**
   * export_task 的内存替身。<b>四条状态改写全部按前置态判定</b>（CAS），因为本类最值钱的三条断言
   * —— 口令只在成功那一刻写入、抢锁失败就放弃、成功时状态已变则回收产物 ——
   * 全看返回 0 还是 1。替身若无条件返 1，这三条分支就永远走不到，测试会绿得很空洞。
   */
  private static final class FakeTasks {

    private final List<ExportTask> rows = new ArrayList<>();

    private final List<String> calls = new ArrayList<>();

    /** 有没有未完成任务（hasUnfinished 的应答），用来测「重复提交不插第二行」。 */
    private boolean unfinished;

    private int markRunningResult = 1;

    private int markSuccessResult = 1;

    /** findByToken 的应答，与 rows 脱钩：下载闸要能单独造「别人的口令」「过期」这些形状。 */
    private ExportTask tokenHit;

    private int deletes;

    private long seq;

    private ExportTask find(Long id) {
      if (id == null) {
        return null;
      }
      for (ExportTask t : rows) {
        if (id.equals(t.getId())) {
          return t;
        }
      }
      return null;
    }

    boolean wasCalled(String name) {
      return calls.contains(name);
    }

    int countOf(String name) {
      int n = 0;
      for (String c : calls) {
        if (c.equals(name)) {
          n++;
        }
      }
      return n;
    }

    ExportTaskMapper mapper() {
      return (ExportTaskMapper) Proxy.newProxyInstance(ExportTaskMapper.class.getClassLoader(),
          new Class<?>[] { ExportTaskMapper.class }, (proxy, method, args) -> handle(method.getName(), args));
    }

    private Object handle(String name, Object[] args) {
      calls.add(name);
      switch (name) {
        case "insert": {
          ExportTask t = (ExportTask) args[0];
          t.setId(++seq);
          rows.add(t);
          return 1;
        }
        case "selectById":
          return find((Long) args[0]);
        case "markRunning": {
          ExportTask t = find((Long) args[0]);
          if (t == null || markRunningResult == 0 || !args[1].equals(t.getStatus())) {
            return 0;
          }
          t.setStatus((String) args[2]);
          return 1;
        }
        case "markSuccess": {
          ExportTask t = find((Long) args[0]);
          if (t == null || markSuccessResult == 0 || !args[1].equals(t.getStatus())) {
            return 0;
          }
          t.setStatus((String) args[2]);
          t.setFilePath((String) args[3]);
          t.setFileBytes((Long) args[4]);
          t.setRowCounts((String) args[5]);
          t.setToken((String) args[6]);
          t.setExpireAt((LocalDateTime) args[7]);
          t.setErrorText(null);
          return 1;
        }
        case "markFailed": {
          ExportTask t = find((Long) args[0]);
          if (t == null || !args[1].equals(t.getStatus())) {
            return 0;
          }
          t.setStatus((String) args[2]);
          t.setErrorText((String) args[3]);
          t.setToken(null);
          t.setExpireAt(null);
          return 1;
        }
        case "findByToken":
          return tokenHit;
        case "deleteById":
          deletes++;
          if (args[0] instanceof Long) {
            ExportTask gone = find((Long) args[0]);
            if (gone != null) {
              gone.setDeleted(1);
            }
          }
          return 1;
        case "hasUnfinished":
          return unfinished;
        case "findLatest":
          return rows.isEmpty() ? null : rows.get(rows.size() - 1);
        case "listByUser": {
          List<ExportTask> out = new ArrayList<>(rows);
          java.util.Collections.reverse(out);
          return out;
        }
        case "findByIdAndUser":
          return null;
        case "listExpired":
          return new ArrayList<ExportTask>();
        case "toString":
          return "ExportTaskMapper$Fake";
        case "hashCode":
          return getClass().hashCode();
        case "equals":
          return false;
        default:
          throw new UnsupportedOperationException("本用例不预期的调用：" + name);
      }
    }
  }

  /** 只攒不跑的执行器：用来测「任务还停在 PENDING 时重复提交」。 */
  private static final class QueuedExecutor implements Executor {

    private final List<Runnable> pending = new ArrayList<>();

    @Override
    public void execute(Runnable command) {
      pending.add(command);
    }

    int size() {
      return pending.size();
    }

    void drain() {
      while (!pending.isEmpty()) {
        pending.remove(0).run();
      }
    }
  }

  // ============================================================ 装配助手

  private static Map<String, Object> row(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      m.put((String) pairs[i], pairs[i + 1]);
    }
    return m;
  }

  /** 与生产同源的时间处理：不注册 JavaTimeModule 的话 AccountFacts 里五个 LocalDateTime 直接抛。 */
  private static ObjectMapper jsonMapper() {
    return new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  private static MindisleProperties properties(Path exportDir, int maxRowsPerTable, int ttlHours) {
    MindisleProperties props = new MindisleProperties();
    props.getPrivacy().setExportDir(exportDir.toString());
    props.getPrivacy().setMaxRowsPerTable(maxRowsPerTable);
    props.getPrivacy().setLinkTtlHours(ttlHours);
    return props;
  }

  private static User account() {
    User u = new User();
    u.setId(UID);
    u.setUsername("cool_user");
    u.setNickname("心屿小号");
    u.setEmail("cool@example.com");
    u.setPassword(SECRET);
    u.setRole("USER");
    u.setStatus(CoolingState.DELETED);
    u.setAiStyle("warm");
    u.setRegSource("web");
    u.setCreatedAt(LocalDateTime.of(2026, 1, 5, 20, 0));
    u.setLastLoginAt(LocalDateTime.of(2026, 3, 1, 7, 30));
    u.setAgreePrivacyAt(LocalDateTime.of(2026, 1, 5, 20, 1));
    u.setDeactivateAt(LocalDateTime.of(2026, 3, 15, 10, 30));
    u.setPurgeAt(LocalDateTime.of(2026, 4, 14, 10, 30));
    u.setLastLoginIp("10.0.0.8");
    return u;
  }

  private static UserMapper userMapper(User user) {
    return (UserMapper) Proxy.newProxyInstance(UserMapper.class.getClassLoader(),
        new Class<?>[] { UserMapper.class }, (proxy, method, args) -> {
          if ("selectById".equals(method.getName())) {
            return user;
          }
          if ("toString".equals(method.getName())) {
            return "UserMapper$Fake";
          }
          if ("hashCode".equals(method.getName())) {
            return UserMapper.class.hashCode();
          }
          if ("equals".equals(method.getName())) {
            return proxy == args[0];
          }
          throw new UnsupportedOperationException("本用例不预期的调用：" + method.getName());
        });
  }

  private static PrivacyExportService service(MindisleProperties props, FakeStore store, FakeTasks tasks,
      User user, Executor exec) {
    return new PrivacyExportService(userMapper(user), tasks.mapper(), store, jsonMapper(), props, exec);
  }

  /** 三张表有数据、其余为空的标准夹具，条数写死在这里，各用例只断言「包里是不是这几个数」。 */
  private static FakeStore standardStore() {
    FakeStore store = new FakeStore();
    store.put("chat_message", List.of(
        row("id", 101L, "content", "今天很丧", "created_at", LocalDateTime.of(2026, 3, 15, 10, 30),
            "embedding", new byte[] { 1, 2, 3, 4 }),
        row("id", 102L, "content", "好了一点", "created_at", LocalDateTime.of(2026, 3, 16, 9, 0),
            "embedding", null)));
    store.put("post", List.of(
        row("id", 11L, "content", "普通的帖子",
            "created_at", Timestamp.valueOf(LocalDateTime.of(2026, 3, 1, 8, 0, 0))),
        row("id", 12L, "content", "=HYPERLINK(" + DQ + "http://evil" + DQ + ")",
            "created_at", Timestamp.valueOf(LocalDateTime.of(2026, 3, 2, 8, 0, 0))),
        row("id", 13L, "content", "含,逗号" + NL + "和换行",
            "created_at", Timestamp.valueOf(LocalDateTime.of(2026, 3, 3, 8, 0, 0)))));
    store.put("post_like", List.of(row("id", 7L, "post_id", 11L, "user_id", UID)));
    return store;
  }

  /** 读 zip 字节里的所有条目：条目名 -> 内容。 */
  private static Map<String, String> unzip(byte[] zipBytes) throws Exception {
    Map<String, String> out = new LinkedHashMap<>();
    try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
      ZipEntry e;
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      while ((e = zin.getNextEntry()) != null) {
        buf.reset();
        int n;
        while ((n = zin.read(chunk)) > 0) {
          buf.write(chunk, 0, n);
        }
        out.put(e.getName(), buf.toString(StandardCharsets.UTF_8));
        zin.closeEntry();
      }
    }
    return out;
  }

  @TempDir
  private Path exportDir;

  // ==================================================== 1. 提交入口的格式闸

  @Test
  @DisplayName("格式只认 json 与 csv：拼错的参数不能被猜成默认值，且一行都不许插")
  void unknownFormatIsRejectedBeforeAnyRowIsInserted() {
    FakeStore store = standardStore();
    FakeTasks tasks = new FakeTasks();
    PrivacyExportService svc = service(properties(exportDir, 5000, 24), store, tasks, account(), Runnable::run);
    for (String bad : List.of("xml", "JSONX", "zip", "jsonl")) {
      BizException ex = assertThrows(BizException.class, () -> svc.submit(UID, bad),
          "不该被放过的格式：" + bad);
      assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode(), "猜一个默认格式 = 用户拿到另一种打不开的文件，必须当场 400");
      assertEquals("导出格式只支持 json 与 csv", ex.getMessage());
    }
    assertTrue(tasks.rows.isEmpty(), "格式不合法时一行都不该插：否则库里留下一条永远跑不完的 PENDING");
    assertEquals(0, store.reads, "读数一次都不该发生");
    // 空与 null 才走默认 json；大小写与首尾空格是归一化，不是猜
    PrivacyViews.ExportTaskView csv = svc.submit(UID, "  CSV ");
    assertEquals(ExportTask.FORMAT_CSV, csv.format(),
        "归一化后写进 fmt 列的值必须与 DDL 的 ENUM 逐字一致，否则会撞 1265 Data truncated");
    assertEquals(1, tasks.rows.size(), "合法格式这次应当真的插行");
  }

  // ==================================================== 2. 异步语义与重复提交

  @Test
  @DisplayName("PENDING 期间重复提交只复用不插第二行；跑完之后才有口令")
  void duplicateSubmitWhilePendingReusesTheRowAndTokenAppearsOnlyOnSuccess() {
    FakeStore store = standardStore();
    FakeTasks tasks = new FakeTasks();
    QueuedExecutor queued = new QueuedExecutor();
    PrivacyExportService svc = service(properties(exportDir, 5000, 24), store, tasks, account(), queued);

    PrivacyViews.ExportTaskView first = svc.submit(UID, "json");
    assertEquals(ExportTask.PENDING, first.status(), "任务还排在队列里，状态必须是 PENDING");
    assertFalse(first.downloadReady(), "产物还不存在时给出口令，只是把一个 404 提前发出去");
    assertNull(first.downloadPath(), "没有口令就没有链接，前端不必去猜 status 与 token 谁非空");
    assertEquals(1, queued.size(), "一次提交只排一个后台任务");

    // 用户手抖又点一次：hasUnfinished 为真时必须复用那一行
    tasks.unfinished = true;
    PrivacyViews.ExportTaskView second = svc.submit(UID, "json");
    assertEquals(1, tasks.rows.size(), "连点两次只该有一个进行中的任务，否则列表里全是同一次导出");
    assertEquals(1, queued.size(), "复用旧任务时不能再排一遍后台执行体：同一份文件会被写两遍");
    assertEquals(first.id(), second.id(), "响应里永远只描述一个任务");

    queued.drain();
    PrivacyViews.ExportTaskView done = svc.latest(UID);
    assertEquals(ExportTask.SUCCESS, done.status());
    assertTrue(done.downloadReady());
    assertTrue(done.downloadPath().startsWith("/api/privacy/export/file?token="));
    assertEquals(1, tasks.rows.size(), "跑完也不该多出第二行");
    assertNull(service(properties(exportDir, 5000, 24), new FakeStore(), new FakeTasks(), account(),
        Runnable::run).latest(UID), "没导出过的账号读最新任务要返回 null，而不是抛一个 404 打断概览页");
  }

  // ==================================================== 3. 成功路径与口令强度

  @Test
  @DisplayName("同步跑完：产物原子落盘、库里的字节数与磁盘一致、口令是 64 位十六进制且 24 小时到期")
  void submitRunsToSuccessWithUnpredictableToken() throws Exception {
    FakeStore store = standardStore();
    FakeTasks tasks = new FakeTasks();
    PrivacyExportService svc = service(properties(exportDir, 5000, 24), store, tasks, account(), Runnable::run);
    PrivacyViews.ExportTaskView view = svc.submit(UID, "json");
    assertEquals(ExportTask.SUCCESS, view.status(), "执行器换成同步之后，submit 返回时任务就该跑完");

    ExportTask row = tasks.rows.get(0);
    tasks.tokenHit = row;
    String token = row.getToken();
    assertNotNull(token, "口令只在 markSuccess 那一刻写入");
    assertEquals(64, token.length(), "32 字节随机数 -> 64 个十六进制字符，与库里 CHAR(64) 对齐");
    assertDoesNotThrow(() -> HexFormat.of().parseHex(token), "口令必须是纯十六进制");
    assertNotNull(row.getExpireAt());
    LocalDateTime now = LocalDateTime.now();
    assertFalse(row.getExpireAt().isBefore(now.plusHours(23)), "链接有效期是 24 小时，不该短");
    assertTrue(row.getExpireAt().isBefore(now.plusHours(25)), "也不该长：手册 §7.5 与需求 FR1.6 都是 24h");

    Path artifact = Path.of(row.getFilePath());
    assertTrue(Files.exists(artifact), "库里记了路径，磁盘上就得真有这份文件");
    assertEquals(Files.size(artifact), row.getFileBytes().longValue(),
        "字节数取的是产物真实长度：防「库里说 2KB、磁盘上是个空文件」");
    assertFalse(Files.exists(Path.of(row.getFilePath() + ".part")), "原子落盘（先写 .part 再 move）不许留残片");

    PrivacyExportService.Download dl = svc.download(token, UID);
    assertTrue(java.util.Arrays.equals(Files.readAllBytes(artifact), dl.content()), "下载回来的字节与磁盘上的必须逐字节相同");
    assertEquals("application/json;charset=UTF-8", dl.contentType());
    assertTrue(dl.fileName().startsWith("mindisle-export-" + UID + "-"), "文件名带 uid 与时间戳：" + dl.fileName());
    assertTrue(dl.fileName().endsWith(".json"));
    assertFalse(dl.fileName().contains(token), "文件名不该成为第二个秘密（它进 Content-Disposition，也进浏览器的下载记录）");
  }

  @Test
  @DisplayName("两次导出的口令互不相同：自增 id 或时间戳当口令就会被枚举")
  void tokensAreNotReusableAcrossTasks() throws Exception {
    List<String> tokens = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      FakeTasks tasks = new FakeTasks();
      PrivacyExportService svc = service(properties(exportDir, 5000, 24), standardStore(), tasks, account(),
          Runnable::run);
      svc.submit(UID, "json");
      tokens.add(tasks.rows.get(0).getToken());
    }
    assertDoesNotThrow(() -> HexFormat.of().parseHex(tokens.get(0)));
    assertFalse(tokens.get(0).equals(tokens.get(1)),
        "两次导出拿到同一个口令 = 口令可预测；它进 URL、浏览器历史与代理日志，可预测就等于别人的包可以枚举");
  }

  // ==================================================== 4. 包的内容形状

  @Test
  @DisplayName("包顶层是「先说明、再账号、再数据」；摘要、登录 IP 与整行 user 都带不出去")
  void jsonLayoutIsDocumentFirstAndCarriesNoSecret() throws Exception {
    FakeTasks tasks = new FakeTasks();
    PrivacyExportService svc = service(properties(exportDir, 5000, 24), standardStore(), tasks, account(),
        Runnable::run);
    svc.submit(UID, "json");
    Path artifact = Path.of(tasks.rows.get(0).getFilePath());
    JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(artifact));

    List<String> fields = new ArrayList<>();
    root.fieldNames().forEachRemaining(fields::add);
    assertEquals(List.of("generatedAt", "requestedByUserId", "format", "notice", "account", "summary", "data"),
        fields, "顺序是刻意的：当事人与替他看这份包的监管方，打开先看到的该是口径说明而不是一坨聊天记录");
    assertEquals(UID, root.get("requestedByUserId").asLong());
    assertEquals("json", root.get("format").asText());
    assertTrue(root.get("notice").asText().contains("user_consent"),
        "包里必须自证「授权记录在里面、注销后它会被物理删除」");

    JsonNode account = root.get("account");
    List<String> accountFields = new ArrayList<>();
    account.fieldNames().forEachRemaining(accountFields::add);
    assertEquals(List.of("id", "username", "nickname", "email", "role", "status", "aiStyle", "regSource",
        "createdAt", "lastLoginAt", "agreePrivacyAt", "deactivateAt", "purgeAt"), accountFields,
        "账号节点就是那张白名单：多一个少一个都意味着概览页与包不一致");
    assertFalse(account.has("password"), "AccountFacts 里根本没有这个字段");
    assertFalse(account.has("lastLoginIp"), "登录 IP 是风控字段，不在「本人要带走的个人数据」白名单里");
    String raw = Files.readString(artifact, StandardCharsets.UTF_8);
    assertFalse(raw.contains(SECRET), "BCrypt 摘要的原文都不许出现在包里（比查字段名更强：任何路径都带不出去）");
    assertFalse(raw.contains("10.0.0.8"), "同理，last_login_ip 的原文也不许出现");
    assertFalse(root.get("data").has("user"),
        "整行 user 不导出：注册表里它是 exportable=false，一旦导出就等于把 password 一起带出去");
  }

  @Test
  @DisplayName("25 个可导出域一个都不能少，空的也留空数组；读数顺序就是注册表顺序")
  void everyExportableDomainIsReadAndPresentEvenWhenEmpty() throws Exception {
    FakeStore store = standardStore();
    FakeTasks tasks = new FakeTasks();
    PrivacyExportService svc = service(properties(exportDir, 5000, 24), store, tasks, account(), Runnable::run);
    svc.submit(UID, "json");
    JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(Path.of(tasks.rows.get(0).getFilePath())));
    List<String> expected = new ArrayList<>();
    for (Domain d : PrivacyDomains.exportable()) {
      expected.add(d.table());
    }
    assertEquals(expected, store.readTables, "读数顺序与域集合就是注册表给的：漏一张表的表现是包里有而没人发现");
    assertEquals(expected.size(), root.get("data").size(), "每个可导出域都要在包里出现，哪怕是空数组");
    for (String table : expected) {
      assertTrue(root.get("data").get(table).isArray(), table + " 应当是数组形状");
    }
    assertTrue(root.get("data").has("user_consent"),
        "user_consent 必须在包里：注销后这张表会被物理删掉，这份包是「告知—同意」唯一留在当事人手上的证据");
    assertEquals(0, root.get("data").get("user_consent").size(), "夹具里这张表没有行，但键要在");
    assertEquals(25, expected.size(), "注册表的可导出域数量（与需求 §7.5 一致）");
    for (Integer limit : store.limits) {
      assertEquals(5000, limit.intValue(), "单表行数上限必须逐域传下去，否则「读到 limit 就停」是句空话");
    }
  }

  @Test
  @DisplayName("JDBC 值先归一再序列化：byte[] 走 base64，Timestamp 走 ISO 串而不是毫秒数")
  void jdbcValuesAreNormalizedBeforeSerialization() throws Exception {
    FakeTasks tasks = new FakeTasks();
    PrivacyExportService svc = service(properties(exportDir, 5000, 24), standardStore(), tasks, account(),
        Runnable::run);
    svc.submit(UID, "json");
    JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(Path.of(tasks.rows.get(0).getFilePath())));
    JsonNode messages = root.get("data").get("chat_message");
    assertEquals(2, messages.size());
    String embedding = messages.get(0).get("embedding").asText();
    assertTrue(embedding.startsWith("base64:"), "二进制列要带前缀，读的人才知道那不是一段原文：" + embedding);
    assertTrue(java.util.Arrays.equals(new byte[] { 1, 2, 3, 4 },
        Base64.getDecoder().decode(embedding.substring("base64:".length()))), "base64 要能还原成原字节");
    assertTrue(messages.get(1).get("embedding").isNull(), "null 还是 null，不该被写成 base64: 空串");
    assertEquals("2026-03-15T10:30", messages.get(0).get("created_at").asText(),
        "LocalDateTime 走 ISO 串；写成 1773504000000 对当事人毫无意义");
    JsonNode posts = root.get("data").get("post");
    assertTrue(posts.get(0).get("created_at").isTextual(), "java.sql.Timestamp 也会被 Jackson 默认压成毫秒数，必须先转");
    assertEquals("2026-03-01T08:00", posts.get(0).get("created_at").asText());
    String raw = Files.readString(Path.of(tasks.rows.get(0).getFilePath()), StandardCharsets.UTF_8);
    assertFalse(raw.contains(String.valueOf(Timestamp.valueOf(LocalDateTime.of(2026, 3, 1, 8, 0, 0)).getTime())),
        "包里不许留下任何epoch 毫秒数形式的时间");
  }

  // ==================================================== 5. 截断必须可见（需求 D11）

  @Test
  @DisplayName("恰好读到上限也记为截断：rowCounts 里要有 _truncated，写回库里的就是同一份")
  void exactlyAtLimitIsRecordedAsTruncated() throws Exception {
    FakeTasks tasks = new FakeTasks();
    // 夹具：post 3 行、chat_message 2 行；把单表上限调成 3，post 正好等于上限
    PrivacyExportService svc = service(properties(exportDir, 3, 24), standardStore(), tasks, account(),
        Runnable::run);
    svc.submit(UID, "json");
    JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(Path.of(tasks.rows.get(0).getFilePath())));
    JsonNode counts = root.get("summary").get("rowCounts");
    assertEquals(3, counts.get("post").asInt());
    assertEquals(2, counts.get("chat_message").asInt());
    assertEquals(1, counts.get("post_like").asInt());
    assertEquals(6L, root.get("summary").get("totalRows").asLong(), "总数是逐域相加，不含任何估算");
    assertTrue(counts.has("_truncated"), "行数等于上限时必须报截断：宁可多报一次，也不漏报「少了两千行而没人知道」");
    List<String> trunc = new ArrayList<>();
    counts.get("_truncated").forEach(node -> trunc.add(node.asText()));
    assertEquals(List.of("post"), trunc, "2 行的那一域不该被牵连：判据是逐域 rows.size() >= limit");
    assertEquals(1, root.get("summary").get("truncatedDomains").size());
    // 界面上那份对账（export_task.row_counts）与包里的 summary 必须是同一份，否则「条数可对账」是两句话
    JsonNode stored = new ObjectMapper().readTree(tasks.rows.get(0).getRowCounts());
    assertEquals(counts, stored, "写回 row_counts 的必须与包里的 rowCounts 逐键相等");

    FakeTasks wide = new FakeTasks();
    service(properties(exportDir, 5000, 24), standardStore(), wide, account(), Runnable::run).submit(UID, "json");
    JsonNode counts2 = new ObjectMapper()
        .readTree(Files.readAllBytes(Path.of(wide.rows.get(0).getFilePath()))).get("summary").get("rowCounts");
    assertFalse(counts2.has("_truncated"), "没截断就不写这个键：前端据此决定要不要显示「可能被截断」的提示");
  }

  // ==================================================== 6. CSV 双格式

  @Test
  @DisplayName("csv 走 zip：一个 README + 每个非空域一个 csv，空表不出文件但条数仍记 0")
  void csvModeProducesZipPerNonEmptyTable() throws Exception {
    FakeStore store = standardStore();
    FakeTasks tasks = new FakeTasks();
    PrivacyExportService svc = service(properties(exportDir, 5000, 24), store, tasks, account(), Runnable::run);
    PrivacyViews.ExportTaskView view = svc.submit(UID, "csv");
    assertEquals(ExportTask.SUCCESS, view.status());
    ExportTask row = tasks.rows.get(0);
    tasks.tokenHit = row;
    assertTrue(row.getFilePath().endsWith(".zip"),
        "csv 的产物是包：一张表一组列，塞进同一个文件只会得到几百列空值矩阵");
    PrivacyExportService.Download dl = svc.download(row.getToken(), UID);
    assertEquals("application/zip", dl.contentType());
    assertTrue(dl.fileName().endsWith(".zip"));
    assertEquals(80, dl.content()[0] & 255, "zip 魔数 PK：只写了一半的包不允许被读到条目");
    assertEquals(75, dl.content()[1] & 255);
    Map<String, String> entries = unzip(dl.content());
    List<String> expectedEntries = new ArrayList<>();
    expectedEntries.add("_README.txt");
    for (Domain d : PrivacyDomains.exportable()) {
      if (!store.rowsOf(d.table()).isEmpty()) {
        expectedEntries.add(d.table() + ".csv");
      }
    }
    assertEquals(expectedEntries, new ArrayList<>(entries.keySet()),
        "zip 条目 = README + 非空域，顺序与注册表一致；空表出一个只有列名的 csv 只会让人怀疑导出坏了");
    assertTrue(entries.get("_README.txt").contains("user_consent"),
        "README 要解释清楚「为什么没有 password、为什么 user_consent 重要」");
    assertFalse(entries.get("_README.txt").contains(SECRET));
    assertTrue(entries.get("chat_message.csv").startsWith("id,content,created_at,embedding" + NL),
        "表头取第一行的键序 = SELECT * 的列序");
    assertTrue(entries.get("post.csv").startsWith("id,content,created_at" + NL));
    assertEquals(5, entries.get("post.csv").split(NL).length,
        "表头 + 3 条数据，其中含换行的正文多出一段物理行：正是外层引号让 Excel 仍把它读作一格");
  }

  @Test
  @DisplayName("CSV 公式注入防护：= 与 + 开头的正文加单引号，内部引号翻倍，含逗号换行整体加引号")
  void csvNeutralizesFormulaInjection() throws Exception {
    FakeTasks tasks = new FakeTasks();
    PrivacyExportService svc = service(properties(exportDir, 5000, 24), standardStore(), tasks, account(),
        Runnable::run);
    svc.submit(UID, "csv");
    ExportTask row = tasks.rows.get(0);
    tasks.tokenHit = row;
    String post = unzip(svc.download(row.getToken(), UID).content()).get("post.csv");
    // 夹具第 12 行的正文是 =HYPERLINK 一个带引号的 URL，用 Excel 打开就是可执行公式
    String expect12 = "12," + DQ + SQ + "=HYPERLINK(" + DQ + DQ + "http://evil" + DQ + DQ + ")" + DQ + ",2026-03-02T08:00";
    assertEquals(expect12, lineStartingWith(post, "12,"),
        "单元格首个可见字符必须是前缀单引号，外层引号 + 内部引号翻倍；同类开源项目普遍没做这一层");
    assertFalse(post.contains(",=HYPERLINK"), "绝不能出现裸的 = 开头单元格：那正是公式执行的入口");
    assertTrue(post.contains("13," + DQ + "含,逗号" + NL + "和换行" + DQ + ",2026-03-03T08:00"),
        "含逗号与换行的值必须整体加引号：" + post);
    assertTrue(post.contains("2026-03-01T08:00"), "Timestamp 在 csv 里同样走 ISO 串");
    assertFalse(post.contains(String.valueOf(Timestamp.valueOf(LocalDateTime.of(2026, 3, 1, 8, 0, 0)).getTime())),
        "也不留 epoch 毫秒数");
    // 加号同样危险（Excel 会把 +1 当公式），换一个夹具值现证这一支
    FakeStore plusStore = standardStore();
    plusStore.put("post", List.of(row("id", 21L, "content", "+1", "created_at", "x")));
    FakeTasks plusTasks = new FakeTasks();
    PrivacyExportService plusSvc = service(properties(exportDir, 5000, 24), plusStore, plusTasks, account(),
        Runnable::run);
    plusSvc.submit(UID, "csv");
    ExportTask plusRow = plusTasks.rows.get(0);
    plusTasks.tokenHit = plusRow;
    String plusCsv = unzip(plusSvc.download(plusRow.getToken(), UID).content()).get("post.csv");
    // 这一格不含逗号、引号与换行，于是只加前缀、不整体加引号：公式防护与转义防护各管各的，
    // 把它们捏成一条断言，下次就会有人误以为「没有外层引号等于没防住」。
    assertEquals("21," + SQ + "+1,x", lineStartingWith(plusCsv, "21,"),
        "加号开头同样拿到前缀单引号；减号、at、制表符与回车走的是同一条分支，源码里六个危险首字符逐条列着");
  }

  // ==================================================== 7. CSV 行定位助手

  /**
   * 按前缀找一行。找不到时把全文塞进返回值：CSV 的行序、引号、转义任何一处变了，
   * 光看「期望串不等」分不出是「那一行没了」还是「那一行变形了」，而这两种的修法完全不同。
   */
  private static String lineStartingWith(String text, String prefix) {
    for (String line : text.split(NL)) {
      if (line.startsWith(prefix)) {
        return line;
      }
    }
    return "<<没有以 " + prefix + " 开头的行>> 全文=" + text;
  }

  // ==================================================== 8. 下载闸：七种拒绝同一句话

  /** 断言「这一次下载该被拒」，并拒的是 404；把那句话带回去给调用方做同文比对。 */
  private static String expectGate(org.junit.jupiter.api.function.Executable call, String gate) {
    BizException ex = assertThrows(BizException.class, call, "下载闸应当拒绝：" + gate);
    assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode(), "哪一道闸拒的都该是 404：" + gate);
    return ex.getMessage();
  }

  @Test
  @DisplayName("七种拒绝拼成同一句话：探测者拿不到「这串口令是真的但过期了」这种半成品情报")
  void everyDownloadGateSaysTheSameSentence() throws Exception {
    FakeTasks tasks = new FakeTasks();
    PrivacyExportService svc = service(properties(exportDir, 5000, 24), standardStore(), tasks, account(),
        Runnable::run);
    svc.submit(UID, "json");
    ExportTask row = tasks.rows.get(0);
    tasks.tokenHit = row;
    String token = row.getToken();
    Path artifact = Path.of(row.getFilePath());
    assertTrue(Files.exists(artifact), "先确认产物在，后面「文件不见了」那一支才是真的在测那一道闸");

    List<String> refusals = new ArrayList<>();
    refusals.add(expectGate(() -> svc.download(null, UID), "null 口令"));
    refusals.add(expectGate(() -> svc.download("   ", UID), "全空格口令"));

    tasks.tokenHit = null;
    refusals.add(expectGate(() -> svc.download("f".repeat(64), UID), "库里查无此串"));
    tasks.tokenHit = row;

    refusals.add(expectGate(() -> svc.download(token, UID + 1), "拿着别人口令的自己"));

    row.setStatus(ExportTask.RUNNING);
    refusals.add(expectGate(() -> svc.download(token, UID), "任务不是成功态"));
    row.setStatus(ExportTask.SUCCESS);

    row.setExpireAt(LocalDateTime.now().minusSeconds(5));
    int deletesBefore = tasks.deletes;
    refusals.add(expectGate(() -> svc.download(token, UID), "链接已过期"));
    assertEquals(deletesBefore + 1, tasks.deletes, "过期那一支要顺手作废这一行：留着只是让列表多一个点了必 404 的按钮");
    row.setExpireAt(LocalDateTime.now().plusHours(1));

    Files.delete(artifact);
    refusals.add(expectGate(() -> svc.download(token, UID), "库说成功但磁盘上没这份文件"));

    assertEquals(1L, refusals.stream().distinct().count(), "七条闸口回的是七种话术就等于泄漏原因：" + refusals);
    assertEquals("下载链接无效或已过期", refusals.get(0));
  }

  // ==================================================== 9. 并发与失败：三条最难复现的分支

  @Test
  @DisplayName("抢不到 PENDING 到 RUNNING 这把锁就立刻放弃：一遍不跑、一次读数不发、口令不发")
  void runGivesUpWhenMarkRunningLosesTheRace() throws Exception {
    FakeStore store = standardStore();
    FakeTasks tasks = new FakeTasks();
    tasks.markRunningResult = 0;
    ExportTask claimed = new ExportTask();
    claimed.setId(9L);
    claimed.setUserId(UID);
    claimed.setFmt(ExportTask.FORMAT_JSON);
    claimed.setStatus(ExportTask.RUNNING);
    tasks.rows.add(claimed);

    PrivacyExportService svc = service(properties(exportDir, 5000, 24), store, tasks, account(), Runnable::run);
    svc.run(9L);

    assertEquals(0, store.reads, "锁没抢到还去读数 = 两个 worker 写同一份产物，而库里的路径只记得住最后一个");
    assertFalse(tasks.wasCalled("markSuccess"), "不能把别人正在跑的任务标成自己成功了");
    assertFalse(tasks.wasCalled("markFailed"), "抢锁失败不是失败，是这一趟本来就不该跑");
    assertEquals(ExportTask.RUNNING, claimed.getStatus(), "状态归抢到的那一路管，放弃的一方不许改它");
    assertNull(claimed.getToken());
    try (java.util.stream.Stream<Path> it = Files.list(exportDir)) {
      assertEquals(0L, it.count(), "放弃时一个字节都不该落盘");
    }
  }

  @Test
  @DisplayName("产物已落盘而状态被改动（markSuccess 返 0）：回收文件，行留在 RUNNING，不发口令")
  void orphanArtifactIsRecycledWhenStatusChangedUnderneath() throws Exception {
    FakeTasks tasks = new FakeTasks();
    tasks.markSuccessResult = 0;
    PrivacyExportService svc = service(properties(exportDir, 5000, 24), standardStore(), tasks, account(),
        Runnable::run);
    PrivacyViews.ExportTaskView view = svc.submit(UID, "json");
    ExportTask row = tasks.rows.get(0);

    assertEquals(ExportTask.RUNNING, view.status(), "CAS 输了就不许把状态推进到 SUCCESS");
    assertNull(row.getFilePath(), "markSuccess 整行没写成，路径也不该留下");
    assertNull(row.getToken(), "没人认领的任务不能有一个能下载口令");
    assertNull(row.getExpireAt());
    assertFalse(view.downloadReady());
    try (java.util.stream.Stream<Path> it = Files.list(exportDir)) {
      assertEquals(0L, it.count(),
          "库里没人认领的产物就是孤儿文件：它装着一个人全部的聊天记录，清不掉就得靠这条回收兜底");
    }
  }

  @Test
  @DisplayName("读数中途炸掉：任务落 FAILED、口令清空，error_text 只留一句人话、不带堆栈与目录")
  void storeFailureBecomesUserFacingBrief() {
    FakeStore store = standardStore();
    store.failOn(2);
    FakeTasks tasks = new FakeTasks();
    PrivacyExportService svc = service(properties(exportDir, 5000, 24), store, tasks, account(), Runnable::run);
    PrivacyViews.ExportTaskView view = svc.submit(UID, "json");

    assertEquals(ExportTask.FAILED, view.status(), "异步这一趟抛出去没有任何调用方能接住，所以它必须自己变成一条看得见的失败");
    assertEquals(2, store.reads, "夹具是第 2 次读数抛，第 1 次应当真的读到了");
    assertFalse(view.downloadReady());
    assertNull(view.downloadPath());

    String text = view.errorText();
    assertNotNull(text, "失败原因得能在界面上读出来，否则用户只知道「转圈转到天荒地老」");
    assertTrue(text.startsWith("生成失败："), text);
    assertTrue(text.contains("读数中断"), "要带上足以定位的那半句：" + text);
    assertTrue(text.length() <= 5 + 160, "压成短句回给用户，长堆栈归日志：" + text.length());
    assertFalse(text.contains(exportDir.toString()), "目录结构不许从接口泄漏：" + text);
    assertFalse(text.contains("IllegalStateException"), "类名与堆栈只进日志：" + text);

    ExportTask row = tasks.rows.get(0);
    assertNull(row.getToken(), "失败的任务不该还揣着一个可用口令");
    assertNull(row.getExpireAt());
  }

  // ==================================================== 10. 配置兜底与响应形状

  @Test
  @DisplayName("有效期配成 0 也夹到一小时；账号已被物理清除时任务失败而不是一路抛成 500")
  void ttlClampAndMissingAccountFailTheTask() {
    FakeTasks shortTasks = new FakeTasks();
    PrivacyExportService shortSvc = service(properties(exportDir, 5000, 0), standardStore(), shortTasks, account(),
        Runnable::run);
    LocalDateTime before = LocalDateTime.now();
    assertEquals(ExportTask.SUCCESS, shortSvc.submit(UID, "json").status());
    LocalDateTime expire = shortTasks.rows.get(0).getExpireAt();
    assertNotNull(expire);
    assertFalse(expire.isBefore(before.plusMinutes(55)),
        "link-ttl-hours 配成 0 或负数就等于「链接一出生就过期」，必须夹到至少 1 小时：" + expire);
    assertTrue(expire.isBefore(before.plusMinutes(65)), "夹完也不许顺手放大成两天：" + expire);

    FakeTasks goneTasks = new FakeTasks();
    PrivacyExportService goneSvc = service(properties(exportDir, 5000, 24), standardStore(), goneTasks, null,
        Runnable::run);
    PrivacyViews.ExportTaskView gone = goneSvc.submit(UID, "json");
    assertEquals(ExportTask.FAILED, gone.status(),
        "查不到账号是数据问题不是系统坏了：它该落成一条读得懂的 FAILED，而不是把异步线程抛出无人接住的异常");
    assertNotNull(gone.errorText());
    assertTrue(gone.errorText().contains("导出对象的账号已不存在"), gone.errorText());
    assertFalse(gone.downloadReady());
  }

  @Test
  @DisplayName("响应里既没有 filePath 也没有 token；历史列表新的在前，一次提交只占一行")
  void viewHasNoFilePathFieldAndHistoryIsNewestFirst() {
    List<String> fields = new ArrayList<>();
    for (java.lang.reflect.RecordComponent rc : PrivacyViews.ExportTaskView.class.getRecordComponents()) {
      fields.add(rc.getName());
    }
    assertEquals(List.of("id", "format", "status", "fileBytes", "rowCountSummary", "downloadReady",
        "downloadPath", "expireAt", "createdAt", "errorText"), fields,
        "导出任务的对外形状就这十个字段，多一个都要先过一遍「它是不是情报」");
    assertFalse(fields.contains("filePath"), "绝对路径给出去 = 白送部署结构：盘符、目录层级、运行账号全在里面");
    assertFalse(fields.contains("token"), "口令只进那一条下载链接，不当成一份可以被别处复制的字段");
    assertTrue(fields.contains("downloadReady"), "前端要能直接判「按钮点亮不点亮」，不必自己拼 status 与 expireAt");

    FakeTasks tasks = new FakeTasks();
    PrivacyExportService svc = service(properties(exportDir, 5000, 24), standardStore(), tasks, account(),
        Runnable::run);
    svc.submit(UID, "csv");
    svc.submit(UID, "json");
    List<PrivacyViews.ExportTaskView> hist = svc.history(UID, 10);
    assertEquals(2, hist.size(), "两次提交两行记录：hasUnfinished 这里配的是「上一趟早已跑完」");
    assertEquals(2, tasks.countOf("insert"));
    assertTrue(hist.get(0).id() > hist.get(1).id(),
        "新的在前：当事人点开列表先看到刚才那一次，而不是半年前那次：" + hist.get(0).id() + " / " + hist.get(1).id());
    assertEquals(ExportTask.FORMAT_JSON, hist.get(0).format());
    assertEquals(ExportTask.FORMAT_CSV, hist.get(1).format());
  }
}
