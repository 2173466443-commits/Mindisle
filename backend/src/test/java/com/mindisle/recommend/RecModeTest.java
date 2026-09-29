package com.mindisle.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A/B 分流单测（任务 T7.8 · 手册 §10.2 7.8 · 需求 FR5.10）。
 *
 * <p><b>这里钉的不是比例，而是「可复现」</b>：同一个 userId 在离线写缓存和在线读缓存两处必须落到
 * 同一个 mode。两处各写一套判断的后果不是「推荐不准」，而是<b>永远读空</b>——
 * 缓存按 cf 批次写、在线按 hot 批次读，页面因为空批次自动退回热度兜底而不报错，
 * 于是 A/B 两组指标全都成了热度榜的数字，论文里的对照组当场作废，而且没有任何一条日志会提醒。</p>
 */
class RecModeTest {

    @Test
    @DisplayName("尾号 0–6 走 CF、7–9 走纯热度：分组只由 ID 尾号决定")
    void splitsByTailDigit() {
        for (long tail = 0; tail < 10; tail++) {
            String mode = RecMode.forUser(1_000L + tail);
            assertThat(mode).as("尾号 %s", tail)
                .isEqualTo(tail < RecMode.CF_TAIL_BOUND ? RecMode.MODE_CF : RecMode.MODE_HOT);
        }
    }

    @Test
    @DisplayName("同一用户两次分流结果必须一致（离线与在线共用同一函数的根据）")
    void isDeterministic() {
        long userId = 486L;
        assertThat(RecMode.forUser(userId)).isEqualTo(RecMode.forUser(userId));
        // 具体到真实账号：486 尾号 6 → CF；487 尾号 7 → 热度对照组
        assertThat(RecMode.forUser(486L)).isEqualTo(RecMode.MODE_CF);
        assertThat(RecMode.forUser(487L)).isEqualTo(RecMode.MODE_HOT);
    }

    @Test
    @DisplayName("前 100 个连号 7:3 分组，且负 ID 不落入 CF（% 取余会出负数，floorMod 才安全）")
    void keepsRatioAndHandlesNegativeId() {
        int cf = 0;
        for (long id = 0; id < 100; id++) {
            if (RecMode.MODE_CF.equals(RecMode.forUser(id))) {
                cf++;
            }
        }
        assertThat(cf).isEqualTo(70);
        assertThat(RecMode.forUser(-1L)).as("floorMod(-1,10)=9 → 热度组")
            .isEqualTo(RecMode.MODE_HOT);
        assertThat(RecMode.forUser(-7L)).as("floorMod(-7,10)=3 → CF 组，而不是 % 得到的 −7")
            .isEqualTo(RecMode.MODE_CF);
        assertThat(RecMode.forUser(-8L)).as("floorMod(-8,10)=2 → CF 组").isEqualTo(RecMode.MODE_CF);
    }

    @Test
    @DisplayName("mode 白名单只认 cf/hot/ab：写别的会被 MySQL 静默改成默认值")
    void modeWhitelistMatchesEnum() {
        assertThat(RecMode.isKnown(RecMode.MODE_CF)).isTrue();
        assertThat(RecMode.isKnown(RecMode.MODE_HOT)).isTrue();
        assertThat(RecMode.isKnown(RecMode.MODE_AB)).isTrue();
        assertThat(RecMode.isKnown("CF")).isFalse();
        assertThat(RecMode.isKnown("usercf")).as("召回通道不是分流模式").isFalse();
        assertThat(RecMode.isKnown("")).isFalse();
        assertThat(RecMode.isKnown(null)).isFalse();
        assertThat(RecMode.isHot(RecMode.MODE_HOT)).isTrue();
        assertThat(RecMode.isHot(RecMode.MODE_CF)).isFalse();
        assertThat(RecMode.isHot(null)).isFalse();
        // forUser 只会返回 cf/hot：ab 留给离线消融批次，不能出现在在线读路径上
        for (long id = 0; id < 40; id++) {
            assertThat(RecMode.forUser(id)).isIn(RecMode.MODE_CF, RecMode.MODE_HOT);
        }
    }
}
