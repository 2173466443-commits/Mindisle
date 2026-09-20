package com.mindisle.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.audit.SensitiveWordEngine.CheckResult;
import com.mindisle.audit.SensitiveWordEngine.Hit;

/**
 * 危机分级的词面通道单测（创新点 ③ · 需求 §5.2、§18.3 · 手册 §6.1 行 3.3）。
 *
 * <p>不启 Spring、不接库：CrisisGrader 是纯函数，输入是引擎已经算好的 {@link CheckResult}，
 * 所以这里手搭命中列表就能把「L2/L3 怎么分」「工单里该留什么证据」两件事全部钉死。
 * 真实的「文本 → 命中」那一截由 {@code SensitiveWordEngineTest} 与 HTTP 冒烟分别覆盖。</p>
 */
class CrisisGraderTest {

    private static final int EVIDENCE_MAX = 200;

    private static Hit hit(String word, String level, String action, int start, int end) {
        return new Hit(word, "自伤自杀", level, action, "user", start, end);
    }

    private static Hit hit(String word, String category, String level, String action, int start, int end) {
        return new Hit(word, category, level, action, "user", start, end);
    }

    private static CheckResult result(List<Hit> hits) {
        Hit first = hits.isEmpty() ? null : hits.get(0);
        return new CheckResult(!hits.isEmpty(),
                first == null ? null : first.category(),
                first == null ? null : first.level(),
                first == null ? null : first.action(),
                hits.size(), hits,
                hits.stream().map(h -> new int[] { h.start(), h.end() }).toList(), "v0.1-test");
    }

    /** 在文本里定位一个词，返回以它为首条 risk 命中的结果，省掉每个用例手算下标。 */
    private static CheckResult riskAt(String text, String word) {
        int at = text.indexOf(word);
        if (at < 0) {
            throw new IllegalArgumentException("样本里没有这个词：" + word);
        }
        return result(List.of(hit(word, "risk", "TAG", at, at + word.length())));
    }

    @Test
    @DisplayName("没有命中：L0，且不建工单（需求 §5.2 L0 只软引导）")
    void cleanTextIsL0() {
        CheckResult none = result(List.of());

        assertThat(CrisisGrader.levelOf(none)).isEqualTo(CrisisGrader.L0);
        assertThat(CrisisGrader.needsTicket(CrisisGrader.L0)).isFalse();
        assertThat(CrisisGrader.levelOf(null)).isEqualTo(CrisisGrader.L0);
        assertThat(CrisisGrader.needsTicket(null)).isFalse();
    }

    @Test
    @DisplayName("只有隐私/广告类 grey 命中：不算危机，不建单")
    void greyOnlyIsNotCrisis() {
        CheckResult privacy = result(List.of(
                hit("13800138000", "隐私泄露", "grey", "REVIEW", 0, 11)));

        assertThat(CrisisGrader.levelOf(privacy)).isEqualTo(CrisisGrader.L0);
    }

    @Test
    @DisplayName("泛化自伤表述（无计划无方式）定 L2：建单、4 小时 SLA 那一档")
    void vagueSelfHarmIsL2() {
        CheckResult l2 = riskAt("这两天一直都在想伤害自己这件事", "伤害自己");

        assertThat(CrisisGrader.levelOf(l2)).isEqualTo(CrisisGrader.L2);
        assertThat(CrisisGrader.needsTicket(CrisisGrader.L2)).isTrue();
    }

    @Test
    @DisplayName("含方式/计划/告别语义的表述升 L3：割腕、把东西分给室友、最后一次跟这里说说话")
    void plannedSelfHarmIsL3() {
        assertThat(CrisisGrader.levelOf(riskAt("我想过用刀片割腕", "割腕"))).isEqualTo(CrisisGrader.L3);
        assertThat(CrisisGrader.levelOf(
                riskAt("我把东西分给室友了", "把东西分给室友"))).isEqualTo(CrisisGrader.L3);
        assertThat(CrisisGrader.levelOf(
                riskAt("这是最后一次跟这里说说话了", "最后一次跟这里说说话"))).isEqualTo(CrisisGrader.L3);
    }

    @Test
    @DisplayName("L2 与 L3 表述同时出现时取重的：判序不看主因，只看命中集合")
    void highestLevelWinsRegardlessOfOrder() {
        CheckResult l3First = result(List.of(
                hit("写遗书", "risk", "TAG", 0, 3),
                hit("伤害自己", "risk", "TAG", 10, 14)));
        CheckResult l3Last = result(List.of(
                hit("伤害自己", "risk", "TAG", 0, 4),
                hit("写遗书", "risk", "TAG", 10, 13)));

        assertThat(CrisisGrader.levelOf(l3First)).isEqualTo(CrisisGrader.L3);
        assertThat(CrisisGrader.levelOf(l3Last)).isEqualTo(CrisisGrader.L3);
    }

