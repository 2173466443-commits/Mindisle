package com.mindisle.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindisle.admin.AdminOpLogService.Ctx;
import com.mindisle.admin.dto.DictRow;
import com.mindisle.admin.dto.WordGroupRow;
import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.audit.SensitiveWordEngine.CheckResult;
import com.mindisle.audit.SensitiveWordEngine.Hit;
import com.mindisle.audit.TextNormalizer;
import com.mindisle.cache.CacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AdminOpLog;
import com.mindisle.entity.SensitiveWord;
import com.mindisle.entity.SensitiveWordGroup;
import com.mindisle.entity.SysConfig;
import com.mindisle.mapper.SensitiveWordGroupMapper;
import com.mindisle.mapper.SensitiveWordMapper;
import com.mindisle.mapper.SysConfigMapper;

/**
 * 参数与词库管理 + 热更新（手册 §9.1 第 7 条 · 需求 FR7.1 FR7.2 · 任务 T6.2 T6.3）。
 *
 * <p><b>热更新的真源是数据库，快照文件只是它的投影</b>：词条的增删改全部落
 * {@code sensitive_word}，再由 {@link #rebuildDict} 把「启用词 + 组口径」重建成 6 列 TAB 快照，
 * 交给 {@link SensitiveWordEngine#reload(String)}。运行期的 jar 里改不了资源文件，
 * 所以 {@code mindisle.audit.dict-path} 指向一个可写快照；没配也能改词——进程内立刻生效，
 * 只是重启后会回落到 classpath 快照，这一点 {@link ReloadResult#snapshotWritten()} 会如实告诉前端，
 * 界面不许把「已生效」写成「已持久化」。</p>
 *
 * <p><b>版本号只在真有变化时递增</b>：{@code audit.wordlib_version} 是
 * {@code audit_record.wordlib_version} 的取值来源（FR7.1「命中日志可回放」靠的就是它）。
 * 空跑一次重建也把版本推高一格，等于让所有历史留痕的版本号失去意义。</p>
 *
 * <p><b>{@code whole} 匹配型拒写</b>：引擎实现了 contains/regex 两种，DDL 里那第三个枚举值是
 * 早期设计留下的。让管理员在界面上选到一个不生效的匹配型，比直接拒绝更坏——
 * 他会以为这个词已经按整词生效了。</p>
 *
 * <p><b>试审（{@link #trial}）不写 {@code audit_record}</b>：那张表的语义是
 * 「某个真实对象在某时刻被某个词库版本判定过」，试审文本不属于任何对象，
 * {@code target_type} 的六个枚举值里也没有「试审」。硬塞一行只会让 FR7.7 的处置链
 * 多出一条对不上号的记录。改口径的依据写在 dev-log，不藏在代码注释里。</p>
 */
@Service
public class ConfigAdminService {

  private static final Logger log = LoggerFactory.getLogger(ConfigAdminService.class);

  /** sys_config 里词库版本号的键；写它等于对全体审核留痕声明「从这一刻起词库换了」。 */
  static final String CFG_WORDLIB_VERSION = "audit.wordlib_version";
  static final int CFG_VALUE_MAX = 2000;
  static final int CFG_KEY_MAX = 64;
  static final int WORD_MAX = 64;
  /** value_type 的五个取值照抄 08_config.sql 的 ENUM。 */
  static final Set<String> VALUE_TYPES = Set.of("string", "int", "decimal", "bool", "json");
  /** 引擎真正实现的匹配型；{@code whole} 在枚举里但没有实现，故拒写。 */
  static final Set<String> SUPPORTED_MATCH_TYPES = Set.of(SensitiveWord.MATCH_CONTAINS,
      SensitiveWord.MATCH_REGEX);
  /** 版本广播键的存活时长：到期只会让各进程少做一次比对，不影响正确性，所以给得很长。 */
  private static final Duration VERSION_TTL = Duration.ofDays(30);
  private static final Pattern V_PATTERN = Pattern.compile("^v(\\d+)\\.(\\d+)$");
  private static final List<String> TRIAL_SIDES = List.of("user", "ai");

