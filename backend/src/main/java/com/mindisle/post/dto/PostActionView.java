package com.mindisle.post.dto;

/**
 * 一次互动之后的帖子状态（任务 3.6 · 需求 FR4.4、BR2）。
 *
 * <p><b>为什么不回「成功/失败」就完事</b>：点赞按钮是即时反馈控件，用户要看到的是
 * 「现在到底赞没赞上、一共多少人赞」。把 {@code liked/collected} 与两个计数一起回，
 * 前端就不用在本地猜状态——猜出来的状态与服务端不一致时，卡片上的数字会开始说谎。
 * 幂等重试（连点、网络重发）因此天然收敛到同一个显示结果。</p>
 *
 * @param postId    帖子 id
 * @param action    本次被受理的动作（归一化后的小写值）
 * @param changed   这次请求<b>有没有真的改变</b>点赞/收藏关系：幂等重复请求为 false
 * @param liked     当前用户此刻是否已赞这条（以库里活动行为准，不是「本次动作是不是 like」）
 * @param collected 当前用户此刻是否已收藏这条
 * @param likeCnt   重算后的点赞人数（与 post_like 恒等，见 PostMapper#refreshLikeCnt）
 * @param collectCnt 重算后的收藏人数
 * @param selfAction 本次动作是否发生在自己的内容上（BR4：这类动作不许进推荐质量分，
 *                   阶段 7 的行为埋点 T3.10 要靠这个标记跳过，所以现在就回传）
 */
public record PostActionView(
        Long postId,
        String action,
        boolean changed,
        boolean liked,
        boolean collected,
        long likeCnt,
        long collectCnt,
        boolean selfAction) {
}
