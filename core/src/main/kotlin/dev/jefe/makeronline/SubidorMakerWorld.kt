package dev.jefe.makeronline

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Response
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.options.WaitForSelectorState
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.regex.Pattern
import kotlin.system.exitProcess

/**
 * Sube a MakerWorld (Bambu Lab) las carpetas de subidas/pendientes/, con los mismos archivos y textos
 * que se usan para MakerOnline y Creality Cloud. Todo lo que usa de la web está en
 * docs/contexto-makerworld.md, comprobado el 10/11/2026 en el formulario y en lo que guarda.
 *
 * Cada modelo se guarda como **borrador** ("Save to draft"), marcado además como privado para cuando
 * se publique: lo revisas en MakerWorld y lo publicas tú.
 *
 *  - Se sube por el camino "I have STL/CAD files or other types of 3MF files": todos los .stl y .3mf
 *    de la carpeta van como archivos del modelo ("Raw Model Files"). No se sube perfil de impresión:
 *    MakerWorld solo lo admite con un .3mf de Bambu Studio y una foto real de esa impresión, y eso
 *    lo decide la persona (se puede añadir luego en la web).
 *  - Portadas: la portada de la carpeta en las dos (4:3 y 3:4), aceptando el recorte que propone la web.
 *    Las demás imágenes van a "Model Pictures". Las fotos reales ("Real life photos") no se ponen:
 *    la app no puede saber cuáles son fotos reales de la pieza impresa.
 *  - La categoría sale de la de MakerOnline (ver CatalogoMakerWorld); la licencia, de la de los textos
 *    (CC0 si no tiene equivalente).
 *  - "Original" o "Remix" no se marca: lo eliges tú al publicar.
 *  - Antes de guardar se comprueba el formulario, y después se lee el borrador de MakerWorld
 *    (`GET /api/v1/design-service/my/draft/<id>`) para confirmar que ha guardado lo que debe.
 *
 * No mueve las carpetas (se quedan en pendientes/ para las otras webs): apunta lo subido en
 * `subido-makerworld.txt` dentro de la carpeta, y las que lo tienen no se vuelven a subir. Si algo
 * falla, deja `error-makerworld.txt` (en qué paso y por qué, y el borrador a medias si se llegó a crear)
 * y `error-makerworld.png`.
 *
 * Programa de terminal (configuración "Subir a MakerWorld (revisar)"):
 *   --revisar   antes de guardar cada borrador, se para para que mires el formulario:
 *               ENTER lo guarda; "no" + ENTER no lo guarda (el borrador a medias se queda en MakerWorld).
 *   --esperar   si otro programa del agente está en marcha, espera a que termine.
 *   nombre ...  solo esas carpetas de pendientes/ (sin nombres, todas).
 * Para pararlo sin dejar nada a medias: "parar" + ENTER.
 */
