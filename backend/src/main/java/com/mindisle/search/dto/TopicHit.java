package com.mindisle.search.dto;

import java.math.BigDecimal;

import com.mindisle.entity.Topic;

/**
 * 话题搜索结果（任务 3.9 · 需求 FR4.8「关键词搜话题」）。
 *
 * <p>字段口径与 {@code FeedController.TopicBrief} 一致：不透出 cover（对象存储域名）、
 * deleted（存储细节）、auditStatus（运营信息，且这里恒为 APPROVED，回它没有信息量只多一个可枚举面）。
 * 单独建一个类型而不是复用那个嵌套 record，是因为它属于搜索域而不是话题墙，
 * 两处复用会把「话题墙只给官方话题」这条判据间接带进搜索结果。</p>
 */
public record TopicHit(
        Long id, String name, String desc, Integer postCnt, Integer followCnt,
        BigDecimal hotScore, Integer isOfficial) {

    public static TopicHit of(Topic topic) {
        return new TopicHit(topic.getId(), topic.getName(), topic.getDescTxt(),
                topic.getPostCnt(), topic.getFollowCnt(), topic.getHotScore(), topic.getIsOfficial());
    }
}