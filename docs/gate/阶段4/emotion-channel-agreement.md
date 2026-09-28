# T4.15 情绪通道占比与一致率 + 词典时延压测（阶段 4 第三条取证线）

任务：手册 §7.2 的 T4.15「词典通道 vs LLM 通道 vs 用户自评的一致率 + 单条时延」。
本页只登记**读数、口径和证伪**，数字一律取自仓库里那两份产物文件与后端断言，不做任何手算。

## 0. 一句话结论

线上 135 行情绪记录里 **63.70% 由 LLM 通道写、26.67% 由词典通道写、9.63% 是用户自己打卡选的**；
把词典重跑在有文本的 73 行上，**外部一致率 13/53 = 24.53%**（llm 档 11/40 = 27.50%、manual 档 2/13 = 15.38%）；
词典单条耗时 **P95 = 0.10ms**（判据 ≤ 50ms），吞吐 24042 条/秒。
**这两个数合起来的含义是：词典不是分类器，是第一道便宜的筛子——它对 71.23% 的线上真实短句自己说「我不确定」并交棒 LLM。**

## 1. 两份产物与生成环境

| 产物 | 来源测试类 | 本轮生成时间 | 大小 |
|---|---|---|---|
| 论文材料/experiments/output/emotion_channel_agreement.txt | backend/src/test/java/com/mindisle/emotion/EmotionChannelAgreementTest.java | 2026-09-28 19:13:45 | 4,061 B / 49 行 |
| 论文材料/experiments/output/emotion_latency.txt | backend/src/test/java/com/mindisle/emotion/EmotionLatencyBenchmarkTest.java | 2026-09-28 19:13:46 | 1,507 B |

- JDK = 17.0.19（OpenJDK 64-Bit Server VM）；词典模型版本 = 「dut-4.0-mindisle-v1+prior-v1.0」。
- ⚠️ 这两份文件**每次「mvn -o -B test」都会被重写**。所以引用它们的任何数字之前，先看文件头那行「生成时间」，
  别拿上一轮摘要里的读数当现值——本轮就发生过一次：上一轮记的 P95 0.08ms 被 19:13 这次重跑改成了 0.10ms。

## 2. 数据来源与三条口径红线

唯一数据来源是「backend/src/test/resources/emotion/emotion_record_snapshot.tsv」（142 行 = 6 行注释 + 1 行列名 + **135 行数据**），
它是从**真库导出的现状**，不是新造的标注集：

- 导出 SQL（写在文件头注释里，逐字可查）：
  「SELECT id,channel,label,confidence,source,REPLACE(REPLACE(REPLACE(text_snippet,0x0A," "),0x0D," "),0x09," ") AS snip,model_version,record_date,deleted FROM emotion_record ORDER BY id」
- 导出时间 2026-09-28 18:24（Asia/Shanghai），root 直连 mindisle 库，「--default-character-set=utf8mb4 -B」。
- 红线①：**135 行里有 62 行没有文本**（打卡只选情绪、没写字），所以一致率的分母只有 73 行。这条必须印在产物里，不然读者会拿 135 当分母。
- 红线②：文本清洗只把 CR / LF / TAB 各替换成一个空格，其余逐字保留（含用户自己打的标点与 emoji）。列序被解析器逐字校验，改一个列名就会整份错位（见 §6 R2b）。
- 红线③：**这 135 行几乎全部来自冒烟与 DOM 探针**，探针账号占比很高。演示数据重播之后必须重导一次，否则数字跟着夹具漂。

## 3. 通道占比与标签分布（产物第一、二节原文）

| 通道 | 行数 | 占比 | 其中无文本 |
|---|---|---|---|
| llm | 86 | 63.70% | 46 行（53.49%） |
| dict | 36 | 26.67% | 16 行（44.44%） |
| manual | 13 | 9.63% | 0 行（0.00%） |
| 合计 | 135 | 100.00% | — |

守恒校验：三通道相加 = 135 = 快照总行数（产物里逐字印着这一行，测试也断言它）。

落库标签分布（**这是「线上看起来是什么样」，不是词典的成绩**）：neutral 70（51.85%）/ sadness 36（26.67%）/ fear 18（13.33%）/ joy 6（4.44%）/ anger 5（3.70%）。

## 4. 三个一致率，只有一个是能拿去答辩的

