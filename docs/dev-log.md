# 开发日志（dev-log）—— 心屿 MindIsle · 009

> 约定：每过一个 Gate、每踩一个新坑，**当场**追加，不事后补写。
> 用途：论文第 6 章「系统与实验实现过程」的过程性证据；答辩演示前的变更记录母本。

---

## 2026-09-18 阶段 0 —— 环境与仓库地基

### 已完成，附实测证据（是结果，不是计划）

| 任务 | 状态 | 证据 |
|---|---|---|
| 0.1 目录 | 完成 | `_tools`、`_cache\m2\repository`、`_cache\downloads`、`_cache\npm`、`_cache\pip` 全部建于 E 盘 |
| 0.2 Maven | 完成 | dlcdn 下载 9 395 475 B，解压至 `_tools\apache-maven-3.9.16`；`mvn -v` → `Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)`，`platform encoding: UTF-8` |
| 0.3 本地仓库 | 完成 | `mvn help:evaluate -Dexpression=settings.localRepository -q -DforceStdout` → `E:\codex workspace\_cache\m2\repository` |
| 0.4 Redis | 完成（方案 B） | 方案 A 失败：Docker 客户端 29.8.0 在，但 `dockerDesktopLinuxEngine` 25 s 无响应、`wsl -l` 报未安装（装 WSL 需管理员+重启，超出毕设必要）。改用 `redis-windows` 便携版 **7.2.16**（10 955 482 B），`redis-cli ping` → `PONG` |
| 0.5 建库 | **待用户执行** | `mysql -u root -e ...` → `ERROR 1045 Access denied (using password: NO)`。口令不由我持有、不写文件，改由 `docs/init-db.ps1` 用 `Read-Host -AsSecureString` 交互输入，临时明文 SQL 用后即删 |
| 0.6 包管理缓存 | 完成 | `npm config get cache` → `E:\codex workspace\_cache\npm`；`pip config list` → `global.cache-dir` 指 E 盘。（注：`pip.ini` 本体位于 `C:\Users\...\Roaming\pip\`，系 pip 唯一支持的配置路径；**只有配置在 C，缓存数据全在 E**） |
| 0.7 仓库 | 完成 | `.gitignore`（668 B / 无 BOM / 46 行，**先于任何代码创建**）、`.gitattributes`（589 B，锁行尾口径）、`git init -b main` |
| 0.8 自检脚本 | 完成 | `docs/check-env.ps1`（4 200 B / 纯 ASCII / 免 BOM），已做**正反两次**验证 |
| 0.9 凭据约定 | 完成 | `.env.example`（866 B）；真实 `.env` 已被 ignore；`JWT_SECRET` 给生成命令不给示例值 |
| 0.10 DeepSeek 探活 | **待用户 Key** | 本机无 `DEEPSEEK_API_KEY`。未凭记忆写死模型名：`.env.example` 填 `deepseek-flash` 并注明「以 `/models` 实测为准」 |

### 本日新增坑

1. **检查脚本会「说谎」**（最值得记的一条）。`chk 'mvn' [bool](Get-Command mvn) 'hint'` 里，PowerShell 把 `[bool]` 当作**独立字符串参数**绑给 `$ok`（非空即为真），`(Get-Command mvn)` 的结果被挤到 `$hint` 位置——于是 **mvn 没装也打印 `[OK]`**。
   修法：传给函数的表达式**必须整体加括号** → `([bool](Get-Command mvn))`。
   验证方式：**先在 mvn 不在 PATH 的 shell 里跑一次**，确认它报 `[!!] False`；再把 PATH 加回去跑一次确认 `[OK] True`。只跑一次全绿等于没测。
2. **msys2 版 Redis 不吃绝对路径配置文件**。传 `E:\...\mindisle.conf` 时被按 POSIX 解析成 `/E:\...`，直接 `Fatal error, can't open config file`。且 Redis 配置行按空格切分，`dir` 值含空格会**被静默截断**。
   修法：`Start-Process -WorkingDirectory $dir -ArgumentList 'mindisle.conf'`，配置内写相对 `dir data`。
3. **stock `settings.xml` 的 `<localRepository>` 示例行在注释块里**。用 `-notmatch '<localRepository>'` 判断「是否已配置」会被注释里的示例骗过，导致既没加真值又误判成功；而按 `^<settings[\s>]+` 定位开标签插入，会落进**跨行标签内部**，Maven 直接 `Non-parseable settings ... start tag unexpected character < @48:4`。
   修法：定位「以 `>` 结束 schemaLocation 的那一行」之后再插；改完必须 `mvn help:evaluate` 做**功能级**验证，不能只看 XML 能否解析。
4. **环境体检不能只看 PATH**。上一轮据 `Get-Command` 判定「MySQL 非服务态」，实际 `Get-Service MySQL` = Running / Automatic、3306 在监听——PATH 缺失只说明没进命令行环境，不代表服务没跑。服务类组件必须 `Get-Service` + 端口双查。

### 许可与合规决定

- 选 Redis **7.2.16** 而非最新 7.4.11 / 8.10.2：Redis 自 7.4 起改三重许可（RSALv2/SSPLv1/AGPLv3），**7.2 及以前仍是 BSD-3-Clause**。本机构建仅作开发期本地依赖、不随项目分发，但为避免答辩被追问许可，直接取 BSD 版本。
- `redis-windows` 是第三方 Windows 移植构建（非 Redis 官方发行），最近发布 2026-08-18 属活跃维护；其 `data/` 已在 `.gitignore` 内，二进制不进仓库。
- MySQL 沿用 C 盘现有安装（用户 2026-09-18 选定方案 ②「就地启用 + 记例外」），例外已写入《全局复利与踩坑日志》；**阶段 8 前**评估是否迁 E 盘或换 Docker。

### 下一步

- 用户执行 `docs\init-db.ps1` 完成 0.5；提供 DeepSeek Key 完成 0.10（`docs/deepseek-models.json` 落盘）。
- 随即进 **阶段 1B**（开题报告 + 文献综述 + 23 页线框图，2026-10 上旬截止）与 **阶段 2**（工程骨架 + 30 表）。

---

## 2026-09-18 阶段 2 —— 数据库 DDL 与种子数据（`sql/01`–`sql/10`）

### 交付物（实测计数，不是计划）

| 文件 | CREATE TABLE | 域 |
|---|---|---|
| `sql/00_create_db_and_user.sql` | — | 建库 + 应用账号（须用户跑 `docs/init-db.ps1`） |
| `sql/01_account.sql` | 6 | `user` `user_profile` `user_consent` `anonymous_alias` `user_follow` `user_block` |
| `sql/02_ai.sql` | 3 | `conversation` `chat_message` `ai_call_log` |
| `sql/03_emotion.sql` | 2 | `emotion_record` `weekly_report` |
| `sql/04_community.sql` | 7 | `post` `post_image` `post_status_log` `topic` `post_topic` `comment` `post_like` |
| `sql/05_recommend.sql` | 3 | `user_action` `item_similarity` `recommend_result` |
| `sql/06_pm.sql` | 1 | `private_message` |
| `sql/07_audit.sql` | 6 | `sensitive_word_group` `sensitive_word` `audit_task` `audit_record` `alert_ticket` `post_appeal` |
| `sql/08_config.sql` | 3 | `sys_config` `notify_message` `admin_op_log` |
| `sql/09_seed.sql` | 0（只 INSERT） | 参数 21 + 词组 7 + 词条 139 + 话题 20 + 演示账号 6 |
| `sql/10_index.sql` | 0（只 ALTER） | 全文索引 2 + 二级索引 4 |

**合计 31 张表**（6+3+2+7+3+1+6+3），全部 LF / UTF-8 无 BOM，脚本实测无 `\r`。

### 与手册口径的 4 项偏差（先改文档口径、再落代码，遵守 R12）

1. **30 → 31 张**：手册 §5.1 推导「26 物理表 + `post_status_log` + `appeal` + `post_like` = 29，v1.1 补 `weekly_report` = 30」，
   漏算了隐私合规必需的 `user_consent`（需求 §7.2 #3 是独立编号行，不是 `user_profile` 的列）。
   实测落库 **31 张**，文档中所有「30 表 / 30 张」口径统一回填为 31。
2. **文件名**：手册写 `sql/00_schema.sql`，实际拆成 `sql/00_create_db_and_user.sql`——
   建库 + 建账号必须 root 执行，与业务 DDL（应用账号执行）权限级别不同，混在一个文件里会让人误用 `root` 跑全量。
3. **`private_message` 会话内排序索引**：需求 §7.2 写 `(from_user_id,to_user_id,created_at)`，
   实测改为 `(from_user_id,to_user_id,id)`：`id` 自增即会话内时间序，且同毫秒消息不会在游标分页时被跳过；语义等价、更稳，已在 DDL COMMENT 里写明理由。
4. **`appeal` → `post_appeal`**：`appeal` 在 MySQL 8 保留字表边缘且语义太泛（未来可能有账号申诉），改名并在 `sql/07` 注释留痕。

### 本日新增坑

1. **种子文件里的幂等不能只靠 `INSERT IGNORE`**：`user_consent` 是「只追加、无唯一键」的审计表，
   重跑必然翻倍。解法：种子数据显式指定主键 `id`，用 PK 冲突达成幂等；同时在文件头写清这条依赖。
2. **MySQL 字符串会吞反斜杠**：敏感词正则若写 `\d` 需在 SQL 里双重转义，极易出错。
   词表统一改用 POSIX 字符类（`[0-9]`、`[a-zA-Z]`），全库正则零反斜杠。
3. **`word_cnt` 必须等于实插条数**：分组词条数与 `sensitive_word_group.word_cnt` 由同一段脚本从同一数组算出，
   并在文件尾附「HAVING g.word_cnt <> COUNT(w.id) 应返回空集」的自检 SQL，答辩时能当场证明。
4. **`ADD INDEX` 没有 `IF NOT EXISTS`**（MySQL 8）：`sql/10` 不可无脑重跑，故文件内附 information_schema 核对查询与逐条回滚语句。

### 仍未做（诚实记录）

- **DDL 尚未在真库执行过**：`mysql` root 口令不由我持有，`docs/init-db.ps1` 只能用户手输。
  也就是说 31 张表目前是「严格手写 + 脚本自检」，**未经 MySQL 解析器验证**。用户跑通后若报语法错，按报错逐条修，不许改口径凑数。
- `sensitive_word` 的 `variant_hash` 是 MD5(原文) 占位，真实变体归一化算法在 T5.x 实现后需要重刷该列。

---

## 2026-09-18 阶段 2（下半）—— 后端可运行 + 用户端 / 管理端骨架

### 交付物（全部为实测计数）

| 模块 | 实测 | 证据 |
|---|---|---|
| 后端 | Spring Boot 4.1.1 单模块，`src/main/java` **45 个类**编译通过并在 8080 启动 | `Get-ChildItem -Recurse -Filter *.java` 实数；`mvn -o spring-boot:run` |
| 单测 | `src/test` 4 个类：`CaptchaServiceTest` 6 + `ResultTest` 7 + `JwtServiceTest` 8 = **21 通过**；`MindisleApplicationTests.contextLoads` **1 跳过** → 合计 run 22 / pass 21 / skip 1 | `target/surefire-reports/*.txt` 四份报告逐行读 |
| 接口实测 | `GET /api/auth/captcha` 返回 captchaId + imageBase64（PNG）；未建库时 `GET /api/topics` → **HTTP 503 + code 90002**，响应体含 traceId 且带 `X-Trace-Id` 头；401 / 404 / 405 分别走 10002 / 90006 / 90007 | `curl.exe -s -i` |
| 错误码 | `ErrorCode` 枚举 **34** 项（90005 本地缓存不支持、90006 资源不存在、90007 方法不支持 为本阶段新增） | 逐个数枚举项 |
| 降级 | 无 Redis → `CaffeineCacheService`；无 DEEPSEEK key → `MockLlmClient`；无库 → 90002。三条都在零外部依赖环境下真跑过 | 启动日志 + 接口返回 |
| 用户端 | `frontend` 28 个文件，`src/views` **8 个 .vue**（feed / ai / emotion / user / help / auth 登录注册 / 404）；145 包安装无 ERESOLVE；`npm run build` 通过 | 退出码 0 |
| 管理端 | `admin` 21 个文件（`src` 下 **17**）：views 5 个 + AdminLayout + api 五件（admin/auth/system/http/errorCode）+ store + router + theme + StageNotice；128 包；`npm run build` 通过 | 退出码 0 |
| 代理 | `vite.config.js` 对 chat/stream 删 content-encoding、置 no-transform 与 x-accel-buffering no；两端 strictPort 钉 5173 / 5174 | 源码 L18-36 |
| 文档 | 手册 **v1.1.3**（1672 行）/ 需求 **v1.2.1**（960 行）/ 调研新增 **§10**（638 行）/ README（102 行）四处口径对齐 **31 表** | 回读断言 |

### 本日对账纠偏（上一版记录写错了，以实测为准）

1. 表数 **30 → 31**：多出的一张是 `user_consent`。上一轮按「手册说 30」抄了 30，这次把 `CREATE TABLE` 后面的**表名抓出来去重**才拿到 31。
2. 单测数字：此前记成「22 通过 + 1 跳过」，surefire 实读是 **21 通过 + 1 跳过（共 22 个用例）**。跳过的是 `contextLoads`（未建库，主动 skip 比造假绿诚实）。
3. 词云 R19 **只关了一半**：词云包只进了 `frontend/package.json` 的 deps，**全仓 .vue 零引用**；手册要求的 `docs/gate/` 目录**根本不存在**，spike 截图从未存档。所以措辞只能是「依赖层风险关闭、运行时渲染未验证」。
4. 手册 §5.10 写的 `/doc.html` 是 knife4j 路径，本工程只引 springdoc-openapi 3.1.1 → 正确地址是 `/swagger-ui/index.html` 与 `/v3/api-docs`。已在手册内注明。
   ⚠ **本条结论已于 2026-09-20 被实测推翻**（`/doc.html` 实际会 302 到 `/swagger-ui/index.html`），见本文末尾「2026-09-20 阶段 2 复核」的纠偏段。原文保留，不偷偷改掉。
5. 文档声称 `docs/rebuild.ps1` 存在 —— 实测不存在。`docs` 下只有 check-env.ps1 / init-db.ps1 / start-redis.ps1 / dev-log.md / diagrams/。**「文件存在」必须 readdirSync 核，不能信上一版文档。**

### 本日新增坑（工具层，与业务无关但反复咬人）

㉑ **js REPL 的 globalThis 不保证活到下一轮**：攒了几轮的内存数组在重置后全丢，而磁盘还是旧版。解法：每轮开头先探一下全局数组还在不在，并且**改完就落盘**，别等「所有批次做完再一次性写」。
㉒ **同一条消息里发两个相同写盘调用 = 写两遍**：本轮再次发生重复调用。规则：**涉及写盘的批次，一条消息只发一个工具调用**。
㉓ **Markdown 表格行不能裸 `includes` 定位**：`| T2.1 |` 这个串在 T2.2 / T2.3 行的「依赖列」里也出现（3 处命中）；`| T2.16 |` 在 §5.11 表里也有一行。必须用 `startsWith(前缀)` 且断言命中数唯一。
㉔ **往数组元素里塞裸换行会造出孤立 LF**：全文 CRLF 的文件里混进不带 CR 的换行，用两种方式数行数就会差 1。落盘前必须把含换行的元素拆开，并用「孤立 LF 计数 == 0」做断言。
㉕ **`exec_command` 传 PowerShell 时 `$` 变量会被吞**（`$p` 变空，报一堆 ParserError）；而且 `Invoke-WebRequest` 是 PS7 语义，拿到的 `HttpResponseMessage` 没有 `GetResponseStream`。结论：**看文件、做统计一律用 node**；**取非 2xx 的响应体一律用 `curl.exe -s -i`**。

### 仍未做（诚实记录）

- **31 表 DDL 从未被 MySQL 解析器执行过** —— root 口令不由我持有。建库、列数比对、`contextLoads` 转绿，全部挂在这一个用户动作上。
- 词云运行时渲染未验证；`backend/run.bat`（无 BOM + chcp 65001）与 `docs/rebuild.ps1` 未建；`mvn -o dependency:tree` 输出未存档；`gen_seed.py` 未写；OpenAPI 分组未逐条核对。（→ 2026-09-20 已核完：5 分组 / 23 operation 注解零缺失；该行其余事项仍未做）
- 阶段 1B（开题报告 / 文献综述 / 线框图）按用户指令顺延，未开始。

---

## 2026-09-20 阶段 2 复核 —— 修 actuator 放行 + 推翻上一版两处结论

### 本轮实测（后端重启后逐条 `curl.exe -s -i`，全是拿回来的字节，不是推断）

| 探针 / 命令 | 结果 | 结论 |
|---|---|---|
| `GET /actuator/health` | **503** `{"groups":["liveness","readiness"],"status":"DOWN"}` | DOWN 来自 DB；Redis 探活按 §5.4 设计 `enabled=false`。未建库时它不可能 UP，别当 bug 追 |
| `GET /actuator/health/liveness` | 修前 **401 `{"code":10002}`** → 修后 **200 `{"status":"UP"}`** | **真 bug**：`SecurityConfig.PUBLIC_MATCHERS` 只写了 `/actuator/health`，子路径落到 `anyRequest().authenticated()` |
| `GET /actuator/health/readiness` | 修后 **200 UP** | readiness 组不含 db 指示器，所以未建库也 UP —— 别拿它当「库通了」的判据 |
| `GET /actuator/info` | 修后 **200 `{}`** | 端点已暴露（`include: health,info,metrics`） |
| `GET /doc.html` | **302 + `Location: /swagger-ui/index.html`** | 🔴 推翻上一版结论：`application.yml` 的 `springdoc.swagger-ui.path=/doc.html` 生效，springdoc 3.1.1 **会**服务这个路径（做重定向），「knife4j 专属」说法作废 |
| `GET /swagger-ui/index.html` | **200 text/html** | 真正的 UI 落点 |
| `GET /v3/api-docs` | **20 paths / 23 operations / 5 分组**（`1 认证`5 · `2 系统`4 · `3 用户`5 · `4 内容`5 · `5 管理端`4）；23 个 operation 的 summary/operationId/responses **零缺失** | Gate 2「接口 ≥ 20」**达标** → T2.10 ◐→☑，§15 阶段 2 表改 ☑10 / ◐6 |
| `GET /api/nope`（未登录） | **401 / 10002**，不是 404 / 90006 | Security 链排在 MVC 之前，未授权的未知路径永远走不到 90006；要复现 90006 必须带合法 JWT |
| `GET /api/topics` | **503** + `{"code":90002,"msg":"数据暂时读取不到，请稍后重试","success":false}` + `X-Trace-Id` 头 | 响应体 msg 是**面向用户的友好文案**，与 `ErrorCode` 枚举里那句技术措辞不是同一字符串，写断言别写错 |
| `mvn -o -B test`（改后复跑） | 重编译 45 源文件 + 4 测试类，**run 22 / pass 21 / skip 1**，BUILD SUCCESS | 放行改动没打破任何东西 |
| 启动 | `Started MindisleApplication in 4.247 seconds`（Tomcat 8080） | WARN 3 条且全不涉业务：`ResourceHandlerUtils` 给 `uploads/` 补尾斜杠（日志里中文路径显示乱码 = 控制台 GBK 显示问题，文件本身没事）、`SpringDocAppInitializer` ×2 提示 `/v3/api-docs` 与 `/doc.html` 默认开启（生产按 §5.4 关掉即可） |

