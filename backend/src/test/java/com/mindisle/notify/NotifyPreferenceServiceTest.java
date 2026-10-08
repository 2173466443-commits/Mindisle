package com.mindisle.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.entity.NotifyMessage;
import com.mindisle.entity.NotifyPreference;
import com.mindisle.mapper.NotifyPreferenceMapper;
import com.mindisle.notify.dto.NotifyPreferenceRequest;
import com.mindisle.notify.dto.NotifyPreferenceView;

/**
 * 通知偏好单测（任务 T3.16 后半 · 需求 FR9.4 · 手册 §15 行 T3.16、§18 Gate3）。
 *
 * <p><b>本类钉的是偏好的三条规则</b>：八格目录的形状（哪些能关、置灰的那三档有没有理由句）、
 * 写侧的入参校验与「值没变就不写」，以及闸门 {@code mutes} 在热路径上的判定。
 * 最后一条放在本类末尾的那组用例里，用的是 {@link RecordingNotifyService} 那个带闸门的构造 ——
 * 因为「关掉赞之后赞不再亮红点」真正的证据不在 {@code NotifyPreferenceService} 里，
 * 而在它被 {@code NotifyService#write} 问的那一句上。只在服务内部自测开关值，
 * 等于验了设置页能存数却没验存下来的数有人读（手册 §14 里 {@code rec.weight_profile} 那个坑）。</p>
 *
 * <p><b>fake 复刻真库的两条语义</b>：没有行 = 没设置过（{@code enabledOf} 回 null，不是 0 也不是 1）、
 * 同一 (user,type) 只有一行（{@code upsert} 覆盖而不是追加）。复合主键与
 * {@code ON DUPLICATE KEY UPDATE} 在 MySQL 9 上到底合不合法是接线问题，
 * 由本轮 curl 与一条 {@code SELECT COUNT(*)} 现查负责，口径同 {@code NotifyServiceTest} 的类注释。</p>
 */
class NotifyPreferenceServiceTest {

    private static final long ME = 100L;

    private FakeMapper mapper;
    private NotifyPreferenceService service;

    @BeforeEach
    void setUp() {
        mapper = new FakeMapper();
        service = new NotifyPreferenceService(mapper);
    }

    private NotifyPreferenceRequest.Toggle toggle(String type, Boolean enabled) {
        return new NotifyPreferenceRequest.Toggle(type, enabled);
    }

    private NotifyPreferenceRequest req(NotifyPreferenceRequest.Toggle... toggles) {
        return new NotifyPreferenceRequest(Arrays.asList(toggles));
    }