    @Test
    @DisplayName("词面通道的输出域只有 {L0,L2,L3}：L1 需要时间序列，发帖这一条文本判不出来的东西不假装判")
    void wordChannelNeverProducesL1() {
        List<CheckResult> samples = List.of(
                result(List.of()),
                result(List.of(hit("傻逼", "辱骂攻击", "grey", "REVIEW", 0, 2))),
                riskAt("活着没意思", "活着没意思"),
                riskAt("我想烧炭", "烧炭"));

        assertThat(CrisisGrader.L3_WORDS).isNotEmpty();
        for (CheckResult sample : samples) {
            assertThat(List.of(CrisisGrader.L0, CrisisGrader.L2, CrisisGrader.L3))
                    .contains(CrisisGrader.levelOf(sample));
        }
    }

    @Test
    @DisplayName("L3 词表快照防漂移：任何一次增删都必须显式改这里（词库升版要复核，见需求 §5.2 与任务 6.2）")
    void l3WordListIsLocked() {
        assertThat(CrisisGrader.L3_WORDS).containsExactlyInAnyOrder(
                "写遗书", "留遗书", "把东西分给室友", "最后一次跟这里说说话", "结束生命", "自我了断",
                "割腕", "自残", "吞药", "烧炭", "跳下去");
    }

    @Test
    @DisplayName("trigger_words：只收 risk 命中、去重、用中文顿号外的逗号连接，超长截断到列宽")
    void triggerWordsDedupeAndCut() {
        CheckResult mixed = result(List.of(
                hit("伤害自己", "risk", "TAG", 0, 4),
                hit("13800138000", "隐私泄露", "grey", "REVIEW", 5, 16),
                hit("伤害自己", "自伤自杀", "risk", "TAG", 20, 24),
                hit("烧炭", "risk", "TAG", 30, 32)));

        assertThat(CrisisGrader.triggerWords(mixed, 200)).isEqualTo("伤害自己，烧炭");
        assertThat(CrisisGrader.triggerWords(mixed, 6)).isEqualTo("伤害自己，烧");
        assertThat(CrisisGrader.triggerWords(result(List.of()), 200)).isEmpty();
    }

    @Test
    @DisplayName("证据片段以首条危机命中为中心开窗，而不是从头截 200 字")
    void evidenceIsAnchoredOnTheCrisisNotOnTheHead() {
        String prefix = "自我介绍部分".repeat(40);
        String text = prefix + "我真的撑不住了，一直在想伤害自己这件事，后天还要考试。";
        CheckResult result = riskAt(text, "伤害自己");

        String evidence = CrisisGrader.evidence(text, result, EVIDENCE_MAX);

        assertThat(evidence).contains("伤害自己");
        // 从头截会得到一段与危机毫无关系的自我介绍：窗口中心必须落在命中上
        assertThat(text.indexOf(evidence)).isGreaterThan(0);
        assertThat(evidence.length()).isLessThanOrEqualTo(EVIDENCE_MAX);
    }

    @Test
    @DisplayName("证据片段里的手机号被打码：工单是常驻数据，不能把求助者的联系方式抄进去（NFR8）")
    void evidenceMasksNonRiskHits() {
        String text = "我最近总在伤害自己的念头里打转，如果有人看到可以联系我 13800138000";
        String phone = "13800138000";
        int riskAt = text.indexOf("伤害自己");
        int phoneAt = text.indexOf(phone);
        CheckResult result = result(List.of(
                hit("伤害自己", "risk", "TAG", riskAt, riskAt + 4),
                hit(phone, "隐私泄露", "grey", "REVIEW", phoneAt, phoneAt + phone.length())));

        String evidence = CrisisGrader.evidence(text, result, EVIDENCE_MAX);

        assertThat(evidence).doesNotContain("13800138000").contains("*");
        assertThat(evidence).contains("伤害自己");
    }

    @Test
    @DisplayName("证据片段压平换行，管理端表格与站内信一行放得下")
    void evidenceFlattensNewlines() {
        String text = "第一段\n第二段有 伤害自己 的说法\n第三段";
        CheckResult result = riskAt(text, "伤害自己");

        String evidence = CrisisGrader.evidence(text, result, EVIDENCE_MAX);

        assertThat(evidence).doesNotContain("\n").doesNotContain("  ");
    }

    @Test
    @DisplayName("危机命中贴着文本末尾时窗口整体前挪，保证拿满 maxChars 而不是只截到一小截")
    void evidenceWindowSlidesBack() {
        String text = "陪着我把它写完的一段长话。".repeat(30) + "我准备伤害自己了";
        CheckResult result = riskAt(text, "伤害自己");

        String evidence = CrisisGrader.evidence(text, result, EVIDENCE_MAX);

        assertThat(evidence).hasSize(EVIDENCE_MAX).contains("伤害自己");
    }

    @Test
    @DisplayName("空文本、非正数窗口长度一律回空串：工单证据缺字段比写脏数据好")
    void evidenceGuardsEmptyInputs() {
        assertThat(CrisisGrader.evidence(null, riskAt("伤害自己", "伤害自己"), EVIDENCE_MAX)).isEmpty();
        assertThat(CrisisGrader.evidence("   ", riskAt("伤害自己", "伤害自己"), EVIDENCE_MAX)).isEmpty();
        assertThat(CrisisGrader.evidence("伤害自己", riskAt("伤害自己", "伤害自己"), 0)).isEmpty();
    }
}
