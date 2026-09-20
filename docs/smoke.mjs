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

    // 未过审话题走 409/30004：本环境 20 条种子话题全部 APPROVED，而话题提交接口属任务 T3.4 还没做，
    // 脚本没有任何合法途径造出一条待审话题，所以这条要真跑得靠外部预置 + 环境变量把 id 传进来。
    const pendingId = Number(process.env.SMOKE_PENDING_TOPIC_ID || "0");
    if (pendingId > 0) {
      r = await post({ title: "冒烟·负面", content: "挂一个待审话题", topicIds: [pendingId] });
      check("9", "话题未过审 → 409/30004（用户能做的是等，不是改，所以不给 400）",
        r.status === 409 && code(r) === "30004", brief(r));
    } else {
      info("9", "未提供 SMOKE_PENDING_TOPIC_ID，30004 分支本轮拿不到 HTTP 证据",
        "该分支需要有「一条真实存在但未过审」的话题才打得动：本环境 20 条种子话题全部 APPROVED，"+
        "话题提交接口又属 T3.4 尚未实现，脚本无法自己造出这条数据 —— 已记进 dev-log 的已知缺口");
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

  console.log("");
  console.log("冒烟汇总：" + rows.length + " 项，断言 " + (rows.filter(function (x) { return x.ok !== null; }).length)
    + " 条，失败 " + failures + " 条");
  process.exitCode = failures === 0 ? 0 : 1;
}

main().catch(function (e) {
  console.error("冒烟脚本异常（后端是否已启动？node docs/smoke.mjs）：", e && e.message ? e.message : e);
  process.exitCode = 1;
});
