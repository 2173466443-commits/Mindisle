package com.mindisle.audit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 内容预检入参（手册 §6.2 U5「敏感词实时提醒」）。
 *
 * @param text  待检文本。上限取得比正文业务上限更宽，是为了让前端把整段草稿一次发来、
 *              不必自己截断；「草稿到底能写多长」这条规则属于发帖校验（任务 3.3），不在这里重复定义
 * @param scene post 发帖 / comment 评论 / im 私信 / ai 模型输出，仅用于提示文案分组，
 *              当前实现只据它决定过 user 侧还是 ai 侧
 */
public record PrecheckRequest(

    @NotBlank(message = "没有可检测的内容")
    @Size(max = 20000, message = "单次预检内容不能超过 20000 字，请分段")
    String text,

    @Size(max = 16)
    String scene
) {}
