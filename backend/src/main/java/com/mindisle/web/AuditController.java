package com.mindisle.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.audit.SensitiveWordEngine.CheckResult;
import com.mindisle.audit.dto.PrecheckRequest;
import com.mindisle.audit.dto.PrecheckView;
import com.mindisle.cache.CacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.Result;
import com.mindisle.config.MindisleProperties;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 内容安全预检接口（任务 3.2 对外口 · 手册 §6.2 U5 · 需求 FR7.1）。
 *
 * <p>发布页输入防抖 300ms 调它，属于「提醒」而不是「判定」：
 * 真正的处置发生在发帖（任务 3.3 状态机），因为提醒可以被绕过（前端不调），
 * 判定必须在服务端做。两者共用同一个引擎实例，规则不会分叉。</p>
 *
 * <p>本路径不在 SecurityConfig 的 permitAll 白名单里，未登录必 401/10002。
 * 这是刻意的：预检会暴露词库的判定结果，游客拿它当探测器和注册用户拿它当提醒，
 * 成本完全不同。</p>
 */
@RestController
@RequestMapping("/api/audit")
@Tag(name = "6 审核", description = "内容安全预检（发帖、评论、私信、AI 输出共用同一引擎）")
public class AuditController {

  /** scene 里表示「检测的是模型输出」的取值，其余按用户侧处理（FR7.1 双侧作用）。 */
  private static final String SCENE_AI = "ai";
  private static final String SIDE_AI = "ai";
  private static final String SIDE_USER = "user";

  private final SensitiveWordEngine engine;
  private final CacheService cacheService;
  private final MindisleProperties properties;

  public AuditController(SensitiveWordEngine engine, CacheService cacheService, MindisleProperties properties) {
    this.engine = engine;
    this.cacheService = cacheService;
    this.properties = properties;
  }

  @PostMapping("/precheck")
  @Operation(summary = "敏感词与隐私信息实时预检（需登录，返回命中位置与处置建议，不含词面）")
  public Result<PrecheckView> precheck(@Valid @RequestBody PrecheckRequest request,
                                       @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      // 兜底：白名单被误改时也不能让预检变成匿名探测器（手册 §5.6 的 permitAll 清单是全站唯一入口）。
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    MindisleProperties.Audit audit = properties.getAudit();
    // 词库热更新检查挂在这里：本接口是全站调用最频繁的审核入口，不需要另起定时任务。
    engine.refreshIfStale(cacheService, audit.getDictVersionKey());
    String side = SCENE_AI.equalsIgnoreCase(request.scene()) ? SIDE_AI : SIDE_USER;
    CheckResult result = engine.check(request.text(), side);
    // side=user：用户自己写的草稿，命中 risk 组才需要顺手给求助入口。
    return Result.ok(PrecheckView.of(result, properties.getCrisis().getHotline(), true));
  }
}
