package com.mindisle.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.audit.SensitiveWordEngine.CheckResult;
import com.mindisle.audit.SensitiveWordEngine.Hit;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.post.PostService.MachineDecision;

/**
 * 发帖状态机里「不需要数据库就能判对错」的那半（任务 3.3 · 手册 §6.1 行 3.3）。
 *
 * <p>本类只测纯逻辑：分支优先级、遮罩、入参归一。落库与事务那条链（DRAFT → 机审 → 终态 → 建单）
 * 靠 {@code docs/smoke.mjs} 打真实 HTTP + 回查真实 MySQL 验收，理由写进 dev-log：
 * 那半段的失败模式是「接线错」，桩化 13 个依赖去 mock 一遍只能证明「我调用了自己期望的方法」，
 * 证明不了状态真的落成了 PUBLISHED、工单真的带着 SLA。两件事各用能证伪它的工具。</p>
 */
class PostServiceTest {

    /** 与 SensitiveWordEngineTest 同一套内置词库口径，只留本类要用的四组。 */
    private static final String FIXTURE = String.join("\n",
            "#version=test-post-1",
            row("政治违法", "black", "BLOCK", "both", "contains", "枪支弹药"),
            row("广告导流", "black", "BLOCK", "both", "contains", "加微信领"),
            row("辱骂攻击", "grey", "REVIEW", "user", "contains", "傻逼"),
            row("自伤自杀", "risk", "TAG", "both", "contains", "伤害自己"),
            row("自伤自杀", "risk", "TAG", "both", "contains", "写遗书"),
            row("隐私泄露", "grey", "REVIEW", "both", "regex", "1[3-9][0-9]{9}"));

    private SensitiveWordEngine engine;

    @BeforeEach
    void setUp() {
        engine = new SensitiveWordEngine(new MindisleProperties(), new DefaultResourceLoader());
        engine.reload(FIXTURE);
    }

    private static String row(String group, String level, String action, String scope, String matchType, String word) {
        return String.join("\t", group, level, action, scope, matchType, word);
    }

    private static Hit hit(String word, String category, String level, String action, int start, int end) {
        return new Hit(word, category, level, action, "user", start, end);
    }

    private static CheckResult result(String category, String level, String action, List<Hit> hits) {
        return new CheckResult(!hits.isEmpty(), category, level, action, hits.size(), hits,
                hits.stream().map(h -> new int[] { h.start(), h.end() }).toList(), "test-post-1");
    }

    // ------------------------------------------------------------------ 分支优先级

    @Test
    @DisplayName("只有黑词：REJECTED，不建单")
    void blockOnlyIsRejectedWithoutTicket() {
        CheckResult blocked = result("政治违法", "black", "BLOCK",
                List.of(hit("枪支弹药", "政治违法", "black", "BLOCK", 0, 4)));

        MachineDecision decision = PostService.decide(blocked, CrisisGrader.L0, false);

        assertThat(decision.status()).isEqualTo(PostService.STATUS_REJECTED);
        assertThat(decision.care()).isFalse();
        assertThat(decision.tip()).isNotBlank().doesNotContain("枪支弹药");
    }

    @Test
    @DisplayName("黑词与危机词同时命中：内容照样拦下，但工单与求助入口一个都不少（拦内容不拦人）")
    void blockWithCrisisStillRaisesTicket() {
        CheckResult mixed = result("政治违法", "black", "BLOCK", List.of(
                hit("枪支弹药", "政治违法", "black", "BLOCK", 0, 4),
                hit("伤害自己", "自伤自杀", "risk", "TAG", 10, 14)));
        String level = CrisisGrader.levelOf(mixed);

        MachineDecision decision = PostService.decide(mixed, level, CrisisGrader.needsTicket(level));

        assertThat(decision.status()).isEqualTo(PostService.STATUS_REJECTED);
        assertThat(decision.care()).isTrue();
        assertThat(decision.riskLevel()).isEqualTo(CrisisGrader.L2);
        assertThat(decision.reason()).contains("危机");
    }

