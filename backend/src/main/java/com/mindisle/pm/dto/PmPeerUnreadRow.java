package com.mindisle.pm.dto;

import lombok.Data;

/**
 * 「某一个发件人还有几条未读」的聚合行（任务 T5.4 · 需求 FR6.2 会话列表右侧那个数字）。
 *
 * <p><b>用 @Data 类而不是 record</b>：MyBatis 往 record 里注入要靠构造器参数名，
 * 而 {@code -parameters} 在 pom 里没打开（同名理由逐字写在
 * {@link com.mindisle.emotion.dto.EmotionGroupRow} 上，这里不重复第二遍论证）。
 * 这是全仓第二个「SQL 投影行」，形状必须与第一个一致，否则后来人要先猜哪一套是对的。</p>
 *
 * <p>{@code unreadCnt} 用包装类型 {@code Integer}：GROUP BY 的结果里没有这个人时，
 * 那一行<b>根本不存在</b>，不是「存在一条 0」。补零是服务层的职责（见
 * {@link com.mindisle.pm.PmStoreAdapter#unreadByPeers}），类型上留一个 null 就是把这件事写白。</p>
 */
@Data
public class PmPeerUnreadRow {

  /** 发件人 id，即会话的另一端。 */
  private Long peerId;

  /** 未读条数；缺行时本对象不会出现，由调用方补 0。 */
  private Integer unreadCnt;
}
