package com.mindisle.emotion;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.emotion.DictEmotionEngine.Analysis;
import com.mindisle.emotion.EmotionLexicon.Entry;
import com.mindisle.emotion.EmotionLexicon.Match;

/** 临时探针：打印引擎与 FMM 对一批样本的真实读数，用于定期望值。跑完即删。 */
class EmotionDumpTest {

    @Test
    void dump() {
        EmotionLexicon lex = EmotionLexicon.load();
        EmotionPrior prior = EmotionPrior.load(new ObjectMapper());
        DictEmotionEngine engine = new DictEmotionEngine(lex, prior);
        System.out.println("[dump] modelVersion=" + engine.modelVersion() + " words=" + lex.dutFaceCount()
                + " entries=" + lex.dutEntryCount() + " slots=" + lex.wordSlots() + " maxLen="
                + lex.maxWordLength() + " priorLoaded=" + prior.loaded() + " ceiling="
                + prior.coverageParam("confCeiling", -1));
        List<String> samples = List.of(
                "今天很开心", "我还是选择信任他", "看到别人被表扬我有点嫉妒", "投了三次简历，已经心灰意冷",
                "半夜听到敲门声，吓得魂飞魄散", "他很责备自己的粗心", "深呼吸之后心情平静",
                "我很开心", "我现在一点也不开心", "这一点也不难过", "我为这次失误感到羞愧",
                "最近一直很焦虑", "我很难过", "我非常难过", "我非常极其难过", "这事真是难过死了",
                "这事很难过", "虽然被导师批评了很难过，但是我很快就开心起来",
                "虽然拿了奖学金很开心，但是我还是很焦虑", "😡", "我好难过！！", "我好难过",
                "我最近很焦虑", "我最近很焦虑吗？", "我最近很开心吗？", "我最近很开心",
                "，。！？", "今天食堂的番茄炒蛋放了糖", "难过，难过，难过",
                "今天下午去图书馆坐了一会儿，路上买了杯咖啡，回来的时候在下雨，"
                        + "会议室的投影仪又坏了，修了半天，最后老师说改到明天上午再讨论一次",
                "这个结果真的 yyds", "笑死我了 xswl", "可真是个好结果呢，我真是太满意了",
                "很开心很高兴", "今天心情还行吧", "心情平静", "我害怕", "我愤怒", "真让人厌恶",
                "投了三次简历都被拒了，我已经心灰意冷了，每天都很难过，也很焦虑，晚上还害怕明天",
                "好结果");
        for (String s : samples) {
            Analysis a = engine.analyze(s);
            StringBuilder hits = new StringBuilder();
            a.hits().forEach(h -> hits.append(h.word()).append('[').append(h.kind()).append(' ')
                    .append(h.dutCode()).append(' ').append(h.label()).append(" i").append(h.intensity())
                    .append(" w").append(h.weight()).append(h.negated() ? " NEG" : "")
                    .append(h.aux() ? " AUX" : "").append("] "));
            StringBuilder fmm = new StringBuilder();
            lex.match(s).forEach(m -> {
                for (Entry e : m.entries()) {
                    fmm.append(m.start()).append('-').append(m.end()).append(':').append(e.word())
                            .append('(').append(e.kind()).append('/').append(e.dutCode()).append('/')
                            .append(e.label()).append("/s").append(e.senseIndex()).append(") ");
                }
            });
            System.out.println("[dump] << " + s + "\n       => label=" + a.label() + " i=" + a.intensity()
                    + " v=" + a.valence() + " conf=" + a.confidence() + " needLlm=" + a.needsLlm()
                    + " contentChars=" + a.contentChars() + " empty=" + a.empty()
                    + "\n       hits: " + hits
                    + "\n       fmm : " + fmm);
        }
    }
}
