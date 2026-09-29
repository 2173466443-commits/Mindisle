package com.mindisle.web;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.admin.AppealService;
import com.mindisle.admin.AppealService.AppealView;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.Result;
import com.mindisle.entity.Post;
import com.mindisle.entity.PostAppeal;
import com.mindisle.mapper.PostMapper;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 作者侧申诉入口（任务 T6.7 · 需求 FR7.6）。
 *
 * <p>挂在 {@code /api/posts} 下而不是 {@code /api/admin} 下：这是普通用户唯一能触达治理流程的口子。
 * 它同时是「内容治理对作者可解释」这条原则的唯一对外证明——
 * 用户被下架后能看见原因、能说话、能看到说话的结果，而不是只收到一条通知。</p>
 *
 * <p>查询申诉记录时<b>非作者返回空列表而不是 403</b>：403 会告诉探测者「这帖有申诉历史」，
 * 而空列表什么都没说。归属判定在这里做（不是靠前端不显示），因为申诉记录里带着平台内部的
 * 处置口径与管理员备注，那是治理信息，不是社区公开内容。</p>
 */
@RestController
@RequestMapping("/api/posts")
@Tag(name = "4 社区-申诉", description = "作者提交申诉与查看申诉进度（一次机会，结果站内通知）")
public class PostAppealController {

  private final AppealService appealService;
  private final PostMapper postMapper;

  public PostAppealController(AppealService appealService, PostMapper postMapper) {
    this.appealService = appealService;
    this.postMapper = postMapper;
  }

  /** 申诉正文入参。 */
  public record AppealReq(String reason) {
  }

  @PostMapping("/{id:\\d+}/appeal")
  @Operation(summary = "对被驳回/被下架的帖子提交申诉（仅作者，一次性，reason 必填）")
  public Result<AppealView> submit(@PathVariable("id") long id, @RequestBody AppealReq req,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    if (req == null || req.reason() == null || req.reason().isBlank()) {
      throw new BizException(ErrorCode.PARAM_INVALID, "申诉必须写清理由");
    }
    return Result.ok(appealService.submit(id, current.id(), req.reason(), AdminSupport.now()));
  }

  @GetMapping("/{id:\\d+}/appeals")
  @Operation(summary = "作者查看自己某帖的申诉记录与裁定结果（他人访问返回空列表，不泄露治理历史）")
  public Result<List<PostAppeal>> list(@PathVariable("id") long id,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    Post post = postMapper.selectById(id);
    if (post == null || !current.id().equals(post.getUserId())) {
      return Result.ok(List.of());
    }
    return Result.ok(appealService.historyOfPost(id));
  }
}