package com.mindisle.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 框架重试必须收敛在自建 SSE 预算之内（任务 T4.13，需求 FR2.8 / NFR2 / NFR12）。
 *
 * <p><b>这个文件为什么必须存在</b>：Spring AI 2.0.1 的 {@code spring.ai.retry} 默认是
 * {@code max-attempts=10}、{@code backoff.initial-interval=2s}、{@code multiplier=5}、
 * {@code max-interval} 无默认（= 退避不设顶）。真值取自本地仓库
 * spring-ai-autoconfigure-retry-2.0.1.jar 里的 spring-configuration-metadata.json。
 * 而本项目把 SSE 预算写死成 {@code mindisle.llm.sse-timeout-ms=30000}。两个数字各自都合理，
 * 叠在一起就是一个必然的 503：2026-09-24 在 8081 第二实例上做断网注入实测，上游端口不可达时
 * 框架退避到第 13 次仍未返回，30s 到点抛 {@code AsyncRequestTimeoutException}，结果
 * HTTP 503 / 0 帧 / {@code chat_message.degraded} 仍为 0 / 熔断器一次都没打开。
 * 修完之后同一脚本 4 轮全部 HTTP 200、每轮 3 帧（meta/delta/done(degraded=true)）、0.7~1.0s 上屏，
 * 第 3 轮起熔断生效。这条测试就是那道闸：谁把重试改回默认值，先在 CI 里红。</p>
 */
class SpringAiRetryBudgetTest {

