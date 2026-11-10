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

    /** En Subir, si se sube a MakerOnline (por defecto, sí). Solo cuenta si hay sesión. */
    var subirMakerOnline: Boolean
        get() = valores.getProperty(SUBIR_MAKERONLINE)?.toBooleanStrictOrNull() ?: true
        set(v) {
            valores.setProperty(SUBIR_MAKERONLINE, v.toString())
            guardar()
        }

    /** En Subir, si se sube a Creality Cloud (por defecto, sí). Solo cuenta si hay sesión. */
    var subirCreality: Boolean
        get() = valores.getProperty(SUBIR_CREALITY)?.toBooleanStrictOrNull() ?: true
        set(v) {
            valores.setProperty(SUBIR_CREALITY, v.toString())
            guardar()
        }

    /** En Subir, si se sube a MakerWorld (por defecto, sí). Solo cuenta si hay sesión. */
    var subirMakerWorld: Boolean
        get() = valores.getProperty(SUBIR_MAKERWORLD)?.toBooleanStrictOrNull() ?: true
        set(v) {
            valores.setProperty(SUBIR_MAKERWORLD, v.toString())
            guardar()
        }

    /** En Descargar, cuántos de los últimos modelos de cada usuario se miran (por defecto, 10, como en la terminal). */
    var ultimosModelos: Int
        get() = valores.getProperty(ULTIMOS_MODELOS)?.toIntOrNull()?.takeIf { it >= 1 } ?: 10
        set(v) {
            valores.setProperty(ULTIMOS_MODELOS, v.toString())
            guardar()
        }

    /** En Descargar, el máximo de descargas de cada vez; null = sin límite (por defecto). */
    var maximoDescargas: Int?
        get() = valores.getProperty(MAXIMO_DESCARGAS)?.toIntOrNull()?.takeIf { it >= 1 }
        set(v) {
            if (v == null) valores.remove(MAXIMO_DESCARGAS) else valores.setProperty(MAXIMO_DESCARGAS, v.toString())
            guardar()
        }

    /** Si la ventana se abre ocupando toda la pantalla (por defecto, sí, como siempre). */
    var ventanaMaximizada: Boolean
        get() = valores.getProperty(VENTANA_MAXIMIZADA)?.toBooleanStrictOrNull() ?: true
        set(v) {
            if (v == ventanaMaximizada) return
            valores.setProperty(VENTANA_MAXIMIZADA, v.toString())
            guardar()
        }

    /** Ancho y alto de la ventana la última vez que estuvo en modo ventana; null = el de siempre. */
    var tamanoVentana: Pair<Int, Int>?
        get() {
            val ancho = valores.getProperty(VENTANA_ANCHO)?.toIntOrNull()?.takeIf { it >= 1 } ?: return null
            val alto = valores.getProperty(VENTANA_ALTO)?.toIntOrNull()?.takeIf { it >= 1 } ?: return null
            return ancho to alto
        }
        set(v) {
            if (v == tamanoVentana) return
            if (v == null) {
                valores.remove(VENTANA_ANCHO)
                valores.remove(VENTANA_ALTO)
            } else {
                valores.setProperty(VENTANA_ANCHO, v.first.toString())
                valores.setProperty(VENTANA_ALTO, v.second.toString())
            }
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
        const val SUBIR_MAKERONLINE = "subirMakerOnline"
        const val SUBIR_CREALITY = "subirCreality"
        const val SUBIR_MAKERWORLD = "subirMakerWorld"
        const val ULTIMOS_MODELOS = "ultimosModelos"
        const val MAXIMO_DESCARGAS = "maximoDescargas"
        const val VENTANA_MAXIMIZADA = "ventanaMaximizada"
        const val VENTANA_ANCHO = "ventanaAncho"
        const val VENTANA_ALTO = "ventanaAlto"
    }
}
