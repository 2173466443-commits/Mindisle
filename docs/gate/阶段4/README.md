# Gate 4 证据目录（阶段 4 · AI 陪伴与情绪识别 · M5）

这一目录回答一个具体问题：**「AI 对话那部分到底做好了没有」**——用户在本项目第四次说这句话之后，
阶段 4 不再以「代码写完 + 单测过了」收口，而是以**十条互相不能替代的取证线**收口。
每条线只登记**能复跑的脚本 + 真实读数 + 证伪记录**，没有读数的判据一律不算数。

## 0. 环境事实（2026-09-28 现查；2026-10-08 在浅色主题下的复跑读数见 §7，不信上一轮记录）

| 项 | 现值 |
|---|---|
| 后端 | 8080（pid 4960，真实上游 DeepSeek）；8081（pid 23900，**离线实例**，专为断网演示拉起，MINDISLE_LLM_PROVIDER=mock 那一档） |
| 前端 | 5173（pid 13568，Vite dev server）；MySQL 3306（pid 6980） |
| Redis | **6379 无监听** ⇒ 缓存走 Caffeine 本地兜底（MINDISLE_CACHE_MODE=local），限流与会话都仍然工作 |
| JDK / Maven | OpenJDK 17.0.19（OpenJDK 64-Bit Server VM）/ 离线模式「mvn -o -B test」 |
| 浏览器 | 本机 Chrome（C:/Users/Drbrain/AppData/Local/Google/Chrome/Application/chrome.exe）+ playwright-core（装在 E:/codex workspace/_cache/node_modules） |
| 单测 | **Tests run 529 / Failures 0 / Errors 0 / Skipped 1，BUILD SUCCESS，Total time 11.942 s**（09-28 19:13:48） |
| 口令 | 数据库口令只走仓库外的 rootpwd.cnf；本目录所有日志已 grep 过一遍，0 命中（见 §4 复跑纪律） |

## 1. 十条取证线（每条都注明「哪一天跑的」，跨天的数字不许混用）

| # | 线 | 脚本 | 读数 | 证据文件 |
|---|---|---|---|---|
| 1 | jsdom DOM 级 | frontend/probe/domprobe.mjs（1369 行） | **121 项 / 失败 0** | domprobe-20260928.log（09-28） |
| 2 | 真浏览器截图 | frontend/probe/shootgate.mjs | **21 张 / 通过 21 / 漏白 0 / 红字 0 / 断言失败 0** | shootgate-20260928.log（09-28）。⚠️ 这批 png 仍落在 docs/gate/阶段3/（脚本第 27 行写死输出目录），是「同一套界面在阶段 4 之后仍然干净」的复检图，不是阶段 4 新增图 |
| 3 | 阶段 4 专用截图线 | frontend/probe/stage4gate.mjs（869 行） | **pass 63 / fail 0** + 11 张图 + console=0 pageerror=0 http 失败=0 | shot-manifest.json（**2026-10-08 09:48 +08:00 复跑**，at=2026-10-08T01:48:12Z；09-28 首跑同读数）+ 本目录 01–09 号 png + console-evidence.log |
| 4 | 提示词注入 | frontend/probe/injection.mjs | **PASS 95 / FAIL 0**（26 条语料 = 12 攻击 + 3 漏检回归 + 11 日常零误伤；全局对账 起跑 16 → 收尾 31，新增 15 = 期望 15） | prompt-injection.md（199 行，§1.1 是三次证伪 A/B/C） |
| 5 | 断网演示 | docs/offline-demo.mjs（227 行 / 15 条 check） | **15/15 全绿**（09-28 用当前源码复跑；第 1 轮 646ms、第 4 轮 695ms）；证伪：把 model 改成 offline-demo-model ⇒ 14/1 FAIL 再还原 | offline-demo-20260928.log + offline-degrade.md |
| 6 | HTTP 冒烟 | docs/smoke.mjs | **312 项 / 断言 294 条 / 失败 0**（09-28），其中第 24 步是周报分享的 19 条判据 | smoke_r23.log（83 KB，留在仓库外 _cache，只把汇总与第 24 步抄进 weekly-report-share.md） |
| 7 | 后端单测 | mvn -o -B test | **529 / 0 / 0 / 1 skipped**（含 T4.15 两份实验产物、T4.20 ③ 的 17 项、T4.8 情绪补标等） | mvn_r24_final.log（175 KB 留在仓库外）；产物见 论文材料/experiments/output/ 两文件 |
| 8 | AI 对话全链路（真浏览器 + 真模型） | frontend/probe/aichat.mjs（664 行） | **74 项 / 失败 0**（09-28 19:39；本轮 TTFT 353ms／全篇 609ms；「停止生成」在已有字之后按下、两次读数等长 31=31） | aichat-20260928.log |
| 9 | 路由遍历 | frontend/probe/routecrawl.mjs（200 行） | **32 项 / 失败 0**（09-28 19:38；/emotion 四图 canvas=6 已画=6；/ai textarea=1 conv=48；免登录 /help 热线在） | routecrawl-20260928.log |
| 10 | 🆕 D1 新账号闭环 | docs/d1-onboarding.mjs（16 KB / 15 条 check） | **15 项判据全绿**（注册→单独同意→首次对话→档案出点，**全程 3.2 s < 180 s**；TTFT 864ms／整轮 1802ms；conv=120；meta=neutral → 库里回读 fear/llm；档案 5→6 点）；证伪：--no-consent 用**从未授权**的马甲 d1_gate_nc ⇒ **14 项里 10 项 FAIL** | d1-onboarding-green.log + d1-onboarding-falsify.log |

