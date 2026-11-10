package dev.jefe.makeronline

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Response
import com.microsoft.playwright.TimeoutError
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
 * Sube a Creality Cloud las carpetas de subidas/pendientes/, con los mismos archivos y textos que
 * se usan para MakerOnline. Todo lo que usa de la web está en el documento de contexto de Creality
 * (04-contexto-creality-cloud.md), comprobado el 09/11/2026 en la página y en su código.
 *
 * Cada modelo se sube como **privado** ("Private" / "Solo yo"): hace de borrador, igual que en
 * MakerOnline. Lo revisas en Creality y lo haces público tú.
 *
 *  - El .3mf (el mismo de MakerOnline) se sube con el título del modelo como nombre de archivo,
 *    porque Creality lo enseña, y Creality lo convierte sola a una impresora suya.
 *  - La categoría sale de la de MakerOnline (ver CatalogoCreality); la licencia, siempre CC0.
 *  - Las dos portadas (4:3 y 3:4) son la portada de la carpeta (la foto del ajuste de impresión la
 *    pone la web con la portada; si no, también se sube).
 *  - Cada campo se busca por su sección del formulario (clases como `.model-name-content`), no por
 *    sus textos, así que da igual que la web salga en inglés o en español.
 *  - Antes de enviar se comprueba todo el formulario; si algo no está como debe, no se envía.
 *
 * **De momento no mueve las carpetas** (se quedan en pendientes/, para MakerOnline): apunta lo
 * subido en `subido-creality.txt` dentro de la carpeta, y las que lo tienen no se vuelven a subir.
 * Si algo falla, deja en la carpeta `error-creality.txt` (en qué paso y por qué) y
 * `error-creality.png` (captura de la página).
 *
 * Programa de terminal (configuración "Subir a Creality (revisar)"):
 *   --revisar   antes de enviar cada modelo, se para para que mires el formulario:
 *               ENTER lo envía; "no" + ENTER no lo envía.
 *   --esperar   si otro programa del agente está en marcha, espera a que termine.
 *   nombre ...  solo esas carpetas de pendientes/ (sin nombres, todas).
 * Para pararlo sin dejar nada a medias: "parar" + ENTER.
 */
