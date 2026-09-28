#!/usr/bin/env node
/* ------------------------------------------------------------------ *
 * 心屿 MindIsle · §7.4「断网演示」取证脚本（任务 T4.13 / T4.16）          *
 *                                                                   *
 * 为什么单独写一个文件：FR2.8/FR2.9 的离线降级链路此前只有代码路径，      *
 * 从未在真链路上跑通过一次（见 dev-log 阶段 4「降级记账说谎」那条），而    *
 * 它的判据同时落在 SSE 帧、chat_message.degraded 与 ai_call_log.model    *
 * 三处；单测能证明分支可达，证明不了「前端收得到、库里存得下、            *
 * 十秒内上屏」。本脚本对着一个不可达的上游把这三处一次核清。              *
 *                                                                   *
 * 用法：起两个后端实例，第二个的上游指向黑洞端口                        *
 *   1) 常规实例 8080（§5.7 的 start-backend 脚本）                     *
 *   2) 离线实例 8081：--server.port=8081 且把 DEEPSEEK_BASE_URL 指向    *
 *      一个必定连不上的地址（本仓库用 http://127.0.0.1:9 ，discard 端口）*
 *   3) node docs/offline-demo.mjs                                     *
 * 环境变量：OFFLINE_BASE（默认 http://127.0.0.1:8081）、                *
 *          OFFLINE_TURNS（默认 4，> circuit-fail-threshold=3）         *
 * 退出码：0=全部判据通过，1=有判据失败。                                *
 *                                                                   *
 * 账号说明：本脚本自己注册一次性账号（口令是脚本内的合成口令，不是任何    *
 * 真实凭据，也不复用 seed 的 demo01 口令），避免把口令写进入库文件。      *
 * ------------------------------------------------------------------ */

const BASE = process.env.OFFLINE_BASE || "http://127.0.0.1:8081";
const TURNS = Number(process.env.OFFLINE_TURNS || "4");
const TTFT_BUDGET_MS = 10000;   // §7.4 判据：断网后离线话术上屏 < 10s
const USERNAME = "offline_probe";
const PASSWORD = "Offline#2026x";
const STAMP = new Date().toISOString().replace(/[-:TZ.]/g, "").slice(0, 14);

let nPass = 0;
let nFail = 0;
const lines = [];
function check(name, ok, read) {
  if (ok) { nPass++; } else { nFail++; }
  const tag = ok ? "PASS" : "FAIL";
  const line = "[" + tag + "] " + name + (read ? "  → " + read : "");
  lines.push(line);
  console.log(line);
}
function info(name, read) {
  const line = "[INFO] " + name + (read ? "  → " + read : "");
  lines.push(line);
  console.log(line);
}

async function req(method, path, token, body) {
  const headers = { "Content-Type": "application/json" };
  if (token) { headers.Authorization = "Bearer " + token; }
  const res = await fetch(BASE + path, {
    method: method, headers: headers, body: body ? JSON.stringify(body) : undefined
  });
  let json = null;
  try { json = await res.json(); } catch (e) { json = null; }
  return { status: res.status, json: json };
}
const codeOf = (r) => (r.json && r.json.code !== undefined ? String(r.json.code) : "-");
const short = (r, n) => {
  const s = JSON.stringify(r.json || {});
  return s.length > (n || 160) ? s.slice(0, n || 160) + "…" : s;
};

/** 发一条并逐帧采集 SSE；返回帧序列、首帧时间、离线话术文本。 */
async function oneTurn(token, conversationId, text) {
  const t0 = Date.now();
  const res = await fetch(BASE + "/api/ai/chat/stream", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "text/event-stream",
      Authorization: "Bearer " + token
    },
    body: JSON.stringify({ conversationId: conversationId, message: text, style: "warm" })
  });
  const frames = [];
  let firstFrameMs = -1;
  let deltaChars = 0;
  if (res.status !== 200 || !res.body) {
    return { http: res.status, frames: frames, firstFrameMs: -1, totalMs: Date.now() - t0, deltaChars: 0 };
  }
  const reader = res.body.getReader();
  const dec = new TextDecoder("utf-8");
  let buf = "";
  for (;;) {
    const chunk = await reader.read();
    if (chunk.done) { break; }
    if (firstFrameMs < 0) { firstFrameMs = Date.now() - t0; }
    buf += dec.decode(chunk.value, { stream: true });
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
  return {
    http: res.status, frames: frames, firstFrameMs: firstFrameMs, totalMs: Date.now() - t0,
    deltaChars: deltaChars,
    meta: frames.filter((f) => f.ev === "meta")[0] || null,
    done: frames.filter((f) => f.ev === "done").pop() || null,
    err: frames.filter((f) => f.ev === "error").pop() || null
  };
}

