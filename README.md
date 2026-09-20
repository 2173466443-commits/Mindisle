# 009_心屿（MindIsle）· AI 心理陪伴与情感支持社区平台（毕业设计）

> 项目编号 009 ｜ 立项 2026-09-17 ｜ 当前阶段：**阶段 3 进行中（☑ 4 / ◐ 1 / ☐ 12）**——真实 MySQL 已建库（**31 张表 / 9.7.1**），社区主线 **T3.3 发帖 + 状态机 + 危机工单** 已落地并跑通：`mvn -o -B test` 实测 **131 例 0 失败 0 错误 1 跳过**，另有一条**真 HTTP 冒烟脚本** `docs/smoke.mjs` 实测 **45 项 / 41 条断言 / 0 失败**（注册→登录→发帖→机审拦截→危机转人工→工单落库→马甲去重→配额 429 全链路，跑完回查数据库取证）。上传接口的登录后 200 链路、precheck 的 200 响应体、马甲与配额的落库行为**均已在本轮由真库证据销账**。未做：`GET /api/posts` 与详情（T3.5）、前端发布器契约对齐（T3.13）等 12 项按设计返回 **90001/501，不用 mock 糊弄演示**。开题材料（阶段 1B）按用户 2026-09-18 指令顺延，不排在代码之前。
> 论文题目（推荐）：**《基于大语言模型与情绪感知协同过滤算法的校园心理陪伴社区平台的设计与实现》**
> 英文题目：*Design and Implementation of a Campus Psychological Companionship Community Platform Based on Large Language Model and Emotion-Aware Collaborative Filtering*
>
> **一句话定性**：这是一个**基于「大语言模型（LLM）+ 混合式中文情绪识别 + 情绪感知加权协同过滤推荐」技术**的**校园心理陪伴社区平台（Web 全栈系统）**设计与实现项目 —— 三层能力缝成一条闭环：LLM 负责共情对话与语义复核，级联情绪识别负责把文本变成可计算的情绪向量，协同过滤负责把「此刻需要什么样的内容」算出来；再叠加 L0–L3 危机分级与 12356 转介闭环做安全兜底。


## 一、文档三件套（按此顺序读）

