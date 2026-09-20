package com.mindisle.post.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 发帖入参（任务 3.3 · 需求 FR4.1、FR4.2 · 手册 §6.1 行 3.3）。
 *
 * <p><b>长度为什么不在这里用 {@code @Size(max=50)} 钉死</b>：校验注解的值必须是编译期常量，
 * 而 FR4.1 的 50/5000 属于「运营可调参数」，按 NFR10 只能放配置
 * （{@code mindisle.post.max-title-chars} / {@code max-content-chars}）。
 * 所以这里只留一道<b>防大body攻击</b>的宽松硬上限（与预检接口的 20000 同口径），
 * 真实业务上限在 {@code PostService} 里判——两处判错的方向都是「拦得更严」，不会放过。
 * 这是刻意的分层：注解挡协议层的脏，服务层挡业务层的规。</p>
 *
 * @param title            标题，必填
 * @param content          正文，纯文本 + 换行 + emoji（FR4.1 明确不支持 Markdown 语法）
 * @param type             normal / hole / help；null 与空串按 normal（FR4.2 三种形式）
 * @param visibility       public / private；null 走配置默认。DDL 里的 friends 属 FR4.6 好友可见，
 *                         本期不开放，传进来直接判参数错，而不是静默改成 public
 * @param anonymous        是否匿名（FR1.4）；{@code type=hole} 时无条件为真，不以此字段为准
 * @param topicIds         话题 id 列表，最多 {@code mindisle.post.max-topics} 个，必须已过审（FR4.5）
 * @param images           配图 URL 列表，取值必须是 {@code POST /api/files/image} 返回的原值（任务 3.1）；
 *                         服务端按 URL 回查磁盘，拿不到文件即拒绝，不接受客户端自己拼路径与字节数
 * @param autoDestroyHours 树洞到期销毁小时数：null=用默认 168（FR4.2「默认 7 天后」），
 *                         0=作者主动选择「不销毁」（需求 §5.1 规则 3「作者可选择」），
 *                         其余值必须命中 {@code mindisle.post.hole-destroy-options}
 */
public record CreatePostRequest(

    @NotBlank(message = "标题要写点什么才好")
    @Size(max = 200, message = "标题过长")
    String title,

    @NotBlank(message = "正文不能为空")
    @Size(max = 20000, message = "正文过长，请精简后再发")
    String content,

    @Pattern(regexp = "^(|normal|hole|help)$", message = "内容形式不合法，请重新选择")
    String type,

    @Pattern(regexp = "^(|public|private)$", message = "可见范围只能选公开或仅自己")
    String visibility,

    Boolean anonymous,

    @Size(max = 20, message = "一次最多关联 20 个话题")
    List<Long> topicIds,

    @Size(max = 20, message = "一次最多挂 20 张配图")
    List<String> images,

    Integer autoDestroyHours
) {}
