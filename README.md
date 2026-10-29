# Libre-Scanner

Escáner de documentos web — 100% local, sin nube. Tecnología original, inspirada en los flujos clásicos de apps de escaneo, con implementación propia.

## Funciones

- 📷 Captura con cámara o importación de imágenes
- ✨ Detección automática de bordes del documento (multi-pasada: Canny adaptativo, umbral adaptativo, Otsu + puntuación de calidad)
- ✂️ Recorte con esquinas arrastrables + vista previa en vivo
- 🔄 Rotación 90°/180°/270° (el recorte acompaña)
- 🎨 Filtros: original, escala de grises, blanco y negro (agudizado), "magic color" (CLAHE + vibrancia)
- 🔍 OCR integrado (Tesseract.js, en el navegador)
- 📚 Múltiples documentos y páginas, persistidos en IndexedDB
- 📕 Exportación a PDF multipágina (pdf-lib)
- 🛜 Funciona sin conexión (OpenCV.js incluido localmente en `public/`)

## Stack

Vite + TypeScript vanilla · OpenCV.js 4.13 · Tesseract.js · pdf-lib · IndexedDB

## Desarrollo

```sh
npm install
npm run dev      # http://localhost:5173
npm run build    # → dist/
npm run preview
```

## Privacidad

Todo el procesamiento ocurre en tu navegador: cámara, OCR, filtros y PDF. No hay servidores, cuentas ni telemetría. Tus documentos se guardan solo en IndexedDB (local).

## Licencia

Código original. Elige tu licencia al publicarla (sugerencia: MIT).
