package dev.jefe.makeronline

import java.nio.file.Files

/**
 * Sube carpetas de subidas/pendientes/ a las páginas elegidas ([Destino]), una detrás de otra, con un
 * solo botón (pantalla Subir).
 *
 * Desde el 17/11/2026 (a petición de la persona) **ninguna página mueve las carpetas mientras sube**:
 * cada una apunta en la carpeta que ya está allí (subido-<página>.txt) o por qué ha fallado
 * (error-<página>.txt). Al terminar, solo las carpetas que ya están en **todas** sus páginas pasan a
 * subidos/ ([Destino.retirarSiCompleta]); las demás se quedan en pendientes/ con lo que les falta, y la
 * próxima vez solo se sube eso (nada dos veces).
 *
 * Orden: Creality, Makeroad y MakerOnline (las que usan el Chrome de la app). **Printables** no se sube
 * aquí: va con la extensión en el Chrome de siempre (ver ColaPrintables); estas carpetas quedan en su
 * cola y la app abre el formulario de Printables al terminar. Se retiran cuando la extensión apunta
 * la última página (ver ColaPrintables.anotarSubido).
 *
 * No coge el candado de Chrome (AgentLock): lo coge quien la llama, una vez para todo.
 */
class SubidaMultiple(
    private val rutas: Rutas,
    private val progreso: Progreso,
    private val cancelacion: Cancelacion,
    private val revision: Revision,
) {

    /**
     * @property destinos las páginas elegidas (al menos una)
     * @property carpetas los nombres de las carpetas de pendientes/ que se suben (al menos una)
     */
    data class Opciones(
        val destinos: Set<Destino>,
        val revisar: Boolean,
        val carpetas: List<String>,
        val verChrome: Boolean,
    )

    /** Cómo ha ido en una página. */
    data class Cuenta(val subidos: Int, val conError: Int, val sinSubir: Int, val yaEstaban: Int)

    /**
     * Cómo ha ido.
     *
     * @property cuentas      por página (solo las elegidas que se han intentado; Printables va aparte)
     * @property sin3mf       las que no van a Creality porque solo tienen .stl
     * @property paraPrintables las que quedan en la cola de Printables (se suben con la extensión)
     * @property retiradas    las que ya están en todas sus páginas y han pasado a subidos/
     * @property parado       si se ha parado a petición de la persona
     */
    data class Resumen(
        val cuentas: Map<Destino, Cuenta>,
        val sin3mf: List<String>,
        val paraPrintables: List<String>,
        val retiradas: Int,
        val parado: Boolean,
    )

    fun ejecutar(op: Opciones): Resumen {
        require(op.destinos.isNotEmpty()) { "Elige al menos una página." }
        require(op.carpetas.isNotEmpty()) { "No hay carpetas que subir." }
        val pendientes = rutas.pendientes
        val dirs = op.carpetas.map { pendientes.resolve(it) }.filter { Files.isDirectory(it) }
        // Cada carpeta se queda con las páginas elegidas ahora (para saber cuándo retirarla)
        for (d in dirs) {
            try {
                Destino.apuntar(d, op.destinos)
            } catch (e: Exception) {
                progreso.error("${d.fileName}: no he podido apuntar sus páginas (${Destino.DESTINOS}): ${e.message}")
            }
        }
        val cuentas = LinkedHashMap<Destino, Cuenta>()
        val sin3mf = if (Destino.CREALITY in op.destinos) dirs.filter { !Destino.CREALITY.aplica(it) }.map { it.fileName.toString() } else emptyList()
        var parado = false

        fun faltanEn(d: Destino): List<String> = dirs.filter { Files.isDirectory(it) && d.aplica(it) && !d.estaEn(it) }
            .map { it.fileName.toString() }

        // --- 1) Creality ---
        if (!parado && Destino.CREALITY in op.destinos) {
            progreso.info("── ${Destino.CREALITY.nombre} ──")
            if (sin3mf.isNotEmpty()) {
                progreso.nota("A ${Destino.CREALITY.nombre} de momento solo se sube con .3mf; no van: ${sin3mf.joinToString(", ")}")
            }
            val lista = faltanEn(Destino.CREALITY)
            val ya = dirs.count { Destino.CREALITY.aplica(it) } - lista.size
            if (lista.isNotEmpty()) {
                val r = SubidorCreality(rutas, conPagina(Destino.CREALITY), cancelacion, revisionDe(Destino.CREALITY))
                    .ejecutar(SubidorCreality.Opciones(op.revisar, lista, false, op.verChrome))
                cuentas[Destino.CREALITY] = Cuenta(r.subidos, r.conError, r.sinSubir, r.yaSubidos + ya)
                parado = r.parado || cancelacion.pedida()
            } else {
                cuentas[Destino.CREALITY] = Cuenta(0, 0, 0, ya)
            }
        }

        // --- 2) Makeroad ---
        if (!parado && Destino.MAKEROAD in op.destinos) {
            progreso.info("── ${Destino.MAKEROAD.nombre} ──")
            val lista = faltanEn(Destino.MAKEROAD)
            val ya = dirs.size - lista.size
            if (lista.isNotEmpty()) {
                val r = SubidorMakeroad(rutas, conPagina(Destino.MAKEROAD), cancelacion, revisionDe(Destino.MAKEROAD))
                    .ejecutar(SubidorMakeroad.Opciones(op.revisar, lista, false, op.verChrome))
                cuentas[Destino.MAKEROAD] = Cuenta(r.subidos, r.conError, r.sinSubir, r.yaSubidos + ya)
                parado = r.parado || cancelacion.pedida()
            } else {
                cuentas[Destino.MAKEROAD] = Cuenta(0, 0, 0, ya)
            }
        }

        // --- 3) MakerOnline (sin mover: se retiran al final las completas) ---
        if (!parado && Destino.MAKERONLINE in op.destinos) {
            progreso.info("── ${Destino.MAKERONLINE.nombre} ──")
            val lista = faltanEn(Destino.MAKERONLINE)
            val ya = dirs.size - lista.size
            if (lista.isNotEmpty()) {
                val r = Uploader(rutas, conPagina(Destino.MAKERONLINE), cancelacion, revisionDe(Destino.MAKERONLINE))
                    .ejecutar(Uploader.Opciones(op.revisar, lista, false, op.verChrome, false))
                cuentas[Destino.MAKERONLINE] = Cuenta(r.subidos(), r.conError() + r.invalidos(), r.sinSubir(), ya)
                parado = r.parado() || cancelacion.pedida()
            } else {
                cuentas[Destino.MAKERONLINE] = Cuenta(0, 0, 0, ya)
            }
        }

        // --- 4) Printables: quedan en su cola para la extensión ---
        val paraPrintables = if (Destino.PRINTABLES in op.destinos) faltanEn(Destino.PRINTABLES) else emptyList()
        if (paraPrintables.isNotEmpty()) {
            progreso.info("── ${Destino.PRINTABLES.nombre} ──")
            progreso.nota("${paraPrintables.size} en la cola de Printables: se suben con la extensión en tu Chrome (se abre el formulario)")
        }

        // --- 5) Las que ya están en todas sus páginas pasan a subidos/ ---
        var retiradas = 0
        for (d in dirs) {
            if (Destino.retirarSiCompleta(rutas, d, progreso) != null) retiradas++
        }
        val siguen = dirs.count { Files.isDirectory(it) }
        if (siguen > 0) progreso.info("$siguen siguen en pendientes porque les falta alguna página (mira sus insignias).")
        return Resumen(cuentas, sin3mf, paraPrintables, retiradas, parado)
    }

    /** Lo que cuenta cada página, con su nombre en "Modelo 1/3 · flower · Creality Cloud". */
    private fun conPagina(d: Destino): Progreso = object : Progreso by progreso {
        override fun modelo(indice: Int, total: Int, titulo: String) =
            progreso.modelo(indice, total, "$titulo · ${d.nombre}")
    }

    /** Al revisar, que se sepa de qué página es el formulario ("flower · Creality Cloud"). */
    private fun revisionDe(d: Destino): Revision = Revision { modelo ->
        // SubidorMakeroad ya pone " · Makeroad" (lo usa la app para el texto del botón)
        revision.confirmar(if (modelo.endsWith(" · ${d.nombre}")) modelo else "$modelo · ${d.nombre}")
    }
}
