package com.mindisle.post.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 信息流列表项（任务 3.5 · 手册 §6.1 行 3.5 · 需求 FR1.6、FR4.3）。
 *
 * <p><b>这是一份白名单，不是「把 post 表少给几个字段」</b>：risk_level、quality_score、
 * report_cnt、deleted 一律不出现在这里。前两个是运营/AI 侧的内部量（NFR8 数据最小化，
 * 也与 {@code PostView} 不透危机等级的口径一致），report_cnt 一旦外露就等于给刷屏者
 * 一个「还差几次能被拦」的进度条，deleted 则是逻辑删除位，属于存储细节。</p>
 *
 * <p><b>anonymous 为真时 authorId 必为 null</b>：需求 FR1.4 要的是「别人猜不到是谁」，
 * 把 user_id 留在响应体里，哪怕前端不显示，抓包就能反查作者，匿名等于没做。
 * 这一条由 {@code PostQueryServiceTest} 钉住。</p>
 *
 * <p><b>viewCnt 给的是库值</b>：详情页才走「缓存 + 每 5 分钟回写」那条路（见 ViewCountService），
 * 列表一屏二十条，逐条去合并未回写增量会让翻页变成二十次缓存读，换来的只是几次的读数差。
 * 所以列表数字最多滞后一个回写窗口，答辩演示时以详情页为准。</p>
 *
 * @param id            帖子 id，同时是游标分页的 nextCursor 取值
 * @param type          normal / hole / help（FR4.2 三种形式）
 * @param title         标题，机审遮罩后的落库值（用户看到的就是发出去的那个样子）
 * @param excerpt       正文摘要，前 80 字，超出补省略号
 * @param displayName   对外展示名：匿名帖是马甲名，实名帖是昵称（无昵称回退登录名）
 * @param anonymous     是否匿名
 * @param authorId      作者 id，仅实名帖给出；匿名帖恒为 null
 * @param topics        已关联且已过审的话题名（FR4.5）
 * @param images        配图，最多取列表缩略所需，宽高是服务端读盘真值
 * @param status        post.status 原值（DDL 的 8 态之一）。「我的帖子」要靠它区分「审核中 / 未通过 / 已发布」；
 *                      广场按可见性只放行 PUBLISHED 与「自己的待审」，所以对非作者这个字段恒为 PUBLISHED——不构成新增泄露面
 * @param visibility    public / private。同理，非作者拿到的行永远是 public；自己的 private 只有 {@code /api/users/me/posts} 会回
 * @param viewCnt       浏览量（库值，最多滞后一个回写窗口）
 * @param likeCnt       点赞数
 * @param commentCnt    评论数（评论链路在任务 3.9，当前恒为库值 0）
 * @param publishedAt   发布时间，游标排序键
 * @param autoDestroyAt 树洞到期销毁时间（FR4.2），非树洞为 null
 * @param auditTip      非空即「这条只有你能看见」：作者的待审帖占位用，其他人永远拿不到 null 以外的值
 * @param hotline       非空即必须显示求助卡片，与 {@code PostView.hotline} 同一契约
 * @param liked         <b>当前查看者</b>是否赞过这条（任务 3.6 · post_like 活动行）。
 *                      不传 viewerId 就算不出这个字段，所以它必然是「相对你是谁」的值——
 *                      与 auditTip 同理，绝不能拿 authorId 和自己比。
 * @param collected     当前查看者是否收藏了这条，同上
 * @param collectCnt    收藏人数。与 likeCnt 一样是 post 表上的冗余列，由互动时重算刷新
 *                      （{@code PostMapper#refreshCollectCnt}），所以它恒等于 post_like 的活动人数真相
 */
public record PostListItem(
        Long id,
        String type,
        String title,
        String excerpt,
        String displayName,
        boolean anonymous,
        Long authorId,
        List<String> topics,
        List<PostView.ImageBrief> images,
        String status,
        String visibility,
        long viewCnt,
        int likeCnt,
        int commentCnt,
        LocalDateTime publishedAt,
        LocalDateTime autoDestroyAt,
        String auditTip,
        String hotline,
        boolean liked,
        boolean collected,
        int collectCnt) {
}
