package com.mindisle.post;

import com.mindisle.entity.AnonymousAlias;
import java.util.List;

/**
 * 马甲存储端口（任务 3.4）。
 *
 * <p>为什么要单独抽一层而不是让 Service 直接用 Mapper：
 * 本阶段真实库还没建（31 表 DDL 尚未执行），而「一匹马甲」的去重规则是 BR1 的硬约束，
 * 必须先能用单测钉死。抽端口 = 一个接口 + 一个 Mapper 适配器，代价 60 行，
 * 换来「规则可测」与「将来换成 Redis 缓存 + DB 两级只改适配器」。</p>
 */
public interface AnonymousAliasRepository {

    /** 某用户在某场景已有马甲则返回，否则 null。 */
    AnonymousAlias findByUserAndScene(long userId, String scene);

    /** 该用户全部马甲，按 id 升序；无则返回空列表（调用方不判 null）。 */
    List<AnonymousAlias> listByUser(long userId);

    /**
     * 落库一条马甲。
     *
     * <p>并发下两个请求可能同时判定「该用户还没有马甲」，靠 uk_user_alias_scene 兜住：
     * 实现方捕获唯一键冲突后返回<b>实际生效</b>的别名，而不是抛出异常。</p>
     *
     * @return 实际生效的别名（冲突时是对手先插进去的那条）
     */
    String insertIfAbsent(long userId, String scene, String aliasName);
}
