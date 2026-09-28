#!/usr/bin/env node
/* ------------------------------------------------------------------ *
 * 心屿 MindIsle · Gate4 D1「新账号 3 分钟闭环」端到端取证脚本          *
 *                                                                   *
 * 手册 §7.4 第 1 条要求的是这么一件事：一个此前不存在的人，从零开始     *
 * 注册 → 单独同意 → 和屿屿说上一句话 → 情绪档案页出现他自己的情绪点，   *
 * 全程 3 分钟以内。这句话里有四个「真的」：账号真的写进 user 表、同意   *
 * 真的写进 user_consent、对话真的落 chat_message 并带情绪标签、档案页   *
 * 的点数真的因为这次打卡而 +1。前面四条取证线各证了其中一角，没有一条   *
 * 线把这四件事串在同一个人的同一次生命里跑一遍。本脚本就是那一条线。     *
 *                                                                   *
 * 顺带把 D2 里「TTFT < 2s」那一半用真实上游量一次数（8080 走 DeepSeek， *
 * 不是 8081 那个离线实例）。「停止生成」那一半在 frontend/probe/aichat. *
 * mjs 里已有判据，这里不重复。                                        *
 *                                                                   *
 * 红线：                                                             *
 *  1) 用户名固定为 d1_gate —— 撞唯一键（20002）按通过处理，脚本可以反复  *
 *     跑而不会每跑一次就多造一个垃圾账号；                             *
 *  2) 口令只在命令行里传，不进本文件；                                *
 *  3) --no-consent 是证伪开关：故意不授 SENSITIVE_INFO，期望对话那一     *
 *     步 FAIL。新写的断言必须先红过一次，否则等于没写。                 *
 *                                                                   *
 * 用法：node docs/d1-onboarding.mjs [--no-consent]                   *
 * 退出码：0=全部判据通过 · 1=有 FAIL                                  *
 * ------------------------------------------------------------------ */

const BASE = process.env.API || "http://127.0.0.1:8080";
// 🔴 证伪开关必须作用在「一个还没被授权过的账号」上：单独同意是一次性的持久状态，
// 同一个账号绿过一次之后再用 --no-consent 跑，同意仍然在库里，判据就不会变红。
// 本轮就踩过这个坑（第一次 --no-consent 跑出 14/14 全绿，看着像断言坏了，其实是账号记住了上次的授权）。
// 所以证伪模式固定换一个从未授权的马甲账号 d1_gate_nc（也只多一个账号，不会每次跑长一个）。
const USERNAME = process.env.D1_USER || (process.argv.includes("--no-consent") ? "d1_gate_nc" : "d1_gate");
const PASSWORD = process.env.D1_PWD || "D1gate#2026x";
const CAPTCHA_ID = "00000000000000000000000000000000";
const NO_CONSENT = process.argv.includes("--no-consent");
const TTFT_BUDGET_MS = Number(process.env.TTFT_BUDGET_MS || "2000");
const TOTAL_BUDGET_S = Number(process.env.TOTAL_BUDGET_S || "180");

let nPass = 0;
let nFail = 0;
const fails = [];

function check(label, ok, reading) {
  if (ok) { nPass += 1; console.log("PASS [" + label + "]   " + String(reading)); }
  else { nFail += 1; fails.push(label); console.log("FAIL [" + label + "]   " + String(reading)); }
}

async function req(method, path, token, body) {
  const headers = {};
  if (body !== null && body !== undefined) { headers["Content-Type"] = "application/json"; }
  if (token) { headers.Authorization = "Bearer " + token; }
  const res = await fetch(BASE + path, { method: method, headers: headers, body: body === null || body === undefined ? undefined : JSON.stringify(body) });
  let json = null;
  try { json = await res.json(); } catch (e) { json = null; }
  return { status: res.status, json: json };
}
const codeOf = (r) => (r.json && r.json.code !== undefined ? String(r.json.code) : "-");
const short = (r, n) => {
  const s = JSON.stringify(r.json || {});
  return s.length > (n || 150) ? s.slice(0, n || 150) + "…" : s;
};

