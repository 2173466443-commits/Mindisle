/**
 * markdown.js —— AI 回复气泡的安全 Markdown 渲染器（阶段 4 / T4.17）
 *
 * 为什么不引 markdown-it + DOMPurify：
 *   1）「前端零新增依赖」是本项目的硬约束（见《同类项目调研与实现方案.md》技术选型一节）；
 *   2）AI 的回复属于不可信输入，渲染必须是「默认拒绝」的白名单实现：
 *      只认本文件列出的这几种语法，其余一律当纯文本转义，绝不做 HTML 直通。
 *
 * 四条安全口径（改这个文件之前请先读这四条）：
 *   A. 先整体转义、再结构化：文本先过 esc()，尖括号和 & 不可能变成标签；
 *   B. 链接协议白名单：只放行 http / https / mailto，javascript: 与 data: 降级成纯文本；
 *   C. 属性值沿用第一次转义的结果，绝不二次转义（否则 URL 里的 & 会变成 &amp;amp;）；
 *   D. 代码块整块转义、不走行内规则，避免代码里的星号被当成强调。
 *
 * 刻意不支持的语法：下划线斜体（一个下划线包起来的那种）—— 它会咬坏正文里的
 * user_action、firstTokenMs 这类标识符，陪伴场景宁可少渲染也不能渲染错。
 */

/** 流式光标占位符：先拼在文本尾部参与渲染，整体渲染完再一次性换成 span，免得被转义吃掉 */
export const CHAT_CARET_TOKEN = '@@MI_CARET@@';
export const CARET_GLYPH = '▍';

const NL = String.fromCharCode(10);
const SLOT_A = String.fromCharCode(1);
const SLOT_B = String.fromCharCode(2);
/** 单条气泡的渲染上限：心理陪伴的回复一般两三百字，超限只渲染前段，防止异常长文本把主线程吃掉 */
export const MAX_RENDER_CHARS = 20000;

const ESC_MAP = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

/** HTML 实体转义；也是导出项，供其它「把文本塞进 innerHTML」的地方复用 */
export function esc(value) {
  const s = value === null || value === undefined ? '' : String(value);
  return s.replace(/[&<>"']/g, (c) => ESC_MAP[c]);
}

export const escapeHtml = esc;

/* ------------------------------ 行内规则 ------------------------------ */

const RE_CODE = /`([^`\n]+)`/g;
const RE_BOLD = /\*\*([^*]+?)\*\*/g;
const RE_ITALIC = /\*([^*\n]+)\*/g;
const RE_STRIKE = /~~([^~]+)~~/g;
const RE_LINK = /\[([^\]\n]*)\]\(\s*([^()\s]+)(?:\s+"[^"\n]*")?\s*\)/g;
const RE_SLOT = /\u0001(\d+)\u0002/g;

const SAFE_SCHEME = /^(?:https?:|mailto:)/i;
const MAX_HREF = 600;

/**
 * 链接。注意：label 与 href 传进来时已经是「整体转义之后」的文本，
 * 这里只做协议白名单校验，绝不再调一次 esc()，否则 URL 里的 & 会被二次编码成 &amp;amp;。
 * 不在白名单里的（javascript: / data: / 相对路径）降级成「文字 (链接原文)」，信息不丢，但不给可点元素。
 */
function linkHtml(label, escapedHref) {
  const href = String(escapedHref || '').trim();
  const target = href.startsWith('//') ? 'https:' + href : href;
  const text = String(label || '').trim() === '' ? href : String(label);
  if (target.length === 0 || target.length > MAX_HREF || !SAFE_SCHEME.test(target)) {
    return text + ' (' + href + ')';
  }
  return '<a class="md-a" href="' + target + '" target="_blank" rel="noopener noreferrer nofollow">' + text + '</a>';
}

/** 行内渲染：代码与链接先摘出去放进入槽位，剩下的粗体/删除线/斜体才不会咬坏 URL 和代码 */
function inline(raw) {
  const slots = [];
  const stash = (html) => {
    slots.push(html);
    return SLOT_A + (slots.length - 1) + SLOT_B;
  };
  let s = esc(raw);
  s = s.replace(RE_CODE, (m, code) => stash('<code class="md-code">' + code + '</code>'));
  s = s.replace(RE_LINK, (m, label, href) => stash(linkHtml(label, href)));
  s = s.replace(RE_BOLD, (m, body) => '<strong class="md-strong">' + body + '</strong>');
  s = s.replace(RE_STRIKE, (m, body) => '<del class="md-del">' + body + '</del>');
  s = s.replace(RE_ITALIC, (m, body) => '<em class="md-em">' + body + '</em>');
  return s.replace(RE_SLOT, (m, idx) => {
    const n = Number(idx);
    return n >= 0 && n < slots.length ? slots[n] : m;
  });
}

/* ------------------------------ 块级规则 ------------------------------ */

const RE_CRLF = /\r\n?/g;
const RE_FENCE = /^ {0,3}(`{3,}|~{3,}) *([A-Za-z0-9_+-]{0,24})? *$/;
const RE_HR = /^ {0,3}(-{3,}|\*{3,}|_{3,}) *$/;
const RE_HEADING = /^ {0,3}(#{1,6}) +(\S.*)$/;
const RE_QUOTE = /^ {0,3}> ?(.*)$/;
const RE_QUOTE_MARK = /^ *> ?/;
const RE_UL = /^ {0,3}([-*+]) +(\S.*)$/;
const RE_OL = /^ {0,3}(\d{1,9})[.)] +(\S.*)$/;
const RE_CONT = /^ {2,}\S/;

