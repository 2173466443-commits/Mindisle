package com.mindisle.web;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.Result;
import com.mindisle.emotion.EmotionCheckinService;
import com.mindisle.emotion.EmotionProfileService;
import com.mindisle.emotion.WeeklyReportJob;
import com.mindisle.emotion.WeeklyReportService;
import com.mindisle.emotion.WeeklyReportShareService;
import com.mindisle.emotion.dto.CheckinRequest;
import com.mindisle.emotion.dto.CheckinView;
import com.mindisle.emotion.dto.ProfileView;
import com.mindisle.emotion.dto.WeeklyReportView;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 情绪域的 6 条端点（任务 T4.9 / T4.10 / T4.20 · 需求 FR3.1、FR3.4、FR3.5）。
 *
 * <p>前 4 条是用户侧的读与写，第 5 条 {@code POST /api/emotions/weekly-report/{id}/share}
 * 是管理员侧的「立即跑一批周报」，它是本文件里唯一一条带角色闸的端点。</p>
 *
 * <p><b>路径前缀用 {@code /api/emotions}（复数）</b>：需求 §9.1 的接口清单里情绪行只写了
 * 「打卡 / 档案 / 周报」三件事、没写具体路径，而阶段 3 已上线的其它域一律是复数
 * （{@code /api/posts}、{@code /api/topics}、{@code /api/users}）。前端 U3 在阶段 3 就按复数
 * 占位调用了，这里对齐它，不改前端。差异已按 SOP 记进 docs/dev-log.md 的契约漂移清单。</p>
 *
 * <p><b>四条全部要求登录</b>（由 {@code SecurityConfig} 的 {@code anyRequest().authenticated()}
 * 兜底），而写入侧还要再过一道<b>敏感信息单独同意</b>：见
 * {@code EmotionCheckinService#checkin}。读侧刻意<b>不</b>要求同意——一个人撤回授权之后
 * 仍然要能看见自己已经留下的数据，否则「撤回」会变成「看不见自己的历史」，
 * 那是把主体权利做成了惩罚（同 {@code ConversationService} 的口径）。</p>
 */
@RestController
@Tag(name = "11 情绪与档案", description = "情绪打卡、情绪档案四图与情绪周报（阶段 4）")
public class EmotionController {

    private final EmotionCheckinService checkinService;
    private final EmotionProfileService profileService;
    private final WeeklyReportService weeklyReportService;
    private final WeeklyReportJob weeklyReportJob;
    private final WeeklyReportShareService shareService;

    public EmotionController(EmotionCheckinService checkinService,
            EmotionProfileService profileService, WeeklyReportService weeklyReportService,
            WeeklyReportJob weeklyReportJob, WeeklyReportShareService shareService) {
        this.checkinService = checkinService;
        this.profileService = profileService;
        this.weeklyReportService = weeklyReportService;
        this.weeklyReportJob = weeklyReportJob;
        this.shareService = shareService;
    }

    /** 每日打卡（FR3.1）。同一天再提交是「追加新版本」，不覆盖历史。 */
    @PostMapping("/api/emotions/checkin")
    @Operation(summary = "提交一次心情打卡（可带 recordDate 补卡，近 30 天内）")
    public Result<CheckinView> checkin(@RequestBody(required = false) CheckinRequest request,
            @AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        return Result.ok(checkinService.checkin(current.id(), request));
    }

    /** 近 N 天的打卡流水（U3 的列表区）。 */
    @GetMapping("/api/emotions/checkins")
    @Operation(summary = "我的打卡记录（只含主动打卡，不含被动识别）")
    public Result<List<CheckinView>> checkins(@RequestParam(name = "days", required = false) Integer days,
            @AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        return Result.ok(checkinService.recent(current.id(), days == null ? 7 : days));
    }

    /** 档案页四图（FR3.4）。一次响应给全，四张图共享同一次聚合。 */
    @GetMapping("/api/emotions/profile")
    @Operation(summary = "情绪档案：趋势折线 + 分布饼 + 触发词云 + 日历热力（窗口 7/30/90 天）")
    public Result<ProfileView> profile(@RequestParam(name = "range", required = false) Integer range,
            @AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        return Result.ok(profileService.profile(current.id(), range));
    }

    /** 情绪周报（FR3.5）。当周首次读取即生成；{@code refresh=true} 强制重算。 */
    @GetMapping("/api/emotions/weekly-report")
    @Operation(summary = "我的情绪周报（本地统计 + LLM 文案，模型不可用时回落模板）")
    public Result<WeeklyReportView> weeklyReport(
            @RequestParam(name = "week", required = false) String week,
            @RequestParam(name = "refresh", required = false) Boolean refresh,
            @AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        return Result.ok(weeklyReportService.report(current.id(), week, Boolean.TRUE.equals(refresh)));
    }


    /**
     * 把一份周报去标识分享成一篇公开帖（任务 T4.20 ③ · 手册 §7.5 第 3 条 · 需求 FR3.5、BR13）。
     *
     * <p><b>路径里带 {@code {id}}</b>：分享的对象是「某一周的那一份周报」，不是一个可以被
     * 查询参数糊过去的抽象动作。幂等也靠这个 id —— 第二次点分享直接返回原来那篇帖子。</p>
     *
     * <p><b>要求登录，但不额外要求敏感信息授权</b>：写侧的同意闸在<b>打卡</b>那一刻已经过了
     * （{@code EmotionCheckinService#checkin}），这里做的是「把已经属于我的数据公开一次」，
     * 属于公开动作而不是采集动作。真正拦得住的是 {@code WeeklyReportShareService} 里的作者校验：
     * 传别人的周报 id 一律 403，不靠前端藏按钮。</p>
     */
    @PostMapping("/api/emotions/weekly-report/{id}/share")
    @Operation(summary = "把我的情绪周报去标识分享成一篇帖子（匿名马甲；重复点返回原帖，不再发第二条）")
    public Result<WeeklyReportShareService.ShareView> shareWeeklyReport(@PathVariable("id") Long id,
            @AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        if (id == null || id <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "要分享的周报 id 不合法");
        }
        return Result.ok(shareService.share(current.id(), id, LocalDateTime.now()));
    }
    /**
     * 手动跑一趟周报批次（任务 T4.20 · 管理端取证 / 演示用）。
     *
     * <p><b>它调的就是定时任务那一个 {@code run()}</b> —— 不是另写一份「手动版」。
     * 如果两条路径各有一份实现，那么这条验过的行为与周日夜里真正发生的行为就不是同一段代码，
     * 而这条端点存在的意义恰恰是「我能在答辩现场证明批处理是对的」。</p>
     *
     * <p><b>为什么是 ADMIN 闸而不是路径前缀闸</b>：{@code /api/admin/**} 的拦截在
     * {@code SecurityConfig} 里按路径写（第 100 行 {@code hasAnyRole("ADMIN","SUPER")}）；
     * 把这条挂到 {@code /api/admin/...} 下也能拦住，但
     * {@code JwtAuthFilterAnonymousPathTest} 钉的是「匿名路径集合」，加一条管理路径就要同步改那条测试，
     * 而它本来只管「哪些接口不必登录」。角色闸写在方法里，能被控制器单测直接钉住，
     * 也避免「前缀改个名字就绕过权限」这种只存在于路径里的规则。</p>
     *
     * <p><b>会真花钱</b>：一次调用最多触发 {@code min(limit, 200)} 次 LLM 调用，
     * 所以它不可能是匿名接口，也不该被自动化冒烟反复调 —— 冒烟里只以 {@code limit=0}（读配置）
     * 或 {@code limit=1} 各跑一次。</p>
     *
     * @param limit 本批人数上限；不传或 {@code <= 0} 用配置值，任何情况下不超 200
     * @return 这一趟的账（候选 / 生成 / 失败 / 是否被截断 / 耗时）
     */
    @PostMapping("/api/emotions/weekly-report/run")
    @Operation(summary = "立即跑一批情绪周报（仅管理员；与周日 21:00 的定时批次同一条代码路径）")
    public Result<WeeklyReportJob.Summary> runWeeklyReport(
            @RequestParam(name = "limit", required = false) Integer limit,
            @AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        if (!current.isAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return Result.ok(weeklyReportJob.run(LocalDate.now(), limit == null ? 0 : limit));
    }

    private static void requireLogin(AuthUser current) {
        if (current == null || current.id() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }
}
