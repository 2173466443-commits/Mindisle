// docs/export-sensitive-dict.mjs
// 从 sql/09_seed.sql 导出后端 DFA 词库资源（任务 3.2 · 需求 FR7.1 / §18.3）。
// 为什么要有这个脚本：词库的**唯一事实来源是 SQL 种子**（建库后 sensitive_word 表就是它），
// 后端在「库还没建好」的阶段也必须能跑审核链路，所以导出一份只读快照进 classpath。
// 两边不一致时以 SQL 为准，重跑本脚本即可；不要手改生成的 txt。
//
// 用法：node docs/export-sensitive-dict.mjs
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const sql = fs.readFileSync(path.join(root, 'sql', '09_seed.sql'), 'utf8');
const VERSION = 'v0.1';

// S2：七个词组元数据（等级 / 处置动作 / 作用侧）
const groups = new Map();
for (const m of sql.matchAll(/^\s*\((\d+),'([^']+)','(black|grey|risk)','(BLOCK|REVIEW|TAG)','(both|user|ai)',(\d+),/gm)) {
  groups.set(Number(m[1]), { name: m[2], level: m[3], action: m[4], scope: m[5], cnt: Number(m[6]) });
}

// S3：词条（行尾可能是逗号也可能是分号，别漏最后一条）
const words = [];
for (const m of sql.matchAll(/^\s*\((\d+),'((?:[^']|'')*)','([0-9a-f]{32})','(\w+)'\)\s*[,;]/gm)) {
  words.push({ gid: Number(m[1]), word: m[2].replace(/''/g, "'"), type: m[4] });
}

const perGroup = new Map();
for (const w of words) perGroup.set(w.gid, (perGroup.get(w.gid) || 0) + 1);
for (const [gid, g] of groups) {
  if ((perGroup.get(gid) || 0) !== g.cnt) {
    console.error(`FAIL 组 ${gid} ${g.name} 声明 ${g.cnt} 条，实得 ${perGroup.get(gid) || 0} 条`);
    process.exit(1);
  }
}

const out = [];
out.push('# 心屿 · 敏感词引擎词库快照（任务 3.2 · 需求 FR7.1 / §18.3）');
out.push(`#version=${VERSION}`);
out.push('# 由 docs/export-sensitive-dict.mjs 从 sql/09_seed.sql 导出，共 ' + words.length + ' 条，勿手改。');
out.push('# 列（TAB 分隔）：组名 \t level(black|grey|risk) \t action(BLOCK|REVIEW|TAG) \t 作用侧(both|user|ai) \t 匹配型(contains|regex) \t 词条');
out.push('# level 语义：black=硬拦截 grey=进人审 risk=放行但打危机标记（需求 §18.3「只预警不删帖」）');
for (const w of words) {
  const g = groups.get(w.gid);
  out.push([g.name, g.level, g.action, g.scope, w.type, w.word].join('\t'));
}
const target = path.join(root, 'backend', 'src', 'main', 'resources', 'dict', 'sensitive_words_' + VERSION + '.txt');
fs.writeFileSync(target, out.join('\n') + '\n', 'utf8');
console.log('OK ->', path.relative(root, target), 'rows=' + words.length, 'groups=' + groups.size);
