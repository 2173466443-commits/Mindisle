package com.mindisle.notify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.entity.NotifyMessage;
import com.mindisle.entity.NotifyPreference;
import com.mindisle.mapper.NotifyPreferenceMapper;
import com.mindisle.notify.dto.NotifyPreferenceRequest;
import com.mindisle.notify.dto.NotifyPreferenceView;

/**
 * 通知偏好（任务 T3.16 后半 · 需求 FR9.4 · 手册 §15 T3.16「四类开关（审核/危机类不可关）」· §18 Gate3）。
 *
 * <p><b>本类同时扮演两个角色，这是它存在的理由</b>：① 给界面读写那八个开关（{@link #snapshot} 与
 * {@link #save}）；② 实现 {@link NotifyService.PreferenceGate}，站在 {@code NotifyService#write}
 * 的落库前面问一句「这个人要不要收这一类」。第二个角色才是让「设置」两个字落到行为上的那一半 ——
 * 只做第一个角色等于做了一张存偏好而没人读的表，界面能改、改了不影响任何一条通知，
 * 那种「设置页是装饰品」的坑本项目在别处已经写白过一次（见手册 §14 的 {@code rec.weight_profile}）。</p>
 *
 * <p><b>三档不可关（audit / crisis / system）</b>：需求 FR9.4 明文点前两档，第三档是本项目的加判：
 * {@code system} 这一类现在装着的是「你的账号被禁言 / 被封禁」（{@code notifyAccountAction}）
 * 与公告，把它做成可关等于让被处置的人提前屏蔽处置通知 —— 那不是打扰，那是送达。
 * 三档在 {@link #KINDS} 里逐个写了一句理由，界面直接念这句话，不另写一套「为了你的安全」。</p>
 *
 * <p><b>关闭 ≠ 不写（Gate3 判据「关闭类仍落库不计红点」）</b>：闸门只改两件事 ——
 * 落库时 {@code is_read} 直接给 1（所以 {@code countUnread} 数不到它，红点不亮），
 * 以及<b>不触发推送口</b>（所以不会有一条实时帧把一个已经声明不想收的提醒弹到屏幕上）。
 * 通知行照写：它是「这件事发生过」的证据，与 {@code notify_message} 不做级联清理是同一个取舍。
 * 用户在设置页关掉「赞」，第二天再打开，那批赞还在列表里 —— 这才对得上「关的是打扰，不是记录」。</p>
 *
 * <p><b>为什么 {@link #mutes} 每次写通知都点查一次库</b>：通知只在互动事件里产生（点赞/评论/关注/私信），
 * 量级由写请求本身决定，而这一次点查走的是复合主键 (user_id, type)，至多命中一行。
 * 缓存它要处理「改完偏好之后什么时候失效」，而那是一道没有标准答案的题
 * （多实例部署下本机缓存别人的改动是必然脏读）。写路径上本来就已经有 {@code existsUnreadDuplicate}
 * 那条 SELECT，两条同构的点查一起走索引，比留一个会漂移的缓存好解释。</p>
 */
@Service
public class NotifyPreferenceService implements NotifyService.PreferenceGate {

    private static final Logger log = LoggerFactory.getLogger(NotifyPreferenceService.class);

    /** 一次提交的开关上限：八类全提交也才八格，超了就是前端在拿类型码做遍历。 */
    static final int TOGGLE_MAX = 8;

    /**
     * 一类通知的「能不能关」与那一句理由。
     *
     * <p>{@code lockable} 这个名字是反着说的：<b>true = 用户可以关</b>，
     * 与 {@link NotifyPreferenceView#locked()} 互为反面。
     *
     * <p>{@code lockReason} 在这份目录里有两个用途：<b>不可关的三档</b>存「为什么不能关」，
     * <b>可关但值得解释的 pm 与 report</b> 存「关掉之后会怎样」，其余为 null。界面拿到的是同一格、
     * 念的是同一句话，不需要自己判断这句该不该说。之所以不在这里直接存 locked：
     * 目录里每一格真正要回答的是「这一格能不能交回 false」，把否定写在字段名上，
     * 读 {@link #save} 的人就得在脑子里再翻一次。出参那一侧叫 locked 是因为界面要画的是置灰。</p>
     */
    record Kind(String type, boolean lockable, String lockReason) {
    }

