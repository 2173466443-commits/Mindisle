# 取证夹具的时间炸弹（T3.15 树洞到期销毁 · 2026-09-28 真炸）

> 一句话：**产品没坏，是「证据」自己过期了。** domprobe 当场红 10 条，根因是 2026-09-21 造的
> 树洞夹具按墙上时钟到期，而 T3.15 的销毁是**读侧按 auto_destroy_at 过滤**——每跑一次取证线，
> 夹具就离死亡近一天。这一份把「怎么发现的、怎么修的、修完怎么证明它真的会红」全记下来。

## 0. 现象：四条线里两条同时变红，红在同一批帖子上

2026-09-28 跑 domprobe（jsdom DOM 探针）时，121 项判据里 10 条 FAIL，全部指向同几处：

- 组2 / 组3：U12「我发过 4 条」只数出 3 张卡片——那条匿名树洞不见了；
- 组9：详情页评论区「19 棵一级楼」数成 0 行；
- 组10：楼中楼（1 棵一级楼 + 5 条回复）整段空态。

**第一反应是去找产品代码**。但 root 直连一查就否掉了这个方向：

~~~sql
SELECT COUNT(*) FROM post WHERE type='hole' AND deleted=0;                    -- 142（行还在）
SELECT id FROM post WHERE type='hole' AND deleted=0
  AND auto_destroy_at IS NOT NULL AND auto_destroy_at <= NOW(3);              -- 42, 141
~~~

deleted 仍是 0，**没有物理删除**：树洞到期销毁走的是查询期过滤（PostQueryService 的
applyNotExpired），而不是 DELETE。post 42 的 auto_destroy_at 落在 2026-09-28，
今天正好到点——**T3.15 这个功能第一次在真链路上把夹具销毁掉了，这是它工作正常的证据**。

## 1. 为什么正解不是 DELETE，也不是「把断言改成数 3 张卡片」

| 方案 | 为什么否掉 |
|---|---|
| 手工 DELETE 掉测试账号，再重造数据 | 取证线本身就依赖「库里要有足够的帖与评论」，清完就得再造，造完又是一次性数据——这正是《全局复利与踩坑日志》第 14 轮记的「清一次性数据拖了五轮」的死循环 |
| 把断言从「4 张卡片」改成「3 张」 | 断言跟着腐烂的夹具走，等于把取证线改成「按今天的日期写死」，明天再炸一次，而且这一次没人会去查根因 |
| 关掉到期销毁 | 为了绿的测试去改产品语义，最坏的一种 |
| **可重入的 seed 脚本** | 夹具过期 → 把过期树洞的存活线整体顺延；库里没有过期行时空跑；跑一百次结果一样 ✅ |

于是新增 **docs/seed-demo.mjs**（188 行）：只做一件事——把**已过期的树洞**的 auto_destroy_at
往后推 N 天（默认 7），并且**只 UPDATE 这一列**，不 DELETE、不 INSERT（物理清除归
DataRetentionJob，那是另一条纪律）。

三条写进代码里的红线：

1. 口令不进文件：只走 mysql --defaults-file 指向的仓库外 cnf（默认 E:/codex workspace/_cache/mindisle-dbtmp/rootpwd.cnf）；
2. 只动 auto_destroy_at 一列；
3. **环境不齐时显式 SKIP 并用退出码 2**，绝不静默当成「刷新成功」。

被取证线写死引用的三条帖钉在文件里（PINNED = 42 / 140 / 141），每条都注明「谁在引用它」：
42 → U12 我发过 4 条里那条匿名树洞；140 → 楼中楼；141 → 评论区 19 棵一级楼 + 马甲名。

## 2. 接线：domprobe 起跑之前先刷夹具（且不许静默）

frontend/probe/domprobe.mjs 里新增一段「1.5 刷新演示夹具」：spawn 一次 seed（--quiet），
把结论行写进取证日志的 notes。**四个分支全部落字**——
OK(exit 0) / SKIP(exit 2) / FAIL(exit N) / ERROR，一个都不许吞掉。
理由：取证线上最贵的一种失败是「脚本自己没跑起来但仍然是绿的」。