    /** 退避总和最多只能吃掉 SSE 预算的三分之一，剩下要留给两次连接、落库和发帧。 */
    private static final double BACKOFF_BUDGET_RATIO = 1.0 / 3.0;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yml() throws Exception {
        try (var in = Files.newInputStream(resource("application.yml"))) {
            return (Map<String, Object>) new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> node, String key, String trail) {
        Object v = node == null ? null : node.get(key);
        assertTrue(v instanceof Map, () -> "application.yml 里缺配置节 " + trail + "." + key);
        return (Map<String, Object>) v;
    }

    private static Map<String, Object> retryNode() throws Exception {
        Map<String, Object> root = yml();
        Map<String, Object> ai = child(child(root, "spring", "spring"), "ai", "spring");
        return child(ai, "retry", "spring.ai");
    }

    @Test
    @DisplayName("max-attempts 必须显式写死且 <= 2（框架默认 10 会吃穿 SSE 预算）")
    void maxAttemptsIsExplicitlyBounded() throws Exception {
        Object v = retryNode().get("max-attempts");
        assertNotNull(v, "max-attempts 不能缺席：缺席 = 用回框架默认的 10 次");
        int attempts = Integer.parseInt(String.valueOf(v));
        assertTrue(attempts >= 1 && attempts <= 2,
                () -> "本项目自带「有限重试 + 熔断 + 离线话术」三层降级，框架层只留一次瞬断补偿，实测值: " + attempts);
    }

    @Test
    @DisplayName("backoff 逐项设限：initial<=1s、multiplier<=3、max-interval 必须显式在位")
    void backoffParametersAreIndividuallyBounded() throws Exception {
        Map<String, Object> backoff = child(retryNode(), "backoff", "spring.ai.retry");
        Object initial = backoff.get("initial-interval");
        assertNotNull(initial, "initial-interval 不能缺席（框架默认 2000ms）");
        assertTrue(millis(String.valueOf(initial)) <= 1000L,
                () -> "首次退避不该超过 1s，实测: " + initial);
        Object multiplier = backoff.get("multiplier");
        assertNotNull(multiplier, "multiplier 不能缺席（框架默认 5）");
        assertTrue(Double.parseDouble(String.valueOf(multiplier)) <= 3.0,
                () -> "退避倍数超过 3 就会在两次重试内把预算翻倍吃掉，实测: " + multiplier);
        assertNotNull(backoff.get("max-interval"),
                "max-interval 在框架里没有默认值 = 退避不设顶，必须显式写出");
    }

    @Test
    @DisplayName("退避总和必须落在 SSE 预算的三分之一以内，否则断网时永远是 503 而不是离线话术")
    void backoffSumFitsInsideSseBudget() throws Exception {
        Map<String, Object> root = yml();
        Map<String, Object> backoff = child(retryNode(), "backoff", "spring.ai.retry");
        int attempts = Integer.parseInt(String.valueOf(retryNode().get("max-attempts")));
        long initial = millis(String.valueOf(backoff.get("initial-interval")));
        double multiplier = Double.parseDouble(String.valueOf(backoff.get("multiplier")));
        long cap = millis(String.valueOf(backoff.get("max-interval")));
        long sseTimeout = sseTimeoutNode(root);
        assertTrue(sseTimeout > 0, "sse-timeout-ms 必须在位，否则这条不变式没有分母");

        long sum = 0L;
        double wait = initial;
        for (int i = 1; i < attempts; i++) {          // attempts 次调用之间只有 attempts-1 次退避
            sum += Math.min((long) wait, cap);
            wait *= multiplier;
        }
        long backoffSum = sum;
        long allowed = (long) (sseTimeout * BACKOFF_BUDGET_RATIO);
        assertTrue(backoffSum <= allowed,
                () -> "框架退避总和 " + backoffSum + "ms 超过 SSE 预算的三分之一（" + allowed
                        + "ms，预算共 " + sseTimeout + "ms）：断网时用户看到的是 503，"
                        + "recordFailure 也等不到返回，熔断器永远打不开");
    }

    @Test
    @DisplayName("401 这类客户端错误绝不重试（密钥写错时重试只是把预算烧光）")
    void doesNotRetryClientErrors() throws Exception {
        Object v = retryNode().get("on-client-errors");
        assertNotNull(v, "on-client-errors 要显式写出来，别靠框架默认值");
        assertTrue("false".equalsIgnoreCase(String.valueOf(v)), () -> "密钥错 = 配置错，不是瞬断；实测值: " + v);
    }

    @Test
    @DisplayName("retry 段上方的注释必须交代它与 SSE 预算/熔断的关系（防止下一个人调回默认值）")
    void budgetRationaleIsDocumented() throws Exception {
        String text = Files.readString(resource("application.yml")).replace("\r", "");
        assertTrue(text.contains("\n    retry:\n"), "spring.ai 段里必须还有缩进四格的 retry:");
        int at = text.indexOf("\n    retry:\n");
        String around = text.substring(Math.max(0, at - 1400), Math.min(text.length(), at + 700));
        assertTrue(around.contains("sse-timeout-ms"), "注释要写明 retry 和 SSE 预算的关系");
        assertTrue(around.contains("熔断") || around.contains("offline-empathy-bank"),
                "注释要写明本项目已有熔断/离线话术，框架重试只留一次补偿");
    }

    private static long sseTimeoutNode(Map<String, Object> root) {
        Map<String, Object> llm = child(child(root, "mindisle", "mindisle"), "llm", "mindisle");
        return Long.parseLong(String.valueOf(llm.get("sse-timeout-ms")));
    }

    /** Spring Boot 的 Duration 简写：本项目只用到 ms / s / 裸数字（毫秒）。 */
    private static long millis(String raw) {
        String s = raw.trim();
        if (s.endsWith("ms")) {
            return Long.parseLong(s.substring(0, s.length() - 2).trim());
        }
        if (s.endsWith("s")) {
            return Long.parseLong(s.substring(0, s.length() - 1).trim()) * 1000L;
        }
        return Long.parseLong(s);
    }

    /** surefire 的 basedir 一般是 backend/，从 IDE 里跑时退到上一层再找。 */
    private static Path resource(String name) {
        String tail = "src/main/resources/" + name;
        Path direct = Path.of(tail);
        if (Files.exists(direct)) {
            return direct;
        }
        Path up = Path.of("..", tail);
        if (Files.exists(up)) {
            return up;
        }
        throw new IllegalStateException("找不到 " + tail + "，工作目录是 " + Path.of("").toAbsolutePath());
    }
}
