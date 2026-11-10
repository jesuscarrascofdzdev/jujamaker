# Contexto: MakerWorld en JujaMaker

Rama: `makerworld`. Objetivo: que JujaMaker suba a **MakerWorld** (Bambu Lab, https://makerworld.com) las
mismas carpetas de `subidas/pendientes/` que ya sube a MakerOnline y a Creality Cloud.

> Regla del proyecto: nada de la web se programa de memoria. Cada dato (selectores, endpoints, cookies,
> categorías, límites) se comprueba en la página o en su código y se anota aquí **con la fecha**, igual
> que se hizo con Creality (`CatalogoCreality`, `DiagnosticoCreality`).

## Estado

**Enfoque (10/11/2026): a MakerWorld no sube la app, sube la persona con una extensión de Chrome.**
MakerWorld (Cloudflare) no deja entrar al Chrome que maneja la app: en la portada sale "Verificación de seguridad en
curso" y la casilla vuelve a salir en bucle aunque la marque la persona. MakerOnline y Creality también usan
Cloudflare pero no tienen ese filtro. Disfrazar el Chrome automatizado sería saltarse la protección: no se hace.
Printables (mismo filtro, más fuerte) y Thingiverse (su API prohíbe que la app suba a otras webs) se descartaron.

| Pieza | Estado |
|---|---|
| `core/.../CatalogoMakerWorld.kt` | Hecho: categorías con id, licencias, límites, equivalencias desde MakerOnline |
| `core/.../ColaMakerWorld.kt` | Hecho: carpetas de pendientes/ y subidos/ sin `subido-makerworld.txt`, su ficha y sus archivos |
| `core/.../ServidorMakerWorld.kt` | Hecho: servidor en 127.0.0.1:47811 para la extensión (también programa de terminal) |
| `desktop/src/main/resources/extension-makerworld/` | Hecha. `pagina.js` probado contra MakerWorld real el 10/11/2026 (relleno completo y guardado) |
| desktop: `EstadoMakerWorld` + tarjeta "MakerWorld" en Subir | Hecho, sin compilar |
| `.run/cola-makerworld.run.xml` | La cola sin la app, para probar |

## Cómo se sube a MakerWorld

1. La app, al abrirse, copia la extensión a `<datos>/extension-makerworld/` y arranca el servidor de la cola.
2. Una vez: en Chrome, `chrome://extensions` → "Modo de desarrollador" → "Cargar descomprimida" → esa carpeta.
3. En tu Chrome normal, MakerWorld → formulario de subida (`/en/my/models/publish`). Sale el panel de JujaMaker
   abajo a la derecha con el siguiente modelo de la cola.
4. "Rellenar este": elige "I have STL/CAD files", sube los `.stl`/`.3mf`, pasa al paso 2 (MakerWorld crea el
   borrador), pone las dos portadas (acepta el recorte), las demás imágenes en "Model Pictures", nombre,
   categoría, etiquetas (quita las que MakerWorld no admite), licencia, Private y descripción.
   Con "Subir a MakerWorld" en la app se abre el formulario con `#jujamaker-auto` (**modo seguido**): el panel
   rellena solo cada modelo y, tras guardar y comprobar el borrador, abre el formulario del siguiente. Se para
   con "Parar seguido", si falla algo o si se acaba la cola.
5. La persona marca **Original o Remix** (MakerWorld no deja guardar ni el borrador sin eso: "Please enter model
   origin"), revisa y pulsa **"Save to draft"**.
6. La extensión lee el borrador guardado, comprueba nombre, categoría, licencia, privado, portadas y archivos, y
   le dice a la app que lo apunte (`subido-makerworld.txt`: id, dirección del borrador y fecha).

La cola incluye subidos/: MakerOnline no espera a MakerWorld (SubidaMultiple sigue como estaba).

**Seguridad del servidor**: solo escucha en 127.0.0.1. Las peticiones que cambian algo (POST) tienen que llevar
`Origin: chrome-extension://…`, que una web no puede poner. Solo sirve los archivos de las fichas de la cola.

**Detalles comprobados para la extensión (10/11/2026)**
- Las etiquetas no se añaden escribiendo y mandando Enter desde un script (el campo solo hace caso al teclado de
  verdad). Se ponen en el formulario, que es de Formik: su `setFieldValue('tags', …)` está en las props de un
  componente por encima de `.js-scroll-tags`. El mismo componente tiene `forbiddenWords` (marcas: "POP MART"…).
- Formik también tiene `coverIsUploading`, `appCoverIsUploading`, `picturesIsUploading`, `rawModelFileIsUploading`.
- Las subidas a S3 se ven en `performance.getEntriesByType('resource')` (una por archivo; un `.stl`, dos).
- En el paso 1 cada archivo subido sale como `[class*="js-scroll-modelFiles-"]`.
- Borrar un borrador: `DELETE /api/v1/design-service/my/draft/<id>` (después se lee con `status: 13`).

## Comprobado en la web (10/11/2026)

Visto en el formulario real con una cuenta con sesión, subiendo un cubo `.stl` de prueba como borrador
(borrador 10459950, "Prueba JujaMaker (borrar)", ya borrado). Lo leído antes en la wiki y el foro está más abajo.

**La web**: Next.js (`window.__NEXT_DATA__`) con componentes de MUI y el editor CKEditor 5. Cada sección del
formulario tiene una clase fija `js-scroll-<campo>`, que es lo que usa el subidor en vez de los textos o de las
clases `mw-css-…` (cambian con cada versión).

**Sesión**
- Sin sesión, `/en/my/models/publish` redirige (desde el servidor) a `/en/sign-in/service?cb=…`.
- `GET /api/v1/design-user-service/my/preference` responde 200 con sesión y 401 sin ella → lo usa `Sesion`.
- La cookie de la sesión es httpOnly: no se ve desde la página, por eso se pregunta a la API en vez de buscarla.
- `__NEXT_DATA__.props.pageProps.session.user` existe cuando hay sesión.
- Aviso de cookies en la primera visita: botón "Reject All".
- Cloudflare: al iniciar sesión desde el Chrome de la app salió "Verificación de seguridad en curso" (título "Un momento…",
  recuadro "Verifique que es un ser humano"). No se salta: el subidor enseña la ventana y espera hasta 5 minutos.

**Formulario** (`/en/my/models/publish`), en pasos:
1. "Do you have a Bambu Studio file(.3mf) for this model?":
   - "Yes (earn extra points reward)" (`value=true`): 3 pasos (Upload, Model Information, Print Profile
     Information). Pide un .3mf en "Bambu Studio File (Print Profile)": *"Only 3mf files produced by Bambu Studio
     are supported"*.
   - "I have STL/CAD files or other types of 3MF files" (`value=false`): 2 pasos. **Es el que usa la app.**
2. "Raw Model Files": `input[type=file][multiple]` que acepta
   `.3ds .amf .dwg .dxf .f3d .factory .fcstd .iges .ipt .obj .ply .rsdoc .scad .shape .shapr .skp .sldasm .sldprt
   .slvs .step .stl .stp .studio3 .zip .3mf .stpz`. Cada archivo sale con la casilla "Open Source" marcada.
   "Does this model include a Laser & Cut model?" viene en "No".
3. "Next Step" crea el borrador (`POST /api/v1/design-service/my/draft` → `{"id": …}`) y la página salta a
   `/en/my/models/drafts/<id>/edit` ("Edit Model Draft").

**Subida de archivos**: por cada archivo, `POST /api/v1/design-user-service/my/upload` responde
`{cdnPrefix, urls:[dirección firmada]}` y la página lo sube por XHR a
`or-cloud-makerworld-prod.s3.us-west-2.amazonaws.com`. Un `.stl` pide dos direcciones (el archivo y una miniatura
.jpeg que hace la propia página); cada imagen, una.

**Paso 2 · Model Information** (clase de cada sección entre paréntesis):
- Origen (`js-scroll-modelSource`, `name=modelSource`): Original / Remix / Share (Share desactivado). Ninguno marcado.
- Portadas (`js-scroll-cover`): "jpg/gif/png, ≤ 30MB. Please use real print photos". "Web/App cover 4:3"
  (`div.dropzone`) y "App cover 3:4" (`div.dropzone.appUpload`). Al elegir imagen sale un diálogo de recorte
  (cropper.js) con botón "Submit"; la 4:3 de una imagen cuadrada queda en 800×600.
- Vídeo (`js-scroll-designVideo`): mp4/mov, máx. 30 s.
- "Real life photos ( 0 / 16 )" (`js-scroll-realPictures`): "Upload ≥3 photos of the printed model, covering 3 views".
- "Model Pictures ( 0 / 16 )" (`js-scroll-designPictures`): png/jpg/webp/gif, ≤ 30MB, 4:3 recomendado. Sin recorte.
- "Model Name" (`js-scroll-title`, `input[name=title]`): contador "0/50".
- "Category" (`js-scroll-categoryId`): autocompletado; solo se eligen subcategorías (ver catálogo).
- "Tags (0 / 50)" (`js-scroll-tags`): se escribe y Enter; contador "0/100" por etiqueta.
- "License" (`js-scroll-license`): cuatro preguntas (adaptaciones, uso comercial, redistribuir, solo Bambu Handy) y un
  desplegable `input[name=license]`. Al elegir licencia en el desplegable, la web ajusta las preguntas (CC0 → Sí/Sí/Sí).
  Por defecto: "Standard Digital File License".
- "Visibility" (`.submitPrivate`): Public (por defecto) / Private.
- "Description" (`js-scroll-summary`): CKEditor 5 (`.ck-editor__editable` con `ckeditorInstance`).
- Además: Community Post, documentación, Exclusive Model Program ("Not Eligible" en la cuenta de prueba), BOM.
- Botones: "Previous", **"Save to draft"**, "Publish".

**Guardar**: "Save to draft" → `PUT /api/v1/design-service/my/draft/<id>` (200, sin cuerpo). Lo guardado se lee con
`GET /api/v1/design-service/my/draft/<id>`: `title`, `categoryId`, `license`, `tags`, `summary`, `modelSource`,
`modelFiles[]`, `cover` (4:3), `coverPortrait` (3:4), `realPictures`, `designPictures`,
`designSetting.submitAsPrivate`, `status`… El subidor lo lee después de guardar para confirmarlo.

**Licencias** (`data-value` del desplegable): `CC0`, `BY`, `BY-SA`, `BY-ND`, `BY-NC`, `BY-NC-SA`, `BY-NC-ND`,
`Standard Digital File License`, `MakerWorld Exclusive License`, `Standard Digital File License - Community Use`,
`Standard Digital File License - Platform Print Only (SDFL-PPO)`.

**Categorías**: en `CatalogoMakerWorld.CATEGORIAS`, con su id (las opciones del autocompletado tienen
`id, name, slug, parentId, parentName`).

**Con un .3mf de Bambu Studio: "Yes (earn extra points reward)"** (comprobado el 10/11/2026 con el .3mf de
SKYLINE_GTR34, con la malla cambiada por un cubo; borradores de prueba 10462473 y 10462596, ya borrados):
- Zonas: `.js-scroll-model3Mf input[type=file][accept=".3mf"]` (un archivo, máx. 200 MB) y "Raw Model Files"
  (`.js-scroll-modelFiles`, opcional).
- La página abre el .3mf ella misma (JSZip): `3D/3dmodel.model` con `Application` = `BambuStudio…`, no puede ser
  un .3mf con G-code, y la impresora (`printer_settings_id` de `Metadata/project_settings.config`) tiene que estar
  en `GET /api/v1/iot-service/api/slicer/device/compatibility?dev_setting_name=…`. **No hace falta impresora
  Bambu Lab**: "Anycubic Kobra Max 0.4 nozzle" responde `{compatibility: {dev_model_name: "Anycubic-Kobra-Max"…}}`
  y se acepta.
- Si lo acepta, sube el .3mf y las imágenes de las placas y rellena sola `model3Mf`, `profileTitle`
  ("0.2mm layer, 3 walls, 15% infill"), `printer`, `compatibility`, `projectSettings`, `details`. Estado en
  `uploading3mfStatus` del formulario: 1 subiendo, 2 bien, 3 error; si lo rechaza al abrirlo se queda en 0 y
  sale un aviso arriba. Un .3mf que no es de Bambu Studio sale como "0 byte / Retry".
- "Next Step" crea el borrador igual. El paso 2 es el mismo; el último botón ya no es "Publish" sino
  "Add Print Profile" (paso 3, "Print Profile Information"), y "Save to draft" sigue justo antes.
- "Save to draft" en el paso 2 guarda todo, perfil incluido (`model3Mf`, `profileTitle`; `modelFiles` vacío).
- La extensión: si la carpeta tiene un .3mf, elige "Yes" y lo sube como perfil; el resto va a "Raw Model
  Files". Si MakerWorld no lo acepta, elige "I have STL/CAD files" y lo sube todo como archivos. El paso 3 no
  lo toca (lo rellena MakerWorld con el .3mf): lo revisa la persona antes de publicar.

**Lo que hace la extensión sin depender del idioma ni de las prisas** (comprobado el 10/11/2026, borradores de prueba
10462080 y 10462166, ya borrados):
- Pregunta del .3mf: al abrir la página no hay ninguna marcada y "Next Step" sin elegir da *"Please select a type."*.
  Si se pulsa antes de que React se haya hecho con la página (el `input` aún no tiene `__reactProps…`), el círculo se
  marca pero no salen las zonas. La extensión espera a React, pulsa `input[type=radio][value=false]` y repite hasta
  que está marcado, existe `.js-scroll-modelFiles input[type=file]` y **no** existe `.js-scroll-model3Mf` (la zona
  del .3mf, que solo sale con "Yes"). No pulsa "Next Step" antes de subir los archivos.
- Botones por su forma: el último `main button.MuiButton-contained` es "Next Step" en `/publish` y "Publish" en el
  borrador; "Save to draft" es el botón justo antes de "Publish"; en el recorte, el contained del `.MuiDialog-root`.
- Categoría y licencia con `setFieldValue('categoryId', id)` y `setFieldValue('license', valor)` del formulario
  (Formik): la web las pinta bien ("Other Toys & Games", "Creative Commons Attribution-Share Alike") y se guardan.

## Lo que dice la documentación de Bambu Lab (leído el 10/11/2026)

Fuentes: wiki de Bambu Lab ([cómo subir modelos](https://wiki.bambulab.com/en/makerworld/tutorials/how-to-upload-models),
[normas del perfil de impresión](https://wiki.bambulab.com/makerworld/tutorials/print-profile-upload)) y foro de Bambu Lab.

- No hace falta un `.3mf` por impresora: MakerWorld vuelve a laminar el perfil en la nube y decide con qué impresoras
  es compatible. Un perfil sirve para toda la serie (X1/P1…). El laminado en la nube tiene límite de tiempo.
- El perfil tiene que ser el `.3mf` de proyecto de Bambu Studio (no el "generic 3mf" exportado ni `.gcode.3mf`;
  según el foro, tampoco el de OrcaSlicer).
- Perfil de impresión: al menos una foto real de la pieza impresa con ese perfil; sin ella se retira.
- Desde el 05/02/2026 MakerWorld pide foto real en la portada (o un render fiel); si falta, puede limitar la
  visibilidad del modelo.
- Foro: si se publica en privado y luego se hace público, cuenta la fecha de la primera publicación → por eso la app
  deja **borrador** (y privado marcado para cuando se publique).
- Foro (sin confirmar): un `.3mf` guardado con la A1 seleccionada salía incompatible con P1S/X1; con P1S, no.
- Normas del nombre del perfil: nada de "fast", "quick", "best", "perfect", "100%"… ni marcas.

## Pendiente

- [ ] Compilar en el PC (`gradlew build`) y arreglar lo que salga.
- [ ] Instalar la extensión y subir la carpeta de prueba `prueba-makerworld` con la app abierta.
- [ ] Revisar las equivalencias de categorías (`CatalogoMakerWorld.EQUIVALENCIAS`, propuesta del 10/11/2026).
- [ ] Perfil de impresión (más adelante, opcional): solo `.3mf` de Bambu Studio y con foto real.

## Si la web cambia

`pagina.js` se puede probar a mano: con el formulario abierto, desde la consola de Chrome,
`window.__jujamakerRellenar(ficha, [{tipo: 'modelo'|'portada'|'foto', file}])`.

## Pruebas

Siempre con `--revisar` y con un modelo de prueba, hasta que el formulario salga bien varias veces seguidas.
