# Gate 5 证据目录（阶段 5 · 实时私信与 WebSocket/STOMP · M6a）

这一目录回答一个具体问题：**「私信与实时通道到底做好了没有」**。阶段 4 收口之后用户仍然觉得「实时那部分没做好」，所以本阶段沿用并加强了阶段 4 的收口方式：**不以「代码写完 + 单测过了」为准，只以能复跑的脚本 + 真实读数 + 证伪记录为准**；并且比阶段 4 多一条硬要求——**界面证据必须由真浏览器出**（本目录 16 张 PNG），因为「双窗口实时」这类判据在 jsdom 里永远证不出来。

## 0. 环境事实（全部 2026-09-29 凌晨现查，不信上一轮记录）

| 项 | 现值 |
|---|---|
| 后端 | 8080（**真实上游 DeepSeek**；`S0-2` 实例自述 `profiles=dev cacheMode=local llmProvider=spring-ai java=17.0.19`）；8081 为阶段 4 的离线实例，本轮未用 |
| 前端 | 5173（Vite dev server）；**5174 管理端本轮仍未起**（属阶段 6） |
| 数据库 | MySQL 3306；`private_message` 由 129 行涨到 **172 行**（`MAX(id)=184`） |
| Redis | 6379 无监听 ⇒ 缓存走 Caffeine 本地兜底（`cacheMode=local`），未读计数与限流仍工作 |
| 浏览器 | 本机 Chrome 150.0.7871.115（headless，executablePath 指向本机安装）+ playwright-core，视口 1600x1000 |
| 私信路由 | `S0-1` 该实例 `/api/pm/*` **恰好 9 条**（block/blocks/conversations/online/read/report/send/thread/unread），全实例 68 条 path |
| 夹具 uid | `S0-3` 甲=3（屿02）乙=5（屿04）丙=6（屿05） |
| 口令 | 数据库口令只走仓库外的 `--defaults-extra-file` .cnf；本目录所有文件 grep 过，0 命中（见 §4 纪律） |

## 1. 五条取证线（每条都注明哪一天跑的，跨天的数字不许混用）

| # | 线 | 命令 | 读数 | 证据 |
|---|---|---|---|---|
| 1 | **Gate5 全量（REST + STOMP + 真浏览器）** | `PACE=1050 GATE_SENDER=demo02 GATE_RECEIVER=demo04 GATE_THIRD=demo05 node probe/pmgate.mjs` | **PASS 103 / FAIL 0 / exit 0**，其中 C 组 **16 张截图全绿** | 本目录 16 张 PNG + `pmgate.log`（156 行）+ `shot-manifest.json` |
| 2 | Gate5 协议层（`NOUI=1`，纯 REST + 裸 WS） | 同上 + `NOUI=1`（`PACE=350`） | **PASS 96 / FAIL 0 / 图 0** | 仓库外 `gate5no48.out` |
| 3 | 前端 DOM 回归闸 | `node probe/domprobe.mjs` | **121 项 / 失败 0** | 仓库外 `domprobe49b.out`（改判据之前是 121/1，见 §3 第 2 条） |
| 4 | A16 禁言补跑（两段式） | `--phase=login` → 库里置 MUTED → `--phase=send` | **PASS 7 / FAIL 0**（+ login 阶段 2 条 = 合计 9 条） | 仓库外 `gate5a16.out`；跑法见 §2 末尾 |
| 5 | 后端单测（本轮新增登录闸门） | `mvn -o -B test -Dtest=AuthServiceSignInTest` | **Tests run 12 / Failures 0 / Errors 0 / Skipped 0，BUILD SUCCESS，12.996 s** | 仓库外 `mvn-a16fix.log` |

### 关键硬读数（逐字抄自线 1 的输出，写文档只引这一段）

