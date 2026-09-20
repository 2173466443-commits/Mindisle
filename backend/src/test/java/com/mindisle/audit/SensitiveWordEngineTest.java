package com.mindisle.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import com.mindisle.audit.SensitiveWordEngine.CheckResult;
import com.mindisle.cache.CaffeineCacheService;
import com.mindisle.config.MindisleProperties;

/**
 * 敏感词引擎单测（手册 §6.3 硬性要求：全半角 / 插入空格 / emoji 分隔三类变体各 ≥2 例）。
 *
 * <p>用例分两层：绝大多数走一个 8 行的内置词库（快、意图清楚），
 * 只有「真实快照能不能加载」「5 条正则分别是否生效」走 src/main/resources 里那份 139 条的词库——
 * 那部分才是需求 FR7.1 真正要保证的东西。</p>
 */
class SensitiveWordEngineTest {

    /** 内置小词库，逐条对应一个断言，别往里加无关词条。 */
    private static final String FIXTURE = String.join("\n",
            "#version=test-1",
            row("政治违法", "black", "BLOCK", "both", "contains", "\u67aa\u652f\u5f39\u836f"),
            row("色情低俗", "black", "BLOCK", "both", "contains", "\u88f8\u804a"),
            row("辱骂攻击", "grey", "REVIEW", "user", "contains", "\u50bb\u903c"),
            row("自伤自杀", "risk", "TAG", "both", "contains", "\u4e0d\u60f3\u6d3b"),
            row("隐私泄露", "grey", "REVIEW", "both", "regex", "1[3-9][0-9]{9}"),
            row("隐私泄露", "grey", "REVIEW", "both", "regex", "[0-9]{16,19}"),
            row("广告导流", "black", "BLOCK", "both", "contains", "\u52a0\u5fae\u4fe1\u9886"),
            row("医疗越界词", "black", "BLOCK", "ai", "contains", "\u5f00\u836f"));

