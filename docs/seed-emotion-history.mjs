#!/usr/bin/env node
/* ------------------------------------------------------------------ *
 * 心屿 MindIsle · 情绪档案夹具补历史（取证线的第二颗「时间炸弹」）      *
 *                                                                    *
 * 为什么要有这个文件：BR12 把「有可信记录的天数 < 3」定义成 accumulating， *
 * 界面据此把趋势图与分布图整段换成「数据积累中」空态（EmotionView.vue 的   *
 * v-show="!profile.accumulating" 与 EmotionProfileService 的           *
 * MIN_TRUSTED_DAYS=3）。阶段 4 取证线里那条「没有降级横幅、也没落在空态」 *
 * 的判据当初是绿的，因为 09-26/27/28 连着跑了三轮探针，每轮产生当天的     *
 * 情绪记录，刚好凑满 3 天。这等于把「取证能不能过」绑在了真实时钟的历史上：*
 * 隔十几天复跑，近 7 天窗口里只剩本轮刚产生的 1 天，档案页**正确地**退回  *
 * 空态，判据当场红。产品逻辑一行没错，错的是夹具会老。                    *
 *                                                                    *
 * 它与 docs/seed-demo.mjs 是同一件事的另一半：那边顺延已过期的树洞，      *
 * 这边给指定账号补最近 N 天的情绪记录，两个脚本都幂等可重入。             *
 *                                                                    *
 * 三条红线（写在代码里，不靠自觉）：                                  *
 *  1) 口令不进本文件：只走 mysql --defaults-file 指向的 cnf，默认落在   *
 *     仓库外（E:\codex workspace\_cache\mindisle-dbtmp\rootpwd.cnf）；  *
 *  2) 只碰带本脚本哨兵的行：先按哨兵 DELETE 自己上次插的，再 INSERT，   *
 *     真实打卡与被动识别产生的记录一条都不动，也不碰其它表；            *
 *  3) 环境不齐时**显式 SKIP 并用退出码 2**，绝不静默当成「刷新成功」。  *
 *                                                                    *
 * 用法：node docs/seed-emotion-history.mjs [--dry-run] [--quiet]      *
 *           [--user=demo01,demo03]                                    *
 *   环境变量：MINDISLE_MYSQL / MINDISLE_ROOT_CNF / MINDISLE_DB /       *
 *            MINDISLE_SEED_EMO_USERS（默认 demo01）                  *
 *            MINDISLE_SEED_EMO_DAYS（默认 7，只接受 3..31 的整数）     *
 * 退出码：0=补齐且自检通过 · 1=SQL 或自检失败 · 2=SKIP（环境不齐）      *
 * ------------------------------------------------------------------ */
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';

const MYSQL = process.env.MINDISLE_MYSQL || 'C:/Program Files/MySQL/MySQL Server 9.7/bin/mysql.exe';
const CNF = process.env.MINDISLE_ROOT_CNF || 'E:/codex workspace/_cache/mindisle-dbtmp/rootpwd.cnf';
const DB = process.env.MINDISLE_DB || 'mindisle';
const DAYS = Number(process.env.MINDISLE_SEED_EMO_DAYS || '7');
const FROM_ARG = (process.argv.find((a) => a.indexOf('--user=') === 0) || '').replace('--user=', '')
const USERS = FROM_ARG || process.env.MINDISLE_SEED_EMO_USERS || 'demo01';
const USER_LIST = USERS.split(',').map((s) => s.trim()).filter(Boolean);
const DRY_RUN = process.argv.indexOf('--dry-run') >= 0;
const QUIET = process.argv.indexOf('--quiet') >= 0;

// 哨兵：写在 text_snippet 开头，既是「这条是夹具」的标记，也是唯一的删除条件。
const SENTINEL = '[seed-emo-history]';

let stderrSeen = '';

/** 跑一条语句，返回行数组（每行按 \t 切开）。一次 spawnSync = 一个连接。 */
function queryOne (label, statement) {
  const r = spawnSync(MYSQL, [
    '--defaults-file=' + CNF, '-B', '-N', '-D', DB, '--default-character-set=utf8mb4'
  ], { input: statement + ';\n', encoding: 'utf8' });
  if (r.error) throw new Error(label + ' 起 mysql 进程就失败：' + r.error.message);
  const errText = String(r.stderr || '').replace(/\r/g, '').trim();
  if (errText) stderrSeen += '[' + label + '] ' + errText + ' ';
  if (r.status !== 0) throw new Error(label + ' mysql 退出码 ' + r.status + '：' + errText.slice(0, 400));
  return String(r.stdout || '').replace(/\r/g, '').split('\n').filter((x) => x !== '')
    .map((x) => x.split('\t'));
}

function say (line) { if (!QUIET) console.log(line); }

