package com.mindisle.emotion;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 实验产物的落盘目录（T4.15 两份产物共用一条解析规则，别再各写一份）。
 *
 * <p>为什么单独成类：{@code EmotionLatencyBenchmarkTest} 与 {@code EmotionChannelAgreementTest}
 * 都要写 {@code 论文材料/experiments/output/}。上一版这个路径解析是写在第一个测试里的私有方法，
 * 第二个测试照抄就会变成两份口径 —— 社区线那次「fmtHot 复制到第三份然后白屏」教训的同一形状。
 * 现在两边都调这里，路径口径只有一处可改。</p>
 */
final class ExperimentPaths {

    /** 用于从工作目录上溯定位项目根的标记文件：它在仓库里唯一，且就是本任务的手册。 */
    private static final String ROOT_MARKER = "制作步骤文档.md";

    private ExperimentPaths() {
    }

    /**
     * 返回 {@code <项目根>/论文材料/experiments/output}。
     * 从 {@code user.dir} 逐级上溯找 {@value #ROOT_MARKER}，这样 IDE 里跑与 {@code mvn} 里跑都能命中；
     * 找不到就退回 {@code <user.dir>/experiments/output}，绝不在测试里抛 NPE 把整批测试带崩。
     */
    static Path experimentOutputDir() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++) {
            if (Files.exists(dir.resolve(ROOT_MARKER))) {
                return dir.resolve("论文材料").resolve("experiments").resolve("output");
            }
            dir = dir.getParent();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().resolve("experiments").resolve("output");
    }
}
