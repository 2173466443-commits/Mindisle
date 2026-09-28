#!/usr/bin/env node
/**
 * 心屿 MindIsle · tools/derive_dut.mjs
 * ---------------------------------------------------------------------------
 * 把「大连理工大学信息检索研究室 情感词汇本体 4.0」的原始 CSV，派生成本项目
 * 情绪词典通道（手册 §7.2 T4.7）真正加载的三张表：
 *
 *   backend/src/main/resources/dict/dut_emotion.tsv     词 -> (DUT 码, 强度, 极性, 7 类映射)
 *   backend/src/main/resources/dict/dut_code_map.tsv    DUT 21 小类 -> Plutchik 7 类 对照表
 *   backend/src/main/resources/dict/dut_provenance.json 派生过程的可复现凭证（行数/脏数据/分布）
 *
 * 为什么脚本进仓库、原始 CSV 不进仓库：
 *   原始文件 1.5MB / 2.7 万行，直接入库会让 git 历史永久背上这份二进制，
 *   而派生表才是运行时要读的东西。但「派生」这两个字必须可复核 ——
 *   所以脚本 + 来源 blob SHA + 逐条统计三样都要留下。
 *
 * 来源与许可（务必连同派生文件一起引用，缺一不可）：
 *   仓库   https://github.com/yizhanmiao/DLUT-Emotionontology
 *   文件   情感词汇/情感词汇.csv
 *   blob   6a8db2aa904b7c3cc81be05dc4b6aa648a5f7cd8
 *   许可   学术用途免费；用于论文/毕设符合条款。
 *   引文   徐琳宏, 林鸿飞, 潘宇, 等. 情感词汇本体的构造[J]. 情报学报, 2008, 27(2): 180-185.
 *
 * DUT 官方字段语义（随库《说明文档》）：
 *   情感分类 = 2 位码，7 大类 21 小类；强度 5 档取值 1/3/5/7/9（9 最强）；
 *   极性 0 中性 1 褒义 2 贬义 3 兼有褒贬；辅助情感分类 = 该词的次级情绪（可为空）。
 *
 * 重跑：node tools/derive_dut.mjs --src "E:/codex workspace/_cache/dut/dut_raw.csv"
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const SRC_DEFAULT = 'E:/codex workspace/_cache/dut/dut_raw.csv';
const OUT_DIR = path.resolve(process.cwd(), 'backend/src/main/resources/dict');

/** DUT 21 小类 -> 本项目落库口径 Plutchik 7 类。note 列原样进 dut_code_map.tsv 与论文附录。 */
const CODE_MAP = [
  ['PA', '快乐', '乐', 'joy', '', '直接对应：Plutchik joy 的核心就是快乐'],
  ['PE', '安心', '乐', 'neutral', '', '安心=低唤醒正性平静；七类里没有 calm，落到界面标签「平静」比落到「喜悦」更贴语义'],
  ['PB', '喜爱', '好', 'joy', 'yes', 'DUT 归「好」，但喜爱是趋近性正情绪，标成喜悦对用户更有用；信任留给评价他人的词'],
  ['PD', '尊敬', '好', 'trust', '', '尊敬是信任的典型外显'],
  ['PH', '赞扬', '好', 'trust', '', '赞扬=认可与信任的表达（本类词数最多，派生后须复核它会不会盖过其他类）'],
  ['PG', '相信', '好', 'trust', '', '字面即信任'],
  ['PK', '祝愿', '好', 'joy', 'yes', '祝愿/渴望指向期待，七类无 anticipation，归入喜悦'],
  ['NA', '愤怒', '怒', 'anger', '', '直接对应'],
  ['NK', '妒忌', '恶', 'anger', 'yes', 'DUT 归「恶」，但嫉妒的攻击性指向他人，落 anger 比落 disgust 更可用于陪伴话术'],
  ['NB', '悲伤', '哀', 'sadness', '', '直接对应'],
  ['NJ', '失望', '哀', 'sadness', '', '失望是悲伤的下位'],
  ['NH', '疚', '哀', 'sadness', '', '内疚在七类里没有独立位，属自我指向的负性低唤醒，归悲伤'],
  ['PF', '思', '哀', 'sadness', '', '思念在需求 §1.5 的七类里没有独立位，其效价为负，归悲伤'],
  ['NE', '烦闷', '恶', 'sadness', 'yes', 'DUT 归「恶」，但烦闷/郁闷带抑郁色彩；若照抄大类，「我好烦」会被标成厌恶，界面直接失真'],
  ['NC', '恐惧', '惧', 'fear', '', '直接对应'],
  ['NI', '慌', '惧', 'fear', '', '慌乱=高唤醒恐惧'],
  ['NG', '羞', '惧', 'fear', '', '羞怯在七类里无独立位，其回避动机来自惧'],
  ['NL', '怀疑', '恶', 'fear', 'yes', 'DUT 归「恶」，但怀疑的核心是不安全感，fear 与 disgust 二选一时取前者'],
  ['ND', '憎恶', '恶', 'disgust', '', '直接对应'],
  ['NN', '贬责', '恶', 'disgust', '', '贬责含指责与脏话，接近 Plutchik contempt=disgust+anger，取 disgust'],
  ['PC', '惊奇', '惊', 'neutral', '', '七类里没有 surprise；惊的效价中性，落 neutral 比硬塞进 fear 更诚实']
];