| 文件 | 作用 |
|---|---|
| `需求分析文档.md`（**v1.2.1**） | 做什么、做到什么标准：FR1–FR10 / NFR / BR / 算法与实验设计 / 里程碑 / DoD / 论文章节映射；§15 风险表已并入调研新增的 **R12–R21**，§7.2 表清单口径统一为「24 个编号行 = **31** 张物理表」（v1.2.1 把「单独同意」拆成独立 `user_consent` 表） |
| **`制作步骤文档.md`（v1.1.6）** | **怎么一步步做**：技术栈定版、阶段 0–9 施工步骤与可复制命令、Gate 验收（含 §18 Gate 判定汇总 10 行）、30 条常见故障速查、117 项任务打勾总表（**144.3 人日 ≈ 577 小时**，日历 ≈32 周）、§17 三向追溯矩阵 101 行（FR 74 + NFR 12 + BR 12 + AR 3）、§19 交付自检。**当前 v1.1.6 = T3.3 发帖状态机 + 危机工单回写（v1.1.4 / v1.1.5 条目仍然有效）**（其下条目为 v1.1.3 的阶段 2 回写，仍然有效）：表数 30→31、`user_consent` 独立建表、申诉表定名 `post_appeal`、`00_create_db_and_user.sql` 实名、词云 R19 依赖层关闭（运行时渲染仍待验证）、§5.8 与 §5.10 逐条对账；**v1.1.4（2026-09-20）新增**：敏感词引擎 + 马甲规则 + 频率规则三块地基落地并测绿（66 例单测）、§15 阶段 3 表 **T3.2/T3.4/T3.12 由 ☐ 升 ◐**（其余 14 项仍 ☐）、§18 Gate3 改「◐ 进行中」、OpenAPI 实测 **21 paths / 24 operations / 6 分组**、本轮修掉 **8 处真实缺陷**（最贵一条：裸路径在 Servlet 容器 ResourceLoader 下解析成 `ServletContext resource [...]`，测试绿线上红）；**v1.1.5（2026-09-20）新增**：**T3.1 图片上传**落地 —— 魔数白名单（只认 jpg/png/gif 文件头，扩展名与 Content-Type 一律不信）+ Thumbnailator 重编码去 EXIF + 按原格式回存（不用 WebP：FR4.1 白名单只有 jpg/png/gif 且 JDK 17 无 WebP 编码器）；§15 表 **T3.1 由 ☐ 升 ◐**（阶段 3 收工口径 **☑ 0 / ◐ 4 / ☐ 13**）、单测 66 → **80 例**、OpenAPI **21/24/6 → 22 paths / 25 operations / 7 分组**（新增 07-file）、本轮又挖出 **4 个坑**（最贵一条：MVC 静态映射被写成 `/uploads//**`，磁盘有文件而 HTTP 404、80 例单测却全绿 → 接线类改动必须在跑着的服务上打一次真实请求）；**v1.1.6（2026-09-20）新增**：**31 表 DDL 第一次被真实 MySQL 解析执行**（`docs/init-db.ps1` 端到端跑通，`information_schema` 实测 31 表 / 9.7.1）、**T3.3 ☐→☑**（发帖八步判定顺序 + 危机 L2·L3 建 `alert_ticket` + `post_status_log` 状态日志）、**T3.12 ◐→☑**（配额有了真实调用方，明确决定不做重启 COUNT 校准）、**T3.1 ◐→☑**（登录 200 上传 + 挂图进帖）、**T3.4 ◐→☑**（马甲分配第一次跑在真库上），阶段 3 口径 **☑ 4 / ◐ 1 / ☐ 12**；单测 80 → **131 例**、新增**真 HTTP 冒烟** `docs/smoke.mjs`（45 项 / 41 断言 / 0 失败）；§6.1 追加 v1.1.6 实测回写 8 条（最贵一条：Java 15+ 把字符串字面量里的 `s` **静默当成空格**，正则失效而 131 例单测照样全绿，最后靠打印 charCode 才抓出来） |
| `同类项目调研与实现方案.md`（**已定版 + §10 增补**） | ✅ Gate 1 产出：18 个同类项目档案 + 横向对比 + 许可证核查 + §6 实现方案 + §7 风险 R12–R21。**SOP 关卡已过，§0–§9 转只读**，开工后的事实以 **§10「阶段 2 施工实测回写」** 追加（表数 31、R19 只关一半、毕设材料顺延） |

## 二、项目目标（四件事缝成一条闭环）

**说出来 → 被理解（AI 共情）→ 被回应（社区）→ 被看见（推荐）→ 被兜底（危机转介）**

- **AI 对话**：DeepSeek（经 Spring AI 2.0.1）+ SSE 逐字流式 + 停止生成 + 离线话术降级。
- **情绪识别**：DUT 词典规则 + LLM 判别级联（可选微调 BERT 作对照），汇成个人情绪档案与周报。
- **社区**：发帖（实名/匿名树洞）、点赞、收藏、评论、话题圈、关注、举报、搜索。
- **推荐**：UserCF/ItemCF + 内容相似兜底 + **情绪感知加权**（创新点）+ 冷启动 + 可解释理由 + 六组对照与消融实验。
- **实时私信**：WebSocket/STOMP + SockJS 兜底、未读计数、断线重连与离线补偿。
- **管理端**：DFA 敏感词 + AI 复核 + 人审工单 + 危机工单闭环 + ECharts 数据大屏。
- **危机干预**：L0–L3 分级，L2/L3 置顶求助卡片（国家 24 小时心理援助热线 **12356**）并生成工单。