- `A3` clientMsgId 重发返回同一条 id：第一次 id=152 重发 id=152；`A3b` 重发没有多落一行 ← 命中 1 条 ids=152。
- `A14-2` 发信方看到「你写的这句话让屿安很在意。已经悄悄帮你把这条留在待审队列里，也可以直接拨打 **12356**（24 小时有人接）」；`A14-3` 收信方看到「对方此刻可能很需要支持。你不需要一个人扛，也可以拨打 12356。」——**同一行两套话术，按视角拼**。
- `B7-1` 通知帧 `{"type":"dm","id":886,"unread":30,"ts":1790620611503}`；`B7-2` 恰好四键、**没有正文**。
- `B12-3` alert 帧恰好 5 键 `at,hotline,level,messageId,side`；`B12-4` 正文命中=false（最小展示原则）。
- `B15` `GET /ws/info` 经 Vite 代理 `http=200 body={"entropy":-826206775,"origins":["*:*"],"cookie_needed":true,"websocket":true}`。
- `B16` SockJS `xhr-polling` 经代理 `http=200 首帧="o\n"`（WebSocket 握不上时浏览器靠这条收私信，代理两条都得透）。
- `C9` 顶栏角标 `亮之前="" 现在="1" 地址=/user/5`；`C10` 行内角标="1"、全部角标=["1"]，与 dim 文案「现在有 1 条没读」同源；`C11` 读完 `gone="GONE"`。
- `C17`（拔网线）`离线=true 恢复=true 发送code=0/0 断网前 我方/对方=14/6 回来后=14/8 两条各出现={"n1":1,"n2":1,"total":20} 实时通道=已连`。
- `A12-5` 拉黑不抹历史：拉黑前 50 条 / 拉黑后 50 条 **同序=true**（FR6.7 的落地口径）。
- `A13-2` 同一目标重复举报只有一张任务（`uk_target_pending` 兜住）：第一次 taskId=87 第二次 taskId=87。
- A16：`A16-2 /api/users/me code=0 status=MUTED`；`A16-4 会话列表 code=0 listLen=1`；`A16-6 发私信 HTTP=403 code=10003 msg=账号处于禁言期，可以看和点赞，暂时不能发布内容`；`A16-8 发帖同码`；对照 `A16-0c 未禁言时私信可发 code=0 id=184`。

## 2. 逐条签手册 §8.1 / §8.2 / §8.3

