/**
 * mdtest.mjs —— markdown.js 的证伪自测（阶段 4 / T4.17）
 * 跑法：node frontend/probe/mdtest.mjs（不依赖浏览器，也不连后端）
 * 判据：每一条都必须既测「渲染对」也测「不能注入」，否则等于没写。
 */
import { renderMarkdown, renderChatHtml } from '../src/utils/markdown.js';

const BT = String.fromCharCode(96);
const LF = String.fromCharCode(10);
const CR = String.fromCharCode(13);

const cases = [
  ['XSS 标签', '<img src=x onerror=alert(1)>', (h) => h.indexOf('<img') === -1],
  ['javascript 链接', '[点我](javascript:alert(1))', (h) => h.indexOf('href') === -1],
  ['data 链接', '[a](data:text/html,<script>alert(1)</script>)', (h) => h.indexOf('href') === -1],
  ['https 链接转义', '[a](https://x.com/?a=1&b=2)', (h) => h.indexOf('rel="noopener') > -1 && h.indexOf('href="https://x.com/?a=1&amp;b=2"') > -1],
  ['有序列表', '1. 甲' + LF + '2. 乙', (h) => h.indexOf('<ol class="md-ol">') > -1 && h.split('<li').length === 3],
  ['无序列表', '- 甲' + LF + '- 乙', (h) => h.indexOf('<ul class="md-ul">') > -1],
  ['代码内不强调', BT + 'x*y' + BT, (h) => h.indexOf('<em') === -1 && h.indexOf('x*y') > -1],
  ['未闭合粗体', '**未闭合', (h) => h.indexOf('<strong') === -1],
  ['围栏内不当列表', BT.repeat(3) + LF + '1. 不是列表' + BT.repeat(3), (h) => h.indexOf('<pre') > -1 && h.indexOf('<ol') === -1],
  ['围栏未闭合', BT.repeat(3) + 'js' + LF + 'const a = 1;', (h) => h.indexOf('md-pre-js') > -1],
  ['标题', '## 你好', (h) => h.indexOf('md-h2') > -1],
  ['分割线', '---', (h) => h.indexOf('<hr class="md-hr" />') > -1],
  ['引用', '> 慢慢说', (h) => h.indexOf('<blockquote') > -1],
  ['段落换行', '甲' + LF + '乙', (h) => h.indexOf('<br />') > -1],
  ['流式光标', 'hello', (h) => renderChatHtml(h, true).indexOf('md-caret') > -1 && renderChatHtml(h, true).indexOf('@@MI_CARET@@') === -1],
  ['CRLF 归一', '甲' + CR + LF + '乙', (h) => h.indexOf('<br />') > -1]
];

let fail = 0;
for (const [name, input, check] of cases) {
  let html = '';
  try {
    html = name === '流式光标' ? renderChatHtml(input, true) : renderMarkdown(input);
  } catch (e) {
    console.log('FAIL ' + name + ' THREW ' + e.message);
    fail += 1;
    continue;
  }
  if (check(html)) {
    console.log('PASS ' + name);
  } else {
    fail += 1;
    console.log('FAIL ' + name + ' -> ' + html);
  }
}
console.log(fail === 0 ? 'ALL ' + cases.length + ' PASS' : 'FAILED ' + fail + '/' + cases.length);
process.exit(fail === 0 ? 0 : 1);
