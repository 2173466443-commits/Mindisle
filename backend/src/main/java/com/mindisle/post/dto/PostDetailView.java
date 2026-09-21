package com.mindisle.post.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子详情出参（任务 3.5 · 手册 §6.1 行 3.5 · 需求 FR4.3）。
 *
 * <p>与 {@link PostListItem} 的差集只有三样：<b>content 全文、visibility、createdAt</b>。
 * 摘要与全文刻意分两个 DTO，是因为列表是「一次二十条」的批量出参，
 * 把 MEDIUMTEXT 正文带进列表会让每页多吐几十 KB 而用户根本看不到。
 * 两者都不含 risk_level / quality_score / report_cnt，理由见 {@link PostListItem} 的类注释。</p>
 *
 * <p><b>能拿到本对象即代表「这个人有权看」</b>：不可见的帖与不存在的帖在服务端
 * 合并成同一个 404/30001（见 PostQueryService#detail），所以这里不需要再看权限字段。</p>
 *
 * @param id            帖子 id
 * @param type          normal / hole / help
 * @param title         标题
 * @param content       正文全文，纯文本 + 换行（FR4.1），转义由前端负责
 * @param visibility    public / private；作者自己看私密帖时给的是 private，别人拿不到这条响应
 * @param displayName   对外展示名
 * @param anonymous     是否匿名
 * @param authorId      作者 id，匿名帖恒为 null
 * @param topics        已关联且已过审的话题名
 * @param images        配图全量（张数上限在发帖时已按 FR4.1 卡死）
 * @param viewCnt       浏览量，含尚未回写进库的增量（详情页是唯一的计数入口，所以能给准）
 * @param likeCnt       点赞数
 * @param commentCnt    评论数
 * @param publishedAt   发布时间
 * @param autoDestroyAt 树洞到期销毁时间
 * @param createdAt     落库时间（待审帖没有 publishedAt，靠它显示「几小时前提交」）
 * @param auditTip      非空即「仅自己可见，还在审核」
 * @param hotline       非空即必须显示求助卡片
 * @param liked         当前查看者是否赞过（任务 3.6）。详情页的 viewCnt 是「含未回写增量」的准数，
 *                      但 likeCnt 直接读库列——因为点赞列在每次互动时就被重算刷平，
 *                      它<b>本来就没有</b>未回写窗口，两个数字的时效性差别是设计而非疏漏
 * @param collected     当前查看者是否收藏了这条
 * @param collectCnt    收藏人数（列表项里也有，详情页多给一次是为了互动之后不用回读列表）
 */
public record PostDetailView(
        Long id,
        String type,
        String title,
        String content,
        String visibility,
        String displayName,
        boolean anonymous,
        Long authorId,
        List<String> topics,
        List<PostView.ImageBrief> images,
        long viewCnt,
        int likeCnt,
        int commentCnt,
        LocalDateTime publishedAt,
        LocalDateTime autoDestroyAt,
        LocalDateTime createdAt,
        String auditTip,
        String hotline,
        boolean liked,
        boolean collected,
        int collectCnt) {
}