## 三、技术栈（2026-09-17 实测定版，非记忆值）

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot **4.1.1** + JDK 17（可升 21 LTS）+ Spring Security 7 + jjwt 0.13.0 |
| 持久层 | MyBatis-Plus **3.5.17**（`mybatis-plus-spring-boot4-starter`）+ MySQL 9.7.1 + Redis 7（不可用则降级 Caffeine，统一 `CacheService` 抽象） |
| AI | Spring AI **2.0.1** + `spring-ai-starter-model-deepseek`；自写 `LlmClient` 双实现（Spring AI / JDK HttpClient）+ MockLlmClient |
| 实时与流式 | Spring WebSocket + STOMP + SockJS；SSE（`SseEmitter`） |
| 前端 | Vue **3.5.43** + Vite **8.3.0** + Element Plus **2.14.5** + Pinia 4.0.3 + Vue Router 5.3.1 + Axios + ECharts 6.1.0（词云用 `@echarts-x/custom-word-cloud` 1.0.1，peer 兼容 ECharts 6） |
| 算法 | 纯 Java（稀疏 Map + 余弦）；实验与出图 Python 3.12 |
| 接口文档 | springdoc-openapi **3.1.1**（Boot4 无 knife4j 版） |
| 构建 | Maven **3.9.16**，本地仓库 `E:\codex workspace\_cache\m2\repository`；npm/pip 缓存同指 E 盘 |

## 四、目录结构

```
009_心屿AI心理陪伴社区/
├─ README.md  需求分析文档.md  制作步骤文档.md
├─ docs/            check-env.ps1 · init-db.ps1 · start-redis.ps1 · dev-log.md · diagrams/{01 架构,02 时序,03 双通道,04 ER}
├─ sql/             00_create_db_and_user · 01–08 建表（**31 表**）· 09_seed · 10_index · patch/
├─ backend/         Spring Boot 4.1.1 工程（`src/main/java` **73 个类 / 8 个 Controller**：web 认证·用户·系统·社区·管理·审计·文件·**发帖** / post **发帖状态机与危机分级**与马甲与配额 / audit 敏感词引擎 / upload 图片上传 / ratelimit / security / common / cache / config / entity 12 / mapper 12 / auth / user / captcha（口径于 2026-09-20 重数纠正，上一版「58 类 / 7 Controller」是 T3.3 开工前的旧值））
├─ frontend/        用户端 Vue 3（端口 5173，8 个视图：feed / ai / emotion / user / help / auth 登录注册 / 404）
├─ admin/           管理端 Vue 3（端口 5174，`src` 17 个文件：Dashboard / Login / Audit / Configs / 404 + 布局 + api 三件套 + store + 主题）
└─ 论文材料/        prompts/ experiments/{data,scripts,output,figs}/ 截图/ 图表/ 论文草稿/ 答辩/
```

## 四·补 怎么跑起来（**后端已连真实 MySQL**；含 131 例单测 + 45 项真 HTTP 冒烟）

