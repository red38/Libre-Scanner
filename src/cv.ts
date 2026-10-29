// OpenCV: detección de bordes de documento + corrección de perspectiva.
// Envuelve el singleton global `cv` cargado desde /opencv.js.

// IMPORTANTE: el build de opencv.js define Module["then"] que devuelve Module;
// si una promesa se resuelve CON el objeto cv y alguien la consume con `await`,
// el navegador entra en un bucle indefinido de adopción de thenables (100% CPU).
// Por eso cvReady resuelve `void` y devuelve window.cv sincrónicamente después.

let ready: Promise<void> | null = null;

/** Espera a que OpenCV esté listo; resuelve `void` a propósito */
export async function cvLoaded(): Promise<void> {
  if (!ready) {
    ready = new Promise((res, rej) => {
      const g = (window as any);
      if (g.cv && g.cv.Mat) return res();
      const start = Date.now();
      const iv = setInterval(() => {
        if (g.cv && g.cv.Mat) { clearInterval(iv); res(); }
        else if (Date.now() - start > 30000) { clearInterval(iv); rej(new Error("OpenCV.js no cargó")); }
      }, 120);
    });
  }
  await ready;
}

/** Obtén el objeto cv (llamar tras cvLoaded) */
export function getCv(): any {
  const cv = (window as any).cv;
  if (!cv || !cv.Mat) throw new Error("OpenCV.js no cargó");
  return cv;
}

export interface Quad { pts: { x: number; y: number }[]; score: number }

/** área de polígono (shoelace) */
function polygonArea(pts: { x: number; y: number }[]): number {
  let a = 0;
  for (let i = 0; i < pts.length; i++) {
    const p = pts[i], q = pts[(i + 1) % pts.length];
    a += p.x * q.y - q.x * p.y;
  }
  return Math.abs(a) / 2;
}

/** cuadrilátero convexo (misma orientación en los 4 cruces) */
function isConvexQuad(pts: { x: number; y: number }[]): boolean {
  let sign = 0;
  for (let i = 0; i < 4; i++) {
    const a = pts[(i + 3) % 4], b = pts[i], c = pts[(i + 1) % 4];
    const cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
    if (Math.abs(cross) < 1e-6) return false;
    const s = cross > 0 ? 1 : -1;
    if (!sign) sign = s;
    else if (s !== sign) return false;
  }
  return true;
}

/** desviación máxima (en grados) de los 4 ángulos respecto a 90° */
function maxAngleDeviation(pts: { x: number; y: number }[]): number {
  let dev = 0;
  for (let i = 0; i < 4; i++) {
    const a = pts[(i + 3) % 4], b = pts[i], c = pts[(i + 1) % 4];
    const v1 = { x: a.x - b.x, y: a.y - b.y }, v2 = { x: c.x - b.x, y: c.y - b.y };
    const dot = (v1.x * v2.x + v1.y * v2.y) / (Math.hypot(v1.x, v1.y) * Math.hypot(v2.x, v2.y) || 1);
    const ang = (Math.acos(Math.max(-1, Math.min(1, dot))) * 180) / Math.PI;
    dev = Math.max(dev, Math.abs(90 - ang));
  }
  return dev;
}

/** lado más corto del cuadrilátero */
function minSide(pts: { x: number; y: number }[]): number {
  let m = Infinity;
  for (let i = 0; i < 4; i++) {
    const p = pts[i], q = pts[(i + 1) % 4];
    m = Math.min(m, Math.hypot(q.x - p.x, q.y - p.y));
  }
  return m;
}

/** puntaje de calidad: área (55%) + rectitud (30%) + ángulos rectos (15%) */
function quadScore(pts: { x: number; y: number }[], w: number, h: number): number {
  const area = polygonArea(pts);
  const frac = area / (w * h);
  const xs = pts.map((p) => p.x), ys = pts.map((p) => p.y);
  const aabb = Math.max(1, (Math.max(...xs) - Math.min(...xs)) * (Math.max(...ys) - Math.min(...ys)));
  const fill = area / aabb;
  const ang = 1 - Math.min(1, maxAngleDeviation(pts) / 45);
  return frac * 0.55 + fill * 0.3 + ang * 0.15;
}

