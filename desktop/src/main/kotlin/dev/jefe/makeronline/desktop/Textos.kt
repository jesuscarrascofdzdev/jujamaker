package dev.jefe.makeronline.desktop

import dev.jefe.makeronline.Web

/**
 * Todos los textos que ve la persona, en un único sitio.
 * Para traducir la app más adelante, solo habrá que tocar aquí.
 */
object Textos {
    const val TITULO_APP = "JujaMaker"

    // ------------------------------------------------------------ menú
    const val MENU_SESION = "Sesión"
    const val MENU_SUBIR = "Subir"
    const val MENU_DESCARGAR = "Descargar"

    // ------------------------------------------------------------ modo de la ventana
    const val PASAR_A_VENTANA = "Pasar a modo ventana"
    const val PASAR_A_PANTALLA_COMPLETA = "Pasar a pantalla completa"


    // ------------------------------------------------------------ sesión
    const val SESION_TITULO = "Sesión"
    const val SESION_EXPLICACION =
        "La app usa tu Google Chrome con un perfil propio. Inicias sesión en cada web una vez y se queda " +
            "guardada en este ordenador; nunca sale de él."
    const val CHROME = "Google Chrome"
    const val CHROME_OK = "Instalado"
    const val CHROME_FALTA = "No lo encuentro. Sin Chrome la app no puede funcionar."
    const val DESCARGAR_CHROME = "Descargar Google Chrome"
    const val URL_CHROME = "https://www.google.com/chrome/"
    const val MAKERONLINE = "MakerOnline"
    const val COMPROBANDO = "Comprobando…"
    const val SIN_COMPROBAR = "Sin comprobar"
    const val SESION_INICIADA = "Sesión iniciada"
    fun sesionIniciadaComo(nombre: String) = "Sesión iniciada como $nombre"
    fun sinSesion(motivo: String?) = "Sin sesión" + (motivo?.let { ": $it" } ?: "")
    /** En la barra de arriba: cuántas webs tienen sesión. */
    fun sesionesEnBarra(con: Int, total: Int) = "Sesiones: $con de $total"
    /** En el detalle de la barra de arriba: la cuenta (si se sabe), "con sesión" o "sin sesión". */
    fun estadoEnBarra(conSesion: Boolean, cuenta: String?) = if (!conSesion) "sin sesión" else cuenta ?: "con sesión"
    const val WEBS_TITULO = "Webs donde subes"
    fun comprobadoA(hora: String) = "Comprobado a las $hora"
    const val SIN_SESION = "Sin sesión"
    fun sesionIniciadaCorta(cuenta: String?) = if (cuenta == null) SESION_INICIADA else "$SESION_INICIADA · $cuenta"
    /** Debajo del nombre de cada web, en Sesión. Al añadir una web nueva, el compilador pide ponerle el suyo. */
    fun comoSube(web: Web) = when (web) {
        Web.MAKERONLINE -> "Sube como borrador"
        Web.CREALITY -> "Sube como privado · solo carpetas con .3mf"
    }
    const val CREALITY_NOTA = "Si la sesión caduca, la app te avisará al subir."
    const val INICIAR_SESION = "Iniciar sesión"
    const val VOLVER_A_INICIAR = "Volver a iniciar sesión"
    const val COMPROBAR = "Comprobar otra vez"
    const val INSTRUCCIONES_LOGIN =
        "Se ha abierto Chrome en MakerOnline. Pulsa \"Log in\", elige \"Continuar con Google\" y entra " +
            "con tu cuenta. No inicies sesión en el propio Chrome (el botón de perfil de arriba a la " +
            "derecha), solo en la web. Cuando veas tu avatar en MakerOnline, vuelve aquí."
    const val INSTRUCCIONES_LOGIN_CREALITY =
        "Se ha abierto Chrome en Creality Cloud. Pulsa el botón para iniciar sesión (arriba a la derecha) y " +
            "entra con tu cuenta. No inicies sesión en el propio Chrome (el botón de perfil de arriba a la " +
            "derecha), solo en la web. Cuando veas tu avatar en Creality Cloud, vuelve aquí."
    fun instruccionesLogin(web: Web) = when (web) {
        Web.MAKERONLINE -> INSTRUCCIONES_LOGIN
        Web.CREALITY -> INSTRUCCIONES_LOGIN_CREALITY
    }
    const val YA_HE_ENTRADO = "Ya he entrado"
    const val CANCELAR = "Cancelar"
    fun carpetaDatos(ruta: String) = "Carpeta de datos: $ruta"
    const val CARPETA_DATOS_TITULO = "Carpeta de datos"
    const val ABRIR_CARPETA_DATOS = "Abrir"

