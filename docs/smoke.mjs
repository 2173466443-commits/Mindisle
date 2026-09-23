#!/usr/bin/env node
/* ------------------------------------------------------------------ *
 * 心屿 MindIsle · 端到端 HTTP 冒烟脚本（任务 T3.1/T3.2 验收 · 手册 §5.10）   *
 *                                                                   *
 * 为什么要有这个文件：验证码答案只进缓存、不返回明文也不写日志，所以      *
 * 「注册 → 登录 → 带 token 打业务接口」这条最需要真实验证的链路，        *
 * 在自动化里天然拿不到 code。解法是本地把 mindisle.captcha.enabled      *
 * 显式设为 false（该键默认 true，属 fail-closed，生产不得关闭），        *
 * 请求体仍然照原样带 captchaId/captchaCode —— 关掉开关只放宽「校验」，   *
 * 不放宽入参契约，这一点由本脚本第 3 步顺带证明。                        *
 *                                                                   *
 * 用法：先起后端，再 node docs/smoke.mjs                              *
 *   环境变量 SMOKE_HOST / SMOKE_PORT / SMOKE_UPLOAD_DIR 可覆盖默认值。  *
 * 退出码：0=全部断言通过，1=有断言失败（CI 可直接用）。                  *
 * ------------------------------------------------------------------ */
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");
const HOST = process.env.SMOKE_HOST || "127.0.0.1";
const PORT = Number(process.env.SMOKE_PORT || "8080");
// URL 里的 /uploads 是 Spring 的挂载点，不是磁盘目录名：磁盘根 = upload.dir（默认 backend/uploads）
const UPLOAD_DIR = process.env.SMOKE_UPLOAD_DIR || path.join(ROOT, "backend", "uploads");

const rows = [];
let failures = 0;

function check(step, name, ok, detail) {
  if (!ok) failures++;
  rows.push({ step: step, name: name, ok: ok, detail: detail });
  const flag = ok ? "PASS" : "FAIL";
  console.log("[" + flag + "] " + step + " · " + name + (detail ? "  → " + detail : ""));
}

function info(step, name, detail) {
  rows.push({ step: step, name: name, ok: null, detail: detail });
  console.log("[INFO] " + step + " · " + name + (detail ? "  → " + detail : ""));
}

function send(method, urlPath, opts) {
  opts = opts || {};
  return new Promise(function (resolve, reject) {
    const req = http.request(
      { host: HOST, port: PORT, method: method, path: urlPath, headers: opts.headers || {} },
      function (res) {
        const chunks = [];
        res.on("data", function (c) { chunks.push(c); });
        res.on("end", function () {
          const body = Buffer.concat(chunks);
          let json = null;
          const ct = String(res.headers["content-type"] || "");
          if (body.length && ct.indexOf("json") >= 0) {
            try { json = JSON.parse(body.toString("utf8")); } catch (e) { json = null; }
          }
          resolve({ status: res.statusCode, headers: res.headers, body: body, json: json });
        });
      });
    req.on("error", reject);
    if (opts.body) req.write(opts.body);
    req.end();
  });
}

function jsonBody(obj) {
  return {
    headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(JSON.stringify(obj)) },
    body: JSON.stringify(obj)
  };
}

/** 手工拼 multipart：不引第三方依赖，脚本要能在任何装了 Node 的机器上直接跑。 */
function multipart(field, filename, contentType, content) {
  const boundary = "----mindisle" + Date.now().toString(16);
  const head = Buffer.from(
    "--" + boundary + "\r\n"
    + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n"
    + "Content-Type: " + contentType + "\r\n\r\n");
  const tail = Buffer.from("\r\n--" + boundary + "--\r\n");
  const body = Buffer.concat([head, content, tail]);
  return {
    headers: { "Content-Type": "multipart/form-data; boundary=" + boundary, "Content-Length": body.length },
    body: body
  };
}

function code(r) {
  return r.json && r.json.code !== undefined ? String(r.json.code) : "-";
}

function short(r, n) {
  const s = r.json ? JSON.stringify(r.json) : r.body.toString("utf8");
  return s.length > (n || 160) ? s.slice(0, n || 160) + "…" : s;
}

