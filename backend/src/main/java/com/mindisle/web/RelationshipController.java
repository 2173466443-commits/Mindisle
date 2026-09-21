package com.mindisle.web;

import java.time.LocalDateTime;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.Result;
import com.mindisle.security.AuthUser;
import com.mindisle.user.RelationshipService;
import com.mindisle.user.dto.FollowRequest;
import com.mindisle.user.dto.FollowView;
import com.mindisle.user.dto.UserHomepage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 关注与主页资料接口（任务 3.6 对外口 · 手册 §6.2 U11 · 需求 FR1.5、FR4.6）。
 *
 * <p><b>为什么单独一个类</b>：{@code UserController} 只有 UserService（「我的」这条路上路径里没有 id），
 * {@code UserPostController} 只有 PostQueryService，本类要的是 RelationshipService。
 * 三个服务没有交集，硬塞进一个类只会让构造函数越来越长、每个方法的依赖都要从头翻。
 * 分工口径与 T3.13 第二批把帖子列表拆出去时写下的理由一致。</p>
 *
 * <p><b>发起人 id 只来自令牌</b>：路径里那个 id 是<b>被</b>关注人。两个 id 都从客户端传来的接口
 * 在需求 BR4/A9 面前是不成立的——任何人都能替别人点赞关注。</p>
 */
@RestController
@RequestMapping("/api/users")
// 描述必须与 UserController / UserPostController 逐字一致：springdoc 的顶层 tags 按 name + description 去重，
// 两处只改文案就会多出一条重复的「3 用户」，把手册 §5.3 的「7 个分组」口径弄歪（本轮实测踩过）。
@Tag(name = "3 用户", description = "账号摘要、扩展资料、隐私授权与帖子列表（我的 / 他人主页）")
public class RelationshipController {

  private final RelationshipService relationshipService;

  public RelationshipController(RelationshipService relationshipService) {
    this.relationshipService = relationshipService;
  }

  /**
   * 关注 / 取关（需求 FR4.6）。
   *
   * <p>动作白名单与帖子互动同一形状：一条 POST、body 里一个 action。
   * 目标不存在与已注销同返 404/20001，不给外人一条「这个号还在不在」的枚举通道。</p>
   */
  @PostMapping("/{id:\\d+}/follow")
  @Operation(summary = "关注或取关（follow / unfollow，两个方向都幂等）")
  public Result<FollowView> follow(@PathVariable("id") long id,
      @RequestBody(required = false) FollowRequest request,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(relationshipService.follow(current.id(), id,
        request == null ? null : request.action()));
  }

  /**
   * 他人主页资料卡（需求 FR1.5）。与 {@code GET /api/users/{id}/posts} 分两个接口，
   * 理由写在 {@code RelationshipService#homepage}：列表要翻页，资料卡不该跟着每页重算一遍。
   */
  @GetMapping("/{id:\\d+}/profile")
  @Operation(summary = "屿友主页资料卡（公开口径：不含年级/院系/性别/risk_flag，获赞数只算公开非匿名帖）")
  public Result<UserHomepage> homepage(@PathVariable("id") long id,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(relationshipService.homepage(current.id(), id));
  }
}
