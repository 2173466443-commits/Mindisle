package com.mindisle.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 登录/刷新闸门对账号态的判定（任务 T5.7 补漏 · 需求 BR6）。
 *
 * <p>来历：第 49 轮补跑 Gate5 的 A16（「MUTED 账号发私信要 10003」）时，先要把演示号置成 MUTED，
 * 结果连登录都拿不到令牌 —— AuthService#allowCoolingOrReject 只放行 ACTIVE 与冷静期内的 DELETED，
 * MUTED 被判 403/20003。那样的「禁言」实际是临时封号：BR6 明写禁言期间「可读、可点赞」，
 * 而「可读」在工程上的前提就是他还能把自己的账号登进来。夺言的是 PostingQuotaService 那道写入闸门
 * （403/10003），不是登录闸门。</p>
 *
 * <p>本类只打这一个静态判据：它不需要把八个 bean 全 mock 一遍，且它是全站唯一一处
 * 「账号态 → 能不能进门」的映射，值得单独钉住。</p>
 */
class AuthServiceSignInTest {

    @ParameterizedTest
    @ValueSource(strings = { "ACTIVE", "MUTED", " muted ", "mUtEd" })
    @DisplayName("ACTIVE 与 MUTED 都能建立新会话（禁言只夺「说」，不夺「看」）")
    void activeAndMutedMaySignIn(String status) {
        assertThat(AuthService.allowsSignIn(status)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "BANNED", "DELETED", "", "   ", "unknown", "MUTED_" })
    @DisplayName("封禁、注销、以及任何看不懂的脏值一律不给进 —— 失败关闭，不给「脏状态被当成禁言」留口子")
    void everythingElseIsRefused(String status) {
        assertThat(AuthService.allowsSignIn(status)).isFalse();
    }

    @Test
    @DisplayName("status 为 null 不抛异常，按拒绝处理（库里这一列 NOT NULL，但判据不能建立在「数据库永远对」上）")
    void nullStatusFailsClosed() {
        assertThat(AuthService.allowsSignIn(null)).isFalse();
    }

    @Test
    @DisplayName("MUTED 常量与 sql/01_account.sql:20 的 ENUM 取值逐字一致")
    void mutedConstantMatchesDdl() {
        assertThat(AuthService.MUTED).isEqualTo("MUTED");
    }
}