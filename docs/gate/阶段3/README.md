# Gate 3 证据目录（阶段 3 · 社区核心内容 · M4）

> 本目录只回答两个问题：**手册 §6.4 第 3 条（500 条帖量级 P95 ≤ 500ms）与第 4 条（前端可交互 + 深色主题统一 + 无 console 红字）到底过没过**，
> 以及「过了」这两个字的**边界在哪里**。目录里每个数字都能被同目录的脚本/SQL 重跑出来，命令原样抄在下面。
> 生成日期 2026-09-23（Asia/Shanghai）。取证账号 `smoke_runner`（user id 8，昵称「冒烟用户」，符合手册 §12「论文截图不得出现真实姓名」）。
> **最后复跑 2026-09-23 19:23（手册 v1.2.5）**：§1.3.1 那个「同屏两个评论 N」修完之后重拍 21 张，仍 **21/21 PASS / 漏白 0 / 红字 0 / 断言失败 0**；本轮新增的第 5 类判据（逐张专属判据要打印读数）见 §1.1 末。

---

## 0. 环境事实（全部现查，不信上一轮记录）

| 项 | 实测值 | 怎么量的 |
|---|---|---|
| 后端 | Spring Boot 8080 = `java` pid **27216**（**用户手动启动**，本轮未重启、未改一行后端代码） | `Get-NetTCPConnection -State Listen -LocalPort 8080` |
| 前端 | Vite dev 5173 = `node` pid **26112**，`GET /` **HTTP 200** | 同上 + `Invoke-WebRequest` |
| 数据库 | MySQL 9.7.1 3306 = `mysqld` pid **6980**，库 `mindisle` | 同上 |
| 浏览器 | **Google Chrome 150.0.7871.115**，`C:/Users/Drbrain/AppData/Local/Google/Chrome/Application/chrome.exe`（`Program Files` 下没有，装在用户目录） | `chromium.launch({executablePath})` 后 `browser.version()` |
| 驱动 | `playwright-core@1.63.0`（**只用系统 Chrome，不跑 `npx playwright install`**，缓存留在 `E:\codex workspace\_cache\node_modules`） | `require(PW_DIR)` |
| 视口 | 桌面 **1600×1000**（手册 §12 论文截图口径）；移动端 **390×844**（iPhone 14 逻辑分辨率） | `shot-manifest.json` 的 `viewport` / `mobile` |
| 语言/时区 | `locale: zh-CN`、`timezoneId: Asia/Shanghai` | 每个 `newContext` 显式设置 |

---

## 1. §6.4 第 4 条：21 张真浏览器截图

### 1.1 这一批图不是「拍得好看」，每张带四条通用断言 + 逐张专属判据

`frontend/probe/shootgate.mjs`（现 **291 行**）对每张图同时判四件事，任何一件不过就 **`exit 1`**：

1. **主背景**必须是需求 Q9 定的午夜蓝 `rgb(14, 22, 38)`；
2. **漏白扫描**：15 类会被 Element Plus 自己画背景的容器（`.el-card` `.mi-card` `.el-dialog` `.el-popover` `.el-popper` `.el-message` `.el-input__wrapper` `.el-textarea__inner` `.el-select-dropdown` `.el-radio-button__inner` `.el-tag` `.el-tabs__content` `.el-loading-mask` `.el-drawer`）里，凡是**可见且尺寸 ≥6px** 的表面，RGB 三通道都 ≥248 就算一处漏白；
3. **文字量下限**：`#app` 的 `innerText` 长度低于该图设定的 `minText` 即判「疑似没画出来」（白屏最典型的特征就是渲染成功、内容为空）；
4. **红字/请求失败**：`console.error`、`pageerror`、`requestfailed`、**HTTP ≥400 的响应**四类全记，其中资源类失败只在 `response` 通道记账（`console` 里那条 `Failed to load resource` 不带 URL，定不了责，同一条也不该被数两遍）。favicon 与字体文件在白名单内。

另有两条**结构性断言**（只有真浏览器能做的）：`.feed .row ≥ 3`、`.flow .mi-card.post ≥ 1`、`.comments .row ≥ commentCnt`（判据按接口回的值动态取，写死数字会在下一轮冒烟之后变成假图），以及 07 的**布局判据**：`.reasons .el-radio` 相对 `.el-dialog__body` 的左偏移 **≤ 12px**。

