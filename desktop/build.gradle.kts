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

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
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
            packageName = "MakerOnline Desktop"
            // Súbelo en cada versión nueva (1.0.1, 1.1.0…): así el instalador sustituye a la anterior
            packageVersion = "1.0.0"
            description = "Subir y descargar modelos 3D de MakerOnline"
            vendor = "jesuscarrasco.dev"
            copyright = "Copyright 2026 jesuscarrasco.dev"
            // Java completo dentro de la app: así no falta nada que usen Playwright, Jackson o las
            // peticiones HTTPS. Ocupa algo más; se puede afinar más adelante con la tarea suggestModules.
            includeAllModules = true
            windows {
                iconFile.set(project.file("icono.ico"))
                // Se instala solo para el usuario, sin pedir permisos de administrador, en
                // %LOCALAPPDATA%\Programs\MakerOnline Desktop. ¡Nunca en %LOCALAPPDATA%\MakerOnline Desktop!:
                // al desinstalar o actualizar, Windows borra entera la carpeta de instalación, y esa es la
                // carpeta de datos (sesión, usuarios.json, subidas…). Ver CarpetaDatos.
                perUserInstall = true
                installationPath = "Programs\\MakerOnline Desktop"
                dirChooser = false
                // Acceso directo en el escritorio y en el menú Inicio
                shortcut = true
                menu = true
                menuGroup = "MakerOnline Desktop"
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