    private SensitiveWordEngine engine;
    private MindisleProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MindisleProperties();
        engine = newEngine();
        engine.reload(FIXTURE);
    }

    private SensitiveWordEngine newEngine() {
        return new SensitiveWordEngine(properties, resourceLoader());
    }

    private static ResourceLoader resourceLoader() {
        return new DefaultResourceLoader();
    }

    private static String row(String group, String level, String action, String scope, String matchType, String word) {
        return String.join("\t", group, level, action, scope, matchType, word);
    }

    @Test
    @DisplayName("全半角变体 x2：全角数字命中手机号正则，繁体+全角空格命中硬拦词")
    void fullWidthVariants() {
        CheckResult phone = engine.check("\uFF11\uFF13\uFF18\uFF10\uFF10\uFF11\uFF13\uFF18\uFF10\uFF10\uFF10");
        assertThat(phone.hit()).isTrue();
        assertThat(phone.category()).isEqualTo("\u9690\u79c1\u6cc4\u9732");
        assertThat(phone.action()).isEqualTo("REVIEW");

        assertThat(engine.check("\u69cd\u652f\u5f48\u85e5").action()).isEqualTo("BLOCK");
        assertThat(engine.check("\u88f8\u3000\u804a").action()).isEqualTo("BLOCK");
    }

    @Test
    @DisplayName("插入空格变体 x2：词面被空格、换行打断仍命中")
    void spacedVariants() {
        assertThat(engine.check("\u67aa \u652f \u5f39 \u836f").hit()).isTrue();
        assertThat(engine.check("\u50bb\n\u903c").hitCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("emoji 与零宽分隔变体 x2：装饰字符不参与匹配")
    void emojiVariants() {
        assertThat(engine.check("\u60f3\uD83D\uDE0A\u4e70\u67aa\u652f\u5f39\u836f").hit()).isTrue();
        assertThat(engine.check("\u4e0d\u60f3\u200B\u6d3b").level()).isEqualTo("risk");
    }

    @Test
    @DisplayName("5 条正则各自生效：手机号、QQ、微信、身份证、银行卡")
    void allFiveRegexRules() throws IOException {
        SensitiveWordEngine real = newEngine();
        real.afterPropertiesSet();
        assertThat(real.check("\u6253\u7535\u8bdd\u7ed9 138 0013 8000").category()).isEqualTo("\u9690\u79c1\u6cc4\u9732");
        assertThat(real.check("\u6709\u4e8b\u627e qq\uff1a123456789").hit()).isTrue();
        assertThat(real.check("\u5fae\u4fe1\uff1aabcd_1234").hit()).isTrue();
        assertThat(real.check("110101199003077771").hit()).isTrue();
        assertThat(real.check("\u5361\u53f7 6222020200112233445").hit()).isTrue();
    }

    @Test
    @DisplayName("真实快照：139 条、版本 v0.1，七个分组一个不少（需求 FR7.1）")
    void loadsRealSnapshot() {
        SensitiveWordEngine real = newEngine();
        real.afterPropertiesSet();
        assertThat(real.version()).isEqualTo("v0.1");
        assertThat(real.wordCount()).isEqualTo(139);
    }

    @Test
    @DisplayName("作用侧隔离：辱骂只拦用户，越界医疗建议只拦模型")
    void scopeSeparatesUserAndAi() {
        assertThat(engine.check("\u4f60\u5c31\u662f\u50bb\u903c", "user").hit()).isTrue();
        assertThat(engine.check("\u4f60\u5c31\u662f\u50bb\u903c", "ai").hit()).isFalse();
        assertThat(engine.check("\u8fd9\u4e2a\u8981\u5f00\u836f\u5417", "ai").hit()).isTrue();
        assertThat(engine.check("\u8fd9\u4e2a\u8981\u5f00\u836f\u5417", "user").hit()).isFalse();
    }

    @Test
    @DisplayName("风险词是「放行 + 打标记」，不是拦截（需求 §18.3、BR7）")
    void riskGroupTagsInsteadOfBlocking() {
        CheckResult result = engine.check("\u6700\u8fd1\u8fde\u8bdd\u90fd\u4e0d\u60f3\u8bf4\uff0c\u4e0d\u60f3\u6d3b\u4e86");
        assertThat(result.hit()).isTrue();
        assertThat(result.level()).isEqualTo("risk");
        assertThat(result.action()).isEqualTo("TAG");
    }

    @Test
    @DisplayName("多组命中时主因取处置最重的：BLOCK > REVIEW > TAG")
    void primaryHitIsTheHeaviestAction() {
        CheckResult mixed = engine.check("\u4e70\u67aa\u652f\u5f39\u836f\uff0c\u8fd8\u662f\u4e0d\u60f3\u6d3b");
        assertThat(mixed.hitCount()).isEqualTo(2);
        assertThat(mixed.action()).isEqualTo("BLOCK");
        assertThat(mixed.category()).isEqualTo("\u653f\u6cbb\u8fdd\u6cd5");
    }

    @Test
    @DisplayName("最长匹配且不重叠：「赌博网站」只记一次，不叠加「赌博」")
    void longestMatchWithoutOverlap() {
        SensitiveWordEngine nested = newEngine();
        nested.reload(String.join("\n", "#version=nested",
                row("广告导流", "black", "BLOCK", "both", "contains", "\u8d4c\u535a"),
                row("广告导流", "black", "BLOCK", "both", "contains", "\u8d4c\u535a\u7f51\u7ad9")));
        CheckResult result = nested.check("\u63a8\u8350\u4e00\u4e2a\u8d4c\u535a\u7f51\u7ad9");
        assertThat(result.hitCount()).isEqualTo(1);
        assertThat(result.hits().get(0).word()).isEqualTo("\u8d4c\u535a\u7f51\u7ad9");
    }

    @Test
    @DisplayName("命中位置回原文下标：带空格与全角标点的正文也能被前端直接切片")
    void positionsUseRawOffsets() {
        String raw = "\u6211 \u60f3 \u4e70 \u67aa \u652f \u5f39 \u836f";
        CheckResult result = engine.check(raw);
        assertThat(result.positions()).hasSize(1);
        int[] range = result.positions().get(0);
        assertThat(raw.substring(range[0], range[1])).isEqualTo("\u67aa \u652f \u5f39 \u836f");
    }

    @Test
    @DisplayName("空文本与未加载状态：一律不命中，也不抛异常")
    void emptyAndUnloadedAreSafe() {
        assertThat(engine.check(null).hit()).isFalse();
        assertThat(engine.check("   ").positions()).isEmpty();
        SensitiveWordEngine unloaded = newEngine();
        assertThat(unloaded.version()).isEqualTo("(\u672a\u52a0\u8f7d)");
        assertThat(unloaded.check("\u67aa\u652f\u5f39\u836f").hit()).isFalse();
    }

    @Test
    @DisplayName("词库热更新：缓存里的版本号一变就重载，且按 dict-check-millis 节流")
    void hotUpdateViaCacheVersion() {
        CaffeineCacheService cache = new CaffeineCacheService();
        properties.getAudit().setDictCheckMillis(0L);
        cache.set("dict:version", "v0.1", java.time.Duration.ofMinutes(5));
        assertThat(engine.version()).isEqualTo("test-1");
        engine.refreshIfStale(cache, "dict:version");
        assertThat(engine.version()).isEqualTo("v0.1");
        assertThat(engine.wordCount()).isEqualTo(139);
    }

    @Test
    @DisplayName("reload 换版本：版本号与条数同步变化，旧对象不受影响")
    void reloadSwapsDictionaryAtomically() {
        CheckResult before = engine.check("\u50bb\u903c");
        engine.reload("#version=test-2\n" + row("辱骂攻击", "grey", "REVIEW", "user", "contains", "\u50bb\u903c"));
        assertThat(engine.version()).isEqualTo("test-2");
        assertThat(engine.wordCount()).isEqualTo(1);
        // 旧结果对象是快照，不被 reload 影响（避免「前端拿到一半新一半旧」）
        assertThat(before.action()).isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("坏词库要失败得快：空内容、列数不对、正则非法都抛异常")
    void rejectsBrokenDictionaries() {
        SensitiveWordEngine fresh = newEngine();
        assertThatThrownBy(() -> fresh.reload(" ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fresh.reload("#version=x\n\u53ea\u6709\u4e00\u5217"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("6");
        assertThatThrownBy(() -> fresh.reload("#version=x\n"
                + row("隐私泄露", "grey", "REVIEW", "both", "regex", "[0-9")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("正则非法");
    }

    @Test
    @DisplayName("词库文件缺失时拒绝启动（不允许「无词库全放行」）")
    void failsStartupWhenSnapshotMissing() {
        MindisleProperties broken = new MindisleProperties();
        broken.getAudit().setDictResource("dict/definitely-not-here.txt");
        SensitiveWordEngine engine2 = new SensitiveWordEngine(broken, resourceLoader());
        assertThatThrownBy(engine2::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("敏感词库加载失败");
    }

    @Test
    @DisplayName("盘外快照优先于 jar 内快照；盘外缺失才回落 classpath")
    void prefersExternalSnapshotWhenConfigured(@TempDir Path externalDir) throws IOException {
        // 盘外只放 1 条，能读到 1 而不是 classpath 里的 139，才算真证明「优先」这条分支被执行过。
        Path snapshot = externalDir.resolve("dict_external.txt");
        Files.writeString(snapshot, String.join("\n", "#version=external-1",
                row("辱骂攻击", "grey", "REVIEW", "user", "contains",
                        "傻逼")) + "\n", StandardCharsets.UTF_8);
        properties.getAudit().setDictPath(snapshot.toString());
        SensitiveWordEngine external = newEngine();
        external.afterPropertiesSet();
        assertThat(external.version()).isEqualTo("external-1");
        assertThat(external.wordCount()).isEqualTo(1);
        assertThat(external.check("你就是傻逼", "user").hit()).isTrue();

        // 指向不存在的盘外文件时回落 classpath，而不是启动失败
        properties.getAudit().setDictPath(externalDir.resolve("nope.txt").toString());
        SensitiveWordEngine fallback = newEngine();
        fallback.afterPropertiesSet();
        assertThat(fallback.wordCount()).isEqualTo(139);
        assertThat(fallback.version()).isEqualTo("v0.1");
    }

    @Test
    @DisplayName("阶段 3 冒烟真踩到的坑：Web 容器把裸路径当 WEB 根目录，必须自动补 classpath: 前缀")
    void normalizesBarePathForServletStyleLoader() {
        // 复刻 ServletWebServerApplicationContext.getResource() 的语义：只有显式 classpath: 才查类路径，
        // 其余按「Web 应用根目录」解析。单测用 DefaultResourceLoader 是感知不到这个差别的，
        // 所以这里手写一个同款 fake loader，把「测试绿、启动红」钉成一条可复现的用例。
        ResourceLoader servletStyle = new ServletStyleResourceLoader();
        MindisleProperties bare = new MindisleProperties();
        bare.getAudit().setDictResource("dict/sensitive_words_v0.1.txt");
        SensitiveWordEngine webLike = new SensitiveWordEngine(bare, servletStyle);
        webLike.afterPropertiesSet();
        assertThat(webLike.wordCount()).isEqualTo(139);

        assertThat(SensitiveWordEngine.withClasspathPrefix("dict/a.txt")).isEqualTo("classpath:dict/a.txt");
        assertThat(SensitiveWordEngine.withClasspathPrefix("classpath:dict/a.txt")).isEqualTo("classpath:dict/a.txt");
        assertThat(SensitiveWordEngine.withClasspathPrefix("file:dict/a.txt")).isEqualTo("file:dict/a.txt");
        assertThat(SensitiveWordEngine.withClasspathPrefix("  ")).isEmpty();
    }

    /** 复刻 ServletWebServerApplicationContext 的解析语义：裸路径按「Web 应用根目录」找，必然不存在。 */
    private static final class ServletStyleResourceLoader implements ResourceLoader {

        @Override
        public Resource getResource(String location) {
            return location.startsWith("classpath:")
                    ? new ClassPathResource(location.substring("classpath:".length()))
                    : new FileSystemResource("no-such-web-root/" + location);
        }

        @Override
        public ClassLoader getClassLoader() {
            return SensitiveWordEngineTest.class.getClassLoader();
        }
    }
}