    // ------------------------------------------------------------ conectar con Claude
    const val CLAUDE_TITULO = "Conectar con Claude"
    const val CLAUDE_EXPLICACION =
        "Usa la app desde Claude (la aplicación de escritorio o Claude Code) con tu cuenta de Claude: " +
            "«¿qué tengo pendiente?», «mira la foto de flower y escríbele la descripción»… Cierra Claude " +
            "antes de pulsar el botón y, después, ábrelo otra vez."
    const val CLAUDE_CONECTADO = "Conectado: Claude puede usar la app."
    const val CLAUDE_ETIQUETA_CONECTADO = "Conectado"
    const val CLAUDE_NO_CONECTADO = "Sin conectar."
    const val CLAUDE_OTRA_RUTA = "Claude tiene un conector de una versión anterior o de otra copia de la app: pulsa Conectar para usar esta."
    const val CLAUDE_SIN_CLAUDE = "No encuentro la aplicación de Claude en este ordenador. Instálala y ábrela una vez antes de conectar."
    const val CLAUDE_SIN_APP = "Para conectar hace falta la app instalada (con su instalador): desde IntelliJ no se puede."
    const val CLAUDE_CONECTAR = "Conectar con Claude"
    const val CLAUDE_RECONECTAR = "Volver a conectar"
    const val CLAUDE_QUITAR = "Quitar"
    const val CLAUDE_COPIAR_COMANDO = "Copiar el comando para Claude Code"
    const val CLAUDE_CONECTADO_REINICIA =
        "✓ Hecho. Ahora cierra Claude del todo (también desde el icono junto al reloj) y ábrelo otra vez."
    const val CLAUDE_QUITADO_REINICIA = "✓ Conector quitado. Cierra Claude del todo y ábrelo otra vez."
    const val CLAUDE_COMANDO_COPIADO = "✓ Comando copiado: pégalo en una terminal y pulsa Enter."
    const val NO_SE_PUDO_COPIAR = "No se ha podido copiar: vuelve a probar."
    fun configClaudeRota(ruta: String) =
        "El archivo de configuración de Claude ($ruta) no se puede leer como JSON: no lo toco, para no estropear tu configuración. Revísalo y vuelve a probar."
    fun claudeArchivo(ruta: String) = "Archivo de Claude: $ruta"

    // ------------------------------------------------------------ traer los datos de antes
    const val TRAER_TITULO = "Traer tus datos de antes"
    fun traerExplicacion(ruta: String) =
        "Hay datos de la app en $ruta. Ahora la app guarda todo en su propia carpeta. " +
            "Tráelos para seguir con tu sesión, tu lista de usuarios, lo ya descargado y tus carpetas " +
            "de subidas. Solo se copian: allí no se borra nada."
    const val TRAER = "Traer mis datos"
    const val TRAER_DE_OTRA = "Traer de otra carpeta…"
    const val ELEGIR_CARPETA_ANTERIOR = "Elige la carpeta donde estaban tus datos (la que tiene chrome-profile y usuarios.json)"
    const val TRAYENDO = "Copiando tus datos… (la sesión de Chrome puede tardar un poco)"
    const val TRAER_MISMA = "Esa es la carpeta de datos que ya usa la app: no hay nada que traer."
    fun traerNada(ruta: String) =
        "En $ruta no hay datos de la app (ni chrome-profile, ni usuarios.json, ni descargados.txt, ni subidas)."
    const val TRAIDO_SESION = "la sesión de Chrome"
    fun traidosUsuarios(n: Int) = if (n == 1) "1 usuario de la lista" else "$n usuarios de la lista"
    fun traidosDescargados(n: Int) = if (n == 1) "1 modelo ya descargado" else "$n modelos ya descargados"
    const val TRAIDOS_AJUSTES = "los ajustes"
    fun traidasSubidas(n: Int) = if (n == 1) "1 carpeta de subidas" else "$n carpetas de subidas"
    const val TRAER_NADA_NUEVO = "No había nada nuevo que traer: la app ya lo tenía todo."
    fun traidoResumen(cosas: List<String>) = "✓ Traído: " + cosas.joinToString(", ") + "."
    const val TRAER_YA_HAY_SESION =
        "La sesión de Chrome no se ha traído porque la app ya tiene una. Si no funciona, usa \"Volver a iniciar sesión\"."
    fun traerSinSesion(motivo: String) =
        "No se ha podido copiar la sesión de Chrome ($motivo). Cierra Chrome si estaba abierto y vuelve a " +
            "probar, o inicia sesión otra vez con el botón de arriba."
    fun traerSinLista(motivo: String) = "La lista de usuarios no se ha traído: $motivo"
    fun subidasRepetidas(n: Int) =
        "$n carpeta(s) de subidas no se han traído porque la app ya tiene una con el mismo nombre."

    // ------------------------------------------------------------ tareas (se ven en el menú)
    const val TAREA_COMPROBANDO = "Comprobando la sesión…"
    const val TAREA_INICIANDO_SESION = "Iniciando sesión en Chrome…"
    const val TAREA_SIMULANDO = "Simulando…"
    const val TAREA_DESCARGANDO = "Descargando…"
    const val TAREA_BUSCANDO_USUARIO = "Buscando un usuario…"
    const val TAREA_SUBIENDO = "Subiendo…"
    const val TAREA_TRAYENDO = "Trayendo tus datos…"

