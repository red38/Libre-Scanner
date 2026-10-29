import { detectQuad, warpQuad, applyFilter, orderQuad } from "./cv";
import { runOcr } from "./ocr";
import { exportPdf } from "./pdf";
import type { Doc, Page } from "./store";
import { listDocs, putDoc, getDoc, putPage, putPages, getPage, delPage, deleteDoc, uid } from "./store";

// ---------- estado ----------
let docs: Doc[] = [];
let current: Doc | undefined;
let pages: Page[] = [];            // páginas del doc actual (metadatos + blobs)
const pageCache = new Map<string, HTMLCanvasElement>(); // processed per page id

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;

// ---------- tabs ----------
const tabs = ["scan", "editor", "ocr", "docs"] as const;
for (const t of tabs) {
  $(`tab-${t}`).onclick = () => showTab(t);
}
function showTab(t: (typeof tabs)[number]) {
  for (const x of tabs) {
    $(`tab-${x}`).classList.toggle("active", x === t);
    ($(`view-${x}`) as HTMLElement).hidden = x !== t;
  }
  if (t === "docs") renderDocs();
  if (t === "editor") updateEdNav(true);
}

// ---------- arrancar ----------
async function init() {
  docs = await listDocs();
  refreshDocSelect();
  $(`new-doc`).onclick = createDoc;
  $(`import-file`).onclick = () => ($(`file-input`) as HTMLInputElement).click();
  ($(`file-input`) as HTMLInputElement).onchange = onFilesPicked;
  await refreshCurrent();
  setupCamera();
  setupEditor();
  setupOcr();
  setupDocs();
}
async function createDoc() {
  const n = docs.length + 1;
  const doc: Doc = { id: uid(), name: `Documento ${n}`, created: Date.now(), pageIds: [] };
  await putDoc(doc);
  docs = await listDocs();
  current = doc;
  refreshDocSelect();
  await refreshCurrent();
}
async function refreshCurrent() {
  pages = [];
  if (!current) await pickFirstDoc();
  refreshDocSelect();
  if (current) {
    pages = (await Promise.all(current.pageIds.map(getPage))).filter(Boolean) as Page[];
  }
  pageCache.clear();
  updateEdNav();
  updateExportBtn();
}

async function pickFirstDoc() {
  current = docs[0];
}
function refreshDocSelect() {
  const sel = $(`doc-select`) as HTMLSelectElement;
  sel.innerHTML = "";
  for (const d of docs) {
    const o = document.createElement("option");
    o.value = d.id;
    o.textContent = d.name;
    sel.appendChild(o);
  }
  if (current) sel.value = current.id;
}
($(`doc-select`) as HTMLSelectElement).onchange = async (e) => {
  const id = (e.target as HTMLSelectElement).value;
  current = docs.find((d) => d.id === id);
  await refreshCurrent();
};

// ---------- importar archivos ----------
async function onFilesPicked(e: Event) {
  const files = (e.target as HTMLInputElement).files;
  if (!files?.length) return;
  showProgress("Importando...");
  if (!current) await createDoc();
  for (const f of Array.from(files)) {
    await addPageFromBlob(f);
    progressTick();
  }
  hideProgress();
  ($(`file-input`) as HTMLInputElement).value = "";
  await refreshCurrent();
  showTab("editor");
}

async function addPageFromBlob(blob: Blob): Promise<Page> {
  let quad: number[] | null = null;
  try {
    const q = await detectQuad(blob);
    if (q) quad = q.pts.flatMap((p) => [p.x, p.y]);
  } catch { /* sin detección, página sin recorte */ }
  const page: Page = { id: uid(), original: blob, quad, filter: "magic" };
  pages.push(page);
  await persistPages();
  return page;
}

async function persistPages() {
  if (!current) return;
  current.pageIds = pages.map((p) => p.id);
  await putDoc(current);
  await putPages(pages);
}

// ---------- cámara ----------
let stream: MediaStream | null = null;
let devices: MediaDeviceInfo[] = [];