> **v1.2.5 起新增一条纪律**：逐张专属判据（`spec.assert`）**必须把量到的数原样打印进 `console-evidence.log`**，PASS 也要打。06 那条现在打印 `.. 06 两处「评论」读数：卡片=19 / 评论区标题=19` —— 只留一个「通过」，下一轮没人能复核它当时比的是什么。

### 1.2 逐张说明

| 文件 | 路由 | 证明了什么 | 实测 |
|---|---|---|---|
| `01-U2登录页-未登录.png` | `/login` | 无 token 的登录页：表单、验证码图、危机入口在深色下的样式 | PASS · 136 字 |
| `02-路由守卫-未登录进广场被弹回登录.png` | `/feed` | **前端路由守卫**真的把未登录访问换成了登录页（不是后端 401 兜的） | PASS · 136 字 |
| `03-U3广场-推荐流.png` | `/feed` | U3 广场整屏：发布器紧凑形态、来源切换、类型 Tab、信息流；**页脚「广场共 223 条可见内容」与当轮 `shot-manifest.json` 的 `samples.postTotal=223` 逐字相等**（这个数会随探针写库漂移，见 §1.4 末两条） | PASS · 3310 字 · `.feed .row=23` |
| `03b-U3广场-整页.png` | `/feed` | `fullPage` 长页不破版（无限滚动已加载的全部内容一起拍） | PASS · 450 KB |
| `04-U3-b关注流.png` | `/feed` → 点「关注」 | 真点来源切换，流内容换成关注流且地址栏带 `?type=follow` | PASS · 1534 字 |
| `05-U6话题详情.png` | `/topic/1` | U6 话题圈：话题头、发帖数、关注按钮 + 该话题的公开帖流 | PASS · 2102 字 · `.flow .mi-card.post=20` |
| `06-U4帖子详情-评论区.png` | `/post/308` | **U4 详情 + 真评论树**：树洞倒计时、已赞/收藏态、举报入口、20 行评论（含 1 行「审核中，仅自己可见」= 本人待审评论的正确读侧表现）、匿名评论里手机号已打码；**卡片「评论 19」与评论区标题「评论 19」同口径**（本轮修的正是这里，见 §1.3.1） | PASS · 1155 字 · `.comments .row=20` · 读数判据通过 |
| `07-U4详情-举报弹层.png` | `/post/308` | FR8.5 用户侧举报入口：对话框深色一致、**六类理由左对齐**、正文/截图上传区、提交按钮 | PASS · 1394 字 · 布局判据通过 |
| `08-U5发布器.png` / `09-U5发布器-话题预选.png` | `/publish`、`/publish?topic=1` | U5 三形态切换 + 字数计数 + 匿名开关；`?topic=` 预填真的落进了选择器 | PASS · 554 / 574 字 |
| `10`~`13` 搜索四张 | `/search?...` | 帖子 / 话题 / 屿友三条路径 + **空态**（空态不是白屏，有引导文案） | PASS · 524 / 610 / 985 / 524 字 |
| `14-U12我的帖子.png` | `/me/posts` | U12 三态筛选页可用 | PASS · 478 字 |
| `15-U11屿友主页.png` | `/user/232` | U11 他人主页 + 资料卡 | PASS · 836 字 · ⚠ 主角是探针一次性账号，见 §1.4 |
| `16-顶栏通知铃铛.png` | `/feed` | 未读徽标（18）与通知弹层，**弹层是 body 下的 portal，最容易漏白，实测 0 处** | PASS · 3310 字 |
| `17-危机求助页.png` | `/help` | 不可关闭求助入口的独立页（FR2.7） | PASS · 207 字 |
| `18-404页.png` | `/no-such-page-xyz` | 兜底路由；`minText` 单独设为 20（这张按设计就只有两行字） | PASS · 22 字 |
| `19-移动端-广场.png` / `20-移动端-话题详情.png` | 390×844 | 响应式在真浏览器里没塌（D13 的一部分） | PASS · 3310 / 2102 字 |

**末次运行结果**：`21 张 / 通过 21 张 / 漏白 0 处 / 红字 0 条 / 断言失败 0 条`，**exit 0**。
原始输出：本目录 `console-evidence.log`；结构化清单：`shot-manifest.json`（每张图的 `url / chars / bg / leaks / errors / pass`）。