    // ------------------------------------------------------------ descargar
    const val DESCARGAR_TITULO = "Descargar"
    const val DESCARGAR_EXPLICACION =
        "Marca de qué páginas quieres descargar y, en cada una, de qué usuarios. Descarga sus modelos " +
            "nuevos, página a página, y al terminar cada uno le da like, así no se vuelve a bajar. Entre " +
            "descarga y descarga espera de 10 a 30 segundos. Al terminar, todo queda en un zip en tu escritorio."
    const val PAGINAS = "Páginas"
    const val PAGINAS_AYUDA = "Se descarga de las marcadas, de izquierda a derecha"
    const val USUARIOS = "Usuarios"
    /** Debajo del nombre de cada página: cuántos usuarios hay marcados en su lista. */
    fun fichaDescarga(marcados: Int) = when (marcados) {
        0 -> "Ningún usuario marcado"
        1 -> "1 usuario marcado"
        else -> "$marcados usuarios marcados"
    }
    /** La pestaña de cada lista: "MakerOnline · 7/9". */
    fun pestanaUsuarios(web: Web, marcados: Int, total: Int) = webCorta(web) + " · $marcados/$total"
    fun pestanaSinDescargar(web: Web) =
        "Ahora no se descarga de ${web.nombre}: márcala arriba, en Páginas (hace falta tener la sesión iniciada)."
    /** El botón Descargar dice de dónde: "Descargar de MakerOnline", "Descargar de las dos"… */
    fun botonDescargar(webs: List<Web>) = when (webs.size) {
        0 -> DESCARGAR
        1 -> "Descargar de " + webCorta(webs[0])
        2 -> "Descargar de las dos"
        else -> "Descargar de las ${webs.size}"
    }
    /** Al empezar con cada web, en los mensajes. */
    fun empiezaWeb(web: Web) = "══════ ${web.nombre} ══════"
    /** Un problema de una web en concreto: "MakerOnline: no hay usuarios en la lista." */
    fun problemaEn(web: Web, problema: String) = webCorta(web) + ": " + problema.replaceFirstChar { it.lowercase() }
    const val ERROR_NINGUNA_WEB_DESCARGA = "Marca al menos una página de la que descargar (arriba, en Páginas)."
    fun errorMaximo(web: Web) =
        "El máximo de descargas de ${webCorta(web)} tiene que ser un número de 1 en adelante, o dejarlo vacío."
    const val TABLA_DESCARGADOS = "Descargados"
    const val TABLA_SE_DESCARGARIAN = "Se bajarían"
    const val TABLA_SOLO_LIKE = "Solo faltaba el like"
    const val TABLA_YA_TENIAN_LIKE = "Ya tenían like"
    /** Lo que no cabe en la tabla del resumen, por web (null si no hay nada que decir). */
    fun notaResumen(web: Web, saltados: Int): String? = if (saltados == 0) null else when (web) {
        Web.MAKERONLINE -> null
        Web.CREALITY -> "Creality: $saltados no se bajan (de pago o sin perfil .3mf)."
    }
    fun usuariosTitulo(n: Int) = "Usuarios ($n)"
    const val RECARGAR = "Recargar"
    const val LISTA_NO_EXISTE = "Todavía no hay lista de usuarios. Pulsa Editar para añadir el primero."
    const val LISTA_VACIA = "La lista está vacía. Pulsa Editar para añadir usuarios."
    const val EDITAR = "Editar"
    const val CREADOR = "Creador de la app"
    const val CREADOR_DETALLE =
        "Va el primero: así apoyas la app gratis. Puedes desmarcarlo."
    const val CREADOR_FIJO = "Creador de la app · no se puede quitar; se marca o desmarca en Descargar"
    fun esCreador(nombre: String) = "$nombre (creador de la app)"
    const val MARCAR_TODOS = "Marcar todos"
    const val DESMARCAR_TODOS = "Desmarcar todos"
    fun marcados(n: Int) = if (n == 1) "1 marcado" else "$n marcados"
    const val OPCIONES = "Opciones"
    const val ULTIMOS = "Últimos modelos"
    const val ULTIMOS_AYUDA = "de cada usuario"
    const val TODOS_LOS_MODELOS = "Todos sus modelos (más lento; útil la primera vez)"
    const val PERFIL = "Descargar también el perfil de impresión (.3mf)"
    const val MAXIMO = "Máximo de descargas"
    const val MAXIMO_AYUDA = "vacío = sin límite"
    const val VENTANA_CHROME = "Ventana de Chrome"
    const val MODO_APARTADA = "Fuera de la pantalla (recomendado)"
    const val MODO_APARTADA_AYUDA = "Aparece sola si hay captcha"
    const val MODO_VISIBLE = "Visible todo el rato"
    const val MODO_SIN_VENTANA = "Sin ventana"
    const val MODO_SIN_VENTANA_AYUDA = "Si sale un captcha, se para"
    const val EN_MARCHA = "En marcha"
    const val USUARIO_HECHO = "✓ hecho"
    const val USUARIO_AHORA = "ahora"
    const val SIMULAR = "Simular"
    const val SIMULAR_AYUDA = "Solo lista lo que descargaría; no descarga nada ni da like."
    const val DESCARGAR = "Descargar"
    const val PARAR = "Parar"
    const val PARANDO = "Parando en cuanto se pueda sin dejar nada a medias…"
    fun usuarioActual(t: String) = "Usuario $t"
    fun modeloActual(t: String) = "Modelo $t"
    fun paginas(t: String) = "Leyendo su lista de modelos: página $t"
    fun esperando(s: Long) = "Esperando $s s antes del siguiente"
    fun pausa(s: Long) = "… esperando $s s antes del siguiente"
    fun captcha(para: String, minutos: Long) =
        "MakerOnline pide un captcha para $para. Resuélvelo en la ventana de Chrome (tienes $minutos minutos)."
    const val MENSAJES = "Mensajes"
    const val SIN_MENSAJES = "Aquí irá apareciendo lo que hace."
    const val RESUMEN_SIMULACION = "Simulación terminada: no se ha descargado nada ni se ha dado ningún like."
    const val RESUMEN_PARADO = "Parado (lo has pedido tú). No se ha dejado nada a medias."
    const val MOSTRAR_ZIP = "Mostrar el zip"
    const val CREANDO_ZIP = "Creando el zip en el escritorio…"
    fun zipCreado(nombre: String, modelos: Int) =
        "Zip creado en el escritorio: $nombre (" + (if (modelos == 1) "1 modelo" else "$modelos modelos") + ")"
    fun errorZip(motivo: String) =
        "✘ No se ha podido crear el zip en el escritorio: $motivo\n" +
            "  Lo descargado no se ha perdido: irá en el zip de la próxima descarga."
    const val VER_ERRORES = "Ver detalle de errores"
    const val ERROR_ULTIMOS = "Pon cuántos de los últimos modelos quieres mirar (un número de 1 en adelante)."
    const val ERROR_NINGUNO_MARCADO = "No hay ningún usuario marcado: marca al menos uno (o al creador de la app)."
    const val ERROR_LISTA_VACIA = "No hay usuarios en la lista."
    const val ERROR_LISTA_ROTA = "Arregla usuarios.json antes de descargar (el error sale en su lista)."

