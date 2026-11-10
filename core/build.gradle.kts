// core: la lógica de MakerOnline (sesión, subir, descargar, likes, registro, candado).
// El código heredado de makeronline-agent está en Java (src/main/java) y las piezas
// nuevas en Kotlin (src/main/kotlin). Java y Kotlin se usan entre sí sin problema.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // Java 21 para Kotlin y para Java
    jvmToolchain(21)
}

dependencies {
    // Automatización del navegador (trae también Gson, que usa el Downloader)
    implementation(libs.playwright)
    // Leer y escribir info.json y usuarios.json
    implementation(libs.jackson.databind)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Diagnóstico de Printables desde la terminal (lo mismo que la configuración de IntelliJ
// "Diagnóstico de Printables"): .\gradlew.bat :core:diagnosticoPrintables
// Se lanza desde la carpeta del proyecto, como desde IntelliJ (Rutas.carpetaActual()), y con la
// consola conectada para poder pulsar ENTER.
tasks.register<JavaExec>("diagnosticoPrintables") {
    group = "jujamaker"
    description = "Mira si Printables deja pasar al Chrome de la app y apunta su formulario"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.jefe.makeronline.DiagnosticoPrintables")
    workingDir = rootDir
    standardInput = System.`in`
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