> ⚠ 一个取证口径：本轮**跑了两次**。第一次取样帖是 `#350`，`.comments .row = 0` —— 断言按 `commentCnt=0` 动态取值，所以它「通过」，但那张图**证明不了评论区可用**，只证明了评论区空态不崩。
> 于是把 `discover()` 改成「首屏没有带评论的帖就顺着 `nextCursor` 往深页找，最多再翻 6 页 ×50 条」，第二次取到 `#308`（接口 `commentCnt=19`，页面画了 20 行 = 19 条已过审 + 1 条本人待审）。**上表里的 06/07 是第二次的数据。**
> 这条改动本身也是一次教训：**近似断言（≥0）等于没断**。

### 1.3 首跑并不全绿：86 处漏白 / 6 条红字 / 1 条布局判据失败 → 三个只有真浏览器能看见的真 bug

`shootgate.mjs` 第一次跑（`_cache/mindisle-dbtmp/shootgate-1.log`）就是 **exit 1**。三个根因全部定位并修掉：

**①  深色主题从来没有生效过（最严重的一个）。**
用 `_cache/mindisle-dbtmp/cssdiag.mjs` 量 `getComputedStyle(documentElement)` 拿到实底：`--el-color-primary` 是 `#409eff`（EP 默认蓝）而不是品牌珊瑚色，`--el-fill-color-blank` 是 `#fff`。
根因不在 `theme.css` 写错，而在**注入顺序**：`vite.config.js` 的 `unplugin-vue-components` + `ElementPlusResolver` 会为每个用到的组件**再注入一份 EP 基础样式**，实测 `<style>` 顺序是 `0=EP base → 1=theme.css → 2=EP base 又一份`，同特异度后来者胜 → 自建主题被压回去。
**修法不是调顺序（拼不过插件），而是提特异度**：`theme.css`（92 行）把 EP 令牌块写成 `:root, html.dark:root {…}` —— `html.dark:root` 是 (0,2,1)，恒高于 EP 的 `:root` (0,1,0) 与 `html.dark` (0,1,1)；主色阶按 EP 深色约定重算（`light-N` 往背景混、`dark-2` 更亮）：`#F0876B / #AC6556 / #7F4F49 / #52383B / #3B2D34 / #25212D`，`dark-2 #F39F89`；补齐 bg/page/overlay、text 5 档、border 6 档、fill 7 档、mask。配套 `main.js` 在 `element-plus/dist/index.css` 之后、`theme.css` 之前 `import 'element-plus/theme-chalk/dark/css-vars.css'` 并 `document.documentElement.classList.add('dark')`。

**② 6 条 console 红字 = 两件事。**
(a) `GET /favicon.ico` 404（Vite 目录里根本没有图标）→ 新增 `frontend/public/favicon.svg` + `index.html` 里 `<link rel="icon" type="image/svg+xml" href="/favicon.svg" />`。
(b) `GET /api/feed/recommend` **恒 501 / code 90001**（阶段 7 才落地的推荐算法占位）→ `FeedView.vue` 加 `RECOMMEND_LANDED=false` + `RECOMMEND_STAGE='6/7'`，`codes.recommend` 静态置 90001，**`onMounted` 不再打这一枪**（「刷新」按钮仍真调，后端落地即出真数据）。**这是「让红字消失」唯一诚实的做法：不是把控制台静音，而是别去调一个明知没落地的接口，并且把「没落地」写在界面上。**

**③ 举报弹层里五个理由单选被推到中间。**
EP 的 `.el-radio-group` 自带 `align-items: center`；`PostDetailView.vue` 把它改成 `flex-direction: column` 之后，交叉轴从垂直变成水平 → 单选整体水平居中。修法：`.reasons` 显式加 `align-items: flex-start`（代码里留了注释说明这条是截图抓的）。07 这张图就是它的回归证据。

### 1.3.1 🔴 第 4 个真 bug 是**看图**看出来的：同一屏两个「评论 N」，一个 19 一个 20

上面三个 bug 修完之后，`shootgate` 连着几轮 **21/21 PASS**、`domprobe` 全绿、`npm run build` exit 0、冒烟 246 项全绿 —— **四条线同时绿**。逐张复核自己刚提交的截图时，在 06 这张上看见**同一屏写着两个「评论」**：卡片统计行是「评论 19」，下面评论区标题是「评论 20」。**21 条断言没有任何一条能发现它**，因为两处各自都「对」。