```powershell
# 0) 建库建用户：2026-09-20 已在本机跑通（31 张表 / MySQL 9.7.1 / utf8mb4_0900_ai_ci，root 口令交互输入不落盘），脚本幂等可重跑
powershell -NoProfile -ExecutionPolicy Bypass -File .\docs\init-db.ps1
#    ⚠ 口令写进**项目根目录**的 .env（不是 backend\.env）：配置项 mindisle.env-file=../.env 是相对 backend/ 工作目录的，由 EnvLoader 注入为系统属性；该文件已被 .gitignore 排除，仓库里永不出现明文口令

# 1) 可选：起 Redis（不起也能跑，CacheService 自动降级 Caffeine）
powershell -NoProfile -ExecutionPolicy Bypass -File .\docs\start-redis.ps1

# 2) 后端（8080）
cd backend; mvn -o spring-boot:run
#    接口文档：http://127.0.0.1:8080/doc.html -> 302 -> /swagger-ui/index.html（200）；OpenAPI 描述在 /v3/api-docs（2026-09-20 复测 22 paths / 25 operations / 7 分组，新增 07-file 文件与上传；分组名含中文与空格，脚本拉取必须先做 URL 编码，否则拿到空 paths 会误判成分组不匹配）
#    验证码：http://127.0.0.1:8080/api/auth/captcha → {captchaId, imageBase64}
#    涉库接口在库不可用时按设计返回 HTTP 503 + {"code":90002}（降级，不是崩了）；未实现的接口返回 90001/501，绝不返回 mock 数据
#    健康检查：/actuator/health/liveness 与 /readiness 免登录可查（200 UP）；/actuator/health 整体在未建库时是 503 DOWN，属设计内降级
#    图片上传：POST /api/files/image（**登录态 200** 与未登录 401+10002 均已真 HTTP 实测，200 链路由冒烟第 11 步覆盖）-> 落 backend/uploads/yyyy/MM/dd/uuid32.<原格式> -> GET /uploads/** 读回（200 image/png，字节与磁盘一致）；单张超 5MB 由容器先拒，返回 413 且响应体不是统一 Result 格式，前端要单独兜这一种

# 3) 用户端（5173）/ 管理端（5174），各自目录内
npm install; npm run dev
#    注册/登录/验证码链路已通；阶段 3+ 的接口会显式返回 90001/90006 并在页面显示 StageNotice，
#    不用假数据糊弄演示。
# 4) 冒烟数据预置（只跑一次，幂等）：给冒烟脚本造一条「待审话题」用例
#    mysql ... -e "source sql/11_smoke_fixture.sql"，拿到该行 id（本机实测 id=21）

# 5) 真 HTTP 冒烟（后端与 MySQL 必须在跑；退出码 0 = 全部断言通过，可直接接 CI）
$env:SMOKE_PENDING_TOPIC_ID = "21"; node docs\smoke.mjs
#    本机实测 2026-09-20 15:51：45 项 / 41 条断言 / 0 失败
#    覆盖：健康→验证码→注册→登录→专题→precheck→发帖正反侧→危机工单→马甲去重→配额 429→上传挂图
#    注意：每跑一次会新增 2 个账号 / 6 帖 / 1~2 张工单 / 1 张图，属正常；清理 SQL 见 docs/dev-log.md

```


## 五、进度

