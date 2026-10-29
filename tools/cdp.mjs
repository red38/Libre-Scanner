// Driver CDP mínimo (sin dependencias): crea una pestaña directamente sobre la app
import { spawn } from "node:child_process";

const appUrl = process.argv[2] ?? "http://localhost:5173/";
const script = process.argv[3] ?? "1+1";

const br = spawn("/opt/brave-bin/brave", [
  "--headless=new", "--disable-gpu", "--no-sandbox",
  "--disable-dev-shm-usage", "--window-size=1200,900",
  "--remote-debugging-port=9333",
  "--user-data-dir=/tmp/opencode/brave-profile-" + Math.random().toString(36).slice(2, 8),
  "--no-first-run", "--no-default-browser-check",
  "--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream",
  "about:blank",
], { stdio: ["ignore", "pipe", "pipe"] });

const target = await getTarget(9333);
const ws = new WebSocket(target.webSocketDebuggerUrl);

async function getTarget(port, tries = 40) {
  for (let i = 0; i < tries; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
      const page = list.find((t) => t.type === "page");
      if (page?.webSocketDebuggerUrl) return page;
    } catch { /* esperar */ }
    await new Promise((r) => setTimeout(r, 300));
  }
  throw new Error("CDP: no se pudo establecer la página");
}

let id = 0;
const pending = new Map();

function send(method, params = {}) {
  return new Promise((res, rej) => {
    const i = ++id;
    pending.set(i, { res, rej });
    ws.send(JSON.stringify({ id: i, method, params }));
  });
}

ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) {
    const { res, rej } = pending.get(m.id);
    pending.delete(m.id);
    m.error ? rej(new Error(m.error.message)) : res(m.result);
  } else if (m.method === "Runtime.consoleAPICalled") {
    const parts = m.params.args.map((a) => a.value ?? a.description ?? "");
    if (parts.join('').length) console.log("📄", ...parts);
  } else if (m.method === "Runtime.exceptionThrown") {
    const d = m.params.exceptionDetails;
    console.log("💥 EXCEPCIÓN:", d.text, d.exception?.description ?? "");
  }
};

await new Promise((res) => (ws.onopen = res));
await send("Runtime.enable");

// cargar la app en esta misma pestaña
await send("Page.navigate", { url: appUrl });
await new Promise((r) => setTimeout(r, 2500));

async function evaluate(expr) {
  const r = await send("Runtime.evaluate", {
    expression: `(async () => { ${expr} })()`,
    awaitPromise: true, returnByValue: true,
  });
  if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails).slice(0, 500));
  return r.result?.value;
}

try {
  console.log("▶ script:");
  const out = await evaluate(script);
  console.log("🏁 RESULTADO:", JSON.stringify(out)?.slice(0, 2500));
} catch (e) {
  console.log("⛔ FALLO:", String(e).slice(0, 600));
}
br.kill("SIGKILL");
process.exit(0);