class SubidorMakerWorld(
    private val rutas: Rutas,
    private val progreso: Progreso,
    private val cancelacion: Cancelacion,
    private val revision: Revision,
) {

    /** Las mismas opciones que SubidorCreality.Opciones. */
    data class Opciones(
        val revisar: Boolean,
        val carpetas: List<String>,
        val esperar: Boolean,
        val verChrome: Boolean = true,
    )

    /**
     * @property subidos     guardados bien como borrador (cada uno con su subido-makerworld.txt)
     * @property yaSubidos   saltados porque ya tenían subido-makerworld.txt
     * @property conError    fallaron (con error-makerworld.txt y error-makerworld.png en su carpeta)
     * @property sinSubir    no válidos, descartados al revisar o sin hacer porque se ha parado
     * @property parado      true si se ha parado a petición de la persona
     */
    data class Resumen(val subidos: Int, val yaSubidos: Int, val conError: Int, val sinSubir: Int, val parado: Boolean)

    /** La persona ha decidido no guardar este borrador al revisarlo. */
    private class Descartado : RuntimeException("descartado al revisar")

    /** La página de subida manda a iniciar sesión: no hay sesión de MakerWorld (o ha caducado). */
    private class SinSesion : RuntimeException("no hay sesión de MakerWorld")

    /** Algo ha fallado en un paso concreto del formulario: el mensaje dice cuál. */
    class ErrorEnPaso(val paso: String, val motivo: String, causa: Throwable?) :
        RuntimeException("$paso: $motivo", causa)

    /** El borrador que MakerWorld ha creado para el modelo en curso (para decirlo si algo falla después). */
    private var borradorEnCurso: String? = null

    fun ejecutar(op: Opciones): Resumen {
        val pendientes = rutas.pendientes
        Files.createDirectories(pendientes)
        if (!Files.isDirectory(rutas.perfilChrome)) {
            throw AgenteException("No hay sesión guardada. Inicia sesión primero (app: pantalla Sesión).")
        }
        var carpetas = Files.list(pendientes).use { s -> s.filter { Files.isDirectory(it) }.sorted().toList() }
        if (op.carpetas.isNotEmpty()) {
            for (n in op.carpetas) if (!Files.isDirectory(pendientes.resolve(n))) progreso.error("No existe en pendientes: $n")
            carpetas = carpetas.filter { it.fileName.toString() in op.carpetas }
        }
        if (carpetas.isEmpty()) {
            progreso.info("No hay nada en ${pendientes.toAbsolutePath()}")
            return Resumen(0, 0, 0, 0, false)
        }

        // 1) Validar antes de abrir el navegador (igual que en Creality). A MakerWorld valen .stl y .3mf
        val listos = ArrayList<ModelFolder>()
        var yaSubidos = 0
        var sinSubir = 0
        for (dir in carpetas) {
            if (cancelacion.pedida()) return Resumen(0, yaSubidos, 0, sinSubir, true)
            if (yaSubido(dir)) {
                progreso.saltado("${dir.fileName}: ya está en MakerWorld ($REGISTRO), no se vuelve a subir")
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
            progreso.info("Nada que subir a MakerWorld.")
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
                for ((i, m) in listos.withIndex()) {
                    if (cancelacion.pedida()) {
                        parado = true
                        sinSubir += listos.size - i
                        break
                    }
                    progreso.modelo(i + 1, listos.size, m.name())
                    val page = ctx.newPage()
                    // Fuera de la pantalla, cada pestaña nueva se aparta también (como en el Uploader)
                    val ventana = Downloader.Ventana(modo, ctx, page, progreso)
                    borradorEnCurso = null
                    try {
                        val id = subir(page, m, op.revisar, ventana)
                        anotarSubido(m.dir(), id)
                        captura(page, m.dir().resolve(CAPTURA_OK))
                        borrarErroresAnteriores(m.dir())
                        progreso.correcto("Guardado en MakerWorld como borrador: ${direccionBorrador(id)}")
                        ok++
                    } catch (_: CanceladoException) {
                        parado = true
                        sinSubir += listos.size - i
                        progreso.info("⏹ ${m.name()} no se ha guardado en MakerWorld." + aMedias())
                    } catch (_: Descartado) {
                        sinSubir++
                        progreso.saltado("No se guarda ${m.name()} en MakerWorld." + aMedias())
                    } catch (_: SinSesion) {
                        // No es culpa de la carpeta: no se apunta error en ella y se para todo
                        throw AgenteException(
                            "No hay sesión de MakerWorld en Chrome (o ha caducado). Vuelve a iniciarla en la " +
                                "pantalla Sesión. " + (if (ok > 0) "Antes se han subido bien $ok." else "No se ha subido nada a MakerWorld.")
                        )
                    } catch (e: Exception) {
                        val mensaje = e.message?.lineSequence()?.firstOrNull() ?: e.toString()
                        progreso.error("Error al subir ${m.name()} a MakerWorld en $mensaje" + aMedias())
                        anotarError(m.dir(), e, avisosSeguros(page), borradorEnCurso)
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
        progreso.info("\n${if (parado) "Parado" else "Terminado"}: $ok subidos a MakerWorld, $ko con error." +
            (if (yaSubidos > 0) " $yaSubidos ya estaban." else "") +
            (if (sinSubir > 0) " $sinSubir sin subir." else ""))
        if (ok > 0) {
            progreso.nota("Antes de publicar en MakerWorld: marca Original o Remix, añade fotos reales de la pieza " +
                "impresa y, si quieres, el perfil de impresión (.3mf de Bambu Studio).")
        }
        return Resumen(ok, yaSubidos, ko, sinSubir, parado)
    }

    /** Si MakerWorld ya había creado el borrador, dónde está (para que no se pierda ni se duplique sin saberlo). */
    private fun aMedias(): String =
        borradorEnCurso?.let { " Se ha quedado un borrador a medias en MakerWorld: ${direccionBorrador(it)}" } ?: ""

    /**
     * Hace un paso del formulario y, si falla, dice cuál era (para error-makerworld.txt).
     * Parar, descartar y no tener sesión no son errores del paso: pasan tal cual.
     */
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
     * Rellena el formulario de MakerWorld con un modelo y lo guarda como borrador.
     *
     * @param ventana la ventana de Chrome, para traerla a la vista al revisar si está apartada
     * @return el id del borrador en MakerWorld
     * @throws CanceladoException si se pide parar antes de guardar
     */
    private fun subir(page: Page, m: ModelFolder, revisar: Boolean, ventana: Downloader.Ventana?): String {
        val meta = m.meta()
        val red = Red()
        page.onResponse(red)
        try {
            paso("Abrir el formulario de MakerWorld") {
                page.navigate(FORMULARIO)
                esperarVerificacion(page, ventana)
                if (SIN_SESION in page.url()) throw SinSesion()
                val opcion = page.locator("main input[type=radio][value=false]").first()
                try {
                    opcion.waitFor(Locator.WaitForOptions().setTimeout(20_000.0).setState(WaitForSelectorState.ATTACHED))
                } catch (e: TimeoutError) {
                    if (SIN_SESION in page.url()) throw SinSesion()
                    throw IllegalStateException("no se ha cargado el formulario de subida")
                }
                rechazarCookies(page)
            }

            paso("Elegir \"I have STL/CAD files\" (sin perfil de impresión)") {
                page.locator("main label").filter(Locator.FilterOptions().setHasText("STL/CAD")).first().click()
                if (!page.locator("main input[type=radio][value=false]").first().isChecked()) {
                    throw IllegalStateException("no se queda marcada la opción")
                }
                botonExacto(page, "Next Step").click()
            }

            val modelos = m.modelos()
            paso("Archivos del modelo (${modelos.joinToString(", ") { it.fileName.toString() }})") {
                val entrada = page.locator("main div.dropzone input[type=file][accept*='.stl']").first()
                try {
                    entrada.waitFor(Locator.WaitForOptions().setTimeout(20_000.0).setState(WaitForSelectorState.ATTACHED))
                } catch (e: TimeoutError) {
                    throw IllegalStateException("no sale la zona de \"Raw Model Files\". Avisos: ${avisos(page)}")
                }
                val antes = red.firmadas.get()
                entrada.setInputFiles(modelos.toTypedArray())
                // Cada archivo pide al menos una dirección firmada (un .stl, dos: el archivo y su miniatura).
                // Que estén todos se comprueba al final en el borrador guardado (modelFiles).
                esperarSubidas(page, red, "los archivos del modelo", antes + modelos.size, 10 * 60_000L)
            }

            cancelacion.comprobar() // punto seguro: todavía no hay borrador en MakerWorld
            paso("Pasar al paso 2 (MakerWorld crea el borrador)") {
                botonExacto(page, "Next Step").click()
                try {
                    page.waitForURL(BORRADOR, Page.WaitForURLOptions().setTimeout(30_000.0))
                } catch (e: TimeoutError) {
                    throw IllegalStateException("no ha pasado al paso 2. Avisos: ${avisos(page)}")
                }
                borradorEnCurso = BORRADOR.matcher(page.url()).let { if (it.matches()) it.group(1) else null }
                    ?: throw IllegalStateException("no encuentro el id del borrador en ${page.url()}")
                page.locator("main .js-scroll-title input[name=title]").waitFor()
            }
            val id = borradorEnCurso!!

            val portada = m.portada()
            paso("Portadas 4:3 y 3:4 (${portada.fileName})") {
                // En un borrador nuevo, la zona 4:3 es la primera zona de imágenes ("div.dropzone" sin
                // "appUpload") y la 3:4 la que tiene "appUpload" (comprobado el 10/11/2026)
                subirPortada(page, red, page.locator(
                    "main .js-scroll-cover div.dropzone:not(.appUpload) input[type=file], " +
                        "main div.dropzone:not(.appUpload) > input[type=file][accept*='image/jpeg']"
                ).first(), portada, "la portada 4:3")
                subirPortada(page, red, page.locator("main div.dropzone.appUpload input[type=file]").first(),
                    portada, "la portada 3:4")
            }

            val otras = m.imagenes().drop(1)
            if (otras.isNotEmpty()) {
                val fotos = otras.take(CatalogoMakerWorld.MAX_FOTOS)
                if (otras.size > fotos.size) progreso.nota("MakerWorld admite ${CatalogoMakerWorld.MAX_FOTOS} fotos: se suben las primeras")
                paso("Model Pictures (${fotos.size})") {
                    val antes = red.firmadas.get()
                    page.locator("main .js-scroll-designPictures input[type=file]").first().setInputFiles(fotos.toTypedArray())
                    aceptarRecorteSiSale(page)
                    esperarSubidas(page, red, "las fotos", antes + 1, 5 * 60_000L)
                }
            }

            val titulo = recortar(meta.title(), CatalogoMakerWorld.MAX_NOMBRE, "El nombre")
            paso("Nombre") {
                val campo = page.locator("main .js-scroll-title input[name=title]").first()
                campo.fill(titulo)
                if (campo.inputValue() != titulo) throw IllegalStateException("no se ha quedado escrito el nombre")
            }

            val categoria = CatalogoMakerWorld.paraMakerOnline(meta.category())
            paso("Categoría $categoria (de ${meta.categoryOrDefault().joinToString(" > ")} en MakerOnline)") {
                elegirCategoria(page, categoria)
            }

            val etiquetas = etiquetas(meta.tags())
            paso("Etiquetas (${etiquetas.size})") { escribirEtiquetas(page, etiquetas) }

            val licencia = CatalogoMakerWorld.licenciaDeMakerOnline(meta.licenseOrDefault()) ?: run {
                progreso.nota("la licencia \"${meta.licenseOrDefault()}\" no existe en MakerWorld: se pone ${CatalogoMakerWorld.LICENCIA_POR_DEFECTO}")
                CatalogoMakerWorld.LICENCIA_POR_DEFECTO
            }
            paso("Licencia $licencia") { elegirLicencia(page, licencia) }

            paso("Visibilidad privada") {
                page.locator("main .submitPrivate label").filter(Locator.FilterOptions().setHasText(EXACTO_PRIVATE)).first().click()
                if (!page.locator("main .submitPrivate input[type=radio][value=true]").first().isChecked()) {
                    throw IllegalStateException("no se queda marcado Private")
                }
            }

            paso("Descripción") { escribirDescripcion(page, meta.description()) }

            paso("Comprobación del formulario") {
                val problemas = comprobarFormulario(page, titulo, categoria, licencia, etiquetas.size)
                if (problemas.isNotEmpty()) throw IllegalStateException(problemas.joinToString("; "))
            }

            if (revisar) {
                // Si Chrome está fuera de la pantalla, se trae para que mires el formulario y luego se vuelve a apartar
                ventana?.mostrar()
                val guardar = try {
                    revision.confirmar(m.name())
                } finally {
                    ventana?.apartar()
                }
                if (!guardar) throw Descartado()
            }
            cancelacion.comprobar() // último punto seguro

            paso("Guardar como borrador (Save to draft)") { guardarBorrador(page, red, id) }
            paso("Comprobar lo que ha guardado MakerWorld") {
                val problemas = comprobarBorrador(page, id, titulo, categoria, licencia, etiquetas.size, modelos.size)
                if (problemas.isNotEmpty()) {
                    throw IllegalStateException("el borrador $id se ha guardado, pero: ${problemas.joinToString("; ")}")
                }
            }
            return id
        } finally {
            page.offResponse(red)
        }
    }

    /**
     * Si MakerWorld enseña la verificación de Cloudflare ("Just a moment…" / "Un momento…", con el
     * recuadro "Verifique que es un ser humano"; vista el 10/11/2026 al iniciar sesión), no se salta:
     * se trae la ventana de Chrome para que la resuelva la persona y se espera hasta 5 minutos.
     */
    private fun esperarVerificacion(page: Page, ventana: Downloader.Ventana?) {
        if (!enVerificacion(page)) return
        ventana?.mostrar()
        progreso.captcha("MakerWorld (verificación de Cloudflare)", MINUTOS_VERIFICACION)
        try {
            val limite = System.currentTimeMillis() + MINUTOS_VERIFICACION * 60_000L
            while (enVerificacion(page)) {
                cancelacion.comprobar()
                if (System.currentTimeMillis() > limite) {
                    throw IllegalStateException("la verificación de Cloudflare no se ha resuelto en $MINUTOS_VERIFICACION minutos")
                }
                page.waitForTimeout(1000.0)
            }
            page.waitForLoadState()
        } finally {
            progreso.captchaTerminado()
            ventana?.apartar()
        }
    }

    /** Si la página es la de verificación de Cloudflare (su recuadro o su título). */
    private fun enVerificacion(page: Page): Boolean = try {
        page.locator("iframe[src*='challenges.cloudflare.com'], #challenge-form, #challenge-stage").count() > 0 ||
            page.title().let { it.startsWith("Just a moment") || it.startsWith("Un momento") }
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------------ red

    /**
     * Lo que se ve en la red: cada archivo se sube pidiendo antes una dirección firmada
     * (`POST /api/v1/design-user-service/my/upload`, que responde `{cdnPrefix, urls}`) y enviándolo a esa
     * dirección de Amazon S3; al guardar, `PUT /api/v1/design-service/my/draft/<id>` (comprobado el 10/11/2026).
     */
    private class Red : Consumer<Response> {
        /** Direcciones firmadas pedidas (una o más por archivo: un .stl pide también su miniatura). */
        val firmadas = AtomicInteger()
        /** Archivos enviados bien a S3. */
        val hechas = AtomicInteger()
        /** Si alguna subida ha fallado, por qué. */
        val falloSubida = AtomicReference<String?>()
        /** Veces que MakerWorld ha respondido bien a guardar el borrador. */
        val guardados = AtomicInteger()
        /** Si guardar el borrador ha fallado, por qué. */
        val falloGuardado = AtomicReference<String?>()

        override fun accept(r: Response) {
            val url = r.url()
            val metodo = r.request().method()
            when {
                "/api/v1/design-user-service/my/upload" in url && metodo == "POST" ->
                    if (r.ok()) firmadas.incrementAndGet() else falloSubida.set("MakerWorld no da dónde subir (HTTP ${r.status()})")
                "amazonaws.com" in url && metodo != "GET" && metodo != "OPTIONS" ->
                    if (r.ok()) hechas.incrementAndGet() else falloSubida.set("el almacén de archivos responde HTTP ${r.status()}")
                "/api/v1/design-service/my/draft/" in url && metodo == "PUT" ->
                    if (r.ok()) guardados.incrementAndGet() else falloGuardado.set("HTTP ${r.status()}")
            }
        }
    }

    /**
     * Espera a que estén subidos los archivos: al menos [minimoFirmadas] direcciones pedidas, todas ya
     * enviadas, y así unos segundos seguidos (por si falta alguna miniatura por pedir).
     */
    private fun esperarSubidas(page: Page, red: Red, que: String, minimoFirmadas: Int, maxMs: Long) {
        val limite = System.currentTimeMillis() + maxMs
        var quietoDesde = 0L
        while (true) {
            cancelacion.comprobar()
            red.falloSubida.get()?.let { throw IllegalStateException("no se ha podido subir $que: $it") }
            val listo = red.firmadas.get() >= minimoFirmadas && red.hechas.get() >= red.firmadas.get()
            val ahora = System.currentTimeMillis()
            if (!listo) {
                quietoDesde = 0L
            } else if (quietoDesde == 0L) {
                quietoDesde = ahora
            } else if (ahora - quietoDesde >= 2_500L) {
                return
            }
            if (ahora > limite) throw IllegalStateException("no termina de subir $que (${maxMs / 60_000} minutos)")
            page.waitForTimeout(500.0)
        }
    }

    // ------------------------------------------------------------------ campos

    /**
     * Elige la imagen en esa zona de portada y acepta el recorte: la web abre una ventana
     * ("Web/App cover 4:3" o "App cover 3:4") con el botón "Submit" (comprobado el 10/11/2026).
     */
    private fun subirPortada(page: Page, red: Red, entrada: Locator, imagen: Path, que: String) {
        if (entrada.count() == 0) throw IllegalStateException("no encuentro la zona de $que")
        val antes = red.firmadas.get()
        entrada.setInputFiles(imagen)
        val enviar = page.locator(".MuiDialog-root button").filter(Locator.FilterOptions().setHasText(EXACTO_SUBMIT)).first()
        try {
            enviar.waitFor(Locator.WaitForOptions().setTimeout(15_000.0))
        } catch (e: TimeoutError) {
            throw IllegalStateException("no ha salido la ventana de recorte de $que")
        }
        enviar.click()
        try {
            enviar.waitFor(Locator.WaitForOptions().setTimeout(15_000.0).setState(WaitForSelectorState.HIDDEN))
        } catch (e: TimeoutError) {
            throw IllegalStateException("no se cierra la ventana de recorte de $que. Avisos: ${avisos(page)}")
        }
        esperarSubidas(page, red, que, antes + 1, 2 * 60_000L)
    }

    /** Si al elegir fotos sale una ventana de recorte, la acepta tal cual (en las fotos no salía). */
    private fun aceptarRecorteSiSale(page: Page) {
        val enviar = page.locator(".MuiDialog-root button").filter(Locator.FilterOptions().setHasText(EXACTO_SUBMIT)).first()
        try {
            enviar.waitFor(Locator.WaitForOptions().setTimeout(3_000.0))
            enviar.click()
        } catch (_: TimeoutError) {
            // no hay ventana de recorte
        }
    }

    /**
     * Elige la categoría en el autocompletado "Category" (`.js-scroll-categoryId`): se escribe su nombre
     * y se pulsa la opción que se llama exactamente así (solo se eligen las de segundo nivel; comprobado
     * el 10/11/2026). Después se comprueba que el campo se ha quedado con ese nombre.
     */
    private fun elegirCategoria(page: Page, cat: CatalogoMakerWorld.Eleccion) {
        val campo = page.locator("main .js-scroll-categoryId input").first()
        campo.click()
        campo.fill(cat.sub.nombre)
        val opcion = page.locator("[role=listbox] li").filter(Locator.FilterOptions().setHasText(exacto(cat.sub.nombre))).first()
        try {
            opcion.waitFor(Locator.WaitForOptions().setTimeout(10_000.0))
        } catch (e: TimeoutError) {
            throw IllegalStateException("no sale \"${cat.sub.nombre}\" en el desplegable de categorías")
        }
        opcion.click()
        page.waitForTimeout(500.0)
        val puesta = campo.inputValue()
        if (puesta != cat.sub.nombre) throw IllegalStateException("la categoría se ha quedado como \"$puesta\"")
    }

    /** Cuántas etiquetas hay puestas: el título de la sección dice "Tags (n / 50)". */
    private fun etiquetasPuestas(page: Page): Int {
        val texto = page.locator("main .js-scroll-tags").first().innerText()
        return Regex("Tags\\s*\\(\\s*(\\d+)\\s*/").find(texto)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Escribe cada etiqueta y pulsa Enter (así la añade la web; comprobado el 10/11/2026), esperando a
     * que suba la cuenta antes de la siguiente.
     */
    private fun escribirEtiquetas(page: Page, etiquetas: List<String>) {
        val campo = page.locator("main .js-scroll-tags input").first()
        val noAnadidas = ArrayList<String>()
        for (t in etiquetas) {
            cancelacion.comprobar()
            val antes = etiquetasPuestas(page)
            if (antes >= CatalogoMakerWorld.MAX_ETIQUETAS) break
            campo.click()
            campo.fill(t)
            campo.press("Enter")
            val limite = System.currentTimeMillis() + 5_000L
            while (etiquetasPuestas(page) <= antes && System.currentTimeMillis() < limite) page.waitForTimeout(200.0)
            if (etiquetasPuestas(page) <= antes) noAnadidas += t
        }
        page.keyboard().press("Escape") // cierra la lista de sugerencias si se ha quedado abierta
        if (noAnadidas.isNotEmpty()) progreso.nota("no se han añadido estas etiquetas: ${noAnadidas.joinToString(", ")}")
        if (etiquetasPuestas(page) == 0 && etiquetas.isNotEmpty()) throw IllegalStateException("no se ha añadido ninguna etiqueta")
    }

    /**
     * Elige la licencia en su desplegable (el que guarda `input[name=license]`): se abre y se pulsa la
     * opción con ese `data-value`. Las preguntas de encima (adaptaciones, uso comercial…) las ajusta
     * la web sola según la licencia (comprobado el 10/11/2026 con CC0: Sí/Sí/Sí).
     */
    private fun elegirLicencia(page: Page, valor: String) {
        val guardada = page.locator("main input[name=license]").first()
        if (guardada.inputValue() == valor) return
        guardada.locator("xpath=..").locator(".MuiSelect-select").first().click()
        val opcion = page.locator("[role=listbox] [role=option][data-value=\"$valor\"]").first()
        try {
            opcion.waitFor(Locator.WaitForOptions().setTimeout(10_000.0))
        } catch (e: TimeoutError) {
            throw IllegalStateException("no sale la licencia \"$valor\" en el desplegable")
        }
        opcion.click()
        page.waitForTimeout(500.0)
        if (guardada.inputValue() != valor) throw IllegalStateException("la licencia se ha quedado como \"${guardada.inputValue()}\"")
    }

    /**
     * Escribe la descripción en su editor (CKEditor 5): se mete de golpe con el propio editor, un
     * párrafo por línea, como en Creality (tecleando, el editor convierte las líneas que empiezan por
     * "- " en listas). Comprobado el 10/11/2026 que así lo guarda en el borrador (`summary`).
     */
    private fun escribirDescripcion(page: Page, texto: String) {
        val html = texto.split(Regex("\\R")).joinToString("") { linea ->
            if (linea.isBlank()) "<p>&nbsp;</p>" else "<p>" + escaparHtml(linea) + "</p>"
        }
        val puesto = page.evaluate(
            """html => {
              const e = document.querySelector('main .js-scroll-summary .ck-editor__editable') || document.querySelector('main .ck-editor__editable');
              if (!e || !e.ckeditorInstance) return false;
              e.ckeditorInstance.setData(html);
              return true;
            }""",
            html,
        ) == true
        if (!puesto) throw IllegalStateException("no encuentro el editor de la descripción")
        if (textoDescripcion(page).isNullOrBlank()) throw IllegalStateException("no se ha quedado escrita la descripción")
    }

    private fun escaparHtml(t: String): String =
        t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** El texto de la descripción, leído del propio editor. */
    private fun textoDescripcion(page: Page): String? = page.evaluate(
        """() => {
          const e = document.querySelector('main .js-scroll-summary .ck-editor__editable') || document.querySelector('main .ck-editor__editable');
          return e && e.ckeditorInstance ? e.innerText : null;
        }"""
    ) as String?

    /** Lo comprueba todo antes de guardar, leyendo el formulario tal como está. Vacío si todo está bien. */
    private fun comprobarFormulario(
        page: Page, titulo: String, cat: CatalogoMakerWorld.Eleccion, licencia: String, etiquetas: Int,
    ): List<String> {
        val problemas = ArrayList<String>()
        val nombre = page.locator("main .js-scroll-title input[name=title]").first().inputValue()
        if (nombre != titulo) problemas += "el nombre no es \"$titulo\" (es \"$nombre\")"
        val categoria = page.locator("main .js-scroll-categoryId input").first().inputValue()
        if (categoria != cat.sub.nombre) problemas += "la categoría no es \"${cat.sub.nombre}\" (es \"$categoria\")"
        if (etiquetas > 0 && etiquetasPuestas(page) == 0) problemas += "no hay etiquetas"
        val lic = page.locator("main input[name=license]").first().inputValue()
        if (lic != licencia) problemas += "la licencia no es $licencia (es $lic)"
        if (!page.locator("main .submitPrivate input[type=radio][value=true]").first().isChecked()) problemas += "no está como privado"
        if (textoDescripcion(page).isNullOrBlank()) problemas += "la descripción está vacía"
        return problemas
    }

    /**
     * Pulsa "Save to draft" y espera a que MakerWorld responda bien a `PUT /api/v1/design-service/my/draft/<id>`
     * (comprobado el 10/11/2026: responde 200 sin nada más).
     */
    private fun guardarBorrador(page: Page, red: Red, id: String) {
        val antes = red.guardados.get()
        red.falloGuardado.set(null)
        botonExacto(page, "Save to draft").click()
        val limite = System.currentTimeMillis() + 60_000L
        while (red.guardados.get() <= antes) {
            red.falloGuardado.get()?.let { throw IllegalStateException("MakerWorld no ha guardado el borrador $id ($it)") }
            if (System.currentTimeMillis() > limite) {
                throw IllegalStateException("he pulsado \"Save to draft\", pero MakerWorld no ha respondido en 60 s. " +
                    "Mira el borrador ${direccionBorrador(id)} antes de repetir")
            }
            page.waitForTimeout(500.0)
        }
    }

    /**
     * Lee el borrador de MakerWorld (`GET /api/v1/design-service/my/draft/<id>`, desde la propia página para
     * que vaya con la sesión) y comprueba lo que ha guardado. Vacío si todo está bien.
     */
    private fun comprobarBorrador(
        page: Page, id: String, titulo: String, cat: CatalogoMakerWorld.Eleccion, licencia: String,
        etiquetas: Int, archivos: Int,
    ): List<String> {
        val texto = page.evaluate(
            """async id => {
              const r = await fetch('/api/v1/design-service/my/draft/' + id, {credentials: 'include'});
              return r.ok ? await r.text() : 'HTTP ' + r.status;
            }""",
            id,
        ) as String
        val j: JsonNode = try {
            JSON.readTree(texto)
        } catch (_: Exception) {
            return listOf("no he podido leer el borrador ($texto)")
        }
        val problemas = ArrayList<String>()
        if (j.path("title").asText() != titulo) problemas += "el nombre guardado es \"${j.path("title").asText()}\""
        if (j.path("categoryId").asInt() != cat.sub.id) problemas += "la categoría guardada es ${j.path("categoryId").asText()}"
        if (j.path("license").asText() != licencia) problemas += "la licencia guardada es ${j.path("license").asText()}"
        if (etiquetas > 0 && j.path("tags").size() == 0) problemas += "no ha guardado las etiquetas"
        if (j.path("summary").asText().isBlank()) problemas += "no ha guardado la descripción"
        if (!j.path("designSetting").path("submitAsPrivate").asBoolean(false)) problemas += "no ha guardado que sea privado"
        if (j.path("modelFiles").size() < archivos) problemas += "solo tiene ${j.path("modelFiles").size()} de $archivos archivos"
        if (j.path("cover").asText().isBlank()) problemas += "no tiene la portada 4:3"
        if (j.path("coverPortrait").asText().isBlank()) problemas += "no tiene la portada 3:4"
        return problemas
    }

    // ------------------------------------------------------------------ utilidades

    /** El botón de `main` que dice exactamente ese texto ("Next Step", "Save to draft"…). */
    private fun botonExacto(page: Page, texto: String): Locator =
        page.locator("main button").filter(Locator.FilterOptions().setHasText(exacto(texto))).first()

    /** Aviso de cookies: "Reject All" (comprobado el 10/11/2026 en la primera visita). */
    private fun rechazarCookies(page: Page) {
        val boton = page.locator("button").filter(Locator.FilterOptions().setHasText(exacto("Reject All"))).first()
        try {
            boton.waitFor(Locator.WaitForOptions().setTimeout(3_000.0))
            boton.click()
        } catch (_: TimeoutError) {
            // no hay aviso de cookies
        }
    }

    /** Los avisos de error que enseña la página, para el mensaje de error. */
    private fun avisos(page: Page): String {
        val textos = page.locator("[role=alert], .MuiFormHelperText-root.Mui-error, .MuiSnackbar-root").allInnerTexts()
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (textos.isEmpty()) "(ninguno)" else textos.joinToString(" | ")
    }

    /** Como [avisos], pero sin fallar si la página ya no responde. */
    private fun avisosSeguros(page: Page): String = try {
        avisos(page)
    } catch (_: Exception) {
        "(no se han podido leer)"
    }

    private fun recortar(texto: String, max: Int, que: String): String {
        val t = texto.trim()
        if (t.length <= max) return t
        progreso.nota("$que tiene más de $max caracteres en MakerWorld: se corta")
        return t.take(max).trim()
    }

    /** Separa por comas, quita vacías y repetidas, corta a 100 caracteres y se queda con 50. */
    private fun etiquetas(tags: List<String>): List<String> {
        val max = CatalogoMakerWorld.MAX_LETRAS_ETIQUETA
        val separadas = tags.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
        val limpias = separadas.map { if (it.length > max) it.take(max).trim() else it }.distinctBy { it.lowercase() }
        if (limpias.size > CatalogoMakerWorld.MAX_ETIQUETAS) {
            progreso.nota("MakerWorld admite ${CatalogoMakerWorld.MAX_ETIQUETAS} etiquetas: se suben las ${CatalogoMakerWorld.MAX_ETIQUETAS} primeras")
        }
        return limpias.take(CatalogoMakerWorld.MAX_ETIQUETAS)
    }

    private fun captura(page: Page, destino: Path) {
        try {
            page.screenshot(Page.ScreenshotOptions().setPath(destino).setFullPage(true))
        } catch (_: Exception) {
            // si la página ya no responde, seguimos sin captura
        }
    }

    companion object {
        const val WEB = "https://makerworld.com"
        /** El formulario de subida en inglés (los textos que se buscan son los de la web en inglés). */
        private const val FORMULARIO = "$WEB/en/my/models/publish"
        /** Sin sesión, el formulario manda aquí (comprobado el 10/11/2026). */
        private const val SIN_SESION = "/sign-in"
        /** Lo que se espera a que la persona resuelva la verificación de Cloudflare. */
        private const val MINUTOS_VERIFICACION = 5L
        /** Al pasar al paso 2, la página salta al borrador que acaba de crear. */
        private val BORRADOR: Pattern = Pattern.compile(".*/my/models/drafts/(\\d+)/edit.*")
        private val EXACTO_SUBMIT = exacto("Submit")
        private val EXACTO_PRIVATE = exacto("Private")

        /** Dentro de la carpeta del modelo: lo subido a MakerWorld (id, dirección y fecha). */
        const val REGISTRO = "subido-makerworld.txt"
        const val CAPTURA_OK = "resultado-makerworld.png"
        const val CAPTURA_ERROR = "error-makerworld.png"
        /** Dentro de la carpeta del modelo: en qué paso falló y por qué. */
        const val ERROR_TXT = "error-makerworld.txt"

        private val JSON = ObjectMapper()
        private val FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /** Un texto que tiene que ser exactamente ese (sin contar espacios alrededor). */
        private fun exacto(texto: String): Pattern = Pattern.compile("^\\s*" + Pattern.quote(texto) + "\\s*$")

        /** Dónde se edita un borrador en MakerWorld. */
        @JvmStatic
        fun direccionBorrador(id: String): String = "$WEB/en/my/models/drafts/$id/edit"

        /** Si esa carpeta ya se subió a MakerWorld. */
        @JvmStatic
        fun yaSubido(carpeta: Path): Boolean = Files.exists(carpeta.resolve(REGISTRO))

        private fun anotarSubido(carpeta: Path, id: String) {
            Files.writeString(
                carpeta.resolve(REGISTRO),
                "$id\t${direccionBorrador(id)}\t${LocalDateTime.now().format(FECHA)}\n",
            )
        }

        /** Escribe error-makerworld.txt: fecha, paso, motivo, el borrador a medias y los avisos de la página. */
        private fun anotarError(carpeta: Path, e: Exception, avisos: String, borrador: String?) {
            val texto = buildString {
                append(LocalDateTime.now().format(FECHA)).append('\n')
                if (e is ErrorEnPaso) {
                    append("Paso: ").append(e.paso).append('\n')
                    append("Motivo: ").append(e.motivo).append('\n')
                } else {
                    append("Motivo: ").append(e.message ?: e.toString()).append('\n')
                }
                if (borrador != null) {
                    append("Borrador a medias en MakerWorld (complétalo o bórralo a mano): ")
                        .append(direccionBorrador(borrador)).append('\n')
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

        @JvmStatic
        fun main(args: Array<String>) {
            val lista = args.toList()
            val op = Opciones(
                revisar = "--revisar" in lista,
                carpetas = lista.filter { !it.startsWith("--") },
                esperar = "--esperar" in lista,
            )
            val rutas = Rutas.carpetaActual()
            val progreso = ProgresoConsola()
            val cancelacion = Cancelacion()
            try {
                AgentLock.acquire(rutas.candado, "Subir a MakerWorld", op.esperar, progreso).use {
                    EntradaConsola.iniciar(cancelacion, progreso)
                    SubidorMakerWorld(rutas, progreso, cancelacion, RevisionConsola(progreso, cancelacion)).ejecutar(op)
                }
            } catch (e: AgenteException) {
                progreso.error(e.message ?: e.toString())
                exitProcess(e.codigoSalida)
            }
            exitProcess(0)
        }
    }
}
