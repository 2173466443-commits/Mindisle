package com.mindisle.web;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.common.BizException;
import com.mindisle.common.Result;
import com.mindisle.entity.Topic;
import com.mindisle.mapper.TopicMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 内容与推荐接口（手册 §5.7 T2.11 · 需求 FR1.6、FR1.7、FR2 推荐）。
 *
 * <p>/api/topics 落在 permitAll 白名单里（游客也能逛话题墙），其余需要登录。
 * 内容域在阶段 2 只开放话题墙这一个真接口：发帖牵涉内容安全链（先审后发 → 危机分流）
 * 与图片上传，未落地前一律返回 90001，而不是造假数据骗过前端联调。
 *
 * <p>2026-09-20 任务 3.3 落地后，<b>POST /api/posts 已迁至 {@link PostController}</b>：
 * 这里的桩必须删掉，否则两条 handler 映射到同一个 POST 路径，
 * Spring MVC 在启动期就报 ambiguous mapping，整个服务起不来（不是运行期才 500）。
 *
 * <p>2026-09-20 任务 3.5 落地后，<b>GET /api/posts 与 GET /api/posts/{id} 两个桩同样删除</b>，
 * 读接口现在在 {@link PostController}。本类只剩游客可访问的话题墙，以及仍属未实现的
 * 推荐流（阶段 4 情绪感知加权，届时再迁走）。
 */
@RestController
@Tag(name = "4 内容", description = "话题墙、推荐流与帖子发布（阶段 3 起逐步开放）")
public class FeedController {

  private final TopicMapper topicMapper;

  public FeedController(TopicMapper topicMapper) {
    this.topicMapper = topicMapper;
  }

  @GetMapping("/api/topics")
  @Operation(summary = "官方话题墙（已过审，按热度倒序）")
  public Result<List<TopicBrief>> topics(
      @Parameter(description = "返回条数，取值 1-50")
      @RequestParam(name = "limit", defaultValue = "10") int limit) {
    int safe = Math.max(1, Math.min(limit, 50));
    return Result.ok(topicMapper.listOfficialApproved(safe).stream().map(TopicBrief::of).toList());
  }

  @GetMapping("/api/feed/recommend")
  @Operation(summary = "情绪感知加权推荐流（阶段 3 召回 + 阶段 4 加权）")
  public Result<Void> recommend() {
    throw BizException.notImplemented("阶段3 推荐召回与阶段4 情绪感知加权");
  }

  /**
   * 话题出参。
   *
   * <p>不透出 cover、deleted、auditStatus：封面地址涉及对象存储域名，逻辑删除位与
   * 审核状态属于运营信息，都不该出现在公开接口的返回体里。
   */
  public record TopicBrief(
      Long id, String name, String desc, Integer postCnt,
      Integer followCnt, BigDecimal hotScore, Integer isOfficial) {

    static TopicBrief of(Topic topic) {
      return new TopicBrief(topic.getId(), topic.getName(), topic.getDescTxt(),
          topic.getPostCnt(), topic.getFollowCnt(), topic.getHotScore(), topic.getIsOfficial());
    }
  }
}
