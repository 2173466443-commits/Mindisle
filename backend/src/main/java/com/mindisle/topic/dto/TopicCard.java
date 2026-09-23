package com.mindisle.topic.dto;

import java.math.BigDecimal;

import com.mindisle.entity.Topic;

/**
 * 话题详情页的资料头（任务 3.8 · 手册 §6.2 U6「话题详情页：头图、参与数、发帖入口」）。
 *
 * <p>字段集合与 {@code FeedController.TopicBrief} / {@code search.dto.TopicHit} 保持同一口径：
 * <b>不透出 {@code cover}（对象存储域名）、{@code deleted}（存储细节）、{@code auditStatus}</b>。
 * 这里的 auditStatus 尤其不必透：能构造出本类型的话题恒为 APPROVED
 * （待审与驳回都在 {@code TopicService#requireReadable} 那一步就被挡掉了），
 * 回一个恒等于常量的字段只是多给外人一条可枚举面。</p>
 *
 * <p>{@code following} 是本次查看者的状态，所以这个 record 只能是「带 viewer 的函数返回值」
 * 而不是 Mapper 的投影 —— 与 {@code PostListItem} 里 liked/collected 同一个理由。</p>
 *
 * <p><b>U6 的「头图」这一列今天恒空</b>：{@code topic.cover} 这一列在 DDL 里有，
 * 但阶段 3 没有给话题上传封面的通道（图片上传接口只服务帖子配图，且不给未过审实体挂图）。
 * 前端拿到的是 {@code cover=null}，渲染成占位渐变而不是破图。</p>
 *
 * @param id        话题 id
 * @param name      话题名
 * @param desc      简介，未填时为空串（不是 null：前端不必再判一次）
 * @param postCnt   话题下公开可见帖数，与 {@code TopicMapper#refreshPostCnt} 同口径
 * @param followCnt 关注数，与 {@code topic_follow} 恒等
 * @param hotScore  热度。定时重算未做（阶段 4），今天它是建表默认值 0，
 *                  原样透出是为了让「排序按热度」这条规则在实现时不必改接口
 * @param isOfficial 是否官方话题（0/1）。前端只用它决定「官方」角标
 * @param cover     封面地址，恒 null（见上面那段边界）
 * @param following 当前用户是否已关注本话题
 */
public record TopicCard(
        Long id, String name, String desc, Integer postCnt, Integer followCnt,
        BigDecimal hotScore, Integer isOfficial, String cover, boolean following) {

    /** 由实体与「当前查看者是否已关注」组装；关注态不在实体里，只能当参数传。 */
    public static TopicCard of(Topic topic, boolean following) {
        String desc = topic.getDescTxt();
        return new TopicCard(topic.getId(), topic.getName(), desc == null ? "" : desc,
                topic.getPostCnt(), topic.getFollowCnt(), topic.getHotScore(),
                topic.getIsOfficial(), topic.getCover(), following);
    }
}