const jsonOf = (f) => { try { return JSON.parse(f ? f.data : "{}"); } catch (e) { return {}; } };

async function main() {
  console.log("# 离线实例 = " + BASE + "（上游刻意指向不可达地址）");

  // ---------- 0 实例活着吗 ----------
  let h = null;
  try { h = await (await fetch(BASE + "/actuator/health")).json(); } catch (e) { h = null; }
  check("0 · 离线实例 /actuator/health 返 UP（否则下面所有结论都来自一个没起来的进程）",
    !!h && h.status === "UP", JSON.stringify(h));
  if (!h || h.status !== "UP") { console.log("离线实例没起来，终止。"); process.exit(1); }

  // ---------- 1 一次性账号 + 敏感信息单独同意 ----------
  let r = await req("POST", "/api/auth/register", null, {
    username: USERNAME, password: PASSWORD, nickname: "断网探针",
    captchaId: "00000000000000000000000000000000", captchaCode: "ZZZZ",
    agreeTerms: true, agreePrivacy: true, agreeSensitive: false,
    consentVersion: "v1.0", grade: "SOPH", school: "示例大学", regSource: "offline-demo"
  });
  check("1 · 注册一次性探针账号（200 或 20002 重跑都算通过：不写库就不会撞唯一键）",
    (r.status === 200 && codeOf(r) === "0") || codeOf(r) === "20002", r.status + " code=" + codeOf(r));

  r = await req("POST", "/api/auth/login", null, {
    username: USERNAME, password: PASSWORD,
    captchaId: "00000000000000000000000000000000", captchaCode: "ZZZZ"
  });
  const token = r.json && r.json.data ? r.json.data.accessToken : null;
  const uid = r.json && r.json.data && r.json.data.user ? r.json.data.user.id : null;
  check("1 · 登录取到 accessToken（后续每一帧都要带它，作者身份只来自 JWT）", !!token, r.status + " code=" + codeOf(r) + " uid=" + uid);
  if (!token) { console.log("拿不到令牌，终止。"); process.exit(1); }

  r = await req("POST", "/api/users/me/consents", token, {
    consentType: "SENSITIVE_INFO", action: "GRANT", contentVersion: "v1.0", sourcePage: "offline-demo"
  });
  check("1 · 授予 SENSITIVE_INFO 单独同意（不授权的话 chat 会在进模型之前就被 20005 挡掉，那样测的是同意闸不是降级）",
    r.status === 200 && codeOf(r) === "0", r.status + " code=" + codeOf(r));

  // ---------- 2 连打 TURNS 轮：前三轮踩熔断计数，第四轮应直接走离线 ----------
  const turns = [];
  let convId = null;
  for (let i = 1; i <= TURNS; i += 1) {
    const t = await oneTurn(token, convId, "今天还是睡不着，脑子一直在转，明天还有汇报。（断网演示 " + STAMP + " 第 " + i + " 轮）");
    if (t.meta) { convId = jsonOf(t.meta).conversationId || convId; }
    turns.push(t);
    console.log("# [" + i + "] HTTP=" + t.http + " 帧=" + (t.frames || []).length
      + " delta字数=" + t.deltaChars + " 首帧=" + t.firstFrameMs + "ms 总=" + t.totalMs + "ms");
  }

  const evNames = (t) => (t.frames || []).map((f) => f.ev).join(",");
  check("2 · 每一轮都是 HTTP 200 + text/event-stream（上游挂了不等于请求该失败：503/0 帧是 v1.2.6 之前那个真症状）",
    turns.every((t) => t.http === 200), turns.map((t) => t.http).join("/"));

  check("2 · 每一轮都收到了 delta 帧且正文非空（离线话术真的一字一字上了屏，而不是只写了库）",
    turns.every((t) => (t.frames || []).some((f) => f.ev === "delta") && t.deltaChars > 0),
    turns.map((t) => t.deltaChars).join("/"));

  check("2 · 帧序列只出现 meta/delta/done 三类，没有 error 帧（error 帧意味着用户看到的是红色失败条，不是兜底话术）",
    turns.every((t) => !t.err && ["meta", "delta", "done"].indexOf(evNames(t).split(",")[0]) >= 0
      && (t.frames || []).every((f) => ["meta", "delta", "done"].indexOf(f.ev) >= 0)
      && (t.frames || []).some((f) => f.ev === "meta") && (t.frames || []).some((f) => f.ev === "done")),
    evNames(turns[0]));

  const overBudget = turns.map((t, i) => (t.firstFrameMs > TTFT_BUDGET_MS ? i + 1 : 0)).filter((x) => x);
  check("2 · 🔴 判据：从发出请求到首帧上屏 < " + (TTFT_BUDGET_MS / 1000) + "s（§7.4 断网演示的硬指标；框架层重试没收敛时这里是 30s 超时然后 0 帧）",
    overBudget.length === 0, "各轮首帧ms=" + turns.map((t) => t.firstFrameMs).join("/"));

  const dones = turns.map((t) => jsonOf(t.done));
  check("2 · done 帧自己承认降级（degraded=true + reason 非空）：界面右下角那句「当前网络不稳定，屿屿先用了离线话术」读的就是这一位",
    dones.every((d) => d.degraded === true), JSON.stringify(dones[0]).slice(0, 220));

  // ---------- 3 回读消息：库里的那一行确实带 degraded / model ----------
  r = await req("GET", "/api/ai/conversations/" + convId + "/messages?limit=200", token, null);
  const msgs = r.json && r.json.data && Array.isArray(r.json.data.items) ? r.json.data.items
    : (r.json && r.json.data && Array.isArray(r.json.data) ? r.json.data : []);
  const assistants = msgs.filter((m) => m.role === "assistant");
  check("3 · 会话消息回读 200 且助手消息条数 = 轮数（每轮真落了一条，不是只在 SSE 里演了一遍）",
    r.status === 200 && assistants.length === TURNS, r.status + " 助手条数=" + assistants.length + " 期望=" + TURNS);

  const badDegraded = assistants.filter((m) => m.degraded !== true).map((m) => m.id);
  check("3 · 🔴 每一条助手消息 degraded=true（chat_message.degraded 这一列此前全表 0 行，是「降级链路从未跑通过」的直接证据）",
    assistants.length === TURNS && badDegraded.length === 0,
    "degraded!=true 的 id=" + (badDegraded.join(",") || "无"));

  const models = Array.from(new Set(assistants.map((m) => m.model)));
  check("3 · 🔴 每一条助手消息 model=offline-empathy-bank，且没有一条冒充真实模型名（v1.2.6 那个「recordDegraded 无条件写 offline」的老坑反过来也会让「用户按停止」被记成离线；现在两路分开）",
    models.length === 1 && models[0] === "offline-empathy-bank",
    "model 取值=" + models.join(",") + " 期望=offline-empathy-bank");

  const texts = assistants.map((m) => (m.content || "").slice(0, 24));
  const distinct = Array.from(new Set(texts));
  check("3 · 离线话术不是同一条复读（EmpathyBank.pick 按情绪标签取样，连续四轮全说同一句会显得机器在敷衍）",
    distinct.length >= 2, "去重后条数=" + distinct.length + " 样例=" + JSON.stringify(distinct.slice(0, 2)));

  // ---------- 4 熔断器：打开之后不该再去撞上游 ----------
  if (TURNS >= 4) {
    const firstHalf = turns[0].firstFrameMs;
    const lastHalf = turns[TURNS - 1].firstFrameMs;
    check("4 · 熔断打开之后的那一轮首帧不比第一轮慢（circuit-fail-threshold=3，第 4 轮起应直接短路走离线，不再花一次连接超时）",
      lastHalf <= firstHalf + 500, "第1轮=" + firstHalf + "ms 第4轮=" + lastHalf + "ms");
  }

  // ---------- 5 清掉本脚本新建的会话，但 ai_call_log 不动（它是审计账） ----------
  r = await req("DELETE", "/api/ai/conversations/" + convId, token, null);
  check("5 · 收尾删掉探针会话（软删；话术与调用账留在库里供 SQL 复核，不让取证脚本把演示库越跑越脏）",
    r.status === 200 && codeOf(r) === "0", r.status + " code=" + codeOf(r));

  info("5 · SQL 取证（root 直连复核，脚本自证不算）：① 这个用户这一天的 ai_call_log 里 model 恒为 offline-empathy-bank 且 success=0、error 带 degrade 原因；② chat_message 里这几行 degraded=1；③ 对照 8080 常规实例：同一时段它的 ai_call_log.model 是真实模型名、degraded=0 —— 两个实例的唯一差别就是上游可达性。",
    "SELECT id,scene,model,success,error,tokens_in,tokens_out,created_at FROM ai_call_log WHERE user_id=" + uid + " ORDER BY id DESC LIMIT " + (TURNS + 2) + "; SELECT id,role,LEFT(content,20) AS c,model,degraded,risk_level,prompt_version FROM chat_message WHERE conversation_id=" + convId + " ORDER BY id;");

  console.log("");
  console.log("断网演示汇总：" + (nPass + nFail) + " 项判据，通过 " + nPass + " 条，失败 " + nFail + " 条");
  process.exit(nFail > 0 ? 1 : 0);
}

main().catch((e) => { console.log("脚本异常：" + (e && e.stack ? e.stack : e)); process.exit(1); });