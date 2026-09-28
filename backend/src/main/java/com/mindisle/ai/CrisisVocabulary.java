package com.mindisle.ai;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 危机应答与安全闸的共享常量与判据（从 {@link SafetyGuard} 拆出，见该类注释）。
 *
 * <p><b>为什么单独一个类</b>：方法类词表这件事有两个消费者 —— {@code SafetyGuard} 用它拦输出，
 * 单测与管理端用它解释「为什么这条回复被换掉了」。放进 SafetyGuard 的 private 字段，
 * 第二个消费者就只能反射或者抄一份，而抄的那一份一定会漂。</p>
 */
@Component
public class CrisisVocabulary {

    /**
     * 「方法类」词面：在危机轮次里<b>绝对不允许</b>由模型复述（需求 FR10.4 第 4 条
     * 「不得输出任何方法性信息」）。
     *
     * <p><b>为什么不是直接用词库里 20 条 risk 级词</b>：那 20 条里混着两类语义完全不同的东西。
     * 「不想活 / 撑不下去 / 死了就好」是<b>念头</b>，模型在危机应答里复述它属于正常反映
     * （「我听到你说撑不下去了」），拿它当拦阻条件会把合规的危机回复也换掉；
     * 「割腕 / 吞药 / 烧炭 / 跳下去」是<b>方法与工具</b>，模型复述它就是伤害。
     * 需求写的是后者，所以这里只收后者。这条切分是本表存在的全部理由。</p>
     *
     * <p>只在 L2/L3 轮次生效（见 {@code SafetyGuard#guardAiOutput}）：非危机轮次里
     * 讨论「烧炭火锅」不该被当成危机复述。</p>
     */
    static final Set<String> METHOD_WORDS = Set.of(
            "割腕", "自残", "吞药", "烧炭", "跳下去", "跳楼", "上吊", "服毒", "喝药",
            "安眠药", "剂量", "刀刃", "刀片", "怎么死", "自杀方法", "自杀方式",
            "一了百了", "自我了断", "结束生命", "写遗书", "留遗书", "伤害自己");

    /** 命中的方法类词面（小写不敏感，中文原样比较）；没命中返回 null。 */
    public String firstMethodWord(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : METHOD_WORDS) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) {
                return word;
            }
        }
        return null;
    }

    /** 词表面数，给自检与管理端显示。 */
    public int size() {
        return METHOD_WORDS.size();
    }
}
