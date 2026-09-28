package com.mindisle.privacy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.ExportTask;
import com.mindisle.entity.User;
import com.mindisle.mapper.ExportTaskMapper;
import com.mindisle.mapper.UserMapper;
import com.mindisle.privacy.PrivacyDomains.Domain;

/**
 * 个人信息导出（任务 T4.21 · 需求 FR1.6「异步生成 zip/json、下载链接 24h 失效」· D11 条数可对账）。
 *
 * <p><b>为什么是「请求 → PENDING → 后台跑 → 成功才给 token」四步，而不是一个同步接口</b>：
 * 一个重度用户的包要跨 20 多张表读数，同步做就得把 HTTP 线程按住几十秒，
 * 而 Nginx/网关的默认超时比这短 —— 结果是「用户看到 504、服务端其实还在写文件」，
 * 那份文件没人知道在哪。手册给的就是「异步生成 export_task」这一条口径，本类是它的物化。
 * token 只在 {@link ExportTaskMapper#markSuccess} 那一刻写入：产物还不存在时，
 * 一条可用的下载链接只是把一个 404 提前发出去。</p>
 *
 * <p><b>异步用自建单线程池，不用 {@code @Async}</b>：现查全站 0 处 {@code @Async}
 * （唯一的两处异步是 {@code AiController} 的流式池与 {@code ChatService} 的补标池，
 * 都是自己 new 的 {@code ExecutorService}）。跟着这个口径走有三个好处：
 * 不必为了一个任务打开 {@code @EnableAsync}（它会改变全站的异常与事务边界语义）、
 * 线程名可寻（日志里 {@code privacy-export} 一眼可辨）、
 * 而单测可以传一个 {@code Runnable::run} 进来让整条链路同步跑完 ——
 * 否则「提交即完成」这个断言必须靠轮询和 sleep，那是偶发红的基础。
 * 单线程而不是池化：导出是低频高成本动作，同一个人连点两次也只该排队一份
 * （{@link ExportTaskMapper#hasUnfinished} 已经在入口拦了重复提交）。</p>
 *
 * <p><b>包内必含 {@code user_consent} 全量、含 content_version</b>：这不是「顺手多导一张表」，
 * 而是本任务里最重要的一条取舍 —— 系统对 {@code user_consent} 的处理是<b>随注销物理删除</b>
 * （见 {@link PrivacyPurgeService}），也就是说清完之后「我曾经告知过、他曾经同意过」
 * 的举证责任全部转移到<b>他自己导出的那一份</b>。少了这张表，导出包就只是一个数据副本，
 * 而不是 PIPL 第 45 条意义上的可携带副本。同理，包里<b>不含 {@code export_task} 自己</b>
 * （包不能包含包自己，否则每次注销导出都会把历史导出记录卷进新包）。</p>
 */