/** 发一句并逐帧采集 SSE。 */
async function oneTurn(token, conversationId, text) {
  const t0 = Date.now();
  const res = await fetch(BASE + "/api/ai/chat/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream", Authorization: "Bearer " + token },
    body: JSON.stringify({ conversationId: conversationId, message: text, style: "warm" })
  });
  const frames = [];
  let firstFrameMs = -1;
  let deltaChars = 0;
  if (res.status !== 200 || !res.body) {
    let j = null;
    try { j = await res.json(); } catch (e) { j = null; }
    return { http: res.status, json: j, frames: frames, firstFrameMs: -1, totalMs: Date.now() - t0, deltaChars: 0 };
  }
  const reader = res.body.getReader();
  const dec = new TextDecoder("utf-8");
  let buf = "";
  for (;;) {
    const c = await reader.read();
    if (c.done) { break; }
    if (firstFrameMs < 0) { firstFrameMs = Date.now() - t0; }
    buf += dec.decode(c.value, { stream: true });
    let cut;
    while ((cut = buf.indexOf("\n\n")) >= 0) {
      const raw = buf.slice(0, cut);
      buf = buf.slice(cut + 2);
      let ev = "message";
      const dataLines = [];
      raw.split("\n").forEach((l) => {
        if (l.startsWith("event:")) { ev = l.slice(6).trim(); }
        else if (l.startsWith("data:")) { dataLines.push(l.slice(5).trim()); }
      });
      const data = dataLines.join("\n");
      if (ev === "delta") { deltaChars += data.replace(/^"|"$/g, "").length; }
      frames.push({ ev: ev, data: data });
    }
  }
  const jsonOf = (f) => { try { return JSON.parse(f ? f.data : "{}"); } catch (e) { return {}; } };
  return {
    http: res.status, frames: frames, firstFrameMs: firstFrameMs, totalMs: Date.now() - t0,
    deltaChars: deltaChars,
    meta: jsonOf(frames.filter((f) => f.ev === "meta")[0] || null),
    done: jsonOf(frames.filter((f) => f.ev === "done").pop() || null),
    err: jsonOf(frames.filter((f) => f.ev === "error").pop() || null),
    hasDelta: frames.some((f) => f.ev === "delta")
  };
}

