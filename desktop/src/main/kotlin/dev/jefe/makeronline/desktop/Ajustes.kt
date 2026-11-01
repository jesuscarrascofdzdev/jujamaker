package dev.jefe.makeronline.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Lo que la app recuerda de una vez para otra, en ajustes.properties (ver Rutas.ajustes).
 * Si el archivo no existe o no se puede leer, se usan los valores de siempre.
 */
class Ajustes(private val archivo: Path) {
    private val valores = Properties().apply { leer(this) }

    /** Vuelve a leer el archivo (después de traer los datos de antes, ver EstadoDatos). */
    fun recargar() {
        valores.clear()
        leer(valores)
    }

    private fun leer(p: Properties) {
        try {
            if (Files.exists(archivo)) Files.newBufferedReader(archivo, Charsets.UTF_8).use { p.load(it) }
        } catch (_: Exception) {
            // archivo dañado: valores de siempre
        }
    }

    /** Si al descargar toda la lista se incluye al creador de la app (por defecto, sí). */
    var incluirCreador: Boolean
        get() = valores.getProperty(INCLUIR_CREADOR)?.toBooleanStrictOrNull() ?: true
        set(v) {
            valores.setProperty(INCLUIR_CREADOR, v.toString())
            guardar()
        }

    /** Los usuarios de usuarios.json que se han desmarcado en Descargar (no se descargan). */
    var desmarcados: Set<String>
        get() = valores.getProperty(DESMARCADOS)?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        set(v) {
            if (v.isEmpty()) valores.remove(DESMARCADOS) else valores.setProperty(DESMARCADOS, v.sorted().joinToString("\n"))
            guardar()
        }

    /** Si en Subir se ve la ventana de Chrome mientras sube (por defecto, sí). */
    var verChromeAlSubir: Boolean
        get() = valores.getProperty(VER_CHROME_AL_SUBIR)?.toBooleanStrictOrNull() ?: true
        set(v) {
            valores.setProperty(VER_CHROME_AL_SUBIR, v.toString())
            guardar()
        }

    private fun guardar() {
        try {
            Files.newBufferedWriter(archivo, Charsets.UTF_8).use { valores.store(it, "Ajustes de JujaMaker") }
        } catch (_: Exception) {
            // si no se puede guardar, la próxima vez se usa el valor de siempre
        }
    }

    private companion object {
        const val INCLUIR_CREADOR = "incluirCreador"
        const val DESMARCADOS = "usuariosDesmarcados"
        const val VER_CHROME_AL_SUBIR = "verChromeAlSubir"
    }
}
