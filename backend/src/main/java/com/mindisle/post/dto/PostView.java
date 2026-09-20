package com.mindisle.post.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发帖结果出参（任务 3.3）。
 *
 * <p><b>不回传 risk_level</b>：需求 §12 规范 5 要求「不做诊断与标签化表述」，
 * 把 L2/L3 这类危机标记透给客户端，等于给用户贴一张他自己没同意的标签，
 * 还会在浏览器历史与截图里留下敏感信息（NFR8 数据最小化）。
 * 危机信息以「热线 + 提示语」这种<b>可行动的关怀</b>形式给出，
 * 等级只留在服务端与 {@code alert_ticket} 里供辅导员看。
 * 同口径的还有 {@code UserService.ProfileView} 不透出 risk_flag。</p>
 *
 * <p><b>为什么被拒的帖也用 HTTP 200 返回</b>：状态机要把「这一条确实进来过并被机审判定」
 * 留成一行记录（BR10），而 status 字段本身就是结果。用 4xx 表达「被拦」会让
 * 前端把它和「你没登录」「你参数写错了」混成一类，而这三件事对用户的下一步动作完全不同：
 * 被拦要看得懂原因并能改后重发，协议错误只需回表单顶部。</p>
 *
 * @param id            帖子 id
 * @param status        DRAFT / MACHINE_REVIEW / HUMAN_REVIEW / PUBLISHED / REJECTED
 * @param type          normal / hole / help
 * @param title         标题原样回显（改后重发要能直接复用）
 * @param content       正文原样回显，同上；仅作者本人可见，列表与详情接口不返回未发布正文
 * @param visibility    public / private
 * @param anonymous     是否匿名（树洞恒为 true）
 * @param displayName   对外展示名：匿名时是「匿名屿民·X」，否则是昵称
 * @param topics        已关联话题名（只给过审的，口径与 FR4.5 一致）
 * @param images        配图，宽高与字节数取自服务端读盘真值
 * @param hotline       非空即「必须显示求助卡片」，与 {@code PrecheckView.hotline} 同一契约
 * @param tip           给用户看的一句话：为什么没直发 / 接下来会发生什么，不含命中词面
 * @param publishedAt   发布时间；未发布为 null（响应体 non_null 会直接省掉）
 * @param autoDestroyAt 树洞到期销毁时间；未设置为 null
 * @param createdAt     落库时间
 */
public record PostView(
        Long id,
        String status,
        String type,
        String title,
        String content,
        String visibility,
        boolean anonymous,
        String displayName,
        List<String> topics,
        List<ImageBrief> images,
        String hotline,
        String tip,
        LocalDateTime publishedAt,
        LocalDateTime autoDestroyAt,
        LocalDateTime createdAt
) {

    /** 配图出参：只给渲染需要的三样，不给磁盘绝对路径与 MD5（内部字段不外泄）。 */
    public record ImageBrief(String url, int width, int height) {
    }
}