    private NotifyPreferenceView byType(List<NotifyPreferenceView> items, String type) {
        return items.stream().filter(i -> i.type().equals(type)).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ 读：目录形状

    @Test
    @DisplayName("八格全给：谁都没设置过时全部 enabled=true，顺序即界面顺序")
    void snapshotGivesAllEightKindsWhenNothingSaved() {
        List<NotifyPreferenceView> items = service.snapshot(ME);
        assertThat(items).as("目录必须与 NotifyMessage 的八个类型码同数量，少一格界面就少画一行")
                .hasSize(8);
        assertThat(items).extracting(NotifyPreferenceView::type)
                .containsExactlyElementsOf(NotifyPreferenceService.types());
        assertThat(items).allSatisfy(item -> {
            assertThat(item.enabled()).as("%s 没设置过时默认接收", item.type()).isTrue();
            assertThat(item.label()).as("%s 必须有中文标签，前端不再维护第二份映射", item.type())
                    .isNotBlank();
        });
    }

    @Test
    @DisplayName("置灰的恰好是 system / audit / crisis 三档，且每一档都带得出理由")
    void onlySystemAuditCrisisAreLocked() {
        List<NotifyPreferenceView> items = service.snapshot(ME);
        List<String> locked = items.stream().filter(NotifyPreferenceView::locked)
                .map(NotifyPreferenceView::type).collect(Collectors.toList());
        assertThat(locked).containsExactlyInAnyOrder(NotifyMessage.TYPE_SYSTEM,
                NotifyMessage.TYPE_AUDIT, NotifyMessage.TYPE_CRISIS);
        assertThat(items).filteredOn(NotifyPreferenceView::locked).allSatisfy(item ->
                assertThat(item.lockReason()).as("%s 置灰必须说得出理由", item.type()).isNotBlank());
        assertThat(items).filteredOn(item -> !item.locked()).allSatisfy(item -> {
            // 只有 pm 与 report 两格带了说明句（「关掉会怎样」），like / comment / follow 三格没带：
            // 它们的语义在标签上就读得出来，多写一句只会变成界面里没人看的灰字。
            if (item.lockReason() != null) {
                assertThat(item.lockReason()).as("%s 的说明句要么没有，要么说得出东西", item.type())
                        .isNotBlank();
            }
        });
    }

    @Test
    @DisplayName("库里只改过一格时，其余七格仍然是 enabled=true（缺行是「没设置过」，不是「关掉了」）")
    void missingRowMeansReceiving() {
        mapper.rows.put("like", 0);
        List<NotifyPreferenceView> items = service.snapshot(ME);
        assertThat(byType(items, NotifyMessage.TYPE_LIKE).enabled()).isFalse();
        assertThat(items).filteredOn(i -> !i.type().equals(NotifyMessage.TYPE_LIKE))
                .allSatisfy(i -> assertThat(i.enabled()).isTrue());
    }

    @Test
    @DisplayName("类型码目录与 NotifyMessage 的八个常量逐一对账：将来加一类而忘了登记，这里先红")
    void catalogMatchesEntityConstants() {
        assertThat(NotifyPreferenceService.types()).containsExactlyInAnyOrder(
                NotifyMessage.TYPE_LIKE, NotifyMessage.TYPE_COMMENT, NotifyMessage.TYPE_FOLLOW,
                NotifyMessage.TYPE_PM, NotifyMessage.TYPE_SYSTEM, NotifyMessage.TYPE_AUDIT,
                NotifyMessage.TYPE_CRISIS, NotifyMessage.TYPE_REPORT);
    }

    // ------------------------------------------------------------------ 写：校验

    @Test
    @DisplayName("空提交 / 超上限 / 同格重复 / 缺 enabled / 未知类型 / 试图关 locked，六样都回 400/10001")
    void saveRejectsMalformedRequests() {
        BizException empty = assertThrows(BizException.class, () -> service.save(ME, null));
        assertThat(empty.getMessage()).isEqualTo("没有要保存的开关");
        assertThat(service.save(ME, req(toggle("like", false))).changed()).isEqualTo(1);
        mapper.rows.clear();

        List<NotifyPreferenceRequest.Toggle> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(toggle("like", i % 2 == 0));
        }
        BizException tooMany = assertThrows(BizException.class,
                () -> service.save(ME, new NotifyPreferenceRequest(nine)));
        assertThat(tooMany.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(tooMany.getMessage()).contains("一次最多提交 8 个开关").contains("现在是 9 个");

        BizException dup = assertThrows(BizException.class, () -> service.save(ME,
                req(toggle("like", false), toggle("like", true))));
        assertThat(dup.getMessage()).contains("同一个开关一次只能提交一次");

        BizException noEnabled = assertThrows(BizException.class,
                () -> service.save(ME, req(toggle("like", null))));
        assertThat(noEnabled.getMessage()).as("缺 enabled 不能被读成「关掉这一类」").contains("无法判断是开还是关");

        BizException unknown = assertThrows(BizException.class,
                () -> service.save(ME, req(toggle("poke", false))));
        assertThat(unknown.getMessage()).contains("没有这一类通知：poke");

        BizException locked = assertThrows(BizException.class, () -> service.save(ME,
                req(toggle(NotifyMessage.TYPE_AUDIT, false))));
        assertThat(locked.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(locked.getMessage()).contains("审核结果不能关").contains("不看到它");
    }

    @Test
    @DisplayName("校验全在写入之前：混进一个非法格的那次提交，一格都不落库")
    void halfValidRequestWritesNothing() {
        BizException error = assertThrows(BizException.class, () -> service.save(ME,
                req(toggle("like", false), toggle(NotifyMessage.TYPE_CRISIS, false))));
        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(mapper.upserts).as("半套生效的偏好是最难解释的界面状态").isEmpty();
    }

    @Test
    @DisplayName("关成一格：落 enabled=0，回执是完整的八格且只有这一格变成关")
    void saveMutesOneKindAndReturnsFullCatalog() {
        NotifyPreferenceService.SaveResult result = service.save(ME, req(toggle("like", false)));
        assertThat(result.changed()).isEqualTo(1);
        assertThat(mapper.upserts).containsExactly("like=0");
        assertThat(result.items()).hasSize(8);
        assertThat(byType(result.items(), NotifyMessage.TYPE_LIKE).enabled()).isFalse();
        assertThat(byType(result.items(), NotifyMessage.TYPE_COMMENT).enabled()).isTrue();
    }

    @Test
    @DisplayName("值没变就不写：把默认「接收」的赞再存成接收，changed=0 且一条 INSERT 都不发")
    void unchangedValueWritesNothing() {
        NotifyPreferenceService.SaveResult result = service.save(ME, req(toggle("like", true)));
        assertThat(result.changed()).as("一次空提交不能让「保存成功」这四个字撒谎").isZero();
        assertThat(mapper.upserts).isEmpty();
        assertThat(result.items()).as("即便如此也要回完整八格，界面照旧按回执重画").hasSize(8);
    }

    @Test
    @DisplayName("关后再打开会落 enabled=1 的历史行，第二次打开才没得改")
    void reopeningStoresAnExplicitOne() {
        assertThat(service.save(ME, req(toggle("comment", false))).changed()).isEqualTo(1);
        assertThat(mapper.upserts).containsExactly("comment=0");
        assertThat(service.save(ME, req(toggle("comment", true))).changed()).isEqualTo(1);
        assertThat(mapper.upserts).containsExactly("comment=0", "comment=1");
        assertThat(service.save(ME, req(toggle("comment", true))).changed())
                .as("库里已经是 1，再存一次 1 不算改动").isZero();
    }

    @Test
    @DisplayName("locked 的三档提交 enabled=true 是合法的：界面把八格全发回来时不该整条请求挨打")
    void savingLockedKindsAsEnabledIsAccepted() {
        NotifyPreferenceService.SaveResult result = service.save(ME,
                req(toggle(NotifyMessage.TYPE_SYSTEM, true), toggle(NotifyMessage.TYPE_CRISIS, true)));
        assertThat(result.changed()).as("默认就是 true，没变所以不写").isZero();
        assertThat(mapper.upserts).isEmpty();
        assertThat(result.items()).hasSize(8);
    }

    // ------------------------------------------------------------------ 闸门：读判定

    @Test
    @DisplayName("mutes 只对「库里存了 0」为真：缺行、未知类型、locked 三档、enabled=1 全为假")
    void mutesOnlyWhenExplicitlyTurnedOff() {
        assertThat(service.mutes(ME, "like")).as("缺行 = 没设置过").isFalse();
        mapper.rows.put("like", 1);
        assertThat(service.mutes(ME, "like")).as("显式开着").isFalse();
        mapper.rows.put("like", 0);
        assertThat(service.mutes(ME, "like")).as("这一格才是关").isTrue();
        assertThat(service.mutes(ME, "poke")).as("ENUM 将来加值时失败开放，新类型照写照推").isFalse();
        assertThat(service.mutes(ME, null)).isFalse();
        assertThat(service.mutes(0L, "like")).as("recipientId <= 0 根本不会被写通知，闸门也不查库").isFalse();
        assertThat(service.mutes(-1L, "like")).isFalse();
    }

    @Test
    @DisplayName("locked 的三档连库都不查：闸门在热路径上先看目录，再看行")
    void lockedKindsShortCircuitBeforeTheQuery() {
        int before = mapper.enabledOfCalls;
        assertThat(service.mutes(ME, NotifyMessage.TYPE_AUDIT)).isFalse();
        assertThat(service.mutes(ME, NotifyMessage.TYPE_CRISIS)).isFalse();
        assertThat(service.mutes(ME, NotifyMessage.TYPE_SYSTEM)).isFalse();
        assertThat(mapper.enabledOfCalls).as("三档不可关，点查它们只是白跑一趟索引").isEqualTo(before);
    }

    // ------------------------------------------------------------------ 闸门：接进写链路

    @Test
    @DisplayName("关掉「赞」之后：赞仍落库但 is_read=1 且不推实时帧，评论不受影响")
    void mutedKindStillPersistsWithoutBadgeOrPush() {
        RecordingNotifyService rec = new RecordingNotifyService((userId, type) ->
                userId == ME && NotifyMessage.TYPE_LIKE.equals(type));
        rec.service().notifyLike(ME, "小屿", 300L, "求助：今晚睡不着");
        rec.service().notifyComment(ME, "阿舟", 300L, "会好起来的");

        assertThat(rec.size()).as("Gate3 原话：关闭类仍落库").isEqualTo(2);
        NotifyMessage like = rec.ofType(NotifyMessage.TYPE_LIKE).get(0);
        NotifyMessage comment = rec.ofType(NotifyMessage.TYPE_COMMENT).get(0);
        assertThat(like.getIsRead()).as("关掉的行按已读落库，于是 countUnread 数不到、红点不亮").isEqualTo(1);
        assertThat(comment.getIsRead()).as("没关的那一格照旧是未读").isEqualTo(0);
        assertThat(rec.pushCount()).as("只有没被关的那一条会推 WebSocket 帧").isEqualTo(1);
        assertThat(rec.service().list(ME, null, null).unreadCount()).as("未读总数里只有评论那一条").isEqualTo(1L);
    }

    @Test
    @DisplayName("不带闸门的既有写法完全不受影响：两参构造仍是 ALLOW_ALL")
    void defaultGateKeepsOldBehaviour() {
        RecordingNotifyService rec = new RecordingNotifyService();
        rec.service().notifyLike(ME, "小屿", 300L, "求助：今晚睡不着");
        assertThat(rec.ofType(NotifyMessage.TYPE_LIKE).get(0).getIsRead()).isEqualTo(0);
        assertThat(rec.pushCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ fake

    /** 通知偏好的内存替身：只复刻「缺行 / 唯一行 / 覆盖写」三条真库语义。 */
    private static final class FakeMapper implements NotifyPreferenceMapper {

        final Map<String, Integer> rows = new LinkedHashMap<>();
        final List<String> upserts = new ArrayList<>();
        int enabledOfCalls;

        @Override
        public List<NotifyPreference> listByUser(long userId) {
            List<NotifyPreference> out = new ArrayList<>();
            rows.forEach((type, enabled) -> {
                NotifyPreference row = new NotifyPreference();
                row.setUserId(userId);
                row.setType(type);
                row.setEnabled(enabled);
                out.add(row);
            });
            return out;
        }

        @Override
        public Integer enabledOf(long userId, String type) {
            enabledOfCalls++;
            return rows.get(type);
        }

        @Override
        public int upsert(long userId, String type, int enabled) {
            upserts.add(type + "=" + enabled);
            rows.put(type, enabled);
            return 1;
        }
    }
}