- [x] 需求分析文档 v1.0 → **v1.1**（题目句式化、技术栈定版、17 节 12 项确认）
- [x] **制作步骤文档 v1.0 → v1.1 → v1.1.1**（19 章施工手册：阶段 0–9 步骤 + 可复制命令、Gate 体系、30 条故障速查、117 项任务总表 144.3 人日、§17 追溯矩阵 101 行、§18 Gate 汇总、§19 交付自检）
- [x] **制作步骤文档 v1.1.1**（2026-09-18 文字级复核：`check-env.ps1` 命名统一、§19 环境清单口径对齐、目录 §18 行与正文一致；1624 行，任务/人日/矩阵/Gate 数量不变）
- [x] **制作步骤文档 v1.1.2 → v1.1.3**（2026-09-18）：v1.1.2 做阶段 0 完工回写 + 调研结论并入；v1.1.3 做**阶段 2 实测回写**（表数 30→31、`user_consent` 独立建表、`post_appeal` 定名、`00_create_db_and_user.sql` 实名、双端构建收口、词云 R19 依赖层关闭、§5.8/§5.10/§15 逐条对账）。任务总数、人日、追溯矩阵、Gate 行数**始终未变**：117 条 / 144.30 人日 / 101 行 / 10 行
- [x] **阶段 0：环境与仓库 8/10 完工**（Maven 3.9.16 → `_tools`、Redis 7.2.16 便携版 → 方案 B、npm/pip 缓存指 E 盘、`git init -b main` + `.gitignore` 先行、`check-env.ps1` 十项全绿）；剩 **0.5 建库建用户**（需用户本机跑 `docs/init-db.ps1` 输入 root 口令）与 **0.10 DeepSeek 探活**（需 `DEEPSEEK_API_KEY`）
- [x] **阶段 1 调研文档已写完**：《同类项目调研与实现方案.md》（618 行 / §0 先给答案 · §1 检索过程 · §2 逐项目档案 · §3 横向对比 · §4 许可证核查 · §5 差异定位 · §6 实现方案 · §7 风险 R12–R21 · §8 引用清单与 Gate 1 自检 · §9 用户答复已回填）
- [x] **SOP 强制关卡已过**：用户 2026-09-18 答复「A. 按《同类项目调研与实现方案.md》和《制作步骤文档.md》开工 / B. 选 2（MySQL 就地启用）」→ 允许编码
- [x] **阶段 2（数据库 + 三端骨架）代码侧完工 —— 2026-09-18**：31 表 DDL + 索引 + 种子脚本落盘（**尚未在真实 MySQL 执行**）；后端 45 类编译通过、8080 启动、`mvn test` **21 通过 + 1 跳过**、`/api/auth/captcha` 与降级/401/405 全实测；`frontend`（8 视图）与 `admin`（17 文件）双端 `npm install` + `npm run build` 通过
- [x] **2026-09-20 阶段 2 复核修正**：修 `SecurityConfig` 放行清单漏 `/actuator/health/**`（修前 liveness/readiness 被拦成 401）；OpenAPI 实测 **20 paths / 23 operations / 5 分组**、注解零缺失，`/doc.html` 经 `springdoc.swagger-ui.path` 302 到 `/swagger-ui/index.html` —— 上一版「/doc.html 是 knife4j 专属、本工程不可用」的说法**作废**；重跑 `mvn -o -B test` 仍 **21 通过 + 1 跳过**（BUILD SUCCESS，后端已在 8080 复起）
- [x] **建库建用户已完成（2026-09-20，消除阶段 2/3 唯一的用户侧阻塞）**：`docs\init-db.ps1` 端到端跑通，`information_schema` 实测 **31 张表**（这批 DDL 自写下去后第一次被真实 MySQL 解析执行，本轮结清这笔欠账）；`/actuator/health` 由 503 DOWN 转 200 UP，涉库接口从 90002 降级转正常。脚本修掉两个自己的 bug：mysql.exe 的 `--defaults-extra-file=` 必须是命令行第一个参数（否则 1045）；PowerShell 的 `$arr -notmatch 'x'` 返回「不匹配的元素」而不是布尔，导致一次成功建库被判成失败。
- [x] **阶段 3 首批开工 —— 2026-09-20（可离线验证的三块地基）**：
  - `com.mindisle.audit`：**DFA 敏感词引擎**（最长匹配优先 + 词级正则旁路 + BLOCK/REVIEW 分级 + 命中位置回映射原文 + `dict:version` 广播热更新）与**变体归一化**（NFKC 折全半角、小写、去零宽与空白、77 对形近与繁简折叠）；词库快照 `dict/sensitive_words_v0.1.txt` = **139 词条 / 5 条正则 / 7 大类**；导出脚本 `docs/export-sensitive-dict.mjs`。
  - `com.mindisle.post`：**马甲分配与 BR1 去重**（FR1.4「匿名屿民·X」、40 名池、幂等、并发撞唯一键回退）与**发帖配额与禁言语义**（BR5 新手 24h ≤5 帖 / 老用户 ≤20、跨自然日重置、BR6 禁言可读不可写且不消耗额度），含 `AnonymousAlias` 实体与 Mapper。
  - `AuditController`：`POST /api/audit/precheck`（发布页敏感词实时提醒，未登录实测 **401 + 10002**）+ `GET /api/admin/audit/tasks`（未实现分支返回 `90001/501`，**无 mock 数据**）。
  - 单测：`mvn -o -B test` = **Tests run: 66, Failures: 0, Errors: 0, Skipped: 1**（Engine 17 / Quota 12 / Alias 10 / Normalizer 5 + 原有 21；skip 为需真实库的 `MindisleApplicationTests`）；OpenAPI 复核 **21 paths / 24 operations / 6 分组**。
  - **诚实边界**：涉库部分仍是 ◐（马甲与配额只在内存 fake 里验过），precheck 的 **200 响应体未经真实 HTTP 验证**（白名单 fail-closed + 无库无法登录）；配额计数在缓存中，重启丢、且 peek-then-incr 非原子最多多放 1 帖（均已写入 javadoc）。

