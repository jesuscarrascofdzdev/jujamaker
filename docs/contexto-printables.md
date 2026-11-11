# Printables: contexto

> Regla: nada de la web de Printables se programa de memoria. Todo lo que use la extensión (campos, botones,
> categorías, licencias, límites) se comprueba en la página o en su código y se anota aquí **con la fecha**.

## Estado

- Rama `printables`, desde `main` (MakerWorld se queda aparte en su rama `makerworld`; se dejó el 10/11/2026
  porque, además de Cloudflare, pide fotos de impresiones reales).
- **Printables no deja entrar al Chrome de la app**: `DiagnosticoPrintables` (10/11/2026) estuvo casi 4 minutos en
  "Just a moment..." en bucle. Por eso se sube como se planteó MakerWorld: **extensión de Chrome** en el Chrome
  de siempre, que rellena el formulario con la cola de la app. La persona revisa y guarda.

| Pieza | Qué hace |
|---|---|
| `core/.../CatalogoPrintables.kt` | Categorías y licencias de Printables (texto exacto), la categoría de todos los modelos y las licencias desde MakerOnline |
| `core/.../ColaPrintables.kt` | La cola: carpetas de `pendientes/` y `subidos/` sin `subido-printables.txt`; la ficha de cada una |
| `core/.../ServidorPrintables.kt` | Servidor en `127.0.0.1:47812` para la extensión (solo `Origin: chrome-extension://` para POST) |
| `core/.../DiagnosticoPrintables.kt` | Mira si Cloudflare deja pasar al Chrome de la app (no deja) |
| `desktop/.../EstadoPrintables.kt` | Copia la extensión a `<datos>/extension-printables` y arranca el servidor |
| `desktop/.../PantallaSubir.kt` | Tarjeta "Printables": cuántos faltan, "Subir a Printables", carpeta de la extensión |
| `desktop/src/main/resources/extension-printables/` | La extensión: `manifest.json`, `background.js`, `panel.js`, `pagina.js` |
| `.run/cola-printables.run.xml`, `.run/diagnostico-printables.run.xml` | Programas de terminal |
| `core/.../ListaUsuariosPrintables.kt` | La lista de usuarios de los que se descarga (`usuarios-printables.json`), por su @handle |
| `core/.../DescargasPrintables.kt` | Descargas: el trabajo para la extensión, dónde se guarda cada archivo, `info.json` y `descargados-printables.txt` |
| `desktop/.../EstadoDescargaPrintables.kt`, `PantallaDescargarPrintables.kt` | Pestaña "Printables" de Usuarios en la pantalla Descargar |
| `extension-printables/descargas.js` | La parte de la extensión que descarga (se activa con `#jujamaker-descargar`) |

## Cómo se sube

1. En la app, "Subir a Printables" abre `https://www.printables.com/model/create#jujamaker-auto` (modo seguido).
2. La extensión pide a la app el siguiente modelo y rellena: archivos, nombre, resumen, categoría, etiquetas,
   descripción, licencia, origen "Original model – I made it" e IA "No — fully human-made" (decisión de la
   persona, 10/11/2026: sus modelos son suyos y sin IA).
3. La extensión pulsa **SAVE DRAFT** (solo si arriba está en DRAFT; si no, para y avisa). Lo pidió la persona
   (10/11/2026) para subir todos seguidos sin tocar nada. Nunca publica: la persona revisa los borradores y publica.
4. La web salta a `/model/<id>/edit`; la extensión comprueba lo guardado, la app apunta `subido-printables.txt`
   (id, dirección, fecha) y, en modo seguido, abre el formulario del siguiente.

## Comprobado en la web (10/11/2026, con Claude in Chrome en el Chrome de la persona, web en inglés)

- **Cloudflare**: en el navegador de Claude (el integrado) y en el Chrome de la app sale "Verificación de seguridad
  en curso" / "Just a moment..." (HTTP 403) en bucle. En el Chrome de siempre de la persona entra sin problema.
- **Tecnología**: SvelteKit. Los campos tienen id `f-<campo>-<número>` (el número cambia en cada carga): se buscan
  por el principio del id.
- **Barra de arriba**: interruptor `#publish-state` (checkbox; sin marcar = DRAFT), "Close" y **"SAVE DRAFT"**
  (`button.btn-primary` en el mismo contenedor que el interruptor). "SAVE DRAFT" está **desactivado mientras se
  suben archivos**.
