# JujaMaker: contexto para Claude

## Qué es
App de escritorio (Windows) para **subir modelos 3D de forma masiva a varias plataformas de impresión 3D**
y descargar modelos de MakerOnline. App no oficial.

- **MakerOnline** y **Creality Cloud**: hechas (autor original del repo).
- **MakerWorld** (Bambu Lab): en curso en la rama `makerworld`. Plan y checklist en `docs/contexto-makerworld.md`.

## Estructura
- `core/`: lógica sin interfaz. Java heredado (`src/main/java`: Uploader, Downloader, ModelFolder, AgentLock…)
  y Kotlin nuevo (`src/main/kotlin`: SubidorCreality, SubidaMultiple, Sesion, catálogos…). Paquete `dev.jefe.makeronline`.
- `desktop/`: app con Compose for Desktop (Kotlin). Paquete `dev.jefe.makeronline.desktop`.
- `.run/`: configuraciones de IntelliJ; cada subidor también se lanza como programa de terminal.
- Gradle con Kotlin DSL, JDK 21, versiones en `gradle/libs.versions.toml`. Automatización con Playwright sobre Chrome.

## Cómo se añade una plataforma (patrón de Creality)
1. `Catalogo<Web>.kt`: categorías/licencias **sacadas de la web, con fecha**, y la equivalencia desde las de MakerOnline.
2. `Diagnostico<Web>.kt`: vuelca a un `.txt` lo que hay en el formulario real, para comprobar selectores.
3. `Subidor<Web>.kt`: valida carpetas → abre Chrome con el perfil de la app → rellena → `--revisar` → envía.
   Deja `subido-<web>.txt` (no se vuelve a subir), o `error-<web>.txt` + `error-<web>.png`. No mueve carpetas.
4. Integración: enum `Web`, `Sesion` (cookie de sesión), `SubidaMultiple` (orden: las que no mueven carpetas
   antes que MakerOnline), `ModelFolder` (archivos ignorados) y pantallas de desktop.

## Reglas
- Código, comentarios, nombres y textos en **español**, con el estilo del código existente (KDoc que explica el porqué).
- Nada de la web se programa de memoria: se comprueba y se anota la fecha.
- Se sube como borrador o privado; la persona publica.
- Los captchas **no se saltan**: se enseña la ventana. La sesión (perfil de Chrome) nunca sale del ordenador.
- Todo lo que usa Chrome coge el candado `AgentLock`.
- Cambios en ramas y con pull request; no tocar `main` directamente.
