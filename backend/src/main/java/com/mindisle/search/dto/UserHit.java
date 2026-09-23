package com.mindisle.search.dto;

/**
 * 「搜人」结果（任务 3.9 · 需求 FR4.8「关键词搜昵称」）。
 *
 * <p><b>这是一份白名单</b>：只有 id、昵称、头像三个字段。email 与手机号属敏感个人信息
 * （NFR8 数据最小化），role 与 status 是运营信息，lastLoginAt 是行为痕迹 —— 一个都不给。
 * 尤其 role：把「这个账号是管理员」广播给任意登录用户，等于给钓鱼多提供了一条可信度线索。
 * 想看完整资料卡走 {@code GET /api/users/{id}/profile}，那才是资料卡的口径与限流。</p>
 *
 * <p>入参侧已经过滤掉 status 非 ACTIVE 的行，所以这里不需要再带 status：恒真的字段
 * 出现在响应体里只会让人误以为「还会有别的值」。</p>
 */
public record UserHit(Long id, String nickname, String avatar) {

    public static UserHit of(com.mindisle.entity.User user) {
        // 昵称空时回退登录名：与 PostQueryService.displayNameOf 同一口径，两处不一致会让人以为搜到的是别人
        String nickname = user.getNickname() == null || user.getNickname().isBlank()
                ? user.getUsername() : user.getNickname().trim();
        return new UserHit(user.getId(), nickname, user.getAvatar());
    }
}