async function setupCamera() {
  try {
    devices = (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === "videoinput");
    const sel = $(`camera-select`) as HTMLSelectElement;
    sel.innerHTML = "";
    for (const d of devices) {
      const o = document.createElement("option");
      o.value = d.deviceId;
      o.textContent = d.label || d.deviceId.slice(0, 24);
      sel.appendChild(o);
    }
    sel.onchange = () => startCamera(sel.value);
    await startCamera(selectRearId());
    ($(`capture`) as HTMLButtonElement).disabled = false;
    ($(`capture`) as HTMLButtonElement).onclick = capture;
  } catch (err) {
    showNote("📷 Cámara no disponible — usa 'Importar' con archivos de imagen");
  }
}
function selectRearId() {
  const rear = devices.find((d) => /back|rear|environment/i.test(d.label));
  return rear?.deviceId ?? devices[0]?.deviceId ?? "";
}
async function startCamera(deviceId?: string) {
  stopCamera();
  const constraints: MediaStreamConstraints = deviceId
    ? { video: { deviceId: { exact: deviceId }, width: { ideal: 2560 } } }
    : { video: { facingMode: { ideal: "environment" }, width: { ideal: 2560 } } };
  try {
    stream = await navigator.mediaDevices.getUserMedia(constraints);
  } catch {
    stream = await navigator.mediaDevices.getUserMedia({ video: true });
  }
  ($("camera") as HTMLVideoElement).srcObject = stream;
}
function stopCamera() {
  stream?.getTracks().forEach((t) => t.stop());
  stream = null;
}
async function capture() {
  const v = $("camera") as HTMLVideoElement;
  if (!v.videoWidth) return;
  const c = document.createElement("canvas");
  c.width = v.videoWidth; c.height = v.videoHeight;
  c.getContext("2d")!.drawImage(v, 0, 0);
  const blob: Blob = await new Promise((r) => c.toBlob((b) => r(b!), "image/jpeg", 0.95));
  showProgress("Capturando y detectando bordes...");
  await addPageFromBlob(blob);
  hideProgress();
  await refreshCurrent();
  showTab("editor");
}

// ---------- editor ----------
let edIdx = 0;
let edQuad: { x: number; y: number }[] | null = null;

function setupEditor() {
  $("ed-prev").onclick = () => { edIdx = Math.max(0, edIdx - 1); updateEdNav(true); };
  $("ed-next").onclick = () => { edIdx = Math.min(pages.length - 1, edIdx + 1); updateEdNav(true); };
  $("ed-auto").onclick = async () => {
    const p = pages[edIdx];
    if (!p) return;
    const q = await detectQuad(p.original);
    edQuad = q ? orderQuad(q.pts).pts : null;
    renderEditor();
  };
  $("ed-rot-l").onclick = () => rotatePage(-90);
  $("ed-rot-r").onclick = () => rotatePage(90);
  ($("ed-filter") as HTMLSelectElement).onchange = async (e) => {
    const p = pages[edIdx];
    if (p) { p.filter = (e.target as HTMLSelectElement).value; pageCache.delete(p.id); await putPage(p); }
    renderEditor();
  };
  $("ed-done").onclick = async () => {
    // guarda la página actual con el recorte/filtro visualizados
    const p = pages[edIdx];
    let count = 0;
    if (p) {
      p.quad = edQuad ? edQuad.flatMap((q) => [q.x, q.y]) : null;
      pageCache.delete(p.id);
      await putPage(p);
      count++;
    }
    // guardar TODAS las páginas del documento: a las que no les haya quad les
    // corre la detección automática; cada una conserva su propio filtro
    showProgress(`Guardando documento (página 1 de ${pages.length})...`);
    try {
      for (let i = 0; i < pages.length; i++) {
        const pg = pages[i];
        if (pg === p) continue;
        if (!pg.quad) {
          const q = await detectQuad(pg.original);
          if (q) pg.quad = orderQuad(q.pts).pts.flatMap((pt) => [pt.x, pt.y]);
        }
        pageCache.delete(pg.id);
        await putPage(pg);
        count++;
        showProgress(`Guardando documento (página ${i + 1} de ${pages.length})...`);
      }
    } finally {
      hideProgress();
    }
    await persistPages();
    showNote(`✅ Documento guardado: ${count} páginas`);
  };
  $("ed-page-del").onclick = async () => {
    const p = pages[edIdx];
    if (!p) return;
    if (!confirm("¿Eliminar esta página?")) return;
    pages.splice(edIdx, 1);
    await delPage(p.id);
    edIdx = Math.max(0, Math.min(edIdx, pages.length - 1));
    await persistPages();
    await refreshCurrent();
  };
  const cv = $("ed-canvas") as HTMLCanvasElement;
  cv.style.touchAction = "none";
  cv.addEventListener("pointerdown", (e) => {
    const i = hitCorner(e);
    if (i >= 0) {
      dragI = i;
      try { cv.setPointerCapture(e.pointerId); } catch { /* sin captura */ }
      e.preventDefault();
    }
  });
  cv.addEventListener("pointermove", (e) => {
    if (dragI < 0 || !edQuad) return;
    const pt = canvasPoint(e);
    // mantener la esquina dentro de la imagen
    pt.x = Math.max(0, Math.min(lastBmp.w - 1, pt.x));
    pt.y = Math.max(0, Math.min(lastBmp.h - 1, pt.y));
    edQuad[dragI] = pt;
    renderEditor();
    e.preventDefault();
  });
  const drop = () => { dragI = -1; };
  cv.addEventListener("pointerup", drop);
  cv.addEventListener("pointercancel", drop);
  cv.addEventListener("lostpointercapture", drop);
}

