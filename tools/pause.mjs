// pause.mjs — pausa el runtime ocupado y muestra el stack del hilo que gira
import { spawn } from "node:child_process";

const appUrl = process.argv[2] ?? "http://localhost:5173/";
const warmupMs = Number(process.argv[3] ?? 2500);
const seedExpr = process.argv[4] ?? "";

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

async function getWs(port, tries = 40) {
  for (let i = 0; i < tries; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
      const page = list.find((t) => t.type === "page");
      if (page) return page.webSocketDebuggerUrl;
    } catch { }
    await new Promise((r) => setTimeout(r, 300));
  }
  throw new Error("no ws");
}

const wsUrl = await getWs(9333);
const ws = new WebSocket(wsUrl);
let pausedResult = null;

ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) {
    const { res, rej } = pending.get(m.id);
    pending.delete(m.id);
    m.error ? rej(new Error(m.error.message)) : res(m.result);
  } else if (m.method === "Debugger.paused") {
    pausedResult = m.params;
  }
};
await new Promise((res) => (ws.onopen = res));
await send(ws, "Runtime.enable");
await send(ws, "Debugger.enable");
await new Promise((r) => setTimeout(r, warmupMs));

if (seedExpr) {
  const r = await send(ws, "Runtime.evaluate", { expression: `(async () => { ${seedExpr} })()`, returnByValue: false });
  console.log("seed:", JSON.stringify(r.result)?.slice(0, 100));
}

// dar tiempo a que el busy-loop arranque
await new Promise((r) => setTimeout(r, 3000));

// pausa el hilo ocupado
await send(ws, "Debugger.pause");
await new Promise((r) => setTimeout(r, 1500));
if (pausedResult) {
  console.log("\n🎯 HILO PAUSADO — STACK:");
  for (const f of pausedResult.callFrames.slice(0, 14)) {
    console.log(`  ${f.functionName || "(anónimo)"} @ ${(f.url || "").split("/").pop()}:${f.location?.lineNumber}`);
  }
} else {
  console.log("no se pausó nada (runtime muerto o ocupa otra cosa) — Debugger.paused jamás llegó");
}
br.kill("SIGKILL");
process.exit(0);
