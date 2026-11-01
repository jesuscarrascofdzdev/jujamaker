// desktop: la aplicación de escritorio (Compose for Desktop).
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
}

// Versión de la app. Súbela en cada versión nueva (1.0.1, 1.1.0…): así el instalador sustituye
// a la anterior. Va en el instalador y en el nombre del archivo que se deja en el escritorio.
val versionApp = "1.2.0"

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // JSON del conector para Claude (ServidorMcp, Acciones)
    implementation(libs.jackson.databind)
}

compose.desktop {
    application {
        mainClass = "dev.jefe.makeronline.desktop.MainKt"

        // El instalador se hace con el JDK 21 del proyecto (el de "jvmToolchain", que Gradle descarga
        // si hace falta), porque trae jpackage; el JDK con el que IntelliJ lanza Gradle a veces no lo tiene.
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
            .get().metadata.installationPath.asFile.absolutePath

        // Instalador de Windows (ver el README, "Crear el instalador")
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "JujaMaker"
            packageVersion = versionApp
            description = "Subir y descargar modelos 3D de MakerOnline (app no oficial)"
            vendor = "jesuscarrasco.dev"
            copyright = "Copyright 2026 jesuscarrasco.dev"
            // Java completo dentro de la app: así no falta nada que usen Playwright, Jackson o las
            // peticiones HTTPS. Ocupa algo más; se puede afinar más adelante con la tarea suggestModules.
            includeAllModules = true
            windows {
                iconFile.set(project.file("icono.ico"))
                // Se instala solo para el usuario, sin pedir permisos de administrador, en
                // %LOCALAPPDATA%\JujaMaker. ¡Nunca en %LOCALAPPDATA%\MakerOnline Desktop!:
                // al desinstalar o actualizar, Windows borra entera la carpeta de instalación, y esa es la
                // carpeta de datos (sesión, usuarios.json, subidas…). Ver CarpetaDatos.
                perUserInstall = true
                // Una sola carpeta, sin "\" ni "/":
                // - con "Programs\MakerOnline Desktop" la "\" se pierde (Compose le pasa esto a jpackage en un
                //   archivo de argumentos entre comillas): hasta la 1.0.2 se instalaba en "ProgramsMakerOnline Desktop".
                // - con "Programs/MakerOnline Desktop" el instalador no se crea: falla la validación de WiX
                //   (light.exe, código 204), seguramente porque al desinstalar nadie borraría la carpeta "Programs".
                // Hasta la 1.1.0 (cuando se llamaba MakerOnline Desktop) era "MakerOnline Desktop App".
                installationPath = "JujaMaker"
                dirChooser = false
                // Acceso directo en el escritorio y en el menú Inicio
                shortcut = true
                menu = true
                menuGroup = "JujaMaker"
                // Siempre el mismo: es lo que hace que una versión nueva sustituya a la instalada. No lo cambies.
                upgradeUuid = "682f6065-7723-4190-acfd-f5251d70be20"
            }
        }
    }
}

// La app guarda sus datos en su propia carpeta (%LOCALAPPDATA%\MakerOnline Desktop, ver CarpetaDatos),
// también al lanzarla desde IntelliJ. A "run" se le dice dónde está la carpeta del proyecto, que es
// donde estaban antes la sesión, usuarios.json y descargados.txt: la pantalla Sesión ofrece traerlos.
// Para que la app use la carpeta del proyecto como antes, cambia la línea por:
//     if (name == "run") systemProperty("makeronline.datos", rootDir.absolutePath)
tasks.withType<JavaExec>().configureEach {
    if (name == "run") systemProperty("makeronline.datosAnteriores", rootDir.absolutePath)
}

// "Crear instalador": hace el .exe (packageExe) y deja una copia en tu escritorio con un nombre sin
// espacios, JujaMaker-<versión>.exe, que es el que se manda o se sube a GitHub Releases.
// El original se queda en desktop/build/compose/binaries/main/exe/ (se rehace cada vez).
val carpetaExe = layout.buildDirectory.dir("compose/binaries/main/exe")
tasks.register("crearInstalador") {
    group = "compose desktop"
    description = "Crea el instalador .exe y deja una copia en el escritorio"
    dependsOn("packageExe")
    doLast {
        val exe = carpetaExe.get().asFile.listFiles { f -> f.name.endsWith(".exe") }
            ?.maxByOrNull { it.lastModified() }
            ?: throw GradleException("No encuentro el instalador en ${carpetaExe.get().asFile}")
        val copia = escritorio().resolve("JujaMaker-$versionApp.exe")
        exe.copyTo(copia, overwrite = true)
        logger.lifecycle("Instalador listo en tu escritorio: $copia")
    }
}

/** El escritorio de Windows (también si OneDrive lo ha movido); si no se encuentra, la carpeta personal. */
fun escritorio(): File {
    val casa = File(System.getProperty("user.home"))
    try {
        // En Windows, la "carpeta de inicio" de FileSystemView es el escritorio de verdad
        val f = javax.swing.filechooser.FileSystemView.getFileSystemView().homeDirectory
        if (f != null && f.isDirectory && f.absoluteFile != casa.absoluteFile) return f
    } catch (e: Exception) {
        logger.info("No se ha podido preguntar a Windows por el escritorio: ${e.message}")
    }
    return listOf("Desktop", "Escritorio", "OneDrive/Desktop", "OneDrive/Escritorio")
        .map { casa.resolve(it) }.firstOrNull { it.isDirectory } ?: casa
}