- [x] **2026-09-20 阶段 3 续 —— T3.1 图片上传（第四块可离线验证地基）**：
  - `com.mindisle.upload.ImageUploadService`：三道闸（字节数 → 魔数只认 jpg/png/gif 文件头 → 真解码），任一失败**不落盘**；`Thumbnails` 重编码（长边 ≤1600、只缩不放、JPEG 0.82）顺带剥掉 EXIF；按**输入原格式**回存到 `upload.dir/yyyy/MM/dd/uuid32.<ext>`，返回 `url/kind/width/height/bytes`，宽高来自回读落盘字节而非客户端声明。
  - `web/FileController`：`POST /api/files/image`（`@RequestPart` + `@AuthenticationPrincipal`）；`ErrorCode` 新增 **70001 FILE_TOO_LARGE / 70002 FILE_TYPE_NOT_ALLOWED / 70003 FILE_DECODE_FAILED / 70004 FILE_STORE_FAILED**；`OpenApiConfig` 新增 **07-file 文件与上传** 分组；`application.yml` 的 `mindisle.upload` 由 1 字段扩到 5 字段（dir / maxBytes / maxEdge / jpegQuality / maxImagesPerPost）。
  - 单测：新增 `ImageUploadServiceTest` **14 例**（不启 Spring、不用 Mockito，`@TempDir` 直接 new 服务）→ `mvn -o -B test` = **Tests run: 80, Failures: 0, Errors: 0, Skipped: 1**（复测于 2026-09-20 12:1x）。
  - 真实 HTTP 实测（**默认配置实例**，非临时 env 覆盖）：未登录真 multipart（node 造合法 PNG，curl 与 node 各发）→ **401 + `{"code":10002}` 带 `X-Trace-Id`**；5MB+1KB 伪 PNG → **413**（容器 `spring.servlet.multipart` 先拒，响应体为 `{timestamp,status,error,path}`，**不是统一 Result 格式**）；往 `upload.dir` 手放 1x1 合法 PNG → `GET /uploads/probe-check.png` **200 image/png、67 字节与磁盘逐字节相同**；探针文件与目录测后已清除。
  - **诚实边界（2026-09-20 本轮部分销账）**：登录后的 **200 成功链路已由冒烟第 11 步真 HTTP 验证**（上传成功 + 挂图进帖 + 服务端读盘判尺寸字节）；`maxImagesPerPost=9` 的校验点已落在 T3.3 发帖；**仍未做**：运维口径（备份与清理；`upload.dir` 是相对路径，换工作目录启动会换落盘位置）与 **秒传**（`post_image.hash` 恒为空串）；GIF 重编码**只保首帧**；**不用 WebP**（FR4.1 白名单只有 jpg/png/gif，JDK 17 的 ImageIO 无 WebP 写实现）。