let dragI = -1;

/** convierte coords de puntero a coords de la imagen original */
function canvasPoint(e: PointerEvent) {
  const cv = $("ed-canvas") as HTMLCanvasElement;
  const r = cv.getBoundingClientRect();
  const x = (e.clientX - r.left) * (cv.width / r.width);
  const y = (e.clientY - r.top) * (cv.height / r.height);
  return { x: x / viewScale, y: y / viewScale };
}

/** índice de esquina bajo el puntero, o -1 */
function hitCorner(e: PointerEvent): number {
  if (!edQuad) return -1;
  const cv = $("ed-canvas") as HTMLCanvasElement;
  const r = cv.getBoundingClientRect();
  const x = (e.clientX - r.left) * (cv.width / r.width);
  const y = (e.clientY - r.top) * (cv.height / r.height);
  // radio de agarre en píxeles CSS convertidos a píxeles del canvas
  const disp = cv.width / Math.max(1, r.width);
  const radius = Math.max(30 * disp, 24);
  let best = -1, bd = 1e9;
  edQuad.forEach((q, i) => {
    const d = Math.hypot(q.x * viewScale - x, q.y * viewScale - y);
    if (d < bd) { bd = d; best = i; }
  });
  return bd <= radius ? best : -1;
}

let viewScale = 1;
let lastBmp = { w: 0, h: 0 };

/** desempaqueta [x0,y0,x1,y1,...] en puntos y los ordena TL,TR,BR,BL */
function quadFromArr(arr: number[]) {
  return orderQuad(arr.reduce<{ x: number; y: number }[]>((acc, v, i, src) => {
    if (i % 2 === 0) acc.push({ x: v, y: src[i + 1] });
    return acc;
  }, [])).pts;
}

async function updateEdNav(force = false) {
  if (!pages.length) {
    $("ed-pos").textContent = "— sin páginas —";
    return;
  }
  edIdx = Math.max(0, Math.min(edIdx, pages.length - 1));
  $("ed-pos").textContent = `${edIdx + 1} / ${pages.length}`;
  const p = pages[edIdx];
  edQuad = p.quad ? quadFromArr(p.quad) : null;
  ($("ed-filter") as HTMLSelectElement).value = p.filter;
  if (force) renderEditor();
}

/** tamaño objetivo del recorte a partir del quad (coordenadas de imagen original) */
function quadTargetSize(quad: { x: number; y: number }[] | null, maxSide = 1600): [number, number] {
  if (!quad) return [Math.round(maxSide), Math.round(maxSide * 1.414)];
  const w = Math.hypot(quad[1].x - quad[0].x, quad[1].y - quad[0].y);
  const h = Math.hypot(quad[3].x - quad[0].x, quad[3].y - quad[0].y);
  const ratio = w / h || 1;
  let tw = w, th = h;
  if (ratio >= 1) { tw = w; th = w / ratio; } else { tw = h * ratio; th = h; }
  const s = Math.min(maxSide, Math.max(tw, th) || 1) / Math.max(tw, th);
  return [Math.max(1, Math.round(tw * s)), Math.max(1, Math.round(th * s))];
}