    @Test
    @DisplayName("危机命中优先于灰词转人审：先公开再救助，不让人工队列把求助卡片挡住")
    void crisisBeatsHumanReview() {
        CheckResult mixed = result("隐私泄露", "grey", "REVIEW", List.of(
                hit("13800138000", "隐私泄露", "grey", "REVIEW", 0, 11),
                hit("写遗书", "自伤自杀", "risk", "TAG", 20, 23)));
        String level = CrisisGrader.levelOf(mixed);

        MachineDecision decision = PostService.decide(mixed, level, CrisisGrader.needsTicket(level));

        assertThat(level).isEqualTo(CrisisGrader.L3);
        assertThat(decision.status()).isEqualTo(PostService.STATUS_PUBLISHED);
        assertThat(decision.riskLevel()).isEqualTo(CrisisGrader.L3);
        assertThat(decision.care()).isTrue();
    }

    @Test
    @DisplayName("只有 grey 复核词：转人工，risk_level 保持 L0（不给人贴标签）")
    void greyOnlyGoesToHumanReview() {
        CheckResult grey = result("辱骂攻击", "grey", "REVIEW",
                List.of(hit("傻逼", "辱骂攻击", "grey", "REVIEW", 0, 2)));

        MachineDecision decision = PostService.decide(grey, CrisisGrader.L0, false);

        assertThat(decision.status()).isEqualTo(PostService.STATUS_HUMAN_REVIEW);
        assertThat(decision.riskLevel()).isEqualTo(CrisisGrader.L0);
        assertThat(decision.care()).isFalse();
        assertThat(decision.tip()).contains("人工审核");
    }

    @Test
    @DisplayName("全清白：直发、L0、不建单、不给提示")
    void cleanTextPublishesSilently() {
        CheckResult none = result(null, null, null, List.of());

        MachineDecision decision = PostService.decide(none, CrisisGrader.L0, false);

        assertThat(decision.status()).isEqualTo(PostService.STATUS_PUBLISHED);
        assertThat(decision.riskLevel()).isEqualTo(CrisisGrader.L0);
        assertThat(decision.tip()).isNull();
    }

    @Test
    @DisplayName("hasAction 自己扫命中集合：主因是 grey 时也要能看出其中藏着 BLOCK")
    void hasActionScansHitsNotThePrimaryReason() {
        CheckResult mixed = result("隐私泄露", "grey", "REVIEW", List.of(
                hit("13800138000", "隐私泄露", "grey", "REVIEW", 0, 11),
                hit("加微信领", "广告导流", "black", "BLOCK", 30, 34)));

        assertThat(mixed.action()).isEqualTo("REVIEW");
        assertThat(PostService.hasAction(mixed, "BLOCK")).isTrue();
        assertThat(PostService.hasAction(mixed, "REVIEW")).isTrue();
        assertThat(PostService.hasAction(mixed, "TAG")).isFalse();
        assertThat(PostService.hasAction(null, "BLOCK")).isFalse();
    }

    // ------------------------------------------------------------------ 匿名遮罩

    @Test
    @DisplayName("匿名帖遮罩：手机号换成 ＊ 且长度不变，危机原句一个字都不遮（工单要靠它复核）")
    void maskHidesContactsButKeepsTheCry() {
        String text = "有没有人陪我聊聊，我这两天总想伤害自己，可以找我 13800138000 说";
        CheckResult result = engine.check(text, "user");

        String masked = PostService.maskNonRisk(text, result);

        assertThat(masked).hasSize(text.length());
        assertThat(masked).doesNotContain("13800138000");
        assertThat(masked).contains("伤害自己");
        assertThat(masked.chars().filter(c -> c == 0xFF0A).count()).isEqualTo(11L);
    }

    @Test
    @DisplayName("标题与正文拼接检测后能按原切点准确分回：遮罩不改变长度是这条链路的前提")
    void maskedTextSplitsBackAtTheSameOffset() {
        String title = "凌晨三点又醒了";
        String content = "一直想伤害自己，留个电话 13800138000 想找人说说话";
        String checkText = title + "\n" + content;
        CheckResult result = engine.check(checkText, "user");
        int bodyFrom = title.length() + 1;

        String masked = PostService.maskNonRisk(checkText, result);

        assertThat(masked.substring(0, title.length())).isEqualTo(title);
        String maskedContent = masked.substring(bodyFrom);
        assertThat(maskedContent).hasSize(content.length())
                .contains("伤害自己").doesNotContain("13800138000");
    }

