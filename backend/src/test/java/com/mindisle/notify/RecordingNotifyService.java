package com.mindisle.notify;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.mindisle.entity.NotifyMessage;

/**
 * 记录型通知替身（任务 T3.11-b · 给点赞 / 评论 / 关注三个业务服务的单测用）。
 *
 * <p><b>为什么要有这么一个共享替身</b>：三个服务里新加的「发通知」判据（真的变化了才发、
 * 不自发自评、收藏不发、待审不发、匿名不漏真名）是本轮真正的风险点，它们必须在<b>调用方</b>
 * 那一层被测到——只在 {@code NotifyServiceTest} 里测通知自己，等于只验了信封没验寄件人。</p>
 *
 * <p><b>这个替身刻意不做幂等查重</b>（{@code existsUnreadDuplicate} 恒回 false）：
 * 「同一个人重复点赞同一条文案只留一条」这条规则归 {@code NotifyServiceTest} 管，
 * 在这里如果一并生效，调用方的测试就会因为「第二次点赞被查重吞掉」而看不出 changed 判据错没错。
 * 一次动作出一条，才是本类要照出来的镜子。</p>
 */
public final class RecordingNotifyService {

    private final List<NotifyMessage> rows = new ArrayList<>();
    private final List<NotifyMessage> pushed = new ArrayList<>();
    private final NotifyService service;

    public RecordingNotifyService() {
        this(NotifyService.PreferenceGate.ALLOW_ALL);
    }

    /**
     * 带偏好闸门的构造（任务 T3.16 后半）。
     *
     * <p>默认那个走 {@code ALLOW_ALL}，于是既有五个消费者（点赞 / 评论 / 关注 / 私信 / 关系）
     * 的断言一字未改就照旧成立 —— 「加偏好」对它们应当是不可见的。</p>
     */
    public RecordingNotifyService(NotifyService.PreferenceGate gate) {
        this.service = new NotifyService(new FakeStore(), pushed::add, gate);
    }

    /** 交给被测服务的那个实例。 */
    public NotifyService service() {
        return service;
    }

    /** 落库顺序（自增 id 由本类按插入顺序补，和真库行为一致）。 */
    public List<NotifyMessage> rows() {
        return rows;
    }

    public int size() {
        return rows.size();
    }

    /** 触发了几次推送口（阶段 5 换成 WebSocket 时，这条断言就是「不会漏推」的回归线）。 */
    public int pushCount() {
        return pushed.size();
    }

    public List<NotifyMessage> ofType(String type) {
        return rows.stream().filter(row -> type.equals(row.getType())).collect(Collectors.toList());
    }

    /** 断言失败时的可读上下文：把每行写成 type|user|title 拼成一段。 */
    public String dump() {
        return rows.stream().map(row -> row.getType() + "|" + row.getUserId() + "|" + row.getTitle())
                .collect(Collectors.joining(" ;; "));
    }

    public void clear() {
        rows.clear();
        pushed.clear();
    }

    /** NotifyStore 的内存实现：只实现本类要用到的四件事，语义与真库端口逐条对齐。 */
    private final class FakeStore implements NotifyService.NotifyStore {

        @Override
        public boolean existsUnreadDuplicate(long userId, String type, String refType, long refId,
                                            String title, String content) {
            return false;
        }

        @Override
        public void insert(NotifyMessage row) {
            row.setId((long) rows.size() + 1L);
            row.setCreatedAt(LocalDateTime.now());
            rows.add(row);
        }

        @Override
        public long countUnread(long userId) {
            return rows.stream().filter(row -> row.getUserId() == userId
                    && row.getIsRead() != null && row.getIsRead() == 0).count();
        }

        @Override
        public List<NotifyMessage> page(long userId, Long beforeId, int limit) {
            List<NotifyMessage> mine = rows.stream()
                    .filter(row -> row.getUserId() == userId)
                    .filter(row -> beforeId == null || row.getId() < beforeId)
                    .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                    .collect(Collectors.toList());
            return mine.size() > limit ? new ArrayList<>(mine.subList(0, limit)) : mine;
        }

        @Override
        public int markRead(long userId, List<Long> ids, LocalDateTime now) {
            int updated = 0;
            for (NotifyMessage row : rows) {
                if (row.getUserId() == userId && row.getIsRead() == 0 && ids.contains(row.getId())) {
                    row.setIsRead(1);
                    row.setReadAt(now);
                    updated++;
                }
            }
            return updated;
        }

        @Override
        public int markAllRead(long userId, LocalDateTime now) {
            int updated = 0;
            for (NotifyMessage row : rows) {
                if (row.getUserId() == userId && row.getIsRead() == 0) {
                    row.setIsRead(1);
                    row.setReadAt(now);
                    updated++;
                }
            }
            return updated;
        }
    }
}
