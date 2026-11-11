package dev.jefe.makeronline

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Response
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.WaitForSelectorState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.regex.Pattern
import kotlin.system.exitProcess

/**
 * Sube a Nexprint (la web de modelos de ELEGOO) las carpetas de subidas/pendientes/, con los mismos
 * archivos y textos que se usan para MakerOnline. Todo lo que usa de la web está en el documento de
 * contexto de Nexprint (claude/05-contexto-nexprint.md), comprobado el 11/11/2026 rellenando el
 * formulario a mano y guardando un borrador.
 *
 * Cada modelo se guarda como **borrador** ("Save as draft"), como en MakerOnline: lo revisas en
 * Nexprint y lo publicas tú.
 *
 *  - Se suben todos los .3mf y .stl de la carpeta como un solo modelo ("Single model"). Si solo hay
 *    uno, se sube con el título del modelo como nombre de archivo, porque Nexprint lo enseña.
 *  - Portada: la de la carpeta. Nombre, descripción y etiquetas: los de MakerOnline.
 *  - Lo fijo, decidido por la persona (11/11/2026): categoría Toys & Games > Others, modelo original,
 *    sin evento ("Not Interested"), sin piezas no impresas y la licencia más permisiva (Sí a las tres
 *    preguntas: "Creative Commons Public Domain", lo que en las otras webs es CC0).
 *  - Antes de guardar se comprueba el formulario; si algo no está como debe, no se guarda.
 *
 * **No mueve las carpetas** (como Creality): apunta lo subido en `subido-nexprint.txt` dentro de la
 * carpeta, y las que lo tienen no se vuelven a subir. Si algo falla, deja en la carpeta
 * `error-nexprint.txt` (en qué paso y por qué) y `error-nexprint.png` (captura de la página).
 *
 * Programa de terminal (configuración "Subir a Nexprint (revisar)"):
 *   --revisar   antes de guardar cada modelo, se para para que mires el formulario:
 *               ENTER lo guarda; "no" + ENTER no lo guarda.
 *   --esperar   si otro programa del agente está en marcha, espera a que termine.
 *   --subidos   de subidos/ (las ya subidas a MakerOnline) en vez de pendientes/.
 *   nombre ...  solo esas carpetas (sin nombres, todas).
 * Para pararlo sin dejar nada a medias: "parar" + ENTER.
 */
