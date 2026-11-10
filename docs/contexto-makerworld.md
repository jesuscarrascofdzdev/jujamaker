# Contexto: MakerWorld en JujaMaker

Rama: `makerworld`. Objetivo: que JujaMaker suba a **MakerWorld** (Bambu Lab, https://makerworld.com) las
mismas carpetas de `subidas/pendientes/` que ya sube a MakerOnline y a Creality Cloud.

> Regla del proyecto: nada de la web se programa de memoria. Cada dato (selectores, endpoints, cookies,
> categorías, límites) se comprueba en la página o en su código y se anota aquí **con la fecha**, igual
> que se hizo con Creality (`CatalogoCreality`, `DiagnosticoCreality`).

## Estado

| Pieza | Estado |
|---|---|
| `core/.../SubidorMakerWorld.kt` | Esqueleto: valida carpetas, no sube nada todavía |
| `core/.../CatalogoMakerWorld.kt` | Esqueleto vacío |
| `.run/subir-makerworld-revisar.run.xml` | Lanza el esqueleto en modo `--revisar` |
| Diagnóstico de MakerWorld | Por hacer (copiar el patrón de `DiagnosticoCreality.kt`) |
| Integración en la app (enum `Web`, sesión, subida múltiple, pantallas) | Por hacer (salvo `ModelFolder`) |

## Qué hay que averiguar de la web (antes de programar la subida)

Con sesión iniciada en el perfil de Chrome de la app, y con un diagnóstico que lo vuelque a un `.txt`:

- [ ] **Sesión:** qué cookie indica que hay sesión iniciada (en Creality era `model_token`) y en qué dominio.
- [ ] **Página de subida:** la dirección exacta y los pasos del formulario (¿un asistente por pasos o una sola página?).
- [ ] **Archivos:** ¿admite `.stl` o exige `.3mf`? ¿Pide un perfil de impresión de Bambu Studio dentro del `.3mf`?
- [ ] **Borrador o privado:** si se puede guardar sin publicar (la app siempre deja la última palabra a la persona).
- [ ] **Campos:** título, descripción (¿editor enriquecido?), etiquetas, portada(s) y su proporción, galería.
- [ ] **Categorías:** lista con ids (de la página o de su API) → rellenar `CatalogoMakerWorld.CATEGORIAS`.
- [ ] **Licencias:** textos exactos → `CatalogoMakerWorld.LICENCIAS`. Por defecto, la equivalente a CC0 si existe.
- [ ] **Equivalencias:** cada categoría de `CatalogoMakerOnline` → una de MakerWorld (`DESDE_MAKERONLINE`).
- [ ] **Cómo saber que ha ido bien:** respuesta o dirección final con el id del modelo, para `subido-makerworld.txt`.
- [ ] **Captchas y límites:** si salen, cuándo, y cuántas subidas seguidas admite sin problemas.
- [ ] **Normas de MakerWorld:** revisar sus términos sobre subidas automatizadas y contenido.

## Dónde tocar para integrarlo en la app (visto en el código de Creality)

**core**
- `Web.kt`: añadir `MAKERWORLD("MakerWorld", "https://makerworld.com/")`. Ojo: hay `when (web)` exhaustivos en
  desktop (`Iconos.kt`, `EstadoSesion.kt`, `EstadoSubida.kt`) que dejarán de compilar hasta que tengan su rama.
- `Sesion.kt`: detectar la cookie de MakerWorld (como `tokenCreality`) y añadir `makerWorld` al estado.
- `SubidaMultiple.kt`: decidir el orden. Ahora es Creality → MakerOnline porque el Uploader de MakerOnline
  mueve la carpeta a `subidos/`. MakerWorld tiene que ir **antes** de MakerOnline (no mueve nada).
- `ModelFolder.java`: hecho, ya ignora `subido-makerworld.txt`, `resultado-makerworld.png` y `error-makerworld.*`.

**desktop**
- `Ajustes.kt`: `subirMakerWorld` (como `subirCreality`).
- `EstadoSesion.kt` / `PantallaSesion.kt`: botón de iniciar sesión y estado de MakerWorld.
- `EstadoSubida.kt` / `PantallaSubir.kt`: casilla para subir a MakerWorld e insignias de estado por carpeta.
- `Iconos.kt`, `Textos.kt`: icono y textos de MakerWorld.

## Pruebas

Siempre con `--revisar` y con un modelo de prueba, hasta que el formulario salga bien varias veces seguidas.
