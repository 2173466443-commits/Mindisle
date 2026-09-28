package com.mindisle.emotion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.emotion.DictEmotionEngine.Analysis;

/**
 * 词典情绪通道的规则单测（任务 T4.7 · 手册 §7.2 施工细则五步逐条钉住）。
 *
 * <p><b>期望值不是手填的</b>：全部来自 {@code EmotionDumpTest} 打印的真机读数
 * （modelVersion=dut-4.0-mindisle-v1+prior-v1.0，词面 27,315 / 词条 31,291）。
 * 先跑探针、再写断言，是为了避免「为了让测试绿而调整期望值」这种自证。
 * 断言里凡是只比大小（强度、置信度）而不钉死小数的地方，都是**故意的**：
 * 那几个数由 {@code prior.json} 的乘子算出，词典版本一换就会动，
 * 而规则方向（程度副词升强度、转折取后句、问句给负面升强度）是不能动的。</p>
 *
 * <p><b>两条「预期失手」用例（反讽、网络用语）是论文消融表里的定性样本</b>，
 * 它们断言的是「词典会判错」这件事本身。把失手写成断言，
 * 比删掉用例诚实：需求 §8.1.2 的级联策略正是为这两种情况才存在的。</p>
 */
class DictEmotionEngineTest {

    private static DictEmotionEngine engine;
    private static EmotionLexicon lexicon;
    private static EmotionPrior prior;

    @BeforeAll
    static void setUp() {
        lexicon = EmotionLexicon.load();
        prior = EmotionPrior.load(new ObjectMapper());
        engine = new DictEmotionEngine(lexicon, prior);
    }

    private Analysis of(String text) {
        return engine.analyze(text);
    }

    // ============================================================ ② 七类各至少一例

    @Test
    @DisplayName("七类标签各至少一例：类别、效价、强度方向全部对得上")
    void sevenCategories() {
        assertEquals("joy", of("今天很开心").label());
        assertEquals("trust", of("我还是选择信任他").label());
        assertEquals("anger", of("我愤怒").label());
        assertEquals("sadness", of("我很难过").label());
        assertEquals("fear", of("半夜听到敲门声，吓得魂飞魄散").label());
        assertEquals("disgust", of("真让人厌恶").label());
        assertEquals("neutral", of("心情平静").label());
        assertEquals(1, of("今天很开心").valence(), "乐是正向");
        assertEquals(1, of("我还是选择信任他").valence(), "信任是正向");
        assertEquals(0, of("心情平静").valence(), "中性效价为 0");
        assertEquals(-1, of("我愤怒").valence(), "怒是负向");
        assertEquals(-1, of("半夜听到敲门声，吓得魂飞魄散").valence(), "惧是负向");
    }

    @Test
    @DisplayName("标签取值域恒等于 EmotionPrior.LABELS（界面按这 7 个渲染，多出一个是白屏）")
    void labelDomainIsClosed() {
        for (String text : new String[] { "今天很开心", "我愤怒", "心情平静", "我很难过", "我害怕",
                "真让人厌恶", "我还是选择信任他", "可真是个好结果呢" }) {
            assertTrue(EmotionPrior.LABELS.contains(of(text).label()), text);
        }
    }

    // ============================================================ ③ 修正规则四条

    @Test
    @DisplayName("程度副词乘子：非常 > 很，叠加两个乘子还会再涨，但强度封顶 5")
    void degreeAdverbs() {
        Analysis plain = of("我很难过");
        Analysis strong = of("我非常难过");
        Analysis stacked = of("我非常极其难过");
        assertEquals("sadness", plain.label());
        assertTrue(strong.intensity() >= plain.intensity(), "「非常」不该比「很」弱");
        assertTrue(stacked.confidence() > plain.confidence(), "两个乘子命中的覆盖度更高");
        assertEquals(5, strong.intensity());
        assertEquals(5, stacked.intensity(), "强度必须夹在 1-5，乘子不能把它推出界");
    }