| 口径 | 数值 | 能不能引用 |
|---|---|---|
| 原始一致率（逐字比标签） | 33/73 = 45.21% | 只作守恒线，别当结论 |
| 总体有效一致率（剔除词典零命中撞车，含 dict 自比 20 条） | 33/73 = 45.21% | 同上 |
| **外部一致率（llm + manual，剔除空值撞车 0 条）** | **13/53 = 24.53%** | ✅ 答辩与论文引用这一行 |
| dict 自比 | 20/20 = 100.00% | 只证明「快照与词典版本没串位」，**不是准确率** |
| llm 档 | 11/40 = 27.50% | 参照物是另一个模型，它自己也会错 |
| manual 档 | 2/13 = 15.38% | 参照物是用户本人，最接近自评标注的一档 |

词典在可比样本上的平均置信度 = **0.43**（低于送兜底阈值 0.55，与 §5 的 needsLlm 比例自洽）。

产物第四节还**原样抄了 5 条 llm 不一致样本**供人工复核，而不是只留一个百分比：
「真实，真实，哈哈」库=neutral 词典=trust conf=0.628 /「恶心」库=fear 词典=disgust conf=0.513（两条）/「安全」库=fear 词典=neutral conf=0.513 /「失眠」库=fear 词典=sadness conf=0.513。

## 5. 兜底比例与词典时延（两份产物对着读）

| 项 | 线上真实短句 | 合成压测语料 |
|---|---|---|
| needsLlm（conf < 0.55 交棒 LLM） | 52/73 = **71.23%** | 8846/10000 = **88.46%** |

合成语料是把互不相干的词面粘在一起，类别一致率天然偏低 ⇒ 它对压测反而是好事：每条都走满词典匹配 + 四类修正 + 置信度计算，是词典通道的**最坏路径**。

时延现值（10000 条正式样本，种子 20260928 固定 ⇒ 同一万条文本可逐条复现；词面样本池 31,291 条；命中 9999/10000 = 99.99%）：

- 单条耗时 **P50 0.03ms / P90 0.07ms / P95 0.10ms / P99 0.20ms / max 5.22ms**，平均 0.04ms；
- 整批墙钟 **415.94ms**，吞吐 **24042 条/秒**；文本平均 64 字，最长 190 字；热身 1000 条不计入判据；
- 判据 **P95 ≤ 50ms ⇒ 达标**；口径 = 计时只包住 DictEmotionEngine.analyze，不含 DB 与网络，分位数按最近邻法（升序第 ceil(p*n) 个）取。

## 6. 五条判据与七次证伪（断言不是装饰）

判据常量（EmotionChannelAgreementTest.java，行号为现查）：
「MIN_ROWS = 100」(L51) / 「MIN_COMPARABLE = 60」(L54) / 「EFFECTIVE_AGREEMENT_FLOOR_PCT = 40.00」(L62) /
「EXTERNAL_AGREEMENT_FLOOR_PCT = 20.00」(L74) / 「MIN_EXTERNAL_COMPARABLE = 30」(L77)；
外加一条守恒线：**dict 自比必须 100%**（L320 附近）。

「新写的断言必须先证伪」是本项目第 13 轮立的规矩，所以这七条都是一一故意改坏、看它真的变红、再还原的。
每条都摘自仓库外的日志原文（「_cache/009_mindisle/mvn_t415b_falsifyR*.log」）：

| 编号 | 故意改坏的地方 | 报出来的红（日志原文，未改写） |
|---|---|---|
| R1 | 外部一致率下限 20.00 → 26.00 | 「外部一致率 24.53% 低于下限 26.00%：词典通道对线上真实文本已退化到没有可对照的信号，应当先修词典而不是先写论文」 |
| R2 | 快照列名大小写改动（source → SOURCE） | 见 R2b |
| R2b | 列名字面被改 | 「文件头写的列序与解析器假定的列序必须一致，否则整份占比都是错位读出来的 ==> expected: <id channel label confidence SOURCE snip ...> but was: <id channel label confidence source snip ...>」 |
| R3 | 词典预测通道被改成不可能命中的标签 | 「dict 通道的标签本来就是词典写的，重跑却对不上 20 条 ⇒ 快照版本与词典版本串了位，这份报告的整体口径作废 ==> expected: <20> but was: <0>」 |
| R4 | MIN_COMPARABLE 60 → 80 | 「可比样本只有 73 条（快照里 62 条没有文本），一致率的分母太小，报出来的是噪声不是结论」 |
| R5 | 列数常量被改（9 → 8） | 「有 135 行列数不等于 9：多半是文本里夹了制表符或换行，快照该重新导出 ==> expected: <0> but was: <135>」 |
| R6 | 外部分母那条判据的比较方向被改反 | 「外部可比样本只有 53 条，低于下限 30：这个分母报出来的百分比是噪声 ⇒ 53 明明 ≥ 30 却判红，说明判据本身也是会被写反的」 |
| R7 | 通道守恒数被 +1 | 「可比样本没有逐条落进三个通道之一（73 vs 73）：分通道统计与总一致率对不上同一批行 ==> expected: <74> but was: <73>」 |