async function renderEditor() {
  const cv = $("ed-canvas") as HTMLCanvasElement;
  const p = pages[edIdx];
  if (!p) return;
  // imagen original escalada
  const bmp = await blobToCanvas(p.original);
  const maxW = Math.min(bmp.width, 900);
  viewScale = maxW / bmp.width;
  cv.width = Math.round(bmp.width * viewScale);
  cv.height = Math.round(bmp.height * viewScale);
  const ctx = cv.getContext("2d")!;
  ctx.drawImage(bmp, 0, 0, cv.width, cv.height);
  lastBmp = { w: bmp.width, h: bmp.height };
  // sin quad → usa el borde completo de la imagen para que SIEMPRE haya esquinas agarrables
  if (!edQuad) {
    edQuad = [
      { x: 0, y: 0 },
      { x: bmp.width - 1, y: 0 },
      { x: bmp.width - 1, y: bmp.height - 1 },
      { x: 0, y: bmp.height - 1 },
    ];
  }
  // factor de escala CSS→canvas para tamaños coherentes en pantalla
  const disp = cv.width / Math.max(1, cv.getBoundingClientRect().width || cv.width);
  if (edQuad) {
    // sombreado fuera del recorte
    ctx.save();
    ctx.beginPath();
    ctx.rect(0, 0, cv.width, cv.height);
    const q = edQuad.map((p2) => ({ x: p2.x * viewScale, y: p2.y * viewScale }));
    ctx.moveTo(q[0].x, q[0].y);
    for (let i = 1; i < 4; i++) ctx.lineTo(q[i].x, q[i].y);
    ctx.closePath();
    ctx.fillStyle = "rgba(0,0,0,0.45)";
    ctx.fill("evenodd");
    ctx.restore();
    // borde del cuadrilátero
    ctx.strokeStyle = "#1f6feb"; ctx.lineWidth = Math.max(2, 2.5 * disp);
    ctx.beginPath();
    ctx.moveTo(q[0].x, q[0].y);
    for (let i = 1; i < 4; i++) ctx.lineTo(q[i].x, q[i].y);
    ctx.closePath();
    ctx.stroke();
    // soportes en L en cada esquina (guía visual del agarre)
    const L = Math.max(26, 30 * disp);
    const off = Math.max(4, 5 * disp);
    q.forEach((pt, i) => {
      const prev = q[(i + 3) % 4], next = q[(i + 1) % 4];
      [prev, next].forEach((a) => {
        const len = Math.hypot(a.x - pt.x, a.y - pt.y) || 1;
        const ux = (a.x - pt.x) / len, uy = (a.y - pt.y) / len;
        ctx.beginPath();
        ctx.moveTo(pt.x + ux * off, pt.y + uy * off);
        ctx.lineTo(pt.x + ux * (off + L), pt.y + uy * (off + L));
        ctx.stroke();
      });
    });
    // mangos: círculo grande con anillo blanco y contorno oscuro
    const hr = Math.max(10, 14 * disp);
    q.forEach((pt) => {
      ctx.beginPath(); ctx.arc(pt.x, pt.y, hr, 0, Math.PI * 2);
      ctx.fillStyle = "rgba(31,111,235,0.92)"; ctx.fill();
      ctx.strokeStyle = "#fff"; ctx.lineWidth = Math.max(2, 3 * disp); ctx.stroke();
      ctx.strokeStyle = "rgba(0,0,0,0.55)"; ctx.lineWidth = 1.5; ctx.stroke();
    });
  }
  await renderPreview();
}

/** vista previa del resultado final (recorte + filtro) */
async function renderPreview() {
  const pc = $("ed-preview") as HTMLCanvasElement;
  const p = pages[edIdx];
  if (!p) { pc.width = pc.height = 0; return; }
  const q = edQuad ? orderQuad(edQuad).pts : null;
  const [tw, th] = quadTargetSize(q, 700);
  let canvas: HTMLCanvasElement;
  try {
    canvas = await warpQuad(p.original, q, tw, th);
  } catch (err) {
    return; // cv aún cargando
  }
  try {
    canvas = await applyFilter(canvas, p.filter);
  } catch { /* mantiene el recorte sin filtro */ }
  pc.width = canvas.width; pc.height = canvas.height;
  pc.getContext("2d")!.drawImage(canvas, 0, 0);
}

