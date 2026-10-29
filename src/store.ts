// IndexedDB sencillo para documentos y páginas (blobs de imagen)
// y servicios menores (PDF, OCR se importan por separado).

export interface Page {
  id: string;
  original: Blob;          // imagen capturada sin procesar
  quad: number[] | null;   // 8 valores [x0,y0,...x3,y3] en coords de la imagen original
  filter: string;          // original | magic | gray | bw | bw2
}
export interface Doc {
  id: string;
  name: string;
  created: number;
  pageIds: string[];
}

const DB = "docscan";
const VERSION = 1;
let dbp: Promise<IDBDatabase> | null = null;

function db(): Promise<IDBDatabase> {
  if (!dbp) {
    dbp = new Promise((res, rej) => {
      const req = indexedDB.open(DB, VERSION);
      req.onupgradeneeded = () => {
        const d = req.result;
        if (!d.objectStoreNames.contains("docs")) d.createObjectStore("docs", { keyPath: "id" });
        if (!d.objectStoreNames.contains("pages")) d.createObjectStore("pages", { keyPath: "id" });
      };
      req.onsuccess = () => res(req.result);
      req.onerror = () => rej(req.error);
    });
  }
  return dbp;
}

function tx(mode: IDBTransactionMode, store: string, fn: (s: IDBObjectStore) => IDBRequest): Promise<any> {
  return db().then(
    (d) =>
      new Promise((res, rej) => {
        const t = d.transaction(store, mode);
        const r = fn(t.objectStore(store));
        r.onsuccess = () => res(r.result);
        r.onerror = () => rej(r.error);
      }),
  );
}

export const uid = () => Math.random().toString(36).slice(2) + Date.now().toString(36);

export async function listDocs(): Promise<Doc[]> {
  const all: Doc[] = await tx("readonly", "docs", (s) => s.getAll());
  return all.sort((a, b) => b.created - a.created);
}
export const putDoc = async (doc: Doc) => tx("readwrite", "docs", (s) => s.put(doc));
export const getDoc = async (id: string) => tx("readonly", "docs", (s) => s.get(id)) as Promise<Doc | undefined>;
export const putPage = async (p: Page) => tx("readwrite", "pages", (s) => s.put(p));
export const getPage = async (id: string) => tx("readonly", "pages", (s) => s.get(id)) as Promise<Page | undefined>;
export const putPages = async (pages: Page[]) => {
  const d = await db();
  return new Promise((res, rej) => {
    const t = d.transaction("pages", "readwrite");
    const s = t.objectStore("pages");
    pages.forEach((p) => s.put(p));
    t.oncomplete = () => res(true);
    t.onerror = () => rej(t.error);
  });
};
export const delPage = async (id: string) => tx("readwrite", "pages", (s) => s.delete(id));
export const deleteDoc = async (id: string) => tx("readwrite", "docs", (s) => s.delete(id));