    // ------------------------------------------------------------ descargar: lista de Creality Cloud
    const val CREALITY_CAMPO_AGREGAR = "Añadir un usuario: pega la dirección de su perfil"
    const val CREALITY_AGREGAR_AYUDA =
        "Por ejemplo https://www.crealitycloud.com/user/8286142150 (se copia de la barra del navegador, " +
            "en su perfil). El nombre lo pone la app la primera vez que descarga."
    const val CREALITY_LISTA_VACIA = "Todavía no hay nadie en esta lista. Añade el primero arriba."
    fun crealityUsuarioSinNombre(id: Long?) = if (id == null) "Usuario sin número" else "Usuario $id"
    fun crealityIdUsuario(id: Long) = "crealitycloud.com/user/$id"
    const val CREALITY_QUE_BAJA =
        "Baja los perfiles .3mf, la portada y los textos. De momento no baja los de pago ni los que solo tienen STL."
    const val CREALITY_ERROR_LISTA_ROTA = "Arregla usuarios-creality.json antes de descargar (el error sale en su lista)."
    const val CREALITY_ERROR_LISTA_VACIA = "No hay usuarios en la lista: añade al menos uno."
    const val CREALITY_ERROR_NINGUNO_MARCADO = "No hay ningún usuario marcado: marca al menos uno."

    // ------------------------------------------------------------ editar la lista de usuarios
    const val EDITAR_TITULO = "Editar la lista de usuarios"
    const val EDITAR_EXPLICACION =
        "Para añadir a alguien, pega la URL de su perfil de MakerOnline (lo más fiable) o escribe su @ " +
            "o su nombre visible. Antes de añadirlo se comprueba en MakerOnline que existe, y se guarda " +
            "como \"Nombre · URL de su perfil\", que es lo que mejor funciona al descargar."
    const val CAMPO_AGREGAR = "URL de su perfil, @ o nombre"
    const val AGREGAR = "Añadir"
    const val BUSCANDO_USUARIO = "Buscando en MakerOnline…"
    fun chromeOcupado(tarea: String) = "Chrome está ocupado ($tarea). Podrás añadir cuando termine; quitar sí se puede."
    fun agregado(nombre: String, handle: String) = "✓ Añadido: $nombre" + (if (handle.isBlank()) "" else " ($handle)")
    fun yaEsta(nombre: String, como: String) = "$nombre ya está en la lista (como \"$como\")."
    fun quitado(nombre: String) = "Quitado: $nombre"
    const val SIN_USUARIOS = "Todavía no hay nadie en la lista. Añade el primero arriba."
    const val QUITAR = "Quitar"
    const val QUITAR_PREGUNTA = "¿Quitarlo?"
    const val SI_QUITAR = "Sí, quitar"
    const val NO = "No"
    const val NOTA_COPIA =
        "Los cambios se guardan al momento en usuarios.json. La primera vez se guarda una copia del " +
            "archivo tal como estaba en usuarios.json.bak (los comentarios que tuviera se quedan ahí)."
    const val CERRAR = "Cerrar"
    const val GUARDAR = "Guardar"
    const val ELIGE = "Elige…"