/** rota la página actual en pasos de 90° (delta: ±90, 180); actualiza la imagen y el quad de recorte */
async function rotatePage(delta: number) {
  const p = pages[edIdx];
  if (!p) return;
  const steps = ((delta / 90) % 4 + 4) % 4; // 0..3 en sentido horario
  if (!steps) return;
  const bmp = await blobToCanvas(p.original);
  const rot = document.createElement("canvas");
  if (steps % 2 === 0) { rot.width = bmp.width; rot.height = bmp.height; }
  else { rot.width = bmp.height; rot.height = bmp.width; }
  const rc = rot.getContext("2d")!;
  rc.translate(rot.width / 2, rot.height / 2);
  rc.rotate((steps * Math.PI) / 2);
  rc.drawImage(bmp, -bmp.width / 2, -bmp.height / 2);
  // transforma el quad activo (o el guardado) a las nuevas coordenadas
  const W = bmp.width, H = bmp.height;
  let live = edQuad ?? (p.quad ? quadFromArr(p.quad) : null);
  if (live) {
    live = live.map((pt) => {
      if (steps === 1) return { x: H - 1 - pt.y, y: pt.x }; // 90° horario
      if (steps === 2) return { x: W - 1 - pt.x, y: H - 1 - pt.y }; // 180°
      return { x: pt.y, y: W - 1 - pt.x }; // 270° (= 90° anti-horario)
    });
    p.quad = live.flatMap((q) => [q.x, q.y]);
    edQuad = live;
  } else {
    edQuad = null;
  }
  p.original = await new Promise<Blob>((res, rej) =>
    rot.toBlob((b) => (b ? res(b) : rej(new Error("toBlob falló"))), "image/jpeg", 0.92)
  );
  pageCache.delete(p.id);
  await putPage(p);
  showNote(steps === 2 ? "🔁 Rotada 180°" : steps === 1 ? "🔁 Rotada 90° →" : "🔁 Rotada 90° ←");
  renderEditor();
}

function blobToCanvas(blob: Blob): Promise<HTMLCanvasElement> {
  return new Promise((res, rej) => {
    const img = new Image();
    img.onload = () => {
      const c = document.createElement("canvas");
      c.width = img.naturalWidth; c.height = img.naturalHeight;
      c.getContext("2d")!.drawImage(img, 0, 0);
      res(c);
    };
    img.onerror = rej;
    img.src = URL.createObjectURL(blob);
  });
}

/** canvas procesado de la página (recorte + filtro), cacheado */
export async function processedCanvas(p: Page): Promise<HTMLCanvasElement> {
  const hit = pageCache.get(p.id);
  if (hit) return hit;
  const quad = p.quad ? quadFromArr(p.quad) : null;
  const [tw, th] = quadTargetSize(quad, 1600);
  const warped = await warpQuad(p.original, quad, tw, th);
  const c = await applyFilter(warped, p.filter);
  pageCache.set(p.id, c);
  return c;
}