### 代码改动（1 个文件、1 行）

- `backend/src/main/java/com/mindisle/security/SecurityConfig.java`：`PUBLIC_MATCHERS` 里在 `"/actuator/health"` 之后**新增** `"/actuator/health/**"`（并列而非替换 —— `/**` 不匹配无斜杠的根路径本身）。

### 新增坑（工具与判断层）

㉖ **`requestMatchers("/actuator/health")` 不覆盖子路径**：Ant 风格下 `/health` 与 `/health/**` 是两条规则，而 Actuator 的存活/就绪探针恰好都在子路径上。只放根路径 = 探针全瞎，**而且在浏览器里看不出来**（根路径自己能打开）。凡是「清单放行 + 兜底 authenticated」的写法，必须专门测带子路径的那几个。
㉗ **`/actuator/health` 的 status 是聚合值，`liveness` / `readiness` 是分组值，三者可以互相矛盾**：本轮实测 health=DOWN 而两个探针同时 UP。写监控、写答辩材料都要标明用的是哪一个，否则「健康检查通过」这句话本身就是错的。
㉘ **`exec_command` 传 PowerShell 时 `$var` 会被吞**（本轮 `$wd=...` 变成空串，`Start-Process -WorkingDirectory` 直接报缺参）。规则同 ㉕：**命令行里一律不写 `$`**，路径用字面量；真需要变量就落一个带 BOM 的 .ps1 再执行。
㉙ **别用「依赖里有哪个包」去推断「有哪些 endpoint」**：`swagger-ui.path` 是配置项，`/doc.html` 通不通只有 `curl -i` 说话算数。上一版就是没读 `application.yml` 就否掉了手册的结论 —— 手册 §14 第 10 条其实早就写对了。

### 文档同步（同一轮全部落盘）

- 手册：§5.6 加放行清单实测修正注；§5.10 第 2、3 条按实测重写（第 3 条转 ☑）；§15 T2.10 ◐→☑、收工口径 ☑9/◐7 → **☑10/◐6**；§18 Gate2 行、§19 v1.1.3 行与「下一步」同步。
- README：`四·补` 接口文档地址改对 + 加健康检查行；进度区加「2026-09-20 阶段 2 复核修正」一条。
- 需求文档与调研文档本轮**未改**（31 表口径、题目、技术栈均未变）。

### 仍未做（截至本轮，别自我感觉良好）

- 31 表 DDL **仍未被 MySQL 解析器执行过**；`contextLoads` 仍 skip；注册→登录→`/api/users/me` 链路仍未跑通 —— 三件都卡在 `docs\init-db.ps1` 这一个用户动作上。
- 词云运行时渲染 spike 未做；`docs/gate/` 证据目录仍未创建；`backend/run.bat`、`docs/rebuild.ps1`、`sql/gen_seed.py` 未建；`mvn -o dependency:tree` 输出未存档。
- 阶段 1B（开题报告 / 文献综述 / 线框图）按用户 2026-09-18 指令顺延，未开始；R21 截止由用户盯办。

---

## 2026-09-20 阶段 3 开工 —— 敏感词引擎 + 马甲规则 + 频率规则（只挑可离线验证的地基先做）

### 交付物（全部为实测计数，不是计划）

| 类型 | 文件 | 实测事实 |
|---|---|---|
| 引擎 | `backend/src/main/java/com/mindisle/audit/SensitiveWordEngine.java` | DFA/Trie、最长匹配优先、词级正则旁路、BLOCK/REVIEW 分级、命中位置回映射原文、`dict:version` 广播热更新；后端启动日志实录 `version=v0.1 词条=139 其中 regex=5` |
| 归一化 | 同包 `TextNormalizer.java` | NFKC 折全半角 + 小写 + 去 Ignorable + 77 对折叠表（24 西里尔 + 53 繁简）；每对「码点 ⇄ 注释字符」已用 node 逐对校验一致 |
| 词库 | `backend/src/main/resources/dict/sensitive_words_v0.1.txt` | 145 行 = **139 词条 / 5 条正则** / 7 大类（政治违法 20、色情低俗 20、辱骂攻击 20、自伤自杀 20、隐私泄露 19、广告导流 20、医疗越界 20） |
| 脚本 | `docs/export-sensitive-dict.mjs` | 把 jar 内快照导出成盘外可写文件，为 `mindisle.audit.dict-path` 的热更新铺路 |
| 业务 | `com/mindisle/post/AnonymousAliasService.java` | FR1.4 取名（前缀 + 40 名池 + 序号回绕）+ BR1 全站一匹唯一 + 幂等 + 并发撞唯一键回退对手别名 |
| 业务 | 同包 `PostingQuotaService.java` | BR5（新手 24h 内 ≤5 帖、老用户 ≤20）+ BR6（禁言可读不可写）+ 评论 20 条每帖 |
| 持久层 | `entity/AnonymousAlias.java`、`mapper/AnonymousAliasMapper.java` | 列与 `sql/01_account.sql` 的 `anonymous_alias` 一一对应 |
| 接口 | `web/AuditController.java` | `POST /api/audit/precheck`（FR4.1 实时提醒）+ `GET /api/admin/audit/tasks`（未实现分支返回 `NOT_IMPLEMENTED 90001/501`，**不返回 mock**） |
| 配置 | `MindisleProperties.Audit` + `application.yml` | 新增 `audit.dict-resource` / `dict-path` / `dict-check-millis` / `quota.*` 外置项 |
| 测试 | 4 个新测试类 | 全项目 `mvn test` = **66 例 0 失败 1 跳过**（阶段 2 为 22 例） |

### 本轮真实踩到的 8 个坑（每条都有日志或测试证据）

1. **编译阻塞**：换行界写成 `split("\R")`，Java 字符串字面量里单反斜杠 + 大写 R 不是合法转义（正则的 `\R` 必须在源码里双写）。**教训：每写完一个文件立刻编译，别攒一批再 build** —— 上一轮就因此把 4 个文件的错一起引爆。
2. `refreshIfStale` 用 `interval <= 0` 判「关闭热更新」，而配置侧期望 `0 = 每次都查`（对齐 `setDictCheckMillis(0)` 语义）→ 改为 `interval < 0` 才关闭，javadoc 写清三态。
3. 测试语料「微信号 abc_def12」中间夹「号」字不匹配微信正则，属**假阴性**；改「微信：abcd_1234」（全角冒号靠 NFKC 自动折半角）后实测命中。
4. `prefersExternalSnapshotWhenConfigured` 原本是**假测试**：把带 `classpath:` 前缀的值塞进 `dictPath`，拼成 `file:classpath:` 之后两个分支都回落内嵌词库，断言恒真。改成 `@TempDir` 真写盘外快照并断言 `version=external-1 / wordCount=1`（证明「优先外置」这条分支真走了），再断言文件缺失时回落 139 条 v0.1。
5. 繁简折叠表**码点写错**：`\u68aa` 是「梪」，「槍」是 U+69CD。肉眼完全看不出来，只有逐对校验能发现 —— 改完实测繁→简折叠通过。
6. `TextNormalizer.isIgnored` 漏删 U+00B7 间隔号，导致「敏·感词」折不成「敏感词」，这是**引擎真实缺口**而非测试问题。
7. `Normalized.toRawRange()` 旧语义「终点取下一个保留字符起点」会把命中之后的空格一起吞进高亮区间。改为**起点 = 第一个命中单元的原文下标、终点 = 最后一个命中字符的末尾**：词内被删掉的分隔符仍覆盖，词后的空格不吞；越界与退化只做 clamp 不抛异常，并补 sourceIndex 与边界断言。
8. **最严重：测试绿、线上红。** `dict-resource` 配裸路径 `dict/x.txt` 时，单测用 `DefaultResourceLoader` 全绿，但 Boot 运行期注入的是 **Servlet Web 容器上下文**，同一字符串被解析成 `ServletContext resource [/dict/x.txt]`，**后端启动直接失败**（日志实录「词库文件不存在：ServletContext resource [...]」）。修法三件套：Java 与 yml 两处默认值都写显式 `classpath:` 前缀 + 引擎侧 `withClasspathPrefix()` 兜底归一（放行以 `/` 开头及 `classpath:` `file:` `jar:` `http:` `https:`）+ 自写 `ServletStyleResourceLoader` 内部类复刻容器语义的回归测试 `normalizesBarePathForServletStyleLoader`。附带一条硬知识：**Spring 7 的 `ResourceLoader` 不是函数式接口**（`getResource` 与 `getClassLoader` 都是抽象方法），lambda 会报「找到多个非覆盖抽象方法」，只能写内部类；查接口方法用 `javap -cp <jar> <类全名>`。

另修两处小错：`post/AnonymousAliasService.java` 漏 `import com.mindisle.entity.AnonymousAlias`（编译错误）；`PostingQuotaService` javadoc 第三条与代码不一致（null 与空串 status 实际按 ACTIVE 放行），把文档改准并在测试里钉住。
### 实测记录（每条都对应一次真实执行）

| 动作 | 结果 |
|---|---|
| `mvn -o -B test`（`-Dmaven.repo.local` 指 E 盘缓存） | **Tests run: 66, Failures: 0, Errors: 0, Skipped: 1** —— Engine 17 / Quota 12 / Alias 10 / Normalizer 5 / Captcha 6 / Result 7 / Jwt 8；skip 仍是需真实库的 `MindisleApplicationTests` |
| 后端启动 | `Started MindisleApplication in 4.044 seconds`（PID 6696），Tomcat 8080；`c.m.a.SensitiveWordEngine - 敏感词库已加载：version=v0.1 词条=139 其中 regex=5` |
| 日志里 `Filter jwtAuthFilterRegistration was not registered (disabled)` | **预期行为**，非 bug：`SecurityConfig` 用 `FilterRegistrationBean.setEnabled(false)` 防 JWT 过滤器被容器自动注册 + Security 链注册两次，类注释已写明 |
| `POST /api/audit/precheck` 三种非法请求（无 token / 坏 token / 空白 body） | 全部 **401 + code 10002「请先登录」** ✅ 与类注释一致：precheck 不进 permitAll，避免游客拿它白嫖探测词库 |
| `GET /api/system/info` | 200，`cacheMode=local`、`llmProvider=spring-ai`、`javaVersion=17.0.19` |
| `GET /actuator/health` | **503**（未建库所致，属预期） |
| `GET /v3/api-docs` | **paths 21 / operations 24**（阶段 2 为 20/23） |
| `GET /v3/api-docs/swagger-config` 后逐分组拉取 | **6 个分组全部 200**；新分组「06-audit 内容安全」= paths 1 / ops 1（`/api/audit/precheck`）；`/api/admin/audit/tasks` 归在「05-admin 管理端」；各组 paths 相加 5+3+5+3+4+1=21 与总数吻合 |

> 上一次复核时我拉 `/v3/api-docs/06-audit 内容安全` 得到 `paths: []`，据此差点写「分组不匹配」。**那是我 URL 没编码的测量假象**：分组名含空格与中文，必须 `encodeURIComponent`，重测即 200 且内容正确。结论：接口文档分组名一律含中文，任何「拉不到」先怀疑编码。

### 同轮文档与 SQL 对齐

- `sql/01_account.sql` 第 82 行 `alias_name` 列注释：「匿名树洞·雾屿 07」→「匿名屿民·阿澜（需求 FR1.4）」，与代码常量、需求 FR1.4 三方一致（DDL 尚未落库，改动零风险）。
- 手册升 **v1.1.4**：§6.1 追加实测注（6 条）、§15 阶段 3 表 **T3.2 / T3.4 / T3.12 ☐→◐**（其余 14 项仍 ☐）并加「阶段 3 首批收工口径」、§18 Gate3 改「◐ 进行中」、§19 加 v1.1.4 变更行并重写「下一步」。任务总数 / 人日 / 追溯矩阵 / Gate 行数**均未变**（117 / 144.30 / 101 / 10）。
- README：测试数 22 → 66、新增 `/api/audit/precheck`、接口文档分组 5 → 6。

### 仍未做（截至本轮，别自我感觉良好）

- **涉库仍是 ◐**：马甲落库只在内存 fake（`InMemoryRepository`）里验过，没碰过真表；`user_post_stat` 落库、**T3.3 发帖状态机**、T3.1 上传、T3.5–T3.17 全部未开工。阶段 3 的 17 项里 14 项还是 ☐。
- `/api/audit/precheck` 的 **200 响应体未经真实 HTTP 验证**：`JwtService.validate` 白名单 fail-closed（`user:token:{uid}` 必须等于 jti），本地手造 token 必返 10005，而登录注册又需真实库 —— 已验证的是 401 语义、路由与安全接线、以及引擎层 17 例单测，**不许对外声称已联调**。
- 词库热更新只接通「版本号广播 → 重载」这半条，词库来源仍是 jar 内 classpath 快照；盘外可写快照链路（`dict-path` + T6.2 管理端写操作）未建。
- 频率计数在缓存里，**重启丢当日额度**；禁言判定 peek-then-incr **非原子**（并发最多多放 1 帖）。两条妥协已写进 javadoc，属公开债不是隐藏债。
- 归一化删空白有**已知误报**：日期与手机号粘连可能命中银行卡正则 → 隐私泄露组只给 REVIEW 不 BLOCK，这个取舍要写进论文局限。
- 31 表 DDL 至今**从未被 MySQL 解析器执行过**；词云运行时渲染仍未验证；`docs/gate/` 证据目录仍未创建；阶段 1B（开题 / 文献 / 线框）按用户 2026-09-18 指令顺延。
- Git：**未打 tag**（Gate 3 未过，只提交不标记）；本轮全部代码与文档改动在同一次提交内收口。

## 2026-09-20 阶段 3（续）—— T3.1 图片上传：三道闸 + 重编码去 EXIF + 静态映射实测

### 交付物（全部为实测计数）

| 文件 | 内容 |
|---|---|
| `upload/ImageUploadService.java`（新） | 字节数（≤5MB）→ **魔数**白名单（jpg/png/gif，扩展名与 Content-Type 一律不信）→ 真解码，三道闸任一失败**不落盘**；`Thumbnails` 重编码（长边 1600、只缩不放、JPEG 0.82）→ EXIF 随解码丢弃、编码不写回；**输出格式恒等于输入格式**（不用 WebP）；落 `upload.dir/yyyy/MM/dd/uuid32.<ext>`；`StoredImage(url, kind, width, height, bytes)` 的宽高来自**回读落盘字节**；`briefName()` 把客户端文件名压成 ≤64 字符的一行日志文本，绝不参与拼路径 |
| `web/FileController.java`（新） | `POST /api/files/image`（`multipart/form-data`，一次一张），未登录一律 `UNAUTHORIZED`；空文件 `PARAM_INVALID`；`getBytes()` 失败 `FILE_DECODE_FAILED` |
| `common/ErrorCode.java` | 新增 **7xxxx 文件上传组**：70001 过大(413) / 70002 类型(400) / 70003 解码(422) / 70004 落盘(500)，类注释图例同步 |
| `config/MindisleProperties.java` + `application.yml` | `Upload` 由 1 字段扩到 5：`dir` / `max-bytes=5242880` / `max-edge=1600` / `jpeg-quality=0.82` / `max-images-per-post=9` |
| `config/OpenApiConfig.java` | 新分组「**07-file 文件与上传**」（`pathsToMatch("/api/files/**")`），分组数 6 → 7 |
| `config/WebMvcConfig.java` | 静态映射改用 `ImageUploadService.URL_PREFIX + "/**"`，字面量不再有两处 |
| `test/.../ImageUploadServiceTest.java`（新） | **14 例**，`new MindisleProperties()` + `new ImageUploadService(props)` + `@TempDir` 真目录，不启 Spring、不用 Mockito |

### 本轮真实踩到的 4 个坑（都有日志或测试证据）

1. **交接稿里落盘的 `ImageUploadService.java` 第 211 行是一段字面量 `NaN`**（上一轮写文件时 JS 转义炸掉的残留），也就是**新代码从未经过一次编译**。先把 `int cut = Math.max(lastIndexOf("/"), lastIndexOf(BACKSLASH))` 补回去才谈编译。教训：接手「已写盘未验证」的代码，第一步永远是 `mvn test`，不是继续往下写。
2. **`FileController` 漏 `import ...annotation.RestController`** —— 第一次编译才炸出来。`@Tag/@Operation` 的导入在，`@RestController` 不在，说明当时是照抄别的 controller 的 import 块而不是逐符号核对。
3. **我自己把静态映射写成了 `/uploads//**`**：抽出 `URL_PREFIX = "/uploads/"`（结尾带斜杠）后 `URL_PREFIX + "/**"` 得到双斜杠模式，`/uploads/...` 一条都不匹配 → **磁盘上真有文件、HTTP 404、80 例单测全绿**。改为常量不含结尾斜杠，并在跑着的服务上 GET 一张手工构造的 1x1 PNG 验回 **200 + `image/png` + 70 字节**。这条 bug 单测原理上测不到（它不是 `ImageUploadService` 的行为，是 MVC 接线），所以**映射类改动必须真实打一次请求**。
4. **`url` 里的 `uploads` 是挂载点不是目录**：测试助手一开始按 `tmp/uploads/yyyy/MM/dd` 还原真实文件，5 例连红；`WebMvcConfig` 把 `/uploads/**` 映射到 `upload.dir` **本身**，磁盘路径里没有 `uploads` 这一段。已在服务 javadoc、测试类注释与手册 §6.1 三处写死这个语义。

另外两处「测试差点骗自己」的地方：`write()` 里断言 `ImageIO.write(...)` 返回 **true**（JDK 缺某格式 writer 时它会静默返回 false，样本就成了空文件）；EXIF 用例先自证「注入 APP1 后样本确实含 `Exif` 且仍可被 ImageIO 解码」，再断言落盘没有 —— 否则拿一张本来就没 EXIF 的干净 JPEG 去断言「没有 EXIF」是恒真断言。首版 alpha 用例还把通道写反（拿 `>> 16` 断言绿色通道），被真实回读当场纠正为 R=0x33 / G=0x66。

### 实测记录（每条都对应一次真实执行）

