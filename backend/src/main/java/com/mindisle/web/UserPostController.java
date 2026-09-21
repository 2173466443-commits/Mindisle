package com.mindisle.web;

import java.time.LocalDateTime;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.common.Result;
import com.mindisle.post.PostQueryService;
import com.mindisle.post.dto.PostListItem;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 用户维度的帖子列表接口（任务 3.13 第二批 · 手册 §6.2 U11/U12 · 需求 FR1.4、FR4.1、FR4.3）。
 *
 * <p><b>为什么不塞进 UserController</b>：那边只有 UserService，这里只有 PostQueryService，
 * 两个类的依赖面完全不交叠；合在一起就等于让「改昵称」和「翻帖子」互相牵连，
 * 还要逼 UserController 多注一个它用不到的 Bean。路径前缀仍然同为 {@code /api/users}，
 * OpenAPI 分组不受影响 —— 分组是按 {@code pathsToMatch("/api/users/**")} 定义的，不是按类，
 * 所以接口清单的统计口径（手册 §5.3「02-user」）不会因为拆类而多出一组。
 * 但 {@code @Tag} 的 description 必须与 UserController 逐字一致：顶层 tags 是按 name 加 description 去重的，
 * 只在这里换个说法，/v3/api-docs 就会多出一条重名的「3 用户」（实测 7 → 8 条），分组数口径当场失真。
 *
 * <p><b>两个接口的分工（差异只在「查谁」与「过滤多严」）</b>：
 * {@code /me/posts} 是<b>作者视角</b>，含私密帖、待审帖与机审未过的帖，用来补上
 * 「发完找不回来」这个洞 —— 广场是公共流，勾了仅自己可见的已发布帖按设计不进广场，那是分工不是漏洞。
 * {@code /{id}/posts} 是<b>外人视角的公开主页</b>，只放行 public 且 PUBLISHED，
 * 且匿名帖与挂了马甲 id 的帖<b>恒不出现</b>：否则这个接口就成了一条把匿名帖逐条对回真实账号的解匿通道，
 * 正是需求 FR1.4 要挡死的事。本人访问自己的主页与外人看到的内容完全一致。
 *
 * <p><b>为什么路径要写数字约束</b>：一是让 {@code /me/posts} 与 {@code /{id}/posts} 的字面量与模板歧义
 * 靠声明消失，而不是依赖 PathPattern 比较器的优先级；二是非数字 id 由容器直接判不匹配、落到兜底 404，
 * 不会先进方法再把 long 解析失败抛成 500 —— GlobalExceptionHandler 里没有
 * MethodArgumentTypeMismatchException 的处理器，那种 500 会把「你参数写错了」伪装成服务端故障。
 *
 * <p>本前缀不在 SecurityConfig 的 permitAll 白名单里，未登录必 401/10002；
 * current == null 的兜底照抄 PostController：白名单被人改宽时，
 * 「我的帖子」也不能变成一个可以匿名打别人主页的口子。
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "3 用户", description = "账号摘要、扩展资料、隐私授权与帖子列表（我的 / 他人主页）")
public class UserPostController {

  private final PostQueryService postQueryService;

  public UserPostController(PostQueryService postQueryService) {
    this.postQueryService = postQueryService;
  }

  @GetMapping("/me/posts")
  @Operation(summary = "我的帖子列表（作者视角：含私密、待审与未通过，可按状态过滤）")
  public Result<PageResult<PostListItem>> myPosts(
      @Parameter(description = "状态过滤，取 post.status 的枚举值；不传表示全部")
      @RequestParam(name = "status", required = false) String status,
      PageQuery page,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(postQueryService.mine(current.id(), status, page, LocalDateTime.now()));
  }

  @GetMapping("/{id:\\d+}/posts")
  @Operation(summary = "他人公开主页帖子列表（只含 public 且已发布，匿名帖恒不出现）")
  public Result<PageResult<PostListItem>> userPosts(@PathVariable("id") long id,
      PageQuery page,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(postQueryService.profile(current.id(), id, page, LocalDateTime.now()));
  }
}
