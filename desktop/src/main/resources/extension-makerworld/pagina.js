// JujaMaker · MakerWorld — la parte que rellena el formulario.
//
// Va dentro de la propia página (manifest: "world": "MAIN") porque el editor de la descripción
// (CKEditor) solo se puede manejar desde ahí. No habla con la app: recibe la ficha y los archivos del
// panel (panel.js) con window.postMessage y le va contando cómo va.
//
// Todo lo que toca del formulario está comprobado en MakerWorld el 10/11/2026 (ver
// docs/contexto-makerworld.md). No pulsa "Save to draft" ni "Publish": eso lo hace la persona.

(() => {
  if (window.__jujamakerMakerWorld) return; // por si se inyecta dos veces
  window.__jujamakerMakerWorld = true;

  const DE_PANEL = 'jujamaker-panel';
  const A_PANEL = 'jujamaker-pagina';

  const esperar = (ms) => new Promise((r) => setTimeout(r, ms));
  const limpio = (t) => (t || '').trim().replace(/\s+/g, ' ');

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

  const botonExacto = (texto) =>
    [...document.querySelectorAll('main button')].find((b) => limpio(b.innerText) === texto);

  /** Escribe en un campo de React con el "setter" del navegador y avisa a React del cambio. */
  function escribir(input, valor) {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, valor);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }

  /** Elige esos archivos en una zona de subida como si se arrastraran. */
  function ponerArchivos(input, archivos) {
    const dt = new DataTransfer();
    archivos.forEach((f) => dt.items.add(f));
    input.files = dt.files;
    input.dispatchEvent(new Event('change', { bubbles: true }));
  }

  /**
   * Cada archivo se sube a Amazon S3 desde la página (comprobado: una petición XHR a
   * or-cloud-makerworld-prod.s3…amazonaws.com por archivo, y otra por la miniatura de cada .stl).
   * Se cuentan las que han terminado para saber cuándo está todo subido.
   */
  const subidasS3 = () =>
    performance.getEntriesByType('resource').filter((e) => e.name.includes('.amazonaws.com/')).length;

  async function esperarSubidas(antes, cuantas, que, maxMs) {
    await hasta(() => subidasS3() >= antes + cuantas, que, maxMs);
    await esperar(1500); // la página apunta el archivo justo después de subirlo
  }

  /** Elige una imagen de portada y acepta el recorte que propone la web (diálogo con "Submit"). */
  async function portada(input, archivo, que) {
    if (!input) throw new Error('no encuentro la zona de ' + que);
    const antes = subidasS3();
    ponerArchivos(input, [archivo]);
    const enviar = await hasta(() => [...document.querySelectorAll('.MuiDialog-root button')]
      .find((b) => limpio(b.innerText) === 'Submit'), 'la ventana de recorte de ' + que, 15000);
    enviar.click();
    await hasta(() => !document.body.contains(enviar), 'que se cierre el recorte de ' + que, 15000);
    await esperarSubidas(antes, 1, 'que se suba ' + que, 120000);
  }

  async function rellenar(ficha, archivos) {
    performance.setResourceTimingBufferSize(5000);
    const modelos = archivos.filter((a) => a.tipo === 'modelo').map((a) => a.file);
    const laPortada = archivos.find((a) => a.tipo === 'portada').file;
    const fotos = archivos.filter((a) => a.tipo === 'foto').map((a) => a.file);

    if (!/\/my\/models\/publish/.test(location.pathname)) {
      throw new Error('abre primero el formulario de subida (Upload → Publish Model)');
    }

    // --- Paso 1: sin perfil de impresión, todos los archivos como "Raw Model Files"
    paso('Eligiendo "I have STL/CAD files"');
    const opcion = await hasta(() => [...document.querySelectorAll('main label')]
      .find((l) => /STL\/CAD/.test(l.innerText)), 'la opción "I have STL/CAD files"');
    opcion.click();
    await hasta(() => document.querySelector('main input[type=radio][value=false]').checked, 'que se marque la opción');
    const siguiente1 = botonExacto('Next Step');
    if (siguiente1) siguiente1.click();

    paso('Subiendo ' + modelos.length + ' archivo(s) del modelo');
    const zona = await hasta(() => document.querySelector('main div.dropzone input[type=file][accept*=".stl"]'),
      'la zona de "Raw Model Files"');
    const antesModelos = subidasS3();
    ponerArchivos(zona, modelos);
    await esperarSubidas(antesModelos, modelos.length, 'que se suban los archivos del modelo', 10 * 60000);

    // --- Paso 2: al pulsar "Next Step" MakerWorld crea el borrador y salta a /my/models/drafts/<id>/edit
    paso('Pasando al paso 2 (MakerWorld crea el borrador)');
    botonExacto('Next Step').click();
    const enBorrador = await hasta(() => location.pathname.match(/\/my\/models\/drafts\/(\d+)\/edit/),
      'el paso 2 (el borrador)', 60000);
    const borrador = enBorrador[1];
    contar('borrador', { borrador });
    await hasta(() => document.querySelector('main .js-scroll-title input[name=title]'), 'el campo del nombre');

    paso('Portadas 4:3 y 3:4');
    await portada(document.querySelector('main .js-scroll-cover div.dropzone:not(.appUpload) input[type=file]') ||
      document.querySelector('main div.dropzone:not(.appUpload) > input[type=file][accept*="image/jpeg"]'),
      laPortada, 'la portada 4:3');
    await portada(document.querySelector('main div.dropzone.appUpload input[type=file]'), laPortada, 'la portada 3:4');

    if (fotos.length) {
      paso('Subiendo ' + fotos.length + ' foto(s) a "Model Pictures"');
      const antes = subidasS3();
      ponerArchivos(document.querySelector('main .js-scroll-designPictures input[type=file]'), fotos);
      await esperarSubidas(antes, fotos.length, 'que se suban las fotos', 5 * 60000);
    }

    paso('Nombre');
    const titulo = document.querySelector('main .js-scroll-title input[name=title]');
    escribir(titulo, ficha.titulo);
    await hasta(() => titulo.value === ficha.titulo, 'que se quede el nombre');

    paso('Categoría: ' + ficha.categoria.grupo + ' > ' + ficha.categoria.nombre);
    await elegirCategoria(ficha.categoria.nombre);

    paso('Etiquetas (' + ficha.etiquetas.length + ')');
    const noAnadidas = await escribirEtiquetas(ficha.etiquetas);
    if (noAnadidas.length) contar('aviso', { texto: 'Quitadas porque MakerWorld no las admite: ' + noAnadidas.join(', ') });

    paso('Licencia ' + ficha.licencia);
    await elegirLicencia(ficha.licencia);

    paso('Visibilidad: Private');
    const privado = document.querySelector('main .submitPrivate input[type=radio][value=true]');
    privado.click();
    await hasta(() => privado.checked, 'que se marque Private');

    paso('Descripción');
    const editor = document.querySelector('main .js-scroll-summary .ck-editor__editable') ||
      document.querySelector('main .ck-editor__editable');
    if (!editor || !editor.ckeditorInstance) throw new Error('no encuentro el editor de la descripción');
    editor.ckeditorInstance.setData(html(ficha.descripcion));

    vigilarGuardado(ficha, borrador);
    contar('relleno', { borrador });
  }

  /** Un párrafo por línea, como en Creality (tecleando, el editor convierte "- " en listas). */
  function html(texto) {
    const escapar = (t) => t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    return texto.split(/\r?\n/).map((l) => (l.trim() ? '<p>' + escapar(l) + '</p>' : '<p>&nbsp;</p>')).join('');
  }

  /** Abre el desplegable de "Category" y pulsa la opción que se llama exactamente así. */
  async function elegirCategoria(nombre) {
    const raiz = document.querySelector('main .js-scroll-categoryId .MuiAutocomplete-root');
    const input = raiz.querySelector('input');
    input.focus();
    raiz.querySelector('.MuiAutocomplete-popupIndicator').click();
    const opcion = await hasta(() => [...document.querySelectorAll('[role=listbox] li')]
      .find((li) => limpio(li.innerText) === nombre), 'la categoría "' + nombre + '" en el desplegable', 10000);
    opcion.scrollIntoView({ block: 'center' });
    opcion.click();
    await hasta(() => input.value === nombre, 'que se quede la categoría "' + nombre + '"', 5000);
  }

  /** Cuántas etiquetas hay puestas: el título de la sección dice "Tags (n / 50)". */
  const etiquetasPuestas = () => {
    const m = document.querySelector('main .js-scroll-tags').innerText.match(/Tags\s*\(\s*(\d+)\s*\//);
    return m ? Number(m[1]) : 0;
  };

  /**
   * El formulario es de Formik: su "bolsa" (values, setFieldValue…) está en las props de un
   * componente por encima del campo de etiquetas (comprobado el 10/11/2026).
   */
  function formik() {
    const campo = document.querySelector('main .js-scroll-tags');
    let f = campo && campo[Object.keys(campo).find((k) => k.startsWith('__reactFiber'))];
    for (let i = 0; i < 40 && f; i++, f = f.return) {
      const v = f.memoizedProps && f.memoizedProps.value;
      if (v && typeof v.setFieldValue === 'function' && v.values) return v;
    }
    return null;
  }

  /** Las palabras que MakerWorld no deja en las etiquetas (marcas: "POP MART"…), de su propio formulario. */
  function palabrasProhibidas() {
    const campo = document.querySelector('main .js-scroll-tags');
    let f = campo && campo[Object.keys(campo).find((k) => k.startsWith('__reactFiber'))];
    for (let i = 0; i < 8 && f; i++, f = f.return) {
      const p = f.memoizedProps;
      if (p && Array.isArray(p.forbiddenWords)) return p.forbiddenWords.map((w) => String(w).toLowerCase());
    }
    return [];
  }

  /**
   * Las etiquetas se ponen en el valor "tags" del formulario (Formik). Escribirlas y pulsar Enter
   * desde un script no las añade: el campo solo hace caso al teclado de verdad (comprobado el
   * 10/11/2026). Se quitan antes las que llevan palabras que MakerWorld no admite.
   */
  async function escribirEtiquetas(etiquetas) {
    const prohibidas = palabrasProhibidas();
    const fuera = etiquetas.filter((t) => prohibidas.some((w) => w && t.toLowerCase().includes(w)));
    const buenas = etiquetas.filter((t) => !fuera.includes(t));
    const f = await hasta(() => formik(), 'el formulario (Formik)', 10000);
    const ya = Array.isArray(f.values.tags) ? f.values.tags : [];
    await f.setFieldValue('tags', [...new Set([...ya, ...buenas])]);
    await hasta(() => etiquetasPuestas() >= Math.min(buenas.length, 50), 'que salgan las etiquetas', 5000);
    return fuera;
  }

  /** Abre el desplegable de licencia (input[name=license]) y pulsa la opción con ese data-value. */
  async function elegirLicencia(valor) {
    const guardada = document.querySelector('main input[name=license]');
    if (guardada.value === valor) return;
    guardada.parentElement.querySelector('.MuiSelect-select')
      .dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    const opcion = await hasta(() => document.querySelector('[role=listbox] [role=option][data-value="' + valor + '"]'),
      'la licencia ' + valor + ' en el desplegable', 10000);
    opcion.click();
    await hasta(() => guardada.value === valor, 'que se quede la licencia ' + valor, 5000);
  }

  /**
   * Cuando la persona pulse "Save to draft", se lee el borrador de MakerWorld
   * (GET /api/v1/design-service/my/draft/<id>) y se comprueba que tiene lo de la ficha.
   * Si MakerWorld no lo ha guardado (la hora de guardado no cambia), se dice por qué: sin
   * "Original" o "Remix" no deja guardar ni el borrador ("Please enter model origin", 10/11/2026).
   * Se escucha en todo el documento porque React puede cambiar el botón por otro.
   */
  function vigilarGuardado(ficha, borrador) {
    if (window.__jujamakerVigilando === borrador) return;
    window.__jujamakerVigilando = borrador;
    const leer = async () => {
      const r = await fetch('/api/v1/design-service/my/draft/' + borrador, { credentials: 'include' });
      if (!r.ok) throw new Error('MakerWorld responde ' + r.status);
      return r.json();
    };
    let antes = null;
    leer().then((d) => { antes = d.updateTime; }).catch(() => {});
    document.addEventListener('click', async (ev) => {
      const b = ev.target.closest && ev.target.closest('button');
      if (!b || limpio(b.innerText) !== 'Save to draft' || window.__jujamakerVigilando !== borrador) return;
      paso('Comprobando lo que ha guardado MakerWorld…');
      try {
        let d = null;
        for (let i = 0; i < 12; i++) {
          await esperar(700);
          d = await leer();
          if (d.updateTime !== antes) break;
        }
        if (d.updateTime === antes) {
          const avisos = [...document.querySelectorAll('[role=alert], .Mui-error')].map((e) => limpio(e.innerText))
            .filter((t) => t && t !== document.title);
          contar('aviso', { texto: 'MakerWorld no lo ha guardado' + (avisos.length ? ': ' + avisos.join(' | ') : '') +
            '. ¿Has marcado Original o Remix?' });
          return;
        }
        antes = d.updateTime;
        const problemas = [];
        if (d.title !== ficha.titulo) problemas.push('el nombre guardado es "' + d.title + '"');
        if (d.categoryId !== ficha.categoria.id) problemas.push('la categoría guardada es ' + d.categoryId);
        if (d.license !== ficha.licencia) problemas.push('la licencia guardada es ' + d.license);
        if (!(d.designSetting && d.designSetting.submitAsPrivate)) problemas.push('no está como privado');
        if (!d.cover || !d.coverPortrait) problemas.push('faltan portadas');
        if (!d.modelFiles || d.modelFiles.length < ficha.archivos.filter((a) => a.tipo === 'modelo').length) {
          problemas.push('faltan archivos del modelo');
        }
        contar('guardado', { borrador, problemas });
      } catch (e) {
        contar('error', { texto: 'No he podido comprobar el borrador: ' + e.message });
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
