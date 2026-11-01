# JujaMaker

App de escritorio para subir y descargar modelos 3D de [MakerOnline](https://www.makeronline.com) sin terminal, sin Maven y sin IntelliJ.

> **App no oficial.** JujaMaker no tiene relación con Anycubic ni con MakerOnline. Antes se llamaba MakerOnline Desktop.

Respeta las licencias de los modelos que descargues. Está basada en la herramienta de terminal [makerOnlineUploader](https://github.com/jesuscarrascofdzdev/makerOnlineUploader).

- Funciona entera **sin IA**. La IA es opcional y cada persona usa su propia clave o una IA local (Ollama).
- La sesión de MakerOnline (perfil de Chrome) **nunca sale de tu ordenador**.
- Los captchas **no se saltan**: se enseña la ventana para que los resuelva la persona.

> **Estado: fase 4 en curso (4a: la app guarda sus datos en su propia carpeta).** Fases 1, 2 y 3 terminadas. La app tiene menú y las pantallas Sesión, Subir (agregar, editar, eliminar y subir modelos, con su descripción) y Descargar (con la lista de usuarios y un zip en el escritorio al terminar). Al descargar toda la lista, la cuenta del creador de la app (juji-nya) va siempre la primera: viene marcada y se puede desmarcar, pero no quitar.

---

## Descargar

1. Entra en [Releases](https://github.com/jesuscarrascofdzdev/jujamaker/releases) y baja el último `JujaMaker-<versión>.exe`.
2. Ejecútalo. Necesitas Windows 10 u 11 y Google Chrome; Java ya va dentro. Se instala sin permisos de administrador, solo para tu usuario.
3. Como no está firmado, Windows dirá "Windows protegió tu PC": *Más información → Ejecutar de todas formas*.
4. Para actualizar, ejecuta el instalador nuevo: sustituye al anterior y tus datos (sesión, lista de usuarios, subidas) se conservan.

## Publicar una versión

1. Sube `versionApp` en `desktop/build.gradle.kts` (por ejemplo `1.2.0` → `1.2.1`) y haz commit.
2. Crea el instalador: `gradlew :desktop:crearInstalador`. Queda en el escritorio como `JujaMaker-<versión>.exe`.
3. Prueba el `.exe` instalándolo (o con *Probar la app empaquetada*).
4. Pon una etiqueta y súbela:
   ```
   git tag v1.2.1
   git push origin main --tags
   ```
5. En GitHub: *Releases → Draft a new release*, elige la etiqueta `v1.2.1`, escribe qué cambia, adjunta `JujaMaker-1.2.1.exe` y pulsa *Publish release*.

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
| App de escritorio | Abre la app: pantallas Sesión, Subir (agregar, editar o eliminar modelos, escribir o pegar su descripción, arrastrar carpetas, subir, revisar con botones y parar) y Descargar (marcar o desmarcar cada usuario, con "Marcar todos" y "Desmarcar todos"; se recuerda; simular, descargar, parar con un botón y editar la lista de usuarios; al terminar deja lo descargado en un zip en el escritorio, `makeronline-31-10-2026.zip`, o `… (2).zip` si ya hay uno de ese día). Guarda sus datos en su propia carpeta (ver abajo), no en la del proyecto |
| Probar la app empaquetada | Abre la app tal como quedará instalada (con su propio Java dentro), sin instalarla |
| Crear instalador | Crea el instalador de Windows (ver "Crear el instalador") |

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
  En la pantalla Subir, el botón **Descripción** de cada carpeta abre un formulario para escribir estos textos (se puede guardar a medias), y el botón **Pegar descripción** reparte en sus campos el texto que te haya dado una IA (ChatGPT, Gemini, Claude…) y lo abre en Descripción para revisarlo antes de guardar. Si los traes ya escritos, déjalos en la carpeta del modelo en `.txt` o `.json`. Lo que se quede entre corchetes `[ ]` cuenta como sin rellenar, y las líneas del `.txt` que empiezan por `#` no cuentan.

De momento se suben la portada y un modelo por zona; si hay más imágenes o modelos, la app y la terminal avisan de lo que se queda sin subir.

**Para parar Descargar o Subir sin dejar nada a medias**, escribe `parar` en la consola y pulsa ENTER. El Downloader termina el modelo que esté bajando y no empieza otro; el Uploader no guarda el borrador que tenga a medias, que se queda en `pendientes/`. El botón *Stop* de IntelliJ, en cambio, lo corta en seco.

**Dónde se guardan los datos**
- **La app** usa su propia carpeta: `%LOCALAPPDATA%\MakerOnline Desktop` (por ejemplo `C:\Users\<tú>\AppData\Local\MakerOnline Desktop`). Ahí están `chrome-profile/` (la sesión), `usuarios.json` (y `usuarios.json.bak`, la copia que guarda antes de cambiarlo por primera vez), `descargados.txt`, `ajustes.properties`, `subidas/`, `descarga-en-curso/` (se vacía al crear el zip) y `errores-descargas/`. La ruta se ve abajo del todo en la pantalla Sesión, con un botón para abrirla.
- **Traer los datos de antes:** si la app se abre desde IntelliJ y su carpeta todavía está vacía, la pantalla Sesión ofrece traer lo que hay en la carpeta del proyecto (sesión de Chrome, lista de usuarios, `descargados.txt`, ajustes y carpetas de subidas). Solo copia: en la carpeta del proyecto no se borra nada. Con "Traer de otra carpeta…" se puede traer de cualquier otra.
- **Los programas de terminal** siguen usando la carpeta desde la que se lanzan (la raíz del repo) y descargan en `modelos-descargados/`. Ya no comparten la sesión ni las listas con la app.
- Para que la app use otra carpeta: `-Dmakeronline.datos=<carpeta>` (ver `desktop/build.gradle.kts`).
- **Nada de eso se sube a git.**

**Crear el instalador**
1. En el desplegable de ejecución, elige **Crear instalador** y pulsa ▶. La primera vez tarda: descarga WiX (lo que hace instaladores de Windows).
2. Al terminar, el instalador está en **tu escritorio** como `JujaMaker-<versión>.exe` (sin espacios, para mandarlo o subirlo a GitHub Releases). El original se queda en `desktop/build/compose/binaries/main/exe/` y se rehace cada vez. El `.msi` se hace con *Gradle → desktop → compose desktop → packageMsi* y queda en `…/main/msi/`.
3. Se instala sin pedir permisos de administrador, solo para ese usuario, en `%LOCALAPPDATA%\JujaMaker` (antes de cambiar de nombre, en `%LOCALAPPDATA%\MakerOnline Desktop App`; hasta la 1.0.2, por un fallo, en `%LOCALAPPDATA%\ProgramsMakerOnline Desktop`; la versión nueva sustituye a la antigua), con acceso directo en el escritorio y en el menú Inicio. Lleva Java dentro: quien lo instala solo necesita Google Chrome.
   - Los datos van aparte, en `%LOCALAPPDATA%\MakerOnline Desktop` (se deja el nombre antiguo para no perder la sesión ni las listas): al desinstalar o actualizar, Windows borra entera la carpeta de instalación, pero no la de datos. Por eso la app nunca guarda datos dentro de la carpeta donde está instalada (`CarpetaDatos.elegida`).
4. Para una versión nueva, sube `versionApp` en `desktop/build.gradle.kts` (1.0.1, 1.1.0…): el instalador nuevo sustituye al anterior y los datos (la carpeta de datos) se quedan.
5. **Para que alguien la pruebe:** mándale ese `.exe` (por WhatsApp como documento, Drive…). Necesita Windows 10 u 11 y Google Chrome. Como no está firmado, Windows dirá "Windows protegió tu PC": *Más información → Ejecutar de todas formas*.

**Conector para Claude (Claude Desktop o Claude Code)**

La app instalada se puede usar desde tu Claude, con tu suscripción: Claude la arranca en "modo conector" (`JujaMaker.exe --mcp`, sin ventanas) y le pide acciones. De momento: ver las carpetas de pendientes, ver la foto de una, ver y poner sus textos y consultar las categorías válidas. Por ejemplo: *"mira la foto de flower y escríbele la descripción en inglés"*.
- **Claude Desktop:** cierra Claude, abre la app y en la pantalla **Sesión** pulsa **Conectar con Claude**. Después abre Claude otra vez: el conector aparece como `jujamaker`. Si venías de la versión anterior, pulsa **Volver a conectar**: cambia el conector antiguo (`makeronline`) por el nuevo. La app solo añade (o quita, con **Quitar**) su entrada en `mcpServers` de `claude_desktop_config.json`; el resto del archivo no lo toca y antes guarda una copia al lado (`claude_desktop_config.json.bak-makeronline`). Hace falta la app instalada: desde IntelliJ no se puede.
- **Claude Code:** el botón **Copiar el comando para Claude Code** copia esto, que se pega en una terminal:
  `claude mcp add jujamaker -- "C:\Users\<tú>\AppData\Local\JujaMaker\JujaMaker.exe" --mcp`
- **A mano:** en Claude Desktop, *Configuración → Desarrollador → Editar configuración*, y dentro de `mcpServers` añade:
  ```json
  "jujamaker": {
    "command": "C:\\Users\\<tú>\\AppData\\Local\\JujaMaker\\JujaMaker.exe",
    "args": ["--mcp"]
  }
  ```

**Desde la terminal (opcional)**
- Compilar todo: `gradlew build`
- Abrir la app: `gradlew :desktop:run`
- Crear el instalador: `gradlew :desktop:crearInstalador` (hace el `.exe` y deja una copia en el escritorio)

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
  - `CarpetaDatos`: la carpeta de datos de la app (`%LOCALAPPDATA%\MakerOnline Desktop`, o la de `-Dmakeronline.datos`). La tarea `run` pasa la raíz del repo en `makeronline.datosAnteriores` para que Sesión ofrezca traer de ahí los datos (`TraerDatos`, estado en `EstadoDatos`): copia sin machacar, junta `usuarios.json` y `descargados.txt`, y copia el perfil de Chrome sin cachés a una carpeta aparte que renombra al final. Coge el candado de las dos carpetas.
  - Instalador: `compose.desktop.application.nativeDistributions` en `desktop/build.gradle.kts` (exe y msi, por usuario, con todo el Java dentro y `upgradeUuid` fijo). `javaHome` es el JDK 21 del toolchain, que trae jpackage. El icono está en `desktop/icono.ico` (instalador) y `desktop/src/main/resources/icono.png` (ventana).
  - `core/Browsers.playwright()` arranca Playwright con `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`: se usa el Chrome instalado y así no se descargan los navegadores de Playwright (unos 400 MB) la primera vez.
  - `core/Sesion` comprueba la sesión (cookie `mo_access_token` + `api/user/personal/info`) y abre Chrome para iniciarla.
  - `core/ListaUsuarios` lee y guarda `usuarios.json` (lo usan el Downloader y la app) y saca un nombre legible de cada entrada (`"Nombre · URL-del-perfil"`, `"@usuario / Nombre"`…). Al guardar reescribe el archivo entero; antes, si no existe, copia el original en `usuarios.json.bak`.
  - `core/BuscadorUsuarios` encuentra a un usuario por la URL de su perfil (`window.__NUXT__.data[0].userInfo`) o por el buscador (`api/search/user`). Lo usan el Downloader y `Sesion.buscarUsuario`, que es lo que llama la app al añadir a alguien; la app lo guarda como `"Nombre · URL-del-perfil"`. `core/Api` hace las peticiones a la API con el token de la sesión.
  - `EstadoEdicion` + `DialogoUsuarios`: la ventana de Editar (añadir comprobando en MakerOnline, quitar con confirmación).
  - `EstadoSubida` + `PantallaSubir`: lista `subidas/pendientes/` comprobando cada carpeta con `ModelFolder.load` (sin moverla) y solo pasa al Uploader las que están bien. Las carpetas arrastradas (o elegidas con "Añadir carpetas…") se copian a pendientes con `CopiaCarpetas`. `RevisionApp` es la `Revision` de la app: el Uploader espera en el hilo del navegador a que se pulse Guardar borrador o Descartar. `Uploader.Resumen.carpetasConError` dice dónde han quedado en `errores/`.
  - `core/ModelFolder` comprueba una carpeta de modelo (nombres libres, ver arriba) y `core/TextosModelo` lee y escribe el `.txt` de textos.
  - Ventana Agregar / Editar modelo: `EdicionModelo` (estado: imagen, modelo y descripción; guarda a medias y pide el nombre de la carpeta la primera vez; la imagen se copia como `portada.<ext>`) + `VentanaModelo` (interfaz).
  - Ventana Descripción: `FormularioTextos` (estado) + `VentanaDescripcion` (interfaz). `core/TextosCarpeta` lee y guarda los textos de una carpeta sin exigir que esté completa. Las opciones de los desplegables salen de `core/CatalogoMakerOnline`, que solo tiene lo comprobado en la web (se completa con el Diagnóstico del formulario).
  - Pegar descripción: `EdicionPegar` + `VentanaPegar` y `EstadoSubida.rellenarConPegado`, que abre Descripción con lo pegado (lo que no traiga, como la licencia o el tipo, se queda como lo tenía la carpeta). Lo lee `core/InstruccionesIA.leerRespuesta`, que limpia lo que suelen añadir las IAs (frases antes, ```, negritas, #etiquetas, categorías escritas un poco distintas) antes de pasarlo a `TextosModelo`. `InstruccionesIA.crear` (instrucciones con el catálogo y los límites del formulario: título 100, etiquetas 20, descripción 9000) es para el asistente de IA.
  - Conector MCP: `Acciones` (la lista de lo que puede hacer una IA, con sus parámetros en JSON Schema; la usará también el chat de la app) y `ServidorMcp` (JSON-RPC 2.0 por la entrada y salida estándar, un mensaje por línea; por la salida estándar solo va el protocolo). `Main` arranca el conector con `--mcp`, sin ventanas. Fase 1a: `listar_pendientes`, `ver_textos`, `ver_foto` (JPEG de 1024 px como mucho), `poner_textos` (solo cambia lo que se pasa) y `opciones_formulario`.
  - Botón "Conectar con Claude" (pantalla Sesión, estado en `EstadoClaude`): `ConectorClaude` añade o quita solo la entrada `mcpServers.jujamaker` de `claude_desktop_config.json` (y quita la antigua, `makeronline`), con copia `.bak-makeronline` y escritura atómica. Si el JSON no se puede leer, no lo toca. En Windows la carpeta de Claude depende de cómo se instaló: `%LOCALAPPDATA%\Claude-Data\claude_*` (paquete MSIX, versiones nuevas: es la `--user-data-dir` de sus procesos), `%APPDATA%\Claude` (instalador clásico) o `%LOCALAPPDATA%\Packages\Claude_*\LocalCache\Roaming\Claude` (MSIX antiguo). Se usa la que tenga el `Local State` más reciente. El `.exe` sale de `jpackage.app-path` o de la instalación.
  - Ventana y aspecto: tema oscuro y letra IBM Plex Sans (licencia SIL OFL, en `desktop/src/main/resources/fuentes/` con su licencia). Todo lo visual común está en `Tema.kt`: colores (`Colores`), `TemaApp`, botones (`BotonPrincipal`, `BotonSecundario`, `BotonSuave`, `BotonParar`, `BotonEnlace`), `Tarjeta`, `Cabecera`, `Etiqueta`, `Barra` y `CampoPequeno`. Los iconos se dibujan en `Iconos.kt` (sin la librería de iconos de Material). Arriba, `BarraSuperior` en `App.kt`: logo a la izquierda, pestañas centradas con icono y, a la derecha, la sesión y la tarea en marcha. La ventana se abre maximizada.
  - `Papelera`: eliminar una carpeta de pendientes la manda a la papelera de Windows (`Desktop.moveToTrash`) o, si no se puede, a `subidas/eliminados/`.
  - `Comunes.kt`: piezas que usan varias pantallas (`ListaMensajes`, `Eleccion`, `Casilla`, `elegirCarpetas`).
  - `Creador`: la cuenta del creador, que la app pasa al Downloader en `Opciones.fijos` (va antes que `usuarios.json`, sin repetirse) cuando se descarga toda la lista y está marcada. No está en `usuarios.json` y no se puede quitar; la casilla se recuerda en `ajustes.properties` (`Ajustes`). La terminal no la incluye.
- **Reglas:** textos y comentarios en español. No inventar selectores ni endpoints de MakerOnline. Pausa de 10 a 30 s entre descargas y nunca en paralelo. No saltar captchas.
- **Compilar:** `gradlew build`. No hay tests todavía.