| 动作 | 结果 |
|---|---|
| `mvn -o -B test` | **Tests run: 80, Failures: 0, Errors: 0, Skipped: 1**（v1.1.4 为 66，本轮 +14）；skip 仍是需真实库的 `MindisleApplicationTests` |
| `POST /api/files/image` 未登录（真 multipart，载荷=脚本文本改名 .png） | **401 + code 10002**，带 `X-Trace-Id`，统一响应体 ✅ |
| `POST /api/files/image` 未登录（载荷=合法 1x1 PNG） | 同上 **401 + 10002** —— 证明内容合法也不会绕过登录闸 |
| `POST /api/files/image` 未登录（载荷 5MB+1KB 伪 PNG） | **413** `{"timestamp","status":413,"error":"Content Too Large","path":"/api/files/image"}` —— **容器 `max-file-size` 先命中，响应体不是我们的统一格式**（无 code/msg/traceId），前端上传组件要单独兜；业务码 70001 只有容器放行后才有机会返回 |
| `GET /uploads/probe.png`（文件由启动后放入 `upload.dir`） | **200 `image/png` 70 字节**；同一实例 `GET /uploads/smoke/probe.png`（不在该实例配置的目录里）→ 404，反证映射由配置驱动，也**证伪**了我先前怀疑的「目录在启动时不存在 → `toUri()` 不加尾斜杠 → 映射失效」——不需要额外加尾斜杠的补丁 |
| `GET /v3/api-docs` | **paths 22 / operations 25**（v1.1.4 为 21/24） |
| `GET /v3/api-docs/swagger-config` 逐分组拉取 | **7 个分组全部 200**：01-auth 5/5、02-user 3/5、03-system 5/5、04-feed 3/4、05-admin 4/4、06-audit 1/1、**07-file 1/1（`/api/files/image`）**，各组 paths 相加 =22 与总数吻合 |

### 同轮文档对齐

- 手册升 **v1.1.5**：§6.1 3.1 行改写（魔数白名单、`uuid.<原格式>`，并标注原文 `uuid.webp` 与需求 FR4.1 冲突已按需求纠正）、新增 v1.1.5 实测注 6 条、§15 **T3.1 ☐→◐**（阶段 3 表口径 ☑0/◐4/☐13）、§18 Gate3 状态、§19 变更行与「下一步」。任务总数 / 人日 / 追溯矩阵 / Gate 行数**均未变**（117 / 144.30 / 101 / 10）。
- README：测试数 66 → 80、接口文档分组 6 → 7、新增 `/api/files/image`。

### 仍未做（截至本轮，别自我感觉良好）

- **「已登录 → 200 上传成功」这条链路仍未经真实 HTTP 验证**（无库取不到 token），与 `/api/audit/precheck` 同批欠账；已验证的是 401/413 语义、静态映射 200、以及 14 例离线单测里的真落盘。
- FR4.1 的「单帖 ≤9 张 / 共 ≤20MB」属 T3.3（上传接口不判，判了也拦不住分 10 次传）；`maxImagesPerPost` 目前只是配置项，**没有调用方**。
- GIF 重编码后**只剩首帧**；`uploads/` 目录的运维口径（磁盘配额、备份、生产环境 Nginx 前置）未定。另：`backend/uploads/` 早在阶段 0 就已被 `.gitignore` 排除（`git log -S backend/uploads` 查到是 a53469f），本轮只是**复核确认**，不是我新补的。
- 阶段 3 其余 13 项仍 ☐，主线 T3.3 未开工；31 表 DDL 至今**从未被 MySQL 解析器执行过**；毕设材料（T1B.*）按用户指令顺延；**仍未打 git tag**（Gate 3 未过）。

### 同日收尾 —— 把上一节里「提前断言」的三件事真正做完

上一节「同轮文档对齐」里写了「README：测试数 66 → 80、分组 6 → 7、新增 `/api/files/image`」，**但当时 README 一行都没改** —— 属提前断言，本轮实际改完并核对，记为一次自纠（本项目的口径是「每条结论必须能指到一次真实执行」）。

| 收尾动作 | 实测结果 |
|---|---|
| README 改 | 当前阶段口径（66 → 80 例、三块 → 四块地基、补上传接口三条实测）、手册行升 v1.1.5 并追加变更句、目录结构 backend 类数 **45 → 58 / web Controller 5 → 7**（旧值是阶段 2 的数，本次重数纠正）、运行段补 `/uploads/**` 读回与 413 兜底口径、进度区新增 T3.1 条目 6 行。文件仍 **LF、无 BOM、无 CRLF**；表格行 pipe 数未增加（无裸竖线）。
| 清探针 | 删掉 5 处 `probe.png` 与 `backend/target/notimage.png`，再逐个 `rmdir` 空目录：`backend/uploads{,/smoke}`、`backend/uploads-fresh`、`E:\codex workspace\uploads{,/smoke}`、`009_.../uploads{,/smoke}`、`C:\Users\Drbrain\uploads{,/smoke}` 全部清空移除。
| 后端回到默认配置 | 停掉带 `MINDISLE_UPLOAD_DIR` 的实验实例（12:04 起的 PID 19828/24420），12:17:07 用文档里唯一可用写法重启（日志换 `target/run4.out`），26s 后 `Test-NetConnection 127.0.0.1:8080` = True。
| 默认实例复测 | `mvn -o -B test` 重启前后各跑一次：**Tests run: 80, Failures: 0, Errors: 0, Skipped: 1**；`/v3/api-docs` = **22 paths / 25 operations**、`swagger-config` **7 分组全 200**（07-file 1/1），各组 paths 相加 =22；未登录真 multipart（合法 1x1 PNG）→ **401 + `{"code":10002}` 带 `X-Trace-Id`**；GET 同路径 → 401；5MB+1KB 伪 PNG → **413 `{"timestamp","status":413,"error":"Content Too Large","path":"/api/files/image"}`**（再次确认非统一响应体）；往默认 `./uploads` 放 1x1 PNG → `GET /uploads/probe-check.png` **200 image/png、67 字节与磁盘逐字节相同**（这条补齐了上一轮只在 `MINDISLE_UPLOAD_DIR` 覆盖目录下验过的缺口），验后即删。
| 工作树 | `git check-ignore -v backend/uploads/probe-check.png` → `.gitignore:30:backend/uploads/` 命中；`git status` 只剩 7 改 3 增的真实交付物。

**仍然未做（不变）**：登录后的 200 上传链路仍无真实 HTTP 证据；`maxImagesPerPost` 无调用方；T3.3 主线未开工；31 表 DDL 仍未被 MySQL 执行过；**未打 tag**。

---

## 2026-09-20 阶段 3（续 2）—— T3.3 发帖状态机：真实建库 + 真 HTTP 冒烟 + 真库取证

### 先销一笔挂了三轮的旧账：31 表 DDL 第一次被 MySQL 解析器执行过

`docs/init-db.ps1` 自 09-18 写下后**从未端到端跑过**。实测：`information_schema.tables` 里 `mindisle` 库 **31 张表**、`VERSION()` = **9.7.1**、字符集 **utf8mb4 / utf8mb4_0900_ai_ci**、`sensitive_word` 种子 **139 行**与 classpath 快照同源。

跑通过程中修掉的是**脚本自己的两个 bug**（不是 SQL 的错，都是包装层）：

1. **`mysql.exe` 只认命令行第一个位置的 `--defaults-extra-file=`**：原脚本把选项文件路径当普通位置参数传，mysql 于是把它当成数据库名、再用操作系统用户免密登录 → `ERROR 1045 Access denied for user ODBC@localhost (using password: NO)`。这条报错既不提文件路径也不提选项文件，光看文本猜不到根因；用户手工执行失败也是同一处（脚本要求交互输入口令 + 环境变量注入，本机直接跑不通）。正解：口令写进**非仓库内**的 `[client]` 选项文件，命令行以 `--defaults-extra-file=<path>` 开头。
2. **PowerShell 的 `$arr -notmatch "x"` 返回的是「不匹配的元素」而不是布尔假**：`Invoke-MySqlFile` 先把展示行写进管道再返回 stdout，数组里只要有一行不含 31，`if ($vOut -notmatch "31")` 就为真 —— **一次真正成功的建库被自己的验收语句判成失败**。改为只取管道最后一个元素，并匹配表格单元格本身（正则 `\|\s*31\s*\|`），注释里写清「为什么不能整数组匹配」。

### 交付物（全部为实测计数）

| 类型 | 文件 | 实测事实 |
|---|---|---|
| 服务 | `post/PostService.java`（新，617 行） | 八步顺序：配额与账号状态 → 字段合规 → 配图一致性（≤9 张 / ≤20MB，按服务端读盘字节） → 话题存在且已过审 → 落 `DRAFT` + 流转日志 → DFA 机审 → 终态 + 第二条日志 → 危机命中**同时**建 `alert_ticket`；`@Transactional`；`now` 由调用方传入，让配额、发布时间、SLA 落在同一时间基准 |
| 分级 | `post/CrisisGrader.java`（新，137 行） | 词面通道输出域 {L0, L2, L3}（刻意不产 L1：需求 §5.2 的 L1 要「同一用户连续 3 条」的时间序列，单条文本判 L1 属凭空造数据）；`L3_WORDS` 11 个「方式 / 计划 / 告别」语义；L2 分 0.6 + SLA 4h，L3 分 0.9 + SLA 30min；它是任务 4.11 双通道 `RiskScorer` 的规则通道前身，接口形状不变 |
| 接口 | `web/PostController.java`（新，55 行） | `POST /api/posts`；**REJECTED 与 HUMAN_REVIEW 都回 200**，处置结果在 `data.status` + `data.tip`；真正的入参错误才 400/10001 |
| DTO | `post/dto/CreatePostRequest.java`、`post/dto/PostView.java`（新） | 出参字段白名单实测 15 个，**不含 `risk_level`**（等级只给服务端与管理端 · NFR8）；非树洞不出现 `autoDestroyAt` |
| 实体与映射 | `entity/{Post,PostImage,PostTopic,PostStatusLog,AlertTicket}.java` + 同名 5 个 Mapper（新） | 列名与 DDL 逐字对齐；`TopicMapper.increasePostCnt` 走 SQL 原子自增（读出来加一再写回会覆盖别人的计数） |
| 改造 | `post/AnonymousAliasRepository(Adapter)`、`AnonymousAliasService` | `insertIfAbsent` 返回值由 `String` 改成**整行**：发帖要写 `post.alias_id` 外键，只拿名字就得再查一次，而「再查一次」在并发下可能查到别人的行；「唯一键冲突却又查不到对手行」改为**失败关闭**（抛 IAE），绝不给匿名帖留 `alias_id=null` 的孤儿（FR1.4 要求真实身份可回溯） |
| 改造 | `upload/ImageUploadService.java` | 新增 `inspect(url)` 与 `resolveUnderBase(base, url)`：发帖这一刻只认 URL，尺寸、类型、字节数一律由服务端重新读盘得到，客户端连「我这张图多大」都没机会谎报 |
| 改造 | `audit/SensitiveWordEngine.CheckResult`、`audit/dto/PrecheckView` | 新增 `riskTouched()`；`hotline` 从「主因是 risk 才给」改成「**出现过 risk 命中就给**」—— 2026-09-20 真 HTTP 打预检时发现「既写自伤又留手机号」的文本主因被判成隐私泄露，按主因走最需要卡片的人恰好拿不到卡片 |
| 改造 | `auth/AuthService`、`auth/dto/RegisterRequest` | 年级 `normalizeGrade` 白名单（FRESH/SOPH/JUNIOR/SENIOR/OTHER）+ DTO `@Pattern`：脏值不再流到 MySQL ENUM 列（否则驱动抛 1265 Data truncated → 被兜成 90002/503「数据读取不到」，排查方向完全错）。这条是真注册炸出来的 |
| 改造 | `captcha/CaptchaService`、`config/MindisleProperties`、`application.yml` | 新增 `mindisle.captcha.enabled`（默认 true，fail-closed）；关掉时跳过校验且**启动打一条 WARN**（日志实录 15:46:47），`.env.example` 补注释「仓库模板必须留 true」 |
| 配置 | `MindisleProperties.Crisis` / `.Post` | 阈值、SLA、证据字数、标题与正文长度、话题数、树洞销毁档位全部可配（NFR10 不写死），`application.yml` 每一项后面标需求编号 |
| 测试 | 新增 `PostServiceTest` **13**、`CrisisGraderTest` **13**、`PrecheckViewTest` **4**、`AuthServiceGradeTest` **14**；`CaptchaServiceTest` 6→**8**、`AnonymousAliasServiceTest` 10→**12**、`ImageUploadServiceTest` 14→**17** | 全库 `mvn -o -B test` 实测 **`Tests run: 131, Failures: 0, Errors: 0, Skipped: 1` / BUILD SUCCESS**（v1.1.5 为 80），日志 `backend/target/verify-t33.log`、`verify-t33-r2.log` |
| 冒烟 | `docs/smoke.mjs`（251 → **427 行**） | 11 步 **45 项 / 断言 41 条 / 失败 0 条 / EXIT=0**；四轮实跑 15:47:26、15:49:00、15:49:48、**15:51:41**，断言数从 36 涨到 41，每一轮都真打 HTTP |
| 夹具 | `sql/11_smoke_fixture.sql`（新） | 幂等 upsert 一条 `PENDING` 话题（实测 id=**21**）供 30004 用例，注释写明「这是冒烟的前置，不跑就没有这条断言」 |

### 本轮真金白银的 3 条 bug（两条真 bug，一条是我自己把断言写错）

1. **Java 15 起允许字面量里的未知转义，正则 `\s` 少写一个反斜杠不会编译失败**：`CrisisGrader` 里的空白归一化必须是 `replaceAll("\\s+", " ")`；写成 `replaceAll("\s+", " ")` 在 JDK 17 里 `s` 是**字面空格**（不是正则的空白类），语义从「压掉所有空白」变成「压掉空格」，且**一句报错都没有**。取证方式不是用眼看，是把源码字节读出来打 charCode：实测 `92,92,115` —— 确有两个反斜杠。教训：正则一律按字节取证，肉眼和渲染器都不可信（本工具链还会把显示折叠成一种写法）。
2. **`..%2f` 断言写错，逼出一次真正的加固**：我原先断言「百分号编码的穿越样本会被解码后再判」，实测发现当前实现**根本不解码**，`..%2f` 里那两个点在文件系统里不是目录 —— 样本既没穿越也不被拒，断言的前提（「它会解码」）是假的。先用一次真实调用证伪自己的假设，再决定改哪一边：这次**改实现**（`resolveUnderBase` 见 `%` 直接拒），理由是服务端自己生成的 URL 只含 hex、斜杠、点和扩展名，出现 `%` 就说明这地址不是本服务给的；将来这条链路换成对象存储 SDK 或 URI 解析，任何一层做 percent-decode，今天「其实逃不出去」的输入就会变成真穿越。改断言与改实现两边的理由都写进了代码注释，不许只留结论。
3. **`PostingQuotaService` 的注释里藏着一句假承诺**：v1.1.4 写的是「T3.3 落地后会在发帖事务内用 `SELECT COUNT(*)` 校准当日额度」，而 T3.3 真落地时评估完**决定不做**，注释原样留着就是给下一个接手的人画饼。本轮改成「评估过 + 三条不做的理由（① 这是防灌水的粗粒度闸门不是账务，少算几帖由 BR5 次日重置自然兜住；② 校准 SQL 要按「自然日 + 含 REJECTED/已删除」的口径写才和缓存数对得上，口径写错会让正常用户被误限，比归零更糟；③ 每次发帖多一次范围扫描，代价落在最热的写路径上）」+ 保留「**别把计数器当审计数据用**」。**代码注释里的「将来会做」也是承诺，落地后必须回改。**

### 冒烟脚本的两条顺序约束（实测出来的，不是设计时想到的）

- **负面组必须排在正面前**：`assertCanPost` 是发帖流程第一道闸，额度用满之后**所有**请求（含非法参数）都回 429 —— 那时「缺标题 → 400」这类用例会以「校验坏了」的假象失败。所以第 9 步（校验与拒绝语义）跑在第 10 步（状态机正向）之前。
- **每个正向组各注册一个新账号**（`smoke_post_<yyyyMMddHHmmss>` / `smoke_care_<…>`）：新手期 5 帖/天 + 计数在缓存 + 按自然日重置，复用固定账号会让「当天第二次跑」在第一条正向断言上撞 429。时间戳用 `slice(0,14)`（含秒），否则同名撞 `20002 用户名已占用`。第 11 步（黑词 + 危机同句）**单独再开一个账号**：它是创新点 3 唯一例外分支，任何已发过帖的账号都排不到它。
- **10.6 的 429 断言同时钉住「REJECTED 也消耗配额」**：那 5 条里 4 条 PUBLISHED + 1 条 REJECTED，第 6 条必须被挡；若哪天有人把 REJECTED 改成不消耗，第 6 条就会 200，断言当场红。
- 10.4 的 BLOCK 帖**故意也挂已过审话题**，就是为了下一节那个 `post_cnt` 差值能证成。

### 真库取证（`post` 18..23 = 最后一轮冒烟 15:51:41 写进去的行）

| id | user | type | status | risk_level | anon | alias_id | floor_no | published_at | auto_destroy_at |
|---|---|---|---|---|---|---|---|---|---|
| 18 | 15 | normal | PUBLISHED | L0 | 0 | NULL | NULL | 15:51:42.148 | NULL |
| 19 | 15 | **help** | PUBLISHED | **L2** | 0 | NULL | NULL | 15:51:42.176 | NULL |
| 20 | 15 | normal | PUBLISHED | **L3** | 0 | NULL | NULL | 15:51:42.208 | NULL |
| 21 | 15 | normal | **REJECTED** | L0 | 0 | NULL | NULL | **NULL** | NULL |
| 22 | 15 | **hole** | PUBLISHED | L0 | **1** | **4** | **4** | 15:51:42.340 | **2026-09-27 15:51:42.340** |
| 23 | 16 | normal | **REJECTED** | **L2** | 0 | NULL | NULL | NULL | NULL |

`alert_ticket` 三个字段级事实（注意列名是 `source_type` + `source_id`，**这张表没有 `post_id` 列**）：