- [x] **2026-09-20 阶段 3（续 2）—— T3.3 发帖状态机 + 危机工单（社区主线第一块，含真库取证）**：
  - `post/PostService`（617 行）八步顺序：禁言与配额 → 归一化与机审 → 危机分级 → 落库与状态日志；机审分支优先级写死为 **拦截 > 危机放行 > 灰词转人审 > 直发**（危机排在转人审之前是救命的顺序：一旦先进人审队列，求助的话就被压住了）。L2/L3 建 `alert_ticket`（本轮真库落 id 9/10/11），状态迁移记 `post_status_log`。
  - `post/CrisisGrader`：词典级 L0–L3 分级，**刻意不产出 L1**（需求 §5.2 的 L1 要同一用户的时间序列，发帖只有当前一条文本，判 L1 属凭空造数据，缺的部分交给阶段 4 的情绪时间序列）；**拦内容不拦人**（BR）这条已由真库证据钉死。
  - `web/PostController`：`POST /api/posts` 状态码口径 = **机审拦下与转人工都回 200**（回 `status` + `reason` + `hotline`，**不向外透 `risk_level`**），真正的入参错误才 400/10001；`GET /api/posts` 与 `GET /api/posts/{id}` 仍 **90001/501**（T3.5 未开工）。
  - 单测：新增 `PostServiceTest` 13 + `CrisisGraderTest` 13 + `AuthServiceGradeTest` 14 + `PrecheckViewTest` 4 等 5 个测试类 → `mvn -o -B test` = **Tests run: 131, Failures: 0, Errors: 0, Skipped: 1**（80 → 131，日志 `backend/target/verify-t33.log`）。
  - 真 HTTP 冒烟：`docs/smoke.mjs` 扩到 **427 行 / 11 步**，本机实测 **45 项 / 41 条断言 / 0 失败**；跑完用 mysql 回查 `posts`、`alert_ticket`、`post_status_log`、`post_image`、`anonymous_alias` 取证（本轮 post 18–23 六条、状态日志 12 行、马甲 4 张脸且 floor_no 1..4）。
  - **仍未做（不算完工）**：HUMAN_REVIEW 未同步进 `audit_task`（人审队列取不到这类帖）、工单无通知与 SLA 扫描、`auto_destroy_at` 只写不扫、禁言（BR6）只有单测证据缺真库冒烟、**前端发布器仍发旧契约**（`FeedView.vue` 缺必填 `title`，接上后端必 400） → 归 **T3.13**，是阶段 3 下一步的头等事。

- [ ] **阶段 3 进行中（☑ 4 / ◐ 1 / ☐ 12）**：已完 T3.1 上传 / T3.3 发帖状态机 / T3.4 马甲落库 / T3.12 频率限制；T3.2 敏感词引擎仍 ◐（状态机接线与灰词人审闭环未做）；下一步 **T3.13 前端发布器对齐契约 → T3.5 列表与详情 → T3.15 危机闭环**；再往后：阶段 4 AI+情绪+危机 → 阶段 5 私信 → 阶段 6 管理端 → 阶段 7 推荐与实验 → 阶段 8 测试 → 阶段 9 论文
- [ ] **阶段 1B（开题报告 / 文献综述 ≥15 篇含 ≥5 英文 / 23 页线框 / ER 图 / 架构图 3 张）—— 按用户 2026-09-18 指令「毕设材料先不用写」顺延**；风险 R21（2026-10 上旬截止）改由用户盯办
- [x] **论文材料目录骨架**：`论文材料/`（prompts · experiments/{data,scripts,output,figs} · 截图 · 图表 · 论文草稿 · 答辩）

## 六、硬性红线（开发中不得失守）

1. **不做诊断、不做治疗建议**：只做情绪支持与转介，L2/L3 必转人工渠道。
2. **敏感个人信息合规**（PIPL 第 28/29/47 条）：单独同意、可撤回、可导出、可注销、匿名不可被普通用户解匿。
3. **不复用「脑益生」任何代码、数据与密钥**：那是企业资产且含真实患者数据与明文生产凭据。
4. **演示数据 100% 虚构**：禁真实手机号/QQ/微信/照片；自伤类敏感词**只预警不删帖**。
5. **API Key 只进 `.env` / 环境变量**，不进仓库、日志、截图；`.gitignore` 先于第一行代码。
6. **所有缓存与工具落 E 盘**（`_cache`、`_tools`），禁止悄悄写 C 盘。
7. 项目结束时更新 `E:\codex workspace\全局复利与踩坑日志.md`。