| 条目 | 签什么 | 凭什么 |
|---|---|---|
| §8.1 `registerStompEndpoints("/ws").withSockJS()` + 另留 `/ws-native` | ✅ | `WebSocketConfig`(153)；线 1 的 B 组全程走裸 `/ws-native`（自写最小帧客户端），C 组浏览器走 SockJS `/ws` |
| §8.1 握手鉴权（`?token=` / `Authorization` → 失败拒握手） | ✅ | `WsAuthHandshakeInterceptor`(132) + `StompPrincipalHandshakeHandler`(70)；`B1-1` 坏令牌 / `B1-2` 不带令牌**都拿不到 CONNECTED**；`B2` 成功帧头 `user-name=3`、`accept-version=1.2` |
| §8.1 七条目的地（`/app/private`、`/app/read`、`/app/ping`、`/user/queue/{private,ack,notify,alert}`、`/topic/presence`） | ✅ | `PmMessageController`(157) 三条 `@MessageMapping` + `PmPushGateway`(104)；逐条读数 B5-1/B5-2（private+ack）、B7-1（notify）、B12-2（alert 两侧）、B3-1（presence）、B4（ping→pong）；**订阅标识在 `subscription` 头**（见 §7 第 1 条） |
| §8.1 NFR6 先落库再推送 + 幂等 + 失败重投（DB 状态位 + 定时作业，不引 MQ） | ✅ | `PmService`(885) 落库在前；`A3/A3b` 幂等；`B8-1/B8-2/B8-3` 收件人离线时**只回 sent、不谎报 delivered**、库里停在 `sent`；`B9-1/B9-2` 重新上线后 `PmDeliveryRetryJob`(91) 补推并发出 delivered |
| §8.1 未读计数 + 已读清零回写 status | ✅ | `A1b/A10b/A10c/A10d/A10e/A10f`（`byPeer` 只带未读>0；重复上报翻 0 行；**把别人的 id 当 upToId 也只是 0 行**，判据在 SQL 里）；`B10-2/B10-3/B10-4` 已读回执只发给发信方且不重播 |
| §8.1 风险私信 FR6.6（建 `alert_ticket(source_type=pm)` + 求助卡 + 私信是唯一允许进人审的私密内容） | ✅ | `A14-1..4`（L3/L2；词面通道输出域只有 L0/L2/L3）、`B12-1` 落库、`B12-6` 气泡文案由服务端拼好；建单量核对：`audit_task` 73→**94**、`alert_ticket` 166→**183**（见 §5） |
| §8.1 心跳与超时（服务端 30s/30s + `/queue/ping` 兜底） | ✅ | `B2` 协商头 `heart-beat":"30000,30000"`；`B4` 前端 25s 保活实测拿到 `{"kind":"pong"}` |
| §8.2 单例 stompClient + 指数退避 + 重连后按 `beforeId` 补拉（离线补偿） | ✅ | `composables/useWs.js`(333) + `stores/pm.js`(766)；**C17 是这一条唯一的实物证据**：离线 11 秒期间乙发的两条回来后**不丢（都在）也不重（各 1 颗气泡）** |
| §8.2 发送三态 + 时间格式化 | ✅ | `C2` 状态槽 13 颗「已读」+ `C5` 最新一颗「已送达」（由 `/queue/ack` 推回来，**不是本地自己写的**）；`C2` 日期分组=["今天"]、空时间戳=0/20 |
| §8.2 图片消息复用上传链路 | ✅ | `A9-0/A9-0b/A9/A9b`（`uploads/yyyy/MM/dd` 三层、url 真能取回 3,271 B 图字节、列表摘要成「[图片]」）+ `C4` 浏览器里 `naturalWidth=120 complete=true`（不是裂口图标） |
| §8.2 拉黑后禁输入 + 举报私信进审核队列 | ✅ | `A12-1..10` 十条 + `B11-1/B11-2`（WS 业务报错**不拆 socket**）；`C12/C13/C14` 三张图：二次确认文案 / 输入区与气泡一起消失只剩「解除限制」/ 解除后历史气泡与输入框一起回来 |
| **§8.3 D5 双窗口双向实时 + 未读正确 + 拔网线 10 秒不丢不重** | ✅ | 线 1 的 C 组就是**两个 context（甲/乙两块屏）**：`C3` 镜像读数 甲 13/7 ⇔ 乙 7/13、`C6` 乙那块屏**一次没刷新**就收到、`C9` 角标跨窗口亮起、`C17` 离线 11 秒恢复 |
| §8.3 `/ws/info` 经 Vite 代理正常 + 关掉 WS 后 SockJS 降级 XHR-polling | ✅ | `B15`（`websocket=true` 这个字段决定浏览器要不要走真 WS）+ `B16`（xhr-polling 拿到 `o` 帧） |
| §8.3 `git tag stage-5-pm` | ✅ | 本目录收口之后打 tag，见 §6 末与 `docs/dev-log.md` |
| **T5.8 §8.4 通知驱动 U13 红点** | ✅ | `B7-1/B7-2` 服务端折成 `type=dm`；`C9→C10→C11` 顶栏角标亮起 → 与行内角标同源 → 读完当场归零（`el-badge` 的 hidden 生效 = `unreadTotal` 真回 0）；§8.4 第 4 条「断网 10s 恢复后未读自愈」由 C17 + `C11` 合起来签 |

### A16 的两段式取证姿势（必须按这个顺序，一步到位跑不出来）

```
# 1) 建一枚专用禁言夹具（口令摘要从 demo05 复制，明文只在仓库外那条链路上）
INSERT INTO user (`username`,`password`,`nickname`,`status`,`role`,`ai_style`,`reg_source`,`agree_privacy_at`)
SELECT 'gate5_mute2', `password`, '闸5禁言对照号','ACTIVE','USER','warm','seed',CURRENT_TIMESTAMP(3)
FROM user d WHERE d.username='demo05' AND NOT EXISTS (SELECT 1 FROM user e WHERE e.username='gate5_mute2');

# 2) 先以 ACTIVE 登录，令牌落盘（仓库外 _cache/mindisle-dbtmp/a16-token-<acct>.json）
node probe/a16mute.mjs --phase=login --acct=gate5_mute2 --peer=5

# 3) 库里把它置 MUTED（模拟「正在用的这个人被处置了」）
UPDATE user SET status='MUTED' WHERE username='gate5_mute2';

# 4) 用第 2 步那份旧令牌继续读写
node probe/a16mute.mjs --phase=send  --acct=gate5_mute2 --peer=5

# 5) 跑完还原
UPDATE user SET status='ACTIVE' WHERE username LIKE 'gate5%';
```

