package com.mindisle.web;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.Result;
import com.mindisle.entity.UserConsent;
import com.mindisle.privacy.DataRetentionJob;
import com.mindisle.privacy.PrivacyAccountService;
import com.mindisle.privacy.PrivacyExportService;
import com.mindisle.privacy.PrivacyViews;
import com.mindisle.ratelimit.RateLimitInterceptor;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 隐私中心的 9 条端点（任务 T4.21 · 需求 FR1.5 数据导出 / FR1.6 注销与删除 / NFR8 授权撤回 · 手册 §7.5）。
 *
 * <p><b>🔴 契约漂移（已按手册落地，记 dev-log）</b>：需求 §9.1 第 621–622 行写的是
 * {@code /privacy/consent}、{@code /users/me/export}、{@code DELETE /users/me} 三条；
 * 手册 §7.5 第 1088 行起写的是 {@code GET /api/privacy/export?format=}、{@code POST /api/privacy/deactivate}、
 * {@code DELETE /api/privacy/consent/{type}}。两者不一致时以<b>手册</b>为准，理由有三：
 * ① 手册是阶段 4 的开工依据，前端按它写；② {@code DELETE /users/me} 会被现有的
 * {@code /api/users/me} 前缀路由读成「删自己的用户行」，而注销在业务上是<b>写</b>不是删；
 * ③ 需求那三条里 {@code /privacy/consent} 与已上线的 {@code POST /api/users/me/consents} 语义重叠。
 * 差异不会静默：这一段注释 + dev-log + 冒烟脚本里的路径三处都会对上。</p>
 *
 * <p><b>为什么多一条手册没写的 {@code POST /api/privacy/restore}</b>：手册只写了「冷静期内登录即自动撤回」，
 * 那条路径确实已实现（{@code AuthService#allowCoolingOrReject}）。但「必须靠登录才能撤回」在界面上无法验收 ——
 * 用户点「撤回注销」时不应该被踢出去重登一次。它同时是状态机可测性的必需项：没有这条端点，
 * 冒烟就只能证明「进得了冷静期」，证不出「出得了冷静期」。偏差已记 dev-log。</p>
 *
 * <p><b>九条全部要登录，包括那条带口令的下载</b>：需求原文允许「凭链接下载」，本项目<b>没有</b>那么做 ——
 * 链接里只有 64 位随机口令，一旦它出现在浏览器历史、代理日志或截图里，那个包就是任何人的了；
 * 补上「JWT + 归属校验」之后，口令泄露最多让人<b>看到</b>一条 404，拿不到字节。
 * 真正的纯链接短期下载需要临时签名 URL 基建，本期如实标注为未做（见 dev-log）。
 * {@code SecurityConfig} 的匿名集合因此<b>一个字都没改</b>，{@code JwtAuthFilterAnonymousPathTest} 也不用动。</p>
 */
@RestController
@Tag(name = "12 隐私中心", description = "我的数据概览、个人信息导出、注销冷静期与到期清除（阶段 4）")
public class PrivacyController {

    private final PrivacyAccountService accountService;
    private final PrivacyExportService exportService;
    private final DataRetentionJob retentionJob;

    public PrivacyController(PrivacyAccountService accountService,
            PrivacyExportService exportService, DataRetentionJob retentionJob) {
        this.accountService = accountService;
        this.exportService = exportService;
        this.retentionJob = retentionJob;
    }

    /** 我的数据概览（FR1.5）。逐域条数 + 账号本体字段 + 冷静期状态，一次给全。 */
    @GetMapping("/api/privacy/summary")
    @Operation(summary = "我的数据概览：34 张注册表里每一域存了我多少条、账号现在处于哪个状态")
    public Result<PrivacyViews.Summary> summary(@AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        return Result.ok(accountService.summary(current.id()));
    }

    /**
     * 提交导出（FR1.6）。同步返回的是 PENDING 任务视图，不是文件 ——
     * 全量导出可能要读几十万行，把它做成一个同步接口就会在网关那边先超时。
     * 前端拿 {@code downloadPath} 轮询 {@code /latest}，成功态才出现可点的下载链接。
     */
    @GetMapping("/api/privacy/export")
    @Operation(summary = "提交个人信息导出任务（format=json|csv，同域内重复提交只排队不重复插行）")
    public Result<PrivacyViews.ExportTaskView> export(
            @RequestParam(name = "format", required = false) String format,
            @AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        return Result.ok(exportService.submit(current.id(), format));
    }

    /** 最近一次导出任务。从没导出过时返回 {@code data = null}，不是错误 —— 界面上那格显示「还没有导出记录」。 */
    @GetMapping("/api/privacy/export/latest")
    @Operation(summary = "我最近一次导出任务的状态（前端轮询用；无记录时 data 为 null）")
    public Result<PrivacyViews.ExportTaskView> latestExport(@AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        return Result.ok(exportService.latest(current.id()));
    }

    /** 导出历史（需求 FR1.5「可查看下载记录」）。上限夹在 200 条以内。 */
    @GetMapping("/api/privacy/export/history")
    @Operation(summary = "我的导出任务历史，新的在前")
    public Result<List<PrivacyViews.ExportTaskView>> exportHistory(
            @RequestParam(name = "limit", required = false) Integer limit,
            @AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        return Result.ok(exportService.history(current.id(), limit == null ? 20 : limit));
    }

    /**
     * 下载产物字节（{@code GET /api/privacy/export/file?token=}）。
     *
     * <p>返回的是 {@code ResponseEntity<byte[]>} 而不是全站统一的 {@code Result<T>}：
     * 一个 JSON 外壳包二进制不是「兼容」，是把 zip 变成 base64 让体积涨三分之一、
     * 而浏览器再也认不出它是什么文件。这是本文件唯一一处偏离统一响应包装的地方，
     * 失败仍然走 {@code BizException} —— 由 {@code GlobalExceptionHandler} 回到 JSON 的 404。</p>
     *
     * <p>{@code no-store} 与 {@code nosniff} 是给个人信息包加的两位守门人：
     * 前者禁止任何中间层把包缓存下来（CDN/共享电脑浏览器的缓存里不该躺着一份导出），
     * 后者阻止浏览器把 json 当脚本执行。文件名全 ASCII（用户 id + 时间戳构造），
     * 所以不需要 {@code filename*=UTF-8''} 那套 RFC 5987 编码 —— 这一点在 001 项目里踩过。</p>
     */
    @GetMapping("/api/privacy/export/file")
    @Operation(summary = "按口令下载导出产物（JWT + 归属双重校验；口令无效/非本人/未成功/已过期都是 404）")
    public ResponseEntity<byte[]> downloadExport(
            @RequestParam(name = "token", required = false) String token,
            @AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        PrivacyExportService.Download file = exportService.download(token, current.id());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(file.content().length)
                .body(file.content());
    }

    /** 提交注销（FR1.6）。只打冷静期标记，不删任何一行；到期清除由 {@link #runRetention} 那条路径执行。 */
    @PostMapping("/api/privacy/deactivate")
    @Operation(summary = "提交注销申请（进入 30 天冷静期，重复提交幂等、不延长到期时间）")
    public Result<PrivacyViews.DeactivateView> deactivate(@AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        return Result.ok(accountService.deactivate(current.id()));
    }

    /** 撤回注销（冷静期内）。与「登录自动撤回」共用同一条 SQL，见 {@code UserMapper#restoreActive}。 */
    @PostMapping("/api/privacy/restore")
    @Operation(summary = "撤回注销申请（仅冷静期内可用；不在期内为 10001）")
    public Result<PrivacyViews.DeactivateView> restore(@AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        return Result.ok(accountService.restore(current.id()));
    }

    /**
     * 撤回某项授权（NFR8）。
     *
     * <p>TERMS 与 PRIVACY 两项被 {@code UserService#grantOrWithdraw} 里的闸门拦住（10001），
     * 文案指向「请使用注销账号」—— 这两项是「用这个产品」的前提，
     * 把它们做成可以撤回的开关，就等于让产品里出现一个「不同意但仍在用」的状态。
     * 其余各项（AI 对话、情绪识别、公开昵称等）可自由撤回。</p>
     */
    @DeleteMapping("/api/privacy/consent/{type}")
    @Operation(summary = "撤回某项授权（追加一条 WITHDRAW 流水，不删历史行；TERMS/PRIVACY 不可撤回）")
    public Result<UserConsent> withdrawConsent(@PathVariable(name = "type") String type,
            @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
        requireLogin(current);
        return Result.ok(accountService.withdrawConsent(current.id(), type,
                RateLimitInterceptor.clientIp(http), http.getHeader("User-Agent")));
    }

    /**
     * 立即跑一趟到期清除批次（仅管理员 · 取证与演示用）。
     *
     * <p>它调的就是定时任务那一个 {@code run()}，不是另写一份「手动版」——
     * 与 {@code POST /api/emotions/weekly-report/run} 同一条纪律：验过的行为与夜里发生的行为
     * 必须是同一段代码。角色闸写在方法里而不是靠路径前缀，理由抄 {@code EmotionController} 那一条。</p>
     *
     * <p>🔴 这条端点<b>会真删数据</b>，所以它的冒烟只能对测试账号跑。参数不带 dry-run，
     * 因为「假装清除」需要再造一套只读版的归属判定，而那套代码与真清除不同源 ——
     * 一个不会随真清除一起更新的保险，本身就是假保险。到期判定由 {@code purge_at} 决定，
     * 演示时想安全地看到全貌，就把演示账号的冷静期配成 1 天（{@code mindisle.privacy.cooling-days}）。</p>
     */
    @PostMapping("/api/privacy/retention/run")
    @Operation(summary = "立即跑一批到期清除（仅管理员；与每天 03:30 的定时批次同一条代码路径）")
    public Result<DataRetentionJob.Summary> runRetention(
            @RequestParam(name = "limit", required = false) Integer limit,
            @AuthenticationPrincipal AuthUser current) {
        requireLogin(current);
        if (!current.isAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return Result.ok(retentionJob.run(java.time.LocalDateTime.now(), limit == null ? 0 : limit));
    }

    private static void requireLogin(AuthUser current) {
        if (current == null || current.id() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }
}
