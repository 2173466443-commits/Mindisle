package com.mindisle.web;

import java.time.LocalDateTime;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.Result;
import com.mindisle.post.PostService;
import com.mindisle.post.dto.CreatePostRequest;
import com.mindisle.post.dto.PostView;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 发帖接口（任务 3.3 对外口 · 手册 §6.2 U5 · 需求 FR4.1、FR4.2、FR7.3）。
 *
 * <p>本路径不在 SecurityConfig 的 permitAll 白名单里，未登录必 401/10002；
 * current == null 的兜底判断照抄 AuditController：白名单被人改宽时，
 * 发帖也不能变成匿名可打的接口（那等于把内容安全链的第一环交给攻击者）。</p>
 *
 * <p><b>状态码口径</b>：被机审拦下（REJECTED）与转人工（HUMAN_REVIEW）都回 <b>200</b>，
 * 处置结果在 {@code data.status} 与 {@code data.tip} 里。理由见 {@link PostView} 的类注释：
 * 「内容没通过审核」是一次业务上完全成功的请求，用户需要的是原因和下一步，
 * 而不是一个和「你没登录」同类的 4xx。真正的入参错误才走 400/10001。</p>
 */
@RestController
@RequestMapping("/api/posts")
@Tag(name = "4 内容", description = "话题墙、推荐流与帖子发布（阶段 3 起逐步开放）")
public class PostController {

  private final PostService postService;

  public PostController(PostService postService) {
    this.postService = postService;
  }

  @PostMapping
  @Operation(summary = "发帖（普通/树洞/求助，服务端先审后发并做危机分流）")
  public Result<PostView> publish(@Valid @RequestBody CreatePostRequest request,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(postService.publish(current.id(), request, LocalDateTime.now()));
  }
}
