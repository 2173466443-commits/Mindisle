#!/usr/bin/env node
/* ------------------------------------------------------------------ *
 * 心屿 MindIsle · 可重入演示数据集刷新（取证夹具的「时间炸弹」）        *
 *                                                                   *
 * 为什么要有这个文件：T3.15 的树洞到期销毁是**按墙上时钟生效**的——       *
 * 发帖时把 auto_destroy_at 算成绝对时间落库，读侧（PostQueryService     *
 * 的 applyNotExpired）一旦发现它不晚于 NOW() 就当这条不存在。而取证线   *
 * 里有几处断言写死了具体帖子编号（见下面 PINNED），于是「每跑一次就往    *
 * 前走一天」的夹具总有一天自己过期。2026-09-28 这一天真的到了：         *
 * post 42（组2 U12 的第四条）与 post 141（组9 的 19 楼评论区）双双      *
 * 到期，domprobe 当场红 10 条——而产品逻辑一行都没错。                  *
 *                                                                   *
 * 这个脚本只干一件事：把**已过期的树洞**的存活线整体往后推 N 天，        *
 * 并把推之前 / 推之后的读数打印出来。它是**幂等可重入**的：库里没有      *
 * 过期树洞时它就是空跑一趟（ROW_COUNT()=0），跑一百次结果一样。         *
 *                                                                   *
 * 三条红线（写在代码里，不靠自觉）：                                  *
 *  1) 口令不进本文件：只走 mysql --defaults-file 指向的 cnf，默认落在   *
 *     仓库外（E:\codex workspace\_cache\mindisle-dbtmp\rootpwd.cnf）；  *
 *  2) 只 UPDATE auto_destroy_at 这一列，不 DELETE、不 INSERT；          *
 *     树洞到期销毁是产品功能，物理清除归 DataRetentionJob，不在这里做； *
 *  3) 环境不齐时**显式 SKIP 并用退出码 2**，绝不静默当成「刷新成功」。  *
 *                                                                   *
 * 用法：node docs/seed-demo.mjs [--dry-run] [--quiet]                *
 *   环境变量：MINDISLE_MYSQL / MINDISLE_ROOT_CNF / MINDISLE_DB /       *
 *            MINDISLE_SEED_HOLE_DAYS（默认 7，只接受 1..90 的整数）    *
 * 退出码：0=刷新完成且自检通过 · 1=自检或 SQL 失败 · 2=SKIP（环境不齐） *
 * ------------------------------------------------------------------ */
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';

const MYSQL = process.env.MINDISLE_MYSQL || 'C:/Program Files/MySQL/MySQL Server 9.7/bin/mysql.exe';
const CNF = process.env.MINDISLE_ROOT_CNF || 'E:/codex workspace/_cache/mindisle-dbtmp/rootpwd.cnf';
const DB = process.env.MINDISLE_DB || 'mindisle';
const DAYS = Number(process.env.MINDISLE_SEED_HOLE_DAYS || '7');
const DRY_RUN = process.argv.includes('--dry-run');
const QUIET = process.argv.includes('--quiet'); // 成功只打一行结论；失败仍然打印全量明细

// 取证线里写死引用的三条帖：domprobe 的判据改了，这里要跟着改。
const PINNED = [42, 140, 141];
const PINNED_WHY = {
  42: 'domprobe 组2/组3：U12「我发过 4 条」里那条匿名树洞',
  140: 'domprobe 组10：楼中楼（1 棵一级楼 + 5 条回复）',
  141: 'domprobe 组9：评论区 19 棵一级楼 + 马甲名'
};

/** 只读判据：过期树洞 = 类型是 hole、没被删、auto_destroy_at 非空且不晚于 NOW(3)。 */
const EXPIRED_WHERE = "type='hole' AND deleted=0 AND auto_destroy_at IS NOT NULL AND auto_destroy_at <= NOW(3)";

let stderrSeen = '';

