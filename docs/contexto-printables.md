# Printables: contexto

> Regla: nada de la web de Printables se programa de memoria. Todo lo que use el subidor (campos, botones,
> categorías, licencias, límites) se comprueba en la página o en su código y se anota aquí **con la fecha**.

## Estado

- Rama `printables`, desde `main` (MakerWorld se queda aparte en su rama `makerworld`).
- MakerWorld se dejó el 10/11/2026: además de Cloudflare, pide fotos de impresiones reales.
- **Primer paso**: comprobar si Printables deja entrar al Chrome que maneja la app (`DiagnosticoPrintables`).
  - Si deja: subidor como el de Creality (`CatalogoPrintables`, `SubidorPrintables`, integración en la app).
  - Si no deja: como MakerWorld, con una extensión en el Chrome de siempre que rellena el formulario con la
    cola de la app.

| Pieza | Estado |
|---|---|
| `core/.../DiagnosticoPrintables.kt` | Hecho: mira si pasa Cloudflare; si pasa, apunta el formulario y las peticiones mientras subes uno a mano |
| `.run/diagnostico-printables.run.xml` | Hecho |
| Catálogo, subidor, integración | Pendiente del resultado del diagnóstico |

## Comprobado (10/11/2026)

- **Cloudflare**: `https://www.printables.com/model/create` en el navegador de Claude (el navegador integrado
  de la app de Claude, no automatizado con Playwright) se queda en "Verificación de seguridad en curso"
  (título "Un momento…", HTTP 403) y no pasa ni esperando. Es más dura que la de MakerWorld, que a ese
  navegador sí lo dejaba pasar. Por eso lo más probable es que tampoco deje pasar al Chrome de la app.
  La verificación no se salta: si sale la casilla, la marca la persona.
- **Condiciones de Printables** (válidas desde el 15/10/2026,
  https://www.prusa3d.com/page/printables-com-terms-of-service_231249/): no tienen ninguna cláusula sobre
  acceso automático, bots o scripts. Sí dicen (cl. 3.2) que las subidas duplicadas o repetidas de modelos o
  fotos van contra las normas: **no subir dos veces el mismo modelo a Printables** (el subidor tiene que
  apuntar lo subido, como `subido-creality.txt`).
- Formatos que nombran las condiciones: archivos de impresión (.gcode, .bgcode, .sl1, .sl1s), modelos
  (.stl, .3mf, .obj, .step, .scad, .amf…) e imágenes.

## Pendiente

- [ ] Resultado de `DiagnosticoPrintables` en el PC (Windows + Chrome).
- [ ] Con el formulario real: campos, categorías y licencias con fecha.

## Pruebas

- Carpeta de prueba en el PC: `C:\Users\alber\source\prueba-makerworld\SKYLINE_GTR34` (.3mf, .jpg e info.json).