    @Test
    @DisplayName("「X 死了」这类补语也算程度：难过死了 = 强度 5")
    void degreeSuffix() {
        assertEquals("sadness", of("这事真是难过死了").label());
        assertEquals(5, of("这事真是难过死了").intensity());
    }

    @Test
    @DisplayName("否定翻转效价：一点也不开心 → 不再是 joy，且落到负向")
    void negationFlipsValence() {
        Analysis positive = of("我很开心");
        Analysis negated = of("我现在一点也不开心");
        assertEquals(1, positive.valence());
        assertFalse("joy".equals(negated.label()), "否定之后还判成乐，翻转规则就是没生效");
        assertEquals(-1, negated.valence(), "「一点也不开心」是负向");
        assertTrue(negated.confidence() > positive.confidence(), "否定短语本身也是命中，覆盖度更高");
    }

    @Test
    @DisplayName("否定翻转效价（第二形）：这一点也不难过 → 中性而不是负向")
    void negationToNeutral() {
        Analysis a = of("这一点也不难过");
        assertEquals("neutral", a.label());
        assertEquals(0, a.valence());
        assertTrue(a.confidence() >= 0.6d, "命中「不难过」这个短语的置信度应当可信（实测 0.838）");
    }

    @Test
    @DisplayName("转折取后句：两个方向相反的从句，结论跟在后半句")
    void adversativeTakesSecondClause() {
        assertEquals("joy", of("虽然被导师批评了很难过，但是我很快就开心起来").label(), "后句是开心");
        assertEquals("sadness", of("虽然拿了奖学金很开心，但是我还是很焦虑").label(), "后句是焦虑");
    }

    @Test
    @DisplayName("问号给负面升强度，但不动正向（反问句里的情绪仍是说话人的）")
    void questionRaisesNegativeIntensityOnly() {
        Analysis negative = of("我最近很焦虑");
        Analysis negativeAsk = of("我最近很焦虑吗？");
        assertEquals("sadness", negative.label());
        assertTrue(negativeAsk.intensity() > negative.intensity(), "负面 + 问号要升强度");
        Analysis positive = of("我最近很开心");
        Analysis positiveAsk = of("我最近很开心吗？");
        assertEquals(positive.intensity(), positiveAsk.intensity(), "正向不因问号变强");
    }

    @Test
    @DisplayName("emoji 单独成句也算命中，但不计入内容长度")
    void emojiCountsAsHit() {
        Analysis a = of("😡");
        assertEquals("anger", a.label());
        assertEquals(5, a.intensity());
        assertEquals(0, a.contentChars(), "emoji 不是「字」，长度口径里不该算它");
        assertFalse(a.empty());
    }

    @Test
    @DisplayName("重复命中提升覆盖度：同一词说三遍，置信度显著高于说一遍")
    void repetitionRaisesConfidence() {
        Analysis once = of("我很难过");
        Analysis thrice = of("难过，难过，难过");
        assertEquals("sadness", thrice.label());
        assertTrue(thrice.confidence() > once.confidence(),
                "conf = 命中覆盖度 × 类别一致率，重复必然抬覆盖度");
    }

    // ============================================================ ④ conf 与 needsLlm 的闸门

    @Test
    @DisplayName("conf < 升级阈值即 needsLlm=true：这是级联策略的唯一入口，不能反")
    void lowConfidenceAsksForLlm() {
        assertFalse(of("今天很开心").needsLlm(), "实测 conf 0.594 ≥ 0.55");
        assertTrue(of("我还是选择信任他").needsLlm(), "实测 conf 0.513 < 0.55");
        assertTrue(of("看到别人被表扬我有点嫉妒").needsLlm(), "实测 conf 0.365");
        // 默认值必须给一个「不可能是真值」的哨兵。上一版写的是 threshold("llmFallback", 0.55d)，
        // 而 prior.json 里的键实际叫 llmFallbackConf：键名拼错时 getOrDefault 返回兜底 0.55，
        // 与真值巧合相等，这条断言就静默地永远为真了。改成 -1 之后，键名一错断言立刻红。
        double fallback = prior.threshold("llmFallbackConf", -1d);
        assertEquals(0.55d, fallback, 1e-9, "prior.json 的 llmFallbackConf 必须是 0.55（需求 §8.1.2 级联升级阈值）");
        for (String text : new String[] { "今天很开心", "我很难过", "我愤怒", "真让人厌恶" }) {
            Analysis a = of(text);
            assertEquals(a.confidence() < fallback, a.needsLlm(), text);
        }
    }