    // ------------------------------------------------------------ subir
    const val SUBIR_TITULO = "Subir"
    const val SUBIR_EXPLICACION =
        "Sube cada carpeta de pendientes (un modelo con su .3mf o .stl, una imagen y la descripción) a las webs " +
            "que marques: a MakerOnline como borrador y a Creality Cloud como privado."
    const val FORMATO_CARPETAS =
        "Los nombres de los archivos dan igual. La portada es la imagen que se llama portada, cover o como la " +
            "carpeta; si no, la primera. Los textos van en un .json o un .txt. A Creality Cloud solo se sube con " +
            ".3mf. Cuando una carpeta está en todas las webs marcadas pasa a subidos/; si falla MakerOnline, a " +
            "errores/ (con una captura). Si falla Creality, se queda en pendientes con error-creality.txt y la " +
            "próxima vez solo se repite lo que falta."
    fun pendientesTitulo(n: Int) = "Pendientes ($n)"
    const val ABRIR_PENDIENTES = "Abrir la carpeta"
    const val VER_ERRORES_SUBIDA = "Ver errores"
    const val SOLTAR_AQUI =
        "Puedes arrastrar aquí carpetas de modelos (se copian; las tuyas no se tocan)."
    const val SUELTA_AHORA = "Suéltalas aquí"
    const val ANADIR_CARPETAS = "Añadir carpetas…"
    const val ELEGIR_CARPETAS = "Elige las carpetas de los modelos"
    const val COPIANDO = "Copiando a pendientes…"
    fun carpetasAnadidas(nombres: List<String>) = "✓ Añadidas a pendientes: " + nombres.joinToString(", ")
    fun noEsCarpeta(nombre: String) = "✘ $nombre no es una carpeta: arrastra la carpeta del modelo, no sus archivos."
    fun yaEnPendientes(nombre: String) = "✘ $nombre ya está en pendientes."
    fun contienePendientes(nombre: String) = "✘ $nombre contiene la propia carpeta de pendientes: no se copia."
    fun yaExisteEnPendientes(nombre: String) =
        "✘ Ya hay una carpeta $nombre en pendientes: cámbiale el nombre a una de las dos."
    fun demasiadosArchivos(nombre: String, max: Int) =
        "✘ $nombre tiene más de $max archivos: no parece la carpeta de un modelo."
    fun noSePuedeCopiar(nombre: String, motivo: String) = "✘ No he podido copiar $nombre: $motivo"
    const val NADA_EN_PENDIENTES = "No hay nada en pendientes."
    const val LISTA_PARA_SUBIR = "Lista para subir"
    const val LE_FALTA_ALGO = "Le falta algo"
    const val SIN_PORTADA = "sin portada"
    const val SUBIR_AHORA = "Subir ahora"
    const val DONDE_SUBIR = "Dónde (pulsa para marcar o desmarcar)"
    /** En la ficha de cada web, debajo de su nombre. */
    fun fichaWeb(web: Web) = when (web) {
        Web.MAKERONLINE -> "Borrador"
        Web.CREALITY -> "Privado · solo .3mf"
    }
    const val INICIAR_SESION_FLECHA = "Iniciar sesión →"
    const val IR_A_SESION = "Ir a Sesión →"
    /** El botón Subir dice adónde: "Subir a MakerOnline", "Subir a las dos"… */
    fun botonSubir(webs: List<Web>) = when (webs.size) {
        0 -> SUBIR
        1 -> "Subir a " + webs[0].nombre
        2 -> "Subir a las dos"
        else -> "Subir a las ${webs.size}"
    }
    const val QUE_CARPETAS = "Qué carpetas"
    const val POR_DEFECTO = " (por defecto)"
    fun todasLasValidas(n: Int) = "Todas las que están bien ($n)"
    const val SOLO_LA_SELECCIONADA = "Solo la seleccionada"
    const val REVISAR = "Revisar cada modelo antes de guardarlo"
    const val SUBIR = "Subir"
    const val VER_CHROME_AL_SUBIR = "Ver Chrome mientras sube"
    fun subirAyuda(verChrome: Boolean, revisar: Boolean) = when {
        verChrome -> "Chrome se abre a la vista mientras sube."
        revisar -> "Chrome trabaja fuera de la pantalla y aparece para que revises cada modelo."
        else -> "Chrome trabaja fuera de la pantalla: no lo verás."
    }
    fun revisarAhora(modelo: String) =
        "Revisa el formulario de $modelo en la ventana de Chrome. Cuando lo hayas mirado, elige aquí:"
    const val GUARDAR_BORRADOR = "Guardar borrador"
    const val ENVIAR_PRIVADO = "Enviar como privado"
    const val DESCARTAR = "Descartar (se queda en pendientes)"
    const val PARANDO_SUBIDA = "Parando: lo que no se haya guardado se queda en pendientes…"
    const val RESUMEN_SUBIDA_PARADA = "Parado (lo has pedido tú). Lo que no se ha guardado sigue en pendientes."
    const val ULTIMA_SUBIDA = "Última subida"
    const val TABLA_WEB = "Web"
    const val TABLA_SUBIDOS = "Subidos"
    const val TABLA_CON_ERROR = "Con error"
    const val TABLA_SIN_SUBIR = "Sin subir"
    fun yaEstabanEnCreality(n: Int) =
        if (n == 1) "1 ya estaba en Creality Cloud." else "$n ya estaban en Creality Cloud."
    fun sinCrealityPorStl(nombres: List<String>) =
        "No van a Creality Cloud porque solo tienen .stl: " + nombres.joinToString(", ")
    fun sinMakerOnline(nombres: List<String>) =
        "No han ido a MakerOnline porque antes tienen que estar en Creality Cloud (siguen en pendientes): " +
            nombres.joinToString(", ")
    fun pasanASubidos(n: Int) =
        if (n == 1) "1 carpeta pasa a subidos (ya está en Creality Cloud)." else "$n carpetas pasan a subidos (ya están en Creality Cloud)."
    const val ERRORES_CREALITY_EN_LISTA =
        "Las que han fallado en Creality siguen en pendientes: en la lista tienen el enlace a su error."
    const val ABRIR_SUBIDOS = "Abrir subidos"
    const val ABRIR_ERRORES = "Abrir errores"
    fun verError(carpeta: String) = "Ver el error de $carpeta"
    /** El nombre corto de cada web, para las insignias de la lista. */
    fun webCorta(web: Web) = when (web) {
        Web.MAKERONLINE -> "MakerOnline"
        Web.CREALITY -> "Creality"
    }
    /** Las insignias de cada carpeta: "Creality · subido", "MakerOnline · espera a Creality"… */
    fun insignia(web: Web, estado: EstadoEnWeb) = webCorta(web) + " · " + when (estado) {
        EstadoEnWeb.SUBIDO -> "subido"
        EstadoEnWeb.ERROR -> "error · ver"
        EstadoEnWeb.FALTA -> "falta"
        EstadoEnWeb.ESPERA -> "espera a Creality"
        EstadoEnWeb.SIN_3MF -> "no va (solo .stl)"
    }
    const val COMO_CARPETA = "¿Cómo tiene que ser una carpeta?"
    // ------------------------------------------------------------ Creality: los que ya están en MakerOnline
    fun crealitySubidos(n: Int) =
        (if (n == 1) "1 carpeta ya subida a MakerOnline (en subidos) aún no está" else "$n carpetas ya subidas a MakerOnline (en subidos) aún no están") +
            " en Creality Cloud. Se suben con las mismas opciones de arriba (ver Chrome, revisar) y no se mueven."
    const val CREALITY_SUBIDOS_BOTON = "Subir a Creality las de subidos"
    const val CREALITY_SUBIDOS_NADA = "No hay ninguna carpeta en subidos que falte en Creality Cloud (con .3mf)."
    // ------------------------------------------------------------ Printables (extensión de Chrome)
    const val PR_TITULO = "Printables"
    const val PR_EXPLICACION =
        "Printables no deja entrar al Chrome de la app, así que se sube desde tu Chrome de siempre con la " +
            "extensión de JujaMaker. Al pulsar \"Subir a Printables\" se abre tu Chrome y la extensión rellena cada " +
            "modelo sola (como modelo original y sin IA), lo guarda como borrador y pasa sola al " +
            "siguiente. Luego revisas los borradores en Printables y los publicas tú. Entran las carpetas que metes en la app y aún no están allí (también cuando pasan a subidos al subirlas a MakerOnline); las que ya estaban en subidos de antes, no."
    fun prCuenta(porSubir: Int, conProblemas: Int, subidas: Int) =
        (if (porSubir == 1) "1 por subir" else "$porSubir por subir") +
            (if (conProblemas > 0) " · $conProblemas con problemas" else "") +
            " · $subidas ya en Printables"
    fun prUltima(texto: String) = "Apuntado: $texto"
    const val PR_ABRIR = "Subir a Printables"
    const val PR_CARPETA_EXTENSION = "Carpeta de la extensión"
    const val PR_INSTALAR =
        "Instalarla (una vez): en Chrome abre chrome://extensions, activa \"Modo de desarrollador\", pulsa " +
            "\"Cargar descomprimida\" y elige la carpeta de la extensión. Esta app tiene que estar abierta mientras subes. " +
            "Si tu navegador por defecto no es Chrome, abre Printables en Chrome y pulsa \"Seguido\" en el panel."
    // ------------------------------------------------------------ Descargar de Printables (extensión de Chrome)
    const val PRD_TITULO = "Printables"
    const val PRD_EXPLICACION =
        "Printables no deja entrar al Chrome de la app, así que descarga la extensión de JujaMaker en tu Chrome " +
            "de siempre (la misma que para subir). Al pulsar \"Descargar de Printables\" se abre Printables y la " +
            "extensión baja los modelos nuevos de los usuarios marcados, igual que en las otras webs: los últimos de " +
            "cada uno (los de arriba), solo los gratis, salta los que ya tienen tu like, les da like al terminar y " +
            "deja todo en el mismo zip del escritorio. La app tiene que estar abierta mientras tanto."
    const val PRD_CAMPO_AGREGAR = "Añadir un usuario: pega la dirección de su perfil"
    const val PRD_AGREGAR_AYUDA =
        "Por ejemplo https://www.printables.com/@VC_Design (o solo @VC_Design). Puedes poner un nombre delante: " +
            "\"VC Design · https://www.printables.com/@VC_Design\"."
    const val PRD_LISTA_VACIA = "Todavía no hay nadie en esta lista. Añade el primero arriba."
    const val PRD_DESCARGAR = "Descargar de Printables"
    const val PRD_REABRIR = "Volver a abrir Printables"
    const val PRD_TAREA = "Descargando de Printables…"
    const val PRD_ABIERTO =
        "Abierto Printables en tu navegador: la extensión empieza sola (recuadro abajo a la izquierda)."
    const val PRD_ESPERANDO =
        "Esperando a la extensión… Si no arranca: abre Printables en Chrome con \"Volver a abrir Printables\", " +
            "y comprueba en chrome://extensions que la extensión de JujaMaker está activada y actualizada (botón ↻)."
    const val PRD_ERROR_MAXIMO = "Printables: el máximo de descargas tiene que ser un número de 1 en adelante (o vacío para sin límite)."
    const val PRD_ERROR_LISTA_ROTA = "Printables: la lista de usuarios tiene un error (arréglala en usuarios-printables.json y pulsa Recargar)."
    const val PRD_ERROR_LISTA_VACIA = "Printables: añade al menos un usuario a la lista."
    const val PRD_ERROR_NINGUNO_MARCADO = "Printables: marca al menos un usuario."
    fun prdUsuarioSinNombre(handle: String?) = if (handle == null) "(sin usuario)" else "@$handle"
    fun prdResumen(r: dev.jefe.makeronline.DescargasPrintables.Resumen) = buildString {
        append(if (r.simulado) "Se descargarían ${r.descargados}" else "Descargados ${r.descargados}")
        if (r.soloLike > 0) append(" · ${r.soloLike} solo like")
        if (r.yaTenianLike > 0) append(" · ${r.yaTenianLike} ya tenían like")
        if (r.saltados > 0) append(" · ${r.saltados} saltados")
        if (r.errores > 0) append(" · ${r.errores} con error")
        if (r.parado) append(" · parado")
    }