async function main() {
  // ---------- 0 服务活着吗 ----------
  let r = await send("GET", "/actuator/health");
  check("0", "GET /actuator/health 返 200 UP", r.status === 200 && r.json && r.json.status === "UP", r.status + " " + short(r, 90));
  r = await send("GET", "/api/system/info");
  info("0", "GET /api/system/info", r.status + " " + short(r, 200));

  // ---------- 1 验证码：接口本身仍要能出图 ----------
  r = await send("GET", "/api/auth/captcha");
  const captchaId = r.json && r.json.data ? r.json.data.captchaId : null;
  const pngB64 = r.json && r.json.data ? r.json.data.imageBase64 : null;
  check("1", "GET /api/auth/captcha 返 200 + 32 位 id + 纯 Base64 PNG",
    r.status === 200 && !!captchaId && captchaId.length === 32 && !!pngB64
    && /^[A-Za-z0-9+/=]+$/.test(pngB64),
    r.status + " code=" + code(r) + " id=" + captchaId + " pngBytes=" + (pngB64 ? Buffer.from(pngB64, "base64").length : 0));
  const captchaPng = pngB64 ? Buffer.from(pngB64, "base64") : null;

  // ---------- 2 未登录打业务接口必须被挡 ----------
  r = await send("POST", "/api/audit/precheck", Object.assign(jsonBody({ text: "测试", scene: "post" }), {}));
  check("2", "游客 POST /api/audit/precheck 返 401/10002", r.status === 401 && code(r) === "10002",
    r.status + " code=" + code(r));
  r = await send("POST", "/api/files/image", multipart("file", "x.png", "image/png", captchaPng || Buffer.alloc(0)));
  check("2", "游客 POST /api/files/image 返 401/10002（不给未登录者开写盘口子）", r.status === 401 && code(r) === "10002", r.status + " code=" + code(r));

  // ---------- 3 注册（带 captcha 字段但服务端已关校验，证明契约未放宽） ----------
  // 用户名固定，脚本才能反复跑而不会每次往库里塞一个垃圾账号。
  // 第二次起 register 会撞 UNIQUE 返回 20002 —— 那同样是「真的写进了 user 表」的证据，按通过处理。
  const username = "smoke_runner";
  const password = "Smoke#2026x";
  r = await send("POST", "/api/auth/register", jsonBody({
    username: username, password: password, nickname: "冒烟用户",
    captchaId: captchaId || "00000000000000000000000000000000", captchaCode: "ZZZZ",
    agreeTerms: true, agreePrivacy: true, agreeSensitive: false,
    // grade 必须是 ENUM 取值（sql/01_account.sql L40）：传中文标签会在 MySQL 侧变成 1265→503，
    // 这条脚本当初正是这么暴露问题的；现在 DTO 与 service 两端都有白名单（见 AuthServiceGradeTest）。
    consentVersion: "v1.0", grade: "SOPH", school: "示例大学", regSource: "smoke-script"
  }));
  const regDup = code(r) === "20002";
  check("3", "POST /api/auth/register 返 200（真实写进 MySQL 的 user 表）",
    (r.status === 200 && code(r) === "0") || regDup,
    r.status + " code=" + code(r) + (regDup ? " 上一轮的记录还在库里，本轮按重跑处理" : "") + " " + short(r, 140));

  r = await send("POST", "/api/auth/register", jsonBody({
    username: username, password: password, captchaId: "", captchaCode: "",
    agreeTerms: true, agreePrivacy: true
  }));
  check("3", "关掉验证码开关后，空 captchaId 仍被入参校验挡住（10001/400）", r.status === 400 && code(r) === "10001", r.status + " code=" + code(r) + " " + short(r, 120));

  // ---------- 4 登录取 token ----------
  r = await send("POST", "/api/auth/login", jsonBody({
    username: username, password: password,
    captchaId: captchaId || "00000000000000000000000000000000", captchaCode: "ZZZZ"
  }));
  const accessToken = r.json && r.json.data ? r.json.data.accessToken : null;
  check("4", "POST /api/auth/login 返 200 + accessToken", r.status === 200 && !!accessToken, r.status + " code=" + code(r) + " len=" + (accessToken ? accessToken.length : 0));
  if (!accessToken) {
    // 拿不到 token 就别再往下打：否则第 5~9 步会整排报 401，看着像 5 个 bug，实际只有第 4 步那一个。
    console.log("");
    console.log("取不到 accessToken，第 5~9 步整体跳过（不逐条报 401，免得把 1 个故障刷屏成 5 个）");
    console.log("冒烟汇总：失败 " + (failures + 1) + " 条（含本条「后续未执行」）");
    process.exitCode = 1;
    return;
  }
  const auth = { headers: { Authorization: "Bearer " + accessToken } };
  // 把 Authorization 与各自的 Content-Type 合成一份 headers。
  // 这里踩过坑：写成 Object.assign({ headers: auth.headers }, jsonBody(x)) 会让 jsonBody 的
  // headers 整份顶掉 Authorization，第 6~9 步集体变成 401，看着像鉴权坏了，其实是脚本自己丢的头。
  function withAuth(opts) {
    return { headers: Object.assign({}, auth.headers, opts.headers || {}), body: opts.body };
  }

  /** 换别的 token 打同一个接口：注册一个发帖账号之后要用它自己的令牌，不能再复用 withAuth。 */
  function asToken(token, opts) {
    return { headers: Object.assign({ Authorization: "Bearer " + token }, opts.headers || {}), body: opts.body };
  }

  // ---------- 5 带 token 读自己（第一条真实落库读链路） ----------
  r = await send("GET", "/api/users/me", auth);
  check("5", "GET /api/users/me 返 200 且 username 对得上", r.status === 200 && r.json && r.json.data && r.json.data.username === username,
    r.status + " " + short(r, 160));
  r = await send("GET", "/api/users/me/consents", auth);
  check("5", "GET /api/users/me/consents 返 200（协议留痕可读回）", r.status === 200 && Array.isArray(r.json && r.json.data) && r.json.data.length >= 2,
    r.status + " n=" + (r.json && r.json.data ? r.json.data.length : "-"));

  // ---------- 6 敏感词预检：阶段 3 地基之一，历史上从没拿到过 200 ----------
  r = await send("POST", "/api/audit/precheck", withAuth(
    jsonBody({ text: "我活不下去了，想伤害自己。我的微信是 abc_123，电话 13800138000", scene: "post" })));
  // 出参刻意不含词面（含就等于给人一份可迭代的钓词库反馈），所以这里用 positions 反查原文，
  // 既断言「命中了几个」，也断言「命中的确实是他」，还顺手钉住坐标系的契约（与前端 textarea 同标）。
  const risky = "我活不下去了，想伤害自己。我的微信是 abc_123，电话 13800138000";
  r = await send("POST", "/api/audit/precheck", withAuth(jsonBody({ text: risky, scene: "post" })));
  const view = r.json && r.json.data ? r.json.data : {};
  const ranges = (view.positions || []).map(function (p) { return risky.slice(p[0], p[1]); });
  check("6", "precheck 返 200，positions 反查原文正好是危机词与手机号",
    r.status === 200 && code(r) === "0" && view.hit === true && view.hitCount >= 2
      && ranges.indexOf("伤害自己") >= 0 && ranges.indexOf("13800138000") >= 0,
    r.status + " n=" + view.hitCount + " 主因=" + view.category + "/" + view.action + " 命中区间=" + ranges.join("|"));
  check("6", "危机命中即使不是主因也必须带求助热线（12356 卡片不被隐私命中顶掉）",
    view.category === "隐私泄露" && !!view.hotline,
    "主因=" + view.category + " hotline=" + view.hotline);
  r = await send("POST", "/api/audit/precheck", withAuth(jsonBody({ text: "伤害自己", scene: "post" })));
  check("6", "单独危机文本判为 risk/TAG（放行打标，绝不删）并给求助入口",
    r.status === 200 && r.json.data.level === "risk" && r.json.data.action === "TAG" && !!r.json.data.hotline,
    r.status + " " + short(r, 200));
  r = await send("POST", "/api/audit/precheck", withAuth(
    jsonBody({ text: "今天天气不错，去图书馆自习了", scene: "post" })));
  check("6", "干净文本不命中（防误杀）", r.status === 200 && r.json.data.hit === false,
    r.status + " " + short(r, 160));

  // ---------- 7 图片上传：真实写盘 + 回读字节一致 ----------
  r = await send("POST", "/api/files/image", withAuth(
    multipart("file", "smoke.png", "image/png", captchaPng)));
  const stored = r.json && r.json.data ? r.json.data : null;
  check("7", "POST /api/files/image 返 200 + /uploads/yyyy/MM/dd/<32位hex>.png", r.status === 200 && !!stored && /^\/uploads\/\d{4}\/\d{2}\/\d{2}\/[0-9a-f]{32}\.png$/.test(stored.url || ""),
    r.status + " " + short(r, 200));
  if (stored && stored.url) {
    const rel = stored.url.replace(/^\/uploads\//, "");
    const disk = path.join(UPLOAD_DIR, rel);
    const onDisk = fs.existsSync(disk) ? fs.readFileSync(disk) : null;
    check("7", "服务端落盘文件存在且字节数与响应体 bytes 字段一致", !!onDisk && onDisk.length === stored.bytes, disk + " size=" + (onDisk ? onDisk.length : "missing") + " claimed=" + stored.bytes);
    const back = await send("GET", stored.url);
    check("7", "GET 静态映射回读 200 + image/png + 与磁盘逐字节相同", back.status === 200 && String(back.headers["content-type"]).indexOf("image/png") === 0
      && back.body.equals(onDisk || Buffer.alloc(0)),
      back.status + " " + back.headers["content-type"] + " len=" + back.body.length);
    info("7", "落盘位置（清理时用 node fs.unlinkSync，别用递归删除）", disk);
  }

  // ---------- 8 主题列表：只读 DB 链路 ----------
  // 默认 limit=10 是接口自己的分页口径（FeedController），种子共 20 条，
  // 所以「拉满 50 能读到 20 条」才是 topic 表全量可读的证据。
  r = await send("GET", "/api/topics");
  check("8", "GET /api/topics 返 200 且默认只给 10 条",
    r.status === 200 && Array.isArray(r.json && r.json.data) && r.json.data.length === 10,
    r.status + " n=" + (r.json && r.json.data ? r.json.data.length : "-"));
  r = await send("GET", "/api/topics?limit=50");
  check("8", "GET /api/topics?limit=50 返 200 且 20 条种子话题全部读回",
    r.status === 200 && Array.isArray(r.json && r.json.data) && r.json.data.length === 20,
    r.status + " n=" + (r.json && r.json.data ? r.json.data.length : "-"));
  // 拿一条真实存在的过审话题给第 10 步：写死 id 会让脚本在别的库上必然失败。
  const topicRow = (r.json && Array.isArray(r.json.data) && r.json.data.length) ? r.json.data[0] : null;
  const topicId = topicRow ? topicRow.id : null;
  const topicName = topicRow ? topicRow.name : null;
  if (!topicRow) { check("8", "拿不到任何话题，第 10 步的话题用例只能空跑", false, "seed 是否加载过？"); }

  // ---------- 9 发帖入参契约：能当场拒的必须拒，不许静默改写 ----------
  // 本步全部是「抛在校验里」的请求，一条都不会落库，所以不消耗发帖额度；
  // 正因如此它必须排在第 10 步之前：配额检查是发帖流程的第一道（最便宜的判断放最前），
  // 额度用满之后再打这些接口，拿回来的会是 429 而不是 400，看着像校验坏了。
  //
  // 为什么另注册一个账号，而不是复用 smoke_runner：
  // 新手期（注册 24 小时内）每天限发 5 帖（BR5），第 10 步要真实发满 5 帖才能验到第 6 帖被挡；
  // 计数落在缓存里、按自然日重置，复用固定账号会让「当天第二次跑」从第一条正向断言就撞 429 ——
  // 那是配额在生效不是功能坏了，但冒烟脚本要的是「跑一次就证一次」。
  // 代价：每跑一次库里多一行冒烟账号，dev-log 里附了清理 SQL。
  const stamp = new Date().toISOString().replace(/[-:T]/g, "").slice(0, 14);
  const posterName = "smoke_post_" + stamp;
  r = await send("POST", "/api/auth/register", jsonBody({
    username: posterName, password: "Smoke#2026x", nickname: "冒烟发帖账号",
    captchaId: captchaId || "00000000000000000000000000000000", captchaCode: "ZZZZ",
    agreeTerms: true, agreePrivacy: true, consentVersion: "v1.0", regSource: "smoke-script"
  }));
  const posterToken = r.json && r.json.data ? r.json.data.accessToken : null;
  check("9", "注册发帖专用账号 " + posterName + "（第 10 步要真实落库，额度必须是满的）",
    r.status === 200 && !!posterToken, r.status + " code=" + code(r) + " " + short(r, 120));

  if (posterToken) {
    const post = function (body) { return send("POST", "/api/posts", asToken(posterToken, jsonBody(body))); };
    const brief = function (rr) { return rr.status + " code=" + code(rr) + " " + short(rr, 150); };

    r = await post({ content: "没有标题的一条", visibility: "public" });
    check("9", "缺标题 → 400/10001（标题是必填，不做「用正文前 20 字兜一个」）", r.status === 400 && code(r) === "10001", brief(r));

    r = await post({ title: "冒烟·负面", content: "只给好友看", visibility: "friends" });
    check("9", "本期未开放的可见性 → 400/10001（明确拒绝，而不是偷偷改成 public 公开出去）",
      r.status === 400 && code(r) === "10001", brief(r));

    r = await post({ title: "冒烟·负面", content: "普通帖不许设销毁时间", autoDestroyHours: 24 });
    check("9", "非树洞传 autoDestroyHours → 400/10001（到期销毁只对树洞开放）", r.status === 400 && code(r) === "10001", brief(r));

    r = await post({ title: "冒烟·负面", content: "形式写错了", type: "moment" });
    check("9", "白名单外的 type → 400/10001（不猜用户想发什么）", r.status === 400 && code(r) === "10001", brief(r));

    r = await post({ title: "冒烟·负面", content: "挂一个不存在的话题", topicIds: [99999999] });
    check("9", "话题不存在 → 400/10001（不能默默把这个话题丢掉、发出一条和用户点的不一样的帖）",
      r.status === 400 && code(r) === "10001", brief(r));

    r = await post({ title: "冒烟·负面", content: "配图是假的", images: ["/uploads/2026/01/01/0000000000000000000000000000dead.png"] });
    check("9", "配图 URL 在服务端读不到真文件 → 400/10001（发帖这一刻的张数与字节数校验不是装饰）",
      r.status === 400 && code(r) === "10001", brief(r));

    // 未过审话题走 409/30004：自 T3.8 起脚本有了合法造待审话题的途径（POST /api/topics + FR8.6
    // 的预审开关缺省即 true），所以那条分支由第 21 步自建话题自证。这里保留外部预置入口，是为了在
    // require-pre-review=false 的环境里（新建即过审、脚本自己造不出待审）仍然拿得到这条证据。
    const pendingId = Number(process.env.SMOKE_PENDING_TOPIC_ID || "0");
    if (pendingId > 0) {
      r = await post({ title: "冒烟·负面", content: "挂一个待审话题", topicIds: [pendingId] });
      check("9", "话题未过审 → 409/30004（用户能做的是等，不是改，所以不给 400）",
        r.status === 409 && code(r) === "30004", brief(r));
    } else {
      info("9", "未提供 SMOKE_PENDING_TOPIC_ID，本步不单独取证 30004 分支",
        "这条分支已由第 21 步自建待审话题自证（T3.8 之后 POST /api/topics 可用）：留这个环境变量"+
        "是给 require-pre-review=false 的环境兜底，本条只多一行 INFO、不改断言计数");
    }

    r = await send("POST", "/api/posts", jsonBody({ title: "游客发帖", content: "应当被挡在门外" }));
    check("9", "不带 token 发帖 → 401/10002", r.status === 401 && code(r) === "10002", brief(r));
  } else {
    info("9", "第 9 步后续用例与第 10 步整体跳过（账号没注册下来，逐条打只会刷出一屏 401）",
      "真正的故障是上面那条注册失败，exit code 已经由它决定");
  }

  // ---------- 10 发帖状态机：真 token + 真 DFA 机审 + 真 MySQL 落库（T3.3 验收主项） ----------
  if (posterToken) {
    const post = function (body) { return send("POST", "/api/posts", asToken(posterToken, jsonBody(body))); };
    const data = function (rr) { return rr.json && rr.json.data ? rr.json.data : {}; };
    const ids = [];

    // 10.1 干净文本：DRAFT → MACHINE_REVIEW → PUBLISHED，两行流转日志（DB 取证对账）
    // 把第 7 步真实上传的那张图挂上：宽高必须来自服务端读盘真值，不是客户端报的（步骤 7 已验过落盘字节）
    r = await post({ title: "冒烟·普通帖", content: "今天去图书馆自习了，晚上还跑了三公里。",
      topicIds: [topicId], images: stored && stored.url ? [stored.url] : [] });
    const v1 = data(r);
    ids.push(v1.id);
    check("10", "干净文本 → 200 + PUBLISHED + publishedAt 非空 + 实名显示昵称",
      r.status === 200 && code(r) === "0" && v1.status === "PUBLISHED" && !!v1.publishedAt
      && v1.anonymous === false && v1.displayName === "冒烟发帖账号",
      r.status + " status=" + v1.status + " publishedAt=" + v1.publishedAt + " 展示名=" + v1.displayName);
    check("10", "已过审话题按名回显（FR4.5 只挂过审的）",
      Array.isArray(v1.topics) && v1.topics.length === 1 && v1.topics[0] === topicName,
      JSON.stringify(v1.topics) + " 期望=" + topicName);
    // 上传接口与发帖接口是两次独立的服务端解码，两边给出来的宽高必须一模一样，
    // 而客户端从头到尾没有一次机会报「这张图多大」。
    check("10", "配图落 post_image：宽高两次服务端解码一致（客户端全程没机会报价）",
      !!stored && Array.isArray(v1.images) && v1.images.length === 1
      && v1.images[0].url === stored.url && v1.images[0].width === stored.width
      && v1.images[0].height === stored.height,
      JSON.stringify(v1.images) + " 上传时=" + (stored ? stored.width + "x" + stored.height : "无图"));

    // 10.2 L2 显著风险：需求 §18.3 写死「放行打标 + 卡片 + 工单，不删除」
    r = await post({ title: "冒烟·求助", content: "这两天压力很大，总想伤害自己。", type: "help" });
    const v2 = data(r);
    ids.push(v2.id);
    check("10", "危机文本 → 仍然 PUBLISHED 并给出 12356 卡片（删帖等于把人推回沉默 · 创新点 3）",
      r.status === 200 && v2.status === "PUBLISHED" && v2.hotline === "12356" && !!v2.tip,
      r.status + " status=" + v2.status + " hotline=" + v2.hotline);
    // 出参字段是白名单，不是「过滤掉敏感字段」：多一个 riskLevel/level 都算契约破了。
    check("10", "出参不透 risk_level / level（等级只给服务端与管理端，不给用户贴标签 · NFR8）",
      v2.riskLevel === undefined && v2.level === undefined && v2.risk === undefined,
      "出参字段=" + Object.keys(v2).join(","));

    // 10.3 L3：告别语义 → 同一张卡片，差别只在服务端工单的 SLA（30 分钟 vs 4 小时），DB 里取证
    r = await post({ title: "冒烟·L3", content: "我把东西分给室友了，这是最后一次跟这里说说话。" });
    const v3 = data(r);
    ids.push(v3.id);
    check("10", "告别语义 → 放行 + 卡片（等级只进 alert_ticket，不给客户端）",
      r.status === 200 && v3.status === "PUBLISHED" && v3.hotline === "12356", r.status + " " + v3.status);

    // 10.4 黑词：内容拦下，但 HTTP 仍是 200 —— 被拦不是协议错误，不能和「没登录」「参数错」混成一类
    // 顺手挂一个已过审话题：被拦下也要留关联行（申诉与举报阈值要指回同一条内容），
    // 但 topic.post_cnt 只能由 PUBLISHED 自增——「关联照写、计数不涨」这两件事的差值只能靠 SQL 取证，见 dev-log。
    r = await post({ title: "冒烟·黑词", content: "出售枪支弹药，私聊。", topicIds: [topicId] });
    const v4 = data(r);
    ids.push(v4.id);
    check("10", "black/BLOCK → 200 + status=REJECTED + 不写 publishedAt + 无危机时不给卡片",
      r.status === 200 && v4.status === "REJECTED" && !v4.publishedAt && !!v4.tip && v4.hotline === undefined,
      r.status + " status=" + v4.status + " publishedAt=" + v4.publishedAt + " hotline=" + v4.hotline);
    check("10", "提示语不回传命中词面（否则等于给出一份可迭代的钓词库反馈）",
      String(v4.tip || "").indexOf("枪支") < 0, "tip=" + v4.tip);
    check("10", "被拦下的帖子也回显话题（关联行照写，post_cnt 是否自增交给 SQL 取证）",
      Array.isArray(v4.topics) && v4.topics.length === 1 && v4.topics[0] === topicName,
      JSON.stringify(v4.topics) + " 期望=" + topicName);

    // 10.5 树洞：强制匿名 + 马甲 + 默认 7 天销毁（floor_no 只在库里，出参不含楼层）
    r = await post({ title: "冒烟·树洞", content: "有些话只能烂在肚子里。", type: "hole" });
    const v5 = data(r);
    ids.push(v5.id);
    check("10", "type=hole → anonymous=true + 展示名「匿名屿民·X」+ autoDestroyAt 非空",
      r.status === 200 && v5.anonymous === true && String(v5.displayName || "").indexOf("匿名屿民·") === 0
      && !!v5.autoDestroyAt,
      r.status + " 展示名=" + v5.displayName + " autoDestroyAt=" + v5.autoDestroyAt);

    info("10", "本轮真实落库的 5 个帖子 id（MySQL 取证就查这些行）", ids.join(","));

    // 10.6 新手期配额：上面 5 条里有 4 条 PUBLISHED、1 条 REJECTED，第 6 条必须被 BR5 挡下。
    // 这条同时钉住「被拦下的帖子也消耗额度」——否则可以拿发帖接口无限试探词库的拦截边界。
    r = await post({ title: "冒烟·第6帖", content: "看看新手额度挡不挡得住。" });
    check("10", "新手期第 6 帖 → 429/10010（BR5 每日 5 帖，含那条 REJECTED）",
      r.status === 429 && code(r) === "10010", r.status + " code=" + code(r) + " " + short(r, 150));
  }

  // ---------- 11 拦内容不拦人：黑词 + 危机同句（创新点 3 最难自证的一条） ----------
  // 需求 §18.3 对自伤词条写的是「放行不删除」，但黑词（涉诈涉赌涉黄）必须拦，
  // 于是留下这条唯一的例外分支：内容 REJECTED，工单与求助卡片照发。
  // 它只有这一种走法能被真实 HTTP 证到：既命中 black/BLOCK 又命中 risk，
  // 而任何一条已发过帖的账号都排不到它（额度只够五条），所以再开一个账号，一次一帖。
  const careName = "smoke_care_" + stamp;
  r = await send("POST", "/api/auth/register", jsonBody({
    username: careName, password: "Smoke#2026x", nickname: "冒烟关怀账号",
    captchaId: captchaId || "00000000000000000000000000000000", captchaCode: "ZZZZ",
    agreeTerms: true, agreePrivacy: true, consentVersion: "v1.0", regSource: "smoke-script"
  }));
  const careToken = r.json && r.json.data ? r.json.data.accessToken : null;
  check("11", "注册关怀用例账号 " + careName, r.status === 200 && !!careToken, r.status + " code=" + code(r));
  if (careToken) {
    r = await send("POST", "/api/posts", asToken(careToken, jsonBody({
      title: "冒烟·黑词里的人", content: "他说不想活了，还要卖枪支弹药给我。" })));
    const vc = r.json && r.json.data ? r.json.data : {};
    check("11", "黑词+危机同句 → 内容 REJECTED，但仍返回 12356 卡片与提示（拦的是一条内容，不是一个人）",
      r.status === 200 && vc.status === "REJECTED" && vc.hotline === "12356" && !!vc.tip,
      r.status + " status=" + vc.status + " hotline=" + vc.hotline);
    // 一句话里三件事都得有：为什么没发出去（拦下）、还能怎么办（改一改）、有人在意（打这个电话），
    // 同时一个命中词面都不能出现——「不想活」和「枪支弹药」任何一个进了 tip 都是把词库公开给用户。
    check("11", "提示语同时说清「已拦下 / 可改后重发 / 有求助电话」，且不出现任何命中词面",
      String(vc.tip || "").indexOf("拦下") >= 0 && String(vc.tip || "").indexOf("再发") >= 0
      && String(vc.tip || "").indexOf("电话") >= 0 && String(vc.tip || "").indexOf("枪支") < 0
      && String(vc.tip || "").indexOf("不想活") < 0, "tip=" + vc.tip);
    info("11", "取证用的帖子 id（对应 alert_ticket 里应有一行 L2 待认领工单）", String(vc.id));
  } else {
    info("11", "账号没注册下来，本步跳过（第 9 步第一条已经决定了退出码）", "");
  }

  // ---------- 12 帖子列表与可见性（任务 3.5 验收主项） ----------
  const seenName = "smoke_seen_" + stamp;
  r = await send("POST", "/api/auth/register", jsonBody({
    username: seenName, password: "Smoke#2026x", nickname: "冒烟可见性账号",
    captchaId: captchaId || "00000000000000000000000000000000", captchaCode: "ZZZZ",
    agreeTerms: true, agreePrivacy: true, consentVersion: "v1.0", regSource: "smoke-script"
  }));
  const seenToken = r.json && r.json.data ? r.json.data.accessToken : null;
  check("12", "注册「可见性用例」账号 " + seenName + "（本步要真发三条，额度必须是满的）",
    r.status === 200 && !!seenToken, r.status + " code=" + code(r));

  const listGet = function (token, qs) {
    return send("GET", "/api/posts" + (qs ? "?" + qs : ""),
      token ? { headers: { Authorization: "Bearer " + token } } : {});
  };
  const detailGet = function (token, id) {
    return send("GET", "/api/posts/" + id, { headers: { Authorization: "Bearer " + token } });
  };
  const itemsOf = function (rr) {
    return rr.json && rr.json.data && Array.isArray(rr.json.data.list) ? rr.json.data.list : null;
  };
  const bodyOf = function (rr) { return rr.json && rr.json.data ? rr.json.data : {}; };
  // 翻到底取整条广场流：单页被 MAX_SIZE 夹在 50，而「某条帖子不该出现」这种否定断言在一页截断上
  // 是恒真的——它可能只是被翻页截掉了。库里公开已发布帖一超过 50 条，首页就不等于全站
  // （本轮冒烟第 2 次跑真的挂了就是这个原因）。所以凡判存在性，先把游标翻到底。
  // 另外待审帖 published_at 是 NULL，在 published_at DESC 里排在流尾，不翻到底必然看不到。
  const walkFeed = async function (token, maxPages) {
    const all = [];
    const seenIds = new Set();
    let pages = 0;
    let firstRows = null;
    let cursor = null;
    for (let i = 0; i < (maxPages || 40); i += 1) {
      const rr = await listGet(token, "size=50" + (cursor ? "&beforeId=" + cursor : ""));
      const items = itemsOf(rr) || [];
      const fresh = items.filter(function (x) { return !seenIds.has(x.id); });
      fresh.forEach(function (x) { seenIds.add(x.id); });
      all.push.apply(all, fresh);
      if (firstRows === null) { firstRows = items.slice(); }
      pages += 1;
      if (rr.status !== 200 || items.length === 0 || fresh.length === 0) { break; }
      cursor = rr.json && rr.json.data ? rr.json.data.nextCursor : null;
      if (!cursor) { break; }
    }
    all.firstPage = firstRows || [];  // 首页只用于证明「单页确实被截断了」，不参与存在性判定
    all.pages = pages;
    return all;
  };

  let privateId = null, pendingId = null, publicId = null;

  if (seenToken) {
    const postAs = function (body) { return send("POST", "/api/posts", asToken(seenToken, jsonBody(body))); };

    r = await postAs({ title: "冒烟·仅自己可见", content: "这本日记只有我自己翻得到。", visibility: "private" });
    privateId = bodyOf(r).id;
    check("12", "visibility=private 照样发得出去（PUBLISHED，可见性只决定谁能看见）",
      r.status === 200 && bodyOf(r).status === "PUBLISHED" && bodyOf(r).visibility === "private" && !!privateId,
      r.status + " status=" + bodyOf(r).status + " visibility=" + bodyOf(r).visibility);

    r = await postAs({ title: "冒烟·转人工", content: "有件事想问，我的手机号是 13800138000。" });
    pendingId = bodyOf(r).id;
    check("12", "灰词（隐私）→ HUMAN_REVIEW，此刻只有作者看得见",
      r.status === 200 && bodyOf(r).status === "HUMAN_REVIEW" && !!pendingId, r.status + " status=" + bodyOf(r).status);

    r = await postAs({ title: "冒烟·被看见", content: "公开的一条，用来验翻页与浏览量。" });
    publicId = bodyOf(r).id;
    check("12", "干净文本 → PUBLISHED，作为第 12/13 步的正面样本", r.status === 200 && bodyOf(r).status === "PUBLISHED" && !!publicId, r.status + " id=" + publicId);

    const others = await walkFeed(accessToken);
    const firstPage = others.firstPage;
    check("12", "第三人广场：公开帖在列，私密帖与待审帖整条不出现（不是置灰，是没这行）"
      + "——在整条流上判：首页被 50 条截断时「不出现」是恒真的，不构成证据",
      firstPage.length === 50 && others.length > firstPage.length
      && others.some(function (x) { return x.id === publicId; })
      && !others.some(function (x) { return x.id === privateId; })
      && !others.some(function (x) { return x.id === pendingId; }),
      "首页=" + firstPage.length + " 全流=" + others.length + " 页=" + others.pages
      + " 私密在列=" + others.some(function (x) { return x.id === privateId; })
      + " 待审在列=" + others.some(function (x) { return x.id === pendingId; }));
    let dr = await detailGet(accessToken, privateId);
    check("12", "第三人打别人私密帖详情 → 404/30001（报 403 就等于承认这条存在）", dr.status === 404 && code(dr) === "30001", dr.status + " code=" + code(dr));
    dr = await detailGet(accessToken, pendingId);
    check("12", "第三人打别人待审帖详情 → 404/30001", dr.status === 404 && code(dr) === "30001", dr.status + " code=" + code(dr));

    const mine = await walkFeed(seenToken);
    const myPending = mine.find(function (x) { return x.id === pendingId; });
    check("12", "作者广场：自己的待审帖在列且带 auditTip（先发后审要被感知，而不是「我发的帖凭空消失」）"
      // 待审帖 published_at 为 NULL，在 published_at DESC 里排在流尾，只看首页必然判不到
      + "——在整条流上判，不是在首页上判",
      !!myPending && String(myPending.auditTip || "").length > 0,
      "全流 n=" + mine.length + " 页=" + mine.pages + " auditTip=" + String((myPending || {}).auditTip));
    check("12", "作者的私密已发布帖不进广场列表（广场=公共流，私密走任务 3.14 的「我的帖子」）",
      !mine.some(function (x) { return x.id === privateId; }), "n=" + mine.length);
    dr = await detailGet(seenToken, privateId);
    check("12", "作者本人打自己私密帖详情 → 200 + visibility=private + 正文全文可读回",
      dr.status === 200 && dr.json.data.visibility === "private" && String(dr.json.data.content || "").length > 0,
      dr.status + " visibility=" + (dr.json.data && dr.json.data.visibility));

    r = await listGet(accessToken, "size=3");
    const p1 = itemsOf(r) || [];
    const cur1 = r.json.data.nextCursor;
    check("12", "首屏（页码模式）也回 nextCursor + total + hasMore，前端只需一种翻页方式",
      r.status === 200 && p1.length === 3 && !!cur1 && r.json.data.hasMore === true && r.json.data.total > 3,
      "cursor=" + cur1 + " total=" + r.json.data.total);
    r = await listGet(accessToken, "size=3&beforeId=" + cur1);
    const p2 = itemsOf(r) || [];
    const cur2 = r.json.data.nextCursor;
    check("12", "游标第二页与首屏零重叠，且 nextCursor 就是本页末条 id",
      r.status === 200 && p2.length === 3 && cur2 === p2[2].id && p1.every(function (x) { return !p2.some(function (y) { return y.id === x.id; }); }),
      "p1=" + p1.map(function (x) { return x.id; }).join(",") + " p2=" + p2.map(function (x) { return x.id; }).join(","));
    r = await listGet(accessToken, "size=3&beforeId=" + cur2);
    const p3 = itemsOf(r) || [];
    check("12", "连翻三页两两不重叠（页码分页在插新帖时必然重复，游标不会）",
      p3.length === 3 && p3.every(function (x) { return !p1.some(function (y) { return y.id === x.id; }) && !p2.some(function (y) { return y.id === x.id; }); }),
      "p3=" + p3.map(function (x) { return x.id; }).join(","));

    r = await listGet(accessToken, "type=hole&size=50");
    const holes = itemsOf(r) || [];
    check("12", "type=hole 只回树洞", r.status === 200 && holes.length > 0 && holes.every(function (x) { return x.type === "hole"; }), "n=" + holes.length);
    r = await listGet(accessToken, "type=moment");
    check("12", "白名单外的 type → 400/10001（读接口与写接口同口径，不猜「你想看全部」）", r.status === 400 && code(r) === "10001", r.status + " code=" + code(r));
    r = await listGet(null, "");
    check("12", "未登录打列表 → 401/10002（广场也是登录后可见，游客只给话题墙）", r.status === 401 && code(r) === "10002", r.status + " code=" + code(r));

    const anonItem = others.find(function (x) { return x.anonymous === true; });
    check("12", "列表里的匿名帖不回 authorId（抓包反查不到作者），展示名是马甲",
      !!anonItem && (anonItem.authorId === undefined || anonItem.authorId === null)
      && String(anonItem.displayName || "").indexOf("匿名屿民·") === 0,
      JSON.stringify(anonItem ? { displayName: anonItem.displayName, authorId: anonItem.authorId } : null));
    const helpItem = others.find(function (x) { return x.type === "help"; });
    check("12", "求助帖在列表里就带 hotline=12356（卡片不该等到详情页才出现）", !!helpItem && helpItem.hotline === "12356", JSON.stringify(helpItem && helpItem.hotline));
    const crisisItem = others.find(function (x) { return x.type !== "help" && x.hotline === "12356"; });
    check("12", "非求助但 L2/L3 的帖子同样带 hotline（放行打标与给卡片是同一件事）", !!crisisItem, crisisItem ? "id=" + crisisItem.id : "本轮列表里没有 L2/L3 公开帖");
  } else {
    info("12", "账号没注册下来，本步与第 13 步整体跳过", "根因是上面那条注册失败，退出码已由它决定");
  }

  // ---------- 13 浏览量：缓存累加 + 每 5 分钟回写（任务 3.5 最硬的一条取证） ----------
  if (publicId) {
    r = await listGet(accessToken, "size=50");
    const row = (itemsOf(r) || []).find(function (x) { return x.id === publicId; });
    const base = row ? row.viewCnt : -1;
    const shown = [];
    for (let i = 0; i < 3; i++) {
      const d = await detailGet(accessToken, publicId);
      shown.push(d.json && d.json.data ? d.json.data.viewCnt : null);
    }
    check("13", "同一帖连开三次详情：展示值 = 基线 +1/+2/+3，既不倒退也不凭空多",
      base >= 0 && shown[0] === base + 1 && shown[1] === base + 2 && shown[2] === base + 3,
      "base=" + base + " 三次=" + shown.join(","));
    r = await listGet(accessToken, "size=50");
    const lag = (itemsOf(r) || []).find(function (x) { return x.id === publicId; });
    check("13", "三次浏览只发了一条 UPDATE：列表(库值)只比基线多 1，其余留在缓存里", !!lag && lag.viewCnt === base + 1,
      "列表=" + (lag && lag.viewCnt) + " 详情=" + shown[2]);
    const self = await detailGet(seenToken, publicId);
    const selfView = self.json && self.json.data ? self.json.data.viewCnt : null;
    check("13", "作者自看不计数，但展示值与第三人一致（口径可以是「你不算」，数字不能比你小）",
      self.status === 200 && selfView === shown[2], "作者视角=" + selfView + " 第三人第三次=" + shown[2]);
    info("13", "SQL 取证：这一条的 view_cnt 应为 " + (base + 1) + "（三次浏览 / 一次回写）",
      "SELECT view_cnt FROM post WHERE id = " + publicId + ";");
  } else {
    info("13", "没有可用的公开帖，本步跳过（根因在第 12 步）", "");
  }

  // ---------- 14 我的帖子 / 他人主页（任务 3.13 第二批验收主项 · U11/U12 的后端口） ----------
  // 这一整步只用第 12 步已经真实落库的那四条帖：公开、私密、待审、匿名树洞。
  // 四个样本在同一条列表里的进出，就是三个接口全部口径的证明，不需要任何夹具数据。
  const has = function (list, id) { return list.some(function (x) { return x.id === id; }); };
  const idsOf = function (list) { return list.map(function (x) { return x.id; }).join(","); };
  const myPostsGet = function (token, qs) {
    return send("GET", "/api/users/me/posts" + (qs ? "?" + qs : ""),
      token ? { headers: { Authorization: "Bearer " + token } } : {});
  };
  const profileGet = function (token, id, qs) {
    return send("GET", "/api/users/" + id + "/posts" + (qs ? "?" + qs : ""),
      { headers: { Authorization: "Bearer " + token } });
  };
  let anonHoleId = null, authorId = null;
  if (seenToken && privateId && pendingId && publicId) {
    r = await send("GET", "/api/users/me", { headers: { Authorization: "Bearer " + seenToken } });
    authorId = r.json && r.json.data ? r.json.data.id : null;
    check("14", "取到作者自己的 id（主页接口的入参就是它，前端也是这么用的）", !!authorId, "id=" + authorId);

    r = await send("POST", "/api/posts", asToken(seenToken, jsonBody({
      title: "冒烟·匿名树洞", content: "今天不想被任何人认出来。", type: "hole" })));
    anonHoleId = bodyOf(r).id;
    check("14", "作者补发一条匿名树洞：主页用例的负面样本必须自己就是 PUBLISHED，否则「不出现」是白捡的",
      r.status === 200 && bodyOf(r).status === "PUBLISHED" && !!anonHoleId,
      r.status + " status=" + bodyOf(r).status + " id=" + anonHoleId);

    r = await myPostsGet(seenToken, "size=50");
    const myItems = itemsOf(r) || [];
    const missing = [privateId, pendingId, anonHoleId, publicId].filter(function (id) { return !has(myItems, id); });
    check("14", "GET /api/users/me/posts：私密 + 待审 + 匿名 + 公开四条全在列（作者视角绕开公共可见性判据）",
      r.status === 200 && missing.length === 0, "n=" + myItems.length + " 缺=" + missing.join(","));
    const myPending = myItems.find(function (x) { return x.id === pendingId; });
    check("14", "列表回显 status 与 auditTip：前端按状态分 Tab 不必再自己猜哪条在审核",
      !!myPending && myPending.status === "HUMAN_REVIEW" && String(myPending.auditTip || "").length > 0,
      JSON.stringify(myPending || {}).slice(0, 170));
    const myPrivate = myItems.find(function (x) { return x.id === privateId; });
    check("14", "自己的私密帖在列表里看得见 visibility（广场刻意不给的那条，正是 U12 存在的理由）",
      !!myPrivate && myPrivate.visibility === "private", JSON.stringify(myPrivate && myPrivate.visibility));

    r = await myPostsGet(seenToken, "status=PUBLISHED&size=50");
    const pubOnly = itemsOf(r) || [];
    check("14", "?status=PUBLISHED 只剩已发布，且仍含自己的私密已发布（状态过滤不该顺手把可见性也收窄）",
      r.status === 200 && pubOnly.length > 0 && pubOnly.every(function (x) { return x.status === "PUBLISHED"; })
      && has(pubOnly, privateId) && !has(pubOnly, pendingId), "n=" + pubOnly.length);
    r = await myPostsGet(seenToken, "status=human_review&size=50");
    check("14", "?status 大小写不敏感：库里 post.status 存大写 ENUM，前端传小写是打字习惯而不是换语义",
      r.status === 200 && has(itemsOf(r) || [], pendingId), "n=" + (itemsOf(r) || []).length);
    r = await myPostsGet(seenToken, "status=moment");
    check("14", "白名单外的 status → 400/10001（与 type 同口径，不做「那就查全部」的兜底）",
      r.status === 400 && code(r) === "10001", r.status + " code=" + code(r));
    r = await myPostsGet(seenToken, "status=private");
    check("14", "?status=private 被拒：visibility 的值不是 status 的值，两个维度不许混成一个参数",
      r.status === 400 && code(r) === "10001", r.status + " code=" + code(r));
    r = await myPostsGet(null, "");
    check("14", "未登录打 /me/posts → 401/10002（不给匿名打别人主页的口子）",
      r.status === 401 && code(r) === "10002", r.status + " code=" + code(r));
    r = await myPostsGet(seenToken, "size=2");
    const mp1 = itemsOf(r) || [];
    const mc1 = r.json && r.json.data ? r.json.data.nextCursor : null;
    r = await myPostsGet(seenToken, "size=2&beforeId=" + mc1);
    const mp2 = itemsOf(r) || [];
    check("14", "我的列表翻页与广场同一套游标口径（三张列表共用 pageResult，翻页键只有一份）",
      mp1.length === 2 && !!mc1 && mp2.length > 0 && mp2.every(function (x) { return !has(mp1, x.id); }),
      "p1=" + idsOf(mp1) + " p2=" + idsOf(mp2));

    r = await profileGet(accessToken, authorId, "size=50");
    const stranger = itemsOf(r) || [];
    const strangerStatus = r.status;
    r = await profileGet(seenToken, authorId, "size=50");
    const selfView = itemsOf(r) || [];
    check("14", "GET /api/users/{id}/posts：本人视角与外人视角逐条相同（主页就是主页，不因为是你就多给）",
      strangerStatus === 200 && idsOf(stranger) === idsOf(selfView),
      "外人 n=" + stranger.length + "[" + idsOf(stranger) + "] 本人 n=" + selfView.length + "[" + idsOf(selfView) + "]");
    check("14", "公开主页只回 public+PUBLISHED：匿名树洞、私密帖、待审帖整条不出现（FR1.4 防解匿）",
      stranger.length > 0 && has(stranger, publicId) && !has(stranger, anonHoleId)
      && !has(stranger, privateId) && !has(stranger, pendingId)
      && stranger.every(function (x) { return x.visibility === "public" && x.status === "PUBLISHED" && x.anonymous === false; }),
      "n=" + stranger.length + " 含匿名=" + has(stranger, anonHoleId) + " 含私密=" + has(stranger, privateId));
    r = await profileGet(accessToken, 99999999, "");
    check("14", "不存在的用户主页 → 404/20001：不存在与已注销同码，区分本身就是一条枚举通道",
      r.status === 404 && code(r) === "20001", r.status + " code=" + code(r));
    r = await profileGet(accessToken, "abc", "");
    check("14", "非数字 id → 404/90006 而不是 500：路径上的数字约束挡住了 long 转换异常",
      r.status === 404 && code(r) === "90006", r.status + " code=" + code(r) + " " + short(r, 120));
    r = await send("GET", "/api/users/me/profile", { headers: { Authorization: "Bearer " + accessToken } });
    check("14", "/me/posts 没有把 /me/profile 挤掉：两条字面量路径照常 200（消歧靠声明，不靠解析器优先级）",
      r.status === 200 && code(r) === "0", r.status + " code=" + code(r));
    info("14", "SQL 取证：这三条 id 应分别落在 私密/待审/匿名 三种行上",
      "SELECT id,status,visibility,is_anonymous,alias_id FROM post WHERE id IN (" + [privateId, pendingId, anonHoleId, publicId].join(",") + ");");
  } else {
    info("14", "第 12 步的三条样本没备齐，本步整体跳过（根因在上面，不逐条报 401）", "");
  }

  // ---------- 15 点赞·收藏·关注（任务 3.6 · 需求 FR4.4、FR4.6、BR2、BR4、BR6）----------
  // 这一步是本项目第一次「跑完就能用 SQL 反证」的写侧冒烟：每一条 check 之后，
  // post.like_cnt 都必须等于 post_like 里的活动人数，跑完由 root 直连 SQL 复核（见 docs/dev-log.md）。
  const actOn = function (token, id, action) {
    return send("POST", "/api/posts/" + id + "/actions",
      token ? asToken(token, jsonBody({ action: action })) : jsonBody({ action: action }));
  };
  const followOne = function (token, id, action) {
    return send("POST", "/api/users/" + id + "/follow",
      token ? asToken(token, jsonBody({ action: action })) : jsonBody({ action: action }));
  };
  const isMsg = function (rr, needle) {
    // 统一响应体是 Result{code,msg,data,traceId}：文案在 msg 字段，不是 Spring 默认的 message。
    const j = rr.json || {};
    return String(j.msg !== undefined ? j.msg : (j.message !== undefined ? j.message : "")).indexOf(needle) >= 0;
  };

  if (publicId && privateId && anonHoleId && authorId && seenToken && accessToken) {
    r = await actOn(accessToken, publicId, "like");
    check("15", "第三人给公开帖点赞 → 200 + changed=true + likeCnt=1",
      r.status === 200 && bodyOf(r).changed === true && bodyOf(r).liked === true && bodyOf(r).likeCnt === 1,
      r.status + " " + short(r, 170));

    r = await actOn(accessToken, publicId, "like");
    check("15", "BR2 正向幂等：连点第二次不报错但 changed=false、likeCnt 仍是 1（一个人只能算一票）",
      r.status === 200 && bodyOf(r).changed === false && bodyOf(r).likeCnt === 1, r.status + " " + short(r, 170));

    r = await detailGet(accessToken, publicId);
    check("15", "详情把互动态回给前端：liked=true、collected=false（PostCard 不必自己猜状态）",
      r.status === 200 && bodyOf(r).liked === true && bodyOf(r).collected === false && bodyOf(r).likeCnt === 1,
      "liked=" + bodyOf(r).liked + " collected=" + bodyOf(r).collected + " likeCnt=" + bodyOf(r).likeCnt);

    r = await actOn(accessToken, publicId, "unlike");
    check("15", "取消点赞 → changed=true、likeCnt 归 0",
      r.status === 200 && bodyOf(r).changed === true && bodyOf(r).liked === false && bodyOf(r).likeCnt === 0,
      r.status + " " + short(r, 170));

    r = await actOn(accessToken, publicId, "unlike");
    check("15", "BR2 负向幂等：取消一个没赞过的帖 200 而不是 400（前端双击/网络重发都不是错误）",
      r.status === 200 && bodyOf(r).changed === false && bodyOf(r).likeCnt === 0, r.status + " " + short(r, 170));

    r = await actOn(accessToken, publicId, "like");
    check("15", "取消之后再赞：复用同一行、计数只回到 1（不是 2）",
      r.status === 200 && bodyOf(r).likeCnt === 1, r.status + " likeCnt=" + bodyOf(r).likeCnt);

    r = await actOn(accessToken, publicId, "collect");
    check("15", "收藏与点赞各算各的：collectCnt=1 且 liked 依旧 true",
      r.status === 200 && bodyOf(r).collected === true && bodyOf(r).collectCnt === 1 && bodyOf(r).liked === true,
      r.status + " " + short(r, 170));

    r = await actOn(accessToken, publicId, "uncollect");
    check("15", "取消收藏不许把赞一起取消（两个动作同表不同 action_type，判据只认自己那一档）",
      r.status === 200 && bodyOf(r).collected === false && bodyOf(r).collectCnt === 0 && bodyOf(r).liked === true,
      r.status + " " + short(r, 170));

    r = await actOn(seenToken, publicId, "like");
    check("15", "BR4 自赞：允许且计入 like_cnt（=2），但回执标 selfAction=true 交给 T3.10 埋点去排除",
      r.status === 200 && bodyOf(r).selfAction === true && bodyOf(r).likeCnt === 2,
      r.status + " " + short(r, 170));

    r = await listGet(accessToken, "size=50");
    const likedRow = (itemsOf(r) || []).find(function (x) { return x.id === publicId; }) || {};
    check("15", "广场列表同样回 liked/collectCnt：卡片上的红心态刷新前后不能变白",
      r.status === 200 && likedRow.liked === true && likedRow.likeCnt === 2 && likedRow.collectCnt === 0,
      JSON.stringify({ liked: likedRow.liked, likeCnt: likedRow.likeCnt, collectCnt: likedRow.collectCnt }));

    r = await actOn(accessToken, anonHoleId, "like");
    const anonDetail = await detailGet(accessToken, anonHoleId);
    check("15", "匿名树洞照样能赞，且赞完之后 authorId 依旧不回（互动不成为解匿通道）",
      r.status === 200 && bodyOf(r).likeCnt === 1 && anonDetail.status === 200
      && anonDetail.json.data.anonymous === true && anonDetail.json.data.authorId == null,
      "likeCnt=" + bodyOf(r).likeCnt + " anonymous=" + (anonDetail.json.data || {}).anonymous
      + " authorId=" + String((anonDetail.json.data || {}).authorId));

    r = await actOn(accessToken, publicId, "hug");
    check("15", "白名单外的动作 → 400/10001，文案按字典序列出四个取值（Set.of 顺序随机，必须 sorted）",
      r.status === 400 && code(r) === "10001" && isMsg(r, "collect / like / uncollect / unlike"),
      r.status + " code=" + code(r) + " msg=" + ((r.json && r.json.msg) || (r.json && r.json.message)));

    r = await actOn(accessToken, "abc", "like");
    check("15", "非数字帖子 id → 404/90006 而不是 500", r.status === 404 && code(r) === "90006",
      r.status + " code=" + code(r));

    r = await actOn(accessToken, privateId, "like");
    check("15", "别人的私密帖点赞 → 404/30001（与详情同口径：报 403 就是免费的存在性枚举通道）",
      r.status === 404 && code(r) === "30001", r.status + " code=" + code(r));

    r = await actOn(null, publicId, "like");
    check("15", "未登录点赞 → 401/10002（发起人只来自 JWT，不接受请求体里的 user_id）",
      r.status === 401 && code(r) === "10002", r.status + " code=" + code(r));

    r = await followOne(accessToken, authorId, "follow");
    check("15", "第三人关注作者 → 200 + following=true + followerCnt=1",
      r.status === 200 && bodyOf(r).changed === true && bodyOf(r).following === true && bodyOf(r).followerCnt === 1,
      r.status + " " + short(r, 170));

    r = await followOne(accessToken, authorId, "follow");
    check("15", "重复关注幂等：changed=false、followerCnt 仍是 1、不报错",
      r.status === 200 && bodyOf(r).changed === false && bodyOf(r).followerCnt === 1, r.status + " " + short(r, 170));

    r = await send("GET", "/api/users/" + authorId + "/profile", { headers: { Authorization: "Bearer " + accessToken } });
    const card = bodyOf(r);
    check("15", "GET /api/users/{id}/profile：following=true + publicPostCnt=1（私密/待审/匿名三条都不算进来）",
      r.status === 200 && card.following === true && card.self === false && card.publicPostCnt === 1
      && card.followerCnt === 1, r.status + " " + short(r, 200));
    check("15", "资料卡获赞数只算公开非匿名帖：公开那条 2 赞（第三人 + 作者自赞），树洞那 1 赞不算",
      card.receivedLikeCnt === 2, "receivedLikeCnt=" + card.receivedLikeCnt);
    check("15", "展示名与帖子同一口径（同昵称回登录名），bio 缺行回空串而不是 null",
      typeof card.displayName === "string" && card.displayName.length > 0 && typeof card.bio === "string",
      "displayName=" + card.displayName + " bio=" + JSON.stringify(card.bio));

    r = await send("GET", "/api/users/" + authorId + "/profile", { headers: { Authorization: "Bearer " + seenToken } });
    check("15", "本人视角：self=true 且 following=false（不存在「关注自己」这种关系）",
      r.status === 200 && bodyOf(r).self === true && bodyOf(r).following === false, r.status + " " + short(r, 170));

    r = await followOne(accessToken, authorId, "unfollow");
    check("15", "取关 → changed=true、followerCnt 归 0（物理删，不留软删行撞 uk_follow_pair）",
      r.status === 200 && bodyOf(r).changed === true && bodyOf(r).following === false && bodyOf(r).followerCnt === 0,
      r.status + " " + short(r, 170));

    r = await followOne(accessToken, authorId, "unfollow");
    check("15", "取关一个没关注的人：幂等不报错",
      r.status === 200 && bodyOf(r).changed === false, r.status + " " + short(r, 170));

    r = await followOne(seenToken, authorId, "follow");
    check("15", "关注自己 → 400/10001「不能关注自己」（参数没有语义，不是权限问题所以不报 403）",
      r.status === 400 && code(r) === "10001" && isMsg(r, "不能关注自己"), r.status + " msg=" + r.json.msg);

    r = await followOne(accessToken, 99999999, "follow");
    check("15", "关注不存在的人 → 404/20001", r.status === 404 && code(r) === "20001", r.status + " code=" + code(r));
    r = await followOne(accessToken, "abc", "follow");
    check("15", "非数字用户 id → 404/90006", r.status === 404 && code(r) === "90006", r.status + " code=" + code(r));
    r = await followOne(accessToken, authorId, "poke");
    check("15", "非法关注动作 → 400/10001 且文案 sorted", r.status === 400 && code(r) === "10001"
      && isMsg(r, "follow / unfollow"), r.status + " msg=" + r.json.msg);

    info("15", "本步落库后的不变式（跑完用 root 直连 SQL 复核，不靠注释自证）",
      "SELECT p.id,p.like_cnt,(SELECT COUNT(DISTINCT user_id) FROM post_like l WHERE l.target_type='post'"
      + " AND l.target_id=p.id AND l.action_type='LIKE' AND l.deleted=0) AS truth FROM post p WHERE p.id IN ("
      + publicId + "," + anonHoleId + ");");
  } else {
    info("15", "互动样本没备齐，本步整体跳过（根因在上面，不逐条报 401）", "");
  }

  // ---------- 16 评论与楼中楼（任务 T3.7 · 需求 FR4.3、FR4.4、FR7.3、BR1、BR4、BR6）----------
  // 这一步只验「接线」：两级压平的两个 id 到底落没落对、待审评论在别人的查询里到底出不出现、
  // comment_cnt 与 comment 表真相是否一致。规则分支本身由 CommentServiceTest 的 43 条钉死，两边不重复。
  // 本脚本一步就能把「60 次/分」的全局限流（T2.17）打满，而它和评论配额是两件事：
  // 不加这层保护，第 16 步测出来的 429 到底是 30003 配额还是 10010 限流都分不清。
  // 所以每条请求都带上「剩余配额不足就等下一个 60s 窗口」的自愈逻辑——这是脚本的问题，不该记到接口头上。
  const waitNextBucket = function () {
    const ms = 60000 - (Date.now() % 60000) + 300;
    info("16", "全局限流窗口将满，等 " + Math.ceil(ms / 1000) + "s 进下一个 60s 窗口再继续", "");
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  };
  const rlSafe = async function (make) {
    for (let attempt = 0; attempt < 4; attempt++) {
      const rr = await make();
      if (code(rr) === "10010") { await waitNextBucket(); continue; }
      const left = rr.headers ? rr.headers["x-ratelimit-remaining"] : undefined;
      if (left !== undefined && Number(left) <= 3) { await waitNextBucket(); }
      return rr;
    }
    return make();
  };
  const cAdd = function (token, postId, body) {
    const opts = token ? asToken(token, jsonBody(body)) : jsonBody(body);
    return rlSafe(function () { return send("POST", "/api/posts/" + postId + "/comments", opts); });
  };
  const cList = function (token, postId, qs) {
    const opts = token ? { headers: { Authorization: "Bearer " + token } } : {};
    return rlSafe(function () {
      return send("GET", "/api/posts/" + postId + "/comments" + (qs ? "?" + qs : ""), opts);
    });
  };
  const cmt = function (rr) { return bodyOf(rr).comment || {}; };
  const tipHas = function (value, needle) { return String(value || "").indexOf(needle) >= 0; };
  let anonUsedByThird = 0;
  if (publicId && anonHoleId && privateId && seenToken && accessToken) {
    const cntBefore = bodyOf(await rlSafe(function () { return detailGet(accessToken, publicId); })).commentCnt;

    r = await cAdd(accessToken, publicId, { content: "  先抱抱楼主，写得真好  " });
    const rootC = cmt(r);
    check("16", "第三人发一级评论 → 200/PUBLISHED：落库的是 trim 过的正文，parentId 与 rootId 都是 null",
      r.status === 200 && code(r) === "0" && !!rootC.id && rootC.status === "PUBLISHED"
      && rootC.content === "先抱抱楼主，写得真好" && rootC.parentId == null && rootC.rootId == null,
      r.status + " " + short(r, 200));

    r = await cAdd(seenToken, publicId, { content: "谢谢你的拥抱", parentId: rootC.id });
    const reply1 = cmt(r);
    check("16", "回复一级评论：parent_id 与 root_id 都指向它，回执 replyToName 用被回复者的展示名（不是登录名）",
      r.status === 200 && !!reply1.id && reply1.parentId === rootC.id && reply1.rootId === rootC.id
      && reply1.replyToName === rootC.authorName,
      "parentId=" + reply1.parentId + " rootId=" + reply1.rootId + " replyToName=" + reply1.replyToName);

    r = await cAdd(accessToken, publicId, { content: "那我也插一句", parentId: reply1.id });
    const reply2 = cmt(r);
    check("16", "回复楼中楼：parent_id 指向真正被回复的那条，root_id 沿用一级评论——页面因此只有两层缩进",
      r.status === 200 && !!reply2.id && reply2.parentId === reply1.id && reply2.rootId === rootC.id,
      "parentId=" + reply2.parentId + " rootId=" + reply2.rootId);

    r = await cList(accessToken, publicId, "size=50");
    const threads = bodyOf(r).list || [];
    const tree = threads.find(function (t) { return t.root && t.root.id === rootC.id; }) || {};
    check("16", "GET 评论列表：一级评论分页，子树预览 2 条、replyTotal 也是 2（预览数与真相数在这一刻相等）",
      r.status === 200 && code(r) === "0" && !!tree.root && tree.replies.length === 2 && tree.replyTotal === 2,
      r.status + " total=" + bodyOf(r).total + " n=" + threads.length);

    const cntAfter = bodyOf(await rlSafe(function () { return detailGet(accessToken, publicId); })).commentCnt;
    check("16", "写侧不变式：详情 commentCnt = 之前的值 + 本次公开的 3 条（冗余列按真相重算，不是 += 1 累加）",
      cntAfter === cntBefore + 3, cntBefore + " -> " + cntAfter);

    r = await cAdd(accessToken, anonHoleId, { content: "我也有过这种时候", anonymous: true });
    const anonC = cmt(r);
    anonUsedByThird++;
    check("16", "匿名评论：回执 authorId=null、展示名是马甲名（前缀 匿名屿民·），绝不回真实昵称",
      r.status === 200 && anonC.authorId == null && anonC.anonymous === true
      && String(anonC.authorName).indexOf("匿名屿民·") === 0,
      JSON.stringify({ authorId: anonC.authorId, authorName: anonC.authorName }));

    r = await cAdd(accessToken, anonHoleId, { content: "有事直接找我13800138000", anonymous: true });
    const masked = cmt(r);
    anonUsedByThird++;
    check("16", "匿名评论里的手机号落库前就被遮掉且 tip 说明原因：字数不变（换成 ＊），身份也不泄露",
      r.status === 200 && String(masked.content).indexOf("13800138000") < 0
      && masked.content.length === "有事直接找我13800138000".length && tipHas(bodyOf(r).tip, "联系方式"),
      "content=" + masked.content);

    r = await cAdd(accessToken, publicId, { content: "实名就不用遮：有事找我13900139000" });
    const namedC = cmt(r);
    check("16", "实名评论不遮联系方式（口径与匿名帖一致：作者本来就露着身份，遮它属于越权改内容）",
      r.status === 200 && cmt(r).content === "实名就不用遮：有事找我13900139000",
      "content=" + cmt(r).content + " status=" + cmt(r).status);

    r = await cAdd(seenToken, publicId, { content: "你就是个傻逼" });
    const pendingC = cmt(r);
    check("16", "灰词评论转人审：HTTP 仍 200、status=PENDING、tip 是人话（与发帖同一契约，不是 4xx）",
      r.status === 200 && pendingC.status === "PENDING" && tipHas(bodyOf(r).tip, "人工审核"),
      r.status + " status=" + pendingC.status);

    const asOther = bodyOf(await cList(accessToken, publicId, "size=50"));
    const asAuthor = bodyOf(await cList(seenToken, publicId, "size=50"));
    const inTree = function (page, id) {
      return (page.list || []).some(function (t) {
        return t.root.id === id || (t.replies || []).some(function (x) { return x.id === id; });
      });
    };
    // 一级评论与楼中楼回复在同一次返回里是两层结构，判「某条评论出不出现」必须两层都看，
    // 只看 replies 会把「它其实是以一级评论出现的」误判成不可见（本脚本第一版就踩在这）。
    const rowOf = function (page, id) {
      const all = [];
      (page.list || []).forEach(function (t) {
        all.push(t.root);
        (t.replies || []).forEach(function (x) { all.push(x); });
      });
      return all.find(function (x) { return x.id === id; }) || {};
    };
    const authorRow = rowOf(asAuthor, pendingC.id);
    check("16", "FR7.3 待审评论只对作者可见：第三人那次查询里根本没有这一条，作者视角带「审核中，仅自己可见」",
      inTree(asOther, rootC.id) && !inTree(asOther, pendingC.id) && inTree(asAuthor, pendingC.id)
      && authorRow.auditTip === "审核中，仅自己可见" && authorRow.status === "PENDING",
      "otherTotal=" + asOther.total + " authorTotal=" + asAuthor.total + " auditTip=" + authorRow.auditTip);

    check("16", "同一条规则对另一个人同样成立：第三人自己那条待审评论（留手机号被灰词转人审）在他自己视角带提示、在楼主视角整条不出现",
      inTree(asOther, namedC.id) && !inTree(asAuthor, namedC.id)
      && rowOf(asOther, namedC.id).auditTip === "审核中，仅自己可见" && rowOf(asOther, namedC.id).status === "PENDING",
      "namedId=" + namedC.id + " otherTotal=" + asOther.total + " authorTotal=" + asAuthor.total);

    r = await cAdd(seenToken, publicId, { content: "那我回复自己这条", parentId: pendingC.id });
    check("16", "待审评论不能当父级（哪怕是自己发的那条）：放行就会造出别人看不见的孤儿子树",
      r.status === 400 && code(r) === "10001" && isMsg(r, "不能被回复"),
      r.status + " code=" + code(r) + " msg=" + (r.json && r.json.msg));

    r = await cAdd(seenToken, publicId, { content: "想伤害自己，可是看到这条社区还在", parentId: rootC.id });
    check("16", "FR10.3 评论里的危机词走同一套分级：放行不删、hotline=12356、工单建不建由 SQL 取证复核",
      r.status === 200 && cmt(r).status === "PUBLISHED" && bodyOf(r).hotline === "12356",
      "hotline=" + bodyOf(r).hotline + " status=" + cmt(r).status);

    r = await cAdd(accessToken, publicId, { content: "   " });
    check("16", "纯空白评论 → 400/10001「评论内容不能为空」：先拒再落库，不靠 NOT NULL 约束兜底",
      r.status === 400 && code(r) === "10001" && isMsg(r, "不能为空"), r.status + " msg=" + (r.json && r.json.msg));

    r = await cAdd(accessToken, publicId, { content: "啊".repeat(1001) });
    check("16", "FR4.4 超 1000 字 → 400/10001 且文案带上限数字",
      r.status === 400 && code(r) === "10001" && isMsg(r, "1000"), r.status + " msg=" + (r.json && r.json.msg));

    r = await cAdd(accessToken, publicId, { content: "回复一个不存在的父级", parentId: 99999999 });
    check("16", "父级不存在 → 400/10001「要回复的评论不存在或已被删除」：越帖与不存在同一句话",
      r.status === 400 && code(r) === "10001" && isMsg(r, "不存在或已被删除"),
      r.status + " code=" + code(r) + " msg=" + (r.json && r.json.msg));

    r = await cAdd(accessToken, privateId, { content: "这条根本不该写进去" });
    check("16", "FR7.3 别人的私密帖不能评论 → 404/30001，与详情同口径：评论接口不是存在性枚举通道",
      r.status === 404 && code(r) === "30001", r.status + " code=" + code(r));

    r = await cList(accessToken, privateId, "");
    check("16", "看不见的帖子也拿不到评论列表 → 404/30001（判帖在前，评论 id 轮不到说话）",
      r.status === 404 && code(r) === "30001", r.status + " code=" + code(r));

    r = await cList(accessToken, publicId, "rootId=" + reply1.id);
    check("16", "rootId 传一条楼中楼回复 → 400/10001：展开只认一级评论，不许拿子树中间节点当入口",
      r.status === 400 && code(r) === "10001", r.status + " code=" + code(r) + " msg=" + (r.json && r.json.msg));

    r = await cAdd(null, publicId, { content: "游客也要说话" });
    check("16", "未登录评论 → 401/10002：发起人只来自 JWT，不接受请求体里的 user_id",
      r.status === 401 && code(r) === "10002", r.status + " code=" + code(r));

    r = await cList(null, publicId, "");
    check("16", "未登录取评论列表 → 401/10002：可见性判据里有登录身份，这条接口天然不能对游客开放",
      r.status === 401 && code(r) === "10002", r.status + " code=" + code(r));

    let quotaHit = 0, quotaStatus = 0, quotaAccepted = 0;
    for (let i = 1; i <= 30; i++) {
      const q = await cAdd(accessToken, anonHoleId, { content: "配额验证第" + i + "条" });
      if (code(q) === "30003") { quotaHit = i; quotaStatus = q.status; break; }
      if (q.status === 200) { quotaAccepted++; anonUsedByThird++; }
    }
    check("16", "BR4 单用户单帖每自然日 20 条：第 " + (anonUsedByThird + 1) + " 次尝试返 429/30003，配额是缓存计数不是库表",
      quotaStatus === 429 && quotaHit > 0 && anonUsedByThird === 20,
      "accepted_before=" + (20 - quotaAccepted) + " loop_accepted=" + quotaAccepted + " status=" + quotaStatus);

    info("16", "SQL 取证（跑完用 root 直连复核，别信脚本自证）：两个 id 的压平关系、comment_cnt 全表不变式、危机工单",
      "SELECT id,post_id,parent_id,root_id,status,is_anonymous,alias_id FROM comment WHERE post_id IN ("
      + publicId + "," + anonHoleId + ") ORDER BY id; "
      + "SELECT COUNT(*) AS bad FROM post p JOIN (SELECT post_id,COUNT(*) c FROM comment WHERE status='PUBLISHED'"
      + " AND deleted=0 GROUP BY post_id) t ON t.post_id=p.id WHERE p.comment_cnt<>t.c;"
      + " SELECT id,source_type,source_id,`level`,risk_score,sla_at FROM alert_ticket ORDER BY id DESC LIMIT 5;");
  } else {
    info("16", "第 12/14 步的样本帖没备齐，本步整体跳过（根因在上面，不逐条报 401）", "");
  }

  // ---------- 17 举报（任务 3.11 · 需求 FR4.7）----------
  // 三件事只有真 HTTP + 真 MySQL 能证明，单测替代不了：
  // ① 重复举报撞的是 uk_reporter_target，不新增行，post.report_cnt 也不该被顺手抬一次；
  // ② 阈值按「不同举报人数」算，满 3 人就 CAS 转 HUMAN_REVIEW：第三人立刻 404，作者自己照常可读；
  // ③ self-harm 只给求助卡片与一张 L3 待审任务，不建 alert_ticket、不改被举报帖的状态。
  const repOn = function (token, id, body) {
    const opts = token ? asToken(token, jsonBody(body)) : jsonBody(body);
    return rlSafe(function () { return send("POST", "/api/posts/" + id + "/report", opts); });
  };
  const regAccount = async function (name, nick) {
    const rr = await rlSafe(function () {
      return send("POST", "/api/auth/register", jsonBody({
        username: name, password: "Smoke#2026x", nickname: nick,
        captchaId: captchaId || "00000000000000000000000000000000", captchaCode: "ZZZZ",
        agreeTerms: true, agreePrivacy: true, consentVersion: "v1.0", regSource: "smoke-script"
      }));
    });
    return rr.json && rr.json.data ? rr.json.data.accessToken : null;
  };
  if (publicId && privateId && anonHoleId && seenToken && accessToken) {
    const repA = await regAccount("smoke_rep_a" + stamp, "冒烟举报甲");
    const repB = await regAccount("smoke_rep_b" + stamp, "冒烟举报乙");
    check("17", "注册两名举报人：阈值按人数算，同一个人刷一百次也不该把帖子推去人审",
      !!repA && !!repB, "a=" + !!repA + " b=" + !!repB);

    r = await repOn(null, publicId, { reason: "spam" });
    check("17", "未登录举报 → 401/10002：举报人只来自 JWT，请求体里没有 reporter_id 这种东西",
      r.status === 401 && code(r) === "10002", r.status + " code=" + code(r));

    r = await repOn(accessToken, publicId, { reason: "porn" });
    check("17", "理由不在六类白名单 → 400/10001，且文案把六个码整串列出（前端下拉与后端白名单同源，不各写一份）",
      r.status === 400 && code(r) === "10001" && isMsg(r, "self-harm") && isMsg(r, "spam"),
      r.status + " msg=" + (r.json && r.json.msg));

    r = await repOn(accessToken, publicId, { reason: "abuse", description: "啊".repeat(201) });
    check("17", "描述 201 字 → 400/10001 且文案带 200：先拒再落库，不靠列宽静默截断",
      r.status === 400 && code(r) === "10001" && isMsg(r, "200"), r.status + " msg=" + (r.json && r.json.msg));

    r = await repOn(accessToken, publicId, { reason: "abuse", evidenceUrls: ["https://evil.example.com/a.png"] });
    check("17", "证据给站外链 → 400/10001 且点名 /uploads/：这个字段只认本站上传回执，不是把服务当图床的口子",
      r.status === 400 && code(r) === "10001" && isMsg(r, "/uploads/"), r.status + " msg=" + (r.json && r.json.msg));

    r = await repOn(accessToken, privateId, { reason: "spam" });
    check("17", "举报别人的私密帖 → 404/30001，与详情/评论同口径：举报接口不是存在性枚举通道",
      r.status === 404 && code(r) === "30001", r.status + " code=" + code(r));

    r = await repOn(accessToken, 99999999, { reason: "spam" });
    check("17", "举报不存在的帖子 → 404/30001，与「别人的私密帖」同一句话（能区分就等于能探测）",
      r.status === 404 && code(r) === "30001", r.status + " code=" + code(r));

    r = await repOn(seenToken, publicId, { reason: "spam" });
    check("17", "作者举报自己的帖 → 400/10001「不能举报自己的内容」：删除权本来就在自己手里，不必绕举报下架",
      r.status === 400 && code(r) === "10001" && isMsg(r, "不能举报自己"), r.status + " msg=" + (r.json && r.json.msg));

    r = await repOn(accessToken, publicId, { reason: "spam", description: "满屏外链引流" });
    const rv1 = bodyOf(r);
    check("17", "首次举报 → 200 + duplicated=false + reportCnt=1 + escalated=false + auditTaskId 非空（FR4.7 举报即建待审任务的物证）",
      r.status === 200 && code(r) === "0" && rv1.duplicated === false && rv1.reportCnt === 1
      && rv1.escalated === false && !!rv1.auditTaskId && rv1.reasonLabel === "广告" && rv1.autoReviewThreshold === 3,
      r.status + " " + short(r, 220));
    check("17", "非 self-harm 的回执不带 hotline（hotline 非空 = 前端必须挂求助卡片，全站唯一判据）",
      rv1.hotline === undefined, "hotline=" + rv1.hotline);

    r = await repOn(accessToken, publicId, { reason: "abuse", description: "换个理由再来一次" });
    const rv2 = bodyOf(r);
    check("17", "同一人重复举报 → 200 + duplicated=true + reportCnt 仍是 1：唯一键挡住新增，换理由也不重开一条（BR2 不叠加权重）",
      r.status === 200 && code(r) === "0" && rv2.duplicated === true && rv2.reportCnt === 1,
      r.status + " " + short(r, 220));
    check("17", "重复举报的文案说「不用重复举报」，而不是把「举报已提交」再许诺一遍",
      String(rv2.tip || "").indexOf("重复举报") >= 0 && String(rv2.tip || "").indexOf("举报已提交") < 0,
      "tip=" + rv2.tip);
    check("17", "重复举报仍回本次提交的理由（回执描述这次请求），工单号沿用首张（同一内容同时只有一张待审单）",
      rv2.reason === "abuse" && rv2.auditTaskId === rv1.auditTaskId,
      "reason=" + rv2.reason + " taskId=" + rv2.auditTaskId + " 首张=" + rv1.auditTaskId);

    r = await repOn(repA, publicId, { reason: "abuse" });
    const rv3 = bodyOf(r);
    check("17", "第二名举报人 → reportCnt=2、escalated=false：还差一个人，帖子此刻仍在广场上",
      r.status === 200 && rv3.duplicated === false && rv3.reportCnt === 2 && rv3.escalated === false,
      r.status + " " + short(r, 200));
    r = await rlSafe(function () { return detailGet(repA, publicId); });
    check("17", "未达阈值时举报人仍能读到原帖：转人审只由人数触发，不由「有人举报过」触发",
      r.status === 200 && code(r) === "0", r.status + " code=" + code(r));

    r = await repOn(repB, publicId, { reason: "privacy" });
    const rv4 = bodyOf(r);
    check("17", "第三名举报人达到阈值 → reportCnt=3 且 escalated=true：FR4.4 的自动转人审真的由 HTTP 触发了一次",
      r.status === 200 && rv4.reportCnt === 3 && rv4.escalated === true, r.status + " " + short(r, 240));
    check("17", "达到阈值的文案说清「已转人工 + 暂时隐藏」，隐藏是事实不是许诺",
      String(rv4.tip || "").indexOf("达到阈值") >= 0 && String(rv4.tip || "").indexOf("暂时") >= 0,
      "tip=" + rv4.tip);
    r = await rlSafe(function () { return detailGet(repA, publicId); });
    check("17", "转人审之后第三人读它 → 404/30001：与别人的待审帖逐字一致，「暂时隐藏」是真的隐藏",
      r.status === 404 && code(r) === "30001", r.status + " code=" + code(r));
    r = await rlSafe(function () { return detailGet(seenToken, publicId); });
    check("17", "作者本人仍能读到被举报转审的帖并看到 auditTip：先发后审要被感知，不能让人觉得帖凭空消失",
      r.status === 200 && String(bodyOf(r).auditTip || "").indexOf("审核") >= 0,
      r.status + " auditTip=" + bodyOf(r).auditTip);

    r = await repOn(accessToken, anonHoleId, { reason: "self-harm", description: "楼主的话让人担心" });
    const rv5 = bodyOf(r);
    check("17", "举报「自伤风险」→ 200 + hotline=12356 + 提示给求助话术：这条回执的第一读者是看到那句话的人",
      r.status === 200 && code(r) === "0" && rv5.hotline === "12356" && String(rv5.tip || "").indexOf("电话") >= 0,
      r.status + " " + short(r, 240));
    check("17", "提示文案里不出现号码本身（号码只有 hotline 一个出口，两处文案两个号码是迟早的事）",
      String(rv5.tip || "").indexOf("12356") < 0, "tip=" + rv5.tip);
    check("17", "举报 self-harm 不抬等级也不转审：单个人的一条主观判断不该直接决定别人的帖子存亡",
      rv5.reportCnt === 1 && rv5.escalated === false && !!rv5.auditTaskId,
      "cnt=" + rv5.reportCnt + " escalated=" + rv5.escalated + " taskId=" + rv5.auditTaskId);
    r = await rlSafe(function () { return detailGet(repA, anonHoleId); });
    check("17", "被 self-harm 举报的帖子仍对第三人可读：判断对方有没有风险是审核的事，不是举报人的权限",
      r.status === 200 && code(r) === "0", r.status + " code=" + code(r));

    info("17", "SQL 取证（跑完用 root 直连复核，别信脚本自证）：一人一条、report_cnt 与真相表同源、举报不建危机单（评论危机单的 source_id 也是帖 id，必须按 evidence_text 排除）",
      "SELECT id,reporter_id,target_type,target_id,post_id,reason,status FROM content_report WHERE post_id IN ("
      + publicId + "," + anonHoleId + ") ORDER BY id; "
      + "SELECT COUNT(*) AS dup_reporter FROM (SELECT reporter_id,target_type,target_id FROM content_report"
      + " GROUP BY 1,2,3 HAVING COUNT(*) > 1) t; "
      + "SELECT id,report_cnt,status FROM post WHERE id IN (" + publicId + "," + anonHoleId + "); "
      + "SELECT id,target_type,target_id,source,channel,risk_level,status,sla_at FROM audit_task"
      + " WHERE target_type='post' AND target_id IN (" + publicId + "," + anonHoleId + ") ORDER BY id; "
      + "SELECT post_id,from_status,to_status,reason FROM post_status_log WHERE post_id IN ("
      + publicId + "," + anonHoleId + ") ORDER BY id; "
      + "SELECT COUNT(*) AS report_made_ticket FROM alert_ticket WHERE source_id IN ("
      + publicId + "," + anonHoleId + ") AND evidence_text NOT LIKE '%（评论 id=%';");
  } else {
    info("17", "第 12/14 步的样本没备齐，本步整体跳过（根因在上面，不逐条报 401）", "");
  }

  // ---------- 18 站内通知（任务 3.11-b · 需求 FR9.1、FR9.2）----------
  // 单测能钉住「规则」，钉不住「这条 HTTP 路径真的存在、真的落到了收件人身上」。本步只做
  // 四件必须真环境才能证明的事：
  // ① 一次真实互动（赞 / 匿名评论 / 回复 / 关注）之后，收件人 GET /api/notifications 立刻能读回来，
  //    且 unreadCount 是按 (user_id,is_read,id) 覆盖索引 COUNT 出来的真相，不是前端自己累加的；
  // ② 匿名评论触发的通知里只有马甲名——文案一落库就是对外内容，事后没法再脱敏，所以必须在这一层验；
  // ③ 越权：B 拿自己的令牌去点 A 的通知 id，SQL 层 WHERE user_id 让它只能影响 0 行；
  // ④ 重复点「全部已读」是 updated=0 而不是报错，重复点赞也不会刷出第二条通知。
  const ntfList = function (token, qs) {
    const opts = token ? { headers: { Authorization: "Bearer " + token } } : {};
    return rlSafe(function () { return send("GET", "/api/notifications" + (qs ? "?" + qs : ""), opts); });
  };
  const ntfRead = function (token, payload) {
    const opts = token ? asToken(token, jsonBody(payload)) : jsonBody(payload);
    return rlSafe(function () { return send("POST", "/api/notifications/read", opts); });
  };
  const nAct = function (token, id, action) {
    return rlSafe(function () {
      return send("POST", "/api/posts/" + id + "/actions", asToken(token, jsonBody({ action: action })));
    });
  };
  const nFollow = function (token, id, action) {
    return rlSafe(function () {
      return send("POST", "/api/users/" + id + "/follow", asToken(token, jsonBody({ action: action })));
    });
  };
  // short() 要的是「一次响应的壳」（有 .json / .body），本步有好几处要打印的是已经拆出来的 data，
  // 直接喂给它会踩 r.body.toString —— 脚本会整段异常退出，冒烟跑到一半的账还留在库里。
  const nj = function (v) {
    const str = JSON.stringify(v);
    return str && str.length > 220 ? str.slice(0, 220) + "…" : str;
  };
  const nOf = function (page, type) {
    return (page.list || []).filter(function (x) { return x.type === type; });
  };

  const ntA = await regAccount("smoke_ntf_a" + stamp, "冒烟收件人");
  const ntB = await regAccount("smoke_ntf_b" + stamp, "冒烟发件人");
  check("18", "注册收发两名一次性账号：通知必须有真实收件人，前 17 步的夹具（user 23 / post 39-42）不能被本步污染",
    !!ntA && !!ntB, "a=" + !!ntA + " b=" + !!ntB);
  if (ntA && ntB) {
    r = await rlSafe(function () { return send("GET", "/api/users/me", { headers: { Authorization: "Bearer " + ntA } }); });
    const ntAId = bodyOf(r).id;
    r = await rlSafe(function () { return send("GET", "/api/users/me", { headers: { Authorization: "Bearer " + ntB } }); });
    const ntBId = bodyOf(r).id;
    r = await rlSafe(function () {
      return send("POST", "/api/posts", asToken(ntA, jsonBody({ title: "冒烟·通知靶子", content: "这条会被赞、被评论、被回复。" })));
    });
    const ntPostId = bodyOf(r).id;
    check("18", "A 发一条公开帖当靶子（必须 PUBLISHED：待审帖本来就不该给任何人推通知）",
      r.status === 200 && bodyOf(r).status === "PUBLISHED" && !!ntPostId && !!ntAId && !!ntBId,
      r.status + " status=" + bodyOf(r).status + " post=" + ntPostId + " a=" + ntAId + " b=" + ntBId);

    r = await ntfList(ntA, "");
    const p0 = bodyOf(r);
    check("18", "新账号的列表是空的而不是报错：list=[]、unreadCount=0、nextCursor 整个键缺席（Jackson NON_NULL，不是 null）",
      r.status === 200 && Array.isArray(p0.list) && p0.list.length === 0 && p0.unreadCount === 0
      && p0.nextCursor === undefined && p0.hasMore === false, r.status + " " + short(r, 200));

    // 自赞：互动成立（changed=true）但不该给自己发提醒
    r = await nAct(ntA, ntPostId, "like");
    const selfAct = bodyOf(r);
    r = await ntfList(ntA, "");
    const pSelf = bodyOf(r);
    check("18", "A 赞自己的帖：互动照常受理（changed/selfAction 都为 true），但一条通知都不发——自己不需要被提醒",
      r.status === 200 && selfAct.changed === true && selfAct.selfAction === true
      && pSelf.unreadCount === 0 && pSelf.list.length === 0,
      "changed=" + selfAct.changed + " self=" + selfAct.selfAction + " unread=" + pSelf.unreadCount);

    // B 赞 A
    r = await nAct(ntB, ntPostId, "like");
    r = await ntfList(ntA, "");
    const pLike = bodyOf(r);
    const likeRow = nOf(pLike, "like")[0] || {};
    check("18", "B 赞了之后 A 侧立刻读回一条 like：文案是「谁 + 做了什么」，跳转靠 ref_type/ref_id 而不是把帖 id 拼进文案",
      likeRow.title === "冒烟发件人 赞了你的帖子" && likeRow.content === "「冒烟·通知靶子」"
      && likeRow.typeLabel === "赞" && likeRow.refType === "post" && likeRow.refId === ntPostId
      && likeRow.read === false && pLike.unreadCount === 1, r.status + " " + nj(pLike));

    r = await nAct(ntB, ntPostId, "like");
    const dupLike = bodyOf(r);
    r = await ntfList(ntA, "");
    const pDup = bodyOf(r);
    check("18", "同一个 B 再点一次赞：互动层 changed=false，通知一条也没多刷出来（刷第二下的成本不该落在收件人身上）",
      dupLike.changed === false && nOf(pDup, "like").length === 1 && pDup.unreadCount === 1,
      "changed=" + dupLike.changed + " 行数=" + nOf(pDup, "like").length + " unread=" + pDup.unreadCount);

    const unAct = bodyOf(await nAct(ntB, ntPostId, "unlike"));
    r = await ntfList(ntA, "");
    const pUn = bodyOf(r);
    check("18", "B 取消赞：那条「赞了你」不撤回——没有 actor 列就认不出哪一行是他的，而「这件事发生过」不是假话（手册 §14 已记取舍）",
      unAct.changed === true && nOf(pUn, "like").length === 1 && pUn.unreadCount === 1,
      "changed=" + unAct.changed + " like行=" + nOf(pUn, "like").length + " unread=" + pUn.unreadCount);

    // B 匿名评论 A 的实名帖
    r = await cAdd(ntB, ntPostId, { content: "匿名顶一下，不想被认出来", anonymous: true });
    const ntRootC = cmt(r);
    r = await ntfList(ntA, "");
    const pCmt = bodyOf(r);
    const cRow = nOf(pCmt, "comment")[0] || {};
    check("18", "B 匿名评论后 A 收到的通知只写马甲名：昵称一个字都不能出现——文案落库即对外内容，这一层不脱敏就再也没有机会了",
      ntRootC.anonymous === true && !!ntRootC.authorName && cRow.title === ntRootC.authorName + " 评论了你的帖子"
      && cRow.title.indexOf("冒烟发件人") < 0 && cRow.content === "「匿名顶一下，不想被认出来」"
      && cRow.refType === "post" && cRow.refId === ntPostId && pCmt.unreadCount === 2,
      "马甲=" + ntRootC.authorName + " " + nj(cRow));

    // A 回复 B 的评论：收件人是 B，A 自己不该因为「别人在我帖下说话」而多一条
    r = await cAdd(ntA, ntPostId, { content: "谢谢顶帖", parentId: ntRootC.id });
    const myReply = cmt(r);
    r = await ntfList(ntB, "");
    const pReply = bodyOf(r);
    const rpRow = nOf(pReply, "comment")[0] || {};
    r = await ntfList(ntA, "");
    const pAfterReply = bodyOf(r);
    check("18", "A 回复 B 的评论：通知发给被回复的人（「回复了你的评论」），而不是发给帖子作者本人——两条规则在同一个接口里分岔",
      myReply.status === "PUBLISHED" && rpRow.title === "冒烟收件人 回复了你的评论"
      && rpRow.refType === "post" && rpRow.refId === ntPostId && pReply.unreadCount === 1
      && pAfterReply.unreadCount === 2 && nOf(pAfterReply, "comment").length === 1,
      "B侧=" + nj(rpRow) + " A未读=" + pAfterReply.unreadCount);

    r = await nFollow(ntB, ntAId, "follow");
    const fvw = bodyOf(r);
    r = await ntfList(ntA, "");
    const pAll = bodyOf(r);
    const fRow = nOf(pAll, "follow")[0] || {};
    check("18", "B 关注 A → A 收到 follow，ref 指向关注者主页（关注关系本身不匿名，FR4.6）：三类事件在一张表里各就各位",
      fvw.changed === true && fRow.title === "冒烟发件人 关注了你" && fRow.content === "去他的主页看看"
      && fRow.refType === "user" && fRow.refId === ntBId && pAll.unreadCount === 3
      && nOf(pAll, "like").length === 1 && nOf(pAll, "comment").length === 1 && nOf(pAll, "follow").length === 1,
      "changed=" + fvw.changed + " " + nj(fRow) + " unread=" + pAll.unreadCount);
    const idsDesc = pAll.list.map(function (x) { return x.id; });
    check("18", "列表按 id 倒序（新的在前），且不出 actor 列也不出 user_id：匿名者的真实身份没有反查通道",
      idsDesc.length === 3 && idsDesc.every(function (v, i) { return i === 0 || idsDesc[i - 1] > v; })
      && pAll.list.every(function (x) { return x.userId === undefined && x.deleted === undefined; }),
      "ids=" + idsDesc.join(">") + " 标题=" + pAll.list.map(function (x) { return x.title; }).join(" | "));

    r = await ntfList(ntA, "size=2");
    const pg1 = bodyOf(r);
    r = await ntfList(ntA, "size=2&beforeId=" + pg1.nextCursor);
    const pg2 = bodyOf(r);
    const overlap = (pg1.list || []).filter(function (x) {
      return (pg2.list || []).some(function (y) { return y.id === x.id; });
    });
    check("18", "游标翻页不重不漏：第一页 2 条 + hasMore=true + nextCursor=末条 id，第二页 1 条且与第一页无交集",
      r.status === 200 && pg1.list.length === 2 && pg1.hasMore === true
      && pg1.nextCursor === pg1.list[pg1.list.length - 1].id && pg2.list.length === 1
      && pg2.hasMore === false && overlap.length === 0,
      "pg1=" + pg1.list.length + " cursor=" + pg1.nextCursor + " pg2=" + pg2.list.length + " 重叠=" + overlap.length);
    check("18", "每页都带回同一个 unreadCount：红点只有一个数据来源，不必再开一个 unread 接口自相矛盾",
      pg1.unreadCount === 3 && pg2.unreadCount === 3 && pAll.unreadCount === 3,
      pg1.unreadCount + "/" + pg2.unreadCount);

    r = await ntfList(ntA, "size=999");
    check("18", "size 超上限按上限取而不是报错（与帖子列表同一口径）：把参数写错的成本不该由用户付",
      r.status === 200 && code(r) === "0" && (bodyOf(r).list || []).length <= 50,
      r.status + " code=" + code(r) + " n=" + (bodyOf(r).list || []).length);

    const crossId = likeRow.id;
    r = await ntfRead(ntB, { ids: [crossId] });
    const crossView = bodyOf(r);
    check("18", "越权点别人的通知 → 200 但 updated=0：写接口的 WHERE 里带着 user_id，前端传谁都影响不到别人的红点",
      r.status === 200 && code(r) === "0" && crossView.updated === 0 && crossView.unreadCount === 1
      && crossView.all === false, r.status + " " + short(r, 200));
    r = await ntfList(ntA, "");
    check("18", "上一步之后 A 的未读仍是 3：updated=0 是真的没动，不是「动了但回执撒谎」",
      bodyOf(r).unreadCount === 3 && nOf(bodyOf(r), "like")[0].read === false,
      "unread=" + bodyOf(r).unreadCount);

    r = await ntfRead(ntB, { all: true });
    const bAll = bodyOf(r);
    check("18", "B 一键已读只清自己的（updated=1 且自己归零），A 的一条没被带走：两个账号在同一张表里互不越界",
      bAll.updated === 1 && bAll.unreadCount === 0 && bAll.all === true, r.status + " " + short(r, 200));

    r = await ntfRead(ntA, {});
    check("18", "两个字段都不给 → 400/10001：把「漏传字段」当成「全部已读」是最坏的一种宽容",
      r.status === 400 && code(r) === "10001" && isMsg(r, "不能为空"), r.status + " msg=" + (r.json && r.json.msg));
    const tooMany = [];
    for (let i = 0; i < 101; i += 1) { tooMany.push(900000000 + i); }
    r = await ntfRead(ntA, { ids: tooMany });
    check("18", "一次给 101 个 id → 400/10001 且文案带 100：批量上限挡的是拿 id 遍历攻击，不是给用户添堵",
      r.status === 400 && code(r) === "10001" && isMsg(r, "100"), r.status + " msg=" + (r.json && r.json.msg));

    r = await ntfRead(ntA, { ids: [crossId] });
    const oneRead = bodyOf(r);
    check("18", "A 点掉自己那一条 → updated=1、未读数当场从 3 变 2：回执直接给真相，前端不必再请求一次列表",
      oneRead.updated === 1 && oneRead.unreadCount === 2 && oneRead.all === false, r.status + " " + short(r, 200));
    r = await ntfRead(ntA, { ids: [crossId] });
    check("18", "同一条再点一次 → updated=0 而不是报错：SQL 里那句 AND is_read=0 就是为连点两下准备的",
      bodyOf(r).updated === 0 && bodyOf(r).unreadCount === 2, r.status + " " + short(r, 200));

    r = await ntfRead(ntA, { all: true });
    const allRead = bodyOf(r);
    r = await ntfList(ntA, "");
    const pRead = bodyOf(r);
    check("18", "一键已读：updated=2（只数真的从未读变已读的）→ 未读归零、列表三条全 read=true 而条数不变",
      allRead.updated === 2 && allRead.unreadCount === 0 && allRead.all === true
      && pRead.list.length === 3 && pRead.list.every(function (x) { return x.read === true; })
      && pRead.unreadCount === 0, "updated=" + allRead.updated + " " + nj(pRead));
    r = await ntfRead(ntA, { all: true });
    check("18", "重复一键已读 → updated=0：幂等，第二下不产生任何写",
      bodyOf(r).updated === 0 && bodyOf(r).unreadCount === 0, r.status + " " + short(r, 200));

    r = await ntfList(null, "");
    check("18", "未登录读通知 → 401/10002：收件人只来自 JWT，这个接口没有「看别人通知」的参数",
      r.status === 401 && code(r) === "10002", r.status + " code=" + code(r));
    r = await ntfRead(null, { all: true });
    check("18", "未登录标已读 → 401/10002：写接口同样不认请求体里的身份",
      r.status === 401 && code(r) === "10002", r.status + " code=" + code(r));

    info("18", "SQL 取证（root 直连复核，别信脚本自证）：落库形状 / 查重 / is_read 与 read_at 同起同落 / 匿名不泄漏。"
      + "四条谓词全部实测跑过（数字见手册 §6.1 末 v1.2.1 回写）：本轮 window=1 行、this_round=0、control=1、dup=0。"
      + "判据口径：每条「应为 0」都必须配一条「应 >0」的正面控制，否则恒真的 0 不算证据。",
      "SELECT id,user_id,type,title,content,ref_type,ref_id,is_read,read_at,created_at FROM notify_message"
      + " WHERE user_id IN (" + ntAId + "," + ntBId + ") ORDER BY id; "
      // 查重的口径只有一条与实现对得上：文案 + 跳转对象全等才算重复。
      // 只按 (user_id,type,title) 分组会捞出一堆假阳性（本轮 9 组，同一个人给不同帖子点两次赞本来就合法）。
      + "SELECT COUNT(*) AS dup_same_everything FROM (SELECT user_id,type,ref_type,ref_id,title,content"
      + " FROM notify_message WHERE deleted=0 GROUP BY 1,2,3,4,5,6 HAVING COUNT(*) > 1) t; "
      + "SELECT COUNT(*) AS read_without_at FROM notify_message WHERE is_read=1 AND read_at IS NULL; "
      + "SELECT COUNT(*) AS unread_with_at FROM notify_message WHERE is_read=0 AND read_at IS NOT NULL; "
      // 匿名不泄漏：本表没有 actor 列也没有来源评论 id，全局判据在这一层结构上做不到精确，只能三条并列——
      // ① 本窗口精确（已知收发两人 + 那条靶子帖）应为 0；② 正面控制：同一条 LIKE 换个非匿名 actor 必须 >0；
      // ③ 全局只能按时间邻接近似，且**必须排除「同一个人在同一窗口内还发过实名评论」这种正常数据**：
      //    不排除时本轮实录 36 行（= 18 条正常实名评论通知 × 2 条匿名评论的交叉乘积），排除后 0。
      //    近似谓词的 0 不是不变式，只是数据还没撞上——这条写在手册 §14 第 39 条（原文 L1440）。
      + "SELECT COUNT(*) AS anon_leaked_this_round FROM notify_message WHERE user_id=" + ntAId
      + " AND ref_id=" + ntPostId + " AND type='comment'"
      + " AND title LIKE CONCAT('%', (SELECT nickname FROM user WHERE id=" + ntBId + "), '%'); "
      + "SELECT COUNT(*) AS anon_leaked_control FROM notify_message WHERE user_id=" + ntBId
      + " AND type='comment'"
      + " AND title LIKE CONCAT('%', (SELECT nickname FROM user WHERE id=" + ntAId + "), '%'); "
      + "SELECT COUNT(*) AS anon_leaked_global_raw FROM notify_message n JOIN comment c"
      + " ON c.post_id = n.ref_id AND c.is_anonymous = 1 AND n.type = 'comment' AND n.ref_type = 'post'"
      + " JOIN user u ON u.id = c.user_id"
      + " WHERE ABS(TIMESTAMPDIFF(SECOND, n.created_at, c.created_at)) <= 2"
      + " AND n.title LIKE CONCAT('%', u.nickname, '%'); "
      + "SELECT COUNT(*) AS anon_leaked_global_tight FROM notify_message n JOIN comment c"
      + " ON c.post_id = n.ref_id AND c.is_anonymous = 1 AND n.type = 'comment' AND n.ref_type = 'post'"
      + " JOIN user u ON u.id = c.user_id"
      + " WHERE ABS(TIMESTAMPDIFF(SECOND, n.created_at, c.created_at)) <= 2"
      + " AND n.title LIKE CONCAT('%', u.nickname, '%')"
      + " AND NOT EXISTS (SELECT 1 FROM comment c2 WHERE c2.post_id = c.post_id"
      + " AND c2.user_id = c.user_id AND c2.is_anonymous = 0"
      + " AND ABS(TIMESTAMPDIFF(SECOND, c2.created_at, n.created_at)) <= 2);");
  } else {
    info("18", "两名一次性账号没注册成功，本步整体跳过（根因在第 17 步同一段注册逻辑上）", "");
  }

  // ---------- 19 站内搜索 + 20 关注流（任务 3.9 / 3.17 · 手册 §6.1 行 3.9、3.17）----------
  // 单测只证明 WHERE 拼对了；「别人那条仅自己可见的帖到底进不进搜索结果」必须问真库。
  // 夹具刻意分甲乙两组关键词、各带本轮时间戳。本步最初让甲乙共用同一个锚点，结果「第三人搜出
  // 来几条」把第三人自己那两条公开帖也算了进去，n===2 当场挂——这不是断言太严，是夹具没把变量
  // 分开：命中集必须只属于被检的那一方，否则「不该出现」这类否定断言随时能被翻页与污染糊过去。
  const srchA = await regAccount("smoke_srch_a" + stamp, "冒烟检索甲");
  const srchB = await regAccount("smoke_srch_b" + stamp, "冒烟检索乙");
  const srchC = await regAccount("smoke_srch_c" + stamp, "冒烟检索丙");
  check("19", "注册三名检索账号：甲、乙各发一批帖，丙既不发帖也不关注任何人（对照账号必须是新真人，"
    + "不能拿前 18 步的夹具凑——那批账号的发帖配额与关注关系都已经被别的步骤动过了）",
    !!srchA && !!srchB && !!srchC, "a=" + !!srchA + " b=" + !!srchB + " c=" + !!srchC);
  if (srchA && srchB && srchC) {
    r = await rlSafe(function () {
      return send("GET", "/api/users/me", { headers: { Authorization: "Bearer " + srchA } });
    });
    const srchAId = bodyOf(r).id;
    r = await rlSafe(function () {
      return send("GET", "/api/users/me", { headers: { Authorization: "Bearer " + srchB } });
    });
    const srchBId = bodyOf(r).id;
    const kwA = "检索锚甲" + stamp;
    const kwB = "检索锚乙" + stamp;
    // 四个取数器就是四种身份：甲（作者本人）、乙（另一个登录用户）、丙（与两边都无关的第三人）、匿名
    const enc = encodeURIComponent;
    const asA = function (path, qs) {
      return rlSafe(function () {
        return send("GET", path + (qs ? "?" + qs : ""), { headers: { Authorization: "Bearer " + srchA } });
      });
    };
    const asB = function (path, qs) {
      return rlSafe(function () {
        return send("GET", path + (qs ? "?" + qs : ""), { headers: { Authorization: "Bearer " + srchB } });
      });
    };
    const noToken = function (path, qs) {
      return rlSafe(function () { return send("GET", path + (qs ? "?" + qs : ""), {}); });
    };
    const hitRows = function (rr) { return itemsOf(rr) || []; };
    const hitIds = function (rr) { return hitRows(rr).map(function (x) { return x.id; }); };
    const rowById = function (rr, id) {
      const found = hitRows(rr).filter(function (x) { return x.id === id; });
      return found.length ? found[0] : null;
    };
    const arrOf = function (rr) { return rr.json && Array.isArray(rr.json.data) ? rr.json.data : []; };
    const putPost = function (token, postBody) {
      return rlSafe(function () {
        return send("POST", "/api/posts", asToken(token, jsonBody(postBody)));
      });
    };

    r = await putPost(srchA, { title: kwA + "甲公开", content: "甲的公开帖，正文里也写着 " + kwA + "。" });
    const aPublicId = bodyOf(r).id;
    r = await putPost(srchA, { title: kwA + "甲私密", content: "仅自己可见的一条 " + kwA, visibility: "private" });
    const aPrivateId = bodyOf(r).id;
    const aPrivateVis = bodyOf(r).visibility;
    r = await putPost(srchA, { title: kwA + "甲树洞", content: "匿名的一条 " + kwA, type: "hole" });
    const aHoleRow = bodyOf(r);
    const aHoleId = aHoleRow.id;
    check("19", "甲的三条夹具落库且形态就是设计的样子：公开 public / 私密 private / 树洞匿名且已发马甲名"
      + "（第 19、20 两步全吃这几条，任何一条变形都会让后面的「不该出现」变成假绿）",
      !!aPublicId && !!aPrivateId && !!aHoleId && aPrivateVis === "private"
      && aHoleRow.anonymous === true && String(aHoleRow.displayName).indexOf("匿名屿民·") === 0,
      "pub=" + aPublicId + " priv=" + aPrivateId + "/" + aPrivateVis
      + " hole=" + aHoleId + "/" + aHoleRow.displayName);

    r = await putPost(srchB, { title: kwB + "转义靶", content: "正文里有字面量 100%_x 这五个字符 " + kwB });
    const bEscId = bodyOf(r).id;
    r = await putPost(srchB, { title: kwB + "对照靶", content: "正文里只有 100abcx 这七个字符 " + kwB });
    const bCtlId = bodyOf(r).id;
    r = await putPost(srchB, { title: kwB + "乙公开", content: "乙的公开实名帖 " + kwB });
    const bPublicId = bodyOf(r).id;
    r = await putPost(srchB, { title: kwB + "乙私密", content: "乙的私密帖 " + kwB, visibility: "private" });
    const bPrivateId = bodyOf(r).id;
    r = await putPost(srchB, { title: kwB + "乙树洞", content: "乙的匿名帖 " + kwB, type: "hole" });
    const bHoleId = bodyOf(r).id;
    check("19", "乙的五个靶子全部发出去：新注册 24 小时内每日 5 帖（BR5）是真闸门，本步一条都不许多发，"
      + "第 6 条会直接 10010 把整步带崩",
      !!bEscId && !!bCtlId && !!bPublicId && !!bPrivateId && !!bHoleId,
      "esc=" + bEscId + " ctl=" + bCtlId + " pub=" + bPublicId + " priv=" + bPrivateId + " hole=" + bHoleId);

    r = await noToken("/api/search/posts", "q=" + enc(kwA));
    check("19", "未登录搜帖 → 401/10002：搜索是「按任意关键词扫全站正文」，不给免登录身份开这条口子"
      + "（对照 /api/topics 是游客可读的话题墙，一个展示运营选好的内容、一个命中全站内容，口径不同是刻意的）",
      r.status === 401 && code(r) === "10002", r.status + " code=" + code(r));
    r = await noToken("/api/search/topics", "q=" + enc("焦虑"));
    const tGate = r.status === 401 && code(r) === "10002";
    r = await noToken("/api/search/users", "q=" + enc("冒烟"));
    check("19", "未登录搜话题、搜人同样 401/10002：三条路径的门槛不许各写一份，少一处兜底就是一条匿名扫库通道",
      tGate && r.status === 401 && code(r) === "10002", "topics401=" + tGate + " users=" + r.status);
    r = await asA("/api/search/posts", "q=%20%20");
    const blankBad = r.status === 400 && code(r) === "10001";
    r = await asA("/api/search/posts", "q=" + enc("焦虑".repeat(33)));
    check("19", "空白与超长关键词都在发出 SQL 之前被拒（400/10001、文案带 64）：搜索框不许退化成分页列表，"
      + "也不许拿一条没有上限的 LIKE 去扫 MEDIUMTEXT",
      blankBad && r.status === 400 && code(r) === "10001" && isMsg(r, "64"),
      "blank=" + r.status + " overlong=" + r.status + " msg=" + (r.json && r.json.msg));
    r = await asA("/api/search/posts", "q=" + enc(kwA) + "&type=sticker");
    check("19", "type 白名单外 → 400/10001 且文案回显三个合法值：搜索与广场共用同一份 normalizeTypeFilter，"
      + "不在这里重写一遍入参契约",
      r.status === 400 && code(r) === "10001" && isMsg(r, "normal"), r.status + " msg=" + (r.json && r.json.msg));

    r = await asB("/api/search/posts", "q=" + enc(kwA) + "&size=50");
    check("19", "乙搜甲的锚点：只命中甲的公开帖与甲的树洞帖这两条，甲那条私密帖不在里面"
      + "（「第三人看不到私密帖」是 FR4.3 的核心承诺，不是广场列表单独的专利）",
      r.status === 200 && code(r) === "0" && hitIds(r).length === 2
      && !!rowById(r, aPublicId) && !!rowById(r, aHoleId),
      r.status + " ids=" + hitIds(r).join(","));
    const srchHoleRow = rowById(r, aHoleId);
    const srchRealRow = rowById(r, aPublicId);
    check("19", "同一页里两条帖的作者口径相反：实名帖回 authorId=srchAId 与甲的昵称（这是正面控制，"
      + "证明下一条的「没有」不是恒真），匿名帖 authorId 整个键缺席、展示名是马甲名、单行序列化后不含甲的昵称。"
      + "注意响应体是 non_null 序列化，null 字段会被整个省掉，所以判「没给作者 id」必须判键在不在——"
      + "判 === null 会因为键根本不存在而假绿",
      !!srchRealRow && srchRealRow.authorId === srchAId && srchRealRow.anonymous === false
      && !!srchHoleRow && !("authorId" in srchHoleRow) && srchHoleRow.anonymous === true
      && String(srchHoleRow.displayName).indexOf("匿名屿民·") === 0
      && JSON.stringify(srchHoleRow).indexOf("冒烟检索甲") < 0,
      "实名行=" + nj(srchRealRow && { id: srchRealRow.id, authorId: srchRealRow.authorId })
      + " 匿名行=" + nj(srchHoleRow));

    r = await asA("/api/search/posts", "q=" + enc(kwA + "甲私密") + "&size=50");
    check("19", "作者本人也搜不到自己那条私密已发布帖：广场判据里 private 走「我的帖子」，搜索照抄这条判据"
      + "而不是给它开后门——多一个入口就多一份要各改一遍的 WHERE",
      r.status === 200 && hitIds(r).length === 0, r.status + " ids=" + hitIds(r).join(","));
    r = await asA("/api/users/me/posts", "size=50");
    check("19", "同一条私密帖在「我的帖子」里读得回来：上一条的空结果来自可见性判据，不来自写入失败",
      r.status === 200 && hitIds(r).indexOf(aPrivateId) >= 0,
      r.status + " n=" + hitIds(r).length + " ids=" + hitIds(r).join(","));
    r = await asA("/api/search/posts", "q=" + enc(kwA) + "&type=hole&size=50");
    check("19", "type=hole 把结果收窄到树洞那一条：搜索与广场共用同一个 type 过滤，不是两个各写一半的开关",
      hitIds(r).length === 1 && hitIds(r)[0] === aHoleId, hitIds(r).join(","));
    r = await asA("/api/search/posts", "q=" + enc(kwB) + "&size=50");
    check("19", "甲搜乙的锚点命中四条（转义靶 / 对照靶 / 公开 / 树洞），独缺乙那条私密帖：别人的私密帖"
      + "对作者之外的读者同样不可见，这一条把「五减一」摆在同一批数据上算",
      hitIds(r).length === 4 && !!rowById(r, bEscId) && !!rowById(r, bCtlId)
      && !!rowById(r, bPublicId) && !!rowById(r, bHoleId) && hitIds(r).indexOf(bPrivateId) < 0,
      "ids=" + hitIds(r).join(","));
    r = await asA("/api/search/posts", "q=" + enc("100%_x") + "&size=50");
    check("19", "搜 100%_x 只命中含这五个字符的那条，不命中只差一个通配符的 100abcx：LIKE 元字符进 SQL 前"
      + "必须按 ! 转义，漏转时模式串 %100%_x% 会把对照靶一起捞出来",
      r.status === 200 && !!rowById(r, bEscId) && !rowById(r, bCtlId),
      "ids=" + hitIds(r).join(",") + " 靶=" + bEscId + " 对照=" + bCtlId);
    r = await asA("/api/search/posts", "q=" + enc(kwB + "%") + "&size=50");
    const wPctIds = hitIds(r);
    r = await asA("/api/search/posts", "q=" + enc(kwB + "_") + "&size=50");
    const wUndIds = hitIds(r);
    check("19", "把关键词拼成「乙锚点+%」「乙锚点+_」两条都 0 命中：乙的标题形状正是「锚点后紧跟一个汉字」，"
      + "转义一漏这两条就退化成搜「锚点%」「锚点_」＝乙的 5 条全捞（含那条私密之外的 4 条可见帖）。"
      + "这条比单搜一个 % 更狠：它把「漏转义」与「本轮夹具」锁死在同一批数据上",
      wPctIds.length === 0 && wUndIds.length === 0,
      "pct=" + wPctIds.join(",") + " underscore=" + wUndIds.join(","));
    r = await asA("/api/search/posts", "q=%25&size=50");
    const pctIds = hitIds(r);
    const srchFixtures = [aPublicId, aPrivateId, aHoleId, bEscId, bCtlId, bPublicId, bPrivateId, bHoleId];
    const pctLeak = srchFixtures.filter(function (id) {
      return id !== bEscId && pctIds.indexOf(id) >= 0;
    });
    check("19", "搜单个 % 不是「把全站列一遍」：命中的恰是含这个字符的那条（转义靶在列＝正面控制），"
      + "其余七条夹具一条都不在结果里，且 total 是个小数字（未转义时它等于全站可见帖数，量级 130+）",
      r.status === 200 && code(r) === "0" && !!rowById(r, bEscId) && pctLeak.length === 0
      && Number(bodyOf(r).total) <= 5,
      "total=" + bodyOf(r).total + " 命中夹具=" + pctIds.filter(function (id) {
        return srchFixtures.indexOf(id) >= 0; }).join(",") + " 泄漏=" + pctLeak.join(","));
    r = await asA("/api/search/posts", "q=" + enc("失眠夜") + "&size=50");
    const topicPathRows = hitRows(r);
    check("19", "话题名命中那条通路真的能用：搜「失眠夜」有结果，而每一条的标题与摘要都不含这三个字"
      + "（root 核对：全库没有任何帖子的 title/content 含这个词）——命中只能来自 EXISTS(post_topic JOIN topic)。"
      + "单测只能证明 SQL 文本里有这段，能不能真把挂了话题的帖搜出来要问数据",
      r.status === 200 && topicPathRows.length > 0 && topicPathRows.every(function (x) {
        return String(x.title || "").indexOf("失眠夜") < 0
          && String(x.excerpt || "").indexOf("失眠夜") < 0; }),
      "n=" + topicPathRows.length + " total=" + bodyOf(r).total);

    r = await asA("/api/search/topics", "q=" + enc("焦虑") + "&limit=999");
    const topicHits = arrOf(r);
    check("19", "搜话题命中种子里含「焦虑」的已过审话题（秋招焦虑 / 体重焦虑），limit 越界被夹到 "
      + "mindisle.search.max-profiles=20 而不是报错、更不是无上限",
      r.status === 200 && code(r) === "0" && topicHits.length > 0 && topicHits.length <= 20,
      r.status + " n=" + topicHits.length + " names=" + topicHits.map(function (x) { return x.name; }).join("/"));
    check("19", "话题出参七个字段一字不差：cover / deleted / auditStatus 一个都不透出（封面是对象存储域名、"
      + "删除位与审核状态属运营信息）",
      topicHits.length > 0 && Object.keys(topicHits[0]).sort().join(",")
      === "desc,followCnt,hotScore,id,isOfficial,name,postCnt",
      topicHits.length ? Object.keys(topicHits[0]).sort().join(",") : "无结果");
    r = await asA("/api/search/topics", "q=" + enc("不存在的话题" + stamp));
    check("19", "搜不到话题时回空数组而不是 404：搜索框要为空态让路，前端不必为「没结果」多写一条异常分支",
      r.status === 200 && code(r) === "0" && Array.isArray(r.json && r.json.data)
      && r.json.data.length === 0, r.status + " " + short(r, 90));
    r = await asA("/api/search/users", "q=" + enc("冒烟检索甲"));
    const userHits = arrOf(r);
    check("19", "按昵称搜人搜到甲本人，且出参只可能是 id / nickname / avatar 三个字段：email、role、status、"
      + "密码哈希一律不给。avatar 为空时 non_null 会整个省掉这个键，所以判「是三个字段的子集且 id/nickname 俱在」，"
      + "而不是判「恰好等于三个键」——后者会因为少一个可空字段而假挂",
      r.status === 200 && userHits.some(function (x) { return x.id === srchAId; })
      && userHits.every(function (x) {
        return "id" in x && "nickname" in x && Object.keys(x).every(function (k) {
          return k === "id" || k === "nickname" || k === "avatar"; }); }),
      "n=" + userHits.length + " keys=" + (userHits.length ? Object.keys(userHits[0]).join(",") : "-"));
    r = await asA("/api/search/users", "q=" + enc("smoke_srch_b" + stamp));
    const userHits2 = arrOf(r);
    check("19", "按登录名也搜得到乙：昵称可改，登录名是唯一的第二入口，两条 LIKE 收在同一组括号里"
      + "（不与 status 之间留一个悬空 OR，那是 §14 第 26 条的原文事故）",
      userHits2.some(function (x) { return x.id === srchBId; }), "n=" + userHits2.length);
    r = await asA("/api/search/users", "q=%25");
    const userPct = arrOf(r);
    check("19", "搜人也逃不过转义：q=% 一条都不命中（未转义时它等于「把全站 ACTIVE 账号列一遍」，"
      + "而库里现在有一百多个账号）",
      r.status === 200 && userPct.length === 0, r.status + " n=" + userPct.length);

    info("19", "SQL 取证（root 直连复核，别信脚本自证）：① 八条夹具的 status / visibility / is_anonymous /"
      + " alias_id 逐行真值——这是「私密搜不到」那两条的正面控制，它们必须真在库里且真的是 private/匿名；"
      + "② 全站含字面 % 的公开已发布帖条数，应等于上面上一步 q=% 返回的 total（本轮应为 1，就是那条转义靶）。"
      + "跑完把两个数字回写手册 §6.1。",
      "SELECT id,user_id,type,status,visibility,is_anonymous,alias_id,title FROM post WHERE id IN ("
      + srchFixtures.join(",") + ") ORDER BY id; SELECT COUNT(*) AS pct_visible_posts FROM post WHERE deleted=0"
      + " AND status='PUBLISHED' AND visibility='public'"
      + " AND (title LIKE '%!%%' ESCAPE '!' OR content LIKE '%!%%' ESCAPE '!');");

    // ---------- 20 关注流（任务 3.17 · 需求 FR4.6）----------
    const feedGet = function (token, qs) {
      return rlSafe(function () {
        return send("GET", "/api/feed/following" + (qs ? "?" + qs : ""),
          token ? { headers: { Authorization: "Bearer " + token } } : {});
      });
    };
    r = await feedGet(null, "");
    check("20", "未登录取关注流 → 401/10002，不退化成「那先给你看广场」：把 current 为 null 兜底成空列表，"
      + "这条路径就成了广场的第二入口", r.status === 401 && code(r) === "10002",
      r.status + " code=" + code(r));
    r = await feedGet(srchC, "");
    check("20", "谁都没关注的丙 → 200 + 空列表 + hasMore=false + total=0：MyBatis-Plus 的 in(空集合) 会拼出"
      + " IN () 直接 500，而「刚注册、还没关注任何人」正是这条路径最常见的新人状态，必须短路在发 SQL 之前",
      r.status === 200 && code(r) === "0" && hitRows(r).length === 0
      && bodyOf(r).hasMore === false && Number(bodyOf(r).total) === 0,
      r.status + " " + short(r, 120));
    r = await nFollow(srchA, srchBId, "follow");
    check("20", "甲关注乙 → changed=true：关系写进 user_follow，关注流的作者集合每次都是从这张表现取的（没有缓存）",
      r.status === 200 && code(r) === "0" && bodyOf(r).changed === true, r.status + " " + nj(bodyOf(r)));
    r = await feedGet(srchA, "size=50");
    const feedRows = hitRows(r);
    const feedIds = feedRows.map(function (x) { return x.id; });
    check("20", "关注流里有乙的三条公开实名帖（两条转义靶也在里面，因为它们是同一条时间线的成员而不是另开一路检索），"
      + "没有甲自己的任何一条：这条流回答的是「我关注的人更新了什么」，不是「全站有什么」",
      feedIds.indexOf(bPublicId) >= 0 && feedIds.indexOf(bEscId) >= 0 && feedIds.indexOf(bCtlId) >= 0
      && feedIds.indexOf(aPublicId) < 0 && feedIds.indexOf(aHoleId) < 0 && feedIds.indexOf(aPrivateId) < 0,
      "ids=" + feedIds.join(","));
    check("20", "乙的私密帖与乙的树洞帖都不进关注流：同一个人不能既是「我关注的冒烟检索乙」又是「匿名屿民·X」，"
      + "放进来就是让关注关系自己把马甲脱了（FR1.4，与公开主页共用同一条判据）",
      feedIds.indexOf(bPrivateId) < 0 && feedIds.indexOf(bHoleId) < 0,
      "私密在列=" + (feedIds.indexOf(bPrivateId) >= 0) + " 树洞在列=" + (feedIds.indexOf(bHoleId) >= 0));
    check("20", "整条流逐行不变式：作者恒为乙、anonymous 恒 false、visibility 恒 public、展示名恒为乙的昵称"
      + "（是整页每行都判，不是抽查一条）",
      feedRows.length > 0 && feedRows.every(function (x) {
        return x.authorId === srchBId && x.anonymous === false && x.visibility === "public"
          && x.displayName === "冒烟检索乙"; }),
      "n=" + feedRows.length + " " + nj(feedRows.slice(0, 2).map(function (x) {
        return { id: x.id, authorId: x.authorId, dn: x.displayName, v: x.visibility }; })));
    r = await feedGet(srchA, "size=1");
    const fpg1 = hitRows(r);
    const fcur = bodyOf(r).nextCursor;
    r = await feedGet(srchA, "size=1&beforeId=" + fcur);
    const fpg2 = hitRows(r);
    check("20", "游标翻页不重不漏：第一页一条、nextCursor 就是它的 id，第二页与第一页无交集且 id 严格更小"
      + "（复用广场那一份 pageResult，排序键 published_at DESC + id DESC）",
      fpg1.length === 1 && fpg2.length === 1 && fpg1[0].id !== fpg2[0].id && fpg2[0].id < fpg1[0].id
      && fcur === fpg1[0].id && bodyOf(r).nextCursor === fpg2[0].id,
      "p1=" + nj(fpg1.map(function (x) { return x.id; })) + " cursor=" + fcur
      + " p2=" + nj(fpg2.map(function (x) { return x.id; })));
    r = await nFollow(srchA, srchBId, "unfollow");
    const unfollowed = bodyOf(r);
    r = await feedGet(srchA, "size=50");
    check("20", "取关之后乙的帖立刻从流里消失：这条路径每次现取 user_follow、没有缓存层，所以不存在"
      + "「取关了还能刷到」的窗口（推荐流那条缓存路径要重新判这件事，是阶段 4 的事）",
      unfollowed.changed === true && hitRows(r).length === 0,
      "changed=" + unfollowed.changed + " n=" + hitRows(r).length);

    info("20", "SQL 取证（root 直连复核，脚本自证不算）：① 甲对乙的关注关系行在取关后确实不在了（这条链路上"
      + " user_follow 没有删除位，取关就是物理删）；② 乙这一批作者里「匿名或挂了马甲」的公开已发布帖条数应为 1"
      + "（就是那条树洞）、③ 实名公开帖条数应为 3——② 是关注流该拒的、③ 是它该给的，两个数分别对上一步里"
      + "「树洞不在列」和「三条都在列」，否则那两条断言只是恒真。",
      "SELECT COUNT(*) AS follow_row_left FROM user_follow WHERE user_id=" + srchAId
      + " AND follow_user_id=" + srchBId + "; SELECT COUNT(*) AS anon_rows_of_followee FROM post p WHERE p.deleted=0"
      + " AND p.user_id=" + srchBId + " AND p.status='PUBLISHED' AND p.visibility='public'"
      + " AND (p.is_anonymous = 1 OR p.alias_id IS NOT NULL); SELECT COUNT(*) AS realname_rows_of_followee"
      + " FROM post p WHERE p.deleted=0 AND p.user_id=" + srchBId + " AND p.status='PUBLISHED'"
      + " AND p.visibility='public' AND p.is_anonymous = 0 AND p.alias_id IS NULL;");

    // ---------- 21 话题域四条端点（任务 3.8 · 需求 FR1.7、FR4.5、FR8.6 · 手册 §6.1 行 3.8、§6.2 U6）----------
    // 这一步要证的三件事，单测一份都替代不了：
    // ① 鉴权白名单的真实形状。本域四条端点曾经「带着合法 token 也恒 401」，根因是 JwtAuthFilter 的
    //    匿名清单里写了 startsWith("/api/topics") —— 前缀把 /api/topics/1 整条吞进去，解析令牌那一步
    //    根本不执行，current 永远是 null。修掉第一层才露出第二层：GET /api/topics（游客墙）与
    //    POST /api/topics（创建）共用同一个 URI，只按 URI 判就必然二选一。两层都只有真跑一次 HTTP
    //    才看得见，所以这一步留在最后，也必须起真后端来跑（§5.10）。
    // ② requireReadable 是全站唯一一处「这个话题现在能不能被读」，四个入口必须给同一个码。
    // ③ post_cnt / follow_cnt 是每次互动后按真相表重算回写的列：私密帖、待审帖不许把它抬上去。
    const tpcA = await regAccount("smoke_tpc_a" + stamp, "冒烟话题甲");
    const tpcB = await regAccount("smoke_tpc_b" + stamp, "冒烟话题乙");
    check("21", "注册两名话题域一次性账号：本段要真实建话题、真实挂帖，前 20 步夹具的发帖额度与关注关系都已经被动过",
      !!tpcA && !!tpcB, "a=" + !!tpcA + " b=" + !!tpcB);
    if (tpcA && tpcB) {
      // 本段的两个取数器：token 传 null 就是游客，而第 ① 条断言要的正是游客态。
      const tpGet = function (token, p, qs) {
        return rlSafe(function () {
          return send("GET", p + (qs ? "?" + qs : ""),
            token ? { headers: { Authorization: "Bearer " + token } } : {});
        });
      };
      const tpPost = function (token, p, reqBody) {
        return rlSafe(function () {
          return send("POST", p, token ? asToken(token, jsonBody(reqBody)) : jsonBody(reqBody));
        });
      };
      const hasStr = function (v, needle) {
        return String(v === undefined || v === null ? "" : v).indexOf(needle) >= 0;
      };
      const tpKeys = function (v) { return Object.keys(v || {}); };
      const msgOf = function (rr) { return (rr.json && rr.json.msg) || ""; };

      // 拿一条「真的有帖」的已过审话题当靶子：库里只有 1 号话题挂着种子帖，但运行期不许写死 id。
      r = await tpGet(null, "/api/topics", "limit=50");
      const tpWallRows = arrOf(r);
      const tpWallWithPosts = tpWallRows.filter(function (x) { return Number(x.postCnt) > 0; });
      const wallT = tpWallWithPosts.length ? tpWallWithPosts[0] : null;
      const tId = wallT ? wallT.id : 0;
      const basePostCnt = wallT ? Number(wallT.postCnt) : 0;
      const baseFollowCnt = wallT ? Number(wallT.followCnt) : 0;

      const gDetail = await tpGet(null, "/api/topics/" + tId, "");
      const gPosts = await tpGet(null, "/api/topics/" + tId + "/posts", "size=1");
      const gCreate = await tpPost(null, "/api/topics", { name: "游客建的话题" });
      const gFollow = await tpPost(null, "/api/topics/" + tId + "/follow", { action: "follow" });
      const gate401 = function (rr) { return rr.status === 401 && code(rr) === "10002"; };
      check("21", "话题域四条端点未登录全部 401/10002，而同一个 URI 上的话题墙游客照常读回 20 条："
        + "读与写按 HTTP 方法分流、白名单里没有前缀。这条同时钉住本轮修掉的两个 bug：匿名清单用前缀"
        + "会连带把 /api/topics/1 变成匿名路径（于是永远解析不出令牌、永远 401），而只按 URI 分流又会"
        + "在游客建话题与登录建话题之间二选一 —— JwtAuthFilter 与 SecurityConfig 两份清单必须一起改",
        gate401(gDetail) && gate401(gPosts) && gate401(gCreate) && gate401(gFollow)
        && r.status === 200 && tpWallRows.length === 20 && !!wallT,
        "detail=" + gDetail.status + " posts=" + gPosts.status + " create=" + gCreate.status
        + " follow=" + gFollow.status + " wall=" + r.status + " n=" + tpWallRows.length);

      r = await tpGet(tpcA, "/api/topics/" + tId, "");
      const det = bodyOf(r);
      check("21", "登录读已过审话题 → 200，出参恰好 8 个键、比话题墙那一行只多一个 following；cover 为 null"
        + " 被 non_null 序列化整个省掉（U6 头图今天没有上传通道，前端判「有没有头图」只能判键在不在）",
        r.status === 200 && code(r) === "0" && tpKeys(det).length === 8 && det.following === false
        && det.id === tId && det.name === wallT.name && Number(det.postCnt) === basePostCnt
        && det.cover == null && tpKeys(det).indexOf("cover") < 0,
        r.status + " keys=" + tpKeys(det).join(",") + " postCnt=" + det.postCnt
        + " followCnt=" + det.followCnt);

      r = await tpGet(tpcA, "/api/topics/abc", "");
      check("21", "非数字 id → 404/90006 而不是 500/400：路径变量上的数字约束先把 long 转换挡在 MVC 那一层；"
        + "文案带请求行，这一支出自全局兜底（NoResourceFoundException），与下一条 requireReadable 给的"
        + "文案形状不同，分开钉才看得出码到底是谁给的",
        r.status === 404 && code(r) === "90006" && hasStr(msgOf(r), "/api/topics/abc"),
        r.status + " code=" + code(r) + " msg=" + msgOf(r));
      r = await tpGet(tpcA, "/api/topics/99999999", "");
      check("21", "不存在的话题 → 404/90006 且文案不含那个 id：「没有这个话题」与「被驳回」是同一句话，"
        + "不给存在性留枚举通道（帖子那边对别人的私密帖是同一教义：30001/404 不区分二者）",
        r.status === 404 && code(r) === "90006" && !hasStr(msgOf(r), "99999999"),
        r.status + " msg=" + msgOf(r));

      r = await tpPost(tpcA, "/api/topics", { name: "   " });
      check("21", "只有空白的名字折叠成空串之后按「没填」拒 → 400/10001「话题名不能为空」："
        + "库里 name NOT NULL，放一条空名进去就是话题墙上一个点不动的空白入口",
        r.status === 400 && code(r) === "10001" && hasStr(msgOf(r), "话题名不能为空"),
        r.status + " msg=" + msgOf(r));
      const name33 = "🌱".repeat(18) + "甲" + stamp;
      r = await tpPost(tpcA, "/api/topics", { name: name33 });
      check("21", "33 个码点的名字被拒，且文案同时报上限 32 与实际 33：长度按码点算 —— 18 个 emoji 在 Java 里"
        + "占 36 个 char，按 char 判就会报出「当前 37 字」这种没人看得懂的数字，也让带表情的话题名莫名超长",
        r.status === 400 && code(r) === "10001" && hasStr(msgOf(r), "32") && hasStr(msgOf(r), "当前 33"),
        "chars=" + name33.length + " codepoints=" + Array.from(name33).length + " msg=" + msgOf(r));
      const name32 = "🌱".repeat(17) + "甲" + stamp;
      r = await tpPost(tpcA, "/api/topics", { name: name32 });
      const created = bodyOf(r);
      check("21", "与上一条只差一个码点（32）就 200 创建成功：两条一起构成码点 vs char 的分水岭。"
        + "判据本身由 TopicServiceTest 逐格钉，这里要的是接口层的证据",
        r.status === 200 && code(r) === "0" && !!created.id && created.name === name32,
        r.status + " chars=" + name32.length + " codepoints=" + Array.from(name32).length
        + " id=" + created.id + " 回执=" + nj(created));
      const createdKeys = tpKeys(created);
      const auditPending = created.auditStatus === "PENDING";
      check("21", "创建回执恰好 8 个键，且 usable / tip 恒等于 auditStatus 的那一面：FR8.6 那个开关（缺省 true）"
        + "只改这一处，前端只读 usable 就知道该不该置灰，不必去读环境变量；而状态码恒 200 —— "
        + "「创建成功但进了待审」是一次业务上完全成功的请求，不该长得像 4xx",
        createdKeys.length === 8 && created.usable === !auditPending
        && Number(created.postCnt) === 0 && Number(created.followCnt) === 0
        && hasStr(created.tip, auditPending ? "审核" : "现在就能进去发帖"),
        "keys=" + createdKeys.join(",") + " auditStatus=" + created.auditStatus
        + " usable=" + created.usable + " tip=" + created.tip);

      if (auditPending) {
        const p1 = await tpGet(tpcA, "/api/topics/" + created.id, "");
        const p2 = await tpGet(tpcA, "/api/topics/" + created.id + "/posts", "size=1");
        const p3 = await tpPost(tpcA, "/api/topics/" + created.id + "/follow", { action: "follow" });
        const p4 = await putPost(tpcA, { title: "话题冒烟·挂待审" + stamp,
          content: "挂一个还没过审的话题 " + stamp, topicIds: [created.id] });
        check("21", "同一条待审话题在四个入口给的都是 409/30004（进页、话题流、关注、发帖挂载）：前三个文案"
          + "带全名与「还在审核中」，挂帖那条是「还没通过审核」。requireReadable 是全站唯一一处判据，"
          + "四处共用才有这条断言 —— 不是 404（会让人以为创建失败），不是 400（用户此刻能做的是等不是改）",
          p1.status === 409 && code(p1) === "30004" && hasStr(msgOf(p1), name32)
          && hasStr(msgOf(p1), "还在审核中")
          && p2.status === 409 && code(p2) === "30004"
          && p3.status === 409 && code(p3) === "30004"
          && p4.status === 409 && code(p4) === "30004" && hasStr(msgOf(p4), "还没通过审核"),
          "detail=" + p1.status + " posts=" + p2.status + " follow=" + p3.status
          + " link=" + p4.status + " msg=" + msgOf(p4));
      } else {
        info("21", "本环境把 mindisle.topic.require-pre-review 显式设成了 false，新建话题直接过审，"
          + "这一轮的 409/30004 四入口分支拿不到 HTTP 证据（判据仍被上一条回执与 TopicServiceTest 钉住）",
          "要跑这条分支：不设该环境变量（缺省即 true）重跑 node docs/smoke.mjs");
      }

      r = await putPost(tpcA, { title: "话题冒烟·公开" + stamp,
        content: "挂在已过审话题下的一条公开帖 " + stamp, topicIds: [tId] });
      const tpPublicRow = bodyOf(r);
      const tpPublicId = tpPublicRow.id;
      check("21", "干净公开帖挂已过审话题 → 200 + PUBLISHED + topics 原样回显话题名（FR4.5 只挂过审的）",
        r.status === 200 && code(r) === "0" && tpPublicRow.status === "PUBLISHED"
        && Array.isArray(tpPublicRow.topics) && tpPublicRow.topics.length === 1
        && tpPublicRow.topics[0] === wallT.name,
        r.status + " status=" + tpPublicRow.status + " topics=" + nj(tpPublicRow.topics));
      r = await tpGet(tpcB, "/api/topics/" + tId, "");
      check("21", "post_cnt 恰好 +1，而且是由第二个人读到的：这一列每次挂帖后按真相表重算回写，"
        + "不是 INCR + 定时回写 —— 它是 INT UNSIGNED，缓存值偏大时相减会下溢成 42 亿，"
        + "而 Redis 缺席时降级 Caffeine 的失败语义还不一样，不该给计数造第二个真相",
        Number(bodyOf(r).postCnt) === basePostCnt + 1,
        "base=" + basePostCnt + " now=" + bodyOf(r).postCnt);
      r = await tpGet(tpcB, "/api/topics/" + tId + "/posts", "size=50");
      const tpFeedIds = hitIds(r);
      check("21", "刚发的帖出现在话题页帖流里（FR4.5 聚合页的最小闭环），且这一页 hasMore=false —— "
        + "下面几条判的都是「不在列」，被翻页截断会让否定断言恒真",
        tpFeedIds.indexOf(tpPublicId) >= 0 && bodyOf(r).hasMore === false,
        "n=" + tpFeedIds.length + " 新帖在列=" + (tpFeedIds.indexOf(tpPublicId) >= 0)
        + " hasMore=" + bodyOf(r).hasMore);

      r = await putPost(tpcA, { title: "话题冒烟·私密" + stamp,
        content: "仅自己可见但挂了话题 " + stamp, visibility: "private", topicIds: [tId] });
      const tpPrivateId = bodyOf(r).id;
      r = await tpGet(tpcA, "/api/topics/" + tId + "/posts", "size=50");
      const tpOwnFeed = hitIds(r);
      const tpOwnMore = bodyOf(r).hasMore;
      r = await tpGet(tpcB, "/api/topics/" + tId + "/posts", "size=50");
      const tpPeerFeed = hitIds(r);
      check("21", "作者自己的私密帖挂进话题也不进话题流，甲、乙两个身份都读不到它：applyVisible 只把"
        + "「自己的待审帖」回给作者，private 不在那一支里。话题页照抄广场那一条判据而不是另开一版 —— "
        + "多一个入口就多一份要各改一遍的 WHERE（手册 §14 第 27 条禁的正是这个）",
        tpOwnMore === false && tpOwnFeed.indexOf(tpPrivateId) < 0
        && tpPeerFeed.indexOf(tpPrivateId) < 0,
        "作者视角 n=" + tpOwnFeed.length + " 含私密=" + (tpOwnFeed.indexOf(tpPrivateId) >= 0)
        + " 他人视角 n=" + tpPeerFeed.length);
      r = await tpGet(tpcA, "/api/users/me/posts", "size=50");
      check("21", "同一条私密帖在「我的帖子」里读得回来：上面那个「读不到」来自可见性判据，不是写入失败"
        + "（applyOwned 与 applyVisible 是两条不同的收窄，前者才给作者回看自己的私密）",
        hitIds(r).indexOf(tpPrivateId) >= 0, "n=" + hitIds(r).length);
      r = await tpGet(tpcB, "/api/topics/" + tId, "");
      check("21", "私密帖被挡在门外，post_cnt 也就纹丝不动：linkTopics 对每个关联话题都无条件触发重算，"
        + "而重算 SQL 只计 public + PUBLISHED + 未到期 —— 计数对不对，看的正是这条「不该涨的有没有涨」",
        Number(bodyOf(r).postCnt) === basePostCnt + 1,
        "base=" + basePostCnt + " now=" + bodyOf(r).postCnt);

      r = await putPost(tpcA, { title: "话题冒烟·树洞" + stamp,
        content: "匿名挂话题 " + stamp, type: "hole", topicIds: [tId] });
      const tpHoleId = bodyOf(r).id;
      r = await tpGet(tpcB, "/api/topics/" + tId + "/posts", "size=50");
      const tpHoleRow = rowById(r, tpHoleId);
      check("21", "树洞帖进话题流时 authorId 整个键缺席、展示名是马甲名、整行序列化后不含甲的昵称："
        + "「换个入口就被认出来」和「在广场被认出来」是同一件事，解匿的口子不能因为多了一条路径就重开（FR1.4）",
        !!tpHoleRow && !("authorId" in tpHoleRow) && tpHoleRow.anonymous === true
        && String(tpHoleRow.displayName).indexOf("匿名屿民·") === 0
        && JSON.stringify(tpHoleRow).indexOf("冒烟话题甲") < 0,
        nj(tpHoleRow && { id: tpHoleRow.id, dn: tpHoleRow.displayName, anon: tpHoleRow.anonymous }));
      r = await tpGet(tpcB, "/api/topics/" + tId, "");
      check("21", "树洞（匿名但公开）计入 post_cnt：base+2 = 公开 + 树洞，中间那条私密始终没算进去。"
        + "匿名是「对读者摘掉作者」，不是「对所有人摘掉内容」，两件事各自的判据不许互相越界",
        Number(bodyOf(r).postCnt) === basePostCnt + 2,
        "base=" + basePostCnt + " now=" + bodyOf(r).postCnt);

      r = await tpPost(tpcA, "/api/topics/" + tId + "/follow", { action: "Follow " });
      const tf1 = bodyOf(r);
      check("21", "关注话题 → 200，出参恰好 5 个键、changed=true、following=true、follow_cnt 从基线 +1；"
        + "action 传 Follow 带尾空格也收（trim + Locale.ROOT 转小写，与关注人、点赞三处共用同一份归一化）",
        r.status === 200 && code(r) === "0" && tpKeys(tf1).length === 5 && tf1.changed === true
        && tf1.following === true && tf1.topicId === tId && tf1.action === "follow"
        && Number(tf1.followCnt) === baseFollowCnt + 1,
        r.status + " keys=" + tpKeys(tf1).join(",") + " " + nj(tf1));
      r = await tpGet(tpcA, "/api/topics/" + tId, "");
      const detailFollowing1 = bodyOf(r).following;
      r = await tpPost(tpcA, "/api/topics/" + tId + "/follow", { action: "follow" });
      const tf2 = bodyOf(r);
      check("21", "detail 把 following=true 回给按钮，而重复关注第二次 changed=false、数字不动："
        + "唯一键 uk_topic_follow 挡住第二次 INSERT，changed 的语义是「这次真的改了这个人的状态」"
        + "而不是「SQL 执行成功了」—— 前端按钮的提示文案读的是前者",
        detailFollowing1 === true && tf2.changed === false && tf2.following === true
        && Number(tf2.followCnt) === baseFollowCnt + 1,
        "following=" + detailFollowing1 + " " + nj(tf2));
      r = await tpPost(tpcB, "/api/topics/" + tId + "/follow", { action: "follow" });
      const tf3 = bodyOf(r);
      check("21", "换乙关注同一条话题 → follow_cnt 再 +1：这一数列的是「多少人在跟」，不是「被点了多少次」"
        + "（幂等键是 (user_id, topic_id)，两个人各算一票，同一个人刷一百次也算一票）",
        tf3.changed === true && Number(tf3.followCnt) === baseFollowCnt + 2,
        "changed=" + tf3.changed + " followCnt=" + tf3.followCnt);
      r = await tpPost(tpcB, "/api/topics/" + tId + "/follow", { action: "unfollow" });
      const tf4 = bodyOf(r);
      r = await tpPost(tpcA, "/api/topics/" + tId + "/follow", { action: "unfollow" });
      const tf5 = bodyOf(r);
      r = await tpPost(tpcA, "/api/topics/" + tId + "/follow", { action: "unfollow" });
      const tf6 = bodyOf(r);
      r = await tpGet(tpcA, "/api/topics/" + tId, "");
      check("21", "取关一路回到基线：乙取消、甲取消各 changed=true 且数字归位，甲再取消一次 changed=false"
        + "（取消一个没关注过的东西不是错误，前端双击与网络重发都不该弹报错），detail.following 也回到 false。"
        + "本表没有删除位，取关就是物理 DELETE，收尾不留脏关系",
        tf4.changed === true && tf5.changed === true && Number(tf5.followCnt) === baseFollowCnt
        && tf6.changed === false && Number(tf6.followCnt) === baseFollowCnt
        && tf6.following === false && bodyOf(r).following === false,
        "乙取消=" + nj(tf4) + " 甲取消=" + nj(tf5) + " 再取消=" + nj(tf6));
      r = await tpPost(tpcA, "/api/topics/" + tId + "/follow", { action: "bogus" });
      check("21", "白名单外的 action → 400/10001 且文案把两个合法值念出来：不猜用户想点哪个按钮",
        r.status === 400 && code(r) === "10001" && hasStr(msgOf(r), "follow")
        && hasStr(msgOf(r), "unfollow"), r.status + " msg=" + msgOf(r));

      r = await tpGet(tpcA, "/api/topics/" + tId + "/posts", "sort=hot");
      const sortBad = r.status === 400 && code(r) === "10001";
      const sortMsg = msgOf(r);
      // r 在后面还要被两次翻页请求复用它自己的值，所以「那次 400 的码」必须当场抄下来：
      // 首跑这里直接打 r.status，结果断言全绿而明细写着 hot=200，读日志的人会以为白名单又坏了。
      const sortBadStatus = r.status;
      r = await tpGet(tpcA, "/api/topics/" + tId + "/posts", "size=50");
      const latestOrder = hitIds(r).join(",");
      r = await tpGet(tpcA, "/api/topics/" + tId + "/posts", "sort=top&size=50");
      check("21", "sort=hot 当场 400/10001 且文案列出两个合法值（不「猜一个」静默变时间序，否则用户看到的是"
        + "「排序坏了」而系统里没一处说它坏了）；sort=top 今天与缺省 latest 完全同序 —— top 只是"
        + "「is_top 在前」，而 is_top 只有管理员能写、管理端属 T6.1，库里恒 0。接口形状先按最终形态定，"
        + "但手册 §6.1 不许把这一档写成「热帖排序」（FR4.5 只做到这里）",
        sortBad && hasStr(sortMsg, "latest") && hasStr(sortMsg, "top")
        && r.status === 200 && hitIds(r).join(",") === latestOrder,
        "hot=" + sortBadStatus + " msg=" + sortMsg + " top 与 latest 同序="
        + (hitIds(r).join(",") === latestOrder) + " n=" + hitIds(r).length);

      r = await tpGet(tpcA, "/api/topics/" + tId + "/posts", "size=1");
      const tpPage1 = hitIds(r);
      const tpCursor = bodyOf(r).nextCursor;
      const tpPageKeys = tpKeys(bodyOf(r)).length;
      r = await tpGet(tpcA, "/api/topics/" + tId + "/posts", "size=1&beforeId=" + tpCursor);
      check("21", "游标翻页不重不漏：第一页一条、nextCursor 就是它的 id，第二页那条 id 严格更小；"
        + "PageResult 的键形状与广场、关注流同一份（六个键，前端复用同一个翻页器）",
        tpPage1.length === 1 && tpCursor === tpPage1[0] && hitIds(r).length === 1
        && hitIds(r)[0] < tpPage1[0] && tpPageKeys === 6 && bodyOf(r).hasMore === true,
        "p1=" + tpPage1.join(",") + " cursor=" + tpCursor + " p2=" + hitIds(r).join(",")
        + " keys=" + tpPageKeys);

      info("21", "SQL 取证（root 直连复核，脚本自证不算）：① 话题 " + tId + " 的 post_cnt 必须等于"
        + "「post_topic 关联里可见的那几条」（多出来的就是私密挂帖，重算 SQL 不该看见它）；② follow_cnt"
        + " 必须等于 topic_follow 的行数，本段收尾时甲乙都已取关，两个数都该回到基线 " + baseFollowCnt
        + "；③ 全站 is_top=1 的帖仍为 0 —— 这是上面那条「top 与 latest 同序」的前提，哪天有人开始写这一列，"
        + "那条断言就得跟着改；④ 本段留下的一次性账号与一条待审话题（清理 SQL 见 dev-log）。",
        "SELECT (SELECT post_cnt FROM topic WHERE id=" + tId + ") AS topic_post_cnt,"
        + " (SELECT COUNT(*) FROM post_topic pt JOIN post p ON p.id = pt.post_id"
        + " WHERE pt.topic_id=" + tId + " AND p.deleted=0 AND p.status='PUBLISHED'"
        + " AND p.visibility='public' AND (p.auto_destroy_at IS NULL OR p.auto_destroy_at > NOW(3)))"
        + " AS visible_links,(SELECT follow_cnt FROM topic WHERE id=" + tId + ") AS topic_follow_cnt,"
        + " (SELECT COUNT(*) FROM topic_follow WHERE topic_id=" + tId + ") AS follow_rows,"
        + " (SELECT COUNT(*) FROM post WHERE is_top = 1) AS top_posts;"
        + " SELECT id,name,audit_status FROM topic WHERE audit_status <> 'APPROVED';"
        + " SELECT username,nickname FROM user WHERE username LIKE 'smoke_tpc_%';");
    }
  } else {
    info("19", "三名检索账号没注册成功，第 19、20 步整体跳过（根因在第 18 步同一段注册逻辑上）", "");
  }

  console.log("");
  console.log("冒烟汇总：" + rows.length + " 项，断言 " + (rows.filter(function (x) { return x.ok !== null; }).length)
    + " 条，失败 " + failures + " 条");
  process.exitCode = failures === 0 ? 0 : 1;
}

main().catch(function (e) {
  console.error("冒烟脚本异常（后端是否已启动？node docs/smoke.mjs）：", e && e.message ? e.message : e);
  process.exitCode = 1;
});