- **Archivos**: una sola zona arriba, `input[type=file][id^="files-upload-box"]` (multiple; acepta `.3mf .stl .obj
  .step .jpg .png .webp .zip…`). Al soltar, la web reparte sola: imágenes a **Photos**, `.stl/.3mf` a **Model Files**
  (cada uno una fila `.print-file-name-input`), `.gcode/.bgcode/.sl1` a **Print Files**, el resto a **Other Files**.
  Se sube a `storage.googleapis.com` (bucket `prusa3d-printables-prod-…`); mientras, se ve un `.spinner-wrapper`.
- **Photos**: recuadros `.images .image`. **El orden es el de fin de subida, no el de soltado**: la portada se sube
  sola y antes. La primera es la portada (letrero `span.cover-btn` "cover photo"); las demás tienen
  `button.cover-btn.set` "set as cover", que la pasa delante. La web renombra a minúsculas y `.webp`.
- **Model name (required)**: `input[id^="f-name-"]`, sin `maxlength`.
- **Summary (required)**: `textarea[id^="f-summary-"]`, `maxlength=120`, contador "0/120". MakerOnline no tiene
  resumen: la app usa la primera línea de la descripción (cortada a 120 con "…").
- **Main category (required)**: `button[id^="f-category-"]` abre `.dropdown-menu.groupped` con títulos de grupo y
  `button.option` (`data-index` = posición, no id). Se elige por grupo + texto. **Todos los modelos van a
  Toys & Games > Other Toys & Games** (decisión de la persona, 10/11/2026: una amplia que valga para todos;
  se puede cambiar en Printables antes de publicar).
- **Additional tags**: `input[id^="f-tags-"]`, "Use space to separate tags". Se escribe y Enter (keydown) o espacio;
  cada una es un `.badge`. "dos palabras" entra como dos etiquetas. **Solo admite `[a-z0-9]`**: "scale-model",
  "ABC", "año", "a_b", "x.y" no entran (sin aviso); "3d" sí. La app ya las manda así ("Scale-Model" → "scalemodel").
- **Model origin**: radios `name=authorship` (Original / Remix / Reupload, `value=on`). **Was AI used**: radios
  `name=aiGenerated` (Yes / No). También casillas NSFW y Political. **No hacen falta para guardar el borrador** (se
  guardó sin marcarlos). La extensión marca el primero de origen (Original) y el segundo de IA (No), por
  posición; se guardan con el borrador.
- **Description**: editor Tiptap (`.tiptap.ProseMirror`, con `.editor`): `editor.commands.setContent(html, true)`.
- **License (required)**: `button[id^="f-license-"]` abre `.dropdown-menu` con `button.option`. Textos exactos con
  rayas "—" (ver `CatalogoPrintables.LICENCIAS`).
- **Guardar**: desde `/model/create`, "SAVE DRAFT" crea el modelo y la página salta (sin recargar) a
  `/model/<id>/edit`. Al recargar esa página está todo lo guardado. La página del modelo, `/model/<id>`, sale con la
  banda "DRAFT".
- **Borrar**: en `/model/<id>`, menú de tres puntos ("Open dropdown menu") → "Delete".

## Archivos de más en un modelo (arreglado el 11/11/2026)

A veces un modelo se guardaba con varios .3mf (iguales o no). El código no impedía que el formulario ya tuviera
archivos al empezar (de un intento anterior en la misma pestaña, o un segundo "rellenar" a la vez) y al final
solo se comprobaba que no *faltaran* archivos, no que no *sobraran*. Ahora:
- `pagina.js`: un solo "rellenar" a la vez y uno por página; si el formulario ya trae fotos o archivos del
  modelo, para sin soltar nada; la portada solo se reintenta si la web no ha hecho nada; antes de "SAVE DRAFT"
  exige el número exacto de filas en "Model Files" y sin nombres repetidos (si no, para y no guarda); al
  comprobar lo guardado también avisa si sobran.
- `panel.js`: botón "Formulario nuevo" también en el formulario, para empezar limpio tras un error.
- `ColaPrintables`: los archivos del modelo iguales byte a byte (SHA-256) van una sola vez, con aviso.
- No se ha podido reproducir el fallo original: si vuelve a pasar, el panel dirá ahora cuántos archivos había.
  Se miró si Printables guarda el formulario a medias (localStorage, sessionStorage, IndexedDB): no guarda nada.

## Qué carpetas entran en la cola (11/11/2026)

