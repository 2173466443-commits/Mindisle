package com.mindisle.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.entity.AnonymousAlias;

/**
 * 马甲分配单测（任务 T3.4 · 需求 FR1.4、BR1）。
 *
 * <p><b>为什么用内存 fake 而不是 Mockito</b>：被测规则是「同一用户全站只有一张匿名脸」，
 * 它的正确性取决于「查—判—插」三步的<b>顺序与返回值</b>，用 mock 逐次打桩会把真实语义
 * 换成我以为的语义（打桩第 2 次返回什么，完全由我当场决定，等于把断言写进了准备阶段）。
 * 这里直接实现存储端口，行为可复现，也能顺手验一遍 Adapter 的接口契约。</p>
 */
class AnonymousAliasServiceTest {

    private InMemoryRepository repository;
    private AnonymousAliasService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepository();
        service = new AnonymousAliasService(repository);
    }

    @Test
    @DisplayName("FR1.4：别名格式为「匿名屿民·X」，长度不超列宽 32")
    void aliasMatchesRequiredFormat() {
        String alias = service.resolveAlias(1L, "HOLE");
        assertThat(alias).startsWith(AnonymousAliasService.ALIAS_PREFIX).hasSizeLessThanOrEqualTo(32);
        assertThat(AnonymousAliasService.NAME_POOL).contains(alias.substring(AnonymousAliasService.ALIAS_PREFIX.length()));
    }

    @Test
    @DisplayName("名字池 40 个且不重复，每个拼出来的别名都在列宽内")
    void namePoolIsBigEnoughAndUnique() {
        assertThat(AnonymousAliasService.NAME_POOL).hasSize(40).doesNotHaveDuplicates().allSatisfy(name -> {
            assertThat(name).hasSize(2);
            assertThat(AnonymousAliasService.ALIAS_PREFIX + name).hasSizeLessThanOrEqualTo(32);
        });
    }

    @Test
    @DisplayName("取名是确定性派生：同人同序号必同名，换人换名，序号走完会回绕")
    void namingIsDeterministic() {
        assertThat(AnonymousAliasService.suggestAlias(1L, 0)).isEqualTo(AnonymousAliasService.suggestAlias(1L, 0));
        assertThat(AnonymousAliasService.suggestAlias(1L, 0)).isNotEqualTo(AnonymousAliasService.suggestAlias(2L, 0));
        assertThat(AnonymousAliasService.suggestAlias(1L, 0))
                .isNotEqualTo(AnonymousAliasService.suggestAlias(1L, 1));
        // 40 个序号刚好把池走一圈：第 0 与第 40 个必须同名（回绕而不是抛异常）
        assertThat(AnonymousAliasService.suggestAlias(7L, 0))
                .isEqualTo(AnonymousAliasService.suggestAlias(7L, 40));
        for (long userId = 1L; userId <= 50L; userId++) {
            assertThat(AnonymousAliasService.suggestAlias(userId, 0))
                    .startsWith(AnonymousAliasService.ALIAS_PREFIX);
        }
    }

    @Test
    @DisplayName("BR1：同一用户在树洞、求助、建议里共用一匹马甲，库里只有一行")
    void oneUserGetsExactlyOneAliasAcrossScenes() {
        String first = service.resolveAlias(1L, "HOLE");
        assertThat(service.resolveAlias(1L, "HELP")).isEqualTo(first);
        assertThat(service.resolveAlias(1L, "FEEDBACK")).isEqualTo(first);
        assertThat(service.resolveAlias(1L, "ALL")).isEqualTo(first);
        assertThat(repository.rows).hasSize(1);
        assertThat(repository.rows.get(0).getScene()).isEqualTo("HOLE");
        assertThat(repository.rows.get(0).getUserId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("同场景重复解析幂等：不会因为并发前的重复请求长第二匹马甲")
    void repeatedSameSceneIsIdempotent() {
        assertThat(service.resolveAlias(2L, "HOLE")).isEqualTo(service.resolveAlias(2L, "HOLE"));
        assertThat(repository.rows).hasSize(1);
    }

    @Test
    @DisplayName("已有 ALL 全局马甲时直接复用，不再按场景新建")
    void globalAliasWins() {
        repository.seed(9L, "ALL", AnonymousAliasService.ALIAS_PREFIX + "既白");
        assertThat(service.resolveAlias(9L, "HOLE")).isEqualTo(AnonymousAliasService.ALIAS_PREFIX + "既白");
        assertThat(repository.rows).hasSize(1);
    }

    @Test
    @DisplayName("场景对不上但有旧马甲时，复用最早起的那匹（BR1 优先于场景标签）")
    void earliestAliasWinsWhenSceneMisses() {
        repository.seed(7L, "HELP", AnonymousAliasService.ALIAS_PREFIX + "望舒");
        assertThat(service.resolveAlias(7L, "FEEDBACK")).isEqualTo(AnonymousAliasService.ALIAS_PREFIX + "望舒");
        assertThat(repository.rows).hasSize(1);
    }

    @Test
    @DisplayName("未知场景收敛为 ALL，而不是造出第五种场景")
    void unknownSceneNormalizesToAll() {
        service.resolveAlias(5L, "definitely-not-a-scene");
        assertThat(repository.rows).hasSize(1);
        assertThat(repository.rows.get(0).getScene()).isEqualTo("ALL");
        assertThat(AnonymousAliasService.normalizeScene("hole")).isEqualTo("HOLE");
        assertThat(AnonymousAliasService.normalizeScene("  all ")).isEqualTo("ALL");
        assertThat(AnonymousAliasService.normalizeScene(null)).isEqualTo("ALL");
        assertThat(AnonymousAliasService.normalizeScene("")).isEqualTo("ALL");
    }

    @Test
    @DisplayName("并发撞唯一键时采用先插入者的别名，不抛异常也不改口")
    void concurrentInsertKeepsTheWinner() {
        repository.conflictWinner = AnonymousAliasService.ALIAS_PREFIX + "和光";
        assertThat(service.resolveAlias(21L, "HOLE")).isEqualTo(AnonymousAliasService.ALIAS_PREFIX + "和光");
    }

    @Test
    @DisplayName("非法入参快速失败：userId 必须为正")
    void rejectsNonPositiveUserId() {
        assertThrows(IllegalArgumentException.class, () -> service.resolveAlias(0L, "HOLE"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveAlias(-1L, "ALL"));
    }

    /** 存储端口的内存实现，行为对齐 Mapper + Adapter：查不到返回 null、列表按 id 升序。 */
    private static final class InMemoryRepository implements AnonymousAliasRepository {

        private final List<AnonymousAlias> rows = new ArrayList<>();
        /** 非空即模拟「insert 撞 uk_user_alias_scene」，返回值取这里的对手别名。 */
        private String conflictWinner;

        private void seed(long userId, String scene, String aliasName) {
            rows.add(row(userId, scene, aliasName));
        }

        private AnonymousAlias row(long userId, String scene, String aliasName) {
            AnonymousAlias entity = new AnonymousAlias();
            entity.setId((long) rows.size() + 1L);
            entity.setUserId(userId);
            entity.setScene(scene);
            entity.setAliasName(aliasName);
            return entity;
        }

        @Override
        public AnonymousAlias findByUserAndScene(long userId, String scene) {
            return rows.stream()
                    .filter(each -> each.getUserId() == userId && each.getScene().equals(scene))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<AnonymousAlias> listByUser(long userId) {
            return rows.stream()
                    .filter(each -> each.getUserId() == userId)
                    .sorted(Comparator.comparing(AnonymousAlias::getId))
                    .toList();
        }

        @Override
        public String insertIfAbsent(long userId, String scene, String aliasName) {
            if (conflictWinner != null) {
                String winner = conflictWinner;
                conflictWinner = null;
                rows.add(row(userId, scene, winner));
                return winner;
            }
            rows.add(row(userId, scene, aliasName));
            return aliasName;
        }
    }
}
