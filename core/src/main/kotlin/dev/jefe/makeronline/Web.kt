package dev.jefe.makeronline

/**
 * Las webs a las que sube la app. Cada una tiene su sesión en el mismo perfil de Chrome
 * (chrome-profile/): sus cookies no se mezclan.
 *
 * El orden es el de las pantallas (de izquierda a derecha).
 *
 * @property nombre   como se llama en la app ("Creality Cloud")
 * @property inicio   la página que se abre para iniciar sesión
 * @property descarga si la app sabe descargar de ella (si no, solo sube y no sale en Descargar)
 */
enum class Web(val nombre: String, val inicio: String, val descarga: Boolean = true) {
    MAKERONLINE("MakerOnline", "https://www.makeronline.com/en/"),
    CREALITY("Creality Cloud", "https://www.crealitycloud.com/"),
    NEXPRINT("Nexprint", "https://www.nexprint.com/en", descarga = false),
}
