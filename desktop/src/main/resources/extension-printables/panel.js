// JujaMaker · Printables — el panel que sale abajo a la derecha en Printables.
//
// Pide a la app (por background.js) el siguiente modelo de la cola, se lo pasa a pagina.js para que
// rellene el formulario y lo guarde como borrador ("SAVE DRAFT") y, si lo guardado está bien, le dice a
// la app que lo apunte como subido. Nunca publica: la persona revisa los borradores y publica.

(() => {
  if (window.__jujamakerPanel) return;
  window.__jujamakerPanel = true;

  const FORMULARIO = 'https://www.printables.com/model/create';
  const DE_PANEL = 'jujamaker-panel';
  const A_PANEL = 'jujamaker-pagina';
  const CLAVE_SALTADAS = 'jujamaker-saltadas';
  /**
   * Modo seguido: la app abre Printables con "#jujamaker-auto" y el panel rellena cada modelo solo; tras
   * "SAVE DRAFT" (y comprobado lo guardado) abre el formulario del siguiente y vuelve a empezar. La
   * persona solo abre la página: luego revisa los borradores en Printables y los publica. Se recuerda en esta pestaña (sessionStorage) entre una
   * página y la siguiente; se para con "Parar", si falla algo o si se acaba la cola.
   */
  const CLAVE_SEGUIDO = 'jujamaker-seguido';
  const MARCA_SEGUIDO = 'jujamaker-auto';
  if (location.hash.includes(MARCA_SEGUIDO)) {
    sessionStorage.setItem(CLAVE_SEGUIDO, '1');
    history.replaceState(null, '', location.pathname + location.search);
  }
  const seguido = () => sessionStorage.getItem(CLAVE_SEGUIDO) === '1';
  function pararSeguido(motivo) {
    if (!seguido()) return;
    sessionStorage.removeItem(CLAVE_SEGUIDO);
    if (motivo) anotar('Modo seguido parado: ' + motivo, 'aviso');
    pintarBotones();
  }

  let ficha = null; // la del modelo que toca
  let ocupado = false; // rellenando o comprobando

  // ------------------------------------------------------------------ comunicación

  function app(mensaje) {
    return new Promise((resolver, rechazar) => {
      chrome.runtime.sendMessage(mensaje, (r) => {
        if (chrome.runtime.lastError) return rechazar(new Error(chrome.runtime.lastError.message));
        if (!r || !r.ok) return rechazar(new Error((r && r.error) || 'sin respuesta de la extensión'));
        resolver(r.datos);
      });
    });
  }

  const saltadas = () => {
    try { return JSON.parse(sessionStorage.getItem(CLAVE_SALTADAS) || '[]'); } catch (_) { return []; }
  };

  function aBytes(base64) {
    const s = atob(base64);
    const b = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) b[i] = s.charCodeAt(i);
    return b.buffer;
  }

  // ------------------------------------------------------------------ panel

  const raiz = document.createElement('div');
  raiz.style.cssText = 'position:fixed;right:16px;bottom:16px;z-index:2147483647;';
  const sombra = raiz.attachShadow({ mode: 'open' });
  sombra.innerHTML = `
    <style>
      .caja { width: 320px; font: 13px/1.4 system-ui, sans-serif; color: #e8e8e8; background: #1e1f22;
              border: 1px solid #3a3b3f; border-radius: 10px; box-shadow: 0 6px 24px rgba(0,0,0,.4); }
      .cabeza { display: flex; align-items: center; justify-content: space-between; padding: 8px 12px;
                font-weight: 600; border-bottom: 1px solid #3a3b3f; cursor: pointer; }
      .cuerpo { padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; }
      .oculto { display: none; }
      .estado { color: #b5b5b5; }
      .modelo { font-weight: 600; }
      .botones { display: flex; gap: 6px; flex-wrap: wrap; }
      button { font: inherit; padding: 6px 10px; border-radius: 6px; border: 1px solid #4a4b50;
               background: #2b2d31; color: #e8e8e8; cursor: pointer; }
      button.principal { background: #1f8f4e; border-color: #1f8f4e; color: #fff; }
      button:disabled { opacity: .45; cursor: default; }
      .registro { max-height: 140px; overflow: auto; font-size: 12px; color: #c9c9c9;
                  background: #17181a; border-radius: 6px; padding: 6px 8px; }
      .error { color: #ff8a80; }
      .bien { color: #7ee2a8; }
      .aviso { color: #ffd479; }
    </style>
    <div class="caja">
      <div class="cabeza"><span>JujaMaker → Printables</span><span class="plegar">–</span></div>
      <div class="cuerpo">
        <div class="estado">Conectando con JujaMaker…</div>
        <div class="modelo"></div>
        <div class="botones">
          <button class="principal rellenar">Rellenar este</button>
          <button class="abrir">Abrir formulario</button>
          <button class="saltar">Saltar</button>
          <button class="actualizar">↻</button>
          <button class="seguido">Seguido</button>
        </div>
        <div class="registro"></div>
      </div>
    </div>`;
  document.documentElement.appendChild(raiz);

  const $ = (s) => sombra.querySelector(s);
  const estado = $('.estado');
  const modelo = $('.modelo');
  const registro = $('.registro');

  $('.cabeza').addEventListener('click', () => $('.cuerpo').classList.toggle('oculto'));
  $('.abrir').addEventListener('click', () => { location.href = FORMULARIO; });
  $('.actualizar').addEventListener('click', () => cargar());
  $('.saltar').addEventListener('click', () => {
    if (!ficha) return;
    sessionStorage.setItem(CLAVE_SALTADAS, JSON.stringify([...saltadas(), ficha.clave]));
    anotar('Saltado: ' + ficha.titulo + ' (vuelve a salir si recargas la cola en otra pestaña)');
    cargar();
  });
  $('.rellenar').addEventListener('click', () => rellenar());
  $('.seguido').addEventListener('click', () => {
    if (seguido()) {
      pararSeguido('lo has parado tú');
    } else {
      sessionStorage.setItem(CLAVE_SEGUIDO, '1');
      anotar('Modo seguido: relleno y guardo como borrador cada modelo, uno tras otro.', 'bien');
      if (enFormulario()) rellenar(); else location.href = FORMULARIO;
    }
    pintarBotones();
  });

  function anotar(texto, clase) {
    const linea = document.createElement('div');
    linea.textContent = texto;
    if (clase) linea.className = clase;
    registro.appendChild(linea);
    registro.scrollTop = registro.scrollHeight;
  }

  const enFormulario = () => /^\/model\/create/.test(location.pathname);

  function pintarBotones() {
    $('.rellenar').disabled = ocupado || !ficha || !enFormulario();
    $('.saltar').disabled = ocupado || !ficha;
    $('.abrir').disabled = ocupado;
    // En el formulario también se ve: "Formulario nuevo" empieza en uno limpio tras un error
    $('.abrir').textContent = enFormulario() ? 'Formulario nuevo' : 'Abrir formulario';
    $('.seguido').textContent = seguido() ? 'Parar seguido' : 'Seguido';
    $('.seguido').disabled = !ficha && !seguido();
  }
  // Printables cambia de página sin recargar: se repintan los botones cada segundo
  setInterval(pintarBotones, 1000);

  // ------------------------------------------------------------------ cola

  async function cargar() {
    if (ocupado) return;
    try {
      const e = await app({ tipo: 'estado' });
      const r = await app({ tipo: 'siguiente', saltar: saltadas() });
      ficha = r.ficha;
      const problemas = e.conProblemas.length ? ' · ' + e.conProblemas.length + ' con problemas (míralas en la app)' : '';
      estado.textContent = e.pendientes.length + ' por subir a Printables' + problemas;
      estado.className = 'estado';
      modelo.textContent = ficha ? 'Siguiente: ' + ficha.titulo : 'No queda nada por subir.';
      if (ficha) (ficha.avisos || []).forEach((a) => anotar(a, 'aviso'));
      if (!ficha) pararSeguido('no queda nada por subir');
    } catch (e) {
      ficha = null;
      estado.textContent = e.message;
      estado.className = 'estado error';
      modelo.textContent = '';
    }
    pintarBotones();
  }

  async function rellenar() {
    if (!ficha || ocupado) return;
    ocupado = true;
    pintarBotones();
    anotar('— ' + ficha.titulo);
    try {
      const archivos = [];
      for (const a of ficha.archivos) {
        anotar('Leyendo ' + a.nombre + ' de JujaMaker');
        const d = await app({ tipo: 'archivo', carpeta: ficha.clave, nombre: a.nombre });
        archivos.push({ nombre: a.nombre, tipo: a.tipo, mime: d.mime, bytes: aBytes(d.base64) });
      }
      window.postMessage({ fuente: DE_PANEL, tipo: 'rellenar', ficha, archivos }, location.origin);
    } catch (e) {
      anotar(e.message, 'error');
      ocupado = false;
      pintarBotones();
    }
  }

  // ------------------------------------------------------------------ lo que cuenta pagina.js

  window.addEventListener('message', async (ev) => {
    if (ev.source !== window || !ev.data || ev.data.fuente !== A_PANEL) return;
    const m = ev.data;
    switch (m.tipo) {
      case 'paso':
        anotar(m.texto);
        break;
      case 'aviso':
        anotar(m.texto, 'aviso');
        break;
      case 'relleno':
        anotar('Rellenado.', 'bien');
        estado.textContent = 'Guardando como borrador…';
        break;
      case 'guardado':
        await guardado(m.modelo, m.problemas || []);
        break;
      case 'error':
        pararSeguido('ha fallado algo');
        anotar('Error: ' + m.texto + ' (la extensión no ha guardado nada: para volver a probar, pulsa "Formulario nuevo" y empieza en uno limpio)', 'error');
        ocupado = false;
        pintarBotones();
        break;
    }
  });

  /** Ya está guardado en Printables: si está bien (lo ha mirado pagina.js), se apunta en la app. */
  async function guardado(modelo, problemas) {
    if (!ficha) return;
    if (problemas.length) {
      pararSeguido('lo guardado no cuadra');
      anotar('Se ha guardado, pero: ' + problemas.join('; ') + '. Corrígelo y vuelve a pulsar "SAVE DRAFT".', 'error');
      return;
    }
    try {
      await app({ tipo: 'subido', carpeta: ficha.clave, modelo });
      anotar('Apuntado en JujaMaker ✓ (modelo ' + modelo + ', borrador)', 'bien');
      ocupado = false;
      ficha = null;
      await cargar();
      if (ficha && seguido()) {
        anotar('Abriendo el formulario del siguiente…');
        setTimeout(() => { location.href = FORMULARIO; }, 1500);
      } else if (ficha) {
        anotar('Pulsa "Abrir formulario" para el siguiente.');
      }
    } catch (e) {
      pararSeguido('no se ha podido apuntar el modelo');
      anotar('No se ha podido apuntar en JujaMaker: ' + e.message, 'error');
    }
    pintarBotones();
  }

  // Al abrir el formulario en modo seguido, se rellena solo (con un momento para que cargue la página)
  cargar().then(() => {
    if (seguido() && ficha && enFormulario()) {
      anotar('Modo seguido: relleno "' + ficha.titulo + '"', 'bien');
      setTimeout(() => rellenar(), 2000);
    }
  });
})();
