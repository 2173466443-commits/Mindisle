# T4.20 ③ 情绪周报「去标识分享」闭环（阶段 4 第四条取证线）

任务：手册 T4.20 的第 ③ 段 —— 周报生成之后**能不能安全地分享出去**。
这一步是阶段 4 里唯一一处「把情绪数据写到公开广场」的动作，所以它的判据全部围绕 NFR8 / BR13：**公开之后别人能多推出什么**。

## 0. 一句话结论

「POST /api/emotions/weekly-report/{id}/share」把一条周报变成**一条匿名普通帖**，
正文只有三样（周区间 + 周报结论原句 + 生成方式）再加两句口径声明，
**insight JSON、五个数值列、dominant_label、text_snippet、作者昵称、authorId 一个都不带**；
重复点分享走 **Java 侧回放 shared_post_id**（同一个 postId，不是再发一条）；
越权（乙拿甲的周报 id）在**服务层**就被 403 拦掉。单测 17 项 + HTTP 冒烟 19 条判据 + 库内 SQL 复核，全部绿。

## 1. 实现落点（本轮逐个文件读过，行号现查）

| 位置 | 干什么 |
|---|---|
| backend/src/main/java/com/mindisle/emotion/WeeklyReportShareService.java（198 行 / 11,005 B） | 分享这件事本身：读周报 → 组正文 → 调发帖端口 → 回写标记 → 出 ShareView |
| 同目录 WeeklyReportShareStore.java | 「ReportStore」端口的生产实现（只读 deleted=0 的周报行，L61–67） |
| backend/src/main/java/com/mindisle/web/EmotionController.java L119 | 「POST /api/emotions/weekly-report/{id}/share」 |
| backend/src/main/java/com/mindisle/emotion/EmotionProfileService.java L55 | 「MIN_TRUSTED_DAYS = 3」（BR12 的「数据还在积累」阈值，周报与档案共用同一个数） |
| frontend/src/views/EmotionView.vue | 「分享（去标识）」按钮 + 「已分享 · 去看那条帖」文案（读后端 shared / sharedPostId 两个字段，不是本地状态） |

三条设计决定都写在类注释里，不是事后补的解释：

1. **正文口径写在「shareContent」一处 + 发帖端口侧强制匿名**（L15–17）：去标识不靠前端不显示，靠后端根本不生成。
2. **走「Publisher」端口而不是直接注入「PostService」**（L32–38）：PostService 带着 8 个协作者（配额、字段合规、审核链……），
   分享这件事只需要「发一条帖」这个动作；复制它的构造器就等于复制它的口径分裂风险。生产实现仍然是「PostService#publish」，所以审核链一步没跳。
3. **幂等放在 Java 侧而不是 SQL 里**（L40–45）：「shared_flag=1 且 shared_post_id 非空」就直接回放，不重新发帖；
   而标记的写库**只在 publish 成功返回之后**（L72–76）——反过来先写标记再发帖，发帖失败就会留下一个「显示已分享、其实广场上没有」的谎。

两个常量（现查）：「TITLE_PREFIX = "情绪周报 · "」(L51)、「DISCLAIMER」(L54–56) 含「心屿不做诊断」「分享已去标识」两句。

## 2. 冒烟里的 19 条判据与读数（docs/smoke.mjs 第 24 步，2026-09-28 复跑 312 项 / 294 断言 / 0 失败）

| 判据 | 读数 |
|---|---|
| 读上一自然周的周报（当场生成并 upsert 回读） | 「{id:12, weekStart:2026-09-21, weekEnd:2026-09-27, checkinDays:2, accumulating:true, generator:template}」（本步不烧 token） |
| 分享位在**读取时**就是未分享 | shared=false / sharedPostId 缺席 |
| 未登录打分享端点 | 401 / 10002（这条会真发一条公开帖，登录是最低门槛；请求体里没有 user_id，作者身份只来自 JWT） |
| 🔴 乙拿甲的周报 id 打分享 | 403 / 10003，且甲的周报**没被标记** |
| 分享不存在的周报 id | 404 / 90006（不是 30001：这个 id 指的是「周报」这个资源，不是帖子） |
| 甲首次分享 | 200「{reportId:12, postId:1231, alreadyShared:false, postStatus:PUBLISHED, displayName:匿名屿民·子衿}」 |
| 走完整审核链并原样回显状态 | postStatus=PUBLISHED（机审没放行时谎称「已发布」比失败更糟，所以回的是发帖终态而不是恒 0） |
| 分享帖是匿名马甲不是昵称 | 展示名以「匿名屿民」开头（FR1.4 口径） |
| 🔴 连点两次分享 | 200 + postId 逐字相同（1231 = 1231）+ alreadyShared=true |
| 回放那一路不重新读帖子 | postStatus/displayName/tip 为 null ⇒ 序列化后三个键整个缺席；帖子权威状态只有帖子详情一个出处 |
| 分享之后再读周报 | shared=true / sharedPostId=1231 / createdAt 原样 / id 不变（没有重新生成） |
| 第三方读这条分享帖 | 标题逐字 =「情绪周报 · 2026-09-21 ~ 2026-09-27」——标题里没有人、没有数字、没有主导情绪 |
| 🔴 正文只放三样 | 前 120 字：「这一周（2026-09-21 ~ 2026-09-27）我在心屿记下了一份情绪周报，现在把它去标识之后分享出来：⏎⏎这一周的数据还在积累，先不急着看趋势。等记录满三天，这里的曲线才有意义。⏎⏎上面这段文字由本地模板按我这周的打卡统计生成。」（模板冒充模型结论是 FR3.5 明令禁止的） |
| 🔴 HTTP 侧去标识 | 整条响应 ①不含打卡原文哨兵「冒烟哨兵20260928104213」（该句**确实还落库在 emotion_record**，只是没被带出来）②不含作者昵称「冒烟分享甲」③authorId 键整个缺席（留在响应体里，前端不显示也照样能被抓包反查） |
| 帖的形态 | type=normal / visibility=public / autoDestroyAt 缺席 —— 周报是「我自己愿意留下的公开记录」，不是树洞；树洞那种到期物理删除的语义用在这里会让用户找不到自己分享过的东西 |
| 🔴 refresh=true 重算之后 | shared 仍然 true、sharedPostId 仍然是 1231：shared_flag 不在 upsert 的 ON DUPLICATE UPDATE 列表里（重算周报不许把「我已分享」洗掉，否则再点一次就发出第二条） |

