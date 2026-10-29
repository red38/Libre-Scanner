# Libre Scanner — Android (Kotlin)

Port nativo de Libre-Scanner. Todo el procesamiento es local: no hay servidor, cuentas ni telemetría.

## Stack

| Pieza | Tecnología |
| --- | --- |
| UI | Jetpack Compose + Material 3 |
| Cámara | CameraX 1.4 |
| Visión | OpenCV 4.13 (`org.opencv:opencv`) |
| Imágenes | EXIF + BitmapFactory con downsampling |
| Concurrencia | Coroutines + Dispatchers.Default |

## Estructura

```
app/src/main/java/com/red38/librescanner/
├── MainActivity.kt              navegación (Captura → Editor → Resultado)
├── data/ImageLoader.kt          carga de JPEG/URI con EXIF y downsampling
├── ui/
│   ├── FitRect.kt               encaje de imagen en el canvas
│   ├── capture/CaptureScreen.kt CameraX + selector de galería
│   ├── editor/EditorScreen.kt   esquinas arrastrables + filtros
│   ├── result/ResultScreen.kt   vista final + compartir
│   └── theme/Theme.kt
├── util/Sharing.kt              FileProvider + ACTION_SEND
└── vision/
    ├── Geometry.kt              área, convexidad, ángulos, puntuación
    ├── DocumentDetector.kt      port de detectQuad (Canny, adaptativo, Otsu)
    ├── PerspectiveWarper.kt     warpPerspective + tamaño objetivo
    ├── ImageFilters.kt          gris, B/N, B/N suave, magic color (CLAHE)
    ├── BitmapUtils.kt           Bitmap ⇄ Mat
    └── OpenCv.kt                carga del runtime nativo
```

El detector es un port línea por línea de `src/cv.ts`: mismas pasadas (Canny automático + fijo, umbral adaptativo con dos sensibilidades, Otsu), misma generación de candidatos (4 epsilons + convex hull) y misma fórmula de puntuación (55% área, 30% rectitud, 15% ángulos rectos), incluyendo el filtro de "cuadro completo".

## Requisitos

- Android Studio (Ladybug o superior) o JDK 17+ y Android SDK 36
- `compileSdk`/`targetSdk` 36, `minSdk` 26

## Compilar

```powershell
cd android
.\gradlew.bat assembleDebug
adb install app\build\outputs\apk\debug\app-debug.apk
```

Si Gradle no encuentra el SDK, define `ANDROID_HOME` o crea `local.properties`:

```properties
sdk.dir=C\:\\Users\\TU_USUARIO\\AppData\\Local\\Android\\Sdk
```

El APK debug pesa ~150 MB porque incluye OpenCV para las 4 ABIs (arm64-v8a, armeabi-v7a, x86, x86_64). En Play Store el AAB se divide por ABI y por densidad automáticamente.

## Probar en un teléfono por USB

Es la forma recomendada durante el desarrollo: usa la cámara real y no requiere publicar nada.

Requisitos:

- Teléfono Android 8.0 o superior (API 26+)
- Cable USB de datos
- Android Studio instalado (recomendado) o JDK 17 + Android SDK 36
- En el teléfono: Ajustes → Acerca del teléfono → tocar 7 veces "Número de compilación", luego Ajustes → Opciones de desarrollador → activar **Depuración USB**

Pasos:

```powershell
git pull
cd android
.\gradlew.bat assembleDebug
adb devices          # debe aparecer el teléfono como "device"
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

En macOS/Linux:

```sh
cd android
./gradlew assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternativa más simple: abrir la carpeta `android/` en Android Studio, conectar el teléfono y pulsar **Run ▶**.

Problemas comunes:

- `unauthorized`: acepta el diálogo "¿Permitir depuración USB?" que aparece en el teléfono.
- `adb: command not found`: usa `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` o agrega esa carpeta al PATH.
- Gradle no encuentra el SDK: abre el proyecto en Android Studio una vez (genera `local.properties`) o define `ANDROID_HOME`.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: el APK instalado se firmó en otra PC; desinstala la app y vuelve a instalar.
- La primera compilación tarda 5–10 minutos porque descarga Gradle, AndroidX y OpenCV (~120 MB).

## Estado

Implementado:

- Captura con CameraX y orientación vía EXIF
- Importación desde galería con el Photo Picker (sin permisos de almacenamiento)
- Detección automática de bordes con OpenCV
- Editor con las 4 esquinas arrastrables sobre la imagen
- Corrección de perspectiva a resolución objetivo (máx. 1600 px)
- Filtros: original, gris, B/N, B/N suave, color (CLAHE en Lab)
- Compartir el resultado

Pendiente (siguiente iteración):

- Rotación 90/180/270 en el editor
- Multi-página y persistencia (Room en vez de IndexedDB)
- Exportación a PDF multipágina (`PdfDocument` o PdfBox-Android)
- OCR con ML Kit Text Recognition v2 (on-device)
- Guardado en galería vía MediaStore
- Re-detección en vivo mientras se mueve la cámara

## Publicar en Play Store

1. Cambia `applicationId` en `app/build.gradle.kts` por uno tuyo (p. ej. `com.tudominio.librescanner`).
2. Crea el keystore de subida y configura `signingConfigs` (o usa Play App Signing):

   ```powershell
   keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```

3. Genera el bundle de release:

   ```powershell
   .\gradlew.bat bundleRelease
   ```

   Sale en `app/build/outputs/bundle/release/app-release.aab`.

4. En Play Console: ficha de la app (icono 512×512 PNG, capturas, descripción), política de privacidad obligatoria, formulario de **Data safety** (esta app no recoge ni comparte datos), clasificación de contenido.
5. Cuentas de desarrollador personales nuevas: Play exige una prueba cerrada con **12 testers durante 14 días** antes de poder publicar en producción.
6. Target API: las apps nuevas deben apuntar a API 36 (Android 16). Este proyecto ya lo hace.

## Licencias

- Código de la app: elige la tuya (MIT recomendada, como en el README original).
- OpenCV: Apache-2.0. Si distribuyes el binario, incluye el aviso de licencia (el AAR ya trae `NOTICE`).
- CameraX, Compose, ExifInterface, Coroutines: Apache-2.0.
