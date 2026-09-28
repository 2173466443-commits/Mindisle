package com.mindisle.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 离线共情话术库（任务 T4.13 · 需求 FR2.6 的降级兜底）。
 *
 * <p><b>它存在的唯一理由是「降级也要像个陪伴」</b>：模型不可用时，如果回复变成
 * 「服务暂不可用，请稍后再试」，对一个正在说「我不想活了」的人来说是二次伤害。
 * 话术库让降级退到「有人接话」而不是「系统故障」。</p>
 *
 * <p>三条硬约束（缺一就变成自欺）：
 * ① 取到的句子<b>必须</b>在 {@code chat_message} 与响应里标成 degraded，界面上要能看出是离线模式；
 * ② 轮转而非随机 —— 随机会在演示时连着抽出同一句，看起来像 bug，轮转可复现；
 * ③ 话术库<b>不做</b>危机判定，危机走 T4.11 的词面通道，那条通道不依赖模型，
 *    所以「模型挂了导致危机没被识别」这个风险在本设计里不存在。</p>
 */
@Component
public class EmpathyBank {

    private static final Logger log = LoggerFactory.getLogger(EmpathyBank.class);
    private static final String RESOURCE = "dict/empathy_bank.json";
    private static final String FALLBACK_LABEL = "general";

    private final Map<String, List<String>> byLabel = new LinkedHashMap<>();
    private final AtomicLong cursor = new AtomicLong();
    private final String version;
    private final int total;

    public EmpathyBank(ObjectMapper mapper) {
        int loaded = 0;
        String parsedVersion = "empathy-unknown";
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            parsedVersion = root.path("version").asText(parsedVersion);
            for (JsonNode item : root.path("items")) {
                String label = item.path("label").asText(FALLBACK_LABEL).trim();
                String text = item.path("text").asText("").trim();
                if (text.isEmpty()) {
                    continue;
                }
                byLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(text);
                loaded++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("共情话术库读取失败：" + RESOURCE, e);
        }
        if (loaded == 0) {
            throw new IllegalStateException("共情话术库为空：" + RESOURCE);
        }
        if (!byLabel.containsKey(FALLBACK_LABEL) || byLabel.get(FALLBACK_LABEL).isEmpty()) {
            throw new IllegalStateException("共情话术库缺少 " + FALLBACK_LABEL + " 兜底组，无法保证降级必有话术");
        }
        this.version = parsedVersion;
        this.total = loaded;
        log.info("已装载共情话术库 version={} 共 {} 条，标签组 {}", version, loaded, byLabel.keySet());
    }

    /**
     * 按情绪标签取一句离线话术；该标签没有话术时落到 general 组。
     *
     * <p>label 传 null / 未知值都安全：直接落 general，不抛异常。
     * 降级路径上抛异常等于把「服务差」升级成「服务挂」。</p>
     */
    public String pick(String label) {
        List<String> pool = byLabel.get(label == null ? "" : label);
        if (pool == null || pool.isEmpty()) {
            pool = byLabel.get(FALLBACK_LABEL);
        }
        // 用全局游标取模实现轮转：跨标签也轮转，演示时不会连续同句
        int index = (int) Math.floorMod(cursor.getAndIncrement(), pool.size());
        return pool.get(index);
    }

    /** 该标签有没有专属话术（单测与埋点用）。 */
    public boolean hasLabel(String label) {
        return label != null && byLabel.containsKey(label);
    }

    public String version() {
        return version;
    }

    public int size() {
        return total;
    }

    public List<String> labels() {
        return Collections.unmodifiableList(new ArrayList<>(byLabel.keySet()));
    }
}
