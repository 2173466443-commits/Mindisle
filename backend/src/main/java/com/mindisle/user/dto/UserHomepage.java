package com.mindisle.user.dto;

/**
 * 他人主页资料卡（任务 3.6 · 需求 FR1.5「个人主页：资料、获赞数、关注/粉丝」）。
 *
 * <p><b>这是一份白名单</b>：grade / school / gender / risk_flag / emotion_share_consent
 * 都不出参。前四个是敏感个人信息或内部标记（需求 §8.2、§12 规范 5「不做诊断与标签化表述」，
 * 与 {@code UserService.ProfileView} 剔除 risk_flag 的口径一致）；gender 在 DDL 注释里
 * 明确「须单独同意方可采集」，把它摆进任何人的主页等于默认公开。
 * 学校/年级要展示得先做「本人可选公开」的开关，那是需求没写的产品决策，不在本期擅自加。</p>
 *
 * <p><b>receivedLikeCnt 与 publicPostCnt 只算「公开且非匿名」的已发布帖</b>：
 * 需求 FR1.4 要的是「别人猜不到树洞是谁发的」。如果主页显示「收到过 12 个赞」而其中 9 个
 * 来自匿名马甲帖，这个数字本身就把「他还有一批匿名内容」泄露出去了，
 * 甚至能被拿去和广场时间线对账反推。口径与 {@code UserPostController} 公开主页列表逐字相同
 * （两者共用 {@link com.mindisle.mapper.PostMapper#sumReceivedLikes} 的同一条过滤条件）。</p>
 *
 * @param userId         主页主人 id
 * @param displayName    展示名，与帖子列表用的<b>同一个函数</b>算出（PostService#displayNameOf），
 *                       不会出现「主页显示昵称、他发的帖显示登录名」
 * @param avatar         头像 URL 或内置头像编号，原样透出（可为 null，前端有兜底图形）
 * @param bio            个性签名；没有 user_profile 行时回空串而不是 null，让前端少判一次
 * @param followingCnt   他关注了多少人（COUNT user_follow 真值）
 * @param followerCnt    多少人在关注他（同上）
 * @param receivedLikeCnt 公开非匿名帖收到的赞总数（口径见类注释）
 * @param publicPostCnt  公开非匿名已发布帖条数，与主页列表的 total 同源
 * @param following      访问者此刻是否关注着对方；访问者看自己主页时恒为 false（没有「关注自己」这种关系）
 * @param self           是不是访问者本人的主页，前端据此把「关注」按钮换成「编辑资料」
 */
public record UserHomepage(
        Long userId,
        String displayName,
        String avatar,
        String bio,
        long followingCnt,
        long followerCnt,
        long receivedLikeCnt,
        long publicPostCnt,
        boolean following,
        boolean self) {
}
