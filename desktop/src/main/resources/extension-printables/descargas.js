// JujaMaker · Printables — descargar los modelos nuevos de los usuarios de la lista.
//
// La app no puede descargar de Printables (Cloudflare no deja entrar a su Chrome), así que descarga la
// extensión, en el Chrome de siempre y con la sesión de la persona. La app prepara el trabajo (a quién,
// cuántos, máximo…) y guarda lo que le llega (ver DescargasPrintables en core). Se pone en marcha cuando
// la app abre Printables con "#jujamaker-descargar"; se puede parar desde la app o desde el recuadro.
//
// La misma lógica que el Downloader de MakerOnline y DescargadorCreality: los últimos N modelos gratis
// de cada usuario; los que ya tienen tu like se saltan; lo descargado se apunta en la app y, si ya
// estaba apuntado pero sin like, solo se le da el like; al terminar cada modelo, like; un máximo por
// vez; pausa al azar entre modelos (espera + hasta "margen" segundos, contada desde el anterior).
//
// Lo que usa de Printables (comprobado el 11/11/2026 en la web, ver docs/contexto-printables.md):
//  - API GraphQL en https://api.printables.com/graphql/ con la sesión (credentials: 'include');
//  - modelos de un usuario: userModels(userId: "@handle", paid: free, ordering: "-first_publish"), por
//    páginas con "cursor"; cada uno trae "liked" (tu like), "price" y "club";
//  - datos y archivos de un modelo: print(id) { …, stls { id name fileSize } };
//  - cada archivo: getDownloadLink(id, printId, fileType: stl, source: model_detail) → dirección firmada
//    en files.printables.com (lo mismo que hace el botón "Download" de cada archivo);
//  - like: likeCreate(targetObjectId, targetType: print). OJO: el valor "print" se ha sacado del código
//    de la web, no se ha probado dando un like (no se toca la cuenta sin querer); si falla, se avisa
//    una vez y no se vuelve a intentar en esta vez.