**根因是 DB 现查出来的，不是猜的**（`_cache/mindisle-dbtmp/verify-state-r13.sql` → `.out.txt`，本轮实测）：

| 量 | 值 | 谁在用这个数 |
|---|---|---|
| `post 308` 的 `comment` 行数 | 20 | — |
| 其中 `status='PUBLISHED' AND deleted=0` | **19** | 卡片/页脚：`post.comment_cnt` ← `CommentMapper.countPublished`（只数已发布，**含楼中楼**） |
| 其中 `status='PENDING'` | 1 | 只有作者自己看得见 |
| 其中 `parent_id IS NOT NULL` | 0 | 这一帖没有楼中楼，所以两数之差恰好就是那条待审 |
| 评论区标题原来用的数 | **20** | 列表 `total` ← `CommentStoreAdapter.countVisibleRoots`（**只数一级**，且把作者自己的 PENDING 算进来） |

**两边都不是 bug**：`comment_cnt` 回答「这条帖有多少条已发布评论」，列表 `total` 回答「当前查看者能看到多少条一级评论」，后端两个方法都有注释写明口径。**撞在前端同一个屏上、又都印成「评论 N」，才是 bug。**

**修法**（不加接口，改的是「同名必须同口径」这条纪律）：
- `CommentSection.vue` 新增 prop `publishedCount`，标题数字改用它（拿不到才回退 `total`），并把口径写进那行灰字：「只数已发布评论，楼中楼回复也算在内，与卡片上的『评论』是同一个数。本页另有 1 条审核中、仅你可见，所以下面会比这个数字多。」
- 翻页到底那行由「共 20 条一级评论，已经看到底了」改成「一级评论 20 条已全部加载（含 1 条审核中、仅你可见）」——**把差值解释掉，而不是把它藏起来**。
- `PostDetailView.vue` 把 `post.commentCnt` 传进组件，两处共用同一个数（发新评论时它自增，口径仍与后端一致）。

**判据（两条线各钉一份）**：`shootgate` 06 的 `assert` 与 `domprobe` 第 9 组各加一条「卡片读数 === 评论区标题读数」，并把两个读数打印进 `console-evidence.log`。

**🔴 新写的断言必须先证伪**：把 `shownTotal` 故意改回 `total` 重跑，06 **确实 FAIL** 并打印 `卡片=19 / 评论区标题=20`（`_cache/mindisle-dbtmp/shootgate-neg.log`），改回来才重新 21/21。**一条从没红过的断言，和没写过它一样。**

### 1.4 这批图**没有**证明的（边界，别拿它当万能背书）

- **U1 首页仍未开工**：§6.4 第 4 条的判据原文只点名 **U3/U4/U5/U6**，本目录证的也正是这四个页面 + 搜索/我的/主页/铃铛/求助/404/移动端。**这不等于「全部页面已在真浏览器里验过」**，U1 依旧是 ☐（记在 §15 T3.13 行）。
- **推荐 Tab 是占位**（见 ②(b)），图里那条流是「最新」口径，不是算法结果 —— 阶段 7 之前广场截图都不要声称展示的是推荐流。
- **带图片的帖子没有样本**：库里现有公开帖都是纯文本，`el-image` 的懒加载与深色占位没被拍到。
- **移动端只拍了两页**（广场、话题详情），其余页面的窄屏布局未取证。
- **没有跨浏览器**：只有 Chrome 150。Safari/Firefox 兼容性属手册 §11（阶段 8）。
- 危机弹层「不可关闭」这一条属交互语义，静态图证不了，由 `domprobe` 第 8 组与冒烟第 13 步分别背书。
- **🔴 取证样本正在被自家夹具污染（本轮看图新发现，未修）**：`domprobe` 每跑一次就往库里注册 5~6 个一次性账号、发 4~6 条夹具帖，而 `shootgate` 的取样规则是「广场首屏里 `commentCnt` 最大的帖」+「首条非匿名帖的作者」——于是本轮 03 那张广场图的**首屏两张卡是「探针话题162429920 乙/甲」**，15 那张 U11 主页图的主角是 `probe_topic_u162429920`(id 232)。**图没拍错，库里真的长这样。** 答辩用这批图之前必须先出一份**干净演示数据集**（已排进手册 §15 下一步），否则「社区首屏只有测试标题」会直接被评委看见。
- **本目录的 `pub_public` 是随复跑漂移的数**：`208`（v1.2.4 压测与截图那轮）→ `218` → 本轮 **`223`**，漂移原因就是上一条里的夹具写入。**任何把这个数写死的句子，复跑一次就变成假话** —— 所以本轮起 03 那格改成「与当轮 `shot-manifest.json` 的 `samples.postTotal` 逐字相等」。

