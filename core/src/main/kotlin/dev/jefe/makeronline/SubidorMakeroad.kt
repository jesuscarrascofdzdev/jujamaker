package dev.jefe.makeronline

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.TimeoutError
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import kotlin.system.exitProcess

/**
 * Sube a Makeroad (https://www.makeroad.com) las carpetas de subidas/pendientes/, con los mismos
 * archivos y textos que se usan para MakerOnline, con el Chrome de la app (como Creality).
 *
 * Por qué con la app y no con una extensión: Makeroad **no tiene Cloudflare** (comprobado el
 * 17/11/2026: servidor nginx, sin verificación). Sí carga el captcha y la "huella de dispositivo" de
 * Aliyun: si un día detecta el Chrome de la app y saca el captcha, **no se salta**: se para y se te
 * enseña la ventana para que lo resuelvas (con --revisar). DiagnosticoMakeroad lo comprueba.
 *
 * Cada modelo se **publica directamente como público** (botón "Publicar"), a petición de la persona
 * (17/11/2026): es la excepción a la regla de subir como borrador. Makeroad no lo enseña al momento:
 * responde "Enviado para revisión" (`toasts.submitSuccess`) y pasa por su moderación. Para mirar cada
 * modelo antes de publicarlo, usa --revisar.
 *
 * Lo que usa del formulario (https://www.makeroad.com/spa/printable_3D_model/upload, hecho con Arco
 * Design y el editor wangEditor; visto el 17/11/2026 en la página, sin guardar nada):
 *  - "Tipo de carga": el primer grupo de opciones; "Modelo original" es value=1 (ya viene marcado).
 *  - Archivos, por su `accept`: el del modelo acepta ".stl" (uno cada vez), el de configuración de
 *    impresión solo ".3mf", y el de fotos ".jpg,.jpeg,.png…" (varias). Los .stl van como modelo; el
 *    .3mf va como configuración de impresión si hay .stl, y si no, como modelo.
 *  - Fotos: la web pide de 3 a 10 (1:1 recomendado). Van la portada y las demás imágenes de la carpeta.
 *  - Título: el único `input.arco-input` (máximo 60). Descripción: el editor (`[contenteditable=true]`).
 *  - Categoría: `.tag-input-box` abre `.selection-modal` con grupos `.brand-group` (`.brand-title`)
 *    y opciones `.option-item`; se acepta con `.confirm-btn`. Ver CatalogoMakeroad.
 *  - Etiquetas: el primer `input.arco-select-view-input` (se escribe cada una y ENTER).
 *  - Método de impresión: `span.method-text` "FDM".
 *  - Licencia: tres grupos de opciones (compartir sin atribución, modificar, uso comercial): se marca
 *    "Sí" (value=2) en los tres, lo más parecido a CC0, como en Creality.
 *  - Visibilidad: "público" (value=1; "Privado" es 2).
 *  - Términos: la casilla "Entiendo y acepto…" es obligatoria para publicar
 *    (`toasts.agreementRequired`: "Por favor, acepte primero los términos de la plataforma.").
 *  - Botones de abajo `div.layout-btn`, por orden: "Registrar" (guardar), "Avance" (vista previa) y
 *    "Publicar".
 *
 * Sin comprobar todavía (hay que verlo en la primera subida con --revisar): que el aviso al publicar
 * salga como dice el código de la web ("Enviado para revisión" / "Submitted for Review") y que el
 * botón "Publicar" se active al tener todo relleno (empieza desactivado).
 *
 * No mueve las carpetas: apunta lo subido en `subido-makeroad.txt` dentro de la carpeta, y las que lo
 * tienen no se vuelven a subir. Si algo falla, deja `error-makeroad.txt` y `error-makeroad.png`.
 *
 * Programa de terminal (configuración "Subir a Makeroad (revisar)"):
 *   --revisar   antes de publicar cada modelo, se para para que mires el formulario:
 *               ENTER lo publica; "no" + ENTER no lo publica.
 *   --esperar   si otro programa del agente está en marcha, espera a que termine.
 *   --subidos   de subidos/ (las ya subidas a MakerOnline) en vez de pendientes/.
 *   nombre ...  solo esas carpetas (sin nombres, todas).
 * Para pararlo sin dejar nada a medias: "parar" + ENTER.
 */