为什么不能用 `pmgate.mjs` 的 `GATE_MUTED_ACCT` 一步到位：**MUTED 之前根本登不进来（HTTP 403 / code=20003）**——这就是本轮抓到的真缺陷（§3 第 3 条）。两段式恰好顺手多证了一件阶段 6 要用的事：A6 的禁言**即时生效**（旧令牌还在，读得到资料、读得到会话列表，写权限当场没了）。
⚠️ `AuthService` 的修复已进源码，但 **8080 上跑的还是旧类**，要重启后端才能用「MUTED 直接登录」这条路复跑 A16；本轮结论以两段式现量为准。

## 3. 本轮两个「判据红 ≠ 产品红」的定性 + 两个真缺陷

1. **C17 曾经红（`n2=2`）＝判据自身缺陷**。`t17a/t17b` 是写死的字面量，C 组会话跨轮累积 ⇒ 上一轮同文本还留在 DOM 里 ⇒ 被读成「补拉重复渲染」。**定性方法就是一条 SQL**：同正文在库里有 3 条（id 89 / 129 / 151，逐字相同，跨轮各一行）。修法不是放宽断言，而是**给正文加本轮唯一标记**（探针里本来就有 `TS`，只是 C 组没用上）⇒ 复跑读数 `{"n1":1,"n2":1}`。
2. **domprobe 第 [7] 组曾经红＝判据过期**。阶段 5 在资料卡操作区**故意**加了「私信」按钮（C7/C8 两张图就是它的交付证据），而 domprobe 还钉着 `btns.length === 1`。改成「关注在第一颗、总数 ≤2」后 **121/0**。**教训：加功能的那一轮要顺手把所有旧闸门的同一判据扫一遍**，否则下一轮会把功能当故障查。
3. 🔴 **真缺陷①（本轮修）：MUTED 账号登不进来**。`AuthService#allowCoolingOrReject` 只放行 ACTIVE 与冷静期 DELETED ⇒ 禁言用户连登录/刷新都 20003 ⇒ 2 小时后 token 过期就被彻底踢出产品，**BR6「禁言期间可读、可点赞」在工程上不可达**。修法：新增 `MUTED` 常量 + `static boolean allowsSignIn(String)`（trim + 大写归一后 ACTIVE‖MUTED 放行，BANNED / DELETED / 脏值 / null **失败关闭**），`AuthService`(379 行) 调用之，`AuthServiceSignInTest`(49 行) 12 项钉住。**口径：夺写权限的是 `PostingQuotaService`（10003），不是登录闸门（20003）。**
4. 🔴 **真缺陷②（上一轮修，本轮由 C 组判据继续钉住）：`activePeer` 交接失效**——从资料卡跳进会话页时 store 里的「当前对端」没跟着换。C 组把「同一账号只有取证客户端在看」写成判据之后，这类跨窗口状态泄漏才会当场变红。
5. 探针侧新坑：① **`fetch` POST 裸 JSON 必须显式带 `Content-Type: application/json;charset=utf-8`**，漏了 Spring 不反序列化 ⇒ **HTTP 500 / 90004「服务开小差了」**，本轮误看了半天「登录挂了」；② 行尾混用——`pmgate.mjs`=CRLF、`domprobe.mjs`=LF、Java 源=LF，node 补丁脚本的锚点必须按各自行尾 join，猜错就 MISS。

## 4. 复跑清单与顺序纪律（顺序错了会拿到假红）

