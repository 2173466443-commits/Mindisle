package com.mindisle.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.config.MindisleProperties;

/**
 * 对话安全闸的单测（任务 T4.18 · 需求 FR7.1「AI 输出也要过审」、BR8、FR10.4）。
 *
 * <p><b>用线上真词库而不是 fixture</b>：本类要钉住的是「模型说出的这句合规话会不会被自己人换掉」，
 * 而答案取决于 139 条快照里那 20 个医疗裸词的具体词面（「开药」正是 2026-09-24 真链路 F 轮误伤的元凶）。
 * 自造 fixture 会把前提抹掉，测试就成了自证。词库经 {@code afterPropertiesSet()} 从
 * {@code mindisle.audit.dict-resource} 的 classpath 快照装载，与线上同源。</p>
 *
 * <p>覆盖三件事：① 专业转介语境豁免（好答案留下）；② 豁免的三条边界（混入其他分组 /
 * 含处方断言 / 危机轮复述方法词）必须照样拦回去；③ 非医疗 BLOCK、BR8 词表、输入侧不改写
 * 这些既有判据不因豁免而放水。</p>
 */
@DisplayName("T4.18 对话安全闸：医疗越界 BLOCK 与专业转介豁免")
class SafetyGuardTest {

    /** 真链路实测 F 轮的模型原文（用户问「舍曲林恶心能否自行减量」），逐字来自 _cache/009_mindisle/ai-live-3.out。 */
    private static final String ROUND_F_GOOD_ANSWER =
            "恶心确实挺难受的，不过减量这件事得由开药的医生来定，因为剂量怎么调跟你的具体情况有关。"
            + "你可以先把「什么时候开始恶心、每天吃多少、吃了多久」记下来，下次复诊时直接告诉医生，"
            + "或者这两天先打电话问一下给你开药的医院。你最近一次复诊大概是什么时候？";

    private static SafetyGuard guard;

    @BeforeAll
    static void setUp() throws Exception {
        MindisleProperties properties = new MindisleProperties();
        SensitiveWordEngine engine = new SensitiveWordEngine(properties, new DefaultResourceLoader());
        engine.afterPropertiesSet();
        assertThat(engine.version()).as("必须是线上那份 v0.1 快照，否则下面的断言没有意义").isEqualTo("v0.1");
        assertThat(engine.wordCount()).as("词库条数一变，本类的豁免边界就得重看").isEqualTo(139);
        guard = new SafetyGuard(engine, properties, new CrisisVocabulary());
    }

    @Test
    @DisplayName("「得由开药的医生来定」这类合规转介句不再被整条替换（真链路 F 轮回归）")
    void medicalReferralAnswerIsKept() {
        SafetyGuard.Output out = guard.guardAiOutput(ROUND_F_GOOD_ANSWER, "L0");

        assertThat(out.rewrote()).as("BR8 要求的正是这种把判断权交回医生的回答").isFalse();
        assertThat(out.text()).isEqualTo(ROUND_F_GOOD_ANSWER);
        assertThat(out.reason()).startsWith("medical-referral:").contains("开药");
    }

    @Test
    @DisplayName("豁免边界①：同一句里出现处方断言（我给你开药）时不豁免")
    void prescriptionAssertionNeverExempted() {
        SafetyGuard.Output out = guard.guardAiOutput("我给你开药，你让医生看一下就行。", "L0");

        assertThat(out.rewrote()).isTrue();
        assertThat(out.reason()).startsWith("sensitive-BLOCK:" + SafetyGuard.MEDICAL_CATEGORY);
    }

    @Test
    @DisplayName("豁免边界②：命中词面混进其他分组（广告导流）时不豁免")
    void mixedCategoryNeverExempted() {
        SafetyGuard.Output out = guard.guardAiOutput(
                "这个可以让医生给你开药，也可以加微信领优惠券群。", "L0");

        assertThat(out.rewrote()).isTrue();
        assertThat(out.reason()).startsWith("sensitive-BLOCK:").doesNotStartWith("medical-referral:");
    }

    @Test
    @DisplayName("豁免边界③：危机轮次复述方法词（剂量）时不豁免")
    void crisisRoundWithMethodWordNeverExempted() {
        SafetyGuard.Output out = guard.guardAiOutput(
                "减量这件事得由开药的医生来定，实在难受就先按原来的剂量，别自己调。", "L2");

        assertThat(out.rewrote()).isTrue();
        assertThat(out.reason()).doesNotStartWith("medical-referral:");
    }

    @Test
    @DisplayName("BR8 输出侧词表仍是硬判据：模型自己下诊断时不豁免")
    void br8TermBlocksExemption() {
        assertThat(guard.firstBr8Term("你这是抑郁症")).isEqualTo("抑郁症");

        SafetyGuard.Output out = guard.guardAiOutput(
                "你这是抑郁症，要吃药，具体用药剂量得由医生来定。", "L0");

        assertThat(out.rewrote()).isTrue();
        assertThat(out.reason()).doesNotStartWith("medical-referral:");
    }

    @Test
    @DisplayName("非医疗分组照旧整条替换，且 reason 带上命中的词面")
    void nonMedicalBlockStillRewritten() {
        SafetyGuard.Output out = guard.guardAiOutput("这种成人网站的内容不要发。", "L0");

        assertThat(out.rewrote()).isTrue();
        assertThat(out.reason()).startsWith("sensitive-BLOCK:色情低俗").contains("成人网站");
    }

    @Test
    @DisplayName("输入侧只清洗不改写：用户的原话必须原样进模型（风险通道靠的是真话）")
    void userInputIsNeverSilentlyRewritten() {
        SafetyGuard.Input in = guard.sanitizeUser("我不想活了，消失一段时间");

        assertThat(in.text()).contains("不想活").contains("消失一段时间");
        assertThat(in.injectionSuspected()).isFalse();
    }

    @Test
    @DisplayName("空输出一律不记成「被安全闸拦了」，避免误报污染统计")
    void blankOutputIsPassThrough() {
        assertThat(guard.guardAiOutput(null, "L0").rewrote()).isFalse();
        assertThat(guard.guardAiOutput(null, "L0").reason()).isNull();
        assertThat(guard.guardAiOutput("   \n  ", "L0").rewrote()).isFalse();
        assertThat(guard.guardAiOutput("   \n  ", "L0").reason()).isNull();
    }
}