## 2. 逐条签手册 §7.4 的阶段 4 收工口径

| §7.4 条目 | 签什么 | 凭什么 |
|---|---|---|
| **D1 新注册用户能在 3 分钟内走完「注册→同意→第一次对话→档案出情绪点」** | ✅ | 线 10：15/15 绿 + 3.2s；证伪 10 红（没授权就整条断） |
| **D2 对话首字 < 2s** | ✅ | 线 10 客户端 TTFT **864ms**、线 8 客户端 TTFT **353ms**；服务端「ai_call_log」另记 first_token **707ms**（本轮 chat 那行）——两个口径分开写，不混用 |
| **D3 危机场景 30 秒内落到管理端** | 🔴 **◐ 结转 T6.1** | 已证的一半：分级 L0–L3 正确、工单**真的落库**（alert_ticket 148 行，L2=107 / L3=41 / L1=0，与设计一致）、危机卡片截图 03-AI对话-L2L3危机卡.png。**没证的一半**：「管理端 30 秒内看到」——AuditController / AdminController 目前只是壳（阶段 6），所以 D3 只能签半条，剩下半条明写结转 |
| **D8 情绪档案四图 + 与库里对账** | ✅ | 线 9 canvas=6 已画=6、无「数据到了但图没画出来」横幅；线 10 档案 trend/distribution/sources 与 emotion_record 逐行对账（passive=被动识别 / checkin=主动打卡） |
| **断网可用** | ✅ | 线 5：15/15（降级文案、逐字上屏、连续 4 轮、degraded=1 落库 12 行、离线实例 8081） |
| **实验首轮数字** | ✅（**只是首轮，不是结论**） | 线 7 的两份产物，见 emotion-channel-agreement.md；🔴 §7.3 的 300 条标注集 / Cohen's Kappa / macro-F1 **一律没做、没签** |
| **git tag stage-4-ai-emotion** | ✅（tag message 里明写 D3 只签半条、半条结转 T6.1） | 见 §3 |

## 3. 这一目录**没有**证明的（边界，写在这里而不是等答辩被问）

- **§7.3 的三件事一件都没做**：没有第二人独立标注 ⇒ 没有 Kappa ⇒ 没有 macro-F1。24.53% 是**一致率**，不是准确率。
- **管理端没有任何一个能用的页面**：AuditController(3,509 B) / AdminController(2,585 B) 是壳；「举报能收不能办」现查为 **content_report 96 行全部 PENDING**（09-28 现查），一条都没被处理过；话题预审默认开着 ⇒ **topic.audit_status=PENDING 36 个**，话题圈仍是「能建不能用」。
- **私信（阶段 5）零实现**：private_message 表已建但 **0 行**（09-28 现查），STOMP 端点、握手鉴权、目的地、未读/在线、私信风险建单、U9/U10 前端全部未开工。
- **通知中心整页**（T3.16 U13）仍只有顶栏铃铛。
- **推荐（阶段 7）**：FeedController 推荐 Tab 是占位卡；recommend_result / item_similarity 两张表 **0 行**；六组对照与消融没跑。
- **压测（NFR4/5）只做过阶段 3 那一次「/api/posts P95」**，AI 侧只有单请求 TTFT 与词典单条时延，没有并发压测。
- 「停止生成」的两种收尾（一个字都没出 / 已经出了半句）在 aichat 里是分开的两条判据，**危机工单不会被探针删掉**（删会话只删消息，alert_ticket 与站内信是独立生命周期）——所以危机段默认关，只在明确取证时开。

