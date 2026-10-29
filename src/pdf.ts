// Exportación a PDF multipágina con pdf-lib
import { PDFDocument } from "pdf-lib";

export async function exportPdf(
  getPageCanvas: (i: number) => Promise<HTMLCanvasElement>,
  count: number,
  docName: string,
): Promise<Blob> {
  const pdf = await PDFDocument.create();
  for (let i = 0; i < count; i++) {
    const canvas = await getPageCanvas(i);
    // JPEG calidad 82: buen equilibrio de peso/importación
    const dataURL = canvas.toDataURL("image/jpeg", 0.82);
    const bytes = await fetch(dataURL).then((r) => r.arrayBuffer());
    const img = await pdf.embedJpg(bytes);
    const W = 595.28, H = 841.89; // A4 en puntos
    const margin = 24;
    const maxW = W - margin * 2, maxH = H - margin * 2;
    const s = Math.min(maxW / img.width, maxH / img.height);
    const w = img.width * s, h = img.height * s;
    const page = pdf.addPage([W, H]);
    page.drawImage(img, { x: (W - w) / 2, y: (H - h) / 2, width: w, height: h });
    if (i < count - 1) await Promise.resolve();
  }
  pdf.setTitle(docName);
  pdf.setProducer("DocScan");
  const bytes = await pdf.save();
  return new Blob([bytes as unknown as BlobPart], { type: "application/pdf" });
}
