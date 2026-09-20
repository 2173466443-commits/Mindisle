package com.mindisle.post;

import java.util.List;

import org.springframework.stereotype.Service;

import com.mindisle.entity.AnonymousAlias;

/**
 * 马甲分配（任务 3.4 · 需求 FR1.4、BR1 · 手册 §6.1 行 3.4）。
 *
 * <p><b>规则口径</b>：FR1.4 要「随机分配别名（如「匿名屿民·阿澜」）」，BR1 要
 * 「同一用户在同一话题下的不同马甲去重（避免自我点赞）」。两条合起来的真实含义是：
 * <b>一个用户对外只有一张匿名脸</b>——不同帖子换不同昵称，等于允许一个人给自己点赞互捧，
 * 那是推荐质量的直接敌人（需求 §8.2 质量分依赖 author 维度去重）。</p>
 *
 * <p>因此解析顺序是：同场景命中 → 该用户的 ALL 全局马甲 → 该用户最早的一匹 → 才新建。
 * {@code anonymous_alias.scene} 只是「这匹马甲第一次是在哪个域用掉的」的记录，
 * 不参与「能不能再发一匹」的判定。</p>
 *
 * <p><b>命名不是随机而是确定性派生</b>：{@code (userId * 31 + 已有马甲数) % 40}。
 * 用 {@link java.util.Random} 会在「插库失败重试」时换名字，测试也无法断言；
 * 确定性派生让同一个人永远拿到同一批雅名，且不同人撞名的概率低到可以忽略。
 * 撞名本身无害：马甲不是账号，全站重名反而更有利于隐藏身份。</p>
 */
@Service
public class AnonymousAliasService {

    /** 别名前缀，需求 FR1.4 原文口径。 */
    static final String ALIAS_PREFIX = "匿名屿民·";

    /** 双字雅名池（40 个）：够用是首要指标，好听的边际收益不重要。 */
    static final List<String> NAME_POOL = List.of(
            "阿澜", "知野", "清和", "与舟", "慕山",
            "微光", "早行", "南栖", "柏舟", "见山",
            "拾夏", "允昭", "亦舒", "怀砚", "半山",
            "临风", "长安", "初霁", "时雨", "木槿",
            "若谷", "溪见", "望舒", "明烛", "迟迟",
            "子衿", "青梧", "向晚", "一苇", "如晦",
            "观澜", "疏影", "度秋", "云胡", "既白",
            "未晞", "采薇", "若飞", "芷汀", "和光"
    );

    /** 与 anonymous_alias.scene 的 ENUM 完全一致，多出即非法。 */
    static final List<String> SCENES = List.of("HOLE", "HELP", "FEEDBACK", "ALL");

    /** 列宽 VARCHAR(32)，超长必须在这里挡住而不是让 MySQL 报错。 */
    static final int MAX_ALIAS_LENGTH = 32;

    private final AnonymousAliasRepository repository;

    public AnonymousAliasService(AnonymousAliasRepository repository) {
        this.repository = repository;
    }

    /**
     * 取（必要时新建）该用户在指定场景下对外展示的别名。
     *
     * @param scene HOLE 树洞 / HELP 求助 / FEEDBACK 建议 / ALL 全域，未知值按 ALL 处理
     */
    public String resolveAlias(long userId, String scene) {
        if (userId <= 0L) {
            throw new IllegalArgumentException("userId 必须为正：" + userId);
        }
        String effective = normalizeScene(scene);
        AnonymousAlias sameScene = repository.findByUserAndScene(userId, effective);
        if (sameScene != null) {
            return sameScene.getAliasName();
        }
        if (!"ALL".equals(effective)) {
            AnonymousAlias universal = repository.findByUserAndScene(userId, "ALL");
            if (universal != null) {
                return universal.getAliasName();
            }
        }
        List<AnonymousAlias> owned = repository.listByUser(userId);
        if (!owned.isEmpty()) {
            // BR1：已经有脸了，就不发第二张。
            return owned.get(0).getAliasName();
        }
        return repository.insertIfAbsent(userId, effective, suggestAlias(userId, owned.size()));
    }

    /** 供发帖接口回显「这次匿名将以什么身份出现」，也方便单测直接验证格式。 */
    static String suggestAlias(long userId, int sequence) {
        int index = (int) Math.floorMod(Math.addExact(Math.multiplyExact(userId, 31L), sequence), NAME_POOL.size());
        String alias = ALIAS_PREFIX + NAME_POOL.get(index);
        if (alias.length() > MAX_ALIAS_LENGTH) {
            throw new IllegalStateException("别名超出列宽：" + alias);
        }
        return alias;
    }

    /** 场景归一：大小写与空白容错，未知值收敛到 ALL（宁可由它共用一张脸，也不发第二张脸）。 */
    static String normalizeScene(String scene) {
        if (scene == null || scene.isBlank()) {
            return "ALL";
        }
        String upper = scene.trim().toUpperCase();
        return SCENES.contains(upper) ? upper : "ALL";
    }
}
