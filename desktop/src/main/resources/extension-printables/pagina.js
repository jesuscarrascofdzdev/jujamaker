// JujaMaker · Printables — la parte que rellena el formulario.
//
// Va dentro de la propia página (manifest: "world": "MAIN") porque el editor de la descripción
// (Tiptap) solo se puede manejar desde ahí. No habla con la app: recibe la ficha y los archivos del
// panel (panel.js) con window.postMessage y le va contando cómo va.
//
// Todo lo que toca del formulario está comprobado en Printables el 10/11/2026 (ver
// docs/contexto-printables.md). Marca "Original model – I made it" y "No — fully human-made" (lo
// pidió la persona: sus modelos son suyos y sin IA). Al terminar pulsa "SAVE DRAFT" (también lo pidió,
// para subir todos seguidos sin tocar nada), solo si arriba está en DRAFT. Nunca publica: eso lo hace la persona.

(() => {
  if (window.__jujamakerPrintables) return; // por si se inyecta dos veces
  window.__jujamakerPrintables = true;

  const DE_PANEL = 'jujamaker-panel';
  const A_PANEL = 'jujamaker-pagina';

  const esperar = (ms) => new Promise((r) => setTimeout(r, ms));
  const limpio = (t) => (t || '').trim().replace(/\s+/g, ' ');
  const visible = (e) => !!e && e.getClientRects().length > 0;

  function contar(tipo, datos) {
    window.postMessage(Object.assign({ fuente: A_PANEL, tipo }, datos || {}), location.origin);
  }
  const paso = (texto) => contar('paso', { texto });

  /** Espera a que [cond] devuelva algo, mirando cada 300 ms; si no llega, falla diciendo qué faltaba. */
  async function hasta(cond, que, maxMs) {
    const fin = Date.now() + (maxMs || 30000);
    while (Date.now() < fin) {
      try {
        const v = cond();
        if (v) return v;
      } catch (_) { /* aún no está */ }
      await esperar(300);
    }
    throw new Error('no llega: ' + que);
  }

  // ------------------------------------------------------------------ el formulario (10/11/2026)
  // La web está hecha con Svelte: los campos tienen id "f-<campo>-<número>" (el número cambia), así que
  // se buscan por el principio del id. Los textos de la web (en inglés) solo se usan para la categoría y
  // la licencia, que no tienen otro nombre.

  const campoNombre = () => document.querySelector('input[id^="f-name-"]');
  const campoResumen = () => document.querySelector('textarea[id^="f-summary-"]');
  const campoEtiquetas = () => document.querySelector('input[id^="f-tags-"]');
  const botonCategoria = () => document.querySelector('button[id^="f-category-"]');
  const botonLicencia = () => document.querySelector('button[id^="f-license-"]');
  const zonaArchivos = () => document.querySelector('input[type=file][id^="files-upload-box"]');
  const editor = () => {
    const e = document.querySelector('.tiptap.ProseMirror');
    return e && e.editor;
  };
  /** El interruptor DRAFT / PUBLISHED de arriba: sin marcar es borrador. */
  const publicado = () => document.querySelector('#publish-state');

  /**
   * "SAVE DRAFT": el botón principal (btn-primary) de la barra de arriba, la que tiene el interruptor
   * DRAFT / PUBLISHED. Está desactivado mientras se suben archivos (comprobado el 10/11/2026).
   */
  function botonGuardar() {
    let c = publicado();
    while (c && !c.querySelector('button.btn-primary')) c = c.parentElement;
    return c ? c.querySelector('button.btn-primary') : null;
  }

  /** Lo que se ve subiendo: cada archivo de "Model Files" es una fila con su nombre editable. */
  const filasModelos = () => document.querySelectorAll('.print-file-name-input').length;
  /** Los recuadros de "Photos" (sin el del "+"), en el orden de la web: el primero es la portada. */
  const fotos = () => [...document.querySelectorAll('.images .image')];
  const subiendo = () => [...document.querySelectorAll('.spinner-wrapper')].some(visible);
  const etiquetasPuestas = () => {
    const e = campoEtiquetas();
    return e ? [...e.parentElement.querySelectorAll('.badge')].map((b) => limpio(b.innerText)) : [];
  };

  /** Escribe en un campo con el "setter" del navegador y avisa a la web del cambio (Svelte escucha "input"). */
  function escribir(campo, valor) {
    const proto = campo.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(campo, valor);
    campo.dispatchEvent(new Event('input', { bubbles: true }));
    campo.dispatchEvent(new Event('change', { bubbles: true }));
  }

  /**
   * Una opción de un desplegable de Printables: los dos son un botón que abre un menú flotante
   * (`.dropdown-menu`) con un `button.option` por opción. En el de categorías las opciones van debajo
   * del título de su grupo (`.dropdown-menu.groupped`), y el mismo nombre puede estar en dos grupos
   * ("Accessories"), así que se busca dentro del grupo.
   */
  async function elegir(boton, texto, grupo, que) {
    boton.scrollIntoView({ block: 'center' });
    boton.click();
    const menu = await hasta(() => [...document.querySelectorAll('.dropdown-menu')]
      .find((m) => visible(m) && m.querySelector('button.option')), 'el desplegable de ' + que, 10000);
    let grupoActual = '';
    let opcion = null;
    for (const e of menu.querySelectorAll('*')) {
      if (e.tagName === 'BUTTON' && e.classList.contains('option')) {
        if (limpio(e.innerText) === texto && (!grupo || grupoActual === grupo)) { opcion = e; break; }
      } else if (e.children.length === 0 && !e.closest('button') && limpio(e.innerText)) {
        grupoActual = limpio(e.innerText);
      }
    }
    if (!opcion) {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      throw new Error('no está "' + (grupo ? grupo + ' > ' : '') + texto + '" en el desplegable de ' + que);
    }
    opcion.click();
    await hasta(() => limpio(boton.innerText).startsWith(texto), 'que se quede "' + texto + '" en ' + que, 5000);
  }

  // ------------------------------------------------------------------ rellenar

  async function rellenar(ficha, archivos) {
    performance.setResourceTimingBufferSize(5000);
    if (!/^\/model\/create/.test(location.pathname)) {
      throw new Error('abre primero el formulario de subida (botón "Create" → modelo nuevo)');
    }
    const modelos = archivos.filter((a) => a.tipo === 'modelo').length;
    const imagenes = archivos.filter((a) => a.tipo !== 'modelo').length;

    // --- Archivos: se sueltan en la zona de arriba y Printables pone solo las imágenes en "Photos" y los
    // .stl/.3mf en "Model Files". Las fotos quedan en el orden en que terminan de subirse, no en el que
    // se sueltan (comprobado el 10/11/2026), y la primera es la portada ("cover photo"): por eso la
    // portada se sube sola y antes, y al final se le da a su "set as cover" si hace falta.
    const portada = archivos.find((a) => a.tipo === 'portada');
    const resto = archivos.filter((a) => a !== portada);
    await hasta(() => zonaArchivos(), 'la zona para soltar archivos');
    await esperar(1000); // que la web termine de arrancar antes de soltar nada
    let fichaPortada = null;
    if (portada) {
      paso('Subiendo la portada');
      soltar([portada]);
      // Si la web aún no estaba lista para recibir archivos (recién abierta), no hace nada: se suelta otra vez
      fichaPortada = await hasta(() => fotos()[0], '', 15000).catch(() => null);
      if (!fichaPortada) {
        soltar([portada]);
        fichaPortada = await hasta(() => fotos()[0], 'que salga la portada en "Photos"', 60000);
      }
      await finSubidas('que se suba la portada');
    }
    if (resto.length) {
      paso('Subiendo ' + (imagenes - (portada ? 1 : 0)) + ' foto(s) más y ' + modelos + ' archivo(s) del modelo');
      soltar(resto);
      await hasta(() => filasModelos() >= modelos && fotos().length >= imagenes,
        'que salgan los archivos en "Photos" y "Model Files"', 60000);
      await finSubidas('que terminen de subirse los archivos');
    }
    if (fichaPortada) await ponerDePortada(fichaPortada, portada.file.name);

    paso('Nombre y resumen');
    escribir(await hasta(() => campoNombre(), 'el campo "Model name"'), ficha.titulo);
    escribir(campoResumen(), ficha.resumen);
    await hasta(() => campoNombre().value === ficha.titulo && campoResumen().value === ficha.resumen,
      'que se queden el nombre y el resumen', 5000);

    paso('Categoría: ' + ficha.categoria.grupo + ' > ' + ficha.categoria.nombre);
    await elegir(botonCategoria(), ficha.categoria.nombre, ficha.categoria.grupo, 'categoría');

    paso('Etiquetas (' + ficha.etiquetas.length + ')');
    await escribirEtiquetas(ficha.etiquetas);

    paso('Descripción');
    const ed = await hasta(() => editor(), 'el editor de la descripción');
    ed.commands.setContent(html(ficha.descripcion), true);

    paso('Licencia: ' + ficha.licencia);
    await elegir(botonLicencia(), ficha.licencia, null, 'licencia');

    paso('Origen: Original model · IA: No — fully human-made');
    await marcarOrigenEIA();

    vigilarGuardado(ficha);
    contar('relleno', {});
    // Se guarda solo como borrador: si arriba pone PUBLISHED, no se pulsa nada y se avisa.
    if (!publicado() || publicado().checked) {
      throw new Error('arriba no está en DRAFT: ponlo en DRAFT y pulsa tú "SAVE DRAFT"');
    }
    paso('Guardando como borrador (SAVE DRAFT)');
    await finSubidas('que se pueda pulsar "SAVE DRAFT"');
    botonGuardar().click(); // vigilarGuardado comprueba lo guardado y avisa al panel
  }

  /**
   * "Model origin" (radios name=authorship: Original / Remix / Reupload) y "Was AI used to create this
   * model?" (radios name=aiGenerated: Yes / No), en ese orden en la página y todos con value="on", así
   * que se eligen por su posición: el primero de origen y el segundo de IA. Se guardan con el borrador
   * (comprobado el 10/11/2026, al recargar la página siguen marcados).
   */
  async function marcarOrigenEIA() {
    const origen = document.querySelectorAll('input[type=radio][name=authorship]');
    const ia = document.querySelectorAll('input[type=radio][name=aiGenerated]');
    if (origen.length !== 3 || ia.length !== 2) {
      contar('aviso', { texto: 'No encuentro las preguntas de origen y de IA como esperaba: márcalas tú.' });
      return;
    }
    origen[0].click();
    ia[1].click();
    await hasta(() => origen[0].checked && ia[1].checked, 'que se marquen el origen y lo de la IA', 5000);
  }

  function soltar(lista) {
    const zona = zonaArchivos();
    const dt = new DataTransfer();
    lista.forEach((a) => dt.items.add(a.file));
    zona.files = dt.files;
    zona.dispatchEvent(new Event('change', { bubbles: true }));
  }

  /**
   * Subido cuando "SAVE DRAFT" se vuelve a poder pulsar y ya no hay ningún archivo dando vueltas
   * (tres veces seguidas, porque entre un archivo y otro hay un momento sin nada).
   */
  async function finSubidas(que) {
    let quietos = 0;
    await hasta(() => {
      const b = botonGuardar();
      quietos = b && !b.disabled && !subiendo() ? quietos + 1 : 0;
      return quietos >= 3;
    }, que, 20 * 60000);
  }

  /**
   * Deja la portada la primera de "Photos": la que es portada lleva el letrero "cover photo"
   * (span.cover-btn) y las demás un botón "set as cover" (button.cover-btn.set) que la pasa delante
   * (comprobado el 10/11/2026). Si Svelte ha cambiado el recuadro por otro, se busca por el nombre
   * (la web lo deja en minúsculas y en .webp: "Portada.png" → "portada.webp").
   */
  async function ponerDePortada(recuadro, nombre) {
    const base = nombre.replace(/\.[^.]+$/, '').toLowerCase();
    const r = document.contains(recuadro) ? recuadro
      : fotos().find((f) => decodeURIComponent((f.querySelector('img') || {}).src || '').toLowerCase().includes(base));
    if (!r) {
      contar('aviso', { texto: 'No encuentro la portada en "Photos": comprueba que la primera foto es la buena.' });
      return;
    }
    const boton = r.querySelector('button.cover-btn.set');
    if (!boton) return; // ya es la portada
    boton.click();
    await hasta(() => fotos()[0] && fotos()[0].querySelector('span.cover-btn') &&
      (fotos()[0] === r || !document.contains(r)), 'que la portada pase a ser la primera foto', 5000);
  }

  /** Un párrafo por línea, como en Creality y MakerWorld. */
  function html(texto) {
    const escapar = (t) => t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    return texto.split(/\r?\n/).map((l) => (l.trim() ? '<p>' + escapar(l) + '</p>' : '<p></p>')).join('');
  }

  /**
   * Las etiquetas se escriben una a una y Enter (cada una sale como un "badge"; comprobado el
   * 10/11/2026). La web parte por los espacios, por eso la app ya las manda de una palabra.
   */
  async function escribirEtiquetas(etiquetas) {
    const campo = await hasta(() => campoEtiquetas(), 'el campo de etiquetas');
    for (const t of etiquetas) {
      if (etiquetasPuestas().some((p) => p.toLowerCase() === t.toLowerCase())) continue;
      escribir(campo, t);
      campo.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true }));
      await esperar(80);
    }
    const faltan = etiquetas.filter((t) => !etiquetasPuestas().some((p) => p.toLowerCase() === t.toLowerCase()));
    if (faltan.length) contar('aviso', { texto: 'No se han quedado estas etiquetas: ' + faltan.join(', ') });
  }

  /**
   * Cuando se pulsa "SAVE DRAFT" (lo pulsa la extensión al terminar, o la persona): desde el formulario nuevo la web salta a
   * /model/<id>/edit al guardar (comprobado el 10/11/2026); ahí se comprueba que lo que se ve es lo de
   * la ficha y se avisa al panel con el id. Si no salta, es que la web no lo ha guardado (le falta
   * algo): se dice qué campos marca en rojo. El origen del modelo y lo de la IA no hacen falta para
   * guardar el borrador (comprobado el 10/11/2026), pero sí para publicar: los marca la persona. Se escucha en todo el documento porque el botón puede
   * cambiar por otro.
   */
  function vigilarGuardado(ficha) {
    if (window.__jujamakerVigilando === ficha.clave) return;
    window.__jujamakerVigilando = ficha.clave;
    const modelos = ficha.archivos.filter((a) => a.tipo === 'modelo').length;
    document.addEventListener('click', async (ev) => {
      const b = ev.target.closest && ev.target.closest('button');
      if (!b || b !== botonGuardar() || window.__jujamakerVigilando !== ficha.clave) return;
      paso('Comprobando lo que ha guardado Printables…');
      try {
        let m = null;
        const fin = Date.now() + 60000;
        while (!m && Date.now() < fin) {
          await esperar(500);
          m = location.pathname.match(/^\/model\/(\d+)\/edit/);
        }
        if (!m) {
          const rojos = [...document.querySelectorAll('[aria-invalid=true]')]
            .map((e) => {
              const t = e.closest('.form-group, fieldset, section');
              return t ? limpio(t.innerText).split(' ').slice(0, 4).join(' ') : e.id;
            });
          contar('aviso', { texto: 'Printables no lo ha guardado' + (rojos.length ? ': revisa ' + rojos.join(' | ') : '') +
            '. Mira si sale algún aviso en la página.' });
          return;
        }
        await esperar(2500); // que la página del modelo termine de pintar lo guardado
        const problemas = [];
        if (campoNombre() && campoNombre().value !== ficha.titulo) problemas.push('el nombre guardado es "' + campoNombre().value + '"');
        if (botonCategoria() && limpio(botonCategoria().innerText) !== ficha.categoria.nombre) {
          problemas.push('la categoría guardada es "' + limpio(botonCategoria().innerText) + '"');
        }
        if (botonLicencia() && !limpio(botonLicencia().innerText).startsWith(ficha.licencia)) problemas.push('la licencia no es ' + ficha.licencia);
        if (filasModelos() < modelos) problemas.push('faltan archivos del modelo');
        if (publicado() && publicado().checked) problemas.push('está como PUBLISHED, no como borrador');
        contar('guardado', { modelo: m[1], problemas });
      } catch (e) {
        contar('error', { texto: 'No he podido comprobar lo guardado: ' + e.message });
      }
    }, true);
  }

  window.addEventListener('message', async (ev) => {
    if (ev.source !== window || !ev.data || ev.data.fuente !== DE_PANEL || ev.data.tipo !== 'rellenar') return;
    const { ficha, archivos } = ev.data;
    try {
      const files = archivos.map((a) => ({ tipo: a.tipo, file: new File([a.bytes], a.nombre, { type: a.mime }) }));
      await rellenar(ficha, files);
    } catch (e) {
      contar('error', { texto: e.message || String(e) });
    }
  });

  // Para probar a mano desde la consola: window.__jujamakerRellenar(ficha, [{tipo, file}])
  window.__jujamakerRellenar = rellenar;
})();
