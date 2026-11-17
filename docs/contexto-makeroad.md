# Contexto: Makeroad en JujaMaker

Rama: `makeroad` (sale de `printables`). Objetivo: que JujaMaker suba a **Makeroad**
(https://www.makeroad.com) las mismas carpetas que sube a MakerOnline, Creality y Printables.

## Conclusión (17/11/2026)

**Se sube con el Chrome de la app, como Creality. No hace falta extensión.**

- Makeroad **no tiene Cloudflare**: servidor nginx, sin cabeceras `cf-ray` ni página de verificación
  (comprobado en el Chrome de la persona con Claude in Chrome).
- `DiagnosticoMakeroad` lanzado en el PC de la persona: el Chrome de la app **llega al formulario de subida en 8 s**.
- Ojo: la web carga el **captcha y la huella de dispositivo de Aliyun** (`AliyunCaptcha.js`, FeiLin,
  `cloudauth-device…aliyuncs.com`). No ha salido al abrir el formulario; falta ver si sale **al guardar**.
  Si sale, no se salta: con `--revisar` lo resuelve la persona; sin `--revisar` el subidor para.
  Si saliera siempre, habría que pasar a una extensión, como Printables.

## Piezas

| Pieza | Qué hace |
|---|---|
| `core/.../DiagnosticoMakeroad.kt` (+ `.run`, tarea `:core:diagnosticoMakeroad`) | Mira si el Chrome de la app llega al formulario, si hay captcha y, si quieres, apunta una subida hecha a mano |
| `core/.../SubidorMakeroad.kt` (+ `.run` "Subir a Makeroad (revisar)", tarea `:core:subirMakeroad`) | Rellena el formulario y lo **publica como público** ("Publicar"). Makeroad responde "Enviado para revisión" |
| `core/.../CatalogoMakeroad.kt` | Categorías de la web y equivalencias desde las de MakerOnline; límites |
| `Rutas.deLaApp()` | Los programas de terminal de Makeroad usan la carpeta de datos de la app (`%LOCALAPPDATA%\MakerOnline Desktop`): sus carpetas y su sesión de Chrome |
| `ModelFolder` | Ignora `subido-makeroad.txt`, `resultado-makeroad.png`, `error-makeroad.*` |

## El formulario (visto el 17/11/2026, sin guardar nada)

`https://www.makeroad.com/spa/printable_3D_model/upload`. Hecho con Arco Design (Vue/Nuxt) y el editor wangEditor v5.

- **Tipo de carga**: primer `.arco-radio-group`; Modelo original = `value=1` (marcado por defecto), Segundo modelo = 2, Impresiones/perfiles = 3.
- **Archivos** (`input[type=file]`, por su `accept`):
  - zona general "Subir archivos" (`accept="*"`, varios): no se usa;
  - **Archivo de modelo** (`accept` con `.stl, .3mf, .obj…`, uno cada vez). Obligatorio. Hasta 80;
  - **Archivo de configuración de impresión** (`accept=".3mf"`);
  - **Fotos** (`accept=".jpg,.jpeg,.png, .gif, .bmp, .webp"`, varias): **de 3 a 10**, 1:1 recomendado. Obligatorio;
  - Texto de instrucciones (pdf, txt…): no se usa.
- **Título**: el único `input.arco-input` visible, máximo 60.
- **Descripción**: `[contenteditable=true]` (wangEditor). Obligatoria.
- **Categoría** (obligatoria): `.tag-input-box` abre `.selection-modal` ("Clasificación de modelos") con
  `.brand-group` > `.brand-title` (grupo; `.on` = desplegado) y `.option-item` (opción); `.confirm-btn` = Enviar,
  `.cancel-btn` = Cancelar. Los nombres van en `CatalogoMakeroad`. **Todos los modelos van a Hogar & Vida > Decoración**
  (decisión de la persona, 17/11/2026).
- **Etiquetas**: `input.arco-select-view-input` con placeholder de etiqueta (el placeholder desaparece tras la primera, así que
  se localiza por posición). Hay que **elegirlas de su desplegable** (`.arco-select-dropdown .arco-select-option`); si no sale, Enter.
  Se cuentan las `.arco-tag`.
- **Método de impresión** (obligatorio): `span.method-text` FDM / LCD / Others (dentro de `div.form-group`).
- **Información del modelo**: casillas "Este es mi modelo original", "segundo modelo", "creado por IA", "NSFW" (`span.work-type-text`): no se tocan.
- **Licencia**: tres grupos (compartir sin atribución: Sí=2/No=1; modificar y compartir: Sí=2/No=1/igual licencia=3;
  uso comercial: Sí=2/No=1). Por defecto No/No/No ("CC BY-ND"). Se pone **Sí a las tres** (lo más parecido a CC0, como en Creality).
- **Visibilidad**: **público=1** / Privado=2. Precio: Gratis. Hay "Lanzamiento programado".
- **Términos**: "Entiendo y acepto subir mi trabajo a MakeRoad…". No es un checkbox de Arco: se pulsa el cuadradito a la
  izquierda del texto (12–24 px) o, si no se encuentra, el principio del texto. Obligatoria para publicar
  (`toasts.agreementRequired`: "Por favor, acepte primero los términos de la plataforma.").
- **Botones** (`div.layout-btn`, por orden): **"Registrar"** (en el código de la web `buttons.save`; en inglés "Save":
  guarda en borradores), "Avance" (vista previa) y "Publicar" (desactivado hasta que todo está bien).
- Aviso al guardar: `toasts.saveSuccess` ("Guardado exitosamente" / "Saved Successfully").
- Aviso al publicar: `toasts.submitSuccess` (**"Enviado para revisión"** / "Submitted for Review"): Makeroad lo modera antes de enseñarlo.

## Decisión: publicar directamente (17/11/2026)

A petición de la persona, a Makeroad se **publica como público** (botón "Publicar"), no se guarda como borrador:
es la excepción a la regla del proyecto. Makeroad no deja publicar con menos de 3 fotos: si la carpeta tiene menos, se repiten
(la portada primero) hasta llegar a 3, también a petición de la persona y la casilla de términos es obligatoria. Con `--revisar` se para antes de publicar cada uno.
- API (de su código, sin usar): `/models/info`, `/models/work`, `/models/getEdit`, `/upload/ossurl`… La sesión va con
  una cabecera de token (`/api/user` sin ella responde `code 50001`).

## Sesión (17/11/2026)

La sesión de Makeroad es la cookie **`X-Token`** de www.makeroad.com; la web la manda en la cabecera `X-Token` a
`/api/user`, que responde `code 0` con la cuenta (`nickname`, `username`…); sin ella, `code 50001`. Comprobado en el
Chrome de la persona. La app la comprueba igual (`Sesion.comprobarMakeroad`) y en la pantalla Sesión hay una fila
**Makeroad** con "Iniciar sesión" / "Ya he entrado", como MakerOnline y Creality: se entra una vez y se queda en el
perfil de Chrome de la app.

## Pendiente

- [x] Sesión guardada en la app (pantalla Sesión → Makeroad).
- [x] Integrado en la app: no en el enum `Web` (Makeroad no descarga) sino en `Destino` (ver abajo).
- [ ] Primera subida real completa: ver si sale el captcha al publicar, si "Publicar" se activa con todo relleno
      y si el aviso "Enviado para revisión" coincide. Categoría, términos y etiquetas ya se han corregido tras
      las primeras pruebas (17/11/2026), falta verlo terminar. Anotarlo aquí.
- [ ] La rama `nexprint` (Jesus) añade NexPrint: al juntarla, como quinta entrada de `Destino`.

## Subida unificada (17/11/2026)

Pedido por la persona: un solo botón Subir y una ficha por página (MakerOnline, Creality, Printables,
Makeroad) que se marca o desmarca (se recuerda en ajustes). Lo hace `SubidaMultiple` con el enum
`Destino` (va aparte de `Web`, que es sesión/descargas):

- Al pulsar Subir se apunta en cada carpeta `destinos.txt` (las páginas elegidas).
- Orden: Creality → Makeroad → MakerOnline (el Uploader con `mover=false`: deja
  `subido-makeronline.txt` o `error-makeronline.txt` y **no** mueve). Printables: las carpetas quedan
  en la cola de la extensión y la app abre su formulario al terminar.
- Una carpeta pasa a subidos/ solo cuando está en **todas** sus páginas (`Destino.retirarSiCompleta`):
  al final de la subida y cuando la extensión apunta Printables (`ColaPrintables.anotarSubido`).
- La cola de Printables solo coge de pendientes/ las carpetas con PRINTABLES en `destinos.txt`.
- El botón suelto "Publicar en Makeroad" ya no existe.