    @Test
    @DisplayName("命中区间正好劈开 emoji 的代理对时整对一起遮：孤立代理项会让响应体序列化直接抛错")
    void maskNeverLeavesADanglingSurrogate() {
        // 一个增补平面 emoji 在 UTF-16 里是「高代理 + 低代理」两个 char，遮罩区间只要从中间切开就出事
        String emoji = new String(Character.toChars(0x1F6AA));
        assertThat(emoji.length()).isEqualTo(2);
        String text = "闭嘴" + emoji + "这里有个手机号13800138000";
        int pairStart = text.indexOf(emoji);

        // 情形一：命中区间以高代理结尾（遮了上一半、漏了下一半）
        CheckResult cutAfterHigh = result("隐私泄露", "grey", "REVIEW",
                List.of(hit("前缀", "隐私泄露", "grey", "REVIEW", 0, pairStart + 1)));
        // 情形二：命中区间以低代理开头（漏了上一半、遮了下一半）
        CheckResult cutBeforeLow = result("隐私泄露", "grey", "REVIEW",
                List.of(hit("后缀", "隐私泄露", "grey", "REVIEW", pairStart + 1, text.length())));

        for (CheckResult fake : List.of(cutAfterHigh, cutBeforeLow)) {
            String masked = PostService.maskNonRisk(text, fake);
            assertThat(masked).hasSize(text.length());
            assertThat(masked).doesNotContain(emoji);
            assertNoLoneSurrogate(masked);
        }
        // 反面对照：不动 emoji 的命中不会牵连它
        assertNoLoneSurrogate(PostService.maskNonRisk(text,
                result("隐私泄露", "grey", "REVIEW", List.of(
                        hit("13800138000", "隐私泄露", "grey", "REVIEW", text.length() - 11, text.length())))));
    }

    /** 遮罩后的串必须仍是合法 UTF-16：任何孤立代理项都会在 Jackson 序列化时炸成 500。 */
    private static void assertNoLoneSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertThat(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)))
                        .as("下标 %d 是高代理，下一位必须是低代理", i).isTrue();
                i++;
            } else if (Character.isLowSurrogate(c)) {
                org.assertj.core.api.Assertions.fail("下标 " + i + " 出现孤立低代理项");
            }
        }
    }


    // ------------------------------------------------------------------ 入参归一

    @Test
    @DisplayName("type 缺省按 normal，白名单外一律拒绝，不做「猜一个」的兜底")
    void typeNormalizesAndRejectsUnknown() {
        assertThat(PostService.normalizeType(null)).isEqualTo(PostService.TYPE_NORMAL);
        assertThat(PostService.normalizeType("  ")).isEqualTo(PostService.TYPE_NORMAL);
        assertThat(PostService.normalizeType(" hole ")).isEqualTo(PostService.TYPE_HOLE);
        assertThat(PostService.normalizeType(PostService.TYPE_HELP)).isEqualTo(PostService.TYPE_HELP);
        assertThatThrownBy(() -> PostService.normalizeType("whisper"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
    }

    @Test
    @DisplayName("visibility 缺省走配置默认；friends 明确拒绝而不是静默改成 public（静默改写等于公开了隐私）")
    void visibilityNeverSilentlyRewritten() {
        assertThat(PostService.normalizeVisibility(null, "public")).isEqualTo("public");
        assertThat(PostService.normalizeVisibility("", "private")).isEqualTo("private");
        assertThat(PostService.normalizeVisibility(" public ", "private")).isEqualTo("public");
        BizException e = assertThrows(BizException.class, () -> PostService.normalizeVisibility("friends", "public"));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(e.getMessage()).contains("仅自己");
    }

    @Test
    @DisplayName("\r\n 与 \r 一律归一成 \n：机审偏移量与遮罩切点只认一种换行符")
    void newlinesAreNormalizedToOneKind() {
        assertThat(PostService.normalizeNewlines("a\r\nb")).isEqualTo("a\nb");
        assertThat(PostService.normalizeNewlines("a\rb")).isEqualTo("a\nb");
        assertThat(PostService.normalizeNewlines("a\n\nb\r\n")).isEqualTo("a\n\nb\n");
        assertThat(PostService.normalizeNewlines(null)).isEmpty();
    }

    @Test
    @DisplayName("马甲场景与帖子形式一一对应：树洞 HOLE、求助 HELP、普通帖 ALL")
    void aliasSceneFollowsPostType() {
        assertThat(PostService.sceneFor(PostService.TYPE_HOLE)).isEqualTo("HOLE");
        assertThat(PostService.sceneFor(PostService.TYPE_HELP)).isEqualTo("HELP");
        assertThat(PostService.sceneFor(PostService.TYPE_NORMAL)).isEqualTo("ALL");
        assertThat(PostService.sceneFor(null)).isEqualTo("ALL");
    }
}