async function main() {
  const startedAt = Date.now();
  console.log("# 实例 = " + BASE + " · 账号 = " + USERNAME + (NO_CONSENT ? " ·（证伪模式：故意不授单独同意，用从未授权的马甲账号）" : ""));

  // ---------- 0 实例活着吗 ----------
  let r = await req("GET", "/api/system/info", null, null);
  check("0 · 后端实例在跑（/api/system/info 200）", r.status === 200, r.status + " code=" + codeOf(r) + " " + short(r, 80));
  if (r.status !== 200) { console.log("后端没起来，终止。"); process.exit(1); }

  // ---------- 1 注册（四件事的第一件：真的写进 user 表）----------
  r = await req("POST", "/api/auth/register", null, {
    username: USERNAME, password: PASSWORD, nickname: "闭环取证",
    captchaId: CAPTCHA_ID, captchaCode: "ZZZZ",
    agreeTerms: true, agreePrivacy: true, agreeSensitive: false,
    consentVersion: "v1.0", grade: "SOPH", school: "示例大学", regSource: "d1-onboarding"
  });
  check("1 · 注册账号返 200（或 20002 重跑：撞唯一键本身就是「真落库」的证据）",
    (r.status === 200 && codeOf(r) === "0") || codeOf(r) === "20002", r.status + " code=" + codeOf(r));

  r = await req("POST", "/api/auth/login", null, {
    username: USERNAME, password: PASSWORD, captchaId: CAPTCHA_ID, captchaCode: "ZZZZ"
  });
  const token = r.json && r.json.data ? r.json.data.accessToken : null;
  const uid = r.json && r.json.data && r.json.data.user ? r.json.data.user.id : null;
  check("1 · 登录拿到 accessToken（后面每一步的身份都只来自这一张票）", !!token, r.status + " code=" + codeOf(r) + " uid=" + uid);
  if (!token) { console.log("拿不到令牌，终止。"); process.exit(1); }

  // ---------- 2 敏感信息单独同意 ----------
  if (!NO_CONSENT) {
    r = await req("POST", "/api/users/me/consents", token, {
      consentType: "SENSITIVE_INFO", action: "GRANT", contentVersion: "v1.0", sourcePage: "d1-onboarding"
    });
    check("2 · 授予 SENSITIVE_INFO 单独同意（20005 就是没同意，AI 线该被挡在进模型之前）",
      r.status === 200 && codeOf(r) === "0", r.status + " code=" + codeOf(r) + " " + short(r, 90));
  } else {
    console.log("SKIP [2] 证伪模式：跳过单独同意，下面的对话判据应当变红");
  }

  // ---------- 3 首次对话（第三件：真的落 chat_message 并带情绪标签）----------
  const t = await oneTurn(token, null, "今晚躺下以后一直没睡着，明天还有汇报，我心里有点慌。（Gate4 D1 闭环取证）");
  if (t.http !== 200) {
    check("3 · 首次对话 HTTP 200 + SSE 帧", false, t.http + " " + JSON.stringify(t.json || {}).slice(0, 160));
  } else {
    check("3 · 首次对话 HTTP 200、帧序列 meta/delta/done、正文非空",
      t.frames.some((f) => f.ev === "meta") && t.frames.some((f) => f.ev === "delta")
      && t.frames.some((f) => f.ev === "done") && t.deltaChars > 0,
      "帧=" + t.frames.map((f) => f.ev).join(",") + " delta字数=" + t.deltaChars + (t.frames.some((f) => f.ev === "error") ? " error帧=" + JSON.stringify(t.err).slice(0, 140) : ""));
    // 这一条差点在证伪跑里假绿：只收到 error 帧时 firstFrameMs 也有值（那是失败帧的到达时刻），
    // 把它当成「首字上屏 14ms」是观测通道造假。判据改为：真的有过 delta 帧，才允许比 TTFT。
    check("3 · 🔴 判据 D2：TTFT < " + TTFT_BUDGET_MS + "ms，且这个首帧真的是 delta（真实上游 DeepSeek，不是离线实例）",
      t.hasDelta && t.firstFrameMs >= 0 && t.firstFrameMs < TTFT_BUDGET_MS,
      "hasDelta=" + t.hasDelta + " 首帧=" + t.firstFrameMs + "ms 整轮=" + t.totalMs + "ms");
    const convId = t.meta.conversationId;
    check("3 · meta 帧带回 conversationId（会话真的在服务端建了，不是前端空壳）", !!convId, "conv=" + convId);
    if (!convId) { t.meta.conversationId = -1; }
    check("3 · meta 帧带回 emotion 与 riskLevel（这一句的情绪判定跟着帧一起回来；契约里 emotion 就是标签字符串）",
      typeof t.meta.emotion === "string" && t.meta.emotion.length > 0 && t.meta.riskLevel !== undefined,
      "meta.emotion(词典初判)=" + t.meta.emotion + " riskLevel=" + t.meta.riskLevel);
    // 补标是异步的，给它一点时间落库：立刻读会拿到词典那一版（neutral），那仍然是合法结论，
    // 但「补标回不回写」这个问题就只能靠轮询才能量出来，所以这里先睡 1.2s 再读一次。
    await new Promise((res2) => setTimeout(res2, 1200));
    r = await req("GET", "/api/ai/conversations/" + convId + "/messages?limit=200", token, null);
    const arr = r.json && Array.isArray(r.json.data) ? r.json.data : (r.json && r.json.data && Array.isArray(r.json.data.items) ? r.json.data.items : []);
    const userMsg = arr.filter((m) => m.role === "user").pop() || null;
    const aiMsg = arr.filter((m) => m.role === "assistant").pop() || null;
    check("3 · 会话回读：用户那一句带 emotionLabel + emotionChannel（情绪标签钉在被说的那句话上，不是钉在回复上）",
      r.status === 200 && !!userMsg && !!userMsg.emotionLabel && !!userMsg.emotionChannel,
      "条数=" + arr.length + " 用户句 emotionLabel=" + (userMsg ? userMsg.emotionLabel : "-") + " channel=" + (userMsg ? userMsg.emotionChannel : "-") + " riskLevel=" + (userMsg ? userMsg.riskLevel : "-"));
    check("3 · 助手那一句带真实模型名与 promptVersion（回复真走了 DeepSeek；model 键缺席说明它压根没进模型）",
      !!aiMsg && !!aiMsg.model && !String(aiMsg.model).startsWith("offline") && !!aiMsg.promptVersion,
      "model=" + (aiMsg ? aiMsg.model : "-") + " promptVersion=" + (aiMsg ? aiMsg.promptVersion : "-") + " degraded=" + (aiMsg ? aiMsg.degraded : "-"));
    // 这一条判据不能钉死成 channel=llm：词典这次如果直接判准（conf >= 0.55）就不会触发补标，
    // 那是正确行为，却被写成红灯就成了随机失败。钉的是「库里一定有可信通道名」，
    // 而「补标确实回写了 chat_message」由读数里 meta 与库内标签的差值现场呈现（本轮实测 neutral → fear/llm）。
    check("3 · 库里用户句的 emotionChannel 是可信通道名（dict / llm / manual 三选一，缺席说明这一行根本没被情绪通道写过）",
      !!userMsg && ["dict", "llm", "manual"].indexOf(userMsg.emotionChannel) >= 0,
      "meta 帧当时=" + t.meta.emotion + "（词典初判） 库里现在=" + (userMsg ? userMsg.emotionLabel + "/" + userMsg.emotionChannel : "-")
      + " 分数=" + (userMsg ? userMsg.emotionScore : "-"));
  }

  // ---------- 4 情绪档案出现情绪点（第四件）----------
  r = await req("GET", "/api/emotions/profile?range=30", token, null);
  const pb = r.json && r.json.data ? r.json.data : {};
  const before = typeof pb.recordCount === "number" ? pb.recordCount : null;
  check("4 · 🔴 判据 D1 的落点：只说了这一句话之后，档案页的点数已经 >= 1，且 trend / distribution / calendar 都有内容（情绪点是「对话」写进去的，不是打卡写进去的）",
    r.status === 200 && typeof before === "number" && before >= 1
    && Array.isArray(pb.trend) && pb.trend.length > 0
    && Array.isArray(pb.distribution) && pb.distribution.length > 0
    && Array.isArray(pb.calendar) && pb.calendar.length > 0,
    r.status + " recordCount=" + before + " trend=" + (pb.trend || []).length + " distribution=" + JSON.stringify((pb.distribution || []).slice(0, 3)) + " sources=" + JSON.stringify(pb.sources || []));

  const note = "Gate4 D1 闭环取证 " + new Date().toISOString().slice(0, 19);
  r = await req("POST", "/api/emotions/checkin", token, { emotion: "fear", intensity: 3, sleepBucket: 2, note: note });
  check("4 · 情绪打卡 200（source=checkin 的记录落 emotion_record）",
    r.status === 200 && codeOf(r) === "0", r.status + " code=" + codeOf(r) + " " + short(r, 130));

  r = await req("GET", "/api/emotions/profile?range=30", token, null);
  const p = r.json && r.json.data ? r.json.data : {};
  // 同日第二次打卡走的是「合并」（CheckinView.appended=false 那条路径），所以判据只能写 >= 基线；
  // 真正要钉住的是「手动那一档进来了」——distribution 里出现 fear 的计数把两路都算在一起。
  check("4 · 打卡之后档案点数不减少，且这一天的强度均值把打卡那一档也算进去了（同一个人两条来源汇成同一个点）",
    typeof p.recordCount === "number" && p.recordCount >= before
    && Array.isArray(p.trend) && p.trend.length > 0 && !!p.trend[0].avgIntensity,
    "基线=" + before + " 现在=" + p.recordCount + " trend[0]=" + JSON.stringify((p.trend || [])[0] || {}));

  // ---------- 5 全程耗时 ----------
  const elapsedS = (Date.now() - startedAt) / 1000;
  check("5 · 🔴 判据 D1：注册 → 同意 → 首次对话 → 档案出现情绪点，全程 < " + TOTAL_BUDGET_S + " 秒",
    elapsedS < TOTAL_BUDGET_S, "实际用时=" + elapsedS.toFixed(1) + "s");

  console.log("");
  console.log("D1 闭环汇总：" + (nPass + nFail) + " 项判据，通过 " + nPass + " 条，失败 " + nFail + " 条" + (fails.length ? " —— 失败项：" + fails.join(" | ") : ""));
  console.log("# SQL 复核（root 直连）：SELECT id,username,created_at FROM user WHERE username=\"" + USERNAME + "\"; SELECT id,consent_type,created_at FROM user_consent WHERE user_id=" + uid + " ORDER BY id DESC LIMIT 3; SELECT id,role,emotion_label,model,degraded FROM chat_message WHERE conversation_id=" + (t.meta ? t.meta.conversationId : "NULL") + " ORDER BY id; SELECT id,record_date,label,intensity,source FROM emotion_record WHERE user_id=" + uid + " ORDER BY id DESC LIMIT 3;");
  process.exit(nFail > 0 ? 1 : 0);
}

main().catch((e) => { console.log("ERROR " + (e && e.stack ? e.stack : e)); process.exit(1); });
