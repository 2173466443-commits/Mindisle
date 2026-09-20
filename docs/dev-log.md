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
