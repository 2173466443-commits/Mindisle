package com.mindisle.post.dto;

/**
 * 发出一条评论之后的回执（任务 3.7 · 需求 FR4.4、FR7.3、FR10.3）。
 *
 * <p>与发帖回执同一个形状：评论本身 + 求助热线 + 人话提示。恒返 HTTP 200，
 * 「被机审拦下」与「转人工」都是<b>成功执行了一次业务判断</b>，
 * 用户要的是原因和下一步，不是一个和「你没登录」同类的 4xx（判据见 {@link PostView} 类注释）。</p>
 *
 * @param comment  落库后的评论（status 已是机审终态）
 * @param hotline  非空即「前端必须显示求助卡片」，与预检与发帖接口同一契约
 * @param tip      给用户看的一句话解释，可为 null
 */
public record CommentCreateView(CommentItem comment, String hotline, String tip) {
}