## 4. 复跑清单与顺序纪律（**顺序错了会拿到假红**）

~~~
# 0) 后端 8080 + 离线实例 8081 + 前端 5173 + MySQL 3306 必须在跑（全部由用户手动启动）
cd E:\codex workspace\009_心屿AI心理陪伴社区

# 1) 先刷夹具（树洞到期时间是硬编码时刻，不刷就会「过期」在镜头前）
node docs/seed-demo.mjs

# 2) 后端全量单测（会重写两份实验产物 ⇒ 之后写文档要用重跑后的数字）
cd backend; mvn -o -B test; cd ..

# 3) 会写库的线排在前面
node frontend/probe/domprobe.mjs
node frontend/probe/stage4gate.mjs
node frontend/probe/injection.mjs
node frontend/probe/aichat.mjs
node docs/d1-onboarding.mjs

# 4) 只读的截图/遍历排在后面
node frontend/probe/shootgate.mjs
node frontend/probe/routecrawl.mjs
node docs/offline-demo.mjs
node docs/smoke.mjs
~~~

三条纪律（本轮各踩中一条）：

1. 🔴 **同一个身份的重探针不能挤在同一分钟里跑**。NFR7 的普通接口限流是 **60 次/分按身份计数**，
   打开一个页面就要拉 /api/users/me、/api/notifications、/api/ai/conversations?limit=50、/api/emotions/weekly-report……
   本轮 aichat 刚跑完立刻接 routecrawl，routecrawl 吃到 **4 个 429**，于是「对话页 conv=0」这条判据变红——
   症状长得像「会话列表没渲染」，根因是配额。**复跑时中间至少隔 60 秒**（本轮实测：隔开之后 32/32 全绿）。
2. 🔴 **长度类判据要先声明前提**。aichat 的「侧栏比基线多一条 / 收尾回到基线长度」前提是会话数没撞上
   keep-conversations=50 的配额（撞上时产品会**逻辑删除最旧的会话**，这是 T4.2 的既定行为）。
   脚本里现在有「前提守卫」：余量不足先腾掉最旧的历史探针会话并把 id 打进日志，断言本身一条都没放宽。
3. **证伪类开关只能作用在「从未满足前置状态」的账号上**。「单独同意」是持久状态，
   在已经授权过的 d1_gate 上跑 --no-consent 会得到 15/15 全绿——那不是断言坏了，是库里同意还在。
   所以 d1-onboarding.mjs 的证伪模式固定用另一枚马甲 d1_gate_nc。

## 5. 夹具污染与待清账号（最后一轮统一处理，正解是可重入 seed，不是一条 DELETE）

- 本轮/上轮新增的一次性账号：**d1_gate(470)、d1_gate_nc(471)**、falsify_tm_0928、offline_probe、
  probe_ntf_*、probe_src_*(461,462)、probe_topic_*(463)、smoke_sha_/smoke_shb_ 各 5 个(378/379、394/395、410/411、426/427、455/456)。
- **不许删的夹具**：帖 42/140/141/1094/1103/1215/1231（1231 是周报分享产物）、domprobe 造的 1232–1237、
  会话 59/60（stage4gate 的回看靶子）、alert_ticket 148 行（危机线的唯一落库证据）。
- 本轮被会话配额回收掉的 conversation 65/66/67：软删除，行还在库里 deleted=1，需要时一条 UPDATE 能还原。
- 首屏污染复检：线 2 的 21 张图与线 3 的 11 张图里，广场首屏与主页面的主角现在是不是探针账号——这条**仍是已知问题**，
  阶段 8「演示数据 seed 升级」要把它按「固定 id 段 + 统一前缀 + 一条 reset 重建」做掉。