---

## 2. §6.4 第 3 条：500 条帖量级下 `/api/posts` 的 P95

### 2.1 量级从哪来 —— 先造量级，再测，测完必须回滚

库里真实量级只有 **208** 条公开帖（`status='PUBLISHED' AND visibility='public' AND deleted=0`），拿它测出来的 P95 **不能**声称「500 条量级达标」。所以：

> ⚠ 表里的 208 是 **2026-09-23 18:26 那一枪**的基线现量，不是常量：探针每跑一次都会往库里发帖，本轮（v1.2.5）复查明库已是 **223**。它只影响「基线是多少」，不影响「508 条量级下 P95=33ms」这条结论 —— 夹具是回滚之后才复测过一次的，`fixture_left=0` 本轮再次实测为 0。

| 步骤 | `post` 全表行数 | 公开可列表 | 夹具行 | 命令 |
|---|---|---|---|---|
| 灌入前 | 350 | **208** | 0 | `SELECT COUNT(*) FROM post WHERE status='PUBLISHED' AND visibility='public' AND deleted=0;` |
| 灌入后 | 650 | **508** | **300** | 同上 + `... WHERE title LIKE '性能量级夹具%'` |
| 接口口径 | — | **`total=508`** | — | `GET /api/posts?page=0&size=1` → `data.total`（8080 直连与 5173 代理**都是 508**，代理不改变口径） |
| 测完回滚 | **350** | **208** | **0** | `DELETE FROM post WHERE title LIKE '性能量级夹具%';` |

夹具脚本 `seed-500-posts.sql`（42 行）的四条设计约束，都是为了让这 300 行**只加量级、不改语义**：
1. 只复制「实名、无马甲、已过审、公开、未删」的帖 —— 匿名帖的 `alias_id` 是一对一关系，复制它会造出「同一个马甲号发了一堆帖」这种库里不该出现的形状；
2. **计数器全 0** —— 不破坏 §6.4 第 2 条那三条 A/B/C 全表不变式（`like_cnt`/`comment_cnt` 与真实互动行数必须相等）；
3. `published_at` 用 `DATE_SUB(NOW(3), INTERVAL (n.k*1440+p.id) MINUTE)` 往前错开（实测落在 09-20 ~ 09-22），**保证它们排在既有内容之后，不会把广场首屏挤满夹具**；
4. 标题 `LEFT(CONCAT('性能量级夹具·', …), 100)` —— `title` 是 `VARCHAR(100)`，不截断会在严格模式下让整条语句失败；实测最长 63 字符。

> **夹具绝不留库过夜**：留在库里，本目录 03 那张「广场共 N 条可见内容」就成了假话，论文截图也会拍到夹具标题。**上表最后一行就是回滚后的实测值，1.2 节的全部截图都是在回滚之后的干净库上重拍的。**（本轮再补一句：`PERF500夹具` 清干净了 ≠ 库干净 —— 探针留下的 `探针话题*` 夹具**不在**这条回滚谓词里，见 §1.4 倒数第二条。）

### 2.2 结果（`perf-posts.log` / `perf-posts.json`，2026-09-23 18:26:45，N=串行单并发）

| 场景 | n | P50 | **P95** | P99 | max | avg | 接口 total | 条/页 | 响应体 |
|---|---|---|---|---|---|---|---|---|---|
| **S1 广场首屏 `page=0&size=20`（主口径）** | 60 | 11ms | **33ms** | 55ms | 55ms | 14ms | **508** | 20 | ≈7.1 KB |
| S2 广场深翻 `page=10&size=20` | 40 | 11ms | **12ms** | 14ms | 14ms | 11ms | 508 | 20 | ≈7.8 KB |
| S3 游标翻页 `size=50&beforeId=340` | 40 | 20ms | **57ms** | 63ms | 63ms | 27ms | −1（设计值） | 50 | ≈17.9 KB |
| S4 话题流 `/api/topics/1/posts` | 40 | 12ms | **57ms** | 59ms | 59ms | 21ms | 43 | 20 | ≈7.4 KB |
| S5 对照：同一枪走 Vite 5173 代理 | 40 | 21ms | **44ms** | 58ms | 58ms | 24ms | 508 | 20 | ≈7.1 KB |

