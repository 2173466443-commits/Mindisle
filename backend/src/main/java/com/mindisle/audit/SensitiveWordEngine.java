package com.mindisle.audit;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.mindisle.audit.TextNormalizer.Normalized;
import com.mindisle.cache.CacheService;
import com.mindisle.config.MindisleProperties;

/**
 * DFA/Trie 敏感词引擎（任务 3.2 · 需求 FR7.1、§18.3 · 手册 §6.1 行 3.4）。
 *
 * <p><b>为什么先做它</b>：发帖、评论、私信、AI 输入四条链路全部依赖它，
 * 它是阶段 3 唯一「不需要真实数据库就能完整验证」的地基（需求 §14 排期提示）。</p>
 *
 * <p><b>匹配语义</b>：</p>
 * <ul>
 *   <li>{@code contains} 词条进 Trie，走<b>最长匹配且不重叠</b>——命中后游标直接跳到词尾，
 *       避免「赌博」与「赌博网站」同时命中导致一次发文产生两个工单；</li>
 *   <li>{@code regex} 词条（手机号、QQ、微信、身份证、银行卡，共 5 条）单独用 {@link Pattern}
 *       扫全文，<b>不参与归一化</b>：正则里的 {@code [0-9xX]} 含有连字符，
 *       若把模式串也送去删分隔符会直接把字符类改坏。这是本类最容易写错的地方。</li>
 * </ul>
 *
 * <p><b>作用侧（scope）</b>：需求 FR7.1 要求「AI 输出也要过审」，但「医疗越界词」只该拦模型
 * （用户问「我该吃什么药」是正当诉求），「辱骂攻击」只该拦用户（模型复述语境不等于模型骂人）。
 * 故 139 条词条带 scope ∈ {both,user,ai}，{@code check(text, side)} 只放行
 * {@code both} 与当前侧。</p>
 *
 * <p><b>主因选择</b>：一次提交可能同时命中多组，返回体的 {@code category/level/action}
 * 取<b>处置最重</b>的那条（BLOCK &gt; REVIEW &gt; TAG），而不是最先命中的那条。
 * 理由见需求 §18.3：风险词组（自伤自杀）语义是「放行 + 打标记 + 出工单」，
 * 若按命中顺序取主因，一条既骂人又流露自伤念头的文本有可能被判成 TAG 而没人跟进。</p>
 *
 * <p><b>热更新</b>：词库快照文件带 {@code #version=}，管理端（T6.2）改词后把新版本写进缓存键
 * {@code mindisle.audit.dict-version-key}（默认 {@code dict:version}）；
 * 本引擎在每次 check 前用 {@link #refreshIfStale} 比对，节流间隔
 * {@code mindisle.audit.dict-check-millis}。<b>诚实边界</b>：当前词库来源是打包进 jar 的
 * classpath 快照（jar 内文件运行时改不了），因此只有把
 * {@code mindisle.audit.dict-path} 指向日志盘上的可写快照时热更新才真正生效；
 * 在 {@code cache.mode=local} 下缓存本身也是单机的。这条链路等 T6.2 的
 * 「DB→快照文件」导出脚本接上，届时不改本类。</p>
 *
 * <p><b>失败姿态</b>：词库缺失或某条正则语法错误时<b>直接让应用启动失败</b>，
 * 不降级成「无词库全放行」——一个静默失灵的审核引擎比一个起不来的服务危险得多（需求 §11.2 的降级口只针对缓存与模型）。</p>
 */