还原之后「mvn -o -B test」全量：**Tests run 529 / Failures 0 / Errors 0 / Skipped 1 / BUILD SUCCESS**（09-28 19:13:48，日志「_cache/009_mindisle/mvn_r24_final.log」）。

## 7. 🔴 这份产物不能用来主张什么（产物第六节原文，逐条抄）

1. **不是 §7.3 的 300 条人工标注集**：没有第二人独立标注 ⇒ 没有 Cohen's Kappa ⇒ 没有 macro-F1。
2. **一致率不等于准确率**：llm 那档的参照物是另一个模型，它自己也会错；只有 manual 那档的参照物是用户本人。
3. **快照是某一刻的线上现状**，探针账号在里面占比很高 ⇒ 演示数据重播之后必须重导一次。

⇒ 手册 §7.3 那三行（300 条标注集 / Kappa / macro-F1）**至今仍然没做，不签**（详见手册 §7.2 收工口径与本页 §2 红线③）。

## 8. 通道这件事在前端与库里长什么样（本轮读代码 + 实测订正，手册 T4.8 的旧口径据此改写）

- SSE 的「meta」帧里，「emotion」是**标签字符串**（不是 {label:...} 对象），「riskLevel」是**整数 0**；
  而 REST 回看接口「MessageView.riskLevel」是**字符串 "L0"**。两处不是同一个类型，别写成一个。
- meta 帧那一刻带的是**词典初判**，前端气泡此后**不刷新**（回复正文也刻意不改，见 ChatService javadoc L761–764）。
- 🔴 但**库里是真的会被回写的**：ChatService.relabel()（L782–821）把 LLM 的补标结果写回
  「chat_message」的 emotion_label / emotion_score / emotion_channel="llm"（L796–801），
  并写回「emotion_record」的 label / intensity / valence / confidence / channel="llm" / model_version（L803–811）。
  所以手册 T4.8 旧口径那句「补标结果不回改本轮 chat_message.emotion_label」**是错的，本轮已订正**。
  代价与理由也写进代码注释了：emotion_record.channel 改成 llm 是消融实验的分组键，代价是覆盖词典原结论、而日志里留着旧值。
- 实测一次（会话 120，账号 d1_gate）：meta 报「neutral」→ 同一条消息在库里回读为 **fear / llm**。
- 情绪标签**钉在用户那一句上**；助手行没有 emotionLabel / emotionChannel（Jackson NON_NULL ⇒ 键整个缺席），
  但带 model 与 promptVersion。
- 「emotion_record.source」的真值只有 **「checkin」与「chat」**（库里没有 'ai' 这个值，「SELECT ... WHERE source='ai'」= 0 行）；
  而「/api/emotions/profile」返回的 sources 把 chat 映射成 key=**passive** zh=**被动识别**、checkin → **主动打卡**。

## 9. 复跑

~~~
cd E:\codex workspace\009_心屿AI心理陪伴社区\backend
mvn -o -B test
~~~

产物落在「论文材料/experiments/output/」两文件；要拿现值写文档，先看文件头「生成时间」再抄数（§1 的警告）。
快照需要重导时，导出 SQL 就在 TSV 文件头注释里，照抄即可（姿势见「_cache/009_mindisle/q_*.cmd」：SQL 落 .sql 文件 + 同目录 .cmd 里 chcp 65001 + 「<」重定向，别在 PowerShell 里内联中文 SQL）。
