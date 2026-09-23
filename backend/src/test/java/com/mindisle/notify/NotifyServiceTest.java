package com.mindisle.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.entity.NotifyMessage;
import com.mindisle.notify.dto.MarkReadRequest;
import com.mindisle.notify.dto.MarkReadView;
import com.mindisle.notify.dto.NotifyItem;
import com.mindisle.notify.dto.NotifyPage;

/**
 * 站内通知单测（任务 T3.11-b · 需求 FR9.1、FR9.2 · 手册 §6.1 行 3.11）。
 *
 * <p><b>本类钉的是通知自己的规则</b>：文案与列宽、幂等查重、摘录、游标分页、已读批量的入参清洗。
 * 三个业务服务「什么条件下该发通知」不在这里测——那是调用方的事，已分别写进
 * {@code PostInteractionServiceTest} / {@code CommentServiceTest} / {@code RelationshipServiceTest}
 * 的 T3.11-b 小节，用 {@link RecordingNotifyService} 照出来。</p>
 *
 * <p><b>fake 刻意复刻真库的两条语义</b>：查重只看未读行、id 按插入顺序自增。
 * 至于 {@code idx_user_read} 走不走、{@code <script>} 拼出的 IN 列表在 MySQL 9 上合不合法，
 * 那是接线而不是规则，由 docs/smoke.mjs 第 18 步与 root 直连取证负责（口径同 T3.11）。</p>
 */
class NotifyServiceTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 9, 21, 10, 0);
    private static final long ME = 100L;
    private static final long ACTOR = 200L;
    private static final long POST_ID = 300L;

    private FakeStore store;
    private List<NotifyMessage> pushed;
    private NotifyService service;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        pushed = new ArrayList<>();
        service = new NotifyService(store, pushed::add);
    }

    /** 期望恰好落一行；断言完把表清掉，方便同一个用例里连着看第二次动作。 */
    private NotifyMessage only() {
        assertThat(store.rows).as("期望恰好写入一行").hasSize(1);
        return store.rows.get(0);
    }

    // ------------------------------------------------------------------ 写：文案

    @Test
    @DisplayName("点赞通知：主文案「谁 赞了你的帖子」，辅助行是被引号包住的帖标题，ref 指向帖子")
    void likeNotificationShape() {
        service.notifyLike(ME, "小屿", POST_ID, "求助：今晚睡不着");
        NotifyMessage row = only();
        assertThat(row.getUserId()).isEqualTo(ME);
        assertThat(row.getType()).isEqualTo(NotifyMessage.TYPE_LIKE);
        assertThat(row.getTitle()).isEqualTo("小屿 赞了你的帖子");
        assertThat(row.getContent()).isEqualTo("「求助：今晚睡不着」");
        assertThat(row.getRefType()).isEqualTo(NotifyMessage.REF_POST);
        assertThat(row.getRefId()).isEqualTo(POST_ID);
        assertThat(row.getIsRead()).as("未读位必须显式置 0：红点是 COUNT(is_read=0)").isEqualTo(0);
        assertThat(row.getDeleted()).isEqualTo(0);
        assertThat(row.getReadAt()).isNull();
        assertThat(pushed).hasSize(1);
    }

    @Test
    @DisplayName("帖标题为空时辅助行不留 null：content 列 NOT NULL，落库用主文案兜底")
    void blankPostTitleFallsBackToTitleAsContent() {
        service.notifyLike(ME, "小屿", POST_ID, "   ");
        assertThat(only().getContent()).isEqualTo("小屿 赞了你的帖子");
    }

    @Test
    @DisplayName("评论与回复同属 comment 类型但文案不同：前端按 type 分组、按 title 认事")
    void commentAndReplyShareType() {
        service.notifyComment(ME, "小屿", POST_ID, "抱抱楼主");
        service.notifyReply(ME, "阿屿", POST_ID, "同意");
        assertThat(store.rows).extracting(NotifyMessage::getType)
                .containsExactly(NotifyMessage.TYPE_COMMENT, NotifyMessage.TYPE_COMMENT);
        assertThat(store.rows).extracting(NotifyMessage::getTitle)
                .containsExactly("小屿 评论了你的帖子", "阿屿 回复了你的评论");
        assertThat(store.rows).allSatisfy(row -> assertThat(row.getRefType()).isEqualTo(NotifyMessage.REF_POST));
        assertThat(store.rows.get(1).getRefId())
                .as("回复通知跳转目标是帖子而不是评论：楼中楼要点开才有上下文").isEqualTo(POST_ID);
    }

    @Test
    @DisplayName("关注通知：ref 指向发起人的 user id（关注关系本身不匿名，FR4.6）")
    void followNotificationPointsAtActorProfile() {
        service.notifyFollow(ME, "小屿", ACTOR);
        NotifyMessage row = only();
        assertThat(row.getType()).isEqualTo(NotifyMessage.TYPE_FOLLOW);
        assertThat(row.getTitle()).isEqualTo("小屿 关注了你");
        assertThat(row.getContent()).isEqualTo("去他的主页看看");
        assertThat(row.getRefType()).isEqualTo(NotifyMessage.REF_USER);
        assertThat(row.getRefId()).isEqualTo(ACTOR);
    }

    @Test
    @DisplayName("收件人 id 非正数就一行都不写：那是一条永远读不到的通知")
    void nonPositiveRecipientWritesNothing() {
        service.notifyLike(0L, "小屿", POST_ID, "标题");
        service.notifyFollow(-5L, "小屿", ACTOR);
        assertThat(store.rows).isEmpty();
        assertThat(pushed).as("没落库就不该触发推送口").isEmpty();
    }

    // ------------------------------------------------------------------ 写：列宽、摘录、幂等

    @Test
    @DisplayName("评论摘录：60 字以内原样，超了才截断并补省略号——省略号不能是恒有的尾巴")
    void excerptAddsEllipsisOnlyWhenClipped() {
        service.notifyComment(ME, "小屿", POST_ID, "短");
        assertThat(only().getContent()).isEqualTo("「短」");
        store.rows.clear();
        service.notifyReply(ME, "小屿", POST_ID, "话".repeat(61));
        String content = only().getContent();
        assertThat(content).isEqualTo("「" + "话".repeat(60) + "…」");
        assertThat(content.codePointCount(0, content.length())).isEqualTo(63);
    }

    @Test
    @DisplayName("截断按码点不按 char：emoji 是一个码点两个 char，按 char 切会切出半个")
    void truncationCountsCodePoints() {
        service.notifyComment(ME, "小屿", POST_ID, "😀".repeat(70));
        String content = only().getContent();
        assertThat(content.codePointCount(0, content.length())).isEqualTo(63);
        assertThat(content).containsOnlyOnce("…");
        assertThat(content.split("😀", -1).length - 1).as("60 个完整 emoji，一个都没被切成半个").isEqualTo(60);
        assertThat(content.length()).as("UTF-16 长度比码点多 60：按 char 截就会在这里出错").isEqualTo(123);
    }

    @Test
    @DisplayName("title 与 content 都不得超过列宽（VARCHAR(100)/VARCHAR(500)），否则 strict 模式直接报错")
    void columnWidthsAreNeverExceeded() {
        String longName = "名".repeat(140);
        service.notifyLike(ME, longName, POST_ID, "标".repeat(600));
        NotifyMessage row = only();
        assertThat(row.getTitle().codePointCount(0, row.getTitle().length())).isEqualTo(100);
        assertThat(row.getContent().codePointCount(0, row.getContent().length())).isEqualTo(500);
    }

    @Test
    @DisplayName("同一个人重复赞同一帖：未读的同文案只留一条（查重比到 title+content 全等）")
    void sameUnreadTextIsNotWrittenTwice() {
        service.notifyLike(ME, "小屿", POST_ID, "同一帖");
        service.notifyLike(ME, "小屿", POST_ID, "同一帖");
        assertThat(store.rows).hasSize(1);
        assertThat(store.duplicateChecks).as("两次写都各自查过一次库：判据在 SQL 侧，不靠内存记数").isEqualTo(2);
        assertThat(pushed).as("跳过的这次不该触发推送口：红点会凭空多一格").hasSize(1);
    }

    @Test
    @DisplayName("两个人赞同一帖要各留一条：这就是不能给 (user_id,type,ref) 建唯一索引的原因")
    void differentActorsBothKeepTheirNotification() {
        service.notifyLike(ME, "小屿", POST_ID, "同一帖");
        service.notifyLike(ME, "阿屿", POST_ID, "同一帖");
        assertThat(store.rows).hasSize(2);
    }

    @Test
    @DisplayName("那条已经读过了就该重新提醒：查重只看未读行")
    void readDuplicateDoesNotBlockANewReminder() {
        service.notifyLike(ME, "小屿", POST_ID, "同一帖");
        store.rows.get(0).setIsRead(1);
        service.notifyLike(ME, "小屿", POST_ID, "同一帖");
        assertThat(store.rows).hasSize(2);
    }

    @Test
    @DisplayName("标题为空导致 content 兜底时不做查重：没有可比的辅助行，宁可多一条也不能吞掉通知")
    void nullContentSkipsTheDuplicateCheck() {
        service.notifyLike(ME, "小屿", POST_ID, null);
        service.notifyLike(ME, "小屿", POST_ID, null);
        assertThat(store.duplicateChecks).isZero();
        assertThat(store.rows).hasSize(2);
    }

    // ------------------------------------------------------------------ 读：列表分页

    @Test
    @DisplayName("列表按 id 倒序、多取一条判 hasMore、nextCursor 是本页最旧那条")
    void listPagesByCursorDescending() {
        seed(3);
        NotifyPage page = service.list(ME, null, 2);
        assertThat(page.list()).extracting(NotifyItem::id).containsExactly(3L, 2L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo(2L);
        assertThat(page.unreadCount()).as("红点每页都带，前端只有一处数字来源").isEqualTo(3L);

        NotifyPage second = service.list(ME, page.nextCursor(), 2);
        assertThat(second.list()).extracting(NotifyItem::id).containsExactly(1L);
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    @DisplayName("空列表：nextCursor 为 null 而不是 0，前端据此停止继续拉页")
    void emptyListHasNoCursor() {
        NotifyPage page = service.list(ME, null, null);
        assertThat(page.list()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.unreadCount()).isZero();
    }

    @Test
    @DisplayName("size 归一：缺省 20、非法值回到默认、超上限按 50 截，都不报错")
    void pageSizeIsNormalizedNotRejected() {
        assertThat(queryLimit(null)).isEqualTo(21);
        assertThat(queryLimit(0)).isEqualTo(21);
        assertThat(queryLimit(-3)).isEqualTo(21);
        assertThat(queryLimit(999)).isEqualTo(51);
        assertThat(queryLimit(5)).isEqualTo(6);
    }

    @Test
    @DisplayName("列表出参：read 由 is_read 映射、typeLabel 给中文、不透 deleted")
    void listItemShape() {
        seed(1);
        NotifyItem item = service.list(ME, null, null).list().get(0);
        assertThat(item.read()).isFalse();
        assertThat(item.typeLabel()).isEqualTo("赞");
        assertThat(item.id()).isEqualTo(1L);
        assertThat(item.title()).isEqualTo("小屿 赞了你的帖子");
    }

    // ------------------------------------------------------------------ 读：标记已读

    @Test
    @DisplayName("按 id 批量已读：清洗重复与非正数、保序，回执带剩余未读")
    void markReadCleansIdsAndReportsRemaining() {
        seed(3);
        MarkReadView view = service.markRead(ME,
                new MarkReadRequest(new ArrayList<>(Arrays.asList(1L, 1L, 0L, null, 2L)), null), DAY);
        assertThat(store.lastMarkedIds).as("去重去非正数之后保序传给端口").containsExactly(1L, 2L);
        assertThat(view.updated()).isEqualTo(2);
        assertThat(view.unreadCount()).isEqualTo(1);
        assertThat(view.all()).isFalse();
        assertThat(store.rows.get(2).getReadAt()).as("第 3 条没被点，read_at 必须还是 null").isNull();
    }

    @Test
    @DisplayName("重复标记已读回 updated=0 而不是报错：连点两下「全部已读」不是错误")
    void repeatedMarkReadIsIdempotent() {
        seed(2);
        service.markRead(ME, new MarkReadRequest(null, true), DAY);
        MarkReadView again = service.markRead(ME, new MarkReadRequest(null, true), DAY);
        assertThat(again.updated()).isZero();
        assertThat(again.unreadCount()).isZero();
        assertThat(store.rows).allSatisfy(row -> assertThat(row.getReadAt()).isEqualTo(DAY));
    }

    @Test
    @DisplayName("all=true 时忽略 ids：两种语义并存时以「全部」为准，别让前端猜优先级")
    void markAllIgnoresIds() {
        seed(2);
        MarkReadView view = service.markRead(ME, new MarkReadRequest(List.of(1L), true), DAY);
        assertThat(view.all()).isTrue();
        assertThat(view.updated()).isEqualTo(2);
    }

    @Test
    @DisplayName("ids 与 all 都不给 → 10001：静默当成全部已读是最坏的一种宽容")
    void emptyMarkReadRequestIsRejected() {
        BizException error = assertThrows(BizException.class,
                () -> service.markRead(ME, new MarkReadRequest(List.of(), null), DAY));
        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(error.getMessage()).isEqualTo("要标记已读的通知 id 不能为空");
        assertThrows(BizException.class, () -> service.markRead(ME, null, DAY));
    }

    @Test
    @DisplayName("一次点掉超过 100 条 → 10001 且文案报出实际条数：那是攻击不是操作")
    void oversizedMarkReadBatchIsRejected() {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            ids.add(i);
        }
        BizException error = assertThrows(BizException.class,
                () -> service.markRead(ME, new MarkReadRequest(ids, null), DAY));
        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(error.getMessage()).contains("一次最多标记 100 条").contains("101");
    }

    // ------------------------------------------------------------------ 纯函数

    @Test
    @DisplayName("type 标签：八类都有中文，认不出来的回原码——ENUM 将来加值不至于让列表 500")
    void typeLabelsFallBackToRawCode() {
        assertThat(NotifyService.typeLabel(NotifyMessage.TYPE_CRISIS)).isEqualTo("危机关怀");
        assertThat(NotifyService.typeLabel("pm")).isEqualTo("私信");
        assertThat(NotifyService.typeLabel("future_type")).isEqualTo("future_type");
        assertThat(NotifyService.typeLabel(null)).isNull();
    }

    @Test
    @DisplayName("quote：空白回 null（交给落库兜底），有值才加引号并去掉首尾空白")
    void quoteTreatsBlankAsNull() {
        assertThat(NotifyService.quote("  标题  ")).isEqualTo("「标题」");
        assertThat(NotifyService.quote("  ")).isNull();
        assertThat(NotifyService.quote(null)).isNull();
    }

    // ------------------------------------------------------------------ 造数据

    /** 造 n 条点赞通知：文案各不相同，因此不会被查重吞掉。 */
    private void seed(int n) {
        for (int i = 0; i < n; i++) {
            service.notifyLike(ME, "小屿", POST_ID, "帖标题" + (i + 1));
        }
    }

    private int queryLimit(Integer size) {
        store.lastLimit = 0;
        service.list(ME, null, size);
        return store.lastLimit;
    }

    // ------------------------------------------------------------------ 内存 fake

    /**
     * NotifyStore 的内存实现。两条真库语义必须复刻：<b>查重只看未读行</b>、
     * <b>id 按插入顺序自增</b>（列表倒序断言依赖它）。
     */
    private final class FakeStore implements NotifyService.NotifyStore {

        private final List<NotifyMessage> rows = new ArrayList<>();
        private int duplicateChecks;
        private int lastLimit;
        private List<Long> lastMarkedIds;

        @Override
        public boolean existsUnreadDuplicate(long userId, String type, String refType, long refId,
                                             String title, String content) {
            duplicateChecks++;
            return rows.stream().anyMatch(row -> row.getUserId() == userId
                    && Objects.equals(type, row.getType())
                    && Objects.equals(refType, row.getRefType())
                    && Objects.equals(title, row.getTitle())
                    && Objects.equals(content, row.getContent())
                    && row.getRefId() != null && row.getRefId() == refId
                    && row.getIsRead() != null && row.getIsRead() == 0);
        }

        @Override
        public void insert(NotifyMessage row) {
            row.setId((long) rows.size() + 1L);
            row.setCreatedAt(DAY);
            rows.add(row);
        }

        @Override
        public long countUnread(long userId) {
            return rows.stream().filter(row -> row.getUserId() == userId
                    && row.getIsRead() != null && row.getIsRead() == 0).count();
        }

        @Override
        public List<NotifyMessage> page(long userId, Long beforeId, int limit) {
            lastLimit = limit;
            return rows.stream()
                    .filter(row -> row.getUserId() == userId)
                    .filter(row -> beforeId == null || row.getId() < beforeId)
                    .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                    .limit(limit)
                    .collect(Collectors.toList());
        }

        @Override
        public int markRead(long userId, List<Long> ids, LocalDateTime now) {
            lastMarkedIds = new ArrayList<>(ids);
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