**判定：主口径 P95 = 33ms，四个业务场景（S1~S4）最坏 P95 = 57ms，阈值 500ms → PASS，`exit 0`。** 余量约 **8.8 倍**（最坏值）。

### 2.3 🔴 这一条差点被限流器骗过去 —— 本目录最重要的一条方法论

`perf-posts.mjs` 的第一版裸跑 100 枪串行，打印出来是「**P95 = 2ms**」，漂亮得离谱。真相在响应体里：

```
{"code":10010,"msg":"操作过于频繁，请稍后再试"}   HTTP 429   ——  70 枪里 69 枪是这个
```

T2.8 的 `RateLimitInterceptor` 挂在 `/api/**` 上、**跑在业务逻辑之前**，固定窗口 `System.currentTimeMillis()/60000`，按登录身份计 **60 次/分**（`MindisleProperties.RateLimit.userPerMinute`）。所以那 2ms 是**被挡回来的耗时，服务端根本没执行过那条 SQL**。
**推论：任何对着本项目的 API 裸压的脚本，测到的都可能是限流器的性能而不是接口的性能。** 修法不是绕过限流（绕过之后测的就不是生产上那套代码了），而是三条：
1. **按窗口预算发枪**：每窗口最多花 40 枪（给浏览器与通知轮询留 20 枪余量），用完睡到下一个自然分钟；
2. **只有 HTTP 200 进延迟样本**；收到 429 立刻**作废当前窗口**、睡过去、这一枪补测，样本数说话算数；
3. **把 `X-RateLimit-Remaining` 记下来当证据** —— 它是服务端自己打印的，独立证明「这一枪确实进了业务层、当时还剩多少额度」。末次运行 `被限流=0`（五枪场景全部在预算内），`remainSeen` 见 `perf-posts.json`。

### 2.4 判据为什么不含 S3/S4 的 `total`（这个坑也踩过一次）

第一版的量级断言写成「所有场景 `total ≥ 500`」，于是一次**真实达标**的测量被判成 FAIL，两处原因都是断言写错而不是接口慢：
- **S3 游标模式 `total = -1`** 是 `PageResult.ofCursor` 的设计（游标不查总数，`hasMore`/`nextCursor` 才是它的分页语义）；
- **S4 话题流的 `total = 43`** 是「这个话题里有几条帖」，跟「广场有没有 500 条量级」是两件事。
现在断言只加在**广场 + 页码模式**那几枪上（`scene.feed && total >= 0`）。**口径写白：串行单并发测的是「500 条量级下这个接口的单次延迟」，不是并发吞吐；正式压测（50 并发）按手册排在阶段 8。**

### 2.5 另外三条必须写白的口径

- **列表接口没有结果缓存**：全仓 `grep @Cacheable` 无命中，只有浏览量是 5s 写延迟缓存（T3.5）。所以每一枪都是真 SQL，不存在「第二枪起命中缓存所以变快」。URL 尾部的 `&i=<n>` 只是防 HTTP 层复用，服务端不读它。
- **主口径打 8080 直连，不打 5173**：Vite dev server 的单线程代理会把 XHR 串起来排队，尾延迟说不清是谁的账。S5 专门再打一遍代理做对照 —— 末次 S5 P50=21ms vs S1 P50=11ms，**这台机器上代理自身约 +10ms 量级**，读者可自行换算。
- **v1.2.4 与 v1.2.5 两轮都没有改一行后端代码**，所以单测/冒烟基线沿用：`mvn -o -B test` **368 例 / 0 失败 / 1 跳过**、`docs/smoke.mjs` **246 项 / 231 断言 / 0 失败（21 步）**。前端 v1.2.4 改 5 个文件、v1.2.5 改 4 个（`CommentSection.vue` `PostDetailView.vue` `probe/shootgate.mjs` `probe/domprobe.mjs`），改后回归：`npm run build` **✓ 1784 modules / 0.99s / exit 0**、`domprobe.mjs` **121 项 / 0 失败（13 组）**、`shootgate.mjs` **21/21 PASS**。