- id 9 · **L2** · src=post/19 · `risk_score` **0.600** · trigger_words `伤害自己` · SLA **240** 分钟 · evidence 21 字
- id 10 · **L3** · src=post/20 · `risk_score` **0.900** · trigger_words `把东西分给室友，最后一次跟这里说说话` · SLA **30** 分钟 · evidence 29 字
- id 11 · **L2** · src=post/**23** · 0.600 · `不想活` · SLA 240 分钟 · evidence 26 字，原文是 `冒烟·黑词里的人 他说不想活了，还要卖 **** 给我。` —— **黑词被遮成星号、危机原句保留**，FR10.5「脱敏后 200 字上下文」第一次在真库里被看见，不是推断
- `SELECT COUNT(*) FROM alert_ticket WHERE source_id = 21` → **0**：纯黑词命中不建单。工单是危机干预资源，不能被广告贴占满，这条边界必须留在证据里。

`post_status_log`：6 帖 × 2 行 = 12 行，状态链完整。第 1 行 reason 一律 `system|dict=v0.1`（写清是哪个版本的词典做的决定，申诉与复现都靠它）；第 2 行按分支：18/22 `机审通过:DFA 无拦截与复核命中`；19/20 `危机命中(L2/L3)按需求 §18.3 放行:删除等于把人推回沉默`；21 `机审命中拦截词(black)，内容已拦下`；23 `机审命中拦截词(black)，且同时命中危机词:内容拦下、工单照建`。

话题计数（本轮最干净的一条量化证据）：`link_rows=6`，其中 `post.status=PUBLISHED` 的 **4** 行、非 PUBLISHED **2** 行，而 `SUM(topic.post_cnt)=4` —— **「关联行照写、`post_cnt` 只对 PUBLISHED 自增」量化成立**。topic 1「失眠夜」APPROVED cnt=4；topic 21「冒烟待审话题」PENDING cnt=0。

其余实测：`post_image` 1 行（post 18，url `/uploads/2026/09/20/115c2cae4eb44bac9822d73ae9b01462.png`，sort 0，**120x40**，`hash` 为空串 —— 秒传未做）；`anonymous_alias` 4 行（四次运行四张脸：观澜 / 溪见 / 与舟 / 子衿，对应 `floor_no` 1..4 递增）；库计数 `sw=139 users=15 posts=23 tickets=11 logs=46 topics=21 links=6 imgs=1 aliases=4`；`post` 按状态 PUBLISHED 16 / REJECTED 7；`alert_ticket` 按级别 L2 pending 7 / L3 pending 4；冒烟账号 id 8 `smoke_runner` 与 id 10/11/13/15 `smoke_post_*`、12/14/16 `smoke_care_*`。SQL 与输出存档在 `_cache/mindisle-dbtmp/evidence*.sql|txt`。

### 冒烟第 9 / 10 / 11 步的实测输出（摘关键几条）

- 缺标题 → `400 {"code":10001,"msg":"title 标题要写点什么才好"}`；`visibility` 非法 → 「可见范围只能选公开或仅自己」；`type` 不在白名单 → 「内容形式不合法，请重新选择」；非树洞传 `autoDestroyHours` → 「到期销毁只对树洞开放，普通帖与求助帖请留空」⇒ **Bean Validation 在服务之前先挡**，服务层还有一道同义兜底（防的是绕过 Controller 的内部调用）。
- 话题不存在 → 「这个话题不存在（id=99999999），请重新选择」；假图 URL → 「配图不存在或已失效，请重新上传」；待审话题 → **409 / 30004**「话题「冒烟待审话题」还没通过审核，通过后就能挂了」—— 用户此刻能做的是等，不是改，所以不给 400。
- 危机文本 → **仍然 200 + PUBLISHED + `hotline=12356`**（创新点 3 的正身）；出参字段实测 `id,status,type,title,content,visibility,anonymous,displayName,topics,images,hotline,tip,publishedAt,createdAt`，**无 `risk_level`**。
- 提示语不回传命中词面（否则等于给出一份可迭代的钓词库反馈）：10.4 → 「内容里有不能公开的部分，这条没有发出去；改一改再发也来得及。」；第 11 步 → 「…已经被拦下；但你在里面写的那句话我们看见了。改一改还能再发，也可以直接打下面的电话，不用先跟任何人解释。」
- 429 → `{"code":10010,"msg":"今日已发 5 帖，达到上限 5 条（新注册 24 小时内限 5 帖），明天再来吧"}`。

### 复跑命令（照抄可用）

```powershell
# 1) 后端：必须离线 + 指定 E 盘本地仓库（C 盘 .m2 里没有 Boot 4.1.1）
cd backend; mvn -o -B "-Dmaven.repo.local=E:/codex workspace/_cache/m2/repository" spring-boot:run
# 2) 单测：期望 Tests run: 131, Failures: 0, Errors: 0, Skipped: 1
mvn -o -B "-Dmaven.repo.local=E:/codex workspace/_cache/m2/repository" test
# 3) 真 HTTP 冒烟：需后端在 8080、根 .env 里 MINDISLE_CAPTCHA_ENABLED=false、已执行过 sql/11
node docs/smoke.mjs            # 期望 45 项 / 断言 41 条 / 失败 0 条 / EXIT=0
```

冒烟数据是**故意留在库里的**（每跑一次 +2 账号、+6 帖、+1~2 工单、+1 张图，这是正常现象不是 bug）。要清的时候按这个顺序删，外键才不会拦：

```sql
DELETE FROM alert_ticket    WHERE user_id IN (SELECT id FROM user WHERE username LIKE "smoke\_%");
DELETE FROM post_status_log WHERE post_id IN (SELECT id FROM post   WHERE user_id IN (SELECT id FROM user WHERE username LIKE "smoke\_%"));
DELETE FROM post_image      WHERE post_id IN (SELECT id FROM post   WHERE user_id IN (SELECT id FROM user WHERE username LIKE "smoke\_%"));
DELETE FROM post_topic      WHERE post_id IN (SELECT id FROM post   WHERE user_id IN (SELECT id FROM user WHERE username LIKE "smoke\_%"));
DELETE FROM anonymous_alias WHERE user_id IN (SELECT id FROM user WHERE username LIKE "smoke\_%");
DELETE FROM user_consent    WHERE user_id IN (SELECT id FROM user WHERE username LIKE "smoke\_%");
DELETE FROM user_profile    WHERE user_id IN (SELECT id FROM user WHERE username LIKE "smoke\_%");
DELETE FROM post            WHERE user_id IN (SELECT id FROM user WHERE username LIKE "smoke\_%");
DELETE FROM user            WHERE username LIKE "smoke\_%";
DELETE FROM topic           WHERE name = "冒烟待审话题";
```

> **口令口径（写死在这里，免得下一轮又去猜）**：MySQL root 口令**不进仓库、不进文档、不进日志、不进对话正文**，只存在于非 git 目录 `_cache/mindisle-dbtmp/rootpwd.cnf`（`[client]` 选项文件，39 字节，用完即可删）；应用账号 `mindisle` 的随机口令在项目根 `.env` 的 `DB_PASSWORD`（`.gitignore:2` 已排除 `.env`）。`mysql.exe` 传口令的唯一可用姿势是把 `--defaults-extra-file=<path>` 放在命令行**第一个位置**。

### 同轮文档对齐

- 手册升 **v1.1.6**：§6.1 行 3.3 补 v1.1.6 实测回写 8 条、§15 **T3.3 ☐→☑**、**T3.12 ◐→☑**（配额终于有了真实调用方 `PostService`，并明确决定不做重启 COUNT 校准）、**T3.1 ◐→☑**（「已登录 → 200 上传 + 挂图进帖」这条欠账本轮结清）、**T3.4 ◐→☑**（马甲分配第一次跑在真库上：4 张脸 + `post.alias_id` 外键 + `floor_no` 1..4）；T3.2 的「未经真实 HTTP 验证」欠账同步销掉但它**仍保持 ◐**（状态机接线与灰词人审闭环未做）。阶段 3 口径重算为 **☑ 4 / ◐ 1 / ☐ 12**；§18 Gate3 状态、§19 下一步、§20 变更表同步。
- README：单测数 80 → **131**、新增「真 HTTP 冒烟」用法与期望输出、**把 `backend\.env` 纠正为项目根 `.env`**（`spring-boot.run` 配的是 `env-file: ../.env`，原来那句照着做会读不到口令）、补 `POST /api/posts` 契约与状态码口径。
- 顺手清理：删 6 张未被任何 `post_image` 引用的上传图与 `_cache/probe-positions.mjs`，只保留被 post 18 引用的那一张；空目录逐层 `rmdir`（不对 `uploads/` 用递归删除）。

### 仍未做（截至本轮，别自我感觉良好）

- **`HUMAN_REVIEW` 帖没有进 `audit_task` 队列**：状态机有这条分支、`PostServiceTest` 也测了，但这一轮真库里**一条都没产生**（灰词 REVIEW 会被「危机放行」或「直发」吃掉），管理端看不到待审内容 —— 归 **T6.1**。
- 工单只落库，**没有任何通知**：管理员不看表就看不见 L3 的 30 分钟 SLA —— 归 T6.4 的告警链路。这是本轮最需要被记住的产品缺口。
- 30004 用例依赖手工先执行 `sql/11_smoke_fixture.sql`（自动建夹具会污染 `topic` 表，权衡后保留手工步骤，脚本注释里写明）。
- 配额重启归零（本轮已明确决定不做 COUNT 校准）、`floor_no` 用 MAX+1 并发下可同号、`L3_WORDS` 与词库 v0.1 强耦合（有快照测试作 tripwire，改词必红）、`auto_destroy_at` **只写不扫**（销毁 job 属 T3.15）、`post.emotion_*` 列仍 NULL（阶段 4）。
- **前端发布器还没对齐新契约**：`frontend/src/views/FeedView.vue` 发的仍是 `{content, topicId, mood, anonymous}` 且缺必填 `title`，接上后端必然 400 —— 归 T3.13，是阶段 3 下一步的头等事。
- `GET /api/posts`、`/api/posts/{id}`、`/api/feed/recommend` 仍是 90001（T3.5 / T3.10 未开工）；毕设材料（T1B.*）按用户指令顺延；**仍未打 tag**（Gate 3 未过，最新 tag `stage-2-skeleton`）。

## 2026-09-20 阶段 3（续 3）—— T3.5 列表/详情 + 浏览量缓存回写，以及 T3.13 前端第一批（U3/U4/U5）

### 交付物：后端（实测计数）

| 文件 | 行数 | 作用 |
|---|---|---|
| `post/PostQueryService.java`（新） | 463 | `list()` 游标/页码双模式 + `detail()`；可见性唯一判据 `visibleTo`；页内批量取图片/话题/用户/马甲防 N+1 |
| `post/ViewCountService.java`（新） | 106 | 浏览量进缓存、**每帖每 5 分钟最多回写一次**；展示值恒等于「库值 + 未回写增量」 |
| `config/ViewCountConfig.java`（新） | 33 | 端口—适配器装配：`new ViewCountService(cache, postMapper::increaseViewCnt)`，业务类保持零 Spring 依赖可裸测 |
| `post/dto/PostListItem.java`（新） | 57 | 列表出参（含 `excerpt`/`auditTip`/`hotline`/`autoDestroyAt`） |
| `post/dto/PostDetailView.java`（新） | 56 | 详情出参（正文全文 + `visibility`） |
| `mapper/PostMapper.java`（改） | +13 | `increaseViewCnt`：**`view_cnt = view_cnt + #{delta}`** 库内原子累加，不是「读出来加一再写回去」 |
| `mapper/PostImageMapper.java`、`mapper/PostTopicMapper.java`（改） | +21 / +16 | 按 `post_id IN (...)` 批量取周边，页内一次查完 |
| `web/PostController.java`（改） | +41 | `GET /api/posts`、`GET /api/posts/{id}` 收进同一个类（读写共用一套可见性判据，避免 404/403 口径分裂） |
| `web/FeedController.java`（改） | -12 | 删掉这两个 GET 的 90001 桩。**桩不删就是启动期 ambiguous mapping，服务整个起不来**（和上一轮 POST 桩同一颗雷） |
| `web/PostService.java`（改） | ±8 | `PostView` 出参字段对齐新 DTO |
| `test/post/PostQueryServiceTest.java`（新） | 308 / **17 例** | 可见性矩阵、游标不重叠、`type` 白名单、匿名不回 `authorId`、`hotline` 判据、到期树洞读侧隐藏、作者自看不计数 |
| `test/post/ViewCountServiceTest.java`（新） | 147 / **5 例** | 窗口翻转、`getAndDelete` 不重复累加、展示值不倒退 |

`mvn -o -B test`（离线 + E 盘本地仓库）实测 **`Tests run: 153, Failures: 0, Errors: 0, Skipped: 1` / BUILD SUCCESS**（上一轮 131，净增 22 = 17 + 5），日志 `backend/target/verify-t35b.log`。服务重启为 **run13**：`Started MindisleApplication in 4.353 seconds`，无 ambiguous mapping，只有验证码关闭时那条预期 WARN。


### 交付物：前端 T3.13 第一批（U3 广场 / U4 详情 / U5 发布器）

| 文件 | 行数 | 作用 |
|---|---|---|
| `src/api/post.js`（新） | 16 | `listPosts` / `postDetail` / `createPost` + `POST_TYPES` 常量；读接口 `silent:true` |
| `src/api/audit.js`（新） | 8 | `precheck(text, scene)`，silent —— 防抖自动调的接口不该每 300ms 弹一次红条 |
| `src/api/file.js`（新） | 11 | `uploadImage` 走 http 实例拼 `FormData`（只有这样才能自动带上 Authorization） |
| `src/api/feed.js`（改） | 9 | 只剩 `topics` / `recommend`，posts 三件套移走归位 |
| `src/stores/feed.js`（重写） | 111 | 游标状态机：`fetchPage({replace})` / `applyRows` 去重回填 `nextCursor`、`hasMore`、`total`；`setType` / `prepend` / `dismiss` / `reset` |
| `src/utils/format.js`（新） | 64 | `toDate`（后端给的是**无时区本地 ISO 串**，`"YYYY-MM-DD HH:mm:ss"` 手工补 T，Safari 不认空格分隔）、`fromNow`、`countdown`、`fmtDateTime`、`fmtCount` |
| `src/components/CrisisCard.vue`（新） | 44 | 12356 求助卡片（`level=inline/card` 两种形态），发布器 / 详情 / 列表三处共用 |
| `src/components/PostCard.vue`（新） | 81 | 列表卡片：形式标签 / 马甲名 / 匿名标记 / 相对时间 / 树洞倒计时 / 图片预览 / `auditTip` / `hotline` / 三个计数 / 「不感兴趣」 |
| `src/components/PostComposer.vue`（新） | 402 | U5 发布器主体：形式切换、标题≤50、正文≤5000、话题≤3、可见性、匿名（树洞强制开且禁用）、销毁档位 24/72/168h、配图≤9、**防抖 300ms 预检**、localStorage 草稿、发布结果三态回显、413 特判 |
| `src/views/post/PostDetailView.vue`（新） | 142 | U4 详情；`30001` 文案写「这条内容你现在看不到」而**不写「不存在」**（前端也不能替服务端承认存在性）；不做本地 +1 |
| `src/views/post/PublishView.vue`（新） | 60 | `/publish` 独立页，复用同一个 composer + 四条规则说明 |
| `src/views/feed/FeedView.vue`（重写） | 230 | U3 广场：composer 紧凑形态 + 全部/树洞/求助/分享 Tab + `IntersectionObserver` 无限滚动 + total 行 + `errorCode`→`StageNotice` + 推荐占位 + 话题墙 |
| `src/router/index.js`、`src/layouts/BasicLayout.vue`、`src/api/auth.js`（改） | +3 / +1 / 32 | 新增 `publish`、`post/:id`（name `post-detail`）；导航加「发布」；`NOT_IMPLEMENTED_YET` 按 web 层真实 `@*Mapping` 重写为 11 条 |

`npm run build` **exit 0 / `✓ built in 5.80s`**（日志 `E:/codex workspace/_cache/mindisle-dbtmp/build-t313.log`），产物含 `PostComposer-*.js 11.79 kB`、`FeedView-*.js 10.06 kB`、`PostDetailView-*.js 4.66 kB`；只剩 500 kB chunk 那条老警告。

**前端→后端真取数（Vite 5173 代理到 8080，不是 mock）**：注册 `fecheck_*` 200 → `GET /api/posts?size=3` **200 / total=21 / nextCursor=26 / 首条 id=32 / `publishedAt="2026-09-20T17:29:22.92"`**；不带 token 打同一路径 **401 / 10002**。

> ⚠ **浏览器渲染未经实测**：`cua.getState()` 本轮返回 `errors:["Browsers: Error: Codex auth token is unavailable"]`，没有任何可驱动的浏览器。所以「U3/U4/U5 视觉与交互已验证」这句话**本轮不成立**，只能主张 build 通过 + 代理级 HTTP 取数通过。Gate 3 里「前端可交互、无 console 红字」那条仍记 ☐。

### 冒烟脚本扩到 13 步（`docs/smoke.mjs` 428 → **560 行**，+132）

实跑命令（需要后端在 8080、根 `.env` 里验证码关闭、`sql/11` 夹具已执行）：

```powershell
$env:SMOKE_PENDING_TOPIC_ID="21"; node docs/smoke.mjs
```

结果：**68 项 / 断言 63 条 / 失败 0 条 / exit=0**（上一轮 45 项 / 41 断言），全文存 `E:/codex workspace/_cache/mindisle-dbtmp/smoke5.txt`。新增两步逐条实测值：

- **第 12 步 · 可见性**：注册 `smoke_seen_20260920092921` 200；`visibility=private` 照样 **200/PUBLISHED/visibility=private**（可见性只决定谁能看见，不影响能不能发）；手机号 → **HUMAN_REVIEW**；干净文本 → **PUBLISHED id=32**。第三人 `size=50` 拉全量：**n=21**，公开帖在列、私密帖与待审帖**整条不出现**；第三人打这两条详情 **404 / 30001**（报 403 等于承认这条存在）。作者自己的列表里待审项带 `auditTip`（实测 `{"id":31,...,"authorId":19}`）；作者的**私密已发布帖不在广场列表**（n=22，走 T3.14「我的帖子」）；但作者本人打自己私密帖详情 **200 + 正文全文可读回**。
- **第 12 步 · 翻页与过滤**：页码首屏也回 `nextCursor`（实测 `cursor=26 total=21 hasMore=true`）；连翻三页 **p1=32,28,26 / p2=25,24,22 / p3=20,19,18** 两两零重叠，且 `nextCursor === p2[2].id`；`type=hole` **n=5 全是 hole**；`type=moment`（白名单外）**400/10001**；无 token **401/10002**；匿名项出参 `{"displayName":"匿名屿民·南栖"}` 且 **`authorId` 字段整个不出现**；help 项 `hotline="12356"`；非 help 但 L2/L3 的 **id=26** 同样带 hotline。
- **第 13 步 · 浏览量**：`base=0`，同一帖连开三次详情展示值 **1 / 2 / 3**；列表（读库值）只比基线多 **1**，而详情是 **3** —— 这就是「三次浏览只发了一条 UPDATE」的直接证据；作者自看展示值 **3**，与第三人一致（计数口径是「你不算」，展示口径必须全平台一致）。

### 真库取证（root 直连，逐字照抄）

```
post:      18|1   26|0   29|0   30|0 private   31|0 HUMAN_REVIEW   32|1 PUBLISHED public user_id=19
alert_ticket: 13  L3  source_id=26  pending  0.900    |  14  L2  source_id=29  pending  0.600
topic:       21  冒烟待审话题  PENDING
```

`post 32 view_cnt=1` 与上面「列表=1 / 详情=3」互为印证：展示值 = 库值(1) + 未回写增量(2) = 3。`post 18 view_cnt=1` 是上一轮手工探测留下的，本轮 SQL 复核**仍为 1**，说明第 13 步没有误伤旧数据。

### 🔧 用户那条「执行不成功」的 mysql 口令：根因定位与可用写法（本轮用户明确要求写进日志）

用户反馈同一条 mysql 命令跑不出来。root 口令已由用户口头提供，**明文不进任何文件、日志、回复**，只写进非 git 目录 `_cache/mindisle-dbtmp/rootpwd.cnf`（`[client]` 段，39 字节）。定位结果是**两条叠加**，都不是口令本身错：

1. **中文输出被按 GBK 解码成了乱码**（看起来像「跑失败」）。两头都要改：`mysql.exe` 要显式带 `--default-character-set=utf8mb4`，PowerShell 侧要先 `$OutputEncoding=[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false)`。少任何一头，`SELECT name FROM topic` 回来的就是 `ð̴` 一类的方块，人立刻怀疑命令写错了。
2. **查了不存在的列**，报 `ERROR 1054 (42S22): Unknown column`。两张表的列名和直觉不一致，**本轮我自己也踩了同样两条**：`topic.status` → 真名 **`topic.audit_status`**；`alert_ticket.post_id` → 真名是 **`source_type` + `source_id`**（工单不止能挂帖子）。所以写 SQL 前先看 `sql/*.sql` 里的 DDL，别按需求文档里的中文措辞猜列名。
3. 附带一条老坑仍会复现：`--defaults-extra-file=` 必须是**命令行第一个参数**，放后面会被当普通参数丢掉，于是拿系统当前用户免密去连，报 `1045 Access denied for user 'ODBC'@'localhost'` —— 错误里的用户名和口令都不来自你填的那份，极其误导人反复改口令。

**照抄可用**：

```powershell
$OutputEncoding=[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false)
& 'C:\Program Files\MySQL\MySQL Server 9.7\bin\mysql.exe' `
  "--defaults-extra-file=E:\codex workspace\_cache\mindisle-dbtmp\rootpwd.cnf" `
  --default-character-set=utf8mb4 -N -B -D mindisle -e "SELECT id, name, audit_status FROM topic"
```

- `-N` 去表头、`-B` 走批次模式（输出 `\t` 分隔，管道里不会被 ASCII 表格框吞掉），要人读就去掉 `-N -B`。
- 口令永远走选项文件，**不要**在命令行写 `-p<明文>`：它会留在 PowerShell 历史与进程列表里，且违反本项目「口令不入仓库/日志/对话」的口径。

### 本轮真实踩到的坑（都有证据）

1. **列表 SQL 与详情 `visibleTo` 是两套判据，必须各自测**：私密已发布帖「不进广场」但「作者详情可读回」，这是分工不是 bug。若只测详情，就会写出一个「广场能刷到自己私密帖」的实现而全绿。
2. **`auditTip` 早期用 `isOwner(post, post.getUserId())` 恒真** —— 待审提示会贴到别人的信息流上，顺带把「谁在被审核」泄露给全广场。修法是 `toListItems(viewerId, ...)` 一路把 viewerId 传到底。
3. **删桩比加接口更容易致命**：`FeedController` 里两个 GET 桩不删，`mvn test` 全绿、`spring-boot:run` 直接起不来（启动期 ambiguous mapping）。上一轮 POST 桩已付过一次学费，本轮提前想到并写进了类注释。
4. **窗口判定不能用缓存 TTL**：本地降级实现 Caffeine 的条目「逻辑过期但未清理」时 `asMap().compute` 仍可能看到旧值，把「key 不存在」当窗口边界，最坏结果是窗口永不翻转、增量一直堆在缓存里不落库 —— 比丢几个数更糟。改成显式存「本窗口起始秒」+ 判定只依赖比较，且 `now` 由调用方注入（与 `PostService.publish` 同口径）。
5. **`view_cnt` 回写用 `view_cnt = view_cnt + delta` 而不是 `set view_cnt = ?`**：后者是读—算—写，两个实例各拿旧值回写就把对方覆盖掉。`WHERE` 刻意不带 `status`（帖子在这一个窗口里被下架，浏览量也是已发生的事实），但带 `deleted = 0`。
6. **PowerShell 里 `node -e "…含正则字面量的串…"` 会因引号解析炸成 `Invalid or unexpected token`**：凡涉及正则和文件内容检查，改在 node REPL 里做。
7. **`cua` 浏览器驱动不可用（auth token unavailable）**：这不是「前端不用测」的理由，而是**必须把没测的部分写白**的理由。


### 本轮库里净增了什么（跑一次冒烟就会永久改库）

- **+2 账号**：`smoke_seen_20260920092921`（id=19，第 12/13 步的正面作者）、`fecheck_20260920095710`（前端代理取数用）。
- **+3 帖**：post 30（private/PUBLISHED）、31（HUMAN_REVIEW）、32（PUBLISHED public，`view_cnt=1`）。
- **工单、话题、图片零净增**（第 12/13 步不再产生危机词与图片）；`topic 21` 仍是上一轮的 `PENDING` 夹具。

清理按外键顺序，**前缀要两个都写**（`fecheck` 不在 `smoke\_%` 里）：

```sql
SET @p1 = "smoke\_%"; SET @p2 = "fecheck\_%";
DELETE FROM alert_ticket    WHERE user_id IN (SELECT id FROM user WHERE username LIKE @p1 OR username LIKE @p2);
DELETE FROM post_status_log WHERE post_id IN (SELECT id FROM post   WHERE user_id IN (SELECT id FROM user WHERE username LIKE @p1 OR username LIKE @p2));
DELETE FROM post_image      WHERE post_id IN (SELECT id FROM post   WHERE user_id IN (SELECT id FROM user WHERE username LIKE @p1 OR username LIKE @p2));
DELETE FROM post_topic      WHERE post_id IN (SELECT id FROM post   WHERE user_id IN (SELECT id FROM user WHERE username LIKE @p1 OR username LIKE @p2));
DELETE FROM anonymous_alias WHERE user_id IN (SELECT id FROM user WHERE username LIKE @p1 OR username LIKE @p2);
DELETE FROM user_consent    WHERE user_id IN (SELECT id FROM user WHERE username LIKE @p1 OR username LIKE @p2);
DELETE FROM user_profile    WHERE user_id IN (SELECT id FROM user WHERE username LIKE @p1 OR username LIKE @p2);
DELETE FROM post            WHERE user_id IN (SELECT id FROM user WHERE username LIKE @p1 OR username LIKE @p2);
DELETE FROM user            WHERE username LIKE @p1 OR username LIKE @p2;
-- 顺带：上一轮 6 条冒烟账号一起清时把 @p1 换成 LIKE "smoke\_%" 即可；topic 夹具单独 DELETE FROM topic WHERE name = "冒烟待审话题";
```

> 上面这段用 `SET @p1` 而不是把 LIKE 串抄九遍，是因为 `\_` 手写第二次就会漏反斜杠 —— 漏了之后 `smoke_%` 会连 `smokeXabc` 一起匹配，清库清过头。

### 复跑命令（照抄可用）

```powershell
# 后端单测：期望 Tests run: 153, Failures: 0, Errors: 0, Skipped: 1
cd backend; mvn -o -B "-Dmaven.repo.local=E:/codex workspace/_cache/m2/repository" test
# 起服务（.env 在项目根，spring-boot.run 配的是 env-file: ../.env）
mvn -o -B "-Dmaven.repo.local=E:/codex workspace/_cache/m2/repository" spring-boot:run
# 冒烟：期望 68 项 / 63 断言 / 0 失败 / EXIT=0（需先执行 sql/11 并把 SMOKE_PENDING_TOPIC_ID 指到 PENDING 话题）
$env:SMOKE_PENDING_TOPIC_ID="21"; node docs/smoke.mjs
# 前端：npm 缓存已在 E:/codex workspace/_cache/npm；期望 exit 0
cd frontend; npm run build
```


### 同轮文档对齐

- 手册升 **v1.1.7**：§6.1 行 3.5 标「已落地」并追加 v1.1.7 实测回写 8 条；§15 **T3.5 ☐→☑**、**T3.13 ☐→◐（第一批）**，阶段 3 口径重算为 **☑ 5 / ◐ 2 / ☐ 10**；§18 Gate3、§19 变更表与下一步同步。**任务总数、人日、追溯矩阵、Gate 行数未变：117 条 / 144.30 人日 / 101 行 / 10 行。**
- README：单测 131 → **153**、冒烟 45 项 → **68 项 / 63 断言**、补 `GET /api/posts` 与 `GET /api/posts/{id}` 契约、新增前端文件清单；`/v3/api-docs` 复测仍是 **22 paths / 25 operations / 7 分组**（本轮读写接口路径未增减）。
- 全局《复利与踩坑日志》补 009 第 4 轮，重点是上面「mysql 取证三连」与「未实现清单必须按控制器反推」两条。

### 仍未做（截至本轮，别自我感觉良好）

- **`GET /api/posts` 只按发布时间倒序**，「热门」Tab 现在还是走同一份数据（`sort` 参数未实现），需求 U3 的「最新/热门」两个 Tab 只算一个半 —— 归 T3.6 之后补。
- **作者私密已发布帖在 UI 上无路可达**：后端刻意不让它进广场，而「我的帖子」还没做（T3.14 / U12）。当前用户发一条私密帖，除了自己记住链接就没有第二次见到的办法。
- **前端只做过代理级取数，没做过浏览器渲染**（见上面写白那条）。`PostComposer` 的草稿恢复、图片九宫格、Tab 切换、无限滚动的真实交互全部未经点击验证。
- 浏览量未回写增量**只存在于缓存**，进程重启会丢（最多一帖一窗口），与配额重启归零同类，进答辩局限清单。
- `HUMAN_REVIEW` 仍不进 `audit_task` 队列（T6.1）；工单仍无任何通知（T6.4）；`auto_destroy_at` 仍只写不扫（读侧已隐藏，扫表销毁属 T3.15）；`post.emotion_*` 仍为 NULL（阶段 4）。
- U1 首页 / U6 话题圈 / U11 主页 / U12 我的、点赞收藏关注（T3.6）、评论（T3.7）、举报通知（T3.11）、埋点（T3.10）全部未开工；毕设材料（T1B.*）按用户指令继续顺延；**仍未打 tag**（Gate3 未过，最新 tag `stage-2-skeleton`）。

## 2026-09-21 阶段 3（续 4）—— T3.13 第二批：帖子读接口「我的 / 他人主页」+ U11/U12 + 本项目第一次 DOM 级取证

### 起点：上一轮写白的那个洞，本轮堵它

- 手册 §6.1 有一条「3.5 与 3.13 的分工：广场是公共流」，末尾自己写了代价 —— 作者勾「仅自己可见」的已发布帖不进广场，而 UI 没有「我的帖子」入口，**那条帖只能靠记住的链接才能再见到**。这是全项目当时唯一一个「后端故意留白、前端又没有补上」的用户可见洞，所以本轮不挑新功能，先补它。
- 三件事：① 后端两条按用户维度的读接口；② 前端 U12「我的帖子」+ U11「屿友主页」；③ **取证方式升级** —— 上一轮只做到「build 过了 + 代理能取到数」，本轮要把「页面真的渲染出这些数、点下去真的跳对页」变成机器断言。

### 交付物：后端（每条行数都是 `Get-Content` 实数 + 1，与手册同口径）

| 文件 | 行数 | 作用 |
|---|---|---|
| `web/UserPostController.java`（新） | 87 | `GET /api/users/me/posts`（作者视角）与 `GET /api/users/{id:\d+}/posts`（外人视角）。不塞进 `UserController` 的理由写在类注释里：两类依赖面完全不交叠（那边只有 `UserService`，这边只有 `PostQueryService`），合并等于让「改昵称」和「翻帖子」互相牵连。 |
| `post/PostQueryService.java`（改） | 463 → 592 | 新增 `mine()` / `profile()`，并把翻页与组装抽成共用的 `pageResult()` —— **「翻页口径只允许有一份」**，否则广场/我的/主页迟早出现「A 页翻页丢条目、B 页不丢」这种只有真机才发现的偏差。 |
| `post/dto/PostListItem.java`（改） | +5 | 出参补 `status` / `visibility`（前端 U12 的徽标要用；`auditTip` 上一轮已有）。 |
| `web/UserController.java`（改） | ±4 | `@Tag` description 与 `UserPostController` **逐字对齐**。 |
| `test/post/PostQueryServiceTest.java`（改） | +53 | 17 → **20 例**：`mine` 能拿回私密与待审、`profile` 拿不回匿名、`status` 白名单外抛 10001、不存在用户抛 20001。 |
| `test/post/PostListSqlConditionTest.java`（新） | 156 / **6 例** | 三种列表各自的 WHERE 形状（见「取证一」）。 |
| `docs/smoke.mjs`（改） | 560 → 653 | 新增**第 14 步**（我的 / 主页两组）。 |

`mvn -o -B test`（离线 + E 盘本地仓库）本轮复跑实测 **`Tests run: 162, Failures: 0, Errors: 0, Skipped: 1` / BUILD SUCCESS**（上一轮 153，净增 9 = SQL 形状 6 + `PostQueryServiceTest` 3），日志 `backend/target/verify-t313b.log`。

### 取证一：不连库也能钉住 SQL 形状（先证伪一句口头话）

- 「脱离 Spring 上下文就测不了 MyBatis 的条件」——**这句话是错的**，所以先证伪再写用例：`TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Post.class)` 之后，`new LambdaQueryWrapper<Post>().eq(Post::getUserId, 11L).getSqlSegment()` 就能拿到最终 SQL 串，走的是与真实执行同一份解析代码。少了 `initTableInfo` 那一步会直接抛 `can not find lambda cache for this entity`。
- 断言的是**形状 + 参值表**两样：`user_id = #{ew.paramNameValuePairs.MPGENVAL1}` 这类占位符按出现顺序逐个钉，`getParamNameValuePairs().values()` 用 `containsExactlyInAnyOrder` 钉住实参 —— 只看 SQL 串会漏掉「条件对、值错」。断言前把 SQL `replaceAll("\\s+", " ")` 压平：**钉形状不钉排版**，格式化代码不该把安全用例弄红。
- 最值的一条是**用反射钉签名**：`applyPublicProfile` 的参数类型列表必须恰好是 `(LambdaQueryWrapper, long, LocalDateTime)`，且 `long` 只出现一次。语义是「主页判据里不允许有 viewerId 这个位置」—— 哪天有人为了「自己也看看待审与匿名帖」往签名里补一个 viewerId，SQL 断言依旧全绿，而解匿已经发生。这条护栏只能靠签名形状钉。
- 主页判据三重收窄：`status=PUBLISHED` + `visibility=public` + `(is_anonymous IS NULL OR is_anonymous = 0)` + `alias_id IS NULL`。「非匿名」写成 `IS NULL OR = 0` 而不是 `= 0`，因为该列可空，历史行会用 `= 0` 整片消失。
- `STATUS_FILTERS` 用 `Set.of`（8 态，与 DDL 逐字一致而不是与 `PostService` 的 5 个写入常量一致 —— 读侧必须能筛出 `TAKEDOWN`）；报错文案里那个列表**必须 `sorted()` 后再拼**，`Set.of` 迭代顺序每次随机，会让同一次报错出现两种说法，前端也没法断言。

### 取证二：代理级 10 项（`E:\codex workspace\_cache\mindisle-dbtmp\proxycheck.mjs`）

跑法：`cd "E:\codex workspace\_cache\mindisle-dbtmp"; node proxycheck.mjs`（前置：后端 8080 + Vite dev 5173 都在跑）。本轮复跑 **共 10 项，失败 0 项**：

```
PASS [A] 经代理登录取 accessToken                 <- HTTP 200 code=0
PASS [B] /api/users/me/posts 回 4 条               <- HTTP 200 ids=42,41,39,40 total=4
PASS [B] status=HUMAN_REVIEW 只剩待审那条           <- ids=40
PASS [B] 白名单外的 status → 400/10001             <- HTTP 400 code=10001
PASS [C] /api/users/23/posts 只回公开非匿名 1 条    <- ids=41
PASS [C] 主页列表里不含匿名帖 42（前端链路即解匿面）  <- ids=41
PASS [C] 不存在的用户主页 → 404/20001               <- HTTP 404 code=20001
PASS [D] 不带 token 经代理 → 401/10002             <- HTTP 401 code=10002
PASS [D] Vite 能把 U12 源码编译成 ES 模块（dev 按需） <- HTTP 200
```

### 取证三：DOM 级 23 项（`frontend/probe/domprobe.mjs`，本轮最大的方法增量）

思路：**不装 Playwright、不起浏览器**，也能把「组件到底渲染出什么」变成断言。

1. `frontend/probe/entry.js`（701 B）复刻 `main.js` 的挂载顺序（pinia + router + ElementPlus + `App.vue`），并挂 `window.__probeRouter` / `window.__probeMounted` 给探针读路由与确认挂载。
2. `domprobe.mjs` 用**同一套 Vite 配置**程序化 build（`configFile: false` + `@vitejs/plugin-vue` + `unplugin-vue-components` 的 `ElementPlusResolver` + 同样的 `@` alias），输出 `format: "iife"` / `inlineDynamicImports: true` / `minify: false` / `target: es2020` → `E:\codex workspace\_cache\mindisle-dbtmp\fe-probe\probe.js`，实测 **3,013,956 B / 编译约 1.1s**。生产构建与探针构建共用配置，探针才不会「测的是另一份代码」。
3. 经 **5173 dev 代理真登录**（`smoke_seen_20260921020038`）拿真 token，`mount` 之前先 `localStorage.setItem("mindisle_token", token)`，再 `window.history.pushState` 深链到 `/me/posts`。
4. jsdom（`E:\codex workspace\_cache\node_modules\jsdom`，用 `pathToFileURL` 绝对路径 import）+ `runScripts: "dangerously"` + `pretendToBeVisual`，`VirtualConsole` 收 `error` / `warn`；给 `IntersectionObserver` / `ResizeObserver` / `matchMedia` 打空壳（jsdom 没有这三个，不打就整片白屏）。
5. 断言一律走 `until()` 轮询而不是 `setTimeout(0)`；DOM 文本用 `textContent`、按钮用 `dispatchEvent(new w.MouseEvent("click", {bubbles:true}))`。

本轮复跑实测 **23 项 / 0 失败**，六组断言：

| 组 | 钉住的事 |
|---|---|
| 1 | 探针包能挂载；`/me/posts` 深链被路由守卫放行（没被踢去登录页）；地址栏真的停在 `/me/posts`（history 模式深链可用，不是 redirect 后改了地址） |
| 2 | U12 渲染 **4 张卡**，标题逐字来自库里 post 39–42；徽标「已发布×3 + 人工审核中×1 + 仅自己可见×1」；`作者名可点`的卡是 **3 张而不是 4 张**（匿名帖无 `authorId` → 天然不可点）；自己的帖上没有「不感兴趣」；读接口是 silent 的 —— `.el-message` 节点数恒为 0 |
| 3 | 点「未通过」→ 0 张卡并给空态（**不是留着上一份列表**）；点「人工审核中」→ 只剩 `冒烟·转人工`；切回「全部」→ 重新 4 张；整个切 Tab 过程不冒全局错误条 |
| 4 | 点作者名 → 路由到 `/user/23`（**不是**详情页，`.who` 上的 `click.stop` 生效）；U11 只 1 张 `冒烟·被看见`、不含 `冒烟·匿名树洞`；标题按路由参数渲染；写白段（「没有假头像」那段）在页上 |
| 5 | `push("/user/99999999")` → 旧卡片清空 + 「看不到这个主页」专属告警（组件复用时不 `watch` 路由参数就会停在上一家） |
| 6 | `/user/abc` → 「地址里的用户 id 不是数字」，而不是先发一个注定 404 的请求再摆空白 |

- **假 FAIL 一条（值得单独记）**：第 1 组「深链后 `route.path` 应为 `/me/posts`」最初写成 `app.mount()` 之后立刻读 `router.currentRoute` —— 那一刻还停在 START 路由（path = `/`），于是探针报了 FAIL。**不是页面的 bug，是探针读早了**。修法：轮询到 `__probeMounted === true` 且 `currentRoute.path` 稳定后再断言，并额外断言 `window.location.pathname`，把「路由内部状态」和「地址栏」分开钉。
- **jsdom 的边界（写白，别越）**：它证明的是组件逻辑、数据接线、点击与路由、错误态文案；**样式、布局、滚动、真实字体渲染、`el-image` 懒加载全部不在覆盖范围**。所以 §6.4 第 4 条「前端全部可交互、深色主题统一、无 console 红字」本轮**仍判 ☐**，Gate 3 的「U1/U3–U6/U11/U12 可用」也仍不能勾。把 jsdom 的 PASS 写成浏览器实测，是本轮最容易犯也最贵的一次造假。

### 交付物：前端 T3.13 第二批

| 文件 | 行数 | 作用 |
|---|---|---|
| `src/composables/usePagedPosts.js`（新） | 94 | `usePagedPosts(fetcher, opts)` → `{items, loading, hasMore, total, errorCode, reload, loadMore}`。**不做成 pinia store** 的理由写进文件头：U11/U12 是单页自用，全局态会出现「切到别人主页看到上一个人的缓存」；口径（`hasMore` / `nextCursor` / `total` 三件套）与 `stores/feed.js` 刻意一致，因为后端三张列表共用同一个 `pageResult`。带 `seq` 号**丢晚到的响应**（不丢请求）。 |
| `src/views/user/MyPostsView.vue`（新） | 117 | U12：四档状态筛选 + 「我发过 N 条 / 该状态共 N 条」计数 + `DB_UNAVAILABLE` 专属文案 + 页尾「这一页还欠什么」。筛选用 `el-radio-group` + `:value` 而不是 `el-tabs`（空 name 的 tab 选中态有坑）。 |
| `src/views/user/UserHomeView.vue`（新） | 110 | U11：非数字 id / 用户不存在 / 正常列表三态；页尾写白「这一页为什么只有一张列表」（缺 `GET /api/users/{id}`，不摆假头像）。 |
| `src/api/user.js`（改） | 11 → 18 | 加 `myPosts` / `userPosts(id, params)`。**不做「一个函数加 flag」**：两个接口的可见性语义不同，混在一起调用方就要替服务端做判断。 |
| `src/api/post.js`（改） | 16 → 42 | `POST_STATUSES` 八态中文（草稿/机审中/人工审核中/已发布/未通过/申诉中/已下架/已删除）+ `statusLabel()`：未知值**原样回显**，不静默变空白 —— 后端加态时前端要看得见，而不是显示一条没有状态的帖。 |
| `src/components/PostCard.vue`（改） | 81 → 109 | 新 prop `showStatus`（默认 false）/ `dismissable`（默认 true）；作者名 `.who.link` 点击 `stop` 后跳 U11；状态与「仅自己可见」两个 `el-tag`。 |
| `src/router/index.js`（改） | 43 → 47 | `me/posts`（name `my-posts`）、`user/:id`（name `user-home`），均 `requiresAuth`。 |
| `src/views/user/ProfileView.vue`（改） | +6 | 「我的帖子」入口。 |
| `frontend/probe/entry.js` + `probe/domprobe.mjs`（新） | 701 B / 274 | 上面那套 DOM 取证工装。 |

`npm run build` 本轮复跑 **exit 0 / ✓ built in 905ms**（首跑 5.86s），`dist` 里能看到 `usePagedPosts-*`、`MyPostsView-*`、`UserHomeView-*` 三个新 chunk。

### 环境事实（下一轮照着跑，不用重新摸）

- 后端仍是上一轮的 **run16** 进程（8080 在监听，本轮**没有改后端代码**，只是重跑单测与冒烟）；Vite dev server 本轮后台起在 5173（`PID 14556`，日志 `E:\codex workspace\_cache\mindisle-dbtmp\vite-dev.out` / `.err`）。
- 探针跑法：`cd "E:\codex workspace\009_心屿AI心理陪伴社区\frontend"; node probe\domprobe.mjs`，前置是 8080 + 5173 都在。日志：`_cache\mindisle-dbtmp\domprobe_r2.txt`、`proxycheck_r2.txt`、`smoke_r3.txt`。
- 冒烟夹具账号 `smoke_seen_20260921020038`（user_id **23**）= post 39（private/PUBLISHED）/ 40（HUMAN_REVIEW）/ 41（public/PUBLISHED）/ 42（public/PUBLISHED + 匿名）的作者 —— **正好是 U11/U12 需要的三种可见性 + 一个待审**，所以本轮取证库里净增 0（探针与代理脚本只读不写）。
- 但本轮为了复核数字**把 `docs/smoke.mjs` 跑了 2 次**，库里 post 从 42 → **62**、`smoke_%` 账号 **20** 个（全库 user **28**）。这不是失控，冒烟本来就要真发帖；清理 SQL 见上一轮记录（按外键顺序删 `smoke\_%` / `fecheck\_%`）。
- **数字口径教训**：文档里「断言 N 条」曾经手数过 —— 本轮两次实测都是 **85 项 / 断言 78 / 失败 0**，而上一轮文档写的是 79。差的正是第 9 步那条 `info()`（`SMOKE_PENDING_TOPIC_ID` 未提供，分支没有 HTTP 证据，脚本按 INFO 报而不是按断言报）。**结论：断言数一律抄脚本自己打印的汇总行，不许手数。**

### 本轮踩坑（7 条，前 4 条是后端/环境侧，后 3 条是本轮新学）

1. **OpenAPI `@Tag` 的 description 必须逐字一致**：`UserPostController` 与 `UserController` 同属「3 用户」，但 tags 是按 `name` + `description` 去重的，只换个说法就会多出一条重名分组（实测 7 → 8），分组数口径当场失真。
2. **`Set.of` 的迭代顺序按设计每次随机**：把白名单直接 `String.join` 进错误文案，同一次报错会出现两种说法，前端断言没法写。`sorted()` 之后再拼。
3. **`Start-Process -ArgumentList` 不处理引号**：路径含空格时（`E:\codex workspace\...`）必须自己把参数包一层 `[char]34`，否则 node 只收到半个路径，报「Cannot find module E:\codex」。
4. **写文件前不 `mkdirSync(dirname, {recursive:true})` 就是 ENOENT**：探针产物目录 `fe-probe\` 不存在时 `writeFileSync` 直接抛，报错信息还看不出是目录问题。
5. **`put()` 辅助函数不是 no-op**：本轮误写了一次 `put("api/user.js.patch", "")`，它**真的创建了一个空文件**，还差点被 git 收进去。用 helper 批量落盘时，路径写错不会失败、只会多造文件 —— 落盘完必须回读文件清单。
6. **深链断言不能在 `mount()` 之后立刻读路由**（上面「假 FAIL」那条的根因，单独占一条是因为它最容易在扩探针到 U3/U4/U5 时被再犯一次）。
7. **Vite 打 iife 单包会报两条警告，都不是错误**：`import.meta may not be a valid syntax` 与 `inlineDynamicImports ignored because codeSplitting:false`。看到红字就判定「探针构建失败」会把工装误拆掉 —— 判据是产物存在 + jsdom 里 `__probeMounted === true`。

### 文档回写

- 手册升 **v1.1.8**：§6.1 追加 v1.1.8 实测回写 7 条 + 旧「未经浏览器实测」条补一句；§6.2 U3 行更新 `PostCard` 行数并新增 U11/U12 行；§6.4 第 1 条补「读侧已闭环」补记；§15 **T3.13 维持 ◐**（第二批落地，但真浏览器未测 + U1/U6 未开工），阶段 3 口径重算说明写清「为什么 DOM 取证了还不升 ☑」；§18 Gate3、§19 变更表与下一步同步。**任务总数、人日、追溯矩阵、Gate 行数未变：117 条 / 144.30 人日 / 101 行 / 10 行。** 文档由 1717 行 → 1728 行（CRLF、无 BOM、无 Tab）。
- README：单测 153 → **162**、冒烟 68/63 → **85/78**、OpenAPI **24 paths / 27 operations / 7 分组**、新增「帖子读接口（我的 / 主页）」、后端类 78 → 79（Controller 8 → 9）、`frontend/src` 文件 34 → 37、views 10 → 12、勾掉「2026-09-21 阶段 3（续 4）」。
- 全局《复利与踩坑日志》补 009 第 5 轮，重点三条：先证伪「脱离 Spring 测不了 SQL」再写断言；先跑探针拿真 SQL 串再写期望值；jsdom 探针是把「build 过了 ≠ 页面能用」变成机器断言的最低成本路径（不需要装 Playwright）。

### 仍未做（截至本轮，别自我感觉良好）

- **真浏览器仍未实测**：jsdom 不含样式与布局，`domprobe` 的 PASS 不等于「U11/U12 视觉与交互已验证」；且探针目前只覆盖 U11/U12 两页，**U3/U4/U5 还没进探针**。§6.4 第 4 条与 Gate 3 的界面项继续挂 ☐。
- U1 首页、U6 话题圈未开工；点赞/收藏/关注（T3.6）、评论（T3.7）、举报与通知（T3.11）、埋点（T3.10）后端接口还没有，前端也就没有对应界面。
- **`GET /api/users/{id}` 用户摘要未做** → U11 只有一张列表，没有头像、昵称、发帖数头卡（宁缺毋滥，页尾已写白）。
- 「我的帖子」的收藏 Tab 是空的：收藏本身（T3.6）没做，没有数据可列。
- `HUMAN_REVIEW` 仍不进 `audit_task` 队列（T6.1，作者侧本轮闭环、管理员依旧看不见）；危机工单仍无通知（T6.4）；`auto_destroy_at` 仍只写不扫（T3.15）；`post.emotion_*` 仍为 NULL（阶段 4）；「热门」排序 `sort` 未实现。
- 毕设材料（T1B.*）按用户指令继续顺延；**仍未打 tag**（Gate3 未过，最新 tag `stage-2-skeleton`）。
## 2026-09-21 阶段 3（续 5）—— T3.6 点赞/收藏/关注三件套 + 第一次「全表不变式」SQL 取证 + 前端接真互动接口

### 本轮挑这三件事的理由

- v1.1.8 写白的空白里有一条「**互动没有任何反馈**」：卡片上没有赞和收藏，主页没有关注按钮，U12 的「收藏」Tab 是空的。它同时卡住 §6.4 第 2 条（Gate 3 判定项）、T3.10 埋点和 T3.11 通知 —— 是阶段 3 剩余任务里唯一一处「一做就同时解开三个下游死结」的点，所以本轮不做评论、不做话题，先做互动三件套。
- 另一个刻意选择：**这次要把取证做到「全表」而不是「这一条」**。前四轮的证据全是单点断言（某条帖 view_cnt=1、某个列表只回 ids=41），单点断言只能证「这次没出错」，证不了「没有任何一条破口」。计数类逻辑恰好是最容易在角落里烂掉的（并发、软删、下溢、复活），所以本轮加了一类新取证：**全表谓词归零**。

### 交付物：后端（行数 = `Get-Content` 实数 + 1）

| 文件 | 行数 | 作用 / 关键决定 |
|---|---|---|
| `entity/PostLike.java`（新） | 58 | `post_like` 的实体：`target_type` enum(post,comment) + `target_id` + `action_type` enum(LIKE,COLLECT) + `day_bucket` + `deleted`。`day_bucket` **是互动幂等键的一部分**（`uk_action` = `user_id` + `target_type` + `target_id` + `action_type` + `day_bucket`），字面语义是「这一赞首次成立的日期」而不是「最后一次点的日期」，值由 `now.toLocalDate()` 传入（服务内不读系统时钟）；同一列也是 T3.10 埋点「同一用户同一对象 1 小时内曝光只记一次」那类时间窗判据的落点。 |
| `entity/UserFollow.java`（新） | 33 | `user_follow(user_id = 关注者, follow_user_id = 被关注者)`，**没有 `deleted` 列** —— 取关是硬删，所以这个表的语义与 `post_like` 相反，两套代码不能互相抄。 |
| `mapper/PostLikeMapper.java`（新） | 71 | 除 MyBatis-Plus 自带方法外，手写 `countDistinctUsers(targetType, targetId, actionType)`（重算用）与 `revive(id)`（把 `deleted` 从 1 改回 0 并把 `updated_at` 前移）。**`@TableLogic` 只影响自带方法，自定义 SQL 一律自己带 `deleted` 条件** —— 少写一个就等于把软删行算进计数。 |
| `mapper/UserFollowMapper.java`（新） | 56 | 五个方法：`insertFollow` = **`INSERT IGNORE`**（撞 **`uk_follow_pair`** 返回 0，重复关注不报错）、`deletePair` = **物理 DELETE**（本表没有 `deleted` 列，取关不软删）、`countFollowing`（走 `uk_follow_pair` 前缀）、`countFollowers`（走 `idx_follow_user` 前缀）、`upsertFollowCounts` = **`INSERT ... ON DUPLICATE KEY UPDATE` 而不是 UPDATE** —— `user_profile` 不保证每人有一行，走 UPDATE 时「0 行受影响」分不清是没行还是值没变，冗余列会静默停在旧值上。 |
| `post/PostInteractionService.java`（新） | 176 | 点赞/取消/收藏/取消收藏的唯一入口。**零 Spring 依赖**（端口接口 + 构造注入），业务顺序 = 帖子存在性（不可见 → **404/30001**，与详情同口径，不给枚举通道）→ 动作白名单（白名单外 400/10001）→ **查活动态并判等**（「要成的状态」== 「现在的状态」就一个字节都不写）→ 正向时**先 `reviveCancelled` 复活软删行、复活不到才 `INSERT IGNORE` 兜并发**，负向时 `cancelActive` 一次置**全部**活动行（不带 `LIMIT`，跨日两行也能一次清掉）→ **重算并回写计数** → **写完再查一次库**才组装回执（`changed` 不用内存推测）。 |
| `post/PostInteractionStoreAdapter.java`（新） | 95 | 端口到 Mapper 的适配器，把「重算」这件事收在一处，业务类看不到 SQL。 |
| `user/RelationshipService.java`（新） | 176 | 关注/取关 + 主页摘要。两处不对称是刻意的：**重复点赞短路**（状态没变就直接回原计数，不再发第二条 UPDATE），**取关不短路**（`deleteById` 恒执行，删 0 行也是成功）—— 「取消一个动作」必须是幂等成功而不是报错。 |
| `user/RelationshipStoreAdapter.java`（新） | 94 | 同上。 |
| `post/dto/PostActionRequest.java` / `PostActionView.java`（新） | 15 / 31 | 回执带 `liked/likeCnt/collected/collectCnt/selfAction`；**`selfAction` 是给 T7.1 质量分用的**，不让前端自己判「这是不是我自己的帖」。 |
| `user/dto/FollowRequest.java` / `FollowView.java` / `UserHomepage.java`（新） | 18 / 29 / 42 | `UserHomepage` 是**白名单摘要**（displayName / avatarUrl / bio / 四个计数 / self），**年级·院系·性别·`risk_flag` 一律不出参**，注销用户统一 **404/20001**。 |
| `web/RelationshipController.java`（新） | 81 | `POST /api/posts/{id:\d+}/actions`、`POST /api/users/{id:\d+}/follow`、`GET /api/users/{id:\d+}/profile`。 |
| `web/PostController.java` / `UserController.java`（改） | ±6 / ±4 | 出参补 `liked/collected`（列表与详情都要带，否则前端每次进广场得再打三次「我点过没」的请求）；`@Tag` description 继续逐字对齐（v1.1.8 那条口径）。 |
| `post/dto/PostListItem.java` / `PostDetailView.java`（改） | +8 / +8 | 互动四字段 + `selfAction`。 |
| `mapper/PostMapper.java`（改） | +12 | `updateInteractionCnt(postId, likeCnt, collectCnt)` —— **整体赋值而不是相减**，因为值已经是重算出来的真相，减法只会把一次算错永久固化。 |
| `test/post/PostInteractionServiceTest.java`（新） | 467 / **11 例** | 逐条按 `@DisplayName` 抄：BR2 连点两次只计 1 人且第二次零写入 / 取消未赞过的帖子幂等零写入 / **取消再点赞复用同一行，`day_bucket` 仍是首次成立那天** / **并发跨日留两行活动行：计数仍 1 人，取消一次把两行全置掉（自愈）** / 点赞与收藏互不干扰 / 动作归一化（大小写空格不敏感、白名单外 10001 且文案按字典序）/ `selfAction` 被标记**但仍计入 `like_cnt`** / 不可见·不存在·已到期树洞一律 404/30001 且一行不写 / **BR6 禁言可点赞收藏、封禁·注销·脏状态 20003、发起人查无此人 20001** / 待审帖只有作者能点赞 / 一条链路来回点八次任何时刻冗余列都等于真相表。
| `test/user/RelationshipServiceTest.java`（新） | 323 / **11 例** | 逐条按 `@DisplayName` 抄：关注成功两侧冗余列 = 真值 / 重复关注 `changed=false` 第二次零写入 / **取关是物理删（本表无 `deleted`）**、取关未关注者幂等 / 删掉再关注同一对只有一行且**与日期无关（`uk_follow_pair` 不含日期桶）** / 三人关注一人后每步冗余 = COUNT / **读侧回执永远来自真表**（冗余列落后也不影响返回值）/ 不能关注自己 → **10001 而非 403**、一行不写 / BR6 禁言可关注、封禁 20003 / 动作归一化 + 字典序 / 资料卡接线（展示名与帖子同一函数、`bio` 缺行回空串、`self`/`following` 判定）/ 主页主人不存在（含被 `@TableLogic` 过滤的注销账号）→ 20001/404。
| `test/post/PostQueryServiceTest.java`（改） | **+59 行 / 例数不变（20）** | 出参带 `liked/collected`；**未登录时这两个字段恒 false 而不是 null**（前端要能直接绑定，不需要 `??`）。 |
| `docs/smoke.mjs`（改） | 653 → **828** | 新增**第 15 步**互动三件套，另把第 12 步的存在性断言改成「翻到底再判」（见「假证据」一节）。 |

`mvn -o -B test`（离线 + E 盘本地仓库）实测 **`Tests run: 185, Failures: 0, Errors: 0, Skipped: 1` / BUILD SUCCESS**（上一轮 162；**逐类抄 surefire 汇总行**：新增 `PostInteractionServiceTest` 11 例 + 新增 `RelationshipServiceTest` 11 例 + `PostingQuotaServiceTest` 12 → 13 例，`PostQueryServiceTest` 例数不变仍 20 例；**162 + 11 + 11 + 1 = 185**，测试类 16 → 18），日志 `backend/target/verify-t36.log`。

### 取证一：root 直连「全表不变式」（本轮新增的取证层，可无限重跑）

SQL 存 `E:\codex workspace\_cache\mindisle-dbtmp\ev_t36.sql`，跑在**干净冒烟之后**（探针与代理脚本只读，所以这一层结论不受后续复跑影响）。2026-09-21 12:2x 实测逐字：

```
A_全表不变式_like_cnt_不一致条数      |   0
B_全表不变式_collect_cnt_不一致条数    |   0
C_follow_冗余列不一致人数             |   0
D_表行数  user 43 / post 111 / post_like 16 / user_follow 0
```

- **A**：`SELECT COUNT(*) FROM post p WHERE p.deleted=0 AND p.like_cnt <> (SELECT COUNT(DISTINCT user_id) FROM post_like l WHERE l.target_type='post' AND l.target_id=p.id AND l.action_type='LIKE' AND l.deleted=0)` → **0 条**。B 同形换 COLLECT → **0 条**。C：`user_profile.follower_cnt/following_cnt` 与 `user_follow` 两侧计数逐人比对 → **0 人**。这三条是**谓词级**的：任何一条帖、任何一个人的冗余列错了都会让它 ≠ 0，且不需要我知道是哪一条。
- **E（`post_like` 逐行，16 行全抄）**：id 1/5/9/13 = user 8 对 post 71/81/100/110 的 LIKE，`deleted=0` 且 **`updated_at > created_at` = 1** —— 「取消点赞后再点」**复用了同一行**（复活），不是每次新插一行；id 2/6/10/14 = COLLECT 且 `deleted=1`（取收藏是软删）；id 3/4/7/8/11/12/15/16 是其他人的一次性点赞（`updated_at = created_at`）。`day_bucket` 全部 = `2026-09-21`。
- **F**：post 100 `like_cnt=2 / collect_cnt=0`（`PUBLISHED`、`public`、非匿名）；post 101 `like_cnt=1` 且 **`is_anonymous=1`** —— **点赞不是解匿通道**：匿名帖可以被点赞，但接口从不下发它的 `authorId`，主页列表也不含它（v1.1.8 那条三重收窄在这里第二次生效）。
- 为什么不用 `GREATEST(cnt-1, 0)` 这类「安全减法」：**减法的正确性依赖「缓存值曾经等于真值」这个假设**，而它一旦被破坏（并发、进程重启、手工改库）就永久错下去且不可察觉。重算没有这个假设，代价只是每次互动多一条聚合 SQL（走 `idx_target`，阶段 8 压测再判要不要加缓存层）。

### 取证二：冒烟脚本第一次自己戳穿自己的假证据（本轮最贵的方法教训）

- 现象：新增第 15 步后跑冒烟，出现 4 处 FAIL，其中三处是**脚本自己错**（见下），第四处最严重 —— **它意味着上一轮记为 PASS 的否定断言这一轮已经不再是证据**：
  1. **错误信封字段是 `msg` 不是 `message`**：`Result{code,msg,data,traceId}`，脚本按 `message` 取文案 → 三条「错误码对不对」的用例集体红。实现无错。同步修 `isMsg()` 与三处诊断输出。
  2. **匿名详情不下发 `authorId`**：Jackson `NON_NULL` 下该键整个消失，`=== null` 判的是 `undefined` → 改成 `== null` 同时接受「值为 null」和「键不存在」。**凡是「某字段不该出现」的断言，都要先想清楚「缺席」在序列化层长什么样。**
  3. **首页截断让否定断言恒真**：库里 `PUBLISHED + visibility=public` 的行数在连续冒烟后达到 **51 > `PageQuery.MAX_SIZE`=50**，而第 12 步「第三人广场里看不到 A 的私密帖/待审帖」是**只看首页**判的 —— 从这一刻起这条断言**必然通过，与实现无关**。第二个叠加因素：待审帖 `published_at IS NULL`，在 `published_at DESC` 里沉到整条流尾部，首页永远判不到（读侧的 `applyCursor` 早就为它写了 `isNull` 分支，说明**实现是对的，错的是断言的观察窗口**）。
- 修法：新增 `walkFeed(token, maxPages)`（沿 `nextCursor` + `beforeId` 翻到底、按 id 去重、记录 `pages` 与 `firstPage`），把三处「不出现」类断言改到**整条流**上判，并额外钉一条 `firstPage.length === 50 && others.length > firstPage.length` —— **先自证「首页确实被截断了」，那句「不出现」才算数**。作者侧「能看到自己待审帖并带 `auditTip`」同样改成 `walkFeed`。
- 方法论落一条硬规矩：**判存在性一律翻到底；每个否定断言必须同时钉住观察窗口够大**。「测试变红」不等于「实现有错」，但「测试变绿」同样不等于「实现被证明了」—— 本轮这四条里有三条是绿的假证据。
- 两个把 helper 放错位置的自伤（各浪费一次复跑）：① 第一版把 `walkFeed` 放到模块顶层 → `listGet is not defined`（`listGet/itemsOf/bodyOf` 是 `main()` 内的 `const`）；② 第二次 splice 误删了 `r = await listGet(...)` 那行 → 冒烟打印「首页=0」的假失败。**改脚本也要有 diff 级回读，别只靠「看起来对了」。**
- 复跑结果（照抄脚本自己打印的汇总行，不许手数）：**`冒烟汇总：113 项，断言 105 条，失败 0 条`**（上一轮基线 85 项 / 78 断言 / 14 步）。日志 `E:\codex workspace\_cache\mindisle-dbtmp\smoke_t36.txt`。

### 交付物：前端（接的是真接口，乐观值只用于手感）

| 文件 | 行数 | 变更 |
|---|---|---|
| `src/composables/usePostInteract.js`（新） | 81 | `usePostInteract() → {busy, isBusy, toggle}`：先本地 ±1，**回执到达后用服务端算好的 `liked/likeCnt/collected/collectCnt` 整体覆盖**，失败回滚；未登录**不发请求**，`router.replace({name:'login', query:{redirect: fullPath}})`。直接改传入的列表条目/详情原始对象（`usePagedPosts` 用 `ref([])`，深层响应）。 |
| `src/api/post.js`（改） | 44 → 54 | +`actOnPost(id, action)`（不 silent，让错误可见）+`POST_ACTION_PAIRS`（`like/unlike`、`collect/uncollect` 与对应标志位、计数列成对声明，避免四个 if 分支各写一半）。 |
| `src/api/user.js`（改） | 17 → 25 | +`userHomepage(id)`（**silent**，主页摘要失败不该弹红条）、+`followUser(id, action)`（不 silent）。 |
| `src/components/PostCard.vue`（改） | 109 → **130** | footer = 浏览/评论 + `.grow` + 两颗 `.act`（**`@click.stop` 必须加**，否则点个赞连带触发整卡跳详情；`:disabled="isBusy(...)"` 防连点）；文案 `赞/已赞/收藏/已收藏 + fmtCount`。三条注释写进模板旁：必须 stop / 计数以回执为准 / 评论恒 0 是因为 T3.7 未做。 |
| `src/views/post/PostDetailView.vue`（改） | 142 → **165** | **删掉 v1.1.7 那句「点赞要等 3.6 再做」的过期脚注**（代码注释里的「将来」也是承诺，落地当轮必须回改），换成 `.acts` 互动条 + 一句「计数由后端按真实互动记录重算」。 |
| `src/views/user/UserHomeView.vue`（改） | 110 → **202** | 新增资料卡（`el-avatar` + displayName + bio + 公开帖子/关注/粉丝/获赞）+ 关注按钮（`v-if="!card.self"`，乐观 +1 后以回执覆盖、失败回滚）+ `cardNote` 降级行；页尾写白段整段改写成「**这一页刻意有什么、刻意没有什么**」——讲清两个新接口 ≠ 改 `me/profile` 的入参、四类敏感字段不出参、以及仍欠私信与关注/粉丝列表页的理由。`watch(targetId)` 与 `onMounted` 同步 `loadCard()`。 |
| `frontend/probe/domprobe.mjs`（改） | 274 → **324** | +`actButtons()` helper；第 4 组旧断言（「这一页为什么只有一张列表」）替换为 3 条新断言（资料卡渲染 / 自己不出现关注按钮 / 写白段新标题）；**新增第 7、8 组**。 |

- `npm run build` **exit 0 / ✓ built in 1.05s**；`node frontend/probe/domprobe.mjs` **30 项 / 0 失败**（上一轮 23），`bundle: 3025109B`；`proxycheck.mjs` **10 项 / 0 失败**；OpenAPI **27 paths / 30 operations / 7 分组**（上一轮 24/27/7）。
- **探针第 7 组踩的坑值得单记**：进他人主页后第一版**等的是页面标题**，但标题是 route 参数的 computed（同步就有），资料卡要等接口 —— 于是 `[7] btns=[]` 假失败。正确写法是**等「真正异步的那个东西」（按钮本身）**。同理第 8 组断言 `acts.length === cards().length * 2` 且逐个匹配 `/^赞 \d+$/`、`/^收藏 \d+$/`，再钉「浏览/评论没有被挤掉」。
- **边界写白**：探针**全程只 GET**，没点过任何写接口 —— 「按钮在不在、文案对不对」是探针证的，「点下去真的写对」是冒烟证的。两件事别混着说。另外 `domprobe` 与 `proxycheck` **不能并发跑**（第一次误并发把 23 项绿跑成满屏 TIMEOUT，两个 Node 脚本同打一个后端 + 同一份编译产物），必须串行。

### 本轮真实踩到的坑（都有证据）

1. **`-p` 与密码之间有空格 = mysql 挂起等交互输入**。`-p<root口令>`（紧贴）能连，但先打一条 `mysql: [Warning] Using a password on the command line interface can be insecure.`，很多人把这条 warning 当成失败；`-p <root口令>`（带空格）会被 MySQL 解释成「`-p` 取密码为提示符、`<root口令>` 是**数据库名**」，于是**永久停在 `Enter password:` 等输入** —— 在 PowerShell 里表现为命令不返回。四种写法实测：`--defaults-extra-file=<cnf>`（**必须是第一个参数**）exit 0；`-h -P -u -p<root口令>` exit 0 + warning；`-p <空格> 密码` **挂起**；`-p` 接 stdin 管道同样**挂起**。控制台中文乱码另需 `[Console]::OutputEncoding=UTF8` + `--default-character-set=utf8mb4`；`\G` 在 `-e` 里不可用。
2. **凭据只留一个仓库外的文件**。`E:\codex workspace\_cache\mindisle-dbtmp\rootpwd.cnf`（39 B，`[client]` + `user` + `password`），文档、日志、commit message、会话回复一律不出现明文；脚本里用变量并在输出上做 `.Replace($pw,'********')` 掩码。本轮新增的 `ev_t36.sql` 里只有 SQL，不含任何凭据。
3. **`mvn package` 在离线仓库里缺 `maven-jar-plugin:3.5.1`** —— 只能 `spring-boot:run` 起服务；要跑 jar 得先补插件依赖，本轮没有为「顺手」去改离线仓库。
4. **`Start-Process -ArgumentList` 不处理引号**（v1.1.8 已记过一次，本轮再咬一次）：`-Dmaven.repo.local=E:/codex workspace/_cache/m2/repository` 不包引号时报 `Unknown lifecycle phase "workspace/_cache/m2/repository"`。正解：`[char]34` 手工包一层。
5. **给 record 加字段 = 改测试**。`UserHomepage`/`Relations` 这类出参 record 扩字段后，3 处 `new Relations(...)` 构造点当场 testCompile 失败。这不是坏事（编译器替你找到所有调用方），但要预期到：改出参的半径比想象大。
6. **内存 fake 必须照 DDL 的 `NOT NULL DEFAULT 0` 初始化计数列**。fake 的默认值是 `null`、数据库的默认值是 `0`，「零写入」路径在 fake 上拿到 `null` 会当场假失败 —— 断言没错、实现没错，是替身不像本体。
7. **`[IO.File]::ReadAllBytes('相对路径')` 按 .NET 进程 CWD 解析**，与 `cd` 不同步；一律写绝对路径。`Set-Content -Encoding utf8LF` 这个参数不存在（写文件改用 Node）。node_repl 里 `process` 不可用、最终表达式常被吞，输出走 `nodeRepl.write(...)` 且写完必回读字节数核验。
8. **明文口令一旦进了 `docs/dev-log.md` 就等于进了 git**：本轮写「`-p` 空格挂起」这条坑时，把 root 口令的字面值抄进了日志 4 次 —— 提交前用 `split(pw).join('<root口令>')` 全部掩码，并全仓扫描（`Get-ChildItem -Recurse -File | Where { $_.FullName -notmatch '\\(node_modules|target|dist|\.git)\\' }` + `[IO.File]::ReadAllText().Contains($pw)`）确认仓库内归零，只剩仓库外的 `rootpwd.cnf` 与三个 `_cache` 辅助脚本。**教训：解释「某种写法能连上」时，示例里放占位符，不放真值。**
9. **多行 PowerShell here-string 会把输出吞掉**，改文档的脚本一律用 Node + JSON ops（本轮 `mdpatch.mjs` 走行替换、新写 `strpatch.mjs` 走子串替换，每条 op 带 `done` 标记，锚点缺失即抛错且不写盘，可安全复跑）。

### 环境事实（下一轮照着跑，不用重新摸）

- MySQL 9.7.1 / root 本地连接走 `rootpwd.cnf`；后端 8080 = 本轮新代码（run18），前端 dev 5173 与探针共用；`.env` 只在项目根（`MINDISLE_CAPTCHA_ENABLED=false`）。
- 冒烟账号口令 `Smoke#2026x`（在 `smoke.mjs` 内，非机密）；探针夹具账号 `smoke_seen_20260921020038` = **user 23**，其 post 39–42 是 U11/U12 与 `domprobe` 的稳定证据源 —— **本轮之后扩到 30 项，夹具未变**。
- 本轮为取证把冒烟又跑了 1 次，库里 post 62 → **111**、user 28 → **43**、`post_like` **16** 行；`user_follow` = **0**（探针与冒烟都**没留下活跃关注行**：关注往返最终是取关，取关是硬删，所以计数为 0 与 C 条谓词归零是一致的）。

### 文档回写

- 手册升 **v1.1.9**（1728 → **1744 行**，CRLF、无 BOM、无 Tab）：§6.1 的 **3.6 行整行按实测重写**（幂等形状 / 计数重算 / 自赞口径）+ 追加 v1.1.9 实测回写 **13 条**（第 13 条是本轮**文档勘误**自身）；§6.2 U3/U4/U11 三行更新（`PostCard` 130、`PostDetailView` 165、`UserHomeView` 202）；§6.4 **第 2 条 ☐→☑** 并附冒烟第 15 步 + SQL E 证据（含「为什么证据取自互动表而不是 `user_action`」的偏差说明）；§15 **T3.6 ☐→☑**、阶段 3 收工口径 **☑ 5→6 / ☐ 10→9**；§18 Gate3 行同步为 185 / 113 / 30；§19 新增 v1.1.9 变更行 + 「下一步」整段替换。**任务总数、人日、追溯矩阵、Gate 行数未变：117 条 / 144.30 人日 / 101 行 / 10 行。**
- README：进度块新增「阶段 3（续 5）」一整节；顶部摘要、目录结构（main 93 / test 18 / Controller 10、`src` 37 文件）、四·补标题、OpenAPI 口径、冒烟实测行、库内行数快照、进度勾选与「下一步」全部按实测更新。
- 全局《复利与踩坑日志》补 **009 第 5 轮与第 6 轮**两节。**这里要显式记一次自纠**：上一轮 dev-log「文档回写」里已经写了「补 009 第 5 轮」，但当时磁盘上并没有这一节 —— 属**提前断言**（同一个毛病本项目第 3 轮就犯过一次）。本轮把第 5 轮真正补上，并顺带补第 6 轮（本轮），措辞改成「本轮与上一轮已各自落节」，避免再出现「文档说写了、文件里没有」。**口径仍然只有一条：每条结论都要能指到一次真实执行。**

### 仍未做（截至本轮，别自我感觉良好）

- **评论（T3.7）**、**举报 + 站内通知（T3.11）**、**`user_action` 埋点（T3.10）**：本轮之后，「点了没反馈」这件事只剩通知没做 —— 点赞和关注现在有了计数与状态，但没人会收到消息。
- **收藏列表页与 U12「收藏」Tab**：数据已经有了（`post_like` 里 `action_type=COLLECT, deleted=0` 的行），缺一个读端点和一张列表；「我关注了谁 / 谁关注我」同理，`user_follow` 有写没读。
- **关注流 `scene=follow`** 未实现；话题（T3.8）、搜索（T3.9）、「热门」排序（`sort`）未动；U1 首页、U6 话题圈未开工。
- 探针**没有点过任何写接口**；**真浏览器仍未测**（`cua` 依旧返回 Codex auth token unavailable），§6.4 第 4 条与 Gate 3 的界面项继续挂 ☐。
- `HUMAN_REVIEW` 不进 `audit_task`（T6.1）、危机工单无通知（T6.4）、`auto_destroy_at` 只写不扫（T3.15）、`post.emotion_*` 恒 NULL（阶段 4）不变。
- 毕设材料（T1B.*）按用户指令继续顺延；**仍未打 tag**（Gate3 未过，最新 tag `stage-2-skeleton`）。

## 2026-09-21 阶段 3（续 6）—— T3.7 评论与楼中楼 + U4 评论区：「先审后发」第一次同时贯穿帖子与评论两条写路径

### 本轮挑这件事的理由

- T3.6 之后，社区主链只剩「评论」这一能让详情页 U4 从「能看能赞」变成「能说话」；而且评论是**第一条同时把 3.2 机审、3.4 马甲、FR7.3 可见性、FR10.5 危机工单串起来的写路径** —— 做完它，等于给阶段 4 的危机分级多验证一条真实入口。
- 更现实的理由：§6.4 Gate 3 的判定项里，评论是唯一一块**表已建、判据已定、只差代码**的（`comment` 表与 `idx_post_status`、`idx_parent` 在阶段 2 的 DDL 里就位），性价比最高。

### 交付物：后端（行数 = 去掉行尾换行后的实数，非估算）

- `backend/src/main/java/com/mindisle/entity/Comment.java`(82)：八态 `status` 常量 + `parent_id`/`root_id`/`reply_to_user_id`/`alias_id`/`like_cnt`，字段与 `sql/04_community.sql` 的 `comment` 表逐列对齐。
- `mapper/CommentMapper.java`(31)：`countPublished(postId)`（给冗余列重算用）+ 批量按 root 取子树。
- **`post/CommentService.java`(586，本轮主产出)**：`comment()` 写侧七步 = 取帖并判可见（不可见 **404/30001**，与详情同口径，评论接口不是存在性枚举通道）→ 账号状态与 BR4 当日评论配额 → 内容合规（换行归一 + trim + 空内容 400/10001 + **码点**长度 ≤1000）→ `findParent` 定位父级 → 3.2 机审 → 匿名遮联系方式与马甲分配 → 落库 + `refreshCommentCnt` + 危机工单；`list()` 读侧 = 一级评论正序翻页（每棵子树预览 `REPLY_PREVIEW=3` 条、带回 `replyTotal`）或 `rootId` 一次展开整棵（`MAX_SUBTREE_REPLIES=500` 封顶）。静态方法 `isVisibleTo`/`toItem`/`auditTipOf` 全部**包级可见**，让 43 例单测不打桩 Spring 就能直接钉判据。
- `post/CommentStoreAdapter.java`(156)：`CommentStore` 端口的 MyBatis 实现，业务类零 Spring 依赖可裸测（沿用 T3.5/T3.6 的端口—适配器分工）。
- 4 个 DTO：`CommentCreateRequest`(16)、`CommentCreateView`(15，含 `comment`/`tip`/`hotline`)、`CommentItem`(41)、`CommentThread`(17)。
- 端点收进 `web/PostController.java`(128 → **187**)：`POST /api/posts/{id:\d+}/comments`、`GET /api/posts/{id:\d+}/comments`，**恒返 200**（被机审拦下、转人工、遮罩都是「请求成功、内容被处置」），只有真入参错误走 4xx。**为什么不新建 `CommentController`**：可见性判据要「先问帖再问这条评论」，另起一类就是把判据复制第二份；且 Swagger `@Tag` 分组数被 `docs/openapi-check` 的断言写死，多一个分组会打挂既有校验。
- 复用而非抄写：`post/PostService.java`(625 → **638**) 把组工单抽成 `static newTicket(userId, sourceType, sourceId, ...)` 并暴露 `tipContactMasked()`；`mapper/PostMapper.java`(84 → **99**) 加 `refreshCommentCnt`；`config/MindisleProperties.java` 加 `mindisle.post.max-comment-chars = 1000`（与 DDL `VARCHAR(1000)` 同宽）。

### 三条设计口径（写下来是因为它们都「反直觉一次」）

1. **两级展示不等于两级存储**：库里 `parent_id` 存**真实父子**（回复谁就挂谁），`root_id` 存一级祖先；页面只画两层，第三层的「你在回复谁」用 `replyToName` 那句话表达。真库实证：评论 37 的 `parent_id=36` 而 `root_id=35`。DOM 探针 **L101** 钉的是「回复文案在」且「没有 `.indent .indent`」。
2. **待审评论不能当父级**：`findParent` 只认 `PUBLISHED`，**故意不复用** `isVisibleTo` —— 作者对自己那条 `PENDING` 是可见的，照可见性放行就能回复一条尚未公开的内容，机审一驳回就造出谁都看不见的孤儿子树。这条判据最初真被写成 `isVisibleTo(parent, parentId)`（参数个数一样、编译通过），现在由单测与冒烟 **L126**（400/10001「这条评论现在不能被回复」）两头钉住。
3. **`comment_cnt` 与点赞同法不同式**：同样是子查询覆盖写（漂移在结构上不可能发生），但① 数**行**不数人（评论无「一人一条」唯一键，同一人可合法发十楼；数人的点赞用 `COUNT(DISTINCT user_id)`）② 只数 `PUBLISHED`（把待审算进去就出现「卡片写 3 条、点进去 2 条」）。

### 一处已知妥协（写白不藏）

- `alert_ticket.source_type` 的 ENUM 只有 `chat/post/hole/pm`，**没有 `comment`**（DDL 定稿于阶段 2，当时没预料评论也建工单）。评论触发的危机工单因此记成**来源帖子**（`post`/`hole` + `post.id`），并把评论 id **追加**到 `evidence_text` 末尾。真库实证：工单 50 / 46 / 42 三条 `level=L2, risk_score=0.600, status=pending`，证据文本结尾分别是「（评论 id=42）/（id=34）/（id=8）」。
- 为什么宁可记错也不省这一枪：需求 §18.3 对自伤类内容要的是「人不能被推回沉默」—— 来源指针不精确是可解释的瑕疵，漏一条 L2/L3 预警是事故。**只能追加不能前缀**：`CrisisGrader.evidence()` 按命中偏移量在原文上切片，改原文会把证据切错位置。修法（ENUM 加值）挂阶段 6 与 T3.15 一并做。

### 交付物：前端 U4 评论区

- **新建 `frontend/src/components/CommentSection.vue`(374 行 / 16713 B)**：一级评论列表 +「查看更多」页码翻页 +「查看 N 条回复」走 `?rootId=` 展开（翻到底改出一行「共 N 条一级评论」而不是留一颗死按钮）+ 发表框（`0 / 1000` 码点计数、匿名勾选、楼主「回复 @某某」占位）+ 三态回显（`PUBLISHED` 立刻在列 / `PENDING` 带「审核中，仅自己可见」/ `REJECTED` **不画**）+ `el-skeleton` 骨架 + `reset()` 换帖即清空（丢草稿是故意的）。
- **发完不重拉列表、也不本地插草稿，而是用后端回执画**：`applyReceipt` 的判据与后端 `isVisibleTo` 逐字对齐。理由有两条，一是全站 60 次/分/身份的限流不该为一楼新评论再交一次列表请求，二是内容没过机审就把「未通过」当众渲染出来是错的。
- **游客连 GET 都不发**：这条接口的登录身份参与 SQL 谓词，天然不能对游客开放；发出去只换来 401 和一条刺眼的红色「登录已过期」—— 把「请你去登录」说成「你操作失败」是两类事（与 v1.1.9「未登录点互动不发请求」同一条规矩）。
- `frontend/src/api/post.js`(53 → **79**)：`listComments`（`silent: true`，读接口自己画空态）、`addComment`（**非 silent**，后端那句 `msg` 就是该给用户看的下一句话）、`COMMENT_MAX_CHARS`/`COMMENT_PAGE_SIZE`/`COMMENT_SUBTREE_CAP` 三个与后端同源常量、`commentLength()` 用 `Array.from` **按码点**数（后端 `codePointCount`，一个 emoji 算 1 字，前端若用 `.length` 会算成 2 个 → 界面说还剩 3 字、提交被拒）。
- `frontend/src/views/post/PostDetailView.vue`(164 → **175**)：`<comment-section v-if="post" :post-id="postId" @published="onCommentPublished" />`，页脚「评论 N」随新发的公开评论 +1，口径与后端一致（只数 `PUBLISHED`，含楼中楼）；删掉 v1.1.7 那句「评论树要等 3.7」的过期脚注和失效的 `.footnote` 样式。
- **刻意不画的东西**：评论点赞按钮（后端只有 `like_cnt` 列、没有端点）、删除按钮（T3.15 未做）、@通知（T3.11 未做）。画一颗不能用的按钮比不画更坏。

### 取证（每条都能指到一次真实执行的日志行号）

- 单测：`mvn -o -B test` **185 → 228 例 / 0 失败 / 1 跳过**，日志 `E:\codex workspace\_cache\mindisle-dbtmp\verify-t37-2.log` **L124**（`CommentServiceTest` 43 例）、**L164**（汇总）、**L167**（BUILD SUCCESS）。算式 185 + 43 = 228，测试类 18 → **19**。
- 真 HTTP 冒烟：`node docs/smoke.mjs` **113 → 137 项 / 断言 105 → 127 / 失败 0 / 约 47s**，日志 `smoke_t37c.txt` **L139** 汇总行、第 16 步 21 条在 **L114–L137**；脚本 `docs/smoke.mjs` 829 → **1016 行**。这一步覆盖：一级/二级/三级压平、`replyTotal`、匿名回执、灰词转人审、**FR7.3 待审只对作者可见**（第三人整条不出现）、待审不能当父级、危机评论 `hotline=12356`、空白/超 1000 字/父级不存在/不可见帖/未登录各有专属错误码、**BR4 第 21 条 429/30003**。
- 前端构建：`npm run build` **exit 0 / ✓ 1774 modules transformed / ✓ built in 871ms**（`build-t37.log` **L7 / L101**，`dist/assets/PostDetailView-DMZxv_ne.js` **L94** = 13.75 kB）。
- DOM 级：`node frontend/probe/domprobe.mjs` **30 → 42 项 / 0 失败**（`domprobe_t37.txt` **L105** 汇总；新增第 9、10 两组 **L93–L104** = 19 棵楼渲染、马甲名「匿名屿民·柏舟」、翻到底无死按钮、silent 无全局消息条、计数初值、预览 3 行、`查看 5 条回复` → 点完 6 行且按钮消失、无第三层缩进、楼主标 n=2、危机评论可见、换帖干净重挂）。
- 真库：`ev_t37.sql` 八条谓词，跑法见下面「环境事实」，输出 `ev_t37.out.txt`（**72 行 / 0 个 ERROR**）。A 全表 `comment_cnt` 不变式 **0 条不一致**；B 140/141 = **6/6 与 19/19**；C 逐行压平；D 匿名评论留真实 `user_id` 且有 `alias_id` **6/6**；E 孤儿子树与跨帖 root **0**；F `PENDING` 9 / `PUBLISHED` 53；G 三条工单；H 行数 user 52 / post 141 / comment 62 / alias 27 / ticket 50。
- 出参形状核对：`inspect-comments-t37.out.txt` —— post 140 一页里 root 1 个 + 预览 3 条 + `replyTotal=5`；`?rootId=35` 一次给 5 条；post 141 `total=19 / n=19 / hasMore=false`；匿名项**无 `authorId` 键**；评论 36/42 `authorIsPostOwner=true`，而匿名的 38 即使作者就是楼主也不透出该标（反解匿）。

### 环境事实（下一轮照着跑，不用重新摸）

- **含中文的 SQL 交给 mysql 的唯一安全姿势 = cmd 的 `<` 重定向**：`E:\codex workspace\_cache\mindisle-dbtmp\run-ev-t37.cmd` 里是 `"...mysql.exe" --defaults-extra-file="...rootpwd.cnf" --default-character-set=utf8mb4 -t -D mindisle < ev_t37.sql > ev_t37.out.txt 2>&1` → exit 0。反面姿势：`Get-Content -Raw` + `-e` 传参会把 UTF-8 无 BOM 的中文按 ANSI 读 → 乱码 + `ERROR 1064`（本轮第一次就栽在这上面）。
- **凭据边界**：root 口令只存**仓库外**的 `_cache\mindisle-dbtmp\rootpwd.cnf`（`--defaults-extra-file` 必须是**第一个**参数、路径含空格必须整体加引号）；仓库内的文档、日志、脚本、commit message 一律占位符，提交前扫一遍 `git diff --cached`。`-p <口令>`（带空格）会让 mysql 转去等 TTY 而**永久挂起**，`-p<口令>`（紧贴）能连但会打一条 insecure warning —— **判据用 exit code，不用有没有红字**。
- **`alert_ticket` 的真实列名是 `level` / `risk_score` / `sla_at`**，不是 `risk_level` / `sla_deadline_at`（后者属 `chat_risk_alert`）。列名一律回 DDL 逐行核对再写谓词。
- **往 shell 里内联 powershell 命令时 `$` 会被吃掉**（`$_`、`$LASTEXITCODE`、`$env:X` 变空 → ParserError）。一律落成 `.ps1`/`.cmd` 文件再执行；含中文的 `.ps1` 必须 UTF-8 **带 BOM**，而 `.md`/`.mjs`/`.java`/`.vue` 恰恰**不能**带 BOM。
- **`Tee-Object -FilePath` 默认写 UTF-16LE**（`build-t37.log` 就是这份），按 UTF-8 读它搜关键词必然 0 命中 —— 别把「搜不到」当成「没构建」。改 `Out-File -Encoding utf8` 或显式按 utf16le 解。
- 后端 8080 = `_cache\mindisle-dbtmp\start-backend-20.ps1` 起的 run20，`/actuator/health` = UP；Vite dev 5173 curl=200（探针与冒烟共用，别杀）。
- 本轮为造「预览 3 / 真值 5」的样本，用 `_cache\mindisle-dbtmp\seed-reply-t37.mjs` 让 user 23 对 post 140 的评论 35 追发 2 条回复 → **comment 61/62，status=PUBLISHED**；这不是脏数据（是真实账号发的合规评论），但**引用 H 表行数时要知道它含这 2 条**，重跑 `run-ev-t37.cmd` 即可复现同一组数字。

### 文档回写

- 手册升 **v1.2.0**：`制作步骤文档.md` 1744 → **1765 行**（本轮勘误后再 +1 → 终值 1766，见文末勘误块）（CRLF、无 BOM、无 Tab）——§6.1 行 3.7 整行按实测重写 + 新增 v1.2.0 实测回写 **15 行**；§6.2 U4 行标「v1.2.0 评论区已接」并改写「仍欠」；§14 速查表新增 **31–35** 五条；§15 的 T3.7 ☐→☑、T3.13 行两处过期口径改正（③ 举报仍未做、⑥ 用户摘要 v1.1.9 已补）、阶段 3 收工口径 **☑ 6 → 7 / ☐ 9 → 8** 并把数字同步到 228 / 137 / 42；§18 Gate3 同步；§19 新增 v1.2.0 行与「下一步」整段重写。**任务总数、人日、追溯矩阵、Gate 行数均未变：117 / 144.30 / 101 / 10。**
- README 与本 dev-log 同步；全局《复利与踩坑日志》补 009 **第 7 轮**（mysql 口令取证：凭据从头到尾是对的、失败的是写法与判据）与**第 8 轮**（本轮：中文 SQL 的 cmd 重定向姿势 + 四条新工具坑）。

### 仍未做（截至本轮，别自我感觉良好）

- **举报 + 站内通知（T3.11）**、**`user_action` 埋点（T3.10）**、**话题（T3.8）**、**搜索（T3.9）**、**编辑与销毁（T3.15）**、**通知中心（T3.16）**、**关注流与举报分类（T3.17）**：本轮之后「点了没反馈」依然只剩通知没做。
- **评论的点赞 / 删除 / @通知界面未开放**（库里 `comment.like_cnt` 是空列，界面刻意不画）；`rootId` 展开单次封顶 500 条，超楼的「万楼层」只能分页看一级。
- 探针这一轮**仍只 GET**，新增 12 项全是渲染与展开断言，**一颗写按钮都没点过**；写路径的证据在冒烟第 16 步与 root SQL 里。**jsdom 不是真浏览器**，真浏览器仍未测（`cua` 依旧 auth token unavailable，备选 Playwright）。
- `HUMAN_REVIEW` 不进 `audit_task`（T6.1）、危机工单无通知（T6.4）、`auto_destroy_at` 只写不扫（T3.15）、工单 `source_type` 没有 `comment`（本轮妥协）、`post.emotion_*` 恒 NULL（阶段 4）不变。
- 毕设材料（T1B.*）按用户指令继续顺延；**仍未打 tag**（Gate3 未过，最新 tag `stage-2-skeleton`）。

### 勘误（2026-09-21 本轮收尾自查，全部由 `git show HEAD:<path>` 与工作区文件同一条命令现量）

- **上一轮手稿里 5 个「before 行数」是滞后的或口径不一致的**，已全部订正：`web/PostController.java` **157 → 128**（HEAD 实数 128，187 为现值）、`post/PostService.java` **617 → 625**、`frontend/src/api/post.js` **61 → 53**（现值 79，不是 80）、`PostDetailView.vue` **165 → 164**（现值 175）、`docs/smoke.mjs` **828 → 829**。根因有两条：① `Get-Content` 与 node「去掉行尾换行再数」的口径差 1 行；② 引用了 T3.5/T3.6 期间的中间值而不是提交时的 HEAD 值。**今后统一**：前后两个数在同一次 node 调用里量出，`--before` 走 `git show HEAD:<path>`。
- **README 首稿把 `comment_cnt` 的机制写成了「`comment_cnt = comment_cnt + 1` 库内原子累加」，这是错的**。真实现 = `PostMapper.refreshCommentCnt`（**L53–L56**）的 `UPDATE post SET comment_cnt = (SELECT COUNT(*) FROM comment WHERE post_id = post.id AND status = 'PUBLISHED' AND deleted = 0) WHERE id = #{id}`，即**按真相表重算覆盖写**；调用链是 `CommentService`(L218) → 端口 `refreshPostCommentCnt` → `CommentStoreAdapter`(L74–L77)。源码注释原文就是「漂移在结构上不可能发生，而不是『+1 再定期回写』」。**教训：机制描述和数字一样要回源码读一遍，「记得大概」在文档里会产生和代码相反的事实。**
- 手册 `制作步骤文档.md` 的行数：HEAD 1744 → 本轮回写后 1765 → 再补 §14 第 36 条（行数口径与机制描述必须现量现读）后**终值 1766**；README 与手册目录里的 §14 计数同步为 **36 条**（目录行原本还写着「30 条」，属第二个滞后值，一并改）。
- `docs/smoke.mjs` 第 16 步打印的「跑完回查 SQL」里 `alert_ticket` 用了 `risk_level` —— 那是 `chat_risk_alert` 的列名，照抄必报 `Unknown column`。已改成 **`level`, `risk_score`, `sla_at`**。**注意：本次只改了脚本里的提示字符串、未复跑冒烟**（冒烟的既有 137 项 / 127 断言 / 0 失败 仍以 `smoke_t37c.txt` **L139** 为准，字符串不参与断言、不影响退出码，但下一次复跑前别把它当成新证据）。