  private final SysConfigMapper configMapper;
  private final SensitiveWordMapper wordMapper;
  private final SensitiveWordGroupMapper groupMapper;
  private final SensitiveWordEngine engine;
  private final CacheService cacheService;
  private final MindisleProperties properties;
  private final AdminOpLogService opLogService;

  public ConfigAdminService(SysConfigMapper configMapper, SensitiveWordMapper wordMapper,
      SensitiveWordGroupMapper groupMapper, SensitiveWordEngine engine, CacheService cacheService,
      MindisleProperties properties, AdminOpLogService opLogService) {
    this.configMapper = configMapper;
    this.wordMapper = wordMapper;
    this.groupMapper = groupMapper;
    this.engine = engine;
    this.cacheService = cacheService;
    this.properties = properties;
    this.opLogService = opLogService;
  }

  /** 一次热更新的结果：三个数字让「改完了」变成可核对的事实，而不是一句提示语。 */
  public record ReloadResult(String version, int wordCount, int scanned, boolean snapshotWritten,
      String snapshotPath, Long opLogId) {
  }

  /** 试审出参：归一化前后 + 命中明细 + 当时生效的词库版本（FR7.1 可回放的「回放」部分）。 */
  public record TrialView(String raw, String normalized, String dictVersion, boolean hit,
      String category, String level, String action, int hitCount, List<Hit> hits) {
  }

  // ================================================================ sys_config

  /** 参数列表：{@code groupKey} 为空返回全部（A8 与 A9 的配置页共用一个接口）。 */
  public List<SysConfig> listConfigs(String groupKey) {
    String g = AuditQueueService.blankToNull(groupKey);
    return configMapper.selectList(new LambdaQueryWrapper<SysConfig>()
        .eq(g != null, SysConfig::getGroupKey, g)
        .orderByAsc(SysConfig::getGroupKey)
        .orderByAsc(SysConfig::getCfgKey));
  }