/** 跑一条语句，返回行数组（每行是按 \t 切开的字符串）。一次 spawnSync = 一个连接。 */
function queryOne(label, statement) {
  const r = spawnSync(MYSQL, [
    '--defaults-file=' + CNF, '-B', '-N', '-D', DB, '--default-character-set=utf8mb4'
  ], { input: statement + ';\n', encoding: 'utf8' });
  if (r.error) throw new Error(label + ' 起 mysql 进程就失败：' + r.error.message);
  const errText = String(r.stderr || '').replace(/\r/g, '').trim();
  if (errText) stderrSeen += '[' + label + '] ' + errText + ' ';
  if (r.status !== 0) throw new Error(label + ' mysql 退出码 ' + r.status + '：' + errText.slice(0, 400));
  const out = String(r.stdout || '').replace(/\r/g, '');
  const rows = [];
  for (const line of out.split('\n')) {
    if (line === '') continue;
    rows.push(line.split('\t'));
  }
  return rows;
}

/** 单值查询：拿第一行第一列；没有行就返回 null。 */
function scalar(label, statement) {
  const rows = queryOne(label, statement);
  return rows.length ? rows[0][0] : null;
}
/** UPDATE 与 ROW_COUNT() 必须同连接，所以这两条一起发（-N 下只有第二个结果集有行）。 */
function execRefresh(days) {
  const stmt = 'UPDATE post SET auto_destroy_at = DATE_ADD(NOW(3), INTERVAL ' + days + ' DAY) WHERE ' + EXPIRED_WHERE + ';\n'
    + 'SELECT ROW_COUNT();\n';
  const r = spawnSync(MYSQL, [
    '--defaults-file=' + CNF, '-B', '-N', '-D', DB, '--default-character-set=utf8mb4'
  ], { input: stmt, encoding: 'utf8' });
  if (r.error) throw new Error('update 起 mysql 进程就失败：' + r.error.message);
  const errText = String(r.stderr || '').replace(/\r/g, '').trim();
  if (errText) stderrSeen += '[update] ' + errText + ' ';
  if (r.status !== 0) throw new Error('update mysql 退出码 ' + r.status + '：' + errText.slice(0, 400));
  const rows = String(r.stdout || '').replace(/\r/g, '').split('\n').filter(function (x) { return x !== ''; });
  if (rows.length !== 1) throw new Error('update 后 ROW_COUNT() 读数不唯一：' + JSON.stringify(rows));
  return Number(rows[0]);
}

function aliveIds() {
  const rows = queryOne('alive', 'SELECT id FROM post WHERE id IN (' + PINNED.join(',') + ')'
    + ' AND deleted=0 AND (auto_destroy_at IS NULL OR auto_destroy_at > NOW(3)) ORDER BY id');
  return rows.map(function (r) { return Number(r[0]); });
}

function pinnedReadout() {
  const rows = queryOne('pinned', 'SELECT id, type, status, deleted, IFNULL(auto_destroy_at,'
    + "'-') FROM post WHERE id IN (" + PINNED.join(',') + ') ORDER BY id');
  const map = {};
  for (const r of rows) map[Number(r[0])] = { type: r[1], status: r[2], deleted: r[3], at: r[4] };
  return map;
}

// ---------- 0 环境体检：不齐就显式 SKIP（退出码 2），不许静默 ----------
if (!fs.existsSync(MYSQL)) {
  console.log('SKIP 找不到 mysql 客户端：' + MYSQL + '（设 MINDISLE_MYSQL 指给它）—— 夹具没被刷新，'
    + '依赖树洞夹具的取证断言会照常变红，这是环境问题不是产品问题。');
  process.exitCode = 2;
} else if (!fs.existsSync(CNF)) {
  console.log('SKIP 找不到 root 凭据文件：' + CNF + '（口令按红线只留在仓库外，设 MINDISLE_ROOT_CNF 指给它）');
  process.exitCode = 2;
} else if (!Number.isInteger(DAYS) || DAYS < 1 || DAYS > 90) {
  console.log('FAIL MINDISLE_SEED_HOLE_DAYS 必须是 1..90 的整数，收到的是：' + process.env.MINDISLE_SEED_HOLE_DAYS);
  process.exitCode = 1;
} else {
  main();
}

