package com.mindisle.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 提示词模板装载与渲染（任务 T4.1 的配套件，手册 §7.2「Prompt 全部外置」）。
 *
 * <p><b>为什么单独一个类而不是写在 ChatService 里</b>：论文要做 Prompt 消融，
 * 意味着同一个链路要能换不同版本的模板重跑。把「模板名 → 文本」这件事收在一个地方，
 * 换模板就只改配置，不动业务代码。</p>
 *
 * <p><b>渲染口径（写在文件头注释里，这里重申，因为两边必须一致）</b>：
 * ① 以 {@code #} 开头的 banner 注释行整行剥掉；
 * ② {@code --- PROMPT-BREAK ---} 之后是可变区，标记行本身剥掉；
 * ③ 占位符是 {@code {{name}}} 的<b>字面替换</b>，没有条件、没有循环、没有正则魔法；
 * ④ 缺失的占位符填「（无）」而<b>不是</b>留空或抛异常 ——
 * 留空会让模型读到一句语法断裂的话（"当前情绪：，强度 /5"），
 * 抛异常会让一次渲染失败把整条对话打断，两个后果都比「（无）」严重。</p>
 *
 * <p><b>可变区为什么永远排在最后</b>：DeepSeek 的前缀缓存按从头开始的相同 token 命中，
 * 可变内容往前挪一个字，整段缓存作废（需求 §12 第②招）。这条是文件注释与这里的双重声明，
 * 谁改模板谁负责 —— 所以 {@link #render} 不做任何重排，原样拼接。</p>
 */
@Component
public class PromptTemplate {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplate.class);

    /** 模板之间的分界标记（模板文件里以 {@code --- PROMPT-BREAK ---} 出现）。 */
    static final String BREAK = "--- PROMPT-BREAK ---";

    /** 缺失占位符的填充值，见类注释第 ④ 条。 */
    static final String MISSING = "（无）";

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 取模板正文（已剥注释与分界标记）。
     *
     * @param name 不带 .txt 后缀的文件名，如 {@code chat_default_v1}
     */
    public String body(String name) {
        return cache.computeIfAbsent(name, this::load);
    }

    /**
     * 渲染。占位符按 params 做字面替换，未提供的补「（无）」。
     *
     * <p>注意 params 为 null 的 value 也按缺失处理，免得 "null" 三个字母进提示词。</p>
     */
    public String render(String name, Map<String, ?> params) {
        String text = body(name);
        if (params == null || params.isEmpty()) {
            return fillStray(text);
        }
        Map<String, Object> safe = new LinkedHashMap<>(params);
        StringBuilder out = new StringBuilder(text.length() + 64);
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf("{{", i);
            if (open < 0) {
                out.append(text, i, text.length());
                break;
            }
            int close = text.indexOf("}}", open + 2);
            if (close < 0) {
                // 只有 "{{" 没有收尾，是模板写坏了；原样输出并告警，不吞字符
                log.warn("提示词模板 {} 有未闭合的 {{{{，位置 {}", name, open);
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, open);
            String key = text.substring(open + 2, close).trim();
            Object value = safe.get(key);
            out.append(value == null || String.valueOf(value).isBlank() ? MISSING : String.valueOf(value));
            i = close + 2;
        }
        return out.toString();
    }

    /** 模板里出现了不该出现的 {@code {{}}} 之外的花括号残留时不做处理，只兜住 params 为空的场景。 */
    private String fillStray(String text) {
        return text.replaceAll("\\{\\{\\s*[A-Za-z0-9_]+\\s*}}", MISSING);
    }

    private String load(String name) {
        String path = "prompts/" + name + ".txt";
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("提示词模板缺失：" + path);
        }
        try (InputStream in = resource.getInputStream()) {
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String stripped = stripComments(raw);
            log.info("已装载提示词模板 {}（正文 {} 字）", name, stripped.length());
            return stripped;
        } catch (IOException e) {
            throw new UncheckedIOException("读取提示词模板失败：" + path, e);
        }
    }

    /** 剥 banner 注释与分界标记行，保留正文里的空行结构（模型对换行敏感）。 */
    private static String stripComments(String raw) {
        String normalized = raw.replace("\r\n", "\n");
        StringBuilder sb = new StringBuilder(normalized.length());
        boolean firstKept = true;
        for (String line : normalized.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith(BREAK)) {
                continue;
            }
            if (firstKept && trimmed.isEmpty()) {
                continue;
            }
            firstKept = false;
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }
}