## 3. 🔴 这一轮修的两个脚本缺陷（都不是产品缺陷，但都会让人误判）

- **--quiet 名不副实**：首稿把 --quiet 做成了「什么都不说」。改成**成功只打 1 行结论，
  失败仍然打全量明细**（实测：quiet_lines=1；失败场景 quietFailLineCount=12）。
- **dry-run 的成功文案在说谎**：首稿 dry-run 也打印「已顺延」。改成条件措辞，只声明它真验到的东西：

~~~
OK dry-run：未写库；读到过期树洞 1 条（真跑会把它们顺延 7 天）；
本趟只验三行夹具是否还在库里：未过期一侧当前有 140（1/3）；未删树洞总数=142
~~~

## 4. 证伪台账（新写的判据必须先红过一次）

| 编号 | 怎么把判据打红 | 期望 | 实测 |
|---|---|---|---|
| R-a | 把 PINNED 改成 999999（dry-run） | EXIT=1 | FAIL「取证夹具在库里没有行了（编号=999999）」✅ |
| R-a2 | 同上 + --quiet | EXIT=1 且仍打明细 | EXIT=1，12 行明细 ✅（证「quiet 不吞失败」） |
| R-c | 真跑时给 UPDATE 的 WHERE 加一句 id<>141，让复检与 UPDATE 判据不一致 | EXIT=1 | FAIL「推完还剩 1 条过期树洞：UPDATE 的 WHERE 与复检的 WHERE 不是同一份判据 ; 三条夹具没全回到未过期一侧（只有 42,140，共 2/3）」✅ |
| R-d | 把 domprobe 的 MINDISLE_ROOT_CNF 指到不存在的 cnf（seed EXIT=2） | 接线本身要能报 SKIP | domprobe 仍 EXIT=0 / 121 项 0 失败，但日志里出现「# seed: SKIP(exit 2) …环境不齐…不是产品问题」✅（**脚本挂了不等于取证白跑了**，这一条要看得见） |

## 5. 真跑读数（写文档只用这些）

- RUN1：过期 1 条 → ROW_COUNT()=1 → 剩余过期 0 条；三条夹具全在未过期一侧；未删树洞总数=142；EXIT=0
- RUN2（立刻重跑）：过期 0 条 → ROW_COUNT()=0，仍 OK ⇒ **幂等成立**
- 顺延之后：post 42 / 141 的 auto_destroy_at = 2026-10-05 19:05:48.812 / 19:05:56.196；140 是 type=normal、auto_destroy_at=NULL（本来就不受树洞销毁影响）
- HTTP 复核（走 5173 代理带 token）：GET /api/posts/141 → 200（title=冒烟·匿名树洞）；42 → 200；140 → 200（autoDestroyAt=null）；GET /api/users/me/posts?size=50 → listLen=4（ids=42,41,39,40，其中 hole=1）
- 复跑 domprobe：**121 项 / 失败 0**，v70 那 10 条红全部转绿，并打印出真读数：
  「U12 真渲染出 4 张卡片 ← titles=冒烟·匿名树洞|冒烟·被看见|冒烟·仅自己可见|冒烟·转人工」、
  「U4 详情页评论区真渲染出 19 棵一级楼 ← threads=19 rows=19」
- 再跑 shootgate（顺序不能反，domprobe 会写库）：**21 张 / 通过 21 / 漏白 0 / 红字 0 / 断言失败 0 / EXIT=0**

## 6. 本轮顺手推翻的一条旧口径

上一轮的交接纪要里写着「GET /api/users/me/posts 的 data 没有 total」。**现查推翻**：

~~~
data 键序 = ["hasMore","list","nextCursor","page","size","total"]
读数      = total=4 / nextCursor=40 / page=1 / size=50
~~~

「无 total」是**广场游标翻页流**的口径，不是 mine 的。这是「摘要句抄脑子里的表而不是现查」这件事第九次咬人，
已经记进《全局复利与踩坑日志》第 15 轮。