@Service
public class PrivacyExportService implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(PrivacyExportService.class);

  /** 下载口令：32 字节随机 → 64 个十六进制字符。不是自增 id，也不是可猜的时间戳。 */
  private static final int TOKEN_BYTES = 32;

  private static final SecureRandom RANDOM = new SecureRandom();

  private static final HexFormat HEX = HexFormat.of();

  /** 文件名里的时间戳，用不带分隔符的紧凑格式：路径里出现空格与冒号在 Windows 上会惹麻烦。 */
  private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  /** 一次导出里出现的域数上限保护（注册表只有 34 条，这里只是把「将来有人加了 300 张表」挡在文件名之外）。 */
  private static final int MAX_DOMAINS = 64;

  private final UserMapper userMapper;
  private final ExportTaskMapper taskMapper;
  private final PrivacyStore store;
  private final ObjectMapper json;
  private final MindisleProperties properties;

  /** 执行器。生产用池，单测传同步执行器。 */
  private final Executor runner;

  /** 只有生产构造器会填充它；注入进来的执行器不由本类负责关闭。 */
  private final ExecutorService ownedPool;

  /**
   * 生产构造器：Spring 装配走的这一条。
   *
   * <p>{@code @Autowired} 不是可省的装饰 —— 本类另有一条只给单测用的包级构造器，
   * 而没有 {@code @Autowired} 时 Spring 面对多个候选构造器会退化成「找无参构造器」，
   * 于是启动直接失败：{@code BeanInstantiationException: No default constructor found}。
   * 这个错在单测里永远发现不了（测试用的正是另一条），只在打包启动时出现。</p>
   */
  @Autowired
  public PrivacyExportService(UserMapper userMapper, ExportTaskMapper taskMapper, PrivacyStore store,
      ObjectMapper json,
      MindisleProperties properties) {
    this(userMapper, taskMapper, store, json, properties, null);
  }

  /**
   * 包级构造：测试传 {@code Runnable::run} 让任务在调用线程里同步跑完。
   *
   * <p>{@code ownedPool} 为 null 时 {@link #destroy()} 什么都不做 ——
   * 「不拥有就不关闭」，否则测试里传进来的执行器会被意外关掉，
   * 而那个失败会出现在与原因完全无关的下一个用例里。</p>
   */
  PrivacyExportService(UserMapper userMapper, ExportTaskMapper taskMapper, PrivacyStore store,
      ObjectMapper json, MindisleProperties properties, Executor injected) {
    this.userMapper = userMapper;
    this.taskMapper = taskMapper;
    this.store = store;
    this.json = json;
    this.properties = properties;
    if (injected == null) {
      this.ownedPool = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "privacy-export");
        // daemon：导出产物是可重做的，热部署与 Ctrl+C 时不该为了一个正在打包的 zip 卡住收场
        //（同一个理由见 ChatService 的补标池）
        t.setDaemon(true);
        return t;
      });
      this.runner = ownedPool;
    } else {
      this.ownedPool = null;
      this.runner = injected;
    }
  }

  @Override
  public void destroy() {
    if (ownedPool != null) {
      ownedPool.shutdownNow();
    }
  }

  /** 一次下载的全部信息：文件名（给 Content-Disposition）与字节。 */
  public record Download(String fileName, String contentType, byte[] content) {
  }

  /**
   * 提交导出。{@code format} 只认 json 与 csv，别的直接 10001 ——
   * 「猜一个默认格式」会让前端拼错的参数静默变成另一种格式的文件，用户拿到手才发现打不开。
   *
   * <p>已有未完成任务时<b>不再插行</b>，直接把那条返回。这既是「连点只排一份」的实现，
   * 也让响应里永远只描述一个任务，前端不必处理「我提交了 3 次，现在有 3 个进行中的」。</p>
   */
  public PrivacyViews.ExportTaskView submit(long userId, String format) {
    String fmt = normalizeFormat(format);
    MindisleProperties.Privacy cfg = properties.getPrivacy();
    if (taskMapper.hasUnfinished(userId)) {
      ExportTask exists = taskMapper.findLatest(userId);
      log.info("已有未完成的导出任务，本次提交复用 id={} user={} status={}",
          exists == null ? null : exists.getId(), userId, exists == null ? "none" : exists.getStatus());
      return view(exists);
    }
    ExportTask task = new ExportTask();
    task.setUserId(userId);
    task.setFmt(fmt);
    task.setStatus(ExportTask.PENDING);
    taskMapper.insert(task);
    final Long taskId = task.getId();
    if (taskId == null) {
      throw new BizException(ErrorCode.INTERNAL_ERROR, "导出任务未能落库，请稍后再试");
    }
    runner.execute(() -> run(taskId));
    return view(task);
  }

  /** 最新一条任务（前端轮询「上一次导出怎么样了」就读它）。没有任务时返回 null，不抛。 */
  public PrivacyViews.ExportTaskView latest(long userId) {
    return view(taskMapper.findLatest(userId));
  }

  /** 历史任务列表，新的在前。上限由 {@link ExportTaskMapper#listByUser} 夹住。 */
  public List<PrivacyViews.ExportTaskView> history(long userId, int limit) {
    List<PrivacyViews.ExportTaskView> out = new ArrayList<>();
    for (ExportTask t : taskMapper.listByUser(userId, limit)) {
      out.add(view(t));
    }
    return out;
  }

  /**
   * 按口令取产物。<b>四道闸依次判，任何一道不过都是同一个 404</b>：
   * 口令不存在 / 口令是别人的 / 任务不是成功态 / 已过期 —— 四种情况在响应上完全一样，
   * 探测者拿不到「这串口令是真的但过期了」这种可用的部分信息（NFR7）。
   *
   * <p>过期时顺手软删这一行：一个已经没有产物的行留在列表里，只会让界面多一个
   * 「下载已失效」的按钮。行留着（{@code deleted} 由 {@code @TableLogic} 管），
   * 审计想知道「这个人导出过几次」仍然查得到。</p>
   */
  public Download download(String token, long userId) {
    if (token == null || token.isBlank()) {
      throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "下载链接无效或已过期");
    }
    ExportTask task = taskMapper.findByToken(token);
    if (task == null || !Long.valueOf(userId).equals(task.getUserId())
        || !ExportTask.SUCCESS.equals(task.getStatus())) {
      throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "下载链接无效或已过期");
    }
    LocalDateTime now = LocalDateTime.now();
    if (task.getExpireAt() != null && !now.isBefore(task.getExpireAt())) {
      taskMapper.deleteById(task.getId());
      log.info("下载链接已过期，作废任务 id={} user={} expire_at={}", task.getId(), userId, task.getExpireAt());
      throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "下载链接无效或已过期");
    }
    byte[] content = readArtifact(task);
    return new Download(fileNameOf(task), contentTypeOf(task.getFmt()), content);
  }

  /**
   * 后台执行体：PENDING → RUNNING → SUCCESS / FAILED。
   *
   * <p>{@code markRunning} 返回 0 就<b>立刻放弃</b>：那一行已经不是 PENDING 了
   * （被另一个 worker 抢走、被清除任务删掉、或者这本身就是重复排队的一个副本）。
   * 继续跑下去的结果是「跑了两遍、后一遍覆盖前一遍的产物文件而库里记的是另一条路径」，
   * 这种状态比失败更难查。</p>
   *
   * <p>整个方法吞掉一切异常并把它压成一句面向用户的短文案写进 {@code error_text}，
   * 堆栈只进日志。理由：这一行是异步跑的，抛出去没有任何调用方能接住，
   * 而任务留在 RUNNING 态会让用户以为「还在生成」并一直等。</p>
   */
  public void run(long taskId) {
    ExportTask task = taskMapper.selectById(taskId);
    if (task == null) {
      return;
    }
    if (taskMapper.markRunning(taskId, ExportTask.PENDING, ExportTask.RUNNING) == 0) {
      log.warn("导出任务未能从 PENDING 进入 RUNNING，放弃本次执行 id={} status={}", taskId, task.getStatus());
      return;
    }
    LocalDateTime beginAt = LocalDateTime.now();
    try {
      Artifact artifact = build(task, beginAt);
      Path target = write(artifact);
      int ttl = Math.max(1, properties.getPrivacy().getLinkTtlHours());
      int n = taskMapper.markSuccess(taskId, ExportTask.RUNNING, ExportTask.SUCCESS,
          target.toString(), artifact.content.length, artifact.rowCounts, newToken(),
          beginAt.plusHours(ttl));
      if (n == 0) {
        // 状态被别人改过：这份文件已经落盘但库里没人认领它，删掉，不留孤儿产物
        Files.deleteIfExists(target);
        log.warn("导出成功但状态已变更，产物已回收 id={} file={}", taskId, target);
        return;
      }
      log.info("导出完成 id={} user={} format={} bytes={} domains={}", taskId, task.getUserId(),
          task.getFmt(), artifact.content.length, artifact.domainCount);
    } catch (Exception e) {
      String brief = briefFailure(e);
      log.error("导出失败 id={} user={} 原因={}", taskId, task.getUserId(), e.toString(), e);
      taskMapper.markFailed(taskId, ExportTask.RUNNING, ExportTask.FAILED, brief);
    }
  }

  /** 内存里的产物：文件名、字节，以及要写回 row_counts 的对账串。 */
  private record Artifact(Path path, String fileName, byte[] content, String rowCounts, int domainCount) {
  }

  // ——————————————————————————————————————————————————————————————————————
  // 产物构建
  // ——————————————————————————————————————————————————————————————————————

  /**
   * 逐域读数 + 归一化 + 序列化。返回的 {@link Artifact} 只描述「这一份包应该长什么样」，
   * 真正落盘在 {@link #write(Artifact)} —— 拆开是因为单测要能只跑读数而不碰文件系统。
   */
  private Artifact build(ExportTask task, LocalDateTime now) throws Exception {
    MindisleProperties.Privacy cfg = properties.getPrivacy();
    int maxRows = Math.max(1, cfg.getMaxRowsPerTable());
    long userId = task.getUserId() == null ? 0L : task.getUserId();
    // 只算一次，全部 Link.POST 的域共用这个集合。同时也是「必须在删 post 之前算」这条纪律的体现：
    // 这里先算后读，读的是同一个集合，不会出现「前一张表按 10 篇帖、后一张表按 9 篇帖」
    List<Long> postIds = store.postIdsOf(userId);

    List<Domain> domains = PrivacyDomains.exportable();
    if (domains.size() > MAX_DOMAINS) {
      throw new IllegalStateException("可导出域数量超出预期（" + domains.size()
          + " > " + MAX_DOMAINS + "），请重新评估单包上限");
    }

    Map<Domain, List<Map<String, Object>>> rowsByDomain = new LinkedHashMap<>();
    Map<String, Object> counts = new LinkedHashMap<>();
    List<String> truncated = new ArrayList<>();
    long total = 0L;
    for (Domain d : domains) {
      List<Map<String, Object>> rows = store.selectRows(d, userId, postIds, maxRows);
      rowsByDomain.put(d, rows);
      counts.put(d.table(), (long) rows.size());
      total += rows.size();
      // 恰好等于 limit 也记为截断：宁可多报一次「可能被截断」，也不漏报
      //「少了两千行而没人知道」。概览页与包里的 _truncated 都用这个判据。
      if (rows.size() >= maxRows) {
        truncated.add(d.table());
      }
    }
    if (!truncated.isEmpty()) {
      counts.put(KEY_TRUNCATED, truncated);
    }

    byte[] content = ExportTask.FORMAT_CSV.equals(task.getFmt())
        ? buildCsv(userId, now, rowsByDomain)
        : buildJson(userId, now, rowsByDomain, counts, total, truncated);
    String fileName = fileNameOf(task, now);
    Path path = Paths.get(cfg.getExportDir()).toAbsolutePath().normalize().resolve(fileName);
    return new Artifact(path, fileName, content, json.writeValueAsString(counts), domains.size());
  }

  /**
   * JSON 单文件。顶层顺序刻意是「先说明、再账号、再各域」：
   * 用户（以及替他看这份包的监管方）打开先看到的应当是
   * 「这是什么时候、给谁、按什么口径生成的」，而不是一坨聊天记录。
   */
  private byte[] buildJson(long userId, LocalDateTime now,
      Map<Domain, List<Map<String, Object>>> rowsByDomain, Map<String, Object> counts, long total,
      List<String> truncated) throws Exception {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("generatedAt", now.toString());
    root.put("requestedByUserId", userId);
    root.put("format", ExportTask.FORMAT_JSON);
    root.put("notice", NOTICE);
    root.put("account", PrivacyViews.AccountFacts.of(accountOf(userId)));
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("domains", rowsByDomain.size());
    meta.put("totalRows", total);
    meta.put("rowCounts", counts);
    meta.put("truncatedDomains", truncated);
    root.put("summary", meta);

    Map<String, Object> data = new LinkedHashMap<>();
    rowsByDomain.forEach((d, rows) -> {
      List<Map<String, Object>> out = new ArrayList<>(rows.size());
      for (Map<String, Object> row : rows) {
        Map<String, Object> clean = new LinkedHashMap<>();
        row.forEach((k, v) -> clean.put(k, plain(v)));
        out.add(clean);
      }
      data.put(d.table(), out);
    });
    root.put("data", data);
    return json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
  }

  /**
   * CSV 走 zip：一个域一个文件，文件名就是表名（{@code chat_message.csv}）。
   * 为什么不把所有域塞进一个 csv：一张表一组列，拼进同一个文件会得到几百列的空值矩阵，
   * 那不是「可携带」，那是新的不可读。
   *
   * <p>空表<b>不产出文件</b>，但条数照样记进 {@code row_counts}（值为 0）。
   * 一个只有一行「列名」的空 csv 会让人怀疑导出坏了；而「这个域你本来就没有数据」
   * 是对账能读出来的事实。</p>
   */
  private byte[] buildCsv(long userId, LocalDateTime now,
      Map<Domain, List<Map<String, Object>>> rowsByDomain) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 16);
    try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
      // 一个说明文件：让拿到 zip 的人不必猜「这里面为什么没有 password」
      zip.putNextEntry(new ZipEntry("_README.txt"));
      zip.write(csvReadme(userId, now).getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();

      for (Map.Entry<Domain, List<Map<String, Object>>> entry : rowsByDomain.entrySet()) {
        List<Map<String, Object>> rows = entry.getValue();
        if (rows.isEmpty()) {
          continue;
        }
        zip.putNextEntry(new ZipEntry(entry.getKey().table() + ".csv"));
        // 表头取第一行的键序：Spring 的 queryForList 用 LinkedCaseInsensitiveMap，列序 = SELECT * 的列序
        List<String> cols = new ArrayList<>(rows.get(0).keySet());
        StringBuilder line = new StringBuilder(256);
        for (int i = 0; i < cols.size(); i++) {
          if (i > 0) {
            line.append(',');
          }
          line.append(csvCell(cols.get(i)));
        }
        zip.write(line.append('\n').toString().getBytes(StandardCharsets.UTF_8));
        for (Map<String, Object> row : rows) {
          line.setLength(0);
          for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
              line.append(',');
            }
            line.append(csvCell(row.get(cols.get(i))));
          }
          zip.write(line.append('\n').toString().getBytes(StandardCharsets.UTF_8));
        }
        zip.closeEntry();
      }
    }
    return buffer.toByteArray();
  }

  private String csvReadme(long userId, LocalDateTime now) {
    return "心屿 MindIsle 个人信息导出包（需求 FR1.6 / PIPL 第 45 条）\n"
        + "生成时间：" + now + "\n导出对象 user_id：" + userId + "\n\n"
        + "每个表名一个 csv；空表不出文件，其条数在导出任务的对账信息里记为 0。\n"
        + "account 节点未单独成文件，它在本任务对应的接口响应（概览页）里可见，"
        + "且刻意不含 password 列。\n"
        + "user_consent 全量在本包里：账号注销后这张表会被物理删除，"
        + "这份包就是「告知—同意」时间线唯一留在你手上的证据。\n";
  }

  private User accountOf(long userId) {
    User user = userMapper.selectById(userId);
    if (user == null) {
      throw new BizException(ErrorCode.USER_NOT_FOUND, "导出对象的账号已不存在");
    }
    return user;
  }

  // ——————————————————————————————————————————————————————————————————————
  // 落盘与读取
  // ——————————————————————————————————————————————————————————————————————

  /**
   * 原子落盘：先写 {@code .part}，再 move。
   *
   * <p>不是讲究，是必要：下载接口和这段写入共用同一个目录，
   * 一个只写了一半的 zip 是可以被 {@code ZipInputStream} 读出前几个条目的 ——
   * 那意味着用户可能拿到「自己前 60% 的数据」而界面显示导出成功。</p>
   */
  private Path write(Artifact artifact) throws Exception {
    Path dir = artifact.path().getParent();
    Files.createDirectories(dir);
    Path part = artifact.path().resolveSibling(artifact.path().getFileName().toString() + ".part");
    Files.write(part, artifact.content());
    try {
      Files.move(part, artifact.path(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
      // 跨盘或某些文件系统不支持原子 move：退回普通覆盖 move。
      // 这里不抛：产物目录在数据盘上而临时目录在系统盘时，ATOMIC_MOVE 必然失败，
      // 把「不能原子」当成致命错误会让整个导出功能在这类机器上不可用。
      Files.move(part, artifact.path(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    return artifact.path();
  }

  private byte[] readArtifact(ExportTask task) {
    try {
      return Files.readAllBytes(Paths.get(task.getFilePath()));
    } catch (Exception e) {
      log.error("导出产物读取失败 id={} file={} 原因={}", task.getId(), task.getFilePath(), e.toString());
      throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "下载链接无效或已过期");
    }
  }

  /** 落盘用的文件名：带 user 与时间戳，不带 token —— 名字本身不该成为第二个秘密。 */
  private static String fileNameOf(ExportTask task, LocalDateTime now) {
    long userId = task.getUserId() == null ? 0L : task.getUserId();
    return "mindisle-export-" + userId + "-" + FILE_STAMP.format(now) + extensionOf(task.getFmt());
  }

  /** 从已落库的路径反推展示名。SUCCESS 态一定有过路径；兜底是为了不让一个历史脏数据变成 500。 */
  private static String fileNameOf(ExportTask task) {
    String path = task == null ? null : task.getFilePath();
    if (path == null || path.isBlank()) {
      return "mindisle-export-" + (task == null || task.getUserId() == null ? 0 : task.getUserId());
    }
    Path name = Paths.get(path).getFileName();
    return name == null ? "mindisle-export" : name.toString();
  }

  private static String extensionOf(String fmt) {
    return ExportTask.FORMAT_CSV.equals(fmt) ? ".zip" : ".json";
  }

  private static String contentTypeOf(String fmt) {
    return ExportTask.FORMAT_CSV.equals(fmt) ? "application/zip" : "application/json;charset=UTF-8";
  }

  /**
   * 32 字节随机数压成 64 个十六进制字符。
   *
   * <p><b>为什么不用自增 id 或时间戳当下载口令</b>：导出包里是<b>一个人的全部数据</b>，
   * 而 {@code export_task.id} 是连续的、时间戳是可推算的——任何一条泄露（浏览器历史、
   * 代理日志、截图）都会让攻击者顺着手势枚举别人的包。口令的强度只取决于它是不是
   * 不可预测，所以这里只信 {@link SecureRandom}（NFR7）。
   */
  private static String newToken() {
    byte[] buf = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(buf);
    return HEX.formatHex(buf);
  }

  private static String normalizeFormat(String format) {
    String fmt = format == null ? "" : format.trim().toLowerCase(java.util.Locale.ROOT);
    if (fmt.isEmpty()) {
      return ExportTask.FORMAT_JSON;
    }
    if (ExportTask.FORMAT_JSON.equals(fmt) || ExportTask.FORMAT_CSV.equals(fmt)) {
      return fmt;
    }
    throw new BizException(ErrorCode.PARAM_INVALID, "导出格式只支持 json 与 csv");
  }

  /** 对账信息里截断标记用的键名。带下划线前缀，与任何真实表名都不可能撞。 */
  private static final String KEY_TRUNCATED = "_truncated";

  /** 写进包里的口径说明。它是一次交付物的一部分，不是注释。 */
  private static final String NOTICE = "本包按需求 FR1.6 生成，含该系统记录的你的一切数据行"
      + "（含软删行），其中 user_consent 为授权与撤回的完整时间线；账号注销后该表将被物理删除，"
      + "请妥善保存本包。password 列不在任何导出内容中。";

  // ——————————————————————————————————————————————————————————————————————
  // 值归一与 CSV 转义
  // ——————————————————————————————————————————————————————————————————————

  /**
   * JDBC 返回值 → 可稳定序列化的值。
   *
   * <p>三类必须处理：① {@code java.sql.Timestamp} 交给 Jackson 会变成毫秒数，
   * 时间戳写成 1769683200000 对当事人毫无意义，转成 ISO 串；
   * ② {@code byte[]}（二进制列）会被 Jackson 写成巨大的数字数组，转 base64 并加前缀，
   * 让读的人知道那不是原文；③ {@code Clob}/{@code Blob} 是游标型对象，
   * 不取出来的话序列化时会抛「连接已关闭」。其余原样，保持可读性。</p>
   */
  private static Object plain(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof byte[] bytes) {
      return "base64:" + Base64.getEncoder().encodeToString(bytes);
    }
    if (value instanceof java.sql.Timestamp ts) {
      return ts.toLocalDateTime().toString();
    }
    if (value instanceof java.time.LocalDateTime dt) {
      return dt.toString();
    }
    if (value instanceof java.time.LocalDate d) {
      return d.toString();
    }
    if (value instanceof java.time.LocalTime t) {
      return t.toString();
    }
    if (value instanceof java.sql.Date d) {
      return d.toLocalDate().toString();
    }
    if (value instanceof java.sql.Time t) {
      return t.toLocalTime().toString();
    }
    if (value instanceof java.sql.Blob blob) {
      try {
        long n = Math.min(blob.length(), MAX_BLOB_BYTES);
        return "base64:" + Base64.getEncoder()
            .encodeToString(blob.getBytes(1, (int) n));
      } catch (Exception e) {
        return "blob:unavailable";
      }
    }
    if (value instanceof java.sql.Clob clob) {
      try {
        long n = Math.min(clob.length(), MAX_BLOB_BYTES);
        return clob.getSubString(1, (int) n);
      } catch (Exception e) {
        return "clob:unavailable";
      }
    }
    return value;
  }

  /**
   * CSV 单元格。两层处理，缺一层都会出事：
   * ① 标准转义：含引号 / 逗号 / 换行的值整体加引号，内部引号翻倍；
   * ② <b>公式注入防护</b>：以 {@code = + - @} 或制表符、CR 开头的值前面加一个单引号。
   * 第二层是导出场景特有的风险 —— 帖子与昵称的正文是用户写的，
   * 审核员或本人用 Excel 打开这个 csv 时，{@code =HYPERLINK(...)} 会被当成公式执行。
   * 这一条在同类开源项目里普遍没做（同类项目调研与实现方案.md §3 记过），
   * 而它正是「导出个人信息」这个动作应该自带的防护。
   */
  private static String csvCell(Object value) {
    Object p = plain(value);
    if (p == null) {
      return "";
    }
    String s = p.toString();
    if (!s.isEmpty()) {
      char head = s.charAt(0);
      // 9 = TAB，13 = CR。用数值而不是字面量：这两个控制字符写在源码里看不见，反而容易被误改
      if (head == '=' || head == '+' || head == '-' || head == '@' || head == 9 || head == 13) {
        s = "'" + s;
      }
    }
    // 10 = LF，13 = CR，34 = 双引号
    boolean needQuote = s.indexOf(34) >= 0 || s.indexOf(',') >= 0 || s.indexOf(10) >= 0
        || s.indexOf(13) >= 0;
    if (!needQuote) {
      return s;
    }
    StringBuilder escaped = new StringBuilder(s.length() + 8);
    escaped.append('"');
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch == '"') {
        escaped.append('"');
      }
      escaped.append(ch);
    }
    return escaped.append('"').toString();
  }

  /** Clob/Blob 的读取上限：一个 20MB 的头像 base64 进 csv 只会让包打不开，而它不是文本数据。 */
  private static final int MAX_BLOB_BYTES = 262144;

  /** 视图：把库里的行压成前端好渲染的形状。不暴露 filePath（NFR7：目录结构是白送的情报）。 */
  private PrivacyViews.ExportTaskView view(ExportTask task) {
    if (task == null) {
      return null;
    }
    boolean ready = ExportTask.SUCCESS.equals(task.getStatus())
        && task.getToken() != null && !task.getToken().isBlank()
        && task.getExpireAt() != null && task.getExpireAt().isAfter(LocalDateTime.now());
    long bytes = task.getFileBytes() == null ? 0L : task.getFileBytes();
    return new PrivacyViews.ExportTaskView(task.getId(), task.getFmt(), task.getStatus(), bytes,
        task.getRowCounts(), ready, ready ? "/api/privacy/export/file?token=" + task.getToken() : null,
        task.getExpireAt(), task.getCreatedAt(), task.getErrorText());
  }

  /** 一句能给人看的失败原因。堆栈进日志，响应里只留这一句（NFR7 + 用户体验）。 */
  private static String briefFailure(Exception e) {
    String msg = e.getMessage();
    if (msg == null || msg.isBlank()) {
      msg = e.getClass().getSimpleName();
    }
    return "生成失败：" + (msg.length() > 160 ? msg.substring(0, 160) : msg);
  }
}
