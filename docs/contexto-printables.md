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

## Cómo se sube

1. En la app, "Subir a Printables" abre `https://www.printables.com/model/create#jujamaker-auto` (modo seguido).
2. La extensión pide a la app el siguiente modelo y rellena: archivos, nombre, resumen, categoría, etiquetas,
   descripción y licencia.
3. La persona marca el origen ("Original model – I made it", "Remix…", "Reupload…") y si se usó IA, revisa y pulsa
   **SAVE DRAFT** (arriba tiene que poner DRAFT).
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
  guardó sin marcarlos); la extensión no los toca: los marca la persona.
- **Description**: editor Tiptap (`.tiptap.ProseMirror`, con `.editor`): `editor.commands.setContent(html, true)`.
- **License (required)**: `button[id^="f-license-"]` abre `.dropdown-menu` con `button.option`. Textos exactos con
  rayas "—" (ver `CatalogoPrintables.LICENCIAS`).
- **Guardar**: desde `/model/create`, "SAVE DRAFT" crea el modelo y la página salta (sin recargar) a
  `/model/<id>/edit`. Al recargar esa página está todo lo guardado. La página del modelo, `/model/<id>`, sale con la
  banda "DRAFT".
- **Borrar**: en `/model/<id>`, menú de tres puntos ("Open dropdown menu") → "Delete".

## Condiciones de Printables (válidas desde el 15/10/2026)

https://www.prusa3d.com/page/printables-com-terms-of-service_231249/ — no tienen ninguna cláusula sobre acceso
automático, bots o scripts. Sí dicen (cl. 3.2) que las subidas duplicadas o repetidas de modelos o fotos van contra
las normas: **no subir dos veces el mismo modelo** (por eso `subido-printables.txt`).

## Pendiente

- [ ] Compilar en el PC, cargar la extensión y probar con un modelo de verdad (el .3mf de 13,8 MB de SKYLINE_GTR34).
- [ ] Borrar los borradores de prueba 1908893, 1908897 y 1908899 (con la persona).
- Primer modelo de verdad: SKYLINE_GTR34 → borrador 1908931 (10/11/2026). Se quedaron fuera 4 etiquetas con guion
  (antes del arreglo) y la categoría se corrigió a mano a Other Toys & Games.

## Si la web cambia

Abrir `https://www.printables.com/model/create` en el Chrome de la persona (Claude in Chrome) y volver a mirar los
selectores de arriba; anotar aquí lo nuevo con la fecha.