interface Candidate { pts: { x: number; y: number }[]; score: number }

/**
 * Detecta el cuadrilátero del documento con múltiples pasadas:
 *  1) Canny auto (umbrales según el brillo medio) + Canny fijo de respaldo
 *  2) Umbral adaptativo del interior brillante del papel (2 sensibilidades)
 * Cada contorno grande se aproxima con 3 epsilon distintos y también vía convex-hull.
 * Se puntúan los candidatos (área, rectitud, ángulos) y gana el mejor.
 */
export async function detectQuad(src: Blob | HTMLCanvasElement): Promise<Quad | null> {
  await cvLoaded();
  const cv = getCv();
  const img = await loadImage(src);
  const scale = Math.max(img.width, img.height) > 1200 ? 1200 / Math.max(img.width, img.height) : 1;
  const w = Math.round(img.width * scale), h = Math.round(img.height * scale);

  const srcMat = cv.imread(img);
  const rs = new cv.Mat();
  cv.resize(srcMat, rs, new cv.Size(w, h), 0, 0, cv.INTER_AREA);
  cv.cvtColor(rs, rs, cv.COLOR_RGBA2GRAY);
  const blur = new cv.Mat();
  cv.GaussianBlur(rs, blur, new cv.Size(5, 5), 0);

  // ---- pasadas de bordes (Canny auto + Canny fijo se unen entre sí) ----
  const mean = cv.mean(blur)[0]; // brillo medio aproximado
  const edges = new cv.Mat();
  cv.Canny(blur, edges, Math.max(30, mean * 0.5), Math.max(90, mean * 1.2));
  const fixedMask = new cv.Mat();
  cv.Canny(blur, fixedMask, 40, 130);
  cv.bitwise_or(edges, fixedMask, edges);
  fixedMask.delete();
  cv.dilate(edges, edges, cv.getStructuringElement(cv.MORPH_RECT, new cv.Size(3, 3)));

  // máscaras adaptativas del interior brillante del papel (2 sensibilidades) + Otsu global, separadas
  const brightMasks: any[] = [];
  for (const cval of [7, 15]) {
    const bin = new cv.Mat();
    cv.adaptiveThreshold(blur, bin, 255, cv.ADAPTIVE_THRESH_GAUSSIAN_C, cv.THRESH_BINARY, 51, cval);
    const k = cv.getStructuringElement(cv.MORPH_RECT, new cv.Size(7, 7));
    cv.morphologyEx(bin, bin, cv.MORPH_CLOSE, k);
    k.delete();
    brightMasks.push(bin);
  }
  // Otsu: separa papel del fondo cuando el contraste es sutil (p.ej. papel claro sobre mesa clara)
  const otsu = new cv.Mat();
  cv.threshold(blur, otsu, 0, 255, cv.THRESH_BINARY + cv.THRESH_OTSU);
  {
    const k = cv.getStructuringElement(cv.MORPH_RECT, new cv.Size(7, 7));
    cv.morphologyEx(otsu, otsu, cv.MORPH_CLOSE, k);
    k.delete();
    brightMasks.push(otsu);
  }

  // ---- recolección de candidatos ----
  const cands: Candidate[] = [];
  const seen = new Set<string>();
  const collect = (ctr: any, minAreaFrac = 0.1) => {
    const area = Math.abs(cv.contourArea(ctr, false));
    if (area < minAreaFrac * w * h) return;
    const hull = new cv.Mat();
    cv.convexHull(ctr, hull, false, true);
    for (const basis of [ctr, hull]) {
      for (const eps of [0.02, 0.035, 0.055, 0.075]) {
        const ap = new cv.Mat();
        cv.approxPolyDP(basis, ap, eps * cv.arcLength(basis, true), true);
        if (ap.rows === 4) {
          const pts: { x: number; y: number }[] = [];
          for (let i = 0; i < 4; i++) pts.push({ x: ap.data32S[i * 2], y: ap.data32S[i * 2 + 1] });
          if (isConvexQuad(pts) && minSide(pts) >= 25) {
            const key = pts.map((p) => `${p.x | 0},${p.y | 0}`).join("|");
            if (!seen.has(key)) {
              seen.add(key);
              cands.push({ pts, score: quadScore(pts, w, h) });
            }
          }
        }
        ap.delete();
      }
    }
    hull.delete();
  };

  const contours = new cv.MatVector();
  const hier = new cv.Mat();
  cv.findContours(edges, contours, hier, cv.RETR_EXTERNAL, cv.CHAIN_APPROX_SIMPLE);
  for (let i = 0; i < contours.size(); i++) collect(contours.get(i));
  edges.delete();

  // pasadas adaptativas por separado: el papel es un blob brillante relleno
  for (const mask of brightMasks) {
    cv.findContours(mask, contours, hier, cv.RETR_EXTERNAL, cv.CHAIN_APPROX_SIMPLE);
    for (let i = 0; i < contours.size(); i++) {
      const area = Math.abs(cv.contourArea(contours.get(i), false));
      if (area >= 0.18 * w * h) collect(contours.get(i), 0.18); // solo blobs grandes
    }
    mask.delete();
  }
  if (import.meta.env?.DEV) console.log("[detectQuad] pasadas:", 2 + brightMasks.length, "| brillo medio:", mean | 0, "| candidatos:", cands.map((c) => (c.score * 100).toFixed(0) + "%" + JSON.stringify(c.pts.map((p) => [p.x | 0, p.y | 0]))).join(" · ") || "ninguno");

  // el quad que coincide con TODO el cuadro indica página fuera de marco: lo dejamos
  // solo si no hay candidatos interiores, para que el usuario al menos tenga mangos
  const m = 0.015 * Math.max(w, h);
  const fullFrame = cands.filter((c) => {
    const xs = c.pts.map((p) => p.x), ys = c.pts.map((p) => p.y);
    return Math.min(...xs) < m && Math.max(...xs) > w - m && Math.min(...ys) < m && Math.max(...ys) > h - m;
  });
  const pool = cands.length > fullFrame.length ? cands.filter((c) => !fullFrame.includes(c)) : fullFrame;

  let best: Candidate | null = null;
  for (const c of pool) if (!best || c.score > best.score) best = c;

  let result: Quad | null = null;
  if (best) {
    const ordered = orderQuad(best.pts).pts;
    result = {
      pts: ordered.map((p) => ({ x: p.x / scale, y: p.y / scale })),
      score: best.score,
    };
  }

  [srcMat, rs, blur, contours, hier].forEach((m2) => m2.delete());
  if (import.meta.env?.DEV) console.log("[detectQuad] resultado:", result ? JSON.stringify(result.pts.map((p) => [p.x | 0, p.y | 0])) : "NULL");
  return result;
}