/** DUT 强度只有 1/3/5/7/9 五档，映射到本项目 1-5 用 (s-1)/2+1，不是任意线性缩放。 */
function toIntensity5(s9) {
  return (s9 - 1) / 2 + 1;
}

function readArg(name, dflt) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : dflt;
}

const src = path.resolve(readArg('src', SRC_DEFAULT));
const dictVersion = readArg('version', 'dut-4.0-mindisle-v1');

if (!fs.existsSync(src)) {
  console.error('[derive_dut] 找不到原始 CSV：' + src);
  console.error('[derive_dut] 取回方式见脚本头注释里的仓库与 blob 地址（docs/gate/阶段4/README.md 也记了）。');
  process.exit(1);
}

const raw = fs.readFileSync(src, 'utf8');
const lines = raw.split(/\r?\n/);
const header = lines.shift();

/**
 * DUT 的「词性种类」是 7 个封闭取值，用它当解析锚点：
 * 少数成语词条本身带逗号（例：眉头一皱,计上心头），按逗号切会把这些行切碎。
 * 第二个字段不是词性时，向后找到第一个词性，把中间片段用逗号拼回词面 —— 这是复原，不是猜。
 */
const POS_SET = new Set(['noun', 'verb', 'adj', 'adv', 'nw', 'idiom', 'prep']);

/** 强度归桶：官方说明是 1/3/5/7/9 五档，本快照实测还混有 0/2/4/6/8（见 provenance.offLadderStrengthCount）。 */
function bucket(s9) {
  return Math.min(5, Math.max(1, Math.round(s9 / 2)));
}

const codeOf = new Map(CODE_MAP.map(r => [r[0], r]));
const rows = [];
const anomalies = [];
const seen = new Set();
const dist = {};
let distTotal = 0;
let rejoined = 0;
let offLadder = 0;
let repairedPolarity = 0;