function main() {
  const log = [];
  function say(s) { log.push(s); if (!QUIET) console.log(s); }

  const expiredBefore = Number(scalar('expired-before', 'SELECT COUNT(*) FROM post WHERE ' + EXPIRED_WHERE));
  const holeTotal = Number(scalar('hole-total', "SELECT COUNT(*) FROM post WHERE type='hole' AND deleted=0"));
  const before = pinnedReadout();
  say('推之前：未删树洞 ' + holeTotal + ' 条，其中已过期 ' + expiredBefore + ' 条（占 '
    + (holeTotal ? (100 * expiredBefore / holeTotal).toFixed(2) : '0.00') + '%）');
  for (const id of PINNED) {
    const row = before[id];
    say('  夹具 ' + id + '：' + (row ? row.type + '/' + row.status + '/deleted=' + row.deleted + '/auto_destroy_at=' + row.at
      : '库里没有这一行') + ' ← ' + PINNED_WHY[id]);
  }

  let changed = 0;
  if (DRY_RUN) {
    say('dry-run：本次不写库（本来会把这 ' + expiredBefore + ' 条往后顺延 ' + DAYS + ' 天）');
  } else {
    changed = execRefresh(DAYS);
    say('写库：UPDATE auto_destroy_at = NOW(3) + INTERVAL ' + DAYS + ' DAY，ROW_COUNT()=' + changed);
  }

  const expiredAfter = Number(scalar('expired-after', 'SELECT COUNT(*) FROM post WHERE ' + EXPIRED_WHERE));
  const alive = aliveIds();
  const after = pinnedReadout();
  say('推之后：仍过期 ' + expiredAfter + ' 条；三条取证夹具在未过期一侧：' + (alive.length === PINNED.length ? '全在' : alive.join(',')) 
    + '（' + alive.length + '/' + PINNED.length + '）');
  for (const id of PINNED) {
    const row = after[id];
    say('  夹具 ' + id + '：' + (row ? row.at : '库里没有这一行'));
  }

  const fail = [];
  if (!Number.isFinite(expiredBefore) || !Number.isFinite(expiredAfter)) fail.push('过期读数不是数字');
  if (!DRY_RUN && expiredAfter !== 0) {
    fail.push('推完还剩 ' + expiredAfter + ' 条过期树洞：UPDATE 的 WHERE 与复检的 WHERE 不是同一份判据');
  }
  // dry-run 不许写库，那它就没资格宣称「夹具已活」——它只能验「这三条行还在不在库里」。
  // 而真跑的那一趟必须三条都落到未过期一侧，否则下一轮 domprobe 照样红，等于这个 seed 白跑。
  const missing = PINNED.filter(function (x) { return !after[x]; });
  if (missing.length) {
    fail.push('取证夹具在库里没有行了（编号=' + missing.join(',') + '）：要么被物理清除了，要么 PINNED 该换编号了');
  } else if (!DRY_RUN && alive.length !== PINNED.length) {
    fail.push('三条夹具没全回到未过期一侧（只有 ' + alive.join(',') + '，共 ' + alive.length + '/' + PINNED.length + '）');
  }
  if (!DRY_RUN && expiredBefore > 0 && changed === 0) fail.push('推之前读到 ' + expiredBefore + ' 条过期，UPDATE 却一行没动（ROW_COUNT()=0）');

  if (stderrSeen) say('# mysql stderr（不当失败，但留痕）：' + stderrSeen.trim());
  if (fail.length) {
    // 失败时不管 quiet 不 quiet 都要把全量明细吐干净：--quiet 只该在**成功**时少说话。
    if (QUIET) for (const s of log) console.log(s);
    console.log('FAIL 夹具刷新自检没过：' + fail.join(' ; '));
    process.exitCode = 1;
  } else {
    const aliveTxt = alive.length === PINNED.length
      ? '三条取证夹具全在未过期一侧'
      : '未过期一侧只捞回 ' + (alive.join(',') || '无') + '（' + alive.length + '/' + PINNED.length + '）';
    // dry-run 一行库都没写，就没资格宣称「夹具已回到未过期一侧」——它只验到「这三行还都在库里」。
    const one = DRY_RUN
      ? 'OK dry-run：未写库；读到过期树洞 ' + expiredBefore + ' 条（真跑会把它们顺延 ' + DAYS + ' 天）；'
        + '本趟只验三行夹具是否还在库里：未过期一侧当前有 ' + (alive.join(',') || '无') + '（'
        + alive.length + '/' + PINNED.length + '）；未删树洞总数=' + holeTotal
      : 'OK 演示夹具就位：过期树洞 ' + expiredBefore + ' 条 → ROW_COUNT()=' + changed
        + ' → 剩余过期 ' + expiredAfter + ' 条；' + aliveTxt + '；未删树洞总数=' + holeTotal;
    console.log(one);
    process.exitCode = 0;
  }
}