    /** 八类目录，顺序即界面顺序（互动四类在前，结果类在后）；标签复用 {@link NotifyService#typeLabel}，不留第二份映射。 */
    private static final Map<String, Kind> KINDS = kinds();

    private final NotifyPreferenceMapper mapper;

    public NotifyPreferenceService(NotifyPreferenceMapper mapper) {
        this.mapper = mapper;
    }

    private static Map<String, Kind> kinds() {
        Map<String, Kind> map = new LinkedHashMap<>();
        map.put(NotifyMessage.TYPE_LIKE, new Kind(NotifyMessage.TYPE_LIKE, true, null));
        map.put(NotifyMessage.TYPE_COMMENT, new Kind(NotifyMessage.TYPE_COMMENT, true, null));
        map.put(NotifyMessage.TYPE_FOLLOW, new Kind(NotifyMessage.TYPE_FOLLOW, true, null));
        map.put(NotifyMessage.TYPE_PM, new Kind(NotifyMessage.TYPE_PM, true,
                "关掉之后新私信不会点亮顶栏红点，也不会实时弹出提醒。私信本身照旧送达，会话里的未读角标不受影响 —— "
                        + "这一格关的是提醒，不是收信。"));
        map.put(NotifyMessage.TYPE_SYSTEM, new Kind(NotifyMessage.TYPE_SYSTEM, false,
                "系统这一类装着「你的账号被禁言」「你的账号被封禁」这类处置结果，关掉等于让被处置的人提前屏蔽处置通知。"));
        map.put(NotifyMessage.TYPE_AUDIT, new Kind(NotifyMessage.TYPE_AUDIT, false,
                "审核结果不能关：不看到它，就不知道自己的帖子为什么别人看不见。"));
        map.put(NotifyMessage.TYPE_CRISIS, new Kind(NotifyMessage.TYPE_CRISIS, false,
                "危机关怀不能关（需求 FR9.4 明文）。这一类是「有人担心你」的那一条提醒。"));
        map.put(NotifyMessage.TYPE_REPORT, new Kind(NotifyMessage.TYPE_REPORT, true,
                "举报回执可以关：举报的处理结论仍然在通知列表里，只是不会亮红点。"));
        return Collections.unmodifiableMap(map);
    }

    /** 目录里的类型码（写侧校验与测试对账用；顺序同界面）。 */
    static List<String> types() {
        return List.copyOf(KINDS.keySet());
    }

    // ================================================================ 读

    /**
     * 八个开关的当前状态（缺行 = 接收）。
     *
     * <p>先一次 {@code listByUser} 再在内存里对齐八格，而不是八次点查：这个接口是给设置页开页用的，
     * 八次同构查询就是八次往返，而一次范围扫至多八行。</p>
     */
    public List<NotifyPreferenceView> snapshot(long userId) {
        Map<String, Boolean> saved = readSaved(userId);
        List<NotifyPreferenceView> out = new ArrayList<>(KINDS.size());
        for (Kind k : KINDS.values()) {
            boolean enabled = saved.getOrDefault(k.type(), Boolean.TRUE);
            out.add(new NotifyPreferenceView(k.type(), NotifyService.typeLabel(k.type()),
                    enabled, !k.lockable(), k.lockReason()));
        }
        return out;
    }

    /** 库里改过的开关。同一类若出现重复行（结构上不可能，主键挡着），后一条覆盖前一条而不是报错。 */
    private Map<String, Boolean> readSaved(long userId) {
        List<NotifyPreference> rows = mapper.listByUser(userId);
        Map<String, Boolean> map = new LinkedHashMap<>();
        if (rows != null) {
            for (NotifyPreference row : rows) {
                if (row == null || row.getType() == null) {
                    continue;
                }
                map.put(row.getType(), row.getEnabled() != null && row.getEnabled() == 0 ? Boolean.FALSE
                        : Boolean.TRUE);
            }
        }
        return map;
    }

    // ================================================================ 写

