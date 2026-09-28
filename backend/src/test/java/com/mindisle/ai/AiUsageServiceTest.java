package com.mindisle.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AiCallLog;
import com.mindisle.mapper.AiCallLogMapper;

/**
 * 降级记账的 model 字段守卫（任务 T4.13，需求 FR2.6 / FR2.7）。
 *
 * <p><b>这个文件为什么必须存在</b>：v1.2.5 之前，{@code ai_call_log} 里有 13 行
 * {@code model=offline-empathy-bank}，被当成「离线降级路径真被走过」的证据。实际那 13 行
 * 全部来自「用户按停止生成」—— {@code recordDegraded} 里有一行无条件写死的 model，
 * 而 cancel / 真降级 / 周报模板三个语义不同的调用方共用它。反证是业务表
 * {@code chat_message} 全表 {@code SUM(degraded=1)=0}。修完之后，model 由调用方传，
 * 这几条断言就是那道「一个值只能对应一种语义」的闸。</p>
 */
class AiUsageServiceTest {

    /** 只拦 insert，其余方法返回默认值；仓库里的测试统一不引 mock 框架。 */
    private static final class Recorder {
        final List<AiCallLog> rows = new ArrayList<>();

        AiCallLogMapper mapper() {
            return (AiCallLogMapper) Proxy.newProxyInstance(
                    AiCallLogMapper.class.getClassLoader(),
                    new Class<?>[] { AiCallLogMapper.class },
                    (proxy, method, args) -> {
                        if ("insert".equals(method.getName()) && args != null && args.length == 1
                                && args[0] instanceof AiCallLog row) {
                            rows.add(row);
                            return 1;
                        }
                        Class<?> t = method.getReturnType();
                        if (t == int.class || t == Integer.class) {
                            return 0;
                        }
                        if (t == long.class || t == Long.class) {
                            return 0L;
                        }
                        if (t == boolean.class || t == Boolean.class) {
                            return false;
                        }
                        if (t == List.class) {
                            return new ArrayList<>();
                        }
                        return null;
                    });
        }
    }

    private static AiUsageService service(Recorder rec) {
        return new AiUsageService(rec.mapper(), new MindisleProperties());
    }

    @Test
    @DisplayName("用户主动停止：model 记真实模型，绝不冒充离线话术库")
    void cancelKeepsRealModelLabel() {
        Recorder rec = new Recorder();
        service(rec).recordDegraded(7L, "chat", "chat_default_v1", "deepseek-flash",
                "interrupted:client-cancel", "trace-cancel");
        assertEquals(1, rec.rows.size(), "应当且只应当落一行");
        AiCallLog row = rec.rows.get(0);
        assertNotEquals(AiUsageService.MODEL_OFFLINE_BANK, row.getModel(),
                "cancel 记成 offline-empathy-bank 就是 v1.2.5 那个假证据的成因");
        assertEquals("deepseek-flash", row.getModel());
        assertEquals(0, row.getSuccess().intValue(), "降级行永远不算成功");
        assertTrue(row.getError().startsWith("DEGRADED:"), () -> "error 要带前缀: " + row.getError());
        assertTrue(row.getError().contains("interrupted:client-cancel"), row::getError);
    }

    @Test
    @DisplayName("真降级到话术库：才配用 offline-empathy-bank 这个标签")
    void offlineBankLabelIsStillAvailable() {
        Recorder rec = new Recorder();
        service(rec).recordDegraded(7L, "chat", "chat_default_v1", AiUsageService.MODEL_OFFLINE_BANK,
                "circuit-open", "trace-offline");
        AiCallLog row = rec.rows.get(0);
        assertEquals(AiUsageService.MODEL_OFFLINE_BANK, row.getModel());
        assertEquals("chat", row.getScene());
        assertEquals(0, row.getTokensIn().intValue(), "没调模型，token 必须是 0");
        assertEquals(0, row.getCostCent().intValue(), "没调模型，成本必须是 0");
    }

    @Test
    @DisplayName("周报走统计模板：用第三个标签，不复用前两个")
    void reportTemplateHasItsOwnLabel() {
        Recorder rec = new Recorder();
        service(rec).recordDegraded(7L, "report", "weekly_report_v1", AiUsageService.MODEL_STAT_TEMPLATE,
                "code=42901", null);
        AiCallLog row = rec.rows.get(0);
        assertEquals(AiUsageService.MODEL_STAT_TEMPLATE, row.getModel());
        assertNotEquals(AiUsageService.MODEL_OFFLINE_BANK, row.getModel(),
                "周报模板与共情话术库是两回事");
        assertEquals("report", row.getScene());
    }

    @Test
    @DisplayName("model 传空 / 传空白：落成 degraded-unknown，让疏漏在报表里显形")
    void missingModelBecomesUnknown() {
        Recorder rec = new Recorder();
        AiUsageService svc = service(rec);
        svc.recordDegraded(7L, "chat", "chat_default_v1", null, "no-model", "t1");
        svc.recordDegraded(7L, "chat", "chat_default_v1", "   ", "no-model", "t2");
        svc.recordDegraded(7L, "chat", "chat_default_v1", "  deepseek-flash  ", "padded", "t3");
        assertEquals(AiUsageService.MODEL_UNKNOWN, rec.rows.get(0).getModel());
        assertEquals(AiUsageService.MODEL_UNKNOWN, rec.rows.get(1).getModel());
        assertNotEquals(AiUsageService.MODEL_OFFLINE_BANK, rec.rows.get(0).getModel(),
                "缺参数也不能顺手冒充话术库");
        assertEquals("deepseek-flash", rec.rows.get(2).getModel(), "前后空白要被吃掉");
    }

    @Test
    @DisplayName("调用点检查：ChatService 的两处 recordDegraded 传的必须是不同标签")
    void chatServiceCallSitesPassDistinctLabels() throws Exception {
        Path p = sourceOf("ai/ChatService.java");
        String text = Files.readString(p).replace("\r", " ");
        int first = text.indexOf("recordDegraded(userId, request.scene()");
        int second = text.indexOf("recordDegraded(userId, \"chat\"");
        assertTrue(first > 0 && second > 0, "两处调用点都还在，改签名时别只改一处");
        String cancelCall = text.substring(first, text.indexOf(";", first));
        String offlineCall = text.substring(second, text.indexOf(";", second));
        assertTrue(cancelCall.contains("modelLabel()"),
                () -> "cancel 那处要传真实模型标签: " + cancelCall);
        assertTrue(offlineCall.contains("MODEL_OFFLINE_BANK"),
                () -> "deliverOffline 那处才用话术库标签: " + offlineCall);
        assertTrue(!cancelCall.contains("MODEL_OFFLINE_BANK"),
                "两处的标签不能又合流回同一个值");
    }

    /** surefire 的 basedir 一般是 backend/，从 IDE 里跑时退到上一层再找。 */
    private static Path sourceOf(String relative) {
        String tail = "src/main/java/com/mindisle/" + relative;
        Path direct = Path.of(tail);
        if (Files.exists(direct)) {
            return direct;
        }
        Path up = Path.of("..", tail);
        if (Files.exists(up)) {
            return up;
        }
        throw new IllegalStateException("找不到源文件 " + tail + "，工作目录是 " + Path.of("").toAbsolutePath());
    }
}