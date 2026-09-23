package com.mindisle.topic.dto;

/**
 * 创建话题的回执（任务 3.8 · 手册 §6.1 行 3.8「创建需 audit_status=待审（防刷）」）。
 *
 * <p><b>为什么要单独一个「能不能立刻用」的布尔而不是让用户读 {@code auditStatus}</b>：
 * 前端在创建成功后要做的事只有一件 —— 决定是「跳进话题页」还是「留在原地显示一句话」。
 * 把 ENUM 原文交给它去比较，等于把 {@code PENDING/APPROVED/REJECTED} 这套内部口径
 * 变成前后端的隐式契约（多一处第二份真相，手册 §5.5 禁止的就是这个）。
 * {@code auditStatus} 仍然回：它是给用户看的那句状态名要用的原始信息，也是管理端 T6.1 的入口参数。</p>
 *
 * <p>{@code tip} 是人话文案，与需求 §6.6「错误信息要能直接展示给用户」同源：
 * 待审那条尤其重要 —— 不解释清楚，用户看到「话题页 409」只会以为创建失败、然后反复再点。</p>
 *
 * @param id          新话题 id
 * @param name        归一化之后、真正落库的话题名（不是用户原样输入：首尾空白与连续空格已被折叠）
 * @param desc        归一化之后的简介
 * @param auditStatus PENDING / APPROVED，与 topic.audit_status 的 ENUM 逐字一致
 * @param usable      是否现在就能进话题页、能被挂帖、能被关注（等价于 {@code auditStatus=APPROVED}）
 * @param tip         给人看的那句话
 * @param postCnt     恒为 0：新话题不可能有帖。回它是为了让前端复用同一个卡片渲染函数，
 *                    不必为「刚创建的」和「列表里拿到的」写两套
 * @param followCnt   恒为 0：创建者<b>不</b>自动关注自己的话题（那会让关注数从一开始就说谎，
 *                    见 {@code TopicService#create} 的注释）
 */
public record TopicCreateView(
        Long id, String name, String desc, String auditStatus,
        boolean usable, String tip, Integer postCnt, Integer followCnt) {
}