    // ------------------------------------------------------------ ventana Descripción
    const val AGREGAR_DESCRIPCION = "Agregar descripción"
    const val EDITAR_DESCRIPCION = "Editar descripción"
    const val QUITAR_DESCRIPCION = "Quitar descripción"
    const val BOTON_DESCRIPCION = "Descripción"
    const val BOTON_EDITAR = "Editar"
    const val BOTON_ELIMINAR = "Eliminar"
    const val BOTON_PEGAR = "Pegar descripción"

    // ------------------------------------------------------------ ventana Pegar descripción
    fun tituloPegar(carpeta: String) = "Pegar descripción: $carpeta"
    const val PEGAR_EXPLICACION =
        "Pega aquí la descripción que te haya dado una IA (ChatGPT, Gemini, Claude…). La app la reparte en " +
            "título, categoría, etiquetas y descripción, y la abre para que la revises antes de guardar."
    const val PEGAR_CAMPO = "Texto de la IA"
    const val PEGAR_DEL_PORTAPAPELES = "Pegar del portapapeles"
    const val PEGAR_NADA = "En el portapapeles no hay texto: copia antes la respuesta de la IA."
    const val PEGAR_RELLENAR = "Rellenar"
    const val REVISA_PEGADO = "Textos pegados: revísalos y pulsa Guardar (todavía no se ha guardado nada)."
    fun etiquetasRecortadas(n: Int) = "el texto trae $n etiquetas y MakerOnline admite 20: se han quedado las 20 primeras"
    const val ELIMINAR_PREGUNTA = "¿Eliminarla?"
    const val SI_ELIMINAR = "Sí, eliminar"
    fun eliminadaPapelera(n: String) = "✓ $n eliminada: está en la papelera de Windows, por si la necesitas."
    fun eliminadaEn(n: String, ruta: String) = "✓ $n quitada de pendientes: está en $ruta"
    fun noSePuedeEliminar(n: String, motivo: String) =
        "✘ No he podido eliminar $n: $motivo (¿la tienes abierta en el Explorador o en otro programa?)"
    fun masProblemas(n: Int) = " (+$n más; pulsa la carpeta para verlos)"
    fun tituloDescripcion(carpeta: String) = "Descripción de $carpeta"
    const val CAMPO_TITULO = "Título"
    const val CAMPO_ETIQUETAS = "Etiquetas"
    const val ETIQUETAS_AYUDA = "Separadas por comas o una por línea"
    const val CAMPO_LICENCIA = "Licencia"
    const val CAMPO_CATEGORIA = "Categoría"
    const val CAMPO_SUBCATEGORIA = "Subcategoría"
    const val CAMPO_TIPO = "Tipo de impresión"
    const val CAMPO_DESCRIPCION = "Descripción"
    fun categoriaElegida(c: String) = "Categoría elegida: $c"
    const val SIN_CATEGORIA = "Sin categoría elegida"
    const val FALTAN_OPCIONES =
        "Todavía faltan las listas completas de MakerOnline (salen del Diagnóstico del formulario). " +
            "De momento solo están las opciones ya comprobadas y las que tenga la carpeta."
    const val TEXTOS_COMPLETOS = "✓ Completo: se puede subir"
    fun faltanTextos(campos: List<String>) = "Para poder subirlo falta: " + campos.joinToString(", ") +
        ". Puedes guardar igual y terminarlo luego."
    fun descripcionGuardada(carpeta: String, archivo: String) = "✓ Descripción de $carpeta guardada en $archivo"
    // ------------------------------------------------------------ ventana Agregar / Editar modelo
    const val AGREGAR_MODELO = "Agregar modelo"
    const val BOTON_AGREGAR_MODELO = "+ Agregar modelo"
    fun editarModelo(carpeta: String) = "Editar $carpeta"
    const val IMAGEN = "Imagen (portada)"
    const val MODELO = "Modelo (.3mf o .stl)"
    const val SIN_IMAGEN = "Sin imagen. Arrástrala aquí o pulsa Elegir."
    const val SIN_MODELO = "Sin modelo. Arrástralo aquí o pulsa Elegir."
    const val SIN_DESCRIPCION = "Sin descripción."
    const val ELEGIR = "Elegir…"
    const val QUITAR_ARCHIVO = "Quitar"
    const val ELEGIR_IMAGEN = "Elige la imagen del modelo"
    const val ELEGIR_MODELO = "Elige el archivo del modelo"
    const val FILTRO_IMAGEN = "Imágenes (.jpg, .png)"
    const val FILTRO_MODELO = "Modelos (.3mf, .stl)"
    const val NO_ES_IMAGEN = "Eso no es una imagen .jpg o .png."
    const val NO_ES_MODELO = "Eso no es un archivo .3mf o .stl."
    fun otrosArchivos(n: Int) = "La carpeta tiene $n imagen(es) o modelo(s) más: se quedan como están."
    const val FALTA_IMAGEN = "imagen"
    const val FALTA_MODELO = "modelo"
    const val FALTA_DESCRIPCION = "descripción"
    const val MODELO_COMPLETO = "✓ Completo: se puede subir"
    fun faltaModelo(cosas: List<String>) = "Para poder subirlo falta: " + cosas.joinToString(", ") + ". Puedes guardar igual."
    const val NOMBRE_CARPETA = "Nombre de la carpeta"
    const val PIDE_NOMBRE = "¿Cómo se llama la carpeta? Escribe el nombre y pulsa Guardar otra vez."
    const val NOMBRE_VACIO = "Escribe un nombre para la carpeta."
    const val NOMBRE_CARACTERES = "El nombre no puede tener estos caracteres: \\ / : * ? \" < > |"
    const val NOMBRE_PUNTO = "El nombre no puede acabar en punto."
    fun nombreRepetido(n: String) = "Ya hay una carpeta \"$n\" en pendientes: elige otro nombre."
    fun modeloGuardado(carpeta: String) = "✓ Guardado en pendientes/$carpeta"
    const val ERROR_SIN_CARPETA = "Elige una carpeta de la lista, o marca \"Todas las que están bien\"."
    const val ERROR_CARPETA_CON_PROBLEMAS = "La carpeta seleccionada tiene problemas: arréglalos antes de subirla."
    const val ERROR_NADA_PARA_SUBIR = "No hay ninguna carpeta lista para subir en pendientes."
    const val ERROR_NINGUNA_WEB = "Marca al menos una web donde subir."
    const val ERROR_SIN_SESIONES = "No hay sesión en ninguna web: iníciala en la pantalla Sesión."
    const val ERROR_SESION_SIN_COMPROBAR =
        "Todavía no sé en qué webs tienes la sesión iniciada: espera un momento o compruébalo en la pantalla Sesión."

    // ------------------------------------------------------------ errores
    fun errorInesperado(e: Throwable) =
        "Algo ha fallado: " + (e.message?.lineSequence()?.firstOrNull() ?: e.toString())
}