Solo las que la persona mete en la app: las de `pendientes/` y, de `subidos/`, las que pasaron antes por
`pendientes/` (se apuntan en `printables-vistas.txt` en la carpeta de datos). Las que ya estaban en `subidos/`
de antes (subidas a MakerOnline hace tiempo) no entran. La primera vez, sin ese archivo, también cuentan las de
`subidos/` cuyo `resultado.png` es del 10/11/2026 en adelante. Para meter una antigua: muévela a `pendientes/`.

## Descargar de Printables (11/11/2026)

Misma lógica que MakerOnline y Creality (últimos N gratis de cada usuario, saltar los que tienen tu like, like al
terminar, `descargados-printables.txt`, máximo por vez, pausa de 10 a 30 s, zip del escritorio), pero **descarga la
extensión** en el Chrome de la persona: la app no puede entrar (Cloudflare). "Descargar de Printables" en la app
prepara el trabajo (`DescargasPrintables`) y abre `https://www.printables.com/#jujamaker-descargar`; la extensión
(`descargas.js`) lo pide, recorre usuarios y modelos con la API de la web y le pasa cada archivo a la app
(`ServidorPrintables`, rutas `/printables/descargas/…`). Los archivos los baja `background.js` (host_permissions de
files/media.printables.com) para que no pasen por los mensajes. Al terminar o parar, la app hace el zip.

Comprobado en la web el 11/11/2026 (Claude in Chrome, sesión de la persona; sin descargar nada ni dar likes):
- **API**: `POST https://api.printables.com/graphql/` con la sesión (`credentials: 'include'`), sin más cabeceras
  que `content-type` (la web añade `client-uid` y `graphql-client-version`, no hacen falta). Sin introspección.
- **Modelos de un usuario**: `userModels(userId: "@handle", paid: free, limit: 36, cursor, ordering: "-first_publish")`
  → `cursor` e `items { id name liked price club: premium user { handle publicUsername } }`. `liked` es el like de
  la persona. El handle también vale con el número de usuario.
- **Un modelo**: `print(id) { name description (HTML) summary tags { name } image { filePath } license { name }
  category { path { nameEn } } user { publicUsername } stls { id name fileSize folder } gcodes otherFiles
  downloadPacks }`. En `stls` van los .stl y .3mf (a veces con nombre "x.stl.stl"). La licencia sale en el idioma
  de `accept-language`: se pide en inglés.
- **Enlace de descarga**: `mutation GetDownloadLink(id, printId, fileType: stl, source: model_detail)` →
  `output.link` firmado en `files.printables.com` (es lo que hace el botón "Download" de cada archivo).
- **Fotos**: `https://media.printables.com/<filePath>` (la original; también vale files.printables.com).
- **Like**: `mutation LikeCreate(targetObjectId, targetType: LikeTargetTypeEnum)`. El valor **`print`** se sacó
  del código de la web; **no se ha probado** (no se dio ningún like). Si falla, la extensión avisa una vez y no lo
  vuelve a intentar en esa vez; lo descargado queda apuntado y la próxima vez solo le faltará el like.

## Condiciones de Printables (válidas desde el 15/10/2026)

https://www.prusa3d.com/page/printables-com-terms-of-service_231249/ — no tienen ninguna cláusula sobre acceso
automático, bots o scripts. Sí dicen (cl. 3.2) que las subidas duplicadas o repetidas de modelos o fotos van contra
las normas: **no subir dos veces el mismo modelo** (por eso `subido-printables.txt`).

## Pendiente

- [ ] Compilar y probar la descarga: primero "Simular", luego un usuario con "Máximo 1". Confirmar que el like
      funciona (`targetType: print`) y anotarlo aquí.
- [ ] Probar la subida con el arreglo de archivos de más (extensión 1.2.0: en chrome://extensions, ↻).

- [ ] Compilar en el PC, cargar la extensión y probar con un modelo de verdad (el .3mf de 13,8 MB de SKYLINE_GTR34).
- [ ] Borrar los borradores de prueba 1908893, 1908897 y 1908899 (con la persona).
- Primer modelo de verdad: SKYLINE_GTR34 → borrador 1908931 (10/11/2026). Se quedaron fuera 4 etiquetas con guion
  (antes del arreglo) y la categoría se corrigió a mano a Other Toys & Games.

## Si la web cambia

Abrir `https://www.printables.com/model/create` en el Chrome de la persona (Claude in Chrome) y volver a mirar los
selectores de arriba; anotar aquí lo nuevo con la fecha.
