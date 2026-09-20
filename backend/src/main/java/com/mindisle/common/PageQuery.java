package com.mindisle.common;

import lombok.Data;

/**
 * 统一分页入参（手册 §5.5）：page/size 页码分页与 beforeId 游标分页两套并存。
 *
 * <p>信息流必须用游标（§6.1 任务 3.5）：页码分页在插入新帖时会跳页重复；
 * 后台列表用页码，因为管理员要看总数。两套参数同时携带时以游标优先。</p>
 */
@Data
public class PageQuery {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    private Integer page = 1;
    private Integer size = DEFAULT_SIZE;
    /** 游标：只返回 id 严格小于该值的数据，对应 SQL 的 beforeId 条件。 */
    private Long beforeId;

    /** 归一化：把非法输入收敛到安全区间，而不是直接报错，前端体验更稳。 */
    public PageQuery normalize() {
        if (page == null || page < 1) {
            page = 1;
        }
        if (size == null || size < 1) {
            size = DEFAULT_SIZE;
        }
        if (size > MAX_SIZE) {
            size = MAX_SIZE;
        }
        if (beforeId != null && beforeId <= 0) {
            beforeId = null;
        }
        return this;
    }

    /** 供 SQL 手工分页使用：offset = (page - 1) * size。 */
    public long offset() {
        normalize();
        return (long) (page - 1) * size;
    }

    public boolean useCursor() {
        return beforeId != null;
    }
}