## 6. 本目录文件清单

| 文件 | 内容 |
|---|---|
| README.md | 本文件：环境事实 + 十条线 + §7.4 逐条签 + 边界 + 复跑纪律 + 待清清单 |
| fixture-timebomb.md | 树洞到期销毁夹具（seed-demo.mjs 的幂等与四条证伪 R-a/R-a2/R-c/R-d） |
| offline-degrade.md | 断网演示线（离线实例 8081、降级文案、degraded=1 落库 12 行、15/15） |
| emotion-channel-agreement.md | T4.15 两份实验产物：通道占比 / 三个一致率 / 时延压测 / 五条判据 / 七次证伪 / 🔴 不能主张什么 / relabel 回写口径订正 |
| weekly-report-share.md | T4.20 ③ 周报去标识分享：实现落点 / 19 条冒烟判据读数 / 库内复核 / 17 项单测与四次证伪 |
| prompt-injection.md | 提示词注入线（26 语料 / 95 判据 / 三次证伪） |
| 01–09 号 png（11 张） | 阶段 4 界面证据截图：流式回复、情绪标签与免责脚注、L2/L3 危机卡、情绪档案四图、详情页停留与读到底、隐私中心四张 |
| shot-manifest.json | stage4gate 的机器读数（pass=63 fail=0 / viewport / 账号 / 夹具 id；最近复跑 2026-10-08 09:48，浅色主题） |
| console-evidence.log | 截图线全程 console/pageerror/http 计数（0/0/0） |
| d1-onboarding-green.log / d1-onboarding-falsify.log | D1 闭环的正样本（15/15）与负样本（14 项中 10 红） |
| aichat-20260928.log / routecrawl-20260928.log / domprobe-20260928.log / shootgate-20260928.log / offline-demo-20260928.log | 各线的当日原始输出（PASS 也打印读数，不只打印 PASS） |

## 7. 2026-10-08 复跑记录（浅色主题下重新签字）

需求 Q9 已于 2026-09-30 改判为「明亮简约 · 小红书式卡片墙」，本目录 11 张 png 在 **2026-10-08 09:47~09:48** 用
`CRISIS=1 node probe/stage4gate.mjs`（工作目录 frontend/）重拍，读数不变：**pass 63 / fail 0 / 图 11 张 / console=0 pageerror=0 http 失败=0**。
注意 03-AI对话-L2L3危机卡.png 只有带 `CRISIS=1` 才会拍（危机段默认跳过，第一次不带只出 10 张图）。

### 7.1 复跑前拆掉的一颗「时间炸弹」（夹具随时间过期，不是产品缺陷）

| 症状 | 根因 | 修法 |
|---|---|---|
| `[emo]` 判据 FAIL：情绪档案停在「数据积累中」空态 | BR12 `accumulating = 可信天数 < 3`（`EmotionProfileService.MIN_TRUSTED_DAYS`，confidence ≥ 0.6 才算可信）。09-28 是靠连续三天跑探针**自然攒出来**的，隔到 10-08 只剩 1 天 ⇒ 假阳性 | 新增 `docs/seed-emotion-history.mjs`：以哨兵 `[seed-emo-history]` 给 demo01 补最近 7 天记录（6 天 × 打卡+被动各 1 行），幂等（先按哨兵 DELETE 再 INSERT，只碰自己的行）；自检输出可信天数=7、行数=13 |

另一颗同类炸弹（树洞 `auto_destroy_at` 158 条全部过期）打在**阶段 7 的 B8 冷帖线**上，判据与读数订正见 `docs/gate/阶段7/清单.md`，
修法同为 `node docs/seed-demo.mjs`（把过期树洞顺延 7 天，ROW_COUNT=158）。

### 7.2 本线**不签**颜色（写白，避免误读）

`stage4gate.mjs` **没有任何配色判据**（只有 `.md` 的 `white-space:normal` 之类排版判据），所以这 63 条绿证明的是功能与 DOM 接线。
「浅色主题是否统一」由另两条线签字：`frontend/probe/shootgate.mjs`（阶段 3：21/21 通过、深色残留 0、主背景 `rgb(246,246,247)`）
与 `frontend/probe/recgate7.mjs`（阶段 7：判据 64/64、7 张图四道闸全过、深色残留 0）。
