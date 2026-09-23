package com.mindisle.web;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
import com.mindisle.search.SearchService;
import com.mindisle.search.dto.TopicHit;
import com.mindisle.search.dto.UserHit;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 站内搜索（任务 3.9 · 手册 §6.1 行 3.9 · 需求 FR4.8「关键词搜帖子 / 话题 / 人」）。
 *
 * <p><b>与需求文档的一条口径偏离，先在这里写白</b>：需求 §9.1 给的是单条
 * {@code GET /search?q=&type=post|topic|user}，这里拆成三条路径
 * （{@code /api/search/posts}、{@code /topics}、{@code /users}）。理由是三种结果的出参形状不同：
 * 搜帖是「游标分页的帖子流」（{@link PostListItem}，带互动态、马甲名、求助卡片、审核提示），
 * 搜话题与搜人是「定长数组」。硬塞进一条路径只有两种做法——把 {@code data} 声明成 Object
 * （Swagger 上是一个空 schema，前端拿不到任何类型），或者拼一个「三种都装」的大对象，
 * 让每次请求都顺带返回两个空数组。前者让接口文档失去意义，后者让调用方为没请求的结果付带宽。
 * 同类取舍见任务 3.11「举报不并进 {@code /api/posts/{id}/actions}」；手册 §19 的版本记录同步了这条偏离。</p>
 *
 * <p><b>三条路径都要求登录</b>：它们都不在 {@code SecurityConfig} 的 permitAll 白名单里，
 * {@code anyRequest().authenticated()} 先把未登录挡成 401/10002。对照 {@code /api/topics} 是游客可访问的
 * 话题墙——那是「展示运营选好的内容」，这是「按任意关键词命中全站内容」，两者口径不同。
 * {@code current == null} 的兜底判断照抄 {@link PostController}：万一白名单被改宽，
 * 搜索也不能变成一条不需要身份就能扫全站正文的通道（那既是内容安全问题，也是拖库的捷径）。</p>
 *
 * <p><b>没做的那半条</b>：搜索历史与热搜（需求 FR4.9 / U8）要落 {@code user_action} 埋点，
 * 属任务 3.10；相关度排序的局限写在 {@link PostQueryService#search} 的注释里。
 * 本类只保证「给关键词，回可见的结果」。</p>
 */
@RestController
@RequestMapping("/api/search")
@Tag(name = "9 搜索", description = "关键词搜帖子 / 话题 / 人（任务 3.9 · 与广场共用可见性判据）")
public class SearchController {

  private final PostQueryService postQueryService;
  private final SearchService searchService;

  /**
   * 两个 Service 的分工是刻意的：<b>帖子必须走 {@link PostQueryService}</b>，因为那条判据
   * 和广场是同一份；话题与人才走 {@link SearchService}。
   * 把三条都塞进 SearchService 就得复制一份帖子的 WHERE，那是 §14 第 27 条的老事故。
   */
  public SearchController(PostQueryService postQueryService, SearchService searchService) {
    this.postQueryService = postQueryService;
    this.searchService = searchService;
  }

  @GetMapping("/posts")
  @Operation(summary = "关键词搜帖子（命中标题 / 正文 / 话题名；默认可见性与广场同源）")
  public Result<PageResult<PostListItem>> posts(
      @Parameter(description = "关键词，去首尾空白后不能为空")
      @RequestParam(name = "q", required = false) String q,
      @Parameter(description = "帖子形式：normal / hole / help，不传表示全部")
      @RequestParam(name = "type", required = false) String type,
      PageQuery page,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(postQueryService.search(current.id(), q, type, page, LocalDateTime.now()));
  }

  @GetMapping("/topics")
  @Operation(summary = "关键词搜话题（只给已过审的，按热度倒序）")
  public Result<List<TopicHit>> topics(
      @Parameter(description = "关键词，去首尾空白后不能为空")
      @RequestParam(name = "q", required = false) String q,
      @Parameter(description = "返回条数，超过 mindisle.search.max-profiles 会被夹住")
      @RequestParam(name = "limit", required = false) Integer limit,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(searchService.topics(q, limit));
  }

  @GetMapping("/users")
  @Operation(summary = "关键词搜人（昵称或登录名，只给正常状态的账号，出参只有 id / 昵称 / 头像）")
  public Result<List<UserHit>> users(
      @Parameter(description = "关键词，去首尾空白后不能为空")
      @RequestParam(name = "q", required = false) String q,
      @Parameter(description = "返回条数，超过 mindisle.search.max-profiles 会被夹住")
      @RequestParam(name = "limit", required = false) Integer limit,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(searchService.users(q, limit));
  }
}