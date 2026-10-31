# MakerOnline Desktop

App de escritorio para subir y descargar modelos 3D de [MakerOnline](https://www.makeronline.com) sin terminal, sin Maven y sin IntelliJ. Está basada en la herramienta de terminal [makerOnlineUploader](https://github.com/jesuscarrascofdzdev/makerOnlineUploader).

- Funciona entera **sin IA**. La IA es opcional y cada persona usa su propia clave o una IA local (Ollama).
- La sesión de MakerOnline (perfil de Chrome) **nunca sale de tu ordenador**.
- Los captchas **no se saltan**: se enseña la ventana para que los resuelva la persona.

> **Estado: fase 3 en curso (3a: pantalla Subir con carpetas).** Fases 1 y 2 terminadas. El código de la herramienta de terminal está en el módulo `core` y se está preparando para usarlo desde la app (hechos: 1a, las rutas están en `Rutas`; 1b, `core` avisa de lo que hace con `Progreso`, y el Uploader espera a que terminen las subidas y confirma el borrador; 1c, se puede parar sin dejar nada a medias; 1d, `--revisar` sin Inspector). La app ya tiene menú y las pantallas Sesión, Subir (arrastrando carpetas de modelos) y Descargar, esta con un botón Editar para añadir y quitar usuarios de la lista. Al descargar toda la lista, la cuenta del creador de la app (juji-nya) va siempre la primera: viene marcada y se puede desmarcar, pero no quitar.

---

## Estructura

```
makeronline-desktop/
├── core/       Lógica de MakerOnline: sesión, subir, descargar, likes, registro, candado (Java)
├── desktop/    App de escritorio con Compose for Desktop (Kotlin)
├── gradle/     Versiones de las librerías (libs.versions.toml) y el wrapper de Gradle
└── .run/       Configuraciones de ejecución ya preparadas para IntelliJ
```

## Para desarrollar

**Qué necesitas**
- IntelliJ IDEA.
- JDK 21. Si no lo tienes, Gradle lo descarga solo.
- Google Chrome instalado.

**Abrir el proyecto**
1. En IntelliJ: *File → Open* y elige esta carpeta.
2. Espera a que Gradle termine de sincronizar. La primera vez tarda porque descarga las librerías.

**Ejecutar**

Arriba a la derecha, en el desplegable de ejecución, está la carpeta **MakerOnline**:

| Configuración | Qué hace |
|---|---|
| Guardar sesión | Abre Chrome para iniciar sesión en MakerOnline. Solo la primera vez o cuando caduque |
| Descargar (simular) | Lista lo que descargaría, sin descargar nada |
| Descargar | Descarga los modelos nuevos de los usuarios de `usuarios.json` |
| Subir (revisar) | Sube los modelos de `subidas/pendientes/` y, antes de guardar cada borrador, te deja mirarlo en Chrome: ENTER lo guarda; `no` + ENTER lo descarta (se queda en `pendientes/`) |
| Subir | Sube los modelos de `subidas/pendientes/` como borrador |
| Codegen | Chrome con sesión y el Playwright Inspector, para grabar selectores |
| Diagnóstico del formulario | Abre el formulario de subida y apunta en `diagnostico-formulario.txt` las licencias, categorías y tipos de impresión que ofrece la web, y de qué peticiones salen. No sube ni guarda nada |
| App de escritorio | Abre la app: pantallas Sesión, Subir (agregar, editar o eliminar modelos, escribir su descripción, arrastrar carpetas, subir, revisar con botones y parar) y Descargar (simular, descargar, parar con un botón y editar la lista de usuarios; al terminar deja lo descargado en un zip en el escritorio, `makeronline-31-10-2026.zip`, o `… (2).zip` si ya hay uno de ese día). Usa la misma carpeta de datos que los programas de terminal (la raíz del repo), así que comparte la sesión, `usuarios.json` y `descargados.txt` |

**Cómo preparar un modelo para subir:** una carpeta por modelo dentro de `subidas/pendientes/`. Los nombres de los archivos dan igual:
- al menos un modelo, `.3mf` o `.stl`;
- al menos una imagen, `.jpg` o `.png`. La portada es la que se llama `portada`, `cover` o como la carpeta; si no hay ninguna así, la primera por orden alfabético;
- los textos, en un `.json` con el formato de `info.json` o, si no hay `.json`, en un `.txt` como este (también vale un `.txt` por apartados como el que da una IA: "Model Title", "20 Tags", "Detailed Description"…):
  ```
  Título: Llavero gato
  Categoría: Toys&Games > Action Figures   (opcional)
  Etiquetas: gato, llavero, regalo
  Licencia: CC0                    (opcional)
  Tipo: FDM                        (opcional)
  Descripción:
  Todo lo que venga después, con sus saltos de línea.
  ```
  Si no hay categoría, licencia o tipo, se usan Toys&Games > Action Figures, CC0 y FDM (se pueden cambiar luego en el borrador).
  En la pantalla Subir, el botón **Descripción** de cada carpeta abre un formulario para escribir estos textos (se puede guardar a medias). Si los traes ya escritos, déjalos en la carpeta del modelo en `.txt` o `.json`. Lo que se quede entre corchetes `[ ]` cuenta como sin rellenar, y las líneas del `.txt` que empiezan por `#` no cuentan.

De momento se suben la portada y un modelo por zona; si hay más imágenes o modelos, la app y la terminal avisan de lo que se queda sin subir.

**Para parar Descargar o Subir sin dejar nada a medias**, escribe `parar` en la consola y pulsa ENTER. El Downloader termina el modelo que esté bajando y no empieza otro; el Uploader no guarda el borrador que tenga a medias, que se queda en `pendientes/`. El botón *Stop* de IntelliJ, en cambio, lo corta en seco.

Los programas de terminal se ejecutan desde la raíz del repo. Ahí se crean `chrome-profile/`, `usuarios.json` (y `usuarios.json.bak`, la copia que guarda la app antes de cambiarlo por primera vez), `descargados.txt`, `ajustes.properties` (lo que recuerda la app), `subidas/` y `modelos-descargados/` (donde descarga la terminal). La app descarga en `descarga-en-curso/`, que vacía al crear el zip, y guarda los errores de las descargas en `errores-descargas/`. **Nada de eso se sube a git.**

**Desde la terminal (opcional)**
- Compilar todo: `gradlew build`
- Abrir la app: `gradlew :desktop:run`

---

## Para asistentes de IA

- **Stack:** Gradle 8.14.3 (Kotlin DSL) con varios módulos. Versiones en `gradle/libs.versions.toml`: Kotlin 2.2.20, Compose Multiplatform 1.9.3, Playwright para Java 1.52.0 y Jackson 2.18.2. JDK 21 (toolchain).
- **`core`:** paquete `dev.jefe.makeronline`. El código heredado está en Java (`src/main/java`) y las piezas nuevas en Kotlin (`src/main/kotlin`). Programas con `main`: `SaveSession`, `Uploader`, `Downloader` y `Codegen`. Todas las rutas de datos salen de `Rutas`. Los programas de terminal usan `Rutas.carpetaActual()`, es decir, el directorio de trabajo, que debe ser la raíz del repo.
- **Cómo se usa `core`:** `new Downloader(rutas, progreso, cancelacion).ejecutar(opciones)` y `new Uploader(rutas, progreso, cancelacion, revision).ejecutar(opciones)` devuelven un `Resumen`.
  - `Cancelacion.pedir()` pide parar desde cualquier hilo. `core` lo comprueba solo en puntos seguros (entre modelos, en pausas, esperando subidas o captchas) y entonces lanza `CanceladoException`, que no es un error.
  - `Revision` decide si se guarda cada borrador con `--revisar`. En la terminal es `RevisionConsola`.
  - La lógica no imprime: avisa con la interfaz `Progreso`. En la terminal se usa `ProgresoConsola`.
  - Lo que impide seguir (sin sesión, candado ocupado, web caída) se lanza como `AgenteException`.
  - Solo los `main` imprimen directamente (ayuda y argumentos) y llaman a `System.exit`.
- **Subidas:** `VigilanteSubidas` sabe cuándo han terminado de verdad, porque vigila las peticiones `api/file/upload` y `api/file/parse-info` y las filas `.file-item` que muestran "Cancel".
  - Si una subida falla o lleva 2 minutos sin avanzar, no se guarda el borrador.
  - El guardado se confirma con la respuesta de `api/mold/save-draft`, o con el salto a `/en/user/uploads/draft.html`.
  - Lo comprobado en la web está en el documento de contexto del proyecto.
- **`desktop`:** Kotlin + Compose for Desktop, paquete `dev.jefe.makeronline.desktop`. Todos los textos de la interfaz están en `Textos.kt`.
  - Todo lo que toca Chrome va en `HiloNavegador`, un único hilo, porque Playwright no admite varios.
  - Cada pantalla tiene su estado (`EstadoSesion`, `EstadoDescarga`) con funciones `suspend`: el trabajo va al hilo del navegador y el estado se cambia al volver, en el hilo de la ventana. Se lanzan con el `CoroutineScope` de `App`, no con el de cada pantalla, para que sigan al cambiar de pantalla.
  - `Ocupacion` dice qué está haciendo Chrome; la comparten todas las pantallas y solo deja empezar una tarea a la vez.
  - `ProgresoApp` convierte los avisos de `Progreso` en `Evento`s y los pasa al hilo de la ventana, en orden.
  - La carpeta de datos llega por la propiedad `makeronline.datos`, que pone la tarea `run` de Gradle; en la fase 4 será la carpeta del usuario.
  - `core/Sesion` comprueba la sesión (cookie `mo_access_token` + `api/user/personal/info`) y abre Chrome para iniciarla.
  - `core/ListaUsuarios` lee y guarda `usuarios.json` (lo usan el Downloader y la app) y saca un nombre legible de cada entrada (`"Nombre · URL-del-perfil"`, `"@usuario / Nombre"`…). Al guardar reescribe el archivo entero; antes, si no existe, copia el original en `usuarios.json.bak`.
  - `core/BuscadorUsuarios` encuentra a un usuario por la URL de su perfil (`window.__NUXT__.data[0].userInfo`) o por el buscador (`api/search/user`). Lo usan el Downloader y `Sesion.buscarUsuario`, que es lo que llama la app al añadir a alguien; la app lo guarda como `"Nombre · URL-del-perfil"`. `core/Api` hace las peticiones a la API con el token de la sesión.
  - `EstadoEdicion` + `DialogoUsuarios`: la ventana de Editar (añadir comprobando en MakerOnline, quitar con confirmación).
  - `EstadoSubida` + `PantallaSubir`: lista `subidas/pendientes/` comprobando cada carpeta con `ModelFolder.load` (sin moverla) y solo pasa al Uploader las que están bien. Las carpetas arrastradas (o elegidas con "Añadir carpetas…") se copian a pendientes con `CopiaCarpetas`. `RevisionApp` es la `Revision` de la app: el Uploader espera en el hilo del navegador a que se pulse Guardar borrador o Descartar. `Uploader.Resumen.carpetasConError` dice dónde han quedado en `errores/`.
  - `core/ModelFolder` comprueba una carpeta de modelo (nombres libres, ver arriba) y `core/TextosModelo` lee y escribe el `.txt` de textos.
  - Ventana Agregar / Editar modelo: `EdicionModelo` (estado: imagen, modelo y descripción; guarda a medias y pide el nombre de la carpeta la primera vez; la imagen se copia como `portada.<ext>`) + `VentanaModelo` (interfaz).
  - Ventana Descripción: `FormularioTextos` (estado) + `VentanaDescripcion` (interfaz). `core/TextosCarpeta` lee y guarda los textos de una carpeta sin exigir que esté completa. Las opciones de los desplegables salen de `core/CatalogoMakerOnline`, que solo tiene lo comprobado en la web (se completa con el Diagnóstico del formulario).
  - `Papelera`: eliminar una carpeta de pendientes la manda a la papelera de Windows (`Desktop.moveToTrash`) o, si no se puede, a `subidas/eliminados/`.
  - `Comunes.kt`: piezas que usan varias pantallas (`ListaMensajes`, `Eleccion`, `Casilla`, `elegirCarpetas`).
  - `Creador`: la cuenta del creador, que la app pasa al Downloader en `Opciones.fijos` (va antes que `usuarios.json`, sin repetirse) cuando se descarga toda la lista y está marcada. No está en `usuarios.json` y no se puede quitar; la casilla se recuerda en `ajustes.properties` (`Ajustes`). La terminal no la incluye.
- **Reglas:** textos y comentarios en español. No inventar selectores ni endpoints de MakerOnline. Pausa de 10 a 30 s entre descargas y nunca en paralelo. No saltar captchas.
- **Compilar:** `gradlew build`. No hay tests todavía.