    /**
     * 保存开关，返回保存后的完整八格（让前端以回执重画，而不是相信自己的乐观值）。
     *
     * <p><b>校验全在前、写入在后</b>：一次提交里混进一个未知类型或一次「试图关掉审核结果」，
     * 整条请求都不落库。半套生效的偏好是最难解释的界面状态 —— 用户看到「保存成功」却发现
     * 另一格没变，而那一格其实是被这次请求之外的东西改的。</p>
     *
     * <p><b>值没变就不写</b>：把本来默认「接收」的赞再保存成「接收」不会留下一行。
     * 于是「有行且 enabled=1」说的是一件真的有意义的往事：这一类被关过、又被打开了。
     * 少写一行也就让 {@code updated_at} 继续表示「最后一次改动」而不是「最后一次点保存」。</p>
     *
     * <p>返回的是「改动了几格」而不是「收到几格」：前端那句提示要说的是「有 N 个开关变了」，
     * 一次空提交（一格都没变）也得能区分出来，否则「保存成功」这四个字会撒谎。</p>
     */
    @Transactional
    public SaveResult save(long userId, NotifyPreferenceRequest request) {
        List<NotifyPreferenceRequest.Toggle> toggles = request == null ? null : request.toggles();
        if (toggles == null || toggles.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "没有要保存的开关");
        }
        if (toggles.size() > TOGGLE_MAX) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "一次最多提交 " + TOGGLE_MAX + " 个开关，现在是 " + toggles.size() + " 个");
        }
        Map<String, Boolean> wanted = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (NotifyPreferenceRequest.Toggle t : toggles) {
            if (t == null || t.type() == null || t.type().isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "开关缺少类型");
            }
            if (t.enabled() == null) {
                // 刻意不把缺字段读成 false：那会让「前端漏传一个字段」变成「把这一类关了」。
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "开关 " + t.type() + " 没有给 enabled，无法判断是开还是关");
            }
            Kind k = KINDS.get(t.type());
            if (k == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "没有这一类通知：" + t.type());
            }
            if (!seen.add(t.type())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "同一个开关一次只能提交一次：" + t.type());
            }
            if (!k.lockable() && !Boolean.TRUE.equals(t.enabled())) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        NotifyService.typeLabel(k.type()) + "不能关闭：" + k.lockReason());
            }
            wanted.put(k.type(), t.enabled());
        }

        Map<String, Boolean> saved = readSaved(userId);
        int changed = 0;
        List<String> changedTypes = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : wanted.entrySet()) {
            boolean current = saved.getOrDefault(e.getKey(), Boolean.TRUE);
            if (current == e.getValue()) {
                continue;
            }
            mapper.upsert(userId, e.getKey(), Boolean.TRUE.equals(e.getValue()) ? 1 : 0);
            changed++;
            changedTypes.add(e.getKey());
        }
        if (changed > 0) {
            log.info("通知偏好已更新 user={} 改动={} 类={}", userId, changed, String.join(",", changedTypes));
        }
        return new SaveResult(snapshot(userId), changed);
    }

    /**
     * @param items   保存后的完整八格（前端按这份重画，包括那些一格都没变的）
     * @param changed 本次真的改动了几格
     */
    public record SaveResult(List<NotifyPreferenceView> items, int changed) {
    }

    // ================================================================ 闸门（T5.8 的写侧一半）

    /**
     * 这一类通知对这个人是否「不落红点、不实时推」。
     *
     * <p>三条一律返回 false 的路径要读明白：未知类型（ENUM 将来加值时<b>失败开放</b>，
     * 新类型的通知照写照推，比静默丢掉好——丢掉的那条永远没人知道）、
     * 不可关的三档、以及库里没有这一行。最后一格是「没设置过」，不是「关掉了」。</p>
     */
    @Override
    public boolean mutes(long userId, String type) {
        if (userId <= 0L || type == null) {
            return false;
        }
        Kind k = KINDS.get(type);
        if (k == null || !k.lockable()) {
            return false;
        }
        Integer enabled = mapper.enabledOf(userId, type);
        return enabled != null && enabled.intValue() == 0;
    }
}
