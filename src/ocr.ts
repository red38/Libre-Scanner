// OCR con Tesseract.js
import { createWorker, type Worker } from "tesseract.js";

let worker: Worker | null = null;
let currentLang = "";

export async function runOcr(canvas: HTMLCanvasElement, lang: string, onProgress?: (p: number, status: string) => void): Promise<string> {
  onProgress?.(0, "preparando motor...");
  if (!worker || currentLang !== lang) {
    if (worker) await worker.terminate();
    worker = await createWorker(lang, 1, {
      logger: (m: any) => { if (m.progress != null) onProgress?.(m.progress, m.status ?? ""); },
    });
    currentLang = lang;
  }
  onProgress?.(0.02, "reconociendo texto...");
  const { data } = await worker.recognize(canvas);
  onProgress?.(1, "listo");
  return data.text ?? "";
}
