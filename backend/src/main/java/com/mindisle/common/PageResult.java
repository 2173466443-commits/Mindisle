package com.mindisle.common;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 统一分页出参（手册 §5.5）。同时给出 total（后台表格要显示条数）与
 * hasMore/nextCursor（信息流无限滚动只关心这个）。
 */
public class PageResult<T> {

    private List<T> list = new ArrayList<>();
    private long total;
    private int page;
    private int size;
    private boolean hasMore;
    /** 下一页游标，等于本页最后一条记录的 id。 */
    private Long nextCursor;

    public static <T> PageResult<T> of(List<T> list, long total, PageQuery query) {
        PageResult<T> result = new PageResult<>();
        result.list = list == null ? new ArrayList<>() : list;
        result.total = total;
        result.page = query.normalize().getPage();
        result.size = query.getSize();
        result.hasMore = (long) result.page * result.size < total;
        return result;
    }

    /** 游标分页：调用方多查一条，这里判断 hasMore 并裁掉多余的那条。 */
    public static <T> PageResult<T> ofCursor(List<T> list, int requestedSize, Function<T, Long> idGetter) {
        PageResult<T> result = new PageResult<>();
        List<T> safe = list == null ? new ArrayList<>() : new ArrayList<>(list);
        boolean more = safe.size() > requestedSize;
        if (more) {
            safe.remove(safe.size() - 1);
        }
        result.list = safe;
        result.size = requestedSize;
        result.page = 1;
        result.total = -1;
        result.hasMore = more;
        if (!safe.isEmpty()) {
            result.nextCursor = idGetter.apply(safe.get(safe.size() - 1));
        }
        return result;
    }

    public static <T> PageResult<T> empty() {
        return new PageResult<>();
    }

    public List<T> getList() {
        return list;
    }

    public void setList(List<T> list) {
        this.list = list;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }

    public Long getNextCursor() {
        return nextCursor;
    }

    public void setNextCursor(Long nextCursor) {
        this.nextCursor = nextCursor;
    }
}