```
# 0) MySQL 3306 + 后端 8080（含私信模块）+ 前端 5173 必须在跑
#    探针期间不要改前端文件、不要 mvn compile、不要重启 8080
cd E:\codex workspace\009_心屿AI心理陪伴社区

# 1) 先跑 DOM 回归闸（便宜、覆盖面大，判据改动在这条线上先暴露）
node frontend/probe/domprobe.mjs

# 2) 协议层先跑（NOUI，不占浏览器，先把 96 条判据钉住）
cd frontend
PACE=350 NOUI=1 GATE_SENDER=demo02 GATE_RECEIVER=demo04 GATE_THIRD=demo05 node probe/pmgate.mjs

# 3) 带 UI 全量（会往 docs/gate/阶段5/ 写 16 张图）
#    ⚠ 跑之前让真人退出浏览器里的 demo02 / demo04
PACE=1050 GATE_SENDER=demo02 GATE_RECEIVER=demo04 GATE_THIRD=demo05 node probe/pmgate.mjs

# 4) A16 禁言补跑（两段式，见 §2 末尾）
node probe/a16mute.mjs --phase=login --acct=gate5_mute2 --peer=5
node probe/a16mute.mjs --phase=send  --acct=gate5_mute2 --peer=5

# 5) 后端全量单测（会重写两份实验产物 ⇒ 之后写文档要用重跑后的数字）
cd ..\backend; mvn -o -B test
```

三条纪律：

1. 🔴 **C 组跑之前必须让真人退出 demo02/demo04**。同一 uid 的第二块屏会让「镜像条数 / 角标只有一个数 / 补拉不重复」三类判据同时失去意义（本轮就是靠这条抓掉缺陷②的）。
2. 🔴 **会写库的线排前面，只读的线排后面**；同一个身份的重探针不要挤在同一分钟里跑（NFR7 限流 60 次/分按身份计数，429 会让「列表为空」这类判据假红）。
3. 🔴 **判「没落库」用本轮唯一标记**，不能拿「会话总长」当判据——它会跟着判据一起长（`A8b`/`A12-7` 现在就是这么写的）。

## 5. 夹具污染现量与待清账号（正解是可重入 seed，不是一条 DELETE）

- 本轮新增 `user` 两行：id=484 `gate5_muted`、id=485 `gate5_mute2`（已还原 ACTIVE，**未删**，因为它们还是 A16 的夹具）。
- `private_message` total **172**、`MAX(id)=184`（跑前 129）；`audit_task` total **94**（跑前 73）；`alert_ticket` total **183**（跑前 166）。
- 🔴 **同正文跨轮累积已实测**：`SELECT id FROM private_message WHERE content LIKE '%回来应当只看到一次%'` 返 3 行（89/129/151）——这是 §3 第 1 条那次假红的根因。
- 演示账号统一口令是 seed 里的**演示用途**（`sql/09_seed.sql` L12/L254 已注明；探针常量在 `pmgate.mjs` L49）。**别把它当秘密写进新文档，更别把数据库 root 口令带进任何地方。**
- 首屏污染复检：C 组截图里的会话对象是 demo04（屿04），**本轮是唯一一处首屏干净的界面证据**；广场首屏仍是探针话题/探针帖，仍等阶段 8 的可重入 seed。

## 6. 本目录文件清单

| 文件 | 内容 |
|---|---|
| `README.md` | 本文件：环境事实 + 五条线 + §8.1/8.2/8.3 逐条签 + 判据缺陷与真缺陷定性 + 复跑纪律 + 夹具污染 |
| `pmgate.log`（156 行） | 线 1 的原始输出（PASS 也打印读数，不只打印 PASS） |
| `shot-manifest.json` | C 组 16 张图的机器读数（视口 / 账号 / 夹具 id / 每张图的判据） |
| `01…16-*.png`（16 张） | 真浏览器界面证据：列表未读、双向气泡与日期分组与求助卡、乙侧镜像、图片气泡、发送与送达回执、不刷新也收到、跳对方主页、主页私信入口、顶栏角标亮起、行内角标、读完归零、拉黑二次确认、拉黑后的会话页、解除限制后回来、举报私信弹窗、断网重连后不丢不重 |