class SubidorMakeroad(
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
        val desdeSubidos: Boolean = false,
    )

    /** El mismo resumen que SubidorCreality.Resumen. */
    data class Resumen(val subidos: Int, val yaSubidos: Int, val conError: Int, val sinSubir: Int, val parado: Boolean)

    private class Descartado : RuntimeException("descartado al revisar")

    /** Pide iniciar sesión: no se sigue con ningún modelo más. */
    private class SinSesion : RuntimeException("no hay sesión de Makeroad")

    /** Ha salido el captcha de Aliyun sin poder enseñárselo a nadie (sin --revisar). */
    private class ConCaptcha : RuntimeException("ha salido el captcha de Makeroad")

    /** Algo ha fallado en un paso concreto del formulario: el mensaje dice cuál. */
    class ErrorEnPaso(val paso: String, val motivo: String, causa: Throwable?) : RuntimeException("$paso: $motivo", causa)

    fun ejecutar(op: Opciones): Resumen {
        val origen = if (op.desdeSubidos) rutas.subidos else rutas.pendientes
        Files.createDirectories(origen)
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
                progreso.saltado("${dir.fileName}: ya está en Makeroad ($REGISTRO), no se vuelve a subir")
                yaSubidos++
                continue
            }
            try {
                val m = ModelFolder.load(dir)
                listos += m
                progreso.correcto("${dir.fileName} validado")
            } catch (e: Exception) {
                progreso.error("${dir.fileName}:\n   ${(e.message ?: e.toString()).replace("\n", "\n   ")}")
                sinSubir++
            }
        }
        if (listos.isEmpty()) {
            progreso.info("Nada que subir a Makeroad.")
            return Resumen(0, yaSubidos, 0, sinSubir, false)
        }

        // 2) Subir
        var ok = 0
        var ko = 0
        var parado = false
        val modo = if (op.verChrome) Downloader.Modo.VISIBLE else Downloader.Modo.APARTADA
        Files.createDirectories(rutas.perfilChrome)
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
                    val ventana = Downloader.Ventana(modo, ctx, page, progreso)
                    try {
                        subir(page, m, op.revisar, ventana)
                        anotarSubido(m.dir(), page.url())
                        captura(page, m.dir().resolve(CAPTURA_OK))
                        borrarErroresAnteriores(m.dir())
                        progreso.correcto("Publicado en Makeroad (enviado para revisión): ${m.name()}")
                        ok++
                    } catch (_: CanceladoException) {
                        parado = true
                        sinSubir += listos.size - i
                        progreso.info("⏹ ${m.name()} no se ha publicado en Makeroad.")
                    } catch (_: Descartado) {
                        sinSubir++
                        progreso.saltado("No se publica ${m.name()} en Makeroad.")
                    } catch (_: SinSesion) {
                        throw AgenteException(
                            "No hay sesión de Makeroad en el Chrome de la app. Inicia sesión en Makeroad en esa ventana " +
                                "(o lanza el diagnóstico) y vuelve a probar. " +
                                (if (ok > 0) "Antes se han publicado bien $ok." else "No se ha publicado nada en Makeroad.")
                        )
                    } catch (_: ConCaptcha) {
                        throw AgenteException(
                            "Makeroad ha sacado su captcha. No se salta: súbelo con --revisar (o desde la app con Chrome a la " +
                                "vista) para resolverlo tú. " + (if (ok > 0) "Antes se han publicado bien $ok." else "")
                        )
                    } catch (e: Exception) {
                        val mensaje = e.message?.lineSequence()?.firstOrNull() ?: e.toString()
                        progreso.error("Error al subir ${m.name()} a Makeroad en $mensaje")
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
        progreso.info("\n${if (parado) "Parado" else "Terminado"}: $ok publicados en Makeroad, $ko con error." +
            (if (yaSubidos > 0) " $yaSubidos ya estaban." else "") +
            (if (sinSubir > 0) " $sinSubir sin subir." else ""))
        return Resumen(ok, yaSubidos, ko, sinSubir, parado)
    }

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
        } catch (e: ConCaptcha) {
            throw e
        } catch (e: ErrorEnPaso) {
            throw e
        } catch (e: Exception) {
            throw ErrorEnPaso(nombre, e.message?.lineSequence()?.firstOrNull() ?: e.toString(), e)
        }
    }

    /** Rellena el formulario con un modelo y lo publica (público; Makeroad lo revisa antes de enseñarlo). */
    private fun subir(page: Page, m: ModelFolder, revisar: Boolean, ventana: Downloader.Ventana?) {
        val meta = m.meta()
        paso("Abrir el formulario de Makeroad") {
            page.navigate(FORMULARIO)
            try {
                page.locator("input.arco-input").locator("visible=true").first().waitFor(Locator.WaitForOptions().setTimeout(30_000.0))
            } catch (e: TimeoutError) {
                if (!pideSesion(page) && hayCaptcha(page)) throw ConCaptcha()
                if (!pideSesion(page)) throw IllegalStateException("no se ha cargado el formulario de subida")
            }
            if (pideSesion(page)) esperarSesion(page, ventana)
            val tipo = grupos(page).firstOrNull() ?: throw IllegalStateException("no encuentro las opciones del formulario (.arco-radio-group)")
            marcarOpcion(tipo, "1", "Modelo original")
        }

        val stl = m.modelos().filter { it.fileName.toString().lowercase().endsWith(".stl") }
        val tresmf = m.modelos().filter { it.fileName.toString().lowercase().endsWith(".3mf") }
        paso("Archivos del modelo (${m.modelos().size})") {
            val entradaModelo = page.locator("input[type=file][accept*='.stl']").first()
            val entradaPerfil = page.locator("input[type=file][accept='.3mf']").first()
            val comoModelo = if (stl.isNotEmpty()) stl else tresmf
            for (f in comoModelo) {
                entradaModelo.setInputFiles(f)
                page.waitForTimeout(1500.0)
            }
            if (stl.isNotEmpty()) for (f in tresmf) {
                entradaPerfil.setInputFiles(f)
                page.waitForTimeout(1500.0)
            }
            esperarSubidas(page, "los archivos del modelo")
        }

        val fotos = fotosParaMakeroad(m)
        paso("Fotos (${fotos.size})") {
            page.locator("input[type=file][accept^='.jpg']").first().setInputFiles(fotos.toTypedArray())
            esperarSubidas(page, "las fotos")
        }

        cancelacion.comprobar() // punto seguro: no se ha publicado nada

        val titulo = recortar(meta.title(), CatalogoMakeroad.MAX_TITULO, "El título")
        paso("Título") {
            val campo = page.locator("input.arco-input").locator("visible=true").first()
            campo.fill(titulo)
            if (campo.inputValue() != titulo) throw IllegalStateException("no se ha quedado escrito el título")
        }

        paso("Descripción") { escribirDescripcion(page, meta.description()) }

        val categoria = CatalogoMakeroad.paraMakerOnline(meta.category())
        paso("Categoría ${categoria.grupo} > ${categoria.opcion}") { elegirCategoria(page, categoria) }

        val etiquetas = etiquetas(meta.tags())
        paso("Etiquetas (${etiquetas.size})") { escribirEtiquetas(page, etiquetas) }

        paso("Método de impresión FDM") {
            val fdm = page.locator("span.method-text").filter(Locator.FilterOptions().setHasText(Pattern.compile("^\\s*FDM\\s*$"))).first()
            if (fdm.count() == 0) throw IllegalStateException("no encuentro la opción FDM")
            fdm.click()
        }

        paso("Licencia (Sí a las tres preguntas, lo más parecido a CC0)") {
            val g = grupos(page)
            // Por orden: tipo de carga, las tres de la licencia y la visibilidad (visto el 17/11/2026)
            if (g.size < 5) throw IllegalStateException("esperaba 5 grupos de opciones y hay ${g.size}")
            for (i in 1..3) marcarOpcion(g[i], "2", "Sí")
        }
        paso("Visibilidad pública") { marcarOpcion(grupos(page)[4], "1", "público") }

        paso("Casilla de términos") { marcarTerminos(page) }

        if (hayCaptcha(page)) {
            if (!revisar) throw ConCaptcha()
            progreso.info("   Ha salido el captcha de Makeroad: resuélvelo tú en la ventana de Chrome.")
        }

        if (revisar) {
            ventana?.mostrar()
            val publicar = try {
                revision.confirmar("${m.name()} · $NOMBRE") // la app pone "Publicar en Makeroad" en el botón
            } finally {
                ventana?.apartar()
            }
            if (!publicar) throw Descartado()
        }
        cancelacion.comprobar() // último punto seguro
        paso("Publicar (público)") { publicar(page, revisar) }
    }

    /**
     * La web pide iniciar sesión: se enseña la ventana de Chrome y se espera hasta 5 minutos a que la
     * persona entre (la sesión se queda en el perfil de Chrome de la app para las siguientes veces).
     */
    private fun esperarSesion(page: Page, ventana: Downloader.Ventana?) {
        ventana?.mostrar()
        progreso.info("   Makeroad pide iniciar sesión: entra tú en la ventana de Chrome que se ha abierto. Espero hasta 5 minutos.")
        val limite = System.currentTimeMillis() + 5 * 60_000L
        try {
            while (pideSesion(page)) {
                cancelacion.comprobar()
                if (System.currentTimeMillis() > limite) throw SinSesion()
                page.waitForTimeout(2000.0)
            }
            page.waitForTimeout(2000.0)
            page.navigate(FORMULARIO)
            page.locator("input.arco-input").locator("visible=true").first().waitFor(Locator.WaitForOptions().setTimeout(30_000.0))
            if (pideSesion(page)) throw SinSesion()
            progreso.correcto("Sesión iniciada en Makeroad")
        } finally {
            ventana?.apartar()
        }
    }

    /**
     * Las fotos que se suben: la portada primero y luego las demás, hasta [CatalogoMakeroad.MAX_FOTOS].
     * Makeroad pide al menos [CatalogoMakeroad.MIN_FOTOS] para publicar: si la carpeta tiene menos, se
     * repiten (la portada primero) hasta llegar, a petición de la persona (17/11/2026). Las repetidas van
     * como copias con otro nombre en una carpeta temporal, para que la web no las tome por la misma.
     */
    private fun fotosParaMakeroad(m: ModelFolder): List<Path> {
        val propias = (listOf(m.portada()) + m.imagenes().filter { it != m.portada() }).take(CatalogoMakeroad.MAX_FOTOS)
        if (propias.size >= CatalogoMakeroad.MIN_FOTOS) return propias
        val temporal = Files.createTempDirectory("jujamaker-makeroad")
        temporal.toFile().deleteOnExit()
        val todas = ArrayList<Path>(propias)
        var i = 0
        while (todas.size < CatalogoMakeroad.MIN_FOTOS) {
            val original = propias[i % propias.size]
            val nombre = original.fileName.toString()
            val copia = temporal.resolve(nombre.substringBeforeLast('.') + "-" + (todas.size + 1) + "." + nombre.substringAfterLast('.'))
            Files.copy(original, copia, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            copia.toFile().deleteOnExit()
            todas.add(copia)
            i++
        }
        progreso.nota("la carpeta tiene ${propias.size} foto(s) y Makeroad pide ${CatalogoMakeroad.MIN_FOTOS}: se repite${if (propias.size == 1) " la misma" else "n"} hasta llegar")
        return todas
    }

    // ------------------------------------------------------------------ campos

    /** Los grupos de opciones del formulario (`.arco-radio-group`), por orden. */
    private fun grupos(page: Page): List<Locator> {
        val todos = page.locator(".arco-radio-group")
        return (0 until todos.count()).map { todos.nth(it) }
    }

    /** Marca en un grupo la opción con ese value. [nombre] es para el mensaje de error. */
    private fun marcarOpcion(grupo: Locator, valor: String, nombre: String) {
        val opcion = grupo.locator("label.arco-radio:has(input[value='$valor'])").first()
        if (opcion.count() == 0) throw IllegalStateException("no encuentro la opción \"$nombre\"")
        val entrada = opcion.locator("input").first()
        if (!entrada.isChecked) opcion.click()
        if (!entrada.isChecked) throw IllegalStateException("no se queda marcada la opción \"$nombre\"")
    }

    /**
     * Espera a que no quede nada subiendo (barras de progreso o "cargando" de Arco). Si no se ve
     * ninguna, espera un poco igualmente: las subidas pequeñas terminan antes de verlas.
     */
    private fun esperarSubidas(page: Page, que: String) {
        val limite = System.currentTimeMillis() + 10 * 60_000L
        page.waitForTimeout(2000.0)
        while (true) {
            cancelacion.comprobar()
            val subiendo = runCatching {
                page.locator(".arco-upload-progress, .arco-progress, .arco-upload-list-item-uploading, .arco-spin-loading")
                    .locator("visible=true").count()
            }.getOrDefault(0)
            if (subiendo == 0) break
            if (System.currentTimeMillis() > limite) throw IllegalStateException("$que llevan más de 10 minutos subiendo")
            page.waitForTimeout(1000.0)
        }
        val error = page.locator(".arco-upload-list-item-error, .arco-message-error").locator("visible=true")
        if (error.count() > 0) throw IllegalStateException("error al subir $que: ${error.first().innerText().trim()}")
    }

    /**
     * Escribe la descripción en el editor (wangEditor), un párrafo por línea, tecleando: así se entera
     * la web. Antes se borra lo que hubiera.
     */
    private fun escribirDescripcion(page: Page, texto: String) {
        val editor = page.locator("[contenteditable=true]").first()
        if (editor.count() == 0) throw IllegalStateException("no encuentro el editor de la descripción")
        editor.click()
        page.keyboard().press("Control+A")
        page.keyboard().press("Delete")
        val lineas = texto.trim().split(Regex("\\R"))
        for ((i, linea) in lineas.withIndex()) {
            if (linea.isNotBlank()) page.keyboard().insertText(linea)
            if (i < lineas.size - 1) page.keyboard().press("Enter")
        }
        val escrito = editor.innerText().trim()
        val primera = lineas.firstOrNull { it.isNotBlank() }?.trim()?.take(30) ?: ""
        if (primera.isNotEmpty() && primera !in escrito) throw IllegalStateException("no se ha quedado escrita la descripción")
    }

    private fun elegirCategoria(page: Page, c: CatalogoMakeroad.Categoria) {
        page.locator(".tag-input-box").first().click()
        val modal = page.locator(".selection-modal").first()
        modal.waitFor(Locator.WaitForOptions().setTimeout(10_000.0))
        val grupo = modal.locator(".brand-group").filter(
            Locator.FilterOptions().setHas(page.locator(".brand-title", Page.LocatorOptions().setHasText(c.grupo)))
        ).first()
        if (grupo.count() == 0) throw IllegalStateException("no encuentro el grupo \"${c.grupo}\"")
        val titulo = grupo.locator(".brand-title").first()
        if ("on" !in (titulo.getAttribute("class") ?: "").split(" ")) titulo.click()
        val opcion = grupo.locator(".option-item").filter(
            Locator.FilterOptions().setHasText(Pattern.compile("^\\W*" + Pattern.quote(c.opcion) + "\\W*$"))
        ).first()
        if (opcion.count() == 0) throw IllegalStateException("no encuentro \"${c.opcion}\" en \"${c.grupo}\"")
        opcion.click() // cada modelo empieza en un formulario nuevo: no hay nada marcado antes
        modal.locator(".confirm-btn").first().click()
        try {
            modal.waitFor(Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN).setTimeout(5_000.0))
        } catch (_: TimeoutError) {
            throw IllegalStateException("la ventana de categorías no se cierra al pulsar Enviar")
        }
        page.waitForTimeout(500.0)
        // La elegida no sale dentro de .tag-input-box sino al lado (en .selected-tags): se mira todo el
        // campo de la categoría (el contenedor que tiene la caja y lo elegido)
        val puesta = page.locator(".tag-input-box").first().evaluate(
            """caja => {
              let p = caja;
              for (let i = 0; i < 6 && p.parentElement; i++) {
                p = p.parentElement;
                if (p.querySelector('.selected-tags') || (p.className || '').toString().includes('form-item')) break;
              }
              return (p.innerText || '').replace(/\u200b/g, '');
            }"""
        ) as String
        if (c.opcion !in puesta) {
            throw IllegalStateException("la categoría no se ha quedado puesta (en el campo se ve \"${puesta.replace(Regex("\\s+"), " ").trim().take(120)}\")")
        }
    }

    private fun escribirEtiquetas(page: Page, etiquetas: List<String>) {
        if (etiquetas.isEmpty()) return
        val campo = page.locator("input.arco-select-view-input").first()
        if (campo.count() == 0) throw IllegalStateException("no encuentro el campo de etiquetas")
        for (e in etiquetas) {
            campo.click()
            campo.fill(e)
            page.waitForTimeout(400.0)
            campo.press("Enter")
            page.waitForTimeout(300.0)
        }
        page.keyboard().press("Escape")
    }

    /** La casilla "Entiendo y acepto subir mi trabajo a MakeRoad…": sin ella no deja publicar. */
    private fun marcarTerminos(page: Page) {
        val casilla = page.locator("label.arco-checkbox").filter(
            Locator.FilterOptions().setHasText(Pattern.compile("MakeRoad", Pattern.CASE_INSENSITIVE))
        ).first()
        if (casilla.count() == 0) throw IllegalStateException("no encuentro la casilla de los términos de la plataforma")
        val entrada = casilla.locator("input").first()
        if (entrada.count() > 0 && !entrada.isChecked) casilla.click()
        if (entrada.count() > 0 && !entrada.isChecked) throw IllegalStateException("no se queda marcada la casilla de los términos")
    }

    /**
     * Pulsa "Publicar" (el tercer `div.layout-btn`, tras "Registrar" y "Avance") y espera el aviso de
     * que se ha enviado. El botón empieza desactivado (clase `disabled`) y se activa cuando el
     * formulario está completo: si no se activa, se dice por qué y no se pulsa nada.
     */
    private fun publicar(page: Page, revisar: Boolean) {
        val botones = page.locator("div.layout-btn")
        if (botones.count() < 3) throw IllegalStateException("no encuentro los botones de abajo (Registrar / Avance / Publicar)")
        val boton = botones.nth(2)
        val texto = boton.innerText().trim()
        if (!Regex("publi|publish", RegexOption.IGNORE_CASE).containsMatchIn(texto)) {
            throw IllegalStateException("el tercer botón es \"$texto\" y no \"Publicar\": no se pulsa nada")
        }
        val activo = System.currentTimeMillis() + 15_000L
        while ("disabled" in (boton.getAttribute("class") ?: "").split(" ")) {
            if (System.currentTimeMillis() > activo) {
                throw IllegalStateException("el botón \"$texto\" sigue desactivado: falta algo en el formulario. Avisos: ${avisosSeguros(page)}")
            }
            page.waitForTimeout(500.0)
        }
        boton.click()
        val limite = System.currentTimeMillis() + (if (revisar) 5 * 60_000L else 90_000L)
        while (true) {
            cancelacion.comprobar()
            page.waitForTimeout(500.0)
            val avisos = page.locator(".arco-message").locator("visible=true").allInnerTexts().map { it.trim() }
            if (avisos.any { ENVIADO.containsMatchIn(it) }) return
            val malo = page.locator(".arco-message-error, .arco-message-warning, .arco-form-item-message")
                .locator("visible=true").allInnerTexts().map { it.trim() }.filter { it.isNotEmpty() }
            if (malo.isNotEmpty()) throw IllegalStateException("Makeroad no lo publica: ${malo.joinToString(" | ")}")
            if (hayCaptcha(page) && !revisar) throw ConCaptcha()
            if (System.currentTimeMillis() > limite) {
                throw IllegalStateException(
                    "he pulsado \"$texto\", pero no ha salido el aviso de \"Enviado para revisión\". Mira en tus modelos de Makeroad si está antes de repetir"
                )
            }
        }
    }

    // ------------------------------------------------------------------ utilidades

    /** Si la página pide iniciar sesión (ventana o botón de "Iniciar sesión" / "Log in" a la vista). */
    private fun pideSesion(page: Page): Boolean = runCatching {
        page.locator("button, a, .arco-modal-title").locator("visible=true")
            .filter(Locator.FilterOptions().setHasText(Pattern.compile("^\\s*(Iniciar sesión|Log in|Login|Sign in)\\s*$", Pattern.CASE_INSENSITIVE)))
            .count() > 0
    }.getOrDefault(false)

    /** Si se ve el captcha de Aliyun. */
    private fun hayCaptcha(page: Page): Boolean = runCatching {
        page.evaluate(
            "() => [...document.querySelectorAll('[id*=aliyunCaptcha], [class*=aliyunCaptcha], iframe[src*=\"captcha\"]')]" +
                ".some(e => e.getClientRects().length > 0)"
        ) as Boolean
    }.getOrDefault(false)

    private fun avisosSeguros(page: Page): String = try {
        page.locator(".arco-message, .arco-form-item-message").allInnerTexts().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" | ").ifEmpty { "(ninguno)" }
    } catch (_: Exception) {
        "(no se han podido leer)"
    }

    private fun recortar(texto: String, max: Int, que: String): String {
        val t = texto.trim()
        if (t.length <= max) return t
        progreso.nota("$que tiene más de $max caracteres en Makeroad: se corta")
        return t.take(max).trim()
    }

    /** Separa por comas, quita vacías y repetidas y se queda con las [CatalogoMakeroad.MAX_ETIQUETAS] primeras. */
    private fun etiquetas(tags: List<String>): List<String> {
        val limpias = tags.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (limpias.size > CatalogoMakeroad.MAX_ETIQUETAS) {
            progreso.nota("se suben las ${CatalogoMakeroad.MAX_ETIQUETAS} primeras etiquetas")
        }
        return limpias.take(CatalogoMakeroad.MAX_ETIQUETAS)
    }

    private fun captura(page: Page, destino: Path) {
        try {
            page.screenshot(Page.ScreenshotOptions().setPath(destino).setFullPage(true))
        } catch (_: Exception) {
            // si la página ya no responde, seguimos sin captura
        }
    }

    companion object {
        const val WEB = "https://www.makeroad.com"
        const val NOMBRE = "Makeroad"
        /** El formulario de subida (comprobado el 17/11/2026). */
        const val FORMULARIO = "$WEB/spa/printable_3D_model/upload"

        /** Dentro de la carpeta del modelo: lo publicado en Makeroad (dirección y fecha). */
        const val REGISTRO = "subido-makeroad.txt"
        const val CAPTURA_OK = "resultado-makeroad.png"
        const val CAPTURA_ERROR = "error-makeroad.png"
        const val ERROR_TXT = "error-makeroad.txt"

        /**
         * El aviso al publicar: "Enviado para revisión" en español y "Submitted for Review" en inglés
         * (`toasts.submitSuccess` del código de la web, visto el 17/11/2026).
         */
        private val ENVIADO = Regex("enviado para revisi|submitted for review|eingereicht", RegexOption.IGNORE_CASE)

        private val FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        @JvmStatic
        fun yaSubido(carpeta: Path): Boolean = Files.exists(carpeta.resolve(REGISTRO))

        private fun anotarSubido(carpeta: Path, direccion: String) {
            Files.writeString(carpeta.resolve(REGISTRO), "${direccion.substringBefore('?')}\t${LocalDateTime.now().format(FECHA)}\n")
        }

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
            }
        }

        private fun borrarErroresAnteriores(carpeta: Path) {
            for (n in listOf(ERROR_TXT, CAPTURA_ERROR)) runCatching { Files.deleteIfExists(carpeta.resolve(n)) }
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
            val rutas = Rutas.deLaApp() // las carpetas y la sesión de Chrome de la app
            val progreso = ProgresoConsola()
            progreso.info("Carpeta de datos: ${rutas.datos.toAbsolutePath()}")
            val cancelacion = Cancelacion()
            try {
                AgentLock.acquire(rutas.candado, "Subir a Makeroad", op.esperar, progreso).use {
                    EntradaConsola.iniciar(cancelacion, progreso)
                    SubidorMakeroad(rutas, progreso, cancelacion, RevisionConsola(progreso, cancelacion)).ejecutar(op)
                }
            } catch (e: AgenteException) {
                progreso.error(e.message ?: e.toString())
                exitProcess(e.codigoSalida)
            }
            exitProcess(0)
        }
    }
}