    @Test
    @DisplayName("完全没有情绪词：empty=true、conf=0、送 LLM，界面此时不该显示「平静」")
    void noEmotionIsEmpty() {
        Analysis a = of("今天下午去图书馆坐了一会儿，路上买了杯咖啡，回来的时候在下雨");
        assertTrue(a.empty(), "没切中任何词条");
        assertEquals(0d, a.confidence(), 1e-9);
        assertTrue(a.needsLlm());
        assertEquals("neutral", a.label(), "兜底标签仍是 neutral，但 empty 把它和「心情平静」分开了");
    }

    @Test
    @DisplayName("标点串与三字段空文本都不炸：空句是真实输入，用户会只发一个「？」")
    void degenerateInputs() {
        assertTrue(of("，。！？").empty());
        assertTrue(of("").empty());
        assertEquals(of(""), of(null));
    }

    // ============================================================ ⑤ 两条预期失手（消融定性样本）

    @Test
    @DisplayName("【预期失手】反讽：「可真是个好结果呢」被词典判成乐——这正是需求 §8.1.2 要 LLM 兜底的场景")
    void ironyIsKnownFailure() {
        Analysis a = of("可真是个好结果呢，我真是太满意了");
        assertEquals("joy", a.label(), "词典按字面命中「好结果/满意」，判成乐");
        assertTrue(a.needsLlm() || a.confidence() < 0.6d, "但它自己承认不确定：兜底通道有得救");
    }

    @Test
    @DisplayName("【预期失手】网络用语：yyds / 笑死我了 / xswl 全部落在词典之外")
    void internetSlangMissesLexicon() {
        for (String text : new String[] { "这个结果真的 yyds", "笑死我了 xswl", "今天心情还行吧" }) {
            assertTrue(of(text).empty(), text + " 应当切不到 DUT 词面");
            assertTrue(of(text).needsLlm(), text + " 必须升级送 LLM");
        }
    }

    // ============================================================ 落库字段口径

    @Test
    @DisplayName("model_version 固定为「词典版本+先验版本」，写进 emotion_record 时可追溯")
    void modelVersionIsComposed() {
        assertEquals("dut-4.0-mindisle-v1+prior-v1.0", engine.modelVersion());
        assertEquals(engine.modelVersion(), of("我很难过").modelVersion());
        assertEquals("dict", DictEmotionEngine.CHANNEL);
        assertTrue(lexicon.dutFaceCount() > 20000, "27,315 个词面装载不全就别跑实验");
        assertTrue(prior.loaded(), "prior.json 没读进来时所有乘子会退回内置默认，实验口径会静默改变");
    }

    @Test
    @DisplayName("hitWords 用全角逗号拼命中词面，且按 maxChars 截断（emotion_record.text_snippet 列宽 200）")
    void hitWordsJoinAndCut() {
        Analysis a = of("每天都很难过，也很焦虑，晚上还害怕明天");
        String words = a.hitWords(200);
        assertTrue(words.contains("难过"), words);
        assertTrue(words.indexOf('，') > 0, "分隔符必须是全角逗号，档案页词云按它切开");
        assertTrue(a.hitWords(4).length() <= 4, "maxChars 是给列宽用的，不能超");
        assertNotNull(of("我很难过").hitWords(0));
    }
}