---

## 3. jsdom 与真浏览器各证了什么、谁不能替谁背书

| 取证线 | 它能证明 | 它**不能**证明 | 本轮状态 |
|---|---|---|---|
| `mvn test`（368 例） | 纯函数、状态机、SQL 谓词 | 任何 HTTP 与界面 | 沿用（本轮未改后端） |
| `docs/smoke.mjs`（246 项） | HTTP 契约、状态码、业务口径 | 页面画不画得出来 | 沿用 |
| `npm run build` | 能编译、模块图完整 | **组件运行时抛不抛**（上一轮 `fmtHot` 白屏时它是 exit 0） | ✓ 1784 modules / 0.99s |
| `domprobe.mjs`（jsdom，121 项） | DOM 结构、事件绑定、**点了之后数据对不对** | **样式、布局、CSS 级联、真实字体、真实滚动** —— jsdom 不做布局 | 0 失败 |
| **`shootgate.mjs`（真 Chrome，21 张）** | 上面那五件事，以及控制台/网络红字 | 并发性能、跨浏览器、算法正确性 | 21/21 PASS |

**本轮的实证结论**：`domprobe 120 项全绿` 与 `npm run build exit 0` 在同一天保持全绿时，真浏览器量出的是 **86 处漏白 + 6 条红字 + 1 处单选被居中**。
**v1.2.5 又补了一次的下半句**：那 120 项升到 121 项、真浏览器也 21/21 全绿之后，**第 4 个 bug 仍然是人眼看图看出来的**（§1.3.1）—— 「绿」只证明断言没覆盖到，不证明没问题。
所以 §6.4 第 4 条的勾**只能由本目录的截图给**，jsdom 不能替它背书；反过来，本目录也**不背书**任何数据口径 —— 那 208→508（以及复跑后的 223）、A/B/C 不变式、`total` 与库内计数相等，全部由冒烟与 root SQL 负责。

---

## 4. 复跑清单（顺序不能颠倒：先测性能、再回滚、最后重拍图）

```powershell
# 0) 后端 8080 / 前端 5173 / MySQL 3306 必须在跑（本轮全部由用户手动启动）
# 0.5) 顺序里还有一条隐性依赖：domprobe 会写库（新增一次性账号与夹具帖），所以它必须排在 shootgate 之前跑，
#      否则本目录写的 postTotal 与图上的数字对不上。本轮实测 208 →（跑过 domprobe）→ 223。
# 1) 量级夹具（口令走仓库外的 rootpwd.cnf，见全局复利日志第 7/11 轮）
& 'E:\codex workspace\_cache\mindisle-dbtmp\run-sql.cmd' `
  'E:\codex workspace\009_心屿AI心理陪伴社区\docs\gate\阶段3\seed-500-posts.sql' `
  'E:\codex workspace\_cache\mindisle-dbtmp\seed500.out.txt'
# 2) 压测（约 6 分钟，因为它必须尊重 60 次/分的产品限流）
Set-Location 'E:\codex workspace\009_心屿AI心理陪伴社区'
node 'docs\gate\阶段3\perf-posts.mjs' *>&1 | Tee-Object -FilePath 'docs\gate\阶段3\perf-posts.log'
# 3) 回滚夹具（不回滚 = 污染截图 = 证据作废）
& 'E:\codex workspace\_cache\mindisle-dbtmp\run-sql.cmd' `
  'E:\codex workspace\009_心屿AI心理陪伴社区\docs\gate\阶段3\cleanup-500-posts.sql' `
  'E:\codex workspace\_cache\mindisle-dbtmp\cleanup500.out.txt'
# 4) 真浏览器取证（干净库上拍）
Set-Location 'E:\codex workspace\009_心屿AI心理陪伴社区\frontend'
node probe\shootgate.mjs *>&1 | Tee-Object -FilePath 'E:\codex workspace\_cache\mindisle-dbtmp\shootgate.log'
```

**本目录文件清单**：21 张 `.png` + `console-evidence.log` + `shot-manifest.json` + `perf-posts.mjs` + `perf-posts.log` + `perf-posts.json` + `seed-500-posts.sql` + `cleanup-500-posts.sql`。