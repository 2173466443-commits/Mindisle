package com.mindisle.web;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.cache.CacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.Result;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.SysConfig;
import com.mindisle.mapper.SysConfigMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 系统与配置接口（手册 §5.7 T2.9 · 需求 FR8.6、§11.2 降级口、§12 规范 4）。
 *
 * <p>全部匿名可访问：前端要靠 /info 判断「后端连接正常」，求助卡片更不允许因为
 * 没登录就取不到 12356。这里暴露的都是版本号与运行模式，不含任何用户数据。
 */
@RestController
@RequestMapping("/api/system")
@Tag(name = "2 系统", description = "运行状态、版本号、危机求助卡片与公开参数")
public class SystemController {

  /** 与 pom.xml 的 project version 同步修改；不读运行时包信息是为了避免 fat jar 下取不到。 */
  static final String APP_VERSION = "0.0.1-SNAPSHOT";

  /**
   * 允许匿名读取的 sys_config 白名单。
   *
   * <p>其余参数（阈值、预算、模型名）一律只进管理端，绝不为「方便」而全量开放：
   * 把 risk.l3_score 这样的判定阈值暴露给公网，等于告诉绕过者该把话说得多轻。
   */
  static final List<String> PUBLIC_CONFIG_KEYS = List.of(
      "prompt.version", "prompt.crisis_card", "audit.wordlib_version", "ai.model");

  /** 求助卡片兜底文案：连数据库都读不到时也必须让用户看到电话，这是 L0 级功能。 */
  static final String FALLBACK_TEXT = "如果你现在很难受，可以拨打 12356 心理援助热线，24 小时都有人接。";

  private final SysConfigMapper sysConfigMapper;
  private final CacheService cacheService;
  private final MindisleProperties properties;
  private final Environment environment;
  private final ObjectMapper objectMapper;

  public SystemController(SysConfigMapper sysConfigMapper, CacheService cacheService,
                          MindisleProperties properties, Environment environment,
                          ObjectMapper objectMapper) {
    this.sysConfigMapper = sysConfigMapper;
    this.cacheService = cacheService;
    this.properties = properties;
    this.environment = environment;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/info")
  @Operation(summary = "运行状态（含缓存降级模式，供答辩演示 §11.2 开关）")
  public Result<Map<String, Object>> info() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("app", "MindIsle 心屿");
    data.put("version", APP_VERSION);
    data.put("profiles", String.join(",", environment.getActiveProfiles()));
    data.put("cacheMode", cacheService.mode());
    data.put("llmProvider", properties.getLlm().getProvider());
    data.put("javaVersion", System.getProperty("java.version"));
    data.put("serverTime", LocalDateTime.now().toString());
    return Result.ok(data);
  }

  @GetMapping("/version")
  @Operation(summary = "前后端契约版本，前端据此提示强刷新")
  public Result<Map<String, Object>> version() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("version", APP_VERSION);
    data.put("apiPrefix", "/api");
    data.put("openapi", "/v3/api-docs");
    data.put("docs", "/doc.html");
    return Result.ok(data);
  }

  /**
   * 危机求助卡片（需求 FR4.4 / §12 规范 4）。
   *
   * <p>读 sys_config 的 prompt.crisis_card，解析或查询失败时**吞掉异常回落内置值**，
   * 并用 source 字段如实标明来源。这里不抛 90002 是刻意的：求助入口的可用性优先级
   * 高于「让前端知道配置过期」，宁可展示略旧的文案也不能空着。
   */
  @GetMapping("/hotline")
  @Operation(summary = "L0-L3 通用求助卡片（无需登录，DB 不可用时回落内置值）")
  public Result<Map<String, Object>> hotline() {
    Map<String, Object> data = new LinkedHashMap<>();
    String source = "fallback";
    String hotline = properties.getCrisis().getHotline();
    String text = FALLBACK_TEXT;
    String display = "L2/L3 置顶卡片";
    try {
      SysConfig config = sysConfigMapper.findByKey("prompt.crisis_card");
      if (config != null && config.getCfgValue() != null && !config.getCfgValue().isBlank()) {
        JsonNode node = objectMapper.readTree(config.getCfgValue());
        hotline = text(node, "hotline", hotline);
        text = text(node, "text", text);
        display = text(node, "display", display);
        source = "sys_config";
      }
    } catch (Exception e) {
      // 数据库不可用、JSON 非法、字段缺失都走这里：求助卡片仍然返回，来源标记不变。
      data.put("degraded", true);
    }
    data.put("hotline", hotline);
    data.put("text", text);
    data.put("display", display);
    data.put("source", source);
    return Result.ok(data);
  }

  @GetMapping("/configs")
  @Operation(summary = "匿名可读的公开参数（白名单，传 keys 也只会取交集）")
  public Result<List<Map<String, Object>>> configs(
      @Parameter(description = "逗号分隔的 key 列表，缺省返回全部白名单项")
      @RequestParam(name = "keys", required = false) String keys) {
    Set<String> wanted = new LinkedHashSet<>();
    if (keys == null || keys.isBlank()) {
      wanted.addAll(PUBLIC_CONFIG_KEYS);
    } else {
      Arrays.stream(keys.split(","))
          .map(String::trim)
          .filter(PUBLIC_CONFIG_KEYS::contains)
          .forEach(wanted::add);
    }
    if (wanted.isEmpty()) {
      return Result.ok(List.of());
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    try {
      for (SysConfig config : sysConfigMapper.listByKeys(new ArrayList<>(wanted))) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", config.getCfgKey());
        row.put("value", config.getCfgValue());
        row.put("valueType", config.getValueType());
        rows.add(row);
      }
    } catch (RuntimeException e) {
      // 参数是展示性数据，取不到就让前端显式报错并稍后重试，比静默返回空列表更诚实。
      throw new BizException(ErrorCode.DB_UNAVAILABLE, "公开参数暂时读取不到，请稍后重试");
    }
    return Result.ok(rows);
  }

  private static String text(JsonNode node, String field, String fallback) {
    JsonNode value = node.get(field);
    return value == null || value.asText().isBlank() ? fallback : value.asText();
  }
}