function renderFence(fence) {
  const cls = fence.lang ? 'md-pre md-pre-' + fence.lang : 'md-pre';
  return '<pre class="' + cls + '"><code class="md-pre-code">' + esc(fence.buf.join(NL)) + '</code></pre>';
}

/**
 * 把一段 Markdown 渲染成「只含白名单标签」的 HTML 字符串。
 * 逐行状态机，优先级：围栏代码块 &gt; 空行 &gt; 标题 &gt; 分割线 &gt; 引用 &gt; 列表 &gt; 段落。
 * 顺序不能换：围栏内部的一切都必须原样保留，否则代码里的 1. 会被当成有序列表。
 */
export function renderMarkdown(text) {
  if (text === null || text === undefined) return '';
  let src = String(text);
  if (src.length > MAX_RENDER_CHARS) src = src.slice(0, MAX_RENDER_CHARS);
  src = src.replace(RE_CRLF, NL);
  const lines = src.split(NL);

  const out = [];
  let para = [];
  let quote = [];
  let list = null;
  let fence = null;

  const flushPara = () => {
    if (para.length === 0) return;
    out.push('<p class="md-p">' + para.map(inline).join('<br />') + '</p>');
    para = [];
  };
  const flushQuote = () => {
    if (quote.length === 0) return;
    out.push('<blockquote class="md-quote">' + quote.map(inline).join('<br />') + '</blockquote>');
    quote = [];
  };
  const flushList = () => {
    if (list === null) return;
    const tag = list.ordered ? 'ol' : 'ul';
    const body = list.items.map((it) => '<li class="md-li">' + inline(it) + '</li>').join('');
    out.push('<' + tag + ' class="md-' + tag + '">' + body + '</' + tag + '>');
    list = null;
  };
  const flushAll = () => {
    flushPara();
    flushQuote();
    flushList();
  };

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    const trimmed = line.trim();
    const fenceMatch = RE_FENCE.exec(line);

    if (fence !== null) {
      if (fenceMatch && !fenceMatch[2] && fenceMatch[1].charAt(0) === fence.char) {
        out.push(renderFence(fence));
        fence = null;
      } else {
        fence.buf.push(line);
      }
      continue;
    }
    if (fenceMatch) {
      flushAll();
      fence = { char: fenceMatch[1].charAt(0), lang: (fenceMatch[2] || '').toLowerCase(), buf: [] };
      continue;
    }
    if (trimmed === '') {
      flushAll();
      continue;
    }
    const heading = RE_HEADING.exec(line);
    if (heading) {
      flushAll();
      out.push('<p class="md-h md-h' + heading[1].length + '">' + inline(heading[2]) + '</p>');
      continue;
    }
    if (RE_HR.test(trimmed)) {
      flushAll();
      out.push('<hr class="md-hr" />');
      continue;
    }
    const quoteMatch = RE_QUOTE.exec(line);
    if (quoteMatch) {
      flushPara();
      flushList();
      quote.push(quoteMatch[1].replace(RE_QUOTE_MARK, ''));
      continue;
    }
    flushQuote();
    const ul = RE_UL.exec(line);
    const ol = RE_OL.exec(line);
    if (ul !== null || ol !== null) {
      flushPara();
      const ordered = ol !== null;
      if (list !== null && list.ordered !== ordered) flushList();
      if (list === null) list = { ordered, items: [] };
      list.items.push((ordered ? ol : ul)[2]);
      continue;
    }
    if (list !== null && RE_CONT.test(line)) {
      list.items[list.items.length - 1] += ' ' + trimmed;
      continue;
    }
    flushList();
    para.push(trimmed);
  }

  if (fence !== null) {
    // 流式回复随时可能停在半块代码上：未闭合也照样渲染成一个代码块，别把后面的文字吃掉
    out.push(renderFence(fence));
    fence = null;
  }
  flushAll();
  return out.join('');
}

/**
 * 对话气泡专用。光标先以占位符参与渲染（它可能正好落在列表项或代码块里），
 * 整段渲染完再一次性换成 span，这样光标既能贴着最后一个字，也不会被转义成 &amp;。
 */
export function renderChatHtml(text, streaming) {
  const base = text === null || text === undefined ? '' : String(text);
  const html = renderMarkdown(streaming ? base + CHAT_CARET_TOKEN : base);
  return html.split(CHAT_CARET_TOKEN).join('<span class="md-caret" aria-hidden="true">' + CARET_GLYPH + '</span>');
}