class SubidorCreality(
    private val rutas: Rutas,
    private val progreso: Progreso,
    private val cancelacion: Cancelacion,
    private val revision: Revision,
) {

    /**
     * @property revisar   se para antes de enviar cada modelo para que lo mires
     * @property carpetas  solo estas carpetas de pendientes/; vacía = todas
     * @property esperar   si otro programa del agente está en marcha, espera a que termine
     * @property verChrome true = Chrome a la vista todo el rato (en la terminal, siempre); false = fuera
     *                     de la pantalla, y aparece solo para revisar cada modelo (como el Uploader)
     */
    data class Opciones(
        val revisar: Boolean,
        val carpetas: List<String>,
        val esperar: Boolean,
        val verChrome: Boolean = true,
    )

    /**
     * @property subidos     enviados bien (cada uno con su subido-creality.txt)
     * @property yaSubidos   saltados porque ya tenían subido-creality.txt
     * @property conError    fallaron (con error-creality.txt y error-creality.png en su carpeta)
     * @property sinSubir    no válidos, sin .3mf, descartados al revisar o sin hacer porque se ha parado
     * @property parado      true si se ha parado a petición de la persona
     */
    data class Resumen(val subidos: Int, val yaSubidos: Int, val conError: Int, val sinSubir: Int, val parado: Boolean)

    /** La persona ha decidido no enviar este modelo al revisarlo. */
    private class Descartado : RuntimeException("descartado al revisar")

    /** No hay sesión de Creality en Chrome (o ha caducado): no se sigue con ningún modelo más. */
    private class SinSesion : RuntimeException("no hay sesión de Creality Cloud")

    /** Algo ha fallado en un paso concreto del formulario: el mensaje dice cuál. */
    class ErrorEnPaso(val paso: String, val motivo: String, causa: Throwable?) :
        RuntimeException("$paso: $motivo", causa)

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

        // 1) Validar antes de abrir el navegador
        val listos = ArrayList<ModelFolder>()
        var yaSubidos = 0
        var sinSubir = 0
        for (dir in carpetas) {
            if (yaSubido(dir)) {
                progreso.saltado("${dir.fileName}: ya está en Creality ($REGISTRO), no se vuelve a subir")
                yaSubidos++
                continue
            }
            try {
                val m = ModelFolder.load(dir)
                if (m.perfil() == null) {
                    progreso.error("${dir.fileName}: de momento a Creality solo se sube con un .3mf (esta carpeta solo tiene .stl)")
                    sinSubir++
                    continue
                }
                listos += m
                progreso.correcto("${dir.fileName} validado")
            } catch (e: Exception) {
                progreso.error("${dir.fileName}:\n   ${(e.message ?: e.toString()).replace("\n", "\n   ")}")
                sinSubir++
            }
        }
        if (listos.isEmpty()) {
            progreso.info("Nada que subir a Creality.")
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
                    try {
                        val id = subir(page, m, op.revisar, ventana)
                        anotarSubido(m.dir(), id)
                        captura(page, m.dir().resolve(CAPTURA_OK))
                        borrarErroresAnteriores(m.dir())
                        progreso.correcto("Subido a Creality (privado): $WEB/model-detail/$id")
                        ok++
                    } catch (_: CanceladoException) {
                        parado = true
                        sinSubir += listos.size - i
                        progreso.info("⏹ ${m.name()} no se ha enviado a Creality.")
                    } catch (_: Descartado) {
                        sinSubir++
                        progreso.saltado("No se envía ${m.name()} a Creality.")
                    } catch (_: SinSesion) {
                        // No es culpa de la carpeta: no se apunta error en ella y se para todo
                        throw AgenteException(
                            "No hay sesión de Creality Cloud en Chrome (o ha caducado). Vuelve a iniciarla en la " +
                                "pantalla Sesión. " + (if (ok > 0) "Antes se han subido bien $ok." else "No se ha subido nada a Creality.")
                        )
                    } catch (e: Exception) {
                        val mensaje = e.message?.lineSequence()?.firstOrNull() ?: e.toString()
                        progreso.error("Error al subir ${m.name()} a Creality en $mensaje")
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
        progreso.info("\n${if (parado) "Parado" else "Terminado"}: $ok subidos a Creality, $ko con error." +
            (if (yaSubidos > 0) " $yaSubidos ya estaban." else "") +
            (if (sinSubir > 0) " $sinSubir sin subir." else ""))
        return Resumen(ok, yaSubidos, ko, sinSubir, parado)
    }

    /**
     * Hace un paso del formulario y, si falla, dice cuál era (para error-creality.txt).
     * Parar y descartar no son errores: pasan tal cual.
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
     * Rellena el formulario de Creality con un modelo y lo envía como privado.
     *
     * @param ventana la ventana de Chrome, para traerla a la vista al revisar si está apartada (o null)
     * @return el id del modelo en Creality
     * @throws CanceladoException si se pide parar antes de enviar (no se envía nada)
     */
    private fun subir(page: Page, m: ModelFolder, revisar: Boolean, ventana: Downloader.Ventana?): String {
        val meta = m.meta()
        val temporal = Files.createTempDirectory("jujamaker-creality")
        val red = Red()
        page.onResponse(red)
        try {
            paso("Abrir el formulario de Creality") {
                page.navigate("$WEB/create-model")
                val zona3mf = page.locator(".creality-print-content .el-upload, .upload-3mf-container .el-upload").first()
                try {
                    zona3mf.waitFor(Locator.WaitForOptions().setTimeout(20_000.0))
                } catch (e: TimeoutError) {
                    if (haySesion(page) == false) throw SinSesion()
                    throw IllegalStateException("no se ha cargado el formulario de subida")
                }
                if (haySesion(page) == false) throw SinSesion()
                rechazarCookies(page)
            }

            val original = m.perfil()
            paso("Subir el .3mf (${original.fileName}) y esperar a que Creality lo convierta") {
                val copia = temporal.resolve(nombreArchivo(meta.title()) + ".3mf")
                Files.copy(original, copia, StandardCopyOption.REPLACE_EXISTING)
                progreso.nota("se sube como \"${copia.fileName}\"")
                page.locator(".upload-3mf-container input[type=file]").first().setInputFiles(copia)
                esperarConversion(page, red)
            }

            cancelacion.comprobar() // punto seguro: aún no se ha rellenado nada
            paso("Pasar al paso 2") {
                page.locator("footer.bottom-btn-group .step-one-btn").first().click()
                try {
                    campoNombre(page).waitFor(Locator.WaitForOptions().setTimeout(20_000.0))
                } catch (e: TimeoutError) {
                    throw IllegalStateException("no ha pasado al paso 2. Avisos: ${avisos(page)}")
                }
            }

            // Portadas primero: al subirlas, la web puede rellenar sola categoría, etiquetas y
            // descripción; lo nuestro se escribe después y lo pisa
            val portada = m.portada()
            paso("Portadas (${portada.fileName})") {
                val entradas = page.locator(".model-cover-content .upload-model-card_wrapper").first().locator("input[type=file]")
                subirImagen(page, red, entradas.nth(0), portada, "la portada 4:3")
                subirImagen(page, red, entradas.nth(1), portada, "la portada 3:4")
                page.waitForTimeout(3000.0)
            }

            val titulo = recortar(meta.title(), CatalogoCreality.MAX_NOMBRE, "El nombre")
            paso("Nombre") {
                val campo = campoNombre(page)
                campo.fill(titulo)
                if (campo.inputValue() != titulo) throw IllegalStateException("no se ha quedado escrito el nombre")
            }

            val categoria = CatalogoCreality.paraMakerOnline(meta.category())
            paso("Categoría $categoria (de ${meta.categoryOrDefault().joinToString(" > ")} en MakerOnline)") {
                elegirCategoria(page, categoria)
            }

            val etiquetas = etiquetas(meta.tags())
            paso("Etiquetas (${etiquetas.size})") { escribirEtiquetas(page, etiquetas) }

            paso("Licencia CC0 (Sí a las tres preguntas)") {
                val si = page.locator(".model-license-content label.el-radio:has(input.el-radio__original[value='1'])")
                val n = si.count()
                if (n != 3) throw IllegalStateException("esperaba 3 preguntas de licencia y hay $n")
                for (i in 0 until n) marcar(si.nth(i))
            }
            paso("Visibilidad privada") {
                marcar(page.locator(".model-share-content label.el-radio:has(input.el-radio__original[value='0'])").first())
            }
            if (page.locator(".material-box label.el-radio").count() > 0) {
                paso("Lista de materiales: No") {
                    marcar(page.locator(".material-box label.el-radio:has(input.el-radio__original[value='false'])").first())
                }
            }

            paso("Descripción") { escribirDescripcion(page, meta.description()) }

            paso("Ajuste de impresión") {
                val nombreAjuste = page.locator(".print-file-name-content input.el-input__inner").first()
                if (nombreAjuste.count() > 0) nombreAjuste.fill(titulo)
                else progreso.nota("no encuentro el nombre del ajuste de impresión: se queda el que ponga la web")
                if (fotosDelAjuste(page) == 0) {
                    val entrada = page.locator(".model-cover-content3 .upload-card_wrapper input[type=file]").first()
                    subirImagen(page, red, entrada, portada, "la foto del ajuste de impresión", recorteObligatorio = false)
                }
            }

            paso("Casilla de derechos de autor") { marcarDerechosDeAutor(page) }

            paso("Comprobación final del formulario") {
                val problemas = comprobarFormulario(page, titulo, etiquetas.size)
                if (problemas.isNotEmpty()) throw IllegalStateException(problemas.joinToString("; "))
            }

            if (revisar) {
                // Si Chrome está fuera de la pantalla, se trae para que mires el formulario y luego se vuelve a apartar
                ventana?.mostrar()
                val enviar = try {
                    revision.confirmar(m.name())
                } finally {
                    ventana?.apartar()
                }
                if (!enviar) throw Descartado()
            }
            cancelacion.comprobar() // último punto seguro
            return paso("Enviar (privado)") { enviar(page, red) }
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
     * Lo que se ve en la red: cómo va la conversión del .3mf, cuántas imágenes han terminado de
     * subir y la respuesta al enviar. La respuesta se lee en cuanto llega (luego Chrome puede no tenerla).
     */
    private class Red : Consumer<Response> {
        /** El último convert3mfDetail.result.convert3mfTaskDetail. */
        val conversion = AtomicReference<JsonNode?>()
        /** Si convert3mfInsert o convert3mfDetail responden con error, su mensaje. */
        val errorConversion = AtomicReference<String?>()
        /** Imágenes que han terminado de subir (el POST final de la subida por partes a Aliyun). */
        val imagenesSubidas = AtomicInteger(0)
        /** La respuesta de modelGroupCreate (al enviar). */
        val guardado = AtomicReference<JsonNode?>()
        /** Si modelGroupCreate no ha respondido bien: el código HTTP o que no se ha podido leer. */
        val errorGuardado = AtomicReference<String?>()
        /** Cuándo cambió por última vez algo de la conversión. */
        @Volatile var ultimoCambio = System.currentTimeMillis()

        override fun accept(r: Response) {
            val url = r.url()
            when {
                "/api/cxy/v3/model/convert3mfInsert" in url || "/api/cxy/v3/model/convert3mfDetail" in url -> {
                    val j = leerJson(r) ?: return
                    if (j.path("code").asInt(-1) != 0) {
                        errorConversion.set(j.path("msg").asText("código ${j.path("code").asText("?")}"))
                        return
                    }
                    val detalle = j.path("result").path("convert3mfTaskDetail")
                    if (!detalle.isMissingNode) {
                        val antes = conversion.getAndSet(detalle)
                        if (antes == null || antes.path("progress").asInt() != detalle.path("progress").asInt()) {
                            ultimoCambio = System.currentTimeMillis()
                        }
                    } else {
                        ultimoCambio = System.currentTimeMillis()
                    }
                }
                "/api/cxy/v3/model/modelGroupCreate" in url -> {
                    val j = leerJson(r)
                    if (j == null) errorGuardado.set(if (r.ok()) "respuesta que no se entiende" else "HTTP ${r.status()}")
                    else guardado.set(j)
                }
                // Subida por partes a Aliyun (la hace la web con su librería): iniciar (POST ?uploads),
                // partes (PUT) y terminar (POST ?uploadId=…). Comprobado en el diagnóstico y en su código.
                "aliyuncs.com" in url && "/crealityCloud/upload/" in url && r.request().method() == "POST"
                    && "uploadId=" in url && r.ok() -> imagenesSubidas.incrementAndGet()
            }
        }

        private fun leerJson(r: Response): JsonNode? = try {
            JSON.readTree(r.text())
        } catch (_: Exception) {
            null
        }
    }

    /** Espera a que Creality acabe de convertir el .3mf (convert3mfState 2). */
    private fun esperarConversion(page: Page, red: Red) {
        val limite = System.currentTimeMillis() + 10 * 60_000L
        var ultimoAviso = -1
        while (true) {
            cancelacion.comprobar()
            page.waitForTimeout(1000.0)
            red.errorConversion.get()?.let { throw IllegalStateException("Creality no ha podido convertir el .3mf: $it") }
            val d = red.conversion.get()
            if (d != null) {
                if (d.path("sliceFailType").asInt(0) != 0) {
                    throw IllegalStateException("Creality no ha podido convertir el .3mf (sliceFailType ${d.path("sliceFailType").asInt()})")
                }
                val porcentaje = d.path("progress").asInt(0)
                if (d.path("convert3mfState").asInt(0) == 2) {
                    progreso.nota("convertido para ${d.path("printer").asText("una impresora Creality")}")
                    return
                }
                if (porcentaje / 25 != ultimoAviso) {
                    ultimoAviso = porcentaje / 25
                    progreso.nota("conversión: $porcentaje %")
                }
            }
            val aviso = page.locator(".el-message--error").locator("visible=true")
            if (aviso.count() > 0) throw IllegalStateException("Creality dice: ${aviso.first().innerText().trim()}")
            if (System.currentTimeMillis() - red.ultimoCambio > 3 * 60_000L) {
                throw IllegalStateException(
                    if (d == null) "la subida del .3mf no ha empezado a convertirse en 3 minutos"
                    else "la conversión del .3mf lleva 3 minutos sin avanzar"
                )
            }
            if (System.currentTimeMillis() > limite) throw IllegalStateException("la conversión del .3mf ha tardado más de 10 minutos")
        }
    }

    // ------------------------------------------------------------------ campos

    private fun campoNombre(page: Page): Locator =
        page.locator(".model-name-content input.el-input__inner").first()

    /**
     * Elige la imagen en esa zona, pulsa el botón de la ventana de recorte y espera a que termine
     * de subir. La ventana es un diálogo con el botón dentro de `.cropper-btn-container` ("OK"/"Confirm").
     */
    private fun subirImagen(page: Page, red: Red, entrada: Locator, imagen: Path, que: String, recorteObligatorio: Boolean = true) {
        val antes = red.imagenesSubidas.get()
        entrada.setInputFiles(imagen)
        val boton = page.locator(".cropper-btn-container button").locator("visible=true").first()
        try {
            boton.waitFor(Locator.WaitForOptions().setTimeout(15_000.0))
            boton.click()
        } catch (e: TimeoutError) {
            if (recorteObligatorio) throw IllegalStateException("no ha salido la ventana de recorte de $que")
            progreso.nota("no ha salido la ventana de recorte de $que (sigo)")
        }
        val limite = System.currentTimeMillis() + 120_000L
        while (red.imagenesSubidas.get() <= antes) {
            cancelacion.comprobar()
            val error = page.locator(".el-message--error").locator("visible=true")
            if (error.count() > 0) throw IllegalStateException("al subir $que, Creality dice: ${error.first().innerText().trim()}")
            if (System.currentTimeMillis() > limite) throw IllegalStateException("no termina de subir $que (2 minutos)")
            page.waitForTimeout(500.0)
        }
    }

    /** Cuántas fotos tiene ya el ajuste de impresión: el título de su sección dice "(n/9)". */
    private fun fotosDelAjuste(page: Page): Int {
        val texto = page.locator(".model-cover-content3").first().let { if (it.count() > 0) it.innerText() else "" }
        return Regex("\\((\\d+)\\s*/\\s*\\d+\\)").find(texto)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Elige la categoría con el buscador del desplegable (comprobado el 09/11/2026): se escribe la
     * ruta completa tal como la enseña la web ("Miniatures / Characters & Creatures") y se pulsa la
     * única sugerencia que coincide. Así no depende de pasar el ratón por el desplegable (que cambia
     * de grupo con solo rozar otra opción) ni de desplazarse dentro de él.
     *
     * Después se comprueba en el propio desplegable que ha quedado marcada esa subcategoría dentro
     * de ese grupo (el texto del campo no sirve: es el que acabamos de escribir).
     */
    private fun elegirCategoria(page: Page, cat: CatalogoCreality.Eleccion) {
        val (grupo, sub) = nombresEnLaWeb(page, cat)
        val esperado = "$grupo / $sub"
        val campo = page.locator(".model-category-content .el-cascader input.el-input__inner").first()
        val sugerencia = page.locator(".el-cascader__suggestion-list").locator("visible=true")
            .getByText(esperado, Locator.GetByTextOptions().setExact(true)).first()
        var visto: List<String?> = emptyList()
        for (intento in 1..3) {
            campo.scrollIntoViewIfNeeded()
            campo.click()
            campo.fill(esperado)
            try {
                sugerencia.waitFor(Locator.WaitForOptions().setTimeout(8_000.0))
                sugerencia.click()
            } catch (e: TimeoutError) {
                page.keyboard().press("Escape")
                if (intento == 3) throw IllegalStateException("el buscador de categorías no ofrece \"$esperado\"")
                continue
            }
            page.waitForTimeout(800.0)
            // Al elegir, la web cierra el desplegable sola; por si acaso:
            if (page.locator(".category-cascader").locator("visible=true").count() > 0) page.keyboard().press("Escape")
            visto = categoriaMarcada(page)
            if (visto == listOf(grupo, sub)) return
        }
        throw IllegalStateException("no se ha quedado elegida la categoría \"$esperado\" (está marcada: ${visto.filterNotNull().joinToString(" / ").ifEmpty { "ninguna" }})")
    }

    /**
     * Lo que está marcado de verdad en el desplegable de categorías: [grupo, subcategoría].
     * El grupo es la opción del primer nivel que está en el camino abierto; la subcategoría, la del
     * radio marcado (el desplegable sigue en la página aunque esté cerrado).
     */
    private fun categoriaMarcada(page: Page): List<String?> {
        @Suppress("UNCHECKED_CAST")
        val r = page.evaluate(
            """() => {
              const menus = [...document.querySelectorAll('.category-cascader .el-cascader-menu')];
              const grupo = menus[0] && menus[0].querySelector('li.el-cascader-node.in-active-path');
              const radio = document.querySelector('.category-cascader li.el-cascader-node .el-radio.is-checked');
              const sub = radio && radio.closest('li');
              return [grupo ? grupo.textContent.trim() : null, sub ? sub.textContent.trim() : null];
            }"""
        ) as List<String?>
        return r
    }

    /**
     * Los nombres del grupo y la subcategoría tal como los escribe la web, en su idioma: se sacan de
     * sus propias categorías (`__NUXT__.data['categoryList-model']`) por id. Si no están, en inglés o
     * en español según la dirección.
     */
    private fun nombresEnLaWeb(page: Page, cat: CatalogoCreality.Eleccion): Pair<String, String> {
        @Suppress("UNCHECKED_CAST")
        val nombres = try {
            page.evaluate(
                """ids => {
                  const d = window.__NUXT__ && window.__NUXT__.data;
                  const k = d && Object.keys(d).find(x => x.startsWith('categoryList'));
                  if (!k || !d[k]) return null;
                  const lista = Array.isArray(d[k]) ? d[k] : Object.values(d[k]);
                  const g = lista.find(c => c && String(c.id) === ids[0]);
                  const s = g && (g.children || []).find(c => String(c.id) === ids[1]);
                  return g && s ? [g.name, s.name] : null;
                }""",
                listOf(cat.grupo.id, cat.sub.id),
            ) as List<String>?
        } catch (_: Exception) {
            null
        }
        if (nombres != null && nombres.size == 2) return nombres[0] to nombres[1]
        val espanol = "/es/" in page.url()
        return cat.grupo.nombre(espanol) to cat.sub.nombre(espanol)
    }

    /**
     * Quita las etiquetas que haya (las puede poner la web sola) y escribe las nuestras.
     * Cada etiqueta, al pulsar Enter, la web la comprueba con su servidor antes de añadirla: se
     * espera a que aparezca antes de escribir la siguiente (si no, la web borra lo que se está escribiendo).
     */
    private fun escribirEtiquetas(page: Page, etiquetas: List<String>) {
        val seccion = page.locator(".model-tag-content")
        val puestas = seccion.locator(".create-model-show-tags .el-tag")
        val cerrar = seccion.locator(".create-model-show-tags .el-tag__close")
        var vueltas = 0
        while (cerrar.count() > 0 && vueltas++ < 40) {
            cerrar.first().click()
            page.waitForTimeout(150.0)
        }
        val campo = seccion.locator("input.el-input__inner").first()
        val noAnadidas = ArrayList<String>()
        for (t in etiquetas) {
            cancelacion.comprobar()
            val antes = puestas.count()
            if (antes >= CatalogoCreality.MAX_ETIQUETAS) break
            campo.click()
            campo.fill(t)
            campo.press("Enter")
            val limite = System.currentTimeMillis() + 6_000L
            while (puestas.count() <= antes && System.currentTimeMillis() < limite) page.waitForTimeout(200.0)
            if (puestas.count() <= antes) {
                val aviso = page.locator(".el-message--warning, .el-message--error").locator("visible=true")
                noAnadidas += if (aviso.count() > 0) "$t (${aviso.first().innerText().trim()})" else t
            }
        }
        page.keyboard().press("Escape") // cierra la lista de sugerencias si se ha quedado abierta
        if (noAnadidas.isNotEmpty()) progreso.nota("no se han añadido estas etiquetas: ${noAnadidas.joinToString(", ")}")
        if (puestas.count() == 0) throw IllegalStateException("no se ha añadido ninguna etiqueta")
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
     * Escribe la descripción en su editor (TinyMCE, en la sección `.model-detail-content`), sustituyendo
     * lo que hubiera. Se mete de golpe con el propio editor, un párrafo por línea: tecleando línea a
     * línea, el editor convierte las que empiezan por "- " en una lista (pasó el 09/11/2026: la
     * primera perdía el guion y las demás quedaban dentro de la lista con él).
     * Después se pulsa una tecla en el editor para que la web se entere del cambio. Si el editor no
     * deja hacerlo así, se escribe tecleando.
     */
    private fun escribirDescripcion(page: Page, texto: String) {
        val html = texto.split(Regex("\\R")).joinToString("") { linea ->
            if (linea.isBlank()) "<p>&nbsp;</p>" else "<p>" + escaparHtml(linea) + "</p>"
        }
        val puesto = try {
            page.evaluate(
                """html => {
                  if (!window.tinymce) return false;
                  const lista = (tinymce.get && tinymce.get()) || tinymce.editors || [];
                  const e = [...lista].find(x => x.iframeElement && x.iframeElement.closest('.model-detail-content'));
                  if (!e) return false;
                  e.setContent(html);
                  if (e.undoManager) e.undoManager.add();
                  // tinymce-vue guarda lo escrito cuando el editor avisa de 'change', 'input', 'undo' o 'redo'
                  if (e.dispatch) e.dispatch('change'); else if (e.fire) e.fire('change');
                  return true;
                }""",
                html,
            ) == true
        } catch (_: Exception) {
            false
        }
        val cuerpo = page.frameLocator(".model-detail-content iframe[id$='_ifr']").first().locator("body")
        if (puesto) {
            // Una tecla de verdad (espacio y borrarlo) para que la web lo dé por escrito
            cuerpo.click()
            page.keyboard().press("End")
            page.keyboard().type(" ")
            page.keyboard().press("Backspace")
        } else {
            progreso.nota("no he podido meter la descripción de golpe: la escribo tecleando (puede salir alguna lista)")
            cuerpo.click()
            page.keyboard().press("Control+A")
            page.keyboard().press("Delete")
            val lineas = texto.split(Regex("\\R"))
            for ((i, linea) in lineas.withIndex()) {
                if (i > 0) page.keyboard().press("Enter")
                if (linea.isNotEmpty()) page.keyboard().insertText(linea)
            }
        }
        if (textoDescripcion(page).isNullOrBlank()) throw IllegalStateException("no se ha quedado escrita la descripción")
    }

    private fun escaparHtml(t: String): String =
        t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** El texto de la descripción, leído del propio editor. */
    private fun textoDescripcion(page: Page): String? = page.evaluate(
        """() => {
          if (!window.tinymce) return null;
          const lista = (tinymce.get && tinymce.get()) || tinymce.editors || [];
          const e = [...lista].find(x => x.iframeElement && x.iframeElement.closest('.model-detail-content'));
          return e ? e.getContent({format: 'text'}) : null;
        }"""
    ) as String?

    /**
     * La casilla "I have read and agree to Copyright Statement." está en el pie, junto a enviar
     * (comprobado en el código de la página). Se pulsa el cuadradito, no el texto: el texto
     * "Copyright Statement" abre una ventana con las normas en vez de marcar la casilla.
     */
    private fun marcarDerechosDeAutor(page: Page) {
        val casilla = page.locator("footer.bottom-btn-group .el-checkbox").first()
        try {
            casilla.waitFor(Locator.WaitForOptions().setTimeout(10_000.0))
        } catch (e: TimeoutError) {
            throw IllegalStateException("no encuentro la casilla junto al botón de enviar")
        }
        if ("is-checked" !in (casilla.getAttribute("class") ?: "")) casilla.locator(".el-checkbox__inner").click()
        if ("is-checked" !in (casilla.getAttribute("class") ?: "")) throw IllegalStateException("no se queda marcada la casilla")
    }

    /**
     * Lo comprueba todo antes de enviar, leyendo el formulario tal como está. Devuelve lo que
     * no está bien (vacío si todo está bien).
     */
    private fun comprobarFormulario(page: Page, titulo: String, etiquetas: Int): List<String> {
        @Suppress("UNCHECKED_CAST")
        val f = page.evaluate(
            """() => {
              const q = s => document.querySelector(s);
              const marcadas = s => [...document.querySelectorAll(s + ' label.el-radio.is-checked input')].map(i => i.value);
              return {
                nombre: (q('.model-name-content input.el-input__inner') || {}).value || '',
                categoria: (q('.model-category-content .el-cascader input.el-input__inner') || {}).value || '',
                etiquetas: document.querySelectorAll('.model-tag-content .create-model-show-tags .el-tag').length,
                licencia: marcadas('.model-license-content'),
                visibilidad: marcadas('.model-share-content'),
                derechos: !!q('footer.bottom-btn-group .el-checkbox.is-checked')
              };
            }"""
        ) as Map<String, Any?>
        val problemas = ArrayList<String>()
        if (f["nombre"] != titulo) problemas += "el nombre no es \"$titulo\" (es \"${f["nombre"]}\")"
        if ((f["categoria"] as String?).isNullOrBlank()) problemas += "no hay categoría"
        if (((f["etiquetas"] as Number?)?.toInt() ?: 0) == 0 && etiquetas > 0) problemas += "no hay etiquetas"
        if (f["licencia"] != listOf("1", "1", "1")) problemas += "la licencia no es Sí/Sí/Sí (CC0): ${f["licencia"]}"
        if (f["visibilidad"] != listOf("0")) problemas += "no está como privado"
        if (f["derechos"] != true) problemas += "no está marcada la casilla de derechos de autor"
        if (textoDescripcion(page).isNullOrBlank()) problemas += "la descripción está vacía"
        return problemas
    }

    /**
     * Pulsa el botón de enviar (`footer .submit-box .submit`) y lo confirma: `POST
     * api/cxy/v3/model/modelGroupCreate` responde code 0 con el id, y la página salta a
     * /model-detail/<id> (comprobado el 09/11/2026). Si la web avisa de algo, se dice cuál.
     */
    private fun enviar(page: Page, red: Red): String {
        val boton = page.locator("footer.bottom-btn-group .submit-box .submit").locator("visible=true").first()
        if (boton.count() == 0) throw IllegalStateException("no encuentro el botón de enviar")
        red.guardado.set(null)
        red.errorGuardado.set(null)
        boton.click()
        val limite = System.currentTimeMillis() + 90_000L
        while (red.guardado.get() == null) {
            red.errorGuardado.get()?.let { throw IllegalStateException("Creality no lo ha guardado ($it)") }
            val aviso = page.locator(".el-message--warning, .el-message--error").locator("visible=true")
            if (aviso.count() > 0 && boton.getAttribute("class")?.contains("is-loading") != true) {
                throw IllegalStateException("Creality no deja enviar: ${aviso.first().innerText().trim()}")
            }
            if (System.currentTimeMillis() > limite) {
                throw IllegalStateException("he pulsado enviar, pero Creality no ha respondido en 90 s. Mira en tu perfil si se ha subido antes de repetir")
            }
            page.waitForTimeout(500.0)
        }
        val j = red.guardado.get()!!
        if (j.path("code").asInt(-1) != 0) {
            throw IllegalStateException("Creality no lo ha guardado: ${j.path("msg").asText("código " + j.path("code").asText("?"))}")
        }
        val id = j.path("result").path("groupItem").path("id").asText("")
        if (id.isBlank()) throw IllegalStateException("Creality no ha devuelto el id del modelo: mira en tu perfil si se ha subido")
        try {
            page.waitForURL("**/model-detail/**", Page.WaitForURLOptions().setTimeout(15_000.0))
        } catch (_: TimeoutError) {
            progreso.nota("Creality lo ha guardado (id $id), pero la página no ha saltado a la del modelo")
        }
        return id
    }

    // ------------------------------------------------------------------ utilidades

    /**
     * Lo que dice la página de la sesión (`$sisLogin` en `__NUXT__.state`): true o false; null si no
     * se sabe porque la página no ha cargado bien (así un corte de red no se toma por "sin sesión").
     */
    private fun haySesion(page: Page): Boolean? = try {
        page.evaluate(
            "() => { const s = window.__NUXT__ && window.__NUXT__.state; return s ? s['\$sisLogin'] === true : null; }"
        ) as Boolean?
    } catch (_: Exception) {
        null
    }

    /** Aviso de cookies: "Reject all" ("Rechazar todo" en español). */
    private fun rechazarCookies(page: Page) {
        val boton = page.locator(".cm__btn")
            .filter(Locator.FilterOptions().setHasText(Pattern.compile("reject|rechaz", Pattern.CASE_INSENSITIVE))).first()
        try {
            boton.waitFor(Locator.WaitForOptions().setTimeout(3_000.0))
            boton.click()
        } catch (_: TimeoutError) {
            // no hay aviso de cookies
        }
    }

    /** Los avisos de error que enseña la página, para el mensaje de error. */
    private fun avisos(page: Page): String {
        val textos = page.locator(".el-message, .el-form-item__error, .el-message-box").allInnerTexts()
            .map { it.trim() }.filter { it.isNotEmpty() }
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
        progreso.nota("$que tiene más de $max caracteres en Creality: se corta")
        return t.take(max).trim()
    }

    /** Separa por comas, quita vacías y repetidas, corta a 30 caracteres y se queda con 20. */
    private fun etiquetas(tags: List<String>): List<String> {
        val max = CatalogoCreality.MAX_LETRAS_ETIQUETA
        val separadas = tags.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
        if (separadas.any { it.length > max }) progreso.nota("alguna etiqueta tenía más de $max caracteres: se ha cortado")
        val limpias = separadas.map { if (it.length > max) it.take(max).trim() else it }.distinctBy { it.lowercase() }
        if (limpias.size > CatalogoCreality.MAX_ETIQUETAS) {
            progreso.nota("Creality admite ${CatalogoCreality.MAX_ETIQUETAS} etiquetas: se suben las ${CatalogoCreality.MAX_ETIQUETAS} primeras")
        }
        return limpias.take(CatalogoCreality.MAX_ETIQUETAS)
    }

    private fun captura(page: Page, destino: Path) {
        try {
            page.screenshot(Page.ScreenshotOptions().setPath(destino).setFullPage(true))
        } catch (_: Exception) {
            // si la página ya no responde, seguimos sin captura
        }
    }

    companion object {
        const val WEB = "https://www.crealitycloud.com"

        /** Dentro de la carpeta del modelo: lo subido a Creality (id, dirección y fecha). */
        const val REGISTRO = "subido-creality.txt"
        const val CAPTURA_OK = "resultado-creality.png"
        const val CAPTURA_ERROR = "error-creality.png"
        /** Dentro de la carpeta del modelo: en qué paso falló y por qué. */
        const val ERROR_TXT = "error-creality.txt"

        private val JSON = ObjectMapper()
        private val FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /** Si esa carpeta ya se subió a Creality. */
        @JvmStatic
        fun yaSubido(carpeta: Path): Boolean = Files.exists(carpeta.resolve(REGISTRO))

        private fun anotarSubido(carpeta: Path, id: String) {
            Files.writeString(
                carpeta.resolve(REGISTRO),
                "$id\t$WEB/model-detail/$id\t${LocalDateTime.now().format(FECHA)}\n",
            )
        }

        /** Escribe error-creality.txt: fecha, paso, motivo y los avisos que enseñaba la página. */
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
            )
            val rutas = Rutas.carpetaActual()
            val progreso = ProgresoConsola()
            val cancelacion = Cancelacion()
            try {
                AgentLock.acquire(rutas.candado, "Subir a Creality", op.esperar, progreso).use {
                    EntradaConsola.iniciar(cancelacion, progreso)
                    SubidorCreality(rutas, progreso, cancelacion, RevisionConsola(progreso, cancelacion)).ejecutar(op)
                }
            } catch (e: AgenteException) {
                progreso.error(e.message ?: e.toString())
                exitProcess(e.codigoSalida)
            }
            exitProcess(0)
        }
    }
}