(() => {
  if (window.__jujamakerDescargas) return;
  window.__jujamakerDescargas = true;

  const MARCA = 'jujamaker-descargar';
  const CLAVE = 'jujamaker-descargando';
  if (location.hash.includes(MARCA)) {
    sessionStorage.setItem(CLAVE, '1');
    history.replaceState(null, '', location.pathname + location.search);
  }
  if (sessionStorage.getItem(CLAVE) !== '1') return;

  const API = 'https://api.printables.com/graphql/';
  const MEDIA = 'https://media.printables.com/';
  const POR_PAGINA = 36; // lo mismo que pide la web en el perfil de un usuario
  const esperar = (ms) => new Promise((r) => setTimeout(r, ms));

  let trabajo = null;
  let parar = false;
  let likesRotos = false;

  // ------------------------------------------------------------------ recuadro

  const raiz = document.createElement('div');
  raiz.style.cssText = 'position:fixed;left:16px;bottom:16px;z-index:2147483647;';
  const sombra = raiz.attachShadow({ mode: 'open' });
  sombra.innerHTML = `
    <style>
      .caja { width: 340px; font: 13px/1.4 system-ui, sans-serif; color: #e8e8e8; background: #1e1f22;
              border: 1px solid #3a3b3f; border-radius: 10px; box-shadow: 0 6px 24px rgba(0,0,0,.4); }
      .cabeza { padding: 8px 12px; font-weight: 600; border-bottom: 1px solid #3a3b3f; }
      .cuerpo { padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; }
      .estado { color: #b5b5b5; }
      button { font: inherit; padding: 6px 10px; border-radius: 6px; border: 1px solid #c0392b;
               background: #2b2d31; color: #ff8a80; cursor: pointer; align-self: flex-start; }
      button:disabled { opacity: .45; cursor: default; }
      .registro { max-height: 160px; overflow: auto; font-size: 12px; color: #c9c9c9;
                  background: #17181a; border-radius: 6px; padding: 6px 8px; }
      .error { color: #ff8a80; } .bien { color: #7ee2a8; } .aviso { color: #ffd479; }
    </style>
    <div class="caja">
      <div class="cabeza">Printables → JujaMaker (descargando)</div>
      <div class="cuerpo">
        <div class="estado">Pidiendo el trabajo a JujaMaker…</div>
        <button class="parar">Parar</button>
        <div class="registro"></div>
      </div>
    </div>`;
  document.documentElement.appendChild(raiz);
  const $ = (s) => sombra.querySelector(s);
  $('.parar').addEventListener('click', () => {
    parar = true;
    $('.parar').disabled = true;
    estado('Parando en cuanto termine el archivo en curso…');
  });

  function estado(texto, clase) {
    $('.estado').textContent = texto;
    $('.estado').className = 'estado' + (clase ? ' ' + clase : '');
  }
  function anotar(texto, clase) {
    const linea = document.createElement('div');
    linea.textContent = texto;
    if (clase) linea.className = clase;
    $('.registro').appendChild(linea);
    $('.registro').scrollTop = $('.registro').scrollHeight;
  }

  // ------------------------------------------------------------------ la app (por background.js)

  function app(mensaje) {
    return new Promise((resolver, rechazar) => {
      chrome.runtime.sendMessage(mensaje, (r) => {
        if (chrome.runtime.lastError) return rechazar(new Error(chrome.runtime.lastError.message));
        if (!r || !r.ok) {
          const e = new Error((r && r.error) || 'sin respuesta de la extensión');
          if (/^PARAR: /.test(e.message)) parar = true; // la app ya no tiene esta descarga en marcha
          return rechazar(e);
        }
        resolver(r.datos);
      });
    });
  }

  /** Manda a la app cómo va; si la app dice que hay que parar (botón "Parar" de la app), se para. */
  async function contar(tipo, datos) {
    const clases = { error: 'error', correcto: 'bien', like: 'bien', 'solo-like': 'bien', saltado: 'aviso', 'ya-like': 'aviso', nota: 'aviso' };
    if (datos && datos.texto && tipo !== 'modelo' && tipo !== 'usuario') anotar(datos.texto, clases[tipo]);
    try {
      const r = await app({ tipo: 'descargas', ruta: 'evento', cuerpo: Object.assign({ trabajo: trabajo.id, tipo }, datos || {}) });
      if (r && r.parar) parar = true;
    } catch (_) {
      // si la app no contesta a un aviso, se sigue; si no tiene el trabajo, ya se ha puesto parar
    }
  }

  // ------------------------------------------------------------------ Printables (GraphQL)

  async function gq(operacion, consulta, variables) {
    let r;
    try {
      r = await fetch(API, {
        method: 'POST',
        credentials: 'include',
        headers: { 'content-type': 'application/json', accept: 'application/json', 'accept-language': 'en' },
        body: JSON.stringify({ operationName: operacion, query: consulta, variables }),
      });
    } catch (_) {
      throw new Error('Printables no contesta');
    }
    if (!r.ok) throw new Error('Printables responde ' + r.status + ' (' + operacion + ')');
    const j = await r.json();
    if (j.errors && j.errors.length) throw new Error('Printables: ' + j.errors.map((e) => e.message).join('; '));
    return j.data;
  }

  const Q_MODELOS = `query UserModels($id: ID!, $paid: PaidEnum, $limit: Int!, $cursor: String, $ordering: String) {
    userModels(userId: $id, paid: $paid, limit: $limit, cursor: $cursor, ordering: $ordering) {
      cursor items { id name liked price club: premium user { handle publicUsername } } } }`;
  const Q_MODELO = `query ModelFiles($id: ID!) { model: print(id: $id) {
      id name description tags { name } image { filePath } license { name }
      category { path { nameEn } } user { handle publicUsername } stls { id name fileSize } } }`;
  const M_ENLACE = `mutation GetDownloadLink($id: ID!, $modelId: ID!, $fileType: DownloadFileTypeEnum!, $source: DownloadSourceEnum!) {
      getDownloadLink(id: $id, printId: $modelId, fileType: $fileType, source: $source) {
        ok errors { field messages } output { link } } }`;
  const M_LIKE = `mutation LikeCreate($targetObjectId: ID!, $targetType: LikeTargetTypeEnum!) {
      likeCreate(targetObjectId: $targetObjectId, targetType: $targetType) { ok errors { field messages } } }`;

  /** Los últimos [n] modelos gratis de un usuario (0 = todos), los más nuevos primero. */
  async function modelosDe(handle, n) {
    const todos = [];
    let cursor = null;
    let pagina = 0;
    do {
      pagina++;
      if (n === 0) await contar('paginas', { pagina, ultima: pagina + (cursor ? 1 : 0) });
      const limite = n === 0 ? POR_PAGINA : Math.min(POR_PAGINA, n - todos.length);
      const d = await gq('UserModels', Q_MODELOS, { id: '@' + handle, paid: 'free', limit: limite, cursor, ordering: '-first_publish' });
      const p = d.userModels;
      todos.push(...(p.items || []));
      cursor = p.cursor;
      if (cursor && (n === 0 || todos.length < n)) await esperar(800); // sin prisas entre páginas
    } while (cursor && !parar && (n === 0 || todos.length < n) && pagina < 200);
    return n === 0 ? todos : todos.slice(0, n);
  }

  /** Like a un modelo. Devuelve true si se ha dado. */
  async function darLike(m) {
    if (likesRotos) return false;
    try {
      const d = await gq('LikeCreate', M_LIKE, { targetObjectId: m.id, targetType: 'print' });
      if (d.likeCreate && d.likeCreate.ok) return true;
      throw new Error(JSON.stringify((d.likeCreate && d.likeCreate.errors) || 'sin "ok"'));
    } catch (e) {
      likesRotos = true;
      await contar('nota', { texto: 'No he podido dar like (' + e.message + '). En esta vez no lo vuelvo a intentar: ' +
        'lo descargado queda apuntado igualmente y la próxima vez solo le faltará el like.' });
      return false;
    }
  }

  // ------------------------------------------------------------------ un modelo

  class SinArchivos extends Error {}

  /** Baja los .stl/.3mf, la portada y le pasa a la app los textos (info.json). */
  async function descargar(m, nombreUsuario) {
    const d = (await gq('ModelFiles', Q_MODELO, { id: m.id })).model;
    const archivos = (d.stls || []).filter((f) => /\.(stl|3mf)$/i.test(f.name || ''));
    if (!archivos.length) throw new SinArchivos('no tiene archivos .stl ni .3mf');
    const { carpeta } = await app({
      tipo: 'descargas', ruta: 'carpeta',
      cuerpo: { trabajo: trabajo.id, usuario: nombreUsuario, id: d.id, titulo: d.name },
    });
    for (const f of archivos) {
      if (parar) throw new Error('parado antes de terminar (el modelo se queda a medias y se vuelve a bajar la próxima vez)');
      await contar('paso', { texto: 'Descargando ' + f.name + ' (' + mb(f.fileSize) + ')' });
      const e = (await gq('GetDownloadLink', M_ENLACE, { id: f.id, modelId: d.id, fileType: 'stl', source: 'model_detail' })).getDownloadLink;
      if (!e || !e.ok || !e.output || !e.output.link) throw new Error('Printables no ha dado el enlace de ' + f.name);
      await app({ tipo: 'descargas-bajar', url: e.output.link, trabajo: trabajo.id, carpeta, nombre: f.name.replace(/(\.(stl|3mf))\1$/i, '$1') });
    }
    if (d.image && d.image.filePath) {
      const ext = (d.image.filePath.match(/\.(jpe?g|png|webp|gif)$/i) || ['.jpg'])[0].toLowerCase();
      try {
        await app({ tipo: 'descargas-bajar', url: MEDIA + d.image.filePath, trabajo: trabajo.id, carpeta, nombre: 'portada' + ext });
      } catch (e) {
        await contar('nota', { texto: 'No he podido bajar la portada de ' + d.name + ': ' + e.message });
      }
    }
    // info.json, lo último: si está, la descarga se completó
    await app({
      tipo: 'descargas', ruta: 'info',
      cuerpo: {
        trabajo: trabajo.id, carpeta, id: d.id, titulo: d.name,
        autor: (d.user && d.user.publicUsername) || nombreUsuario, usuario: nombreUsuario,
        categoria: ((d.category && d.category.path) || []).map((c) => c.nameEn).join(' > '),
        licencia: (d.license && d.license.name) || '',
        etiquetas: (d.tags || []).map((t) => t.name),
        descripcion: d.description || '',
      },
    });
  }

  const mb = (b) => ((b || 0) / 1048576).toFixed(1).replace('.', ',') + ' MB';

  /** Pausa entre modelos: espera + hasta "margen" s al azar, contada desde el inicio del anterior. */
  let inicioUltimo = 0;
  async function pausa() {
    if (!inicioUltimo) return;
    const total = (trabajo.espera + Math.floor(Math.random() * (trabajo.margen + 1))) * 1000;
    const fin = inicioUltimo + total;
    if (fin <= Date.now()) return;
    await contar('pausa', { segundos: Math.ceil((fin - Date.now()) / 1000) });
    while (Date.now() < fin && !parar) {
      await esperar(Math.min(1000, fin - Date.now()));
      // Cada 5 s se pregunta a la app si se ha pulsado "Parar" allí
      if (Math.floor((fin - Date.now()) / 1000) % 5 === 0) await mirarSiParar();
    }
  }

  async function mirarSiParar() {
    try {
      const t = await app({ tipo: 'descargas-trabajo' });
      if (!t.activo || t.trabajo !== trabajo.id || t.parar) parar = true;
    } catch (_) {
      // sin respuesta: se sigue, y si la app se ha cerrado fallará lo siguiente
    }
  }

  // ------------------------------------------------------------------ el trabajo

  async function ejecutar() {
    try {
      trabajo = await app({ tipo: 'descargas-trabajo' });
    } catch (e) {
      estado(e.message, 'error');
      return;
    }
    if (!trabajo.activo) {
      sessionStorage.removeItem(CLAVE);
      estado('No hay ninguna descarga pedida desde JujaMaker. Pulsa "Descargar de Printables" en la app.', 'aviso');
      $('.parar').disabled = true;
      return;
    }
    trabajo.id = trabajo.trabajo;
    const registrados = new Set(trabajo.registrados || []);
    let restantes = trabajo.max;
    estado(trabajo.simular ? 'Simulando (no se descarga nada)…' : 'Descargando…');
    usuarios:
    for (const [i, u] of trabajo.usuarios.entries()) {
      if (parar) break;
      let modelos;
      try {
        modelos = await modelosDe(u.handle, trabajo.ultimos);
      } catch (e) {
        await contar('usuario', { indice: i + 1, total: trabajo.usuarios.length, entrada: u.entrada });
        await contar('error', { texto: 'No he podido leer los modelos de @' + u.handle + ': ' + e.message });
        continue;
      }
      const primero = modelos[0] && modelos[0].user;
      const nombre = (primero && primero.publicUsername) || u.handle;
      await contar('usuario', { indice: i + 1, total: trabajo.usuarios.length, entrada: u.entrada, nombre });
      await contar('nota', { texto: nombre + ': ' + modelos.length + ' modelos para mirar' });
      for (const [j, m] of modelos.entries()) {
        if (parar) break usuarios;
        await contar('modelo', { indice: j + 1, total: modelos.length, texto: m.name });
        estado((trabajo.simular ? 'Simulando · ' : '') + nombre + ' · ' + (j + 1) + '/' + modelos.length + ' · ' + m.name);
        if (m.liked) {
          await contar('ya-like', { texto: 'Ya tenía like, lo salto: ' + m.name });
        } else if (registrados.has(m.id)) {
          // Bajado antes (sin like): solo se le da el like
          if (trabajo.simular) {
            await contar('solo-like', { texto: 'Ya estaba descargado; solo le daría like: ' + m.name });
          } else if (await darLike(m)) {
            await contar('solo-like', { texto: 'Like (ya estaba descargado): ' + m.name });
            await esperar(2000 + Math.random() * 2000);
          }
        } else if (m.price || m.club) {
          await contar('saltado', { texto: 'Es de pago o del club, no se descarga: ' + m.name });
        } else if (restantes <= 0) {
          await contar('nota', { texto: 'Se ha llegado al máximo de descargas de esta vez (' + trabajo.max + ').' });
          break usuarios;
        } else if (trabajo.simular) {
          restantes--;
          await contar('descargaria', { texto: 'Se descargaría: ' + m.name });
        } else {
          await pausa();
          if (parar) break usuarios;
          inicioUltimo = Date.now();
          try {
            await descargar(m, nombre);
            restantes--;
            registrados.add(m.id);
            if (await darLike(m)) await contar('like', { texto: 'Like: ' + m.name });
          } catch (e) {
            // Parado a medias: el modelo no tiene info.json, así que no va al zip y se vuelve a bajar otra vez
            if (parar) break usuarios;
            if (e instanceof SinArchivos) {
              await contar('saltado', { texto: 'Sin archivos .stl ni .3mf, no se descarga: ' + m.name });
            } else {
              await contar('error', { texto: m.name + ': ' + e.message, usuario: nombre, modelo: m.name + ' (' + m.id + ')' });
            }
          }
        }
      }
    }
    try {
      await app({ tipo: 'descargas', ruta: 'fin', cuerpo: { trabajo: trabajo.id, parado: parar } });
    } catch (_) {
      // si la app ya lo había dado por terminado, da igual
    }
    sessionStorage.removeItem(CLAVE);
    $('.parar').disabled = true;
    estado(parar ? 'Parado. Lo completo está en JujaMaker.' : 'Terminado. Mira el resumen en JujaMaker.', parar ? 'aviso' : 'bien');
  }

  ejecutar();
})();