// ---------- OCR ----------
function setupOcr() {
  $("ocr-run").onclick = async () => {
    const p = pages[edIdx];
    if (!p) { showNote("No hay página seleccionada en el Editor"); return; }
    const lang = ($("ocr-lang") as HTMLSelectElement).value;
    $("ocr-status").textContent = "preparando...";
    try {
      const canvas = await processedCanvas(p);
      const text = await runOcr(canvas, lang, (pr, st) => {
        $("ocr-status").textContent = `${st} ${(pr * 100).toFixed(0)}%`;
      });
      ($("ocr-output") as HTMLTextAreaElement).value = text.trim();
      $("ocr-status").textContent = `✅ ${text.length} caracteres`;
    } catch (err) {
      $("ocr-status").textContent = `⚠ Error: ${err}`;
    }
  };
  $("ocr-copy").onclick = async () => {
    const v = ($("ocr-output") as HTMLTextAreaElement).value;
    await navigator.clipboard.writeText(v);
    showNote("📋 Copiado");
  };
  $("ocr-txt").onclick = () => {
    const v = ($("ocr-output") as HTMLTextAreaElement).value;
    const b = new Blob([v], { type: "text/plain" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(b);
    a.download = `${current?.name ?? "documento"}-ocr.txt`;
    a.click();
  };
}

// ---------- documentos ----------
let selectedDoc = "";
async function setupDocs() {
  $("export-pdf").onclick = async () => {
    const id = selectedDoc || current?.id;
    if (!id) return;
    await exportDocPdf(id);
  };
}
async function renderDocs() {
  docs = await listDocs();
  const box = $("doc-list");
  box.innerHTML = "";
  for (const d of docs) {
    const full = await getDoc(d.id);
    const page = full?.pageIds?.length ? await getPage(full.pageIds[0]) : undefined;
    const card = document.createElement("div");
    card.className = "doc-card" + (d.id === selectedDoc ? " selected" : "");
    if (page) card.appendChild(await makeThumb(page));
    const nm = document.createElement("div");
    nm.className = "name"; nm.textContent = d.name;
    const meta = document.createElement("div");
    meta.className = "meta"; meta.textContent = `${full?.pageIds?.length ?? 0} páginas · ${new Date(d.created).toLocaleDateString()}`;
    card.append(nm, meta);
    card.onclick = async () => {
      selectedDoc = d.id;
      // abrir el documento clicado para editar: usa la versión completa
      const opened = await getDoc(d.id);
      current = opened ?? d;
      selectedDoc = current.id;
      refreshDocSelect();
      pages = (await Promise.all(current.pageIds.map(getPage))).filter(Boolean) as Page[];
      pageCache.clear();
      edIdx = 0;
      await updateEdNav(true);
      showTab("editor");
      showNote(`📂 Abierto: ${current.name}`);
    };
    const del = document.createElement("button");
    del.className = "danger del"; del.textContent = "🗑️ Eliminar";
    del.onclick = async (e) => {
      e.stopPropagation();
      if (!confirm(`¿Eliminar "${d.name}" con todas sus páginas?`)) return;
      for (const pid of full?.pageIds ?? []) await delPage(pid);
      await deleteDoc(d.id);
      docs = (await listDocs()).filter((x) => x.id !== d.id);
      if (current?.id === d.id) current = docs[0];
      selectedDoc = "";
      await refreshCurrent();
      renderDocs();
    };
    card.appendChild(del);
    box.appendChild(card);
  }
  updateExportBtn();
}
function updateExportBtn() {
  ($("export-pdf") as HTMLButtonElement).disabled = !(selectedDoc || current);
}
async function exportDocPdf(id: string) {
  const d = docs.find((x) => x.id === id) ?? current!;
  const ids = d.pageIds;
  showProgress(`Generando PDF (${ids.length} páginas)...`);
  try {
    const blob = await exportPdf(
      async (i) => {
        const p = await getPage(ids[i]);
        if (!p) throw new Error("página no encontrada");
        return processedCanvas(p);
      },
      ids.length,
      d.name,
    );
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `${d.name}.pdf`;
    a.click();
    showNote("📕 PDF generado");
  } catch (err) {
    showNote(`⚠ Error generando PDF: ${err}`);
  }
  hideProgress();
}

async function makeThumb(p: Page): Promise<HTMLImageElement> {
  const c = await processedCanvas(p);
  const img = document.createElement("img");
  img.src = c.toDataURL("image/jpeg", 0.6);
  return img;
}

// ---------- progreso / avisos ----------
function showProgress(text: string) {
  const d = $("progress") as HTMLDialogElement;
  ($("progress-text") as HTMLElement).textContent = text;
  d.showModal();
}
function progressTick() { /* placeholder para ticks */ }
function hideProgress() { ($("progress") as HTMLDialogElement).close(); }
function showNote(msg: string) {
  const d = $("progress") as HTMLDialogElement;
  ($("progress-text") as HTMLElement).textContent = msg;
  d.showModal();
  setTimeout(() => d.close(), 1800);
}

init();