/** Aplica el recorte en perspectiva con el quad sobre la imagen original. quad=null → imagen entera. */
export async function warpQuad(src: Blob | HTMLCanvasElement, quad: { x: number; y: number }[] | null, targetW: number, targetH: number): Promise<HTMLCanvasElement> {
  await cvLoaded();
  const cv = getCv();
  const img = await loadImage(src);
  const srcMat = cv.imread(img);
  const out = document.createElement("canvas");
  out.width = targetW; out.height = targetH;
  const dst = new cv.Mat();

  if (!quad) {
    const rs = new cv.Mat();
    cv.resize(srcMat, rs, new cv.Size(targetW, targetH), 0, 0, cv.INTER_AREA);
    cv.imshow(out, rs);
    srcMat.delete(); rs.delete();
    return out;
  }

  // Asegurar orden TL, TR, BR, BL
  const q = orderQuad(quad);
  const srcPts = cv.matFromArray(4, 1, cv.CV_32FC2, [
    q.pts[0].x, q.pts[0].y, q.pts[1].x, q.pts[1].y,
    q.pts[2].x, q.pts[2].y, q.pts[3].x, q.pts[3].y,
  ]);
  const dstPts = cv.matFromArray(4, 1, cv.CV_32FC2, [0, 0, targetW, 0, targetW, targetH, 0, targetH]);
  const M = cv.getPerspectiveTransform(srcPts, dstPts);
  cv.warpPerspective(srcMat, dst, M, new cv.Size(targetW, targetH), cv.INTER_CUBIC);
  cv.imshow(out, dst);

  srcMat.delete(); dst.delete(); M.delete(); srcPts.delete(); dstPts.delete();
  return out;
}

