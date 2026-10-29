// perf.mjs — perfilar la página: qué JS está caliente mientras "cuelga"
import { spawn } from "node:child_process";

const appUrl = process.argv[2] ?? "http://localhost:5173/";
const warmupMs = Number(process.argv[3] ?? 3000);
const sampleMs = Number(process.argv[4] ?? 12000);
const seedExpr = process.argv[5] ?? "";

const br = spawn("/opt/brave-bin/brave", [
  "--headless=new", "--disable-gpu", "--no-sandbox",
  "--disable-dev-shm-usage", "--window-size=1200,900",
  "--remote-debugging-port=9333",
  "--user-data-dir=/tmp/opencode/brave-profile-" + Math.random().toString(36).slice(2, 8),
  "--no-first-run", "--no-default-browser-check",
  appUrl,
], { stdio: ["ignore", "pipe", "pipe"] });

let id = 0;
const pending = new Map();
function send(ws, method, params = {}) {
  return new Promise((res, rej) => {
    const i = ++id;
    pending.set(i, { res, rej });
    ws.send(JSON.stringify({ id: i, method, params }));
  });
}

function getWs(port, tries = 40) {
  return new Promise(async (res, rej) => {
    for (let i = 0; i < tries; i++) {
      try {
        const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
        const page = list.find((t) => t.type === "page");
        if (page) return res(page.webSocketDebuggerUrl);
      } catch { /* espera */ }
      await new Promise((r) => setTimeout(r, 300));
    }
    rej(new Error("no ws"));
  });
}

const wsUrl = await getWs(9333);
const ws = new WebSocket(wsUrl);
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) {
    const { res, rej } = pending.get(m.id);
    pending.delete(m.id);
    m.error ? rej(new Error(m.error.message)) : res(m.result);
  }
};
await new Promise((res) => (ws.onopen = res));
await send(ws, "Runtime.enable");
await send(ws, "Profiler.enable");
await send(ws, "Profiler.setSamplingInterval", { interval: 5 });
await send(ws, "Profiler.start");
console.log("profiler activo — warmup", warmupMs, "ms");
await new Promise((r) => setTimeout(r, warmupMs));

if (seedExpr) {
  // sin awaitPromise: volvemos de inmediato, dejamos correr la seed en el runtime
  const rs = await send(ws, "Runtime.evaluate", { expression: `(async () => { ${seedExpr} })()`, returnByValue: false });
  console.log("seed estado:", rs.exceptionDetails ? "EXC " + JSON.stringify(rs.exceptionDetails.exception?.description) : JSON.stringify(rs.result)?.slice(0, 120));
}


console.log(`⏱ muestreando ${sampleMs / 1000}s ...`);

await new Promise((r) => setTimeout(r, sampleMs));
let profile;
try {
  ({ profile } = await send(ws, "Profiler.stop"));
} catch (e) {
  console.log("Profiler.stop falló (runtime truncado?):", String(e).slice(0, 200));
  br.kill("SIGKILL");
  process.exit(1);
}

// agregar tiempo por nodo con self time
const nodesById = new Map(profile.nodes.map((n) => [n.id, n]));
const selfTime = new Map();
for (const s of profile.samples || []) {
  selfTime.set(s.nodeId, (selfTime.get(s.nodeId) || 0) + 1);
}
const rows = [];
for (const [nodeId, samples] of selfTime.entries()) {
  const n = nodesById.get(nodeId);
  if (!n) continue;
  const cf = n.callFrame;
  rows.push({
    samples,
    fn: cf.functionName || "(anónimo)",
    url: (cf.url || "").split("/").pop(),
    line: cf.lineNumber,
  });
}
rows.sort((a, b) => b.samples - a.samples);
console.table(rows.slice(0, 12));

// reconstruir stack del nodo más caliente
const hot = rows[0];
if (hot) {
  const n = Array.from(nodesById.values()).find((x) => (x.callFrame.functionName || "(anónimo)") === hot.fn && (x.callFrame.url || "").endsWith(hot.url));
  if (n) {
    let stack = [];
    let cur = n;
    while (cur && stack.length < 15) {
      stack.push(`${cur.callFrame.functionName || "(anónimo)"} @ ${cur.callFrame.url.split("/").pop()}:${cur.callFrame.lineNumber}`);
      const parent = profile.nodes.find((pn) => pn.children?.includes(cur.id));
      cur = parent;
    }
    console.log("\n=== STACK del nodo caliente ===");
    console.log(stack.join("\n"));
  }
}
br.kill("SIGKILL");
process.exit(0);
