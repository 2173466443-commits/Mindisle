package com.mindisle.common;

/**
 * 搜索关键词入参归一（任务 3.9 · 需求 FR4.8）。
 *
 * <p><b>为什么放在 common 而不是某个域里</b>：搜帖（post 域）、搜话题与搜人（search 域）
 * 共用这一条判据，而它们互相之间不该有依赖。放进任何一方，另一方就得「跨域借一个校验函数」，
 * 那正是「同一规则两处各写一遍、过半年只改得动一处」的起点。</p>
 */
public final class Keyword {

    private Keyword() {
    }

    /**
     * 去首尾空白后判空与判长，两种都是 10001。
     *
     * <p>不做「超长就截断」的兜底：截断会悄悄改变用户要查的东西，「我明明搜了这句话」
     * 却拿到另一个词的结果，比直接报错更难解释。上限由配置给（NFR10 不写死）。</p>
     */
    public static String normalize(String raw, int maxChars) {
        String kw = raw == null ? "" : raw.trim();
        if (kw.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请输入搜索关键词");
        }
        if (kw.length() > maxChars) {
            throw new BizException(ErrorCode.PARAM_INVALID, "搜索关键词最长 " + maxChars + " 字");
        }
        return kw;
    }
}