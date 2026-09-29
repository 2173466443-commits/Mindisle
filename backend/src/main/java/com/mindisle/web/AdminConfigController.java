package com.mindisle.web;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.admin.ConfigAdminService;
import com.mindisle.admin.ConfigAdminService.ReloadResult;
import com.mindisle.admin.ConfigAdminService.TrialView;
import com.mindisle.admin.dto.DictRow;
import com.mindisle.admin.dto.WordGroupRow;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.common.Result;
import com.mindisle.entity.SensitiveWord;
import com.mindisle.entity.SysConfig;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * A8 参数与词库管理（任务 T6.8 · 需求 FR7.1「改词 5 秒内生效」、FR10 参数可配）。
 *
 * <p>三组路径合并在一个类里（configs / words / dict），因为它们是同一个后台页面 A8 的三段：
 * 改参数、改词条、让改动生效。分开三个类只会让「改词之后必须点热更新」这条流程在代码里散成三处。</p>
 *
 * <p>{@code /dict/trial} 是只读的试审：它不落 audit_record（那张表的 target_type 枚举里没有 trial 档，
 * 硬塞会污染 FR7.7 的处置链），也不写 oplog（ACTIONS 白名单里没有 WORD_TRIAL，
 * 拿 UPDATE_CONFIG 记一次只读操作更糟）。要验证词库效果，看返回的命中明细就够了。</p>
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "5 管理端-配置与词库", description = "系统参数、敏感词 CRUD、词库热更新与试审")
public class AdminConfigController {

  private final ConfigAdminService configService;

  public AdminConfigController(ConfigAdminService configService) {
    this.configService = configService;
  }

  /** 改参数入参。 */
  public record ConfigUpdateReq(String cfgKey, String value) {
  }

  /** 新增词条入参：groupId 为空时由服务层归到默认组，matchType 缺省 contains。 */
  public record WordAddReq(String word, Long groupId, String matchType) {
  }

  /** 词条启停入参。 */
  public record WordStatusReq(Long id, Integer status) {
  }

  /** 试审入参：side=user 检用户输入，side=ai 检模型输出。 */
  public record TrialReq(String text, String side) {
  }

  // ------------------------------------------------------------ 系统参数

  @GetMapping("/configs")
  @Operation(summary = "参数列表（可按分组过滤；公开读的那份在 /api/system/configs，本接口带类型与更新人）")
  public Result<List<SysConfig>> listConfigs(
      @RequestParam(name = "groupKey", required = false) String groupKey, @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(configService.listConfigs(groupKey));
  }

  @PostMapping("/configs/update")
  @Operation(summary = "修改参数（值类型按 sys_config.value_type 校验，改动逐条写 admin_op_log）")
  public Result<SysConfig> updateConfig(@RequestBody ConfigUpdateReq req,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (req == null || req.cfgKey() == null || req.cfgKey().isBlank()) {
      throw new BizException(ErrorCode.PARAM_INVALID, "cfgKey 必填");
    }
    return Result.ok(configService.updateConfig(req.cfgKey(), req.value(),
        AdminSupport.ctxOf(current, http), AdminSupport.now()));
  }

  // ------------------------------------------------------------ 敏感词

  @GetMapping("/words")
  @Operation(summary = "词库分页（关键字精确/模糊 + 分组 + 启停状态）")
  public Result<PageResult<DictRow>> pageWords(
      @RequestParam(name = "keyword", required = false) String keyword,
      @RequestParam(name = "groupId", required = false) Long groupId,
      @RequestParam(name = "status", required = false) Integer status,
      PageQuery page, @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(configService.pageWords(keyword, groupId, status, page));
  }

  @GetMapping("/word-groups")
  @Operation(summary = "词库分组列表（含每组策略：等级、处置动作、作用侧与实时词条数）")
  public Result<List<WordGroupRow>> listGroups(@AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(configService.listGroups());
  }

  @PostMapping("/words/add")
  @Operation(summary = "新增词条（同名词条若被软删过则复活并清零命中数，正则词条入库前先编译）")
  public Result<SensitiveWord> addWord(@RequestBody WordAddReq req,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (req == null || req.word() == null || req.word().isBlank()) {
      throw new BizException(ErrorCode.PARAM_INVALID, "word 必填");
    }
    return Result.ok(configService.addWord(req.word(), req.groupId(), req.matchType(),
        AdminSupport.ctxOf(current, http), AdminSupport.now()));
  }

  @PostMapping("/words/status")
  @Operation(summary = "启用 / 停用词条（软停用，不删历史命中数）")
  public Result<SensitiveWord> updateWordStatus(@RequestBody WordStatusReq req,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (req == null || req.id() == null || req.status() == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "id 与 status 必填");
    }
    return Result.ok(configService.updateWordStatus(req.id(), req.status(),
        AdminSupport.ctxOf(current, http), AdminSupport.now()));
  }

  @PostMapping("/words/delete")
  @Operation(summary = "删除词条（逻辑删除；uk_word 不看 deleted 位，所以同名词条走复活而不是撞唯一键）")
  public Result<Void> deleteWord(@RequestBody WordStatusReq req,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (req == null || req.id() == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "id 必填");
    }
    configService.deleteWord(req.id(), AdminSupport.ctxOf(current, http));
    return Result.ok();
  }

  // ------------------------------------------------------------ 热更新与试审

  @PostMapping("/dict/reload")
  @Operation(summary = "词库热更新（FR7.1：从库里重算快照 -> 引擎 reload -> 成功才落盘并 bump 版本号）")
  public Result<ReloadResult> reloadDict(@AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    AdminSupport.requireAdmin(current);
    return Result.ok(configService.rebuildDict(AdminSupport.ctxOf(current, http), AdminSupport.now()));
  }

  @PostMapping("/dict/trial")
  @Operation(summary = "试审一段文本（只读，返回归一化结果与命中明细，不落任何审计表）")
  public Result<TrialView> trial(@RequestBody TrialReq req, @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    if (req == null || req.text() == null || req.text().isBlank()) {
      throw new BizException(ErrorCode.PARAM_INVALID, "text 必填");
    }
    return Result.ok(configService.trial(req.text(), req.side()));
  }

  @GetMapping("/dict/status")
  @Operation(summary = "引擎当前状态（内存版本号 / 词数 / 库里版本号 / 快照路径 / 缓存模式）")
  public Result<Map<String, Object>> dictStatus(@AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(configService.engineStatus());
  }
}