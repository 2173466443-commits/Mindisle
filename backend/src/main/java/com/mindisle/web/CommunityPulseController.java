package com.mindisle.web;

import com.mindisle.common.Result;
import com.mindisle.pulse.CommunityPulseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 落地页社区脉搏接口（任务 T3.13 结转项 U1）。
 *
 * <p><b>路径刻意挂在 {@code /api/system/**} 而不是新开一条 {@code /api/pulse}</b>：
 * SecurityConfig 的 {@code PUBLIC_MATCHERS} 与 JwtAuthFilter 的 {@code ANONYMOUS_PREFIXES}
 * 已经各有一份 {@code /api/system/} 前缀，两份清单由 {@code JwtAuthFilterAnonymousPathTest} 逐条钉住。
 * 挂在这棵子树下，匿名可读这件事<b>一行代码和一条测试都不用改</b>；
 * 新开一条前缀则要动那两份清单，而「为落地页放宽公开面」这件事本身就该被慎对待。</p>
 *
 * <p>本接口只返回聚合数与已过审的公开卡片，不含任何个人内容；
 * 响应体里 {@code degraded}/{@code cached} 两个标记如实说明这批数从哪儿来、新不新，
 * 口径与 {@code /api/system/hotline} 的 {@code source} 字段一致。</p>
 */
@RestController
@RequestMapping("/api/system/community-pulse")
@Tag(name = "2 系统", description = "运行状态、版本号、危机求助卡片、公开参数与落地页社区脉搏")
public class CommunityPulseController {

  private final CommunityPulseService pulseService;

  public CommunityPulseController(CommunityPulseService pulseService) {
    this.pulseService = pulseService;
  }

  @GetMapping
  @Operation(summary = "落地页社区脉搏（免登录：近 N 日聚合氛围数 + 精选公开笔记）")
  public Result<Map<String, Object>> pulse(
      @Parameter(description = "统计窗口天数，缺省 7，上限 30")
      @RequestParam(name = "days", required = false) Integer days) {
    return Result.ok(pulseService.landing(days, LocalDateTime.now()));
  }
}