class SubidorNexprint(
    private val rutas: Rutas,
    private val progreso: Progreso,
    private val cancelacion: Cancelacion,
    private val revision: Revision,
) {

    /**
     * @property revisar      se para antes de guardar cada modelo para que lo mires
     * @property carpetas     solo estas carpetas; vacía = todas
     * @property esperar      si otro programa del agente está en marcha, espera a que termine
     * @property verChrome    true = Chrome a la vista; false = fuera de la pantalla (aparece para revisar)
     * @property desdeSubidos true = las carpetas se buscan en subidos/ en vez de en pendientes/
     */
    data class Opciones(
        val revisar: Boolean,
        val carpetas: List<String>,
        val esperar: Boolean,
        val verChrome: Boolean = true,
        val desdeSubidos: Boolean = false,
    )

    /**
     * @property subidos   guardados bien como borrador (cada uno con su subido-nexprint.txt)
     * @property yaSubidos saltados porque ya tenían subido-nexprint.txt
     * @property conError  fallaron (con error-nexprint.txt y error-nexprint.png en su carpeta)
     * @property sinSubir  no válidos, descartados al revisar o sin hacer porque se ha parado
     * @property parado    true si se ha parado a petición de la persona
     */
    data class Resumen(val subidos: Int, val yaSubidos: Int, val conError: Int, val sinSubir: Int, val parado: Boolean)

    /** La persona ha decidido no guardar este modelo al revisarlo. */
    private class Descartado : RuntimeException("descartado al revisar")

    /** No hay sesión de Nexprint en Chrome (o ha caducado): no se sigue con ningún modelo más. */
    private class SinSesion : RuntimeException("no hay sesión de Nexprint")

    /** Algo ha fallado en un paso concreto del formulario: el mensaje dice cuál. */
    class ErrorEnPaso(val paso: String, val motivo: String, causa: Throwable?) :
        RuntimeException("$paso: $motivo", causa)

    fun ejecutar(op: Opciones): Resumen {
        val origen = if (op.desdeSubidos) rutas.subidos else rutas.pendientes
        Files.createDirectories(origen)
        if (!Files.isDirectory(rutas.perfilChrome)) {
            throw AgenteException("No hay sesión guardada. Inicia sesión primero (app: pantalla Sesión).")
        }
        var carpetas = Files.list(origen).use { s -> s.filter { Files.isDirectory(it) }.sorted().toList() }
        if (op.carpetas.isNotEmpty()) {
            for (n in op.carpetas) if (!Files.isDirectory(origen.resolve(n))) progreso.error("No existe en ${origen.fileName}: $n")
            carpetas = carpetas.filter { it.fileName.toString() in op.carpetas }
        }
        if (carpetas.isEmpty()) {
            progreso.info("No hay nada en ${origen.toAbsolutePath()}")
            return Resumen(0, 0, 0, 0, false)
        }

        // 1) Validar antes de abrir el navegador
        val listos = ArrayList<ModelFolder>()
        var yaSubidos = 0
        var sinSubir = 0
        for (dir in carpetas) {
            if (yaSubido(dir)) {
                progreso.saltado("${dir.fileName}: ya está en Nexprint ($REGISTRO), no se vuelve a subir")
                yaSubidos++
                continue
            }
            try {
                listos += ModelFolder.load(dir)
                progreso.correcto("${dir.fileName} validado")
            } catch (e: Exception) {
                progreso.error("${dir.fileName}:\n   ${(e.message ?: e.toString()).replace("\n", "\n   ")}")
                sinSubir++
            }
        }
        if (listos.isEmpty()) {
            progreso.info("Nada que subir a Nexprint.")
            return Resumen(0, yaSubidos, 0, sinSubir, false)
        }

        // 2) Subir
        var ok = 0
        var ko = 0
        var parado = false
        if (!op.verChrome) {
            progreso.paso("Chrome se abre fuera de la pantalla" + (if (op.revisar) "; aparecerá para que revises cada modelo" else ""))
        }
        val modo = if (op.verChrome) Downloader.Modo.VISIBLE else Downloader.Modo.APARTADA
        Browsers.playwright().use { pw ->
            Browsers.openChrome(pw, rutas.perfilChrome, 150.0, false, !op.verChrome).use { ctx ->
                ctx.setDefaultTimeout(30_000.0)
                if (ctx.cookies(WEB).none { it.name == COOKIE_SESION && !it.value.isNullOrBlank() }) {
                    throw AgenteException(SIN_SESION_TEXTO + "No se ha subido nada a Nexprint.")
                }
                for ((i, m) in listos.withIndex()) {
                    if (cancelacion.pedida()) {
                        parado = true
                        sinSubir += listos.size - i
                        break
                    }
                    progreso.modelo(i + 1, listos.size, m.name())
                    val page = ctx.newPage()
                    val ventana = Downloader.Ventana(modo, ctx, page, progreso)
                    try {
                        val id = subir(page, m, op.revisar, ventana)
                        anotarSubido(m.dir(), id)
                        captura(page, m.dir().resolve(CAPTURA_OK))
                        borrarErroresAnteriores(m.dir())
                        progreso.correcto("Guardado en Nexprint como borrador: ${borrador(id)}")
                        ok++
                    } catch (_: CanceladoException) {
                        parado = true
                        sinSubir += listos.size - i
                        progreso.info("⏹ ${m.name()} no se ha guardado en Nexprint.")
                    } catch (_: Descartado) {
                        sinSubir++
                        progreso.saltado("No se guarda ${m.name()} en Nexprint.")
                    } catch (_: SinSesion) {
                        // No es culpa de la carpeta: no se apunta error en ella y se para todo
                        throw AgenteException(
                            SIN_SESION_TEXTO + (if (ok > 0) "Antes se han subido bien $ok." else "No se ha subido nada a Nexprint.")
                        )
                    } catch (e: Exception) {
                        val mensaje = e.message?.lineSequence()?.firstOrNull() ?: e.toString()
                        progreso.error("Error al subir ${m.name()} a Nexprint en $mensaje")
                        anotarError(m.dir(), e, avisosSeguros(page))
                        captura(page, m.dir().resolve(CAPTURA_ERROR))
                        progreso.info("   Detalle en ${m.dir().resolve(ERROR_TXT)} y captura en ${m.dir().resolve(CAPTURA_ERROR)}")
                        ko++
                    } finally {
                        if (!page.isClosed) page.close()
                    }
                    if (parado) break
                }
            }
        }
        progreso.info("\n${if (parado) "Parado" else "Terminado"}: $ok subidos a Nexprint, $ko con error." +
            (if (yaSubidos > 0) " $yaSubidos ya estaban." else "") +
            (if (sinSubir > 0) " $sinSubir sin subir." else ""))
        return Resumen(ok, yaSubidos, ko, sinSubir, parado)
    }

    /** Hace un paso del formulario y, si falla, dice cuál era. Parar y descartar no son errores. */
    private fun <T> paso(nombre: String, bloque: () -> T): T {
        progreso.paso(nombre)
        return try {
            bloque()
        } catch (e: CanceladoException) {
            throw e
        } catch (e: Descartado) {
            throw e
        } catch (e: SinSesion) {
            throw e
        } catch (e: ErrorEnPaso) {
            throw e
        } catch (e: Exception) {
            throw ErrorEnPaso(nombre, e.message?.lineSequence()?.firstOrNull() ?: e.toString(), e)
        }
    }

    /**
     * Rellena el formulario de Nexprint con un modelo y lo guarda como borrador.
     *
     * @return el id del modelo en Nexprint
     * @throws CanceladoException si se pide parar antes de guardar (no se guarda nada)
     */
    private fun subir(page: Page, m: ModelFolder, revisar: Boolean, ventana: Downloader.Ventana?): String {
        val meta = m.meta()
        val temporal = Files.createTempDirectory("jujamaker-nexprint")
        val red = Red()
        page.onResponse(red)
        try {
            paso("Abrir el formulario de Nexprint") {
                page.navigate(FORMULARIO)
                val zona = page.locator("input.el-upload__input").first()
                try {
                    zona.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(30_000.0))
                } catch (e: TimeoutError) {
                    if (!sigueConSesion(page)) throw SinSesion()
                    throw IllegalStateException("no se ha cargado el formulario de subida (${page.url()})")
                }
                if (!sigueConSesion(page)) throw SinSesion()
                descartarSubidaAMedias(page, esperarMs = 5_000.0)
            }

            paso("Modo \"Single model\"") {
                marcar(page.locator("label.el-radio").filter(Locator.FilterOptions().setHasText("Single model")).first())
            }

            val archivos = m.modelos()
            paso("Subir ${archivos.size} archivo(s) del modelo (${archivos.joinToString(", ") { it.fileName.toString() }})") {
                val aSubir = if (archivos.size == 1) {
                    val original = archivos[0]
                    val ext = original.fileName.toString().substringAfterLast('.', "").lowercase()
                    val copia = temporal.resolve(nombreArchivo(meta.title()) + "." + ext)
                    Files.copy(original, copia, StandardCopyOption.REPLACE_EXISTING)
                    progreso.nota("se sube como \"${copia.fileName}\"")
                    listOf(copia)
                } else {
                    archivos
                }
                val antes = red.modelosSubidos.get()
                page.locator("input.el-upload__input").first().setInputFiles(aSubir.toTypedArray())
                esperar("que terminen de subir los archivos del modelo", 20 * 60_000L, page, red) {
                    red.modelosSubidos.get() - antes >= aSubir.size
                }
                // La web hace sola unas vistas previas del modelo: se les da un momento
                val limite = System.currentTimeMillis() + 30_000L
                while (red.imagenesSubidas.get() == 0 && System.currentTimeMillis() < limite) {
                    cancelacion.comprobar()
                    page.waitForTimeout(500.0)
                }
            }

            cancelacion.comprobar() // punto seguro: aún no se ha rellenado nada
            paso("Pasar a \"Model Information\"") {
                descartarSubidaAMedias(page, esperarMs = 0.0)
                boton(page, "Model Information").click()
                try {
                    campoNombre(page).waitFor(Locator.WaitForOptions().setTimeout(20_000.0))
                } catch (e: TimeoutError) {
                    throw IllegalStateException("no ha pasado al paso 2. Avisos: ${avisos(page)}")
                }
            }

            paso("Origen: Original Model") {
                marcar(page.locator("label.el-radio").filter(Locator.FilterOptions().setHasText("Original Model")).first())
            }

            val portada = m.portada()
            paso("Portada (${portada.fileName})") {
                val antes = red.imagenesSubidas.get()
                page.locator("input[type=file][accept^='.jpeg']:not([multiple])").first().setInputFiles(portada)
                // Sale una ventana para recortarla: se acepta tal cual ("OK")
                val ok = page.locator(".el-dialog").locator("visible=true")
                    .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("OK").setExact(true)).first()
                try {
                    ok.waitFor(Locator.WaitForOptions().setTimeout(15_000.0))
                    ok.click()
                } catch (e: TimeoutError) {
                    throw IllegalStateException("no ha salido la ventana de recorte de la portada")
                }
                esperar("que termine de subir la portada", 2 * 60_000L, page, red) { red.imagenesSubidas.get() > antes }
            }

            val titulo = recortar(meta.title(), MAX_NOMBRE, "El nombre")
            paso("Nombre") {
                val campo = campoNombre(page)
                campo.fill(titulo)
                if (campo.inputValue() != titulo) throw IllegalStateException("no se ha quedado escrito el nombre")
            }

            paso("Categoría ${CATEGORIA.first} > ${CATEGORIA.second}") { elegirCategoria(page) }

            paso("Evento: Not Interested") {
                val casilla = page.locator("label.el-checkbox").filter(
                    Locator.FilterOptions().setHasText(Pattern.compile("^\\s*Not Interested\\s*$"))
                ).first()
                if (casilla.count() == 0) throw IllegalStateException("no encuentro la casilla \"Not Interested\"")
                if ("is-checked" !in (casilla.getAttribute("class") ?: "")) casilla.click()
                if ("is-checked" !in (casilla.getAttribute("class") ?: "")) throw IllegalStateException("no se queda marcada \"Not Interested\"")
            }

            paso("Descripción") { escribirDescripcion(page, meta.description()) }

            val etiquetas = etiquetas(meta.tags())
            if (etiquetas.isNotEmpty()) paso("Etiquetas (${etiquetas.size})") { escribirEtiquetas(page, etiquetas) }

            paso("Piezas no impresas: No y licencia Creative Commons Public Domain") { marcarMaterialesYLicencia(page) }

            paso("Comprobación final del formulario") {
                val problemas = comprobarFormulario(page, titulo)
                if (problemas.isNotEmpty()) throw IllegalStateException(problemas.joinToString("; "))
            }

            if (revisar) {
                ventana?.mostrar()
                val guardar = try {
                    revision.confirmar(m.name())
                } finally {
                    ventana?.apartar()
                }
                if (!guardar) throw Descartado()
            }
            cancelacion.comprobar() // último punto seguro
            return paso("Guardar como borrador (Save as draft)") { guardarBorrador(page, red) }
        } finally {
            page.offResponse(red)
            try {
                Files.walk(temporal).sorted(reverseOrder()).forEach { Files.deleteIfExists(it) }
            } catch (_: Exception) {
                // la carpeta temporal la borrará Windows; no es grave
            }
        }
    }

    // ------------------------------------------------------------------ red

    /**
     * Lo que se ve en la red. Cada archivo subido termina con `infra-server/file/create`, y su cuerpo
     * dice dónde quedó: en `/models/` los del modelo y en `/images/` las imágenes (vistas previas y
     * portada). Al guardar, `createOrUpdateBatch` devuelve el id. Comprobado el 11/11/2026.
     */
    private class Red : Consumer<Response> {
        val modelosSubidos = AtomicInteger(0)
        val imagenesSubidas = AtomicInteger(0)
        /** Si una subida de archivo responde con error, su mensaje. */
        val errorSubida = AtomicReference<String?>()
        val guardado = AtomicReference<JsonNode?>()
        val errorGuardado = AtomicReference<String?>()

        override fun accept(r: Response) {
            val url = r.url()
            when {
                "/infra-server/file/create" in url -> {
                    val j = leerJson(r)
                    if (j == null || j.path("code").asInt(-1) != 0) {
                        errorSubida.set(j?.path("msg")?.asText("").orEmpty().ifBlank { "HTTP ${r.status()}" })
                        return
                    }
                    val cuerpo = try { r.request().postData() ?: "" } catch (_: Exception) { "" }
                    when {
                        "/models/" in cuerpo -> modelosSubidos.incrementAndGet()
                        "/images/" in cuerpo -> imagenesSubidas.incrementAndGet()
                    }
                }
                "/model-base-info/createOrUpdateBatch" in url -> {
                    val j = leerJson(r)
                    if (j == null) errorGuardado.set(if (r.ok()) "respuesta que no se entiende" else "HTTP ${r.status()}")
                    else guardado.set(j)
                }
            }
        }

        private fun leerJson(r: Response): JsonNode? = try {
            JSON.readTree(r.text())
        } catch (_: Exception) {
            null
        }
    }

    /** Espera a que se cumpla [listo], fallando si la web avisa de un error o se pasa el tiempo. */
    private fun esperar(que: String, maxMs: Long, page: Page, red: Red, listo: () -> Boolean) {
        val limite = System.currentTimeMillis() + maxMs
        while (!listo()) {
            cancelacion.comprobar()
            red.errorSubida.get()?.let { throw IllegalStateException("Nexprint no ha aceptado un archivo: $it") }
            val error = page.locator(".el-message--error").locator("visible=true")
            if (error.count() > 0) throw IllegalStateException("esperando $que, Nexprint dice: ${error.first().innerText().trim()}")
            if (System.currentTimeMillis() > limite) throw IllegalStateException("no llega: $que (${maxMs / 60_000} min)")
            page.waitForTimeout(500.0)
        }
    }

    // ------------------------------------------------------------------ campos

    /**
     * El nombre del modelo: el campo de la sección cuyo título (`p.title-p`) es "*Model Name". No vale
     * buscar "el campo de 80 letras": el buscador de arriba de la web también lo es y a veces se ve
     * (el 11/11/2026 el nombre se escribió ahí en 5 modelos y fallaron).
     */
    private fun campoNombre(page: Page): Locator =
        page.locator(".input-div").filter(
            Locator.FilterOptions().setHas(page.locator("p.title-p").filter(
                Locator.FilterOptions().setHasText(Pattern.compile("^\\s*\\*?\\s*Model Name\\s*$"))
            ))
        ).locator("input.el-input__inner").first()

    /** Un botón visible por su texto exacto ("Model Information", "Save as draft"). */
    private fun boton(page: Page, texto: String): Locator {
        val b = page.locator("button").locator("visible=true")
            .filter(Locator.FilterOptions().setHasText(textoExacto(texto))).first()
        try {
            b.waitFor(Locator.WaitForOptions().setTimeout(30_000.0))
        } catch (e: TimeoutError) {
            throw IllegalStateException("no encuentro el botón \"$texto\"")
        }
        // Algunos están desactivados mientras la web termina algo (p. ej. "Model Information" hasta que se sube el archivo)
        val limite = System.currentTimeMillis() + 60_000L
        while (!b.isEnabled) {
            cancelacion.comprobar()
            if (System.currentTimeMillis() > limite) throw IllegalStateException("el botón \"$texto\" sigue desactivado (1 min)")
            page.waitForTimeout(500.0)
        }
        return b
    }

    /**
     * Expresión para "este texto y nada más" (sin contar espacios). Se escribe a mano y no con
     * Pattern.quote porque Playwright pasa la expresión a JavaScript, que no entiende el \Q…\E de Java
     * (por eso no encontraba el botón "Model Information" el 11/11/2026).
     */
    private fun textoExacto(texto: String): Pattern =
        Pattern.compile("^\\s*" + texto.replace(Regex("[\\\\^$.|?*+()\\[\\]{}/-]")) { "\\" + it.value } + "\\s*$")

    /**
     * Si un intento anterior se quedó a medias (archivo subido pero sin guardar), al abrir el formulario
     * Nexprint pregunta qué hacer con él: ventana "Unsaved" (`.unsaved-dialog`) con "Don't save" /
     * "Save as draft" / "Continue editing" (vista el 11/11/2026). Tapa la página, así que se pulsa
     * "Don't save": se tira lo de antes y se empieza limpio. Seguir con lo de antes subiría el archivo
     * dos veces, y guardarlo dejaría un borrador a medias en la cuenta.
     *
     * @param esperarMs cuánto esperar a que salga (0 = solo mirar si ya está)
     */
    private fun descartarSubidaAMedias(page: Page, esperarMs: Double) {
        val ventana = page.locator(".unsaved-dialog").locator("visible=true").first()
        if (esperarMs > 0) {
            try {
                ventana.waitFor(Locator.WaitForOptions().setTimeout(esperarMs))
            } catch (_: TimeoutError) {
                return // no ha salido: no había nada a medias
            }
        } else if (ventana.count() == 0) {
            return
        }
        // El apóstrofo puede ser recto o tipográfico (Don't / Don’t)
        val noGuardar = ventana.locator("button").filter(
            Locator.FilterOptions().setHasText(Pattern.compile("^\\s*Don.t save\\s*$", Pattern.CASE_INSENSITIVE))
        ).first()
        if (noGuardar.count() == 0) throw IllegalStateException("Nexprint pregunta por una subida a medias y no encuentro \"Don't save\"")
        progreso.nota("había una subida a medias de un intento anterior: se descarta (\"Don't save\")")
        noGuardar.click()
        ventana.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout(10_000.0))
    }

    /** Pulsa esa opción de radio y comprueba que se queda marcada. */
    private fun marcar(opcion: Locator) {
        if (opcion.count() == 0) throw IllegalStateException("no encuentro la opción")
        if ("is-checked" !in (opcion.getAttribute("class") ?: "")) opcion.click()
        if ("is-checked" !in (opcion.getAttribute("class") ?: "")) {
            throw IllegalStateException("no se queda marcada la opción \"${opcion.innerText().trim()}\"")
        }
    }

    /**
     * La categoría es un desplegable en dos niveles (`.el-cascader`): se abre, se pasa por encima del
     * grupo para que salgan sus subcategorías y se pulsa la final. Queda "Toys & Games / Others".
     */
    private fun elegirCategoria(page: Page) {
        val (grupo, final) = CATEGORIA
        page.locator(".el-cascader").first().click()
        val panel = page.locator(".el-cascader-panel").locator("visible=true").first()
        panel.waitFor(Locator.WaitForOptions().setTimeout(10_000.0))
        val nodoGrupo = panel.locator(".el-cascader-menu").nth(0).locator(".el-cascader-node")
            .filter(Locator.FilterOptions().setHasText(textoExacto(grupo))).first()
        if (nodoGrupo.count() == 0) throw IllegalStateException("no está \"$grupo\" en las categorías")
        nodoGrupo.hover()
        nodoGrupo.click()
        val nodoFinal = panel.locator(".el-cascader-menu").nth(1).locator(".el-cascader-node")
            .filter(Locator.FilterOptions().setHasText(textoExacto(final))).first()
        nodoFinal.waitFor(Locator.WaitForOptions().setTimeout(10_000.0))
        nodoFinal.click()
        val valor = page.locator(".el-cascader input").first().inputValue()
        if (valor != "$grupo / $final") throw IllegalStateException("la categoría se ha quedado en \"$valor\"")
    }

    /**
     * La descripción va en un editor CKEditor 5: se mete de golpe con el propio editor, un párrafo por
     * línea (comprobado el 11/11/2026 con `ckeditorInstance.setData`).
     *
     * Hay que coger el editor de la sección "Model description" (su título es un `p.title-p`): con un
     * .3mf la web añade antes otro editor, "Print profile introduction" (máximo 1000), y el primero de
     * la página es ese (el 11/11/2026 la descripción acabó ahí y la de verdad quedó vacía).
     */
    private fun escribirDescripcion(page: Page, texto: String) {
        var t = texto.trim()
        if (t.length > MAX_DESCRIPCION) {
            progreso.nota("la descripción tiene más de $MAX_DESCRIPCION caracteres en Nexprint: se corta")
            t = t.take(MAX_DESCRIPCION)
        }
        val html = t.split(Regex("\\R")).joinToString("") { linea ->
            if (linea.isBlank()) "<p>&nbsp;</p>" else "<p>" + escaparHtml(linea) + "</p>"
        }
        val puesta = page.evaluate(
            """html => {
              const t = [...document.querySelectorAll('p.title-p')].find(p => p.textContent.trim() === 'Model description');
              const e = t && t.parentElement.querySelector('.ck-editor__editable');
              if (!e || !e.ckeditorInstance) return false;
              e.ckeditorInstance.setData(html);
              return e.innerText.trim().length > 0;
            }""",
            html,
        ) == true
        if (!puesta) throw IllegalStateException("no se ha quedado escrita la descripción")
    }

    /** Cada etiqueta se escribe en su campo (máximo 50 letras) y se pulsa Enter. */
    /**
     * Las etiquetas van en la sección `.tag-input-div` (título "Tag"). Su campo (`.tag-input input`,
     * máximo 50 letras) a veces está escondido (`hide-tag-input`) hasta que se pulsa en la zona de las
     * etiquetas (`.input-div`). Con un .3mf hay otra `.tag-input-div` escondida antes que la buena
     * (el 11/11/2026 se cogía esa y fallaba): se usa la visible cuyo título (`p.title-p`) es "Tag".
     * Cada etiqueta se escribe y se pulsa Enter; las puestas salen como `.tag-item` con su texto en `title`.
     */
    private fun escribirEtiquetas(page: Page, etiquetas: List<String>) {
        // Puede haber más de una zona de etiquetas (con un .3mf, una escondida): la visible con título "Tag"
        val seccion = page.locator(".tag-input-div").locator("visible=true").filter(
            Locator.FilterOptions().setHas(page.locator("p.title-p").filter(Locator.FilterOptions().setHasText(textoExacto("Tag"))))
        ).first()
        try {
            seccion.waitFor(Locator.WaitForOptions().setTimeout(10_000.0))
        } catch (e: TimeoutError) {
            throw IllegalStateException("no encuentro la sección de etiquetas (Tag)")
        }
        seccion.scrollIntoViewIfNeeded()
        val campo = seccion.locator(".tag-input input.el-input__inner").first()
        for (t in etiquetas) {
            cancelacion.comprobar()
            if (!campo.isVisible) seccion.locator(".input-div").first().click()
            campo.fill(t)
            campo.press("Enter")
            page.waitForTimeout(300.0)
        }
        val puestas = seccion.locator(".tag-item").all().mapNotNull { it.getAttribute("title") }
        val faltan = etiquetas.filter { e -> puestas.none { it.equals(e, ignoreCase = true) } }
        if (faltan.size == etiquetas.size) throw IllegalStateException("no se ha añadido ninguna etiqueta")
        if (faltan.isNotEmpty()) progreso.nota("no se han añadido estas etiquetas: ${faltan.joinToString(", ")}")
    }

    /**
     * "Bill of materials" (¿lleva piezas no impresas?) y las tres preguntas de la licencia son grupos de
     * radios (`.el-radio-group`), cada uno debajo de su pregunta. Materiales: No. Licencia: Sí a las tres
     * (remezclar, uso comercial y redistribuir), con lo que la web dice "Creative Commons Public Domain".
     */
    private fun marcarMaterialesYLicencia(page: Page) {
        val grupos = page.locator(".el-radio-group").locator("visible=true")
        val preguntas = (0 until grupos.count()).map { i ->
            grupos.nth(i).evaluate("g => ((g.parentElement && g.parentElement.parentElement && g.parentElement.parentElement.innerText) || '').split('\\n')[0].trim()") as String
        }
        val materiales = preguntas.indexOfFirst { "Bill of materials" in it }
        if (materiales < 0) throw IllegalStateException("no encuentro la pregunta \"Bill of materials\"")
        marcar(opcionExacta(grupos.nth(materiales), "No"))
        // Las de la licencia son los grupos que van detrás: el primero tiene "Yes, allow others…"
        val licencia = (materiales + 1 until grupos.count()).toList()
        if (licencia.size != 3) throw IllegalStateException("esperaba 3 preguntas de licencia y hay ${licencia.size}")
        for (i in licencia) marcar(opcionExacta(grupos.nth(i), "Yes"))
        page.waitForTimeout(500.0)
        val texto = textoLicencia(page)
        if (texto != LICENCIA) throw IllegalStateException("la licencia ha quedado como \"$texto\" y no \"$LICENCIA\"")
    }

    private fun opcionExacta(grupo: Locator, texto: String): Locator =
        grupo.locator("label.el-radio").filter(Locator.FilterOptions().setHasText(textoExacto(texto))).first()

    /** El texto de la licencia que dice la web ("This model is licensed under the following terms: …"). */
    private fun textoLicencia(page: Page): String? = page.evaluate(
        "() => { const m = document.body.innerText.match(/licensed under the following terms:\\s*([^\\n]+)/); return m ? m[1].trim() : null; }"
    ) as String?

    /** Lo último antes de guardar: que todo esté como debe. Devuelve lo que no lo está. */
    private fun comprobarFormulario(page: Page, titulo: String): List<String> {
        val p = ArrayList<String>()
        if (campoNombre(page).inputValue() != titulo) p += "el nombre no es el del modelo"
        val categoria = page.locator(".el-cascader input").first().inputValue()
        if (categoria != "${CATEGORIA.first} / ${CATEGORIA.second}") p += "la categoría es \"$categoria\""
        if (textoLicencia(page) != LICENCIA) p += "la licencia no es $LICENCIA"
        if (page.locator("label.el-radio.is-checked").filter(Locator.FilterOptions().setHasText("Original Model")).count() == 0) {
            p += "no está marcado \"Original Model\""
        }
        val errores = page.locator(".el-form-item__error").locator("visible=true").allInnerTexts().map { it.trim() }.filter { it.isNotEmpty() }
        if (errores.isNotEmpty()) p += "la web marca: " + errores.joinToString(" | ")
        return p
    }

    /** Pulsa "Save as draft" y espera la respuesta de la web: devuelve el id del modelo. */
    private fun guardarBorrador(page: Page, red: Red): String {
        red.guardado.set(null)
        red.errorGuardado.set(null)
        boton(page, "Save as draft").click()
        val limite = System.currentTimeMillis() + 90_000L
        while (red.guardado.get() == null) {
            red.errorGuardado.get()?.let { throw IllegalStateException("Nexprint no lo ha guardado ($it)") }
            val aviso = page.locator(".el-message--warning, .el-message--error").locator("visible=true")
            if (aviso.count() > 0) throw IllegalStateException("Nexprint no deja guardar: ${aviso.first().innerText().trim()}")
            if (System.currentTimeMillis() > limite) {
                throw IllegalStateException("he pulsado \"Save as draft\", pero Nexprint no ha respondido en 90 s. Mira en tu perfil si se ha guardado antes de repetir")
            }
            page.waitForTimeout(500.0)
        }
        val j = red.guardado.get()!!
        if (j.path("code").asInt(-1) != 0) {
            throw IllegalStateException("Nexprint no lo ha guardado: ${j.path("msg").asText("código " + j.path("code").asText("?"))}")
        }
        val id = j.path("data").path("modelInfoList").path(0).path("id").asText("")
        if (id.isBlank()) throw IllegalStateException("Nexprint no ha devuelto el id del modelo: mira en tu perfil si se ha guardado")
        page.waitForTimeout(1500.0) // que salga "Draft saved successfully" en la captura
        return id
    }

    // ------------------------------------------------------------------ utilidades

    /** Si la página sigue en Nexprint con sesión (sin ella, la web manda al inicio de sesión de ELEGOO). */
    private fun sigueConSesion(page: Page): Boolean =
        "account.elegoo.com" !in page.url() &&
            page.context().cookies(WEB).any { it.name == COOKIE_SESION && !it.value.isNullOrBlank() }

    private fun avisos(page: Page): String {
        val textos = page.locator(".el-message, .el-form-item__error, .el-message-box").allInnerTexts()
            .map { it.trim() }.filter { it.isNotEmpty() }
        return if (textos.isEmpty()) "(ninguno)" else textos.joinToString(" | ")
    }

    private fun avisosSeguros(page: Page): String = try {
        avisos(page)
    } catch (_: Exception) {
        "(no se han podido leer)"
    }

    private fun recortar(texto: String, max: Int, que: String): String {
        val t = texto.trim()
        if (t.length <= max) return t
        progreso.nota("$que tiene más de $max caracteres en Nexprint: se corta")
        return t.take(max).trim()
    }

    /** Separa por comas, quita vacías y repetidas, corta a 50 caracteres y se queda con 20. */
    private fun etiquetas(tags: List<String>): List<String> {
        val separadas = tags.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
        if (separadas.any { it.length > MAX_LETRAS_ETIQUETA }) progreso.nota("alguna etiqueta tenía más de $MAX_LETRAS_ETIQUETA caracteres: se ha cortado")
        val limpias = separadas.map { if (it.length > MAX_LETRAS_ETIQUETA) it.take(MAX_LETRAS_ETIQUETA).trim() else it }.distinctBy { it.lowercase() }
        if (limpias.size > MAX_ETIQUETAS) progreso.nota("Nexprint admite $MAX_ETIQUETAS etiquetas: se suben las $MAX_ETIQUETAS primeras")
        return limpias.take(MAX_ETIQUETAS)
    }

    private fun escaparHtml(t: String): String =
        t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun captura(page: Page, destino: Path) {
        try {
            page.screenshot(Page.ScreenshotOptions().setPath(destino).setFullPage(true))
        } catch (_: Exception) {
            // si la página ya no responde, seguimos sin captura
        }
    }

    companion object {
        const val WEB = "https://www.nexprint.com"
        /** El formulario de subida (botón "Upload" de la barra de arriba). */
        const val FORMULARIO = "$WEB/en/upload"

        /**
         * La cookie de la sesión: `auth_token` en www.nexprint.com (dura 7 días; comprobado el 11/11/2026).
         * Sin sesión, la web manda al inicio de sesión de ELEGOO (account.elegoo.com).
         */
        const val COOKIE_SESION = "auth_token"

        /** Fijas, decididas por la persona el 11/11/2026 (los nombres, tal cual en la web en inglés). */
        val CATEGORIA = "Toys & Games" to "Others"
        const val LICENCIA = "Creative Commons Public Domain"

        /** Límites del formulario (comprobados el 11/11/2026). */
        const val MAX_NOMBRE = 80
        const val MAX_ETIQUETAS = 20
        const val MAX_LETRAS_ETIQUETA = 50
        const val MAX_DESCRIPCION = 10_000

        /** Dentro de la carpeta del modelo: lo subido a Nexprint (id, dirección del borrador y fecha). */
        const val REGISTRO = "subido-nexprint.txt"
        const val CAPTURA_OK = "resultado-nexprint.png"
        const val CAPTURA_ERROR = "error-nexprint.png"
        /** Dentro de la carpeta del modelo: en qué paso falló y por qué. */
        const val ERROR_TXT = "error-nexprint.txt"

        private const val SIN_SESION_TEXTO =
            "No hay sesión de Nexprint en Chrome (o ha caducado). Vuelve a iniciarla en la pantalla Sesión. "

        private val JSON = ObjectMapper()
        private val FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /** La página para editar el borrador (la web la usa así: /en/editUpload/<id>). */
        @JvmStatic
        fun borrador(id: String) = "$WEB/en/editUpload/$id"

        /** Si esa carpeta ya se subió a Nexprint. */
        @JvmStatic
        fun yaSubido(carpeta: Path): Boolean = Files.exists(carpeta.resolve(REGISTRO))

        /** Las carpetas de subidos/ (ya en MakerOnline) que aún no están en Nexprint, por nombre. */
        @JvmStatic
        fun subidosSinNexprint(rutas: Rutas): List<String> {
            val base = rutas.subidos
            if (!Files.isDirectory(base)) return emptyList()
            return Files.list(base).use { st -> st.filter { Files.isDirectory(it) }.sorted().toList() }
                .filter { !yaSubido(it) && runCatching { ModelFolder.load(it) }.isSuccess }
                .map { it.fileName.toString() }
        }

        private fun anotarSubido(carpeta: Path, id: String) {
            Files.writeString(carpeta.resolve(REGISTRO), "$id\t${borrador(id)}\t${LocalDateTime.now().format(FECHA)}\n")
        }

        /** Escribe error-nexprint.txt: fecha, paso, motivo y los avisos que enseñaba la página. */
        private fun anotarError(carpeta: Path, e: Exception, avisos: String) {
            val texto = buildString {
                append(LocalDateTime.now().format(FECHA)).append('\n')
                if (e is ErrorEnPaso) {
                    append("Paso: ").append(e.paso).append('\n')
                    append("Motivo: ").append(e.motivo).append('\n')
                } else {
                    append("Motivo: ").append(e.message ?: e.toString()).append('\n')
                }
                append("Avisos de la página: ").append(avisos).append('\n')
                val causa = (e.cause ?: e)
                append("\nDetalle técnico:\n").append(causa.toString()).append('\n')
                causa.stackTrace.take(8).forEach { append("  at ").append(it).append('\n') }
            }
            try {
                Files.writeString(carpeta.resolve(ERROR_TXT), texto)
            } catch (_: Exception) {
                // si no se puede escribir, el error ya se ha enseñado en pantalla
            }
        }

        /** Tras subirlo bien, se quitan los restos de un intento fallido anterior. */
        private fun borrarErroresAnteriores(carpeta: Path) {
            for (n in listOf(ERROR_TXT, CAPTURA_ERROR)) {
                try {
                    Files.deleteIfExists(carpeta.resolve(n))
                } catch (_: Exception) {
                    // no es grave
                }
            }
        }

        /** Nombre válido para un archivo de Windows, a partir del título. */
        private fun nombreArchivo(titulo: String): String {
            val s = titulo.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").replace(Regex("\\s+"), " ").trim()
                .take(80).trimEnd('.', ' ')
            return s.ifEmpty { "modelo" }
        }

        @JvmStatic
        fun main(args: Array<String>) {
            val lista = args.toList()
            val op = Opciones(
                revisar = "--revisar" in lista,
                carpetas = lista.filter { !it.startsWith("--") },
                esperar = "--esperar" in lista,
                desdeSubidos = "--subidos" in lista,
            )
            val rutas = Rutas.carpetaActual()
            val progreso = ProgresoConsola()
            val cancelacion = Cancelacion()
            try {
                AgentLock.acquire(rutas.candado, "Subir a Nexprint", op.esperar, progreso).use {
                    EntradaConsola.iniciar(cancelacion, progreso)
                    SubidorNexprint(rutas, progreso, cancelacion, RevisionConsola(progreso, cancelacion)).ejecutar(op)
                }
            } catch (e: AgenteException) {
                progreso.error(e.message ?: e.toString())
                exitProcess(e.codigoSalida)
            }
            exitProcess(0)
        }
    }
}