/** 单引号转义：入参全部来自命令行/常量，仍按最笨的方式转，防手滑。 */
function q (s) { return "'" + String(s).replace(/'/g, "''") + "'" }

// [label, valence, intensity]：七类情绪与效价口径按需求 §1.5
const LABELS = [['joy', 1, 4], ['trust', 1, 3], ['neutral', 0, 2],
  ['sadness', -1, 3], ['fear', -1, 2], ['anger', -1, 3], ['trust', 1, 2]];

function main () {
  if (!fs.existsSync(MYSQL) && !process.env.MINDISLE_MYSQL) {
    console.log('SKIP 找不到 mysql 客户端：' + MYSQL + '（可用 MINDISLE_MYSQL 指定）'); return 2;
  }
  if (!fs.existsSync(CNF)) { console.log('SKIP 找不到仓库外口令文件：' + CNF); return 2; }
  if (!Number.isInteger(DAYS) || DAYS < 3 || DAYS > 31) {
    console.log('FAIL MINDISLE_SEED_EMO_DAYS 必须是 3..31 的整数，收到的是：' + process.env.MINDISLE_SEED_EMO_DAYS); return 1;
  }
  if (!USER_LIST.length) { console.log('FAIL 没有指定账号'); return 1; }

  const names = USER_LIST.map(q).join(',');
  const found = queryOne('resolve-users',
    'SELECT id, username FROM `user` WHERE username IN (' + names + ') AND deleted = 0');
  const missing = USER_LIST.filter((u) => !found.some((r) => r[1] === u));
  if (missing.length) { console.log('FAIL 库里没有这些账号：' + missing.join(',')); return 1; }
  say('# 目标账号：' + found.map((r) => r[1] + '(id=' + r[0] + ')').join(' '));
  say('# 窗口天数：' + DAYS + '（可信天数判据是 3，本脚本给每一天都补上）');

  const ids = found.map((r) => Number(r[0]));
  const like = "'" + SENTINEL + "%'";
  const before = queryOne('count-before', 'SELECT COUNT(*) FROM emotion_record WHERE text_snippet LIKE ' + like);
  say('# 库里已有本脚本夹具 ' + (before[0] ? before[0][0] : '0') + ' 行');
  if (DRY_RUN) {
    say('# dry-run：不写库。本来会先删上述行，再为每个账号插 ' + (DAYS - 1) + '×2 行');
    console.log('OK dry-run 完成（未写库）'); return 0;
  }
  queryOne('delete-old', 'DELETE FROM emotion_record WHERE user_id IN (' + ids.join(',') + ') AND text_snippet LIKE ' + like);

  // 逐日插入：k=1..DAYS-1 往回。**不碰今天**——今天那条由探针自己产生，
  // 抢先插进去会和 d1-onboarding 的「同日第二次打卡走合并」判据打架。
  let inserted = 0;
  for (const row of found) {
    const uid = Number(row[0]);
    const values = [];
    for (let k = 1; k < DAYS; k++) {
      const lab = LABELS[(k + uid) % LABELS.length];
      const day = 'DATE_SUB(CURDATE(), INTERVAL ' + k + ' DAY)';
      values.push('(' + uid + ",'checkin',NULL," + q(SENTINEL + ' 主动打卡第' + k + '天') + ",'" + lab[0] + "'," +
        lab[2] + ',' + lab[1] + ",0.900,'manual','seed-demo'," + day + ',0,' + day + ')');
      values.push('(' + uid + ",'post',NULL," + q(SENTINEL + ' 今天有点' + lab[0] + '，但还是想把作业交上去') +
        ",'" + lab[0] + "'," + Math.max(1, lab[2] - 1) + ',' + lab[1] + ",0.720,'dict','lexicon-seed'," +
        day + ',0,' + day + ')');
    }
    queryOne('insert-' + row[1],
      'INSERT INTO emotion_record (user_id,source,ref_id,text_snippet,label,intensity,valence,' +
      'confidence,channel,model_version,record_date,deleted,created_at) VALUES ' + values.join(','));
    inserted += values.length;
    say('  -- ' + row[1] + '：插 ' + values.length + ' 行（' + (DAYS - 1) + ' 天 × 打卡+被动各一条）');
  }

  // 自检：按后端同一口径数「可信天数」（deleted=0 且 confidence >= 0.6 = emotion-confident-min），
  // 少于 3 天就等于这一步白做，直接退出码 1，不把「应该好了」写进台账。
  const check = queryOne('selfcheck',
    'SELECT u.username, COUNT(DISTINCT e.record_date) AS trusted_days, COUNT(*) AS rows_in_window ' +
    'FROM emotion_record e JOIN `user` u ON u.id = e.user_id ' +
    'WHERE e.deleted = 0 AND e.confidence >= 0.6 ' +
    'AND e.record_date >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) ' +
    'AND u.id IN (' + ids.join(',') + ') GROUP BY u.username');
  let bad = 0;
  for (const r of check) {
    const d = Number(r[1]);
    say('  -- 自检 ' + r[0] + '：近 7 天可信天数=' + d + ' 行数=' + r[2] + (d >= 3 ? ' OK' : ' 仍然 <3'));
    if (d < 3) bad++;
  }
  if (check.length !== ids.length) { say('  ⚠ 自检只数到 ' + check.length + ' 个账号，期望 ' + ids.length); bad++; }
  if (stderrSeen) say('# mysql stderr（预期为空）：' + stderrSeen.slice(0, 300));
  if (bad) { console.log('FAIL 自检未过：有账号的可信天数仍然 <3'); return 1; }
  console.log('OK 情绪档案夹具补齐：' + inserted + ' 行 · 账号 ' + ids.length + ' 个 · 可信天数已达判据 3 天');
  return 0;
}

try { process.exit(main()); } catch (e) { console.log('ERROR ' + (e && e.stack ? e.stack : e)); process.exit(1); }