# Cambios de la rama `printables` (PR #2), para quien la fusione

> Resumen para la persona (o la IA) que revise y fusione esta rama en `main`. Lo detallado de la web
> está en `docs/contexto-printables.md`; las reglas del proyecto, en `CLAUDE.md` (nuevo en esta rama).

## Qué trae

1. **Printables (Prusa) como plataforma nueva, con extensión de Chrome.**
   - Cloudflare no deja entrar al Chrome que maneja la app (Playwright): se queda en "Just a moment..." en
     bucle (comprobado el 10/11/2026 con `DiagnosticoPrintables`). Por eso **no hay `SubidorPrintables`** como
     el de Creality: la persona abre Printables en su Chrome de siempre y una extensión rellena el formulario.
   - La app hace de cola: `ColaPrintables` (carpetas de `pendientes/` y `subidos/` sin `subido-printables.txt`)
     y `ServidorPrintables` (HTTP en `127.0.0.1:47812`; los POST solo con `Origin: chrome-extension://`).
   - La extensión (`desktop/src/main/resources/extension-printables/`: `manifest.json`, `background.js`,
     `panel.js`, `pagina.js`) la copia la app a `<carpeta de datos>/extension-printables` al arrancar; se carga
     en `chrome://extensions` con "Cargar descomprimida".
   - Por cada modelo: sube la portada y luego el resto, rellena nombre, resumen (primera línea de la
     descripción, máx. 120), categoría, etiquetas, descripción, licencia, origen y IA, **pulsa SAVE DRAFT** (solo
     si arriba está en DRAFT; nunca publica), comprueba lo guardado y la app apunta `subido-printables.txt`.
     En "modo seguido" pasa sola al siguiente.
   - Decisiones de la persona: categoría siempre **Toys & Games > Other Toys & Games**; origen **Original model**;
     IA **No — fully human-made**; guardar el borrador sin intervención.
   - Etiquetas: Printables solo admite `[a-z0-9]` y separa por espacios; la app las adapta
     ("Scale-Model" → "scalemodel").
   - Tarjeta **Printables** en la pantalla Subir (`PanelPrintables` en `PantallaSubir.kt`, `EstadoPrintables`).

2. **Creality: subir también lo que ya está en `subidos/`.** El Uploader de MakerOnline mueve las carpetas a
   `subidos/` y `SubidorCreality` solo miraba `pendientes/`. Nuevo `SubidorCreality.Opciones.desdeSubidos`
   (terminal: `--subidos`), `SubidorCreality.subidosSinCreality(rutas)` y el botón **"Subir a Creality las de
   subidos"** en Subir (`EstadoSubida.subirSubidosACreality`). No mueve carpetas.

3. **Documentación**: `CLAUDE.md` (contexto y reglas del proyecto para la IA), `docs/contexto-printables.md`
   (todo lo comprobado en la web, con fecha) y este archivo.

## Archivos

| Nuevos | |
|---|---|
| `core/.../CatalogoPrintables.kt` | Categorías y licencias de Printables (texto exacto), categoría fija, licencias desde MakerOnline |
| `core/.../ColaPrintables.kt` | Cola y ficha de cada modelo para la extensión |
| `core/.../ServidorPrintables.kt` | Servidor local para la extensión (también programa de terminal) |
| `core/.../DiagnosticoPrintables.kt` | Comprueba si Cloudflare deja pasar al Chrome de la app |
| `desktop/.../EstadoPrintables.kt` | Copia la extensión y arranca el servidor |
| `desktop/src/main/resources/extension-printables/*` | La extensión de Chrome |
| `.run/cola-printables.run.xml`, `.run/diagnostico-printables.run.xml` | Configuraciones de IntelliJ |
| `CLAUDE.md`, `docs/contexto-printables.md`, `docs/cambios-printables.md` | Documentación |

| Cambiados | |
|---|---|
| `ModelFolder.java` | Ignora `subido-printables.txt` (y `resultado/error-printables.*`) |
| `SubidorCreality.kt` | `desdeSubidos`, `--subidos`, `subidosSinCreality()` |
| `EstadoSubida.kt`, `PantallaSubir.kt`, `Textos.kt` | Tarjeta Printables y botón de Creality desde subidos |
| `EstadoApp.kt`, `App.kt` | Crea `EstadoPrintables` (y lo cierra al salir) y lo pasa a `PantallaSubir` |
| `core/build.gradle.kts` | Tarea `:core:diagnosticoPrintables` |

## Fusión con `main`

Ya está fusionado `main` (commit `dba0844`, "funcionalidades de creality completas": descargas de Creality)
dentro de esta rama. Solo hubo conflicto en `App.kt` y `EstadoApp.kt`, resuelto quedándose con lo de los dos
(`descargas` / `EstadoDescargas` del compañero + `printables`). Si `main` ha cambiado después, hacer
`git merge origin/main` en `printables` y resolver igual antes de fusionar.

## Comprobar antes de fusionar

- `.\gradlew.bat build` en Windows (no se pudo compilar en el entorno de Claude: sin acceso a Gradle).
- App: Subir (tarjeta Printables, botón "Subir a Creality las de subidos") y Descargar (MakerOnline y Creality).
- Printables: con la app abierta, cargar la extensión y pulsar "Subir a Printables".

## Fuera de esta rama

- **MakerWorld** (Bambu Lab): rama `makerworld`, PR #1 en borrador. Se dejó porque pide fotos de impresiones
  reales. No se fusiona.
- Borradores de prueba en la cuenta de Printables de la persona (1908893, 1908897, 1908899): pendientes de borrar.