for (let i = 0; i < lines.length; i++) {
  const line = lines[i];
  if (!line.trim()) {
    continue;                       // 末尾空行
  }
  let f = line.split(',').map(s => s.trim());
  const lineNo = i + 2;             // 1-based，表头占第 1 行
  if (!POS_SET.has(f[1])) {
    const j = f.findIndex(x => POS_SET.has(x));
    if (j > 1) {
      f = [f.slice(0, j).join(',')].concat(f.slice(j));
      rejoined += 1;
    }
  }
  const word = f[0];
  const pos = f[1];
  const senseCount = Number(f[2]);
  const senseIndex = Number(f[3]);
  const code = f[4];
  const s9 = Number(f[5]);
  let pol = Number(f[6]);
  const auxCode = f[7] || '';
  const auxS9 = Number(f[8]);

  const valid = !!word
    && !!pos && POS_SET.has(pos)
    && /^[A-Z]{2}$/.test(code || '')
    && codeOf.has(code)
    && Number.isFinite(s9) && s9 >= 0 && s9 <= 9;
  if (!valid) {
    // 上游 CSV 本身的坏行：跳过并留证，绝不静默吞掉。
    anomalies.push({ lineNo, col5: code || '', raw: line.slice(0, 80) });
    continue;
  }
  if (![1, 3, 5, 7, 9].includes(s9)) {
    offLadder += 1;                 // 不在官方五档上，归桶后仍入库，但计数要上报
  }
  if (![0, 1, 2, 3].includes(pol)) {
    // 实测 1 行极性写成 7.0。极性可由大类推回：乐/好=褒义，惊=中性，其余=贬义。
    const fam = codeOf.get(code)[2];
    pol = fam === '乐' || fam === '好' ? 1 : fam === '惊' ? 0 : 2;
    repairedPolarity += 1;
  }
  distTotal += 1;
  dist[code] = (dist[code] || 0) + 1;

  const push = (c, strength, polarity, role) => {
    const key = word + '\u0001' + c + '\u0001' + role;
    if (seen.has(key)) {
      return;                       // 同词同码的多个义项：只留一条，词典不需要重复行
    }
    seen.add(key);
    rows.push({
      word, pos, code: c, codeName: codeOf.get(c)[1], family: codeOf.get(c)[2],
      plutchik: codeOf.get(c)[3], intensity9: strength, intensity5: bucket(strength),
      polarity, role,
      senseIndex: Number.isFinite(senseIndex) && senseIndex >= 1 ? Math.round(senseIndex) : 1
    });
  };
  push(code, s9, pol, 'primary');
  if (/^[A-Z]{2}$/.test(auxCode) && codeOf.has(auxCode) && auxCode !== code) {
    const auxStrength = Number.isFinite(auxS9) && auxS9 >= 1 && auxS9 <= 9 ? auxS9 : s9;
    push(auxCode, auxStrength, pol, 'aux');
  }
}

rows.sort((a, b) => (a.word === b.word
  ? (a.code === b.code ? a.senseIndex - b.senseIndex : a.code.localeCompare(b.code))
  : (a.word < b.word ? -1 : 1)));

const words = new Set(rows.map(r => r.word));
const T = '\t';
const banner = [
  '# dut-4.0-derived（运行时版本以本文件第一行为准，写进 emotion_record.model_version）',
  '# 版本标识：' + dictVersion,
  '# 来源：大连理工大学信息检索研究室《情感词汇本体》4.0（有效数据行 ' + distTotal + '，跳过错位脏行 ' + anomalies.length + '）',
  '# 仓库 https://github.com/yizhanmiao/DLUT-Emotionontology · 文件 情感词汇/情感词汇.csv · blob 6a8db2aa904b7c3cc81be05dc4b6aa648a5f7cd8',
  '# 强度归桶：官方说明为 1/3/5/7/9 五档，本快照实测另有 ' + offLadder + ' 行取值 0/2/4/6/8，按 round(s/2) 归入 1-5（intensity_1_9 保留原值）',
  '# 许可：学术用途免费。公开发表须声明使用了该情感词汇本体并引用：',
  '#   徐琳宏, 林鸿飞, 潘宇, 等. 情感词汇本体的构造[J]. 情报学报, 2008, 27(2): 180-185.',
  '# 派生脚本：tools/derive_dut.mjs（本文件由脚本生成，不要手改）',
  '# 列：word' + T + 'pos' + T + 'dut_code' + T + 'dut_code_name' + T + 'dut_family' + T + 'plutchik'
    + T + 'intensity_1_9' + T + 'intensity_1_5' + T + 'polarity' + T + 'sense_index' + T + 'role',
  '# polarity: 0 中性 / 1 褒义 / 2 贬义 / 3 兼有褒贬；sense_index: DUT 词义序号，1=核心义（引擎消歧优先取 1）；role: primary 主情感 / aux 辅助情感（投票权重减半）'
].join('\n');

const tsv = banner + '\n' + rows.map(r => [
  r.word, r.pos, r.code, r.codeName, r.family, r.plutchik,
  r.intensity9, r.intensity5, r.polarity, r.senseIndex, r.role
].join(T)).join('\n') + '\n';
fs.mkdirSync(OUT_DIR, { recursive: true });
fs.writeFileSync(path.join(OUT_DIR, 'dut_emotion.tsv'), tsv, 'utf8');