## 3. 库内复核（root 直连 COUNT/SELECT，脚本自证不算；本轮 19:40 现查）

- 「weekly_report」= **7 行，max id = 12**；其中 shared_flag=1 的三行：id 4→post 1119、id 10→post 1194、id 12→post 1231（作者分别是 smoke_sha_* 378 / 426 / 455）。
- 「post」id=1231：user_id=455、**is_anonymous=1**、status=PUBLISHED、type=normal、title=「情绪周报 · 2026-09-21 ~ 2026-09-27」。
- 同标题的行**只有一条**（幂等不是靠应用层记性，是靠库里这行读数）。
- 甲的两句打卡原文**都还在**「emotion_record」里 —— 分享没有顺手删原始数据（撤回分享是另一件事，走 WITHDRAW 追加行）。
- 一次性账号「smoke_sha_ / smoke_shb_」各 5 个（378/379、394/395、410/411、426/427、455/456）进「待清」清单，见 README §5。

## 4. 单测：WeeklyReportShareServiceTest 17 项，以及它被证伪过的四个位置

日志在仓库外「_cache/009_mindisle/mvn_share_r*.log」（09-28 17:23–17:26）：

| 轮次 | 改坏的东西 | 结果（日志原文） |
|---|---|---|
| r1 首次跑 | —— | 「firstSharePublishesAndMarks:193 expected: <2> but was: <42>」⇒ **第一次跑就抓到一处口径写错**，改断言来源而不是改实现 |
| r2 | 还原后 | Tests run 17 / Failures 0 / BUILD SUCCESS |
| r3 证伪 A | 幂等回放的读判据 | 「readsExactlyOneRow:391 expected: <0> but was: <1>」+「secondShareIsReplayOnly:238 expected: <true> but was: <false>」⇒ 双发会真发出两条 |
| r4 证伪 B | 把聚合明细塞回正文 | 「contentOmitsEveryAggregateField:310 正文泄露了聚合明细字段：[checkin_days 哨兵=8888, record_cnt 哨兵=7777, dominant_label=sadness, insight: counts=counts, insight: trustedDays=trustedDays, insight: meanValence=meanValence, insight: negativeDominant=negativeDominant, insight: joy 键="joy"]」⇒ 这条判据是**用哨兵值逐字段钉**的，不是抽查 |
| r6 证伪 D | 摘掉归属校验 | 「nullOwnerIsForbiddenToo:178 Expected BizException to be thrown, but nothing was thrown」+「otherUsersReportIsForbidden:164 同上」 |
| r5 还原 C | —— | 17 项全绿 BUILD SUCCESS |

## 5. 已知边界（不掩饰）

- **只有「分享」这一条出路，没有「撤回分享后把帖下架」的入口**：分享帖生成后走的是普通帖的编辑/删除生命周期（阶段 3 的 T3.15 那条线），分享服务里没有反向操作。这条记在手册阶段 6/8 的复检里，不签成已完成。
- 「generator=llm」的周报（库里 id=1 那一行）与「template」两种生成方式**共用同一段正文口径**，区别只在「生成方式」那句话——LLM 那一路的正文同样只放三样。
- 「postStatus」只在首次分享那一路回真值，回放那一路为 null（§2 第 10 行）。前端如果想在「已分享」状态下直接显示审核状态，必须去读帖子详情，**不能拿这个 null 当「审核未通过」**。

## 6. 复跑

~~~
# 单测（17 项，不烧 token）
cd E:\codex workspace\009_心屿AI心理陪伴社区\backend
mvn -o -B test -Dtest=WeeklyReportShareServiceTest

# HTTP 冒烟（第 24 步，含 19 条分享判据 + 一次性账号 + SQL 取证行）
cd E:\codex workspace\009_心屿AI心理陪伴社区
node docs/smoke.mjs
~~~
