package dev.jefe.makeronline

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Leer y guardar los textos de una carpeta de modelo desde los formularios de la app.
 * A diferencia de ModelFolder.load, no exige que la carpeta esté completa: se puede guardar
 * a medias y la lista de pendientes dice lo que falta.
 */
object TextosCarpeta {

    private val JSON = ObjectMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)

    /**
     * Lo que hay en la carpeta.
     *
     * @property archivo  el .json o .txt actual; null si no hay ninguno (se creará info.json)
     * @property meta     los textos, o null si no hay o no se pueden leer
     * @property problema por qué no se pueden leer, para enseñarlo, o null
     */
    data class Lectura(val archivo: Path?, val meta: ModelMeta?, val problema: String?)

    /**
     * Lee los textos con las mismas reglas que la subida (el .json; si no hay, el .txt).
     * Si hay varios archivos de textos, no elige ninguno: devuelve el problema sin archivo.
     */
    @JvmStatic
    fun leer(dir: Path): Lectura {
        val candidatos = ModelFolder.archivosDeTextos(dir)
        if (candidatos.size > 1) {
            return Lectura(
                null, null,
                "Hay varios archivos de textos (${candidatos.joinToString(", ") { it.fileName.toString() }}): " +
                    "deja solo uno antes de editar la descripción.",
            )
        }
        val archivo = candidatos.firstOrNull() ?: return Lectura(null, null, null)
        return try {
            Lectura(archivo, ModelFolder.leerTextos(archivo), null)
        } catch (e: Exception) {
            val motivo = e.message?.lineSequence()?.firstOrNull() ?: e.toString()
            Lectura(
                archivo, null,
                "No se puede leer ${archivo.fileName} ($motivo). Si guardas, se sustituye por lo que pongas aquí.",
            )
        }
    }

    /**
     * Guarda los textos en [archivo] (en su formato, .json o .txt) o, si es null, en info.json.
     * Devuelve dónde se han guardado.
     */
    @JvmStatic
    fun guardar(dir: Path, archivo: Path?, meta: ModelMeta): Path {
        val destino = archivo ?: dir.resolve("info.json")
        val texto = if (destino.fileName.toString().lowercase().endsWith(".txt")) {
            TextosModelo.escribir(meta)
        } else {
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(meta) + "\n"
        }
        // Se escribe aparte y luego se cambia por el bueno: si algo falla a medias, no queda cortado
        val temporal = destino.resolveSibling(destino.fileName.toString() + ".tmp")
        Files.writeString(temporal, texto, Charsets.UTF_8)
        try {
            Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING)
        }
        return destino
    }
}