const mapBanner = [
  '# ' + dictVersion + ' · DUT 21 小类 -> 本项目落库口径 Plutchik 7 类 的对照表',
  '# 这张映射表是【本项目自定】，不是 DUT 原生结构；论文附录、dev-log、README 都必须写明这一点。',
  '# 五条偏离 DUT 大类的取舍（PB/PK/NK/NE/NL）理由见 note 列；界面中文标签见 frontend/src/components/EmotionPill.vue。',
  '# 列：dut_code' + T + 'dut_code_name' + T + 'dut_family' + T + 'plutchik' + T + 'deviates_from_family' + T + 'primary_word_count' + T + 'note'
].join('\n');
const mapTsv = mapBanner + '\n' + CODE_MAP.map(r => [
  r[0], r[1], r[2], r[3], r[4] || 'no', dist[r[0]] || 0, r[5]
].join(T)).join('\n') + '\n';
fs.writeFileSync(path.join(OUT_DIR, 'dut_code_map.tsv'), mapTsv, 'utf8');

const byPlutchik = {};
for (const r of rows) {
  byPlutchik[r.plutchik] = (byPlutchik[r.plutchik] || 0) + 1;
}
const provenance = {
  generatedBy: 'tools/derive_dut.mjs',
  generatedAt: new Date().toISOString(),
  dictVersion,
  source: {
    repo: 'https://github.com/yizhanmiao/DLUT-Emotionontology',
    file: '情感词汇/情感词汇.csv',
    blobSha: '6a8db2aa904b7c3cc81be05dc4b6aa648a5f7cd8',
    license: '学术用途免费；公开发表须声明并引文',
    citation: '徐琳宏,林鸿飞,潘宇,等. 情感词汇本体的构造[J]. 情报学报, 2008, 27(2): 180-185.',
    rawCsv: src,
    rawBytes: fs.statSync(src).size,
    rawHeader: header
  },
  dataLinesSeen: distTotal + anomalies.length,
  dataLinesValid: distTotal,
  dataLinesSkipped: anomalies.length,
  commaWordsRejoined: rejoined,
  offLadderStrengthCount: offLadder,
  repairedPolarityCount: repairedPolarity,
  skippedSamples: anomalies.slice(0, 8),
  rowsEmitted: rows.length,
  distinctWords: words.size,
  primaryRows: rows.filter(r => r.role === 'primary').length,
  auxRows: rows.filter(r => r.role === 'aux').length,
  sense1Rows: rows.filter(r => r.senseIndex === 1).length,
  multiPlutchikWords: Object.values(rows.reduce((acc, r) => { (acc[r.word] = acc[r.word] || new Set()).add(r.plutchik); return acc; }, {})).filter(set => set.size > 1).length,
  dutCodeDist: dist,
  plutchikDist: byPlutchik,
  mappingIsProjectDefined: true
};
fs.writeFileSync(path.join(OUT_DIR, 'dut_provenance.json'), JSON.stringify(provenance, null, 2) + '\n', 'utf8');

console.log('[derive_dut] dictVersion   = ' + dictVersion);
console.log('[derive_dut] 数据行读到     = ' + provenance.dataLinesSeen);
console.log('[derive_dut] 有效行         = ' + provenance.dataLinesValid);
console.log('[derive_dut] 跳过脏行       = ' + provenance.dataLinesSkipped
  + (anomalies.length ? ' -> 行号 ' + anomalies.map(a => a.lineNo).join(',') : ''));
console.log('[derive_dut] 含逗号词复原   = ' + rejoined + '；离档强度 ' + offLadder + '；极性回推 ' + repairedPolarity);
  console.log('[derive_dut] 输出词条行     = ' + rows.length + '（primary ' + provenance.primaryRows + ' / aux ' + provenance.auxRows + '）');
console.log('[derive_dut] 去重后词数     = ' + words.size);
console.log('[derive_dut] 7 类分布       = ' + JSON.stringify(byPlutchik));
console.log('[derive_dut] OUT ' + path.join(OUT_DIR, 'dut_emotion.tsv'));
console.log('[derive_dut] OUT ' + path.join(OUT_DIR, 'dut_code_map.tsv'));
console.log('[derive_dut] OUT ' + path.join(OUT_DIR, 'dut_provenance.json'));