export function orderQuad(quad: { x: number; y: number }[]) {
  const pts = quad.map((p) => ({ x: p.x, y: p.y }));
  const cx = pts.reduce((s, p) => s + p.x, 0) / 4;
  const left = pts.filter((p) => p.x < cx).sort((a, b) => a.y - b.y);
  const right = pts.filter((p) => p.x >= cx).sort((a, b) => a.y - b.y);
  return { pts: [left[0] ?? right[0], right[0], right[1] ?? left[1], left[1] ?? right[1]] as { x: number; y: number }[], score: 0 };
}

/** Applies the selected filter to the already-cut canvas and returns a new canvas. */
export async function applyFilter(canvas: HTMLCanvasElement, filter: string): Promise<HTMLCanvasElement> {
  if (filter === "original") return canvas;
  await cvLoaded();
  const cv = getCv();
  const src = cv.imread(canvas);
  const outMat = new cv.Mat();
  const out = document.createElement("canvas");

  if (filter === "gray") {
    cv.cvtColor(src, outMat, cv.COLOR_RGBA2GRAY, 4);
    cv.normalize(outMat, outMat, 0, 255, cv.NORM_MINMAX);
  } else if (filter === "bw") {
    const gray = new cv.Mat();
    cv.cvtColor(src, gray, cv.COLOR_RGBA2GRAY, 4);
    cv.adaptiveThreshold(gray, outMat, 255, cv.ADAPTIVE_THRESH_GAUSSIAN_C, cv.THRESH_BINARY, 21, 12);
    gray.delete();
  } else if (filter === "bw2") {
    const gray = new cv.Mat();
    cv.cvtColor(src, gray, cv.COLOR_RGBA2GRAY, 4);
    cv.adaptiveThreshold(gray, outMat, 255, cv.ADAPTIVE_THRESH_GAUSSIAN_C, cv.THRESH_BINARY, 41, 18);
    gray.delete();
  } else if (filter === "magic") {
    // "magic color": CLAHE sobre el canal L de Lab + normalización
    const rgb = new cv.Mat();
    const lab = new cv.Mat();
    const ch = new cv.MatVector();
    cv.cvtColor(src, rgb, cv.COLOR_RGBA2RGB);
    cv.cvtColor(rgb, lab, cv.COLOR_RGB2Lab);
    cv.split(lab, ch);
    const clahe = new cv.CLAHE(2.0, new cv.Size(8, 8));
    clahe.apply(ch.get(0), ch.get(0));
    clahe.delete();
    cv.merge(ch, lab);
    cv.cvtColor(lab, rgb, cv.COLOR_Lab2RGB);
    cv.cvtColor(rgb, outMat, cv.COLOR_RGB2RGBA);
    lab.delete(); ch.delete(); rgb.delete();
  } else {
    return canvas;
  }
  cv.imshow(out, outMat);
  src.delete(); outMat.delete();
  return out;
}

function loadImage(src: Blob | HTMLCanvasElement): Promise<HTMLImageElement | HTMLCanvasElement> {
  if (src instanceof HTMLCanvasElement) return Promise.resolve(src);
  return new Promise((res, rej) => {
    const url = URL.createObjectURL(src);
    const img = new Image();
    img.onload = () => { URL.revokeObjectURL(url); res(img); };
    img.onerror = rej;
    img.src = url;
  });
}