脚本面（`frontend/probe/`）：`pmgate.mjs`（1392 行，A/B/C 三线合一）、`a16mute.mjs`（113 行，两段式禁言）。
后端新增：`config/WebSocketConfig`(153)；`pm/`＝`PmService`(885)、`PmStore`(106)/`PmStoreAdapter`(187)、`PmMessageController`(157)、`PmPushGateway`(104)、`PmRealtimeListener`(131)、`PresenceRegistry`(242)、`WsAuthHandshakeInterceptor`(132)、`StompPrincipalHandshakeHandler`(70)、`PmDeliveryRetryJob`(91)、`PmSendEvent`(33)/`PmReadEvent`(17)、`pm/dto/` 14 个；`web/PmController`(200)；`notify/StompPushHook`(104)；`entity/PrivateMessage`、`entity/UserBlock`、`mapper/PrivateMessageMapper`、`mapper/UserBlockMapper`。
后端新增测试：`pm/PmServiceTest`、`pm/PresenceRegistryTest`、`pm/WsAuthHandshakeInterceptorTest`、`pm/PmFakeStore`、`notify/StompPushHookTest`、`auth/AuthServiceSignInTest`。
前端新增：`api/pm.js`(128)、`composables/useWs.js`(333)、`stores/pm.js`(766)、`views/chat/ChatListView.vue`(252)、`views/chat/ChatDetailView.vue`(517)。

## 7. 口径与偏离（不写下来，下一轮一定会重新踩一遍）

1. **STOMP 的订阅标识在 `subscription` 头，不在 `id` 头**——自写最小帧客户端按 `id` 过滤会一帧都收不到（症状长得像「服务端没推」）。
2. **`delivered` 可能早于 `sent`**（两个回执由不同线程发出）：判据改成「从本轮标记之后往后找」，**不是**把顺序写死。
3. **判「没落库」用本轮唯一标记，判「有落库」用 id 递增区间**（`A4d/A4e` 只认本轮那三条）。
4. **在线数不用绝对阈值**：`B3-1` 判「载荷只有 `{onlineCount,ts}` 且不含任何 id」、`B13` 判「不高于乙在线那一次」——本机还挂着别的连接，写死 `=== 2` 必假红。
5. `@stomp/stompjs` 7.3.0 的 `activate()` **返回 void**（不是 Promise），别 `await` 它再判连接态，要等 `/queue/ping` 那一帧。
6. **C 组判据要求「同一账号只有取证客户端在看」**——曾据此抓掉 `activePeer` 交接失效这个真缺陷。
7. 🔴 **正文类判据必须带本轮唯一标记**（本轮新增的规矩，根因见 §3 第 1 条）。
8. **探针的 POST 必须显式带 Content-Type**，否则 Spring 不反序列化 ⇒ 500/90004。
9. **私信是唯一允许进入人审的私密内容**（FR6.6 / BR10）：`A13-1` 的回执带「我们会在 24 小时内看完这条私信」，隐私政策页已有对应文案；**管理端查看私信正文的解匿通道属阶段 6（T6.5）**。

## 8. 没在本目录证明的事（结转，不写成已收工）

- `PmDeliveryRetryJob`(91) 只有**行为证据**（B9-1/B9-2）没有单测：重试上限、多实例重复投递这两条边界留给阶段 8。
- **MUTED 直接登录**这条修复要重启 8080 才生效；本轮结论是两段式现量，阶段 6 的 A6 用户处置页要复跑它。
- `/user/queue/notify` 目前只折了 `dm` 一类；评论 / 关注 / 审核结果三类通知仍走阶段 3 的 30 秒轮询心跳（T3.16 的「按类型开关」与聚合视图未做）。
- 私信撤回/删除、端到端加密、多读者已读一致性（只保证发信方看到读者已读，不保证两个读者之间同步）——三条都是 V1 范围取舍，写进论文局限。
- 结转阶段 6：危机私信建的 `alert_ticket` 已落库但**没有人办**（183 行、`pending` 全未认领）；`audit_task` 里 pm 档 25 条 PENDING；`user` 表**没有 `mute_until` 列** ⇒ 阶段 6 第一件事是 `sql/17_stage6_alter.sql`。

> **一句话收口**：阶段 5 的交付不是「私信能发」，而是「**双窗口实时 + 落库优先不丢不重 + 16 张真浏览器图 + 顺手修掉两个真缺陷（MUTED 登不进来、activePeer 交接失效）**」。