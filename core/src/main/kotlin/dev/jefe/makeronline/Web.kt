package dev.jefe.makeronline

/**
 * Las webs a las que sube la app. Cada una tiene su sesión en el mismo perfil de Chrome
 * (chrome-profile/): sus cookies no se mezclan.
 *
 * @property nombre como se llama en la app ("Creality Cloud")
 * @property inicio la página que se abre para iniciar sesión
 */
enum class Web(val nombre: String, val inicio: String) {
    MAKERONLINE("MakerOnline", "https://www.makeronline.com/en/"),
    CREALITY("Creality Cloud", "https://www.crealitycloud.com/"),
    MAKERWORLD("MakerWorld", "https://makerworld.com/en"),
}