@Component
public class SensitiveWordEngine implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordEngine.class);

    /** 匹配型取值，与 sensitive_word.match_type 的 ENUM 一致。 */
    private static final String MATCH_REGEX = "regex";
    /** 词库行数上限，防止把整张表误当文件读进来（当前快照 145 行）。 */
    private static final int MAX_DICT_LINES = 20_000;
    private static final int COLUMN_COUNT = 6;

    /**
     * 一条命中。
     *
     * @param start 原文（非归一化串）起始下标，前端可直接 substring
     * @param end   原文结束下标，左闭右开
     */
    public record Hit(String word, String category, String level, String action, String scope, int start, int end) {
    }

    /**
     * 检测结果（手册 §6.2 U5「敏感词实时提醒」的响应体）。
     *
     * @param hit      是否命中
     * @param category 主因分组，未命中为 null
     * @param level    主因层级 black|grey|risk
     * @param action   主因处置 BLOCK|REVIEW|TAG
     * @param hitCount 命中总条数
     * @param hits     全部命中，按处置严重度、再按原文位置排序
     * @param positions 与 hits 同序的原文区间，便于前端只画高亮不读词面
     * @param dictVersion 本次使用的词库版本，供前端判断「提醒比发帖时新」
     */
    public record CheckResult(boolean hit, String category, String level, String action, int hitCount,
            List<Hit> hits, List<int[]> positions, String dictVersion) {
    }

    /** 去重键：同一匹配型 + 同一作用侧 + 同一词面只保留第一次出现。 */
    private record DedupeKey(String matchType, String scope, String word) {
    }

    /** 一条已编译词条。 */
    private static final class Rule {
        private final String word;
        private final String category;
        private final String level;
        private final String action;
        private final String scope;
        /** 仅 regex 词条非空。 */
        private final Pattern pattern;

        private Rule(String word, String category, String level, String action, String scope, Pattern pattern) {
            this.word = word;
            this.category = category;
            this.level = level;
            this.action = action;
            this.scope = scope;
            this.pattern = pattern;
        }

        private boolean allows(String side) {
            return "both".equals(scope) || scope.equals(side);
        }
    }

    /** Trie 节点：children 在构建期可变、发布后只读（靠 volatile 字段发布，读侧无需加锁）。 */
    private static final class Node {
        private final Map<Integer, Node> children = new HashMap<>(2);
        private int ruleIndex = -1;
    }

    /** 不可变词库快照，整体替换实现无锁热更新。 */
    private static final class Dictionary {
        private final String version;
        private final List<Rule> rules;
        private final Node root;

        private Dictionary(String version, List<Rule> rules, Node root) {
            this.version = version;
            this.rules = rules;
            this.root = root;
        }
    }

    private static final Dictionary EMPTY = new Dictionary("(未加载)", List.of(), new Node());

    private final MindisleProperties properties;
    private final ResourceLoader resourceLoader;

    private volatile Dictionary dictionary = EMPTY;
    private volatile long lastVersionCheckAt;

    public SensitiveWordEngine(MindisleProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            reload(readSnapshot());
        } catch (IOException | IllegalStateException e) {
            throw new IllegalStateException("敏感词库加载失败，拒绝以「无词库」状态启动：" + e.getMessage(), e);
        }
    }

    /**
     * 用新的快照文本替换词库。
     *
     * @return 新词库版本号
     * @throws IllegalStateException 列数不符、正则非法或一条有效词条都没有
     */
    public synchronized String reload(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("词库内容为空");
        }
        String[] lines = content.split("\\R");
        if (lines.length > MAX_DICT_LINES) {
            throw new IllegalStateException("词库行数 " + lines.length + " 超过上限 " + MAX_DICT_LINES);
        }
        String version = "unknown";
        List<Rule> rules = new ArrayList<>();
        Map<DedupeKey, Integer> seen = new HashMap<>();
        Node root = new Node();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("#")) {
                if (line.startsWith("#version=")) {
                    version = line.substring("#version=".length()).trim();
                }
                continue;
            }
            String[] cols = line.split("\t", -1);
            if (cols.length != COLUMN_COUNT) {
                throw new IllegalStateException("词库第 " + (i + 1) + " 行列数为 " + cols.length + "，应为 " + COLUMN_COUNT);
            }
            String category = cols[0].trim();
            String level = cols[1].trim();
            String action = cols[2].trim().toUpperCase();
            String scope = cols[3].trim().toLowerCase();
            String matchType = cols[4].trim().toLowerCase();
            String word = cols[5].trim();
            Pattern pattern = null;
            if (MATCH_REGEX.equals(matchType)) {
                // 正则原样编译，绝不归一化（字符类里的 '-' 会被当分隔符删掉）。
                try {
                    pattern = Pattern.compile(word);
                } catch (PatternSyntaxException e) {
                    throw new IllegalStateException("词库第 " + (i + 1) + " 行正则非法：" + word, e);
                }
            } else {
                word = TextNormalizer.normalize(word).text();
                if (word.isEmpty()) {
                    log.warn("敏感词第 {} 行归一化后为空，已跳过：{}", i + 1, cols[5]);
                    continue;
                }
            }
            DedupeKey dedupeKey = new DedupeKey(matchType, scope, word);
            if (seen.containsKey(dedupeKey)) {
                continue;
            }
            seen.put(dedupeKey, rules.size());
            Rule rule = new Rule(word, category, level, action, scope, pattern);
            int ruleIndex = rules.size();
            rules.add(rule);
            if (pattern == null) {
                insert(root, word, ruleIndex);
            }
        }
        if (rules.isEmpty()) {
            throw new IllegalStateException("词库解析后没有任何有效词条");
        }
        Dictionary parsed = new Dictionary(version, List.copyOf(rules), root);
        this.dictionary = parsed;
        log.info("敏感词库已加载：version={} 词条={} 其中 regex={}", version, parsed.rules.size(),
                parsed.rules.stream().filter(r -> r.pattern != null).count());
        return version;
    }

    /** 检测（用户侧）。发帖、评论、私信一律用它。 */
    public CheckResult check(String text) {
        return check(text, "user");
    }

    /**
     * 检测。
     *
     * @param side user（用户输入）或 ai（模型输出）；null 按 user 处理
     */
    public CheckResult check(String text, String side) {
        Dictionary current = this.dictionary;
        String wantedSide = side == null || side.isBlank() ? "user" : side.trim().toLowerCase();
        if (text == null || text.isBlank() || current.root.children.isEmpty() && current.rules.isEmpty()) {
            return new CheckResult(false, null, null, null, 0, List.of(), List.of(), current.version);
        }
        Normalized normalized = TextNormalizer.normalize(text);
        String folded = normalized.text();
        if (folded.isEmpty()) {
            return new CheckResult(false, null, null, null, 0, List.of(), List.of(), current.version);
        }
        List<Hit> hits = new ArrayList<>();
        scanTrie(current, normalized, wantedSide, hits);
        scanRegex(current, normalized, wantedSide, hits);
        hits.sort(Comparator.comparingInt((Hit hit) -> actionRank(hit.action())).thenComparingInt(Hit::start));
        List<int[]> positions = new ArrayList<>(hits.size());
        for (Hit hit : hits) {
            positions.add(new int[] {hit.start(), hit.end()});
        }
        Hit primary = hits.isEmpty() ? null : hits.get(0);
        return new CheckResult(!hits.isEmpty(),
                primary == null ? null : primary.category(),
                primary == null ? null : primary.level(),
                primary == null ? null : primary.action(),
                hits.size(), List.copyOf(hits), positions, current.version);
    }

    /** 当前词库版本，写 audit_task 时随快照一起留痕（需求 FR7.2「可复核」）。 */
    public String version() {
        return dictionary.version;
    }

    /** 词条总数，/api/system/info 与管理端词库页用来核对「加载的是不是同一份」。 */
    public int wordCount() {
        return dictionary.rules.size();
    }

    /**
     * 缓存里版本号变了才重载，且按 dict-check-millis 节流。
     *
     * <p>{@code dict-check-millis} 的三种取值：{@code 0} 表示每次检测前都比对（不节流，单测与
     * 本地调试用），{@code > 0} 表示最小比对间隔，<b>负数</b>才表示关闭热更新检查。</p>
     *
     * <p>缓存读失败绝不影响本次检测：审核链路的可用性优先于「立刻知道词库更新了」。</p>
     */
    public void refreshIfStale(CacheService cache, String cacheKey) {
        MindisleProperties.Audit audit = properties.getAudit();
        long interval = audit.getDictCheckMillis();
        if (cache == null || interval < 0 || cacheKey == null || cacheKey.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastVersionCheckAt < interval) {
            return;
        }
        lastVersionCheckAt = now;
        String remote;
        try {
            remote = cache.get(cacheKey, String.class);
        } catch (RuntimeException e) {
            log.warn("读取词库版本失败，本次沿用内存词库 {}：{}", dictionary.version, e.toString());
            return;
        }
        if (remote == null || remote.isBlank() || remote.equals(dictionary.version) || remote.equals(version())) {
            return;
        }
        log.info("检测到词库版本变化 {} -> {}，准备重载", dictionary.version, remote);
        try {
            reload(readSnapshot());
        } catch (IOException | IllegalStateException e) {
            log.error("词库热更新失败，继续使用 {}：{}", dictionary.version, e.getMessage());
        }
    }

    private String readSnapshot() throws IOException {
        MindisleProperties.Audit audit = properties.getAudit();
        Resource resource = resolve(audit);
        if (!resource.exists()) {
            throw new IOException("词库文件不存在：" + resource);
        }
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            StringBuilder all = new StringBuilder(8 * 1024);
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                all.append(buffer, 0, read);
            }
            return all.toString();
        }
    }

    /** 可写的盘外快照优先（热更新用），没配或不存在才回落 jar 内快照。 */
    private Resource resolve(MindisleProperties.Audit audit) {
        String external = audit.getDictPath();
        if (external != null && !external.isBlank()) {
            Resource resource = resourceLoader.getResource("file:" + external);
            if (resource.exists()) {
                return resource;
            }
            log.warn("盘外词库不存在，回落 classpath 快照：{} -> {}", external, audit.getDictResource());
        }
        return resourceLoader.getResource(withClasspathPrefix(audit.getDictResource()));
    }

    /**
     * 没写前缀的内置词库位置按 classpath 解析。
     *
     * <p>阶段 3 冒烟实测：Spring 注入给 Bean 的 {@link ResourceLoader} 是 Web 容器上下文，
     * 它对 {@code dict/xxx.txt} 这种裸路径走 {@code ServletContext resource [/dict/xxx.txt]}，
     * 打 jar 运行必然「文件不存在」而单测（用 DefaultResourceLoader）却全绿——
     * 这类「测试绿、线上红」只能靠显式前缀 + 这里一次归一化兜住。</p>
     */
    static String withClasspathPrefix(String location) {
        String trimmed = location == null ? "" : location.trim();
        if (trimmed.isEmpty()
                || trimmed.startsWith("/")           // 已由容器按绝对/WEB 根路径解析
                || trimmed.startsWith("classpath:")
                || trimmed.startsWith("file:")       // 盘外文件请配 dict-path，而不是 dict-resource
                || trimmed.startsWith("jar:")
                || trimmed.startsWith("http:")
                || trimmed.startsWith("https:")) {
            return trimmed;
        }
        return "classpath:" + trimmed;
    }

    private static void insert(Node root, String word, int ruleIndex) {
        Node node = root;
        int cursor = 0;
        while (cursor < word.length()) {
            int cp = word.codePointAt(cursor);
            node = node.children.computeIfAbsent(cp, key -> new Node());
            cursor += Character.charCount(cp);
        }
        node.ruleIndex = ruleIndex;
    }

    private static void scanTrie(Dictionary dict, Normalized normalized, String side, List<Hit> hits) {
        String folded = normalized.text();
        int cursor = 0;
        while (cursor < folded.length()) {
            Node node = dict.root;
            int matchedRule = -1;
            int matchedEnd = -1;
            int probe = cursor;
            while (probe < folded.length()) {
                int cp = folded.codePointAt(probe);
                node = node.children.get(cp);
                if (node == null) {
                    break;
                }
                if (node.ruleIndex >= 0 && dict.rules.get(node.ruleIndex).allows(side)) {
                    matchedRule = node.ruleIndex;
                    matchedEnd = probe + Character.charCount(cp);
                }
                probe += Character.charCount(cp);
            }
            if (matchedRule >= 0) {
                hits.add(hitOf(dict, matchedRule, normalized, cursor, matchedEnd));
                // 最长匹配且不重叠：整段跳过，被跳过的字不再作为新起点。
                cursor = matchedEnd;
            } else {
                cursor += Character.charCount(folded.codePointAt(cursor));
            }
        }
    }

    private static void scanRegex(Dictionary dict, Normalized normalized, String side, List<Hit> hits) {
        String folded = normalized.text();
        for (int index = 0; index < dict.rules.size(); index++) {
            Rule rule = dict.rules.get(index);
            if (rule.pattern == null || !rule.allows(side)) {
                continue;
            }
            Matcher matcher = rule.pattern.matcher(folded);
            while (matcher.find()) {
                if (matcher.end() <= matcher.start()) {
                    break; // 防空转匹配（本项目 5 条正则都不会，留着是防止以后加词踩坑）
                }
                hits.add(hitOf(dict, index, normalized, matcher.start(), matcher.end()));
            }
        }
    }

    private static Hit hitOf(Dictionary dict, int ruleIndex, Normalized normalized, int foldStart, int foldEnd) {
        Rule rule = dict.rules.get(ruleIndex);
        int[] range = normalized.toRawRange(foldStart, foldEnd);
        return new Hit(rule.word, rule.category, rule.level, rule.action, rule.scope, range[0], range[1]);
    }

    /** 处置严重度：BLOCK 硬拦 &gt; REVIEW 进人审 &gt; TAG 只打标记。 */
    private static int actionRank(String action) {
        return switch (action == null ? "" : action.toUpperCase()) {
            case "BLOCK" -> 0;
            case "REVIEW" -> 1;
            case "TAG" -> 2;
            default -> 3;
        };
    }
}