  /**
   * 改一个参数：{@code editable=0} 的键直接拒绝，值按 {@code value_type} 转型校验。
   *
   * <p>校验放在写库之前是因为读取侧（{@code RiskScoringService}、{@code TicketService.slaFor}）
   * 全部是「转型失败就回落默认值」。如果这里不拦，配置界面会显示保存成功，
   * 而运行值悄悄回到默认——那是需求 §12 规范1「阈值不写死在代码里」最讽刺的破法。</p>
   */
  @Transactional
  public SysConfig updateConfig(String cfgKey, String value, Ctx ctx, LocalDateTime now) {
    requireOperator(ctx);
    String key = AuditQueueService.blankToNull(cfgKey);
    if (key == null || key.length() > CFG_KEY_MAX) {
      throw new BizException(ErrorCode.PARAM_INVALID, "参数键必填且不超过 " + CFG_KEY_MAX + " 字符");
    }
    SysConfig row = findByKey(key);
    if (!Integer.valueOf(1).equals(row.getEditable())) {
      throw new BizException(ErrorCode.FORBIDDEN, "该参数不允许在管理端修改：" + key);
    }
    String text = value == null ? "" : value.trim();
    if (text.isEmpty()) {
      throw new BizException(ErrorCode.PARAM_INVALID, "参数值不能为空（要清空语义请给显式取值）");
    }
    if (text.length() > CFG_VALUE_MAX) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "参数值超过列宽 " + CFG_VALUE_MAX + " 字符，当前 " + text.length());
    }
    validateType(row.getValueType(), text, key);
    String before = row.getCfgValue();
    row.setCfgValue(text);
    row.setUpdatedBy(ctx == null ? null : ctx.operatorId());
    row.setUpdatedAt(now);
    configMapper.updateById(row);
    opLogService.success(ctx, AdminOpLog.ACTION_UPDATE_CONFIG, "sys_config:" + key, row.getId(),
        key + ": " + AuditQueueService.cut(before, 80) + " -> " + AuditQueueService.cut(text, 80));
    return row;
  }

  private SysConfig findByKey(String key) {
    SysConfig row = configMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
        .eq(SysConfig::getCfgKey, key).last("limit 1"));
    if (row == null) {
      throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "参数不存在：" + key);
    }
    return row;
  }

  private static void validateType(String valueType, String text, String key) {
    String type = valueType == null ? "string" : valueType;
    switch (type) {
      case "int" -> {
        try {
          Integer.parseInt(text);
        } catch (NumberFormatException e) {
          throw new BizException(ErrorCode.PARAM_INVALID, key + " 需要整数，收到：" + text);
        }
      }
      case "decimal" -> {
        double d;
        try {
          d = Double.parseDouble(text);
        } catch (NumberFormatException e) {
          throw new BizException(ErrorCode.PARAM_INVALID, key + " 需要小数，收到：" + text);
        }
        if (!Double.isFinite(d) || d < 0 || d > 1) {
          throw new BizException(ErrorCode.PARAM_INVALID, key + " 需要在 0~1 之间，收到：" + text);
        }
      }
      case "bool" -> {
        if (!List.of("true", "false", "1", "0").contains(text.toLowerCase())) {
          throw new BizException(ErrorCode.PARAM_INVALID, key + " 需要布尔值，收到：" + text);
        }
      }
      case "json" -> {
        char first = text.charAt(0);
        if (first != '{' && first != '[' && !text.equalsIgnoreCase("null")) {
          throw new BizException(ErrorCode.PARAM_INVALID, key + " 需要合法 JSON，收到：" + text);
        }
      }
      default -> {
        if (!VALUE_TYPES.contains(type)) {
          log.warn("sys_config.value_type 出现未知取值 {}（{}），按 string 处理", type, key);
        }
      }
    }
  }

  // ================================================================ 词库 CRUD

  /** A8 左栏：组 + 实时词数，{@code storedWordCnt} 与 {@code wordCnt} 不等就是账本漂移。 */
  public List<WordGroupRow> listGroups() {
    return wordMapper.listGroups();
  }

  /** A8 右栏：词条分页（keyword 前缀匹配，能吃到 uk_word 索引）。 */
  public PageResult<DictRow> pageWords(String keyword, Long groupId, Integer status,
      PageQuery query) {
    String kw = AuditQueueService.blankToNull(keyword);
    String like = kw == null ? null : kw + "%";
    Integer statusFilter = status;
    if (statusFilter != null && statusFilter != 0 && statusFilter != 1) {
      throw new BizException(ErrorCode.PARAM_INVALID, "词条状态只能是 0（停用）或 1（启用）");
    }
    PageQuery q = query.normalize();
    long total = wordMapper.countWords(kw, like, groupId, statusFilter);
    List<DictRow> rows = wordMapper.pageWords(kw, like, groupId, statusFilter, q.offset(),
        q.getSize());
    return PageResult.of(rows, total, q);
  }

  /**
   * 新增词条。同名词条若已被软删，走 {@code resurrect} 救活而不是插入撞 {@code uk_word}。
   *
   * <p>救活时把 {@code hit_cnt} 归零：这一列要用来算误报率，留着旧账会让「重新启用的新词」
   * 一上线就背上前世的历史。</p>
   */
  @Transactional
  public SensitiveWord addWord(String word, Long groupId, String matchType, Ctx ctx,
      LocalDateTime now) {
    requireOperator(ctx);
    String text = AuditQueueService.blankToNull(word);
    if (text == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "词条不能为空");
    }
    if (text.codePointCount(0, text.length()) > WORD_MAX) {
      throw new BizException(ErrorCode.PARAM_INVALID, "词条不超过 " + WORD_MAX + " 字符");
    }
    if (groupId == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "必须选择词条所属的词库组");
    }
    SensitiveWordGroup group = groupMapper.selectById(groupId);
    if (group == null || Integer.valueOf(1).equals(group.getDeleted())) {
      throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "词库组不存在：id=" + groupId);
    }
    String type = AuditQueueService.blankToNull(matchType);
    if (type == null) {
      type = SensitiveWord.MATCH_CONTAINS;
    }
    if (SensitiveWord.MATCH_WHOLE.equals(type)) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "整词匹配（whole）引擎未实现，请用 contains 或 regex");
    }
    if (!SUPPORTED_MATCH_TYPES.contains(type)) {
      throw new BizException(ErrorCode.PARAM_INVALID, "匹配方式只能是 contains/regex");
    }
    if (SensitiveWord.MATCH_REGEX.equals(type)) {
      try {
        java.util.regex.Pattern.compile(text);
      } catch (RuntimeException e) {
        throw new BizException(ErrorCode.PARAM_INVALID, "正则不合法：" + e.getMessage());
      }
    }
    SensitiveWord existing = wordMapper.findByWordAny(text);
    if (existing != null && Integer.valueOf(0).equals(existing.getDeleted())) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "这个词已经在词库里（组：" + existing.getGroupId() + "，id=" + existing.getId() + "）");
    }
    if (existing != null) {
      wordMapper.resurrect(existing.getId(), groupId, type, now);
      opLogService.success(ctx, AdminOpLog.ACTION_UPDATE_CONFIG, "sensitive_word:" + existing.getId(),
          existing.getId(), "救活软删词条|" + text + "|组=" + groupId);
      existing.setDeleted(0);
      existing.setStatus(SensitiveWord.STATUS_ENABLED);
      existing.setGroupId(groupId);
      existing.setMatchType(type);
      return existing;
    }
    SensitiveWord row = new SensitiveWord();
    row.setGroupId(groupId);
    row.setWord(text);
    row.setVariantHash(variantHash(text));
    row.setMatchType(type);
    row.setHitCnt(0);
    row.setStatus(SensitiveWord.STATUS_ENABLED);
    row.setDeleted(0);
    row.setCreatedAt(now);
    row.setUpdatedAt(now);
    wordMapper.insert(row);
    opLogService.success(ctx, AdminOpLog.ACTION_UPDATE_CONFIG, "sensitive_word:" + row.getId(),
        row.getId(), "新增词条|" + text + "|组=" + groupId + "|" + type);
    return row;
  }

  /** 启用/停用：停用不删行，{@code hit_cnt} 要留着算误报率（DDL 注释原话）。 */
  @Transactional
  public SensitiveWord updateWordStatus(long id, int status, Ctx ctx, LocalDateTime now) {
    requireOperator(ctx);
    if (status != SensitiveWord.STATUS_ENABLED && status != SensitiveWord.STATUS_DISABLED) {
      throw new BizException(ErrorCode.PARAM_INVALID, "词条状态只能是 0 或 1");
    }
    SensitiveWord row = wordMapper.selectById(id);
    if (row == null || Integer.valueOf(1).equals(row.getDeleted())) {
      throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "词条不存在：id=" + id);
    }
    if (wordMapper.updateStatus(id, status, now) == 0) {
      throw new BizException(ErrorCode.FORBIDDEN, "词条已被删除，请刷新列表");
    }
    row.setStatus(status);
    opLogService.success(ctx, AdminOpLog.ACTION_UPDATE_CONFIG, "sensitive_word:" + id, id,
        (status == SensitiveWord.STATUS_ENABLED ? "启用" : "停用") + "|" + row.getWord());
    return row;
  }

  /** 软删词条：唯一键不看 deleted 位，所以删除后同名词条靠 resurrect 回来（见 {@link #addWord}）。 */
  @Transactional
  public void deleteWord(long id, Ctx ctx) {
    requireOperator(ctx);
    SensitiveWord row = wordMapper.selectById(id);
    if (row == null || Integer.valueOf(1).equals(row.getDeleted())) {
      throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "词条不存在：id=" + id);
    }
    wordMapper.deleteById(id);
    opLogService.success(ctx, AdminOpLog.ACTION_UPDATE_CONFIG, "sensitive_word:" + id, id,
        "删除词条|" + row.getWord());
  }

  // ================================================================ 热更新（FR7.1）

  /**
   * 重建快照并热更新词库（{@code 改词 → 秒级生效}，FR7.1 验收判据）。
   *
   * <p>顺序是刻意的：先 reload 引擎、再写 sys_config、最后广播缓存版本。反过来做的话，
   * 别的进程一旦发现新版本就会去读盘外快照，而那一刻快照可能还没写完或引擎还没换；
   * reload 失败（列数不符、正则非法、空词库）时三步全不做，线上继续用旧词库，
   * 于是「改坏了词库」不会让审核链路停摆——这条降级写在需求 FR7.2 的「引擎故障退回纯规则」里。</p>
   */
  @Transactional
  public ReloadResult rebuildDict(Ctx ctx, LocalDateTime now) {
    requireOperator(ctx);
    List<DictRow> rows = wordMapper.listSnapshotRows();
    if (rows.isEmpty()) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "词库里没有任何启用词条，拒绝用空词库覆盖当前生效版本");
    }
    String current = readVersion(CFG_WORDLIB_VERSION);
    String next = nextVersion(current);
    String snapshot = renderSnapshot(next, rows);
    String applied;
    try {
      applied = engine.reload(snapshot);
    } catch (IllegalStateException e) {
      opLogService.fail(ctx, AdminOpLog.ACTION_UPDATE_CONFIG, "wordlib:" + next, null,
          "词库重载失败：" + AuditQueueService.cut(e.getMessage(), 200));
      throw new BizException(ErrorCode.CACHE_UNAVAILABLE, "词库重建失败，线上仍用 " + engine.version()
          + "：" + e.getMessage());
    }
    boolean written = writeSnapshot(applied, snapshot);
    bumpVersionConfig(applied, ctx, now);
    try {
      cacheService.set(properties.getAudit().getDictVersionKey(), applied, VERSION_TTL);
    } catch (RuntimeException e) {
      log.warn("词库版本广播失败（{}），其它进程会沿用到下次比对：{}", applied, e.toString());
    }
    groupMapper.refreshAllWordCnt(now);
    Long opLogId = opLogService.success(ctx, AdminOpLog.ACTION_UPDATE_CONFIG,
        "wordlib:" + applied, null,
        "热更新 " + current + " -> " + applied + "|词条=" + rows.size() + "|落盘=" + written
            + "|路径=" + properties.getAudit().getDictPath());
    log.info("词库热更新完成：{} -> {}，生效词条 {} 条，快照落盘 {}", current, applied,
        rows.size(), written);
    return new ReloadResult(applied, engine.wordCount(), rows.size(), written,
        properties.getAudit().getDictPath(), opLogId);
  }

  /**
   * 试审：给一段文本，返回归一化预览与命中明细，不写任何库。
   *
   * <p>{@code side} 只允许 user/ai，与全站 {@code engine.check(text, side)} 的调用姿势一致；
   * 默认 user（发帖侧），因为管理员想验的多半是「这条词会不会拦住作者的帖子」。</p>
   */
  public TrialView trial(String text, String side) {
    if (text == null || text.isBlank()) {
      throw new BizException(ErrorCode.PARAM_INVALID, "试审文本不能为空");
    }
    String s = AuditQueueService.blankToNull(side);
    if (s == null) {
      s = "user";
    }
    if (!TRIAL_SIDES.contains(s)) {
      throw new BizException(ErrorCode.PARAM_INVALID, "side 只能是 user/ai");
    }
    CheckResult result = engine.check(text, s);
    return new TrialView(text, TextNormalizer.normalize(text).text(), engine.version(),
        result.hit(), result.category(), result.level(), result.action(), result.hitCount(),
        result.hits());
  }

  /** 当前引擎内存词库的版本与词条数（A8 顶栏与 A2 大屏共用）。 */
  public java.util.Map<String, Object> engineStatus() {
    java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("version", engine.version());
    out.put("wordCount", engine.wordCount());
    out.put("dbVersion", readVersion(CFG_WORDLIB_VERSION));
    out.put("dictPath", properties.getAudit().getDictPath());
    out.put("cacheMode", cacheService.mode());
    return out;
  }

  // ================================================================ 工具

  /** 6 列 TAB：组名 / level / action / 作用侧 / 匹配型 / 词条，顺序与 classpath 快照的表头逐字一致。 */
  static String renderSnapshot(String version, List<DictRow> rows) {
    StringBuilder sb = new StringBuilder(rows.size() * 48 + 128);
    sb.append("# 心屿 · 敏感词词库快照（管理端重建，勿手改）\n");
    sb.append("#version=").append(version).append('\n');
    sb.append("# 列（TAB 分隔）：组名\tlevel\taction\t作用侧\t匹配型\t词条\n");
    for (DictRow row : rows) {
      sb.append(nz(row.getGroupName())).append('\t').append(nz(row.getLevel())).append('\t')
          .append(nz(row.getAction())).append('\t').append(nz(row.getHitScope())).append('\t')
          .append(nz(row.getMatchType())).append('\t').append(nz(row.getWord())).append('\n');
    }
    return sb.toString();
  }

  /**
   * 版本递增：{@code v0.1 -> v0.2}，读不到或形状不认识就用时间戳版本，
   * 因为「版本号」的第一职责是可区分，其次才是好看。
   */
  static String nextVersion(String current) {
    if (current != null) {
      Matcher m = V_PATTERN.matcher(current.trim());
      if (m.matches()) {
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        if (minor >= Integer.MAX_VALUE - 1) {
          return "v" + (major + 1) + ".0";
        }
        return "v" + major + "." + (minor + 1);
      }
    }
    return "v" + LocalDateTime.now().format(java.time.format.DateTimeFormatter
        .ofPattern("yyMMdd.HHmmss"));
  }

  private String readVersion(String key) {
    SysConfig row = configMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
        .eq(SysConfig::getCfgKey, key).last("limit 1"));
    return row == null ? null : row.getCfgValue();
  }

  /** 把新版本写回 sys_config。这一行 editable=0（种子脚本给的口径），所以只能由本方法代写。 */
  private void bumpVersionConfig(String version, Ctx ctx, LocalDateTime now) {
    SysConfig row = configMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
        .eq(SysConfig::getCfgKey, CFG_WORDLIB_VERSION).last("limit 1"));
    if (row == null) {
      log.warn("sys_config 里缺少 {}，版本号只存在于引擎内存（重启即回落 classpath 快照）",
          CFG_WORDLIB_VERSION);
      return;
    }
    row.setCfgValue(AuditQueueService.cut(version, CFG_VALUE_MAX));
    row.setUpdatedBy(ctx == null ? null : ctx.operatorId());
    row.setUpdatedAt(now);
    configMapper.updateById(row);
  }

  /** 落盘外快照；未配置或写失败都只记日志并把 {@code snapshotWritten=false} 交回界面。 */
  private boolean writeSnapshot(String version, String snapshot) {
    String configured = properties.getAudit().getDictPath();
    String path = AuditQueueService.blankToNull(configured);
    if (path == null) {
      log.warn("未配置 mindisle.audit.dict-path：词库 v{} 只在当前进程生效，重启会回落 classpath 快照",
          version);
      return false;
    }
    try {
      Path target = Path.of(path);
      if (target.getParent() != null) {
        Files.createDirectories(target.getParent());
      }
      Files.writeString(target, snapshot, StandardCharsets.UTF_8);
      return true;
    } catch (IOException | RuntimeException e) {
      log.error("词库快照落盘失败（{}）：{}", path, e.toString());
      return false;
    }
  }

  /** 变体归一化后的 MD5：与 {@link TextNormalizer} 同一套全半角/大小写/装饰符折叠。 */
  static String variantHash(String word) {
    String normalized = TextNormalizer.normalize(word).text();
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(32);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 缺少 MD5，无法生成 variant_hash", e);
    }
  }

  private static String nz(String value) {
    return value == null ? "" : value.trim();
  }

  private static long requireOperator(Ctx ctx) {
    if (ctx == null || ctx.operatorId() == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED, "管理端操作需要登录身份");
    }
    return ctx.operatorId();
  }
}