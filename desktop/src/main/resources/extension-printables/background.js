// JujaMaker · Printables — parte de fondo de la extensión.
//
// Es la única parte que habla con la app (http://127.0.0.1:47812, ver ServidorPrintables en core):
// el panel de la página le pide cosas con mensajes y esto las trae. Así las peticiones a la app salen
// con el origen de la extensión ("chrome-extension://…"), que es lo único que la app acepta.

const APP = 'http://127.0.0.1:47812/printables';
const VERSION_PROTOCOLO = 2;
/** Las rutas de descargas a las que el script de descargas puede mandar datos (ver DescargasPrintables). */
const RUTAS_DESCARGAS = ['evento', 'carpeta', 'info', 'fin'];
/** De dónde se bajan los archivos y las fotos de Printables (comprobado el 11/11/2026). */
const ORIGENES_ARCHIVOS = ['https://files.printables.com/', 'https://media.printables.com/'];

chrome.runtime.onMessage.addListener((mensaje, _remitente, responder) => {
  atender(mensaje)
    .then((datos) => responder({ ok: true, datos }))
    .catch((e) => responder({ ok: false, error: e.message || String(e) }));
  return true; // la respuesta llega más tarde
});

async function atender(m) {
  switch (m.tipo) {
    case 'estado': {
      const e = await pedir('/estado');
      if (e.version !== VERSION_PROTOCOLO) {
        throw new Error('La extensión y JujaMaker no son de la misma versión: actualiza la extensión desde la app.');
      }
      return e;
    }
    case 'siguiente':
      return pedir('/siguiente?saltar=' + encodeURIComponent((m.saltar || []).join(',')));
    case 'archivo': {
      const r = await llamar(
        '/archivo?carpeta=' + encodeURIComponent(m.carpeta) + '&nombre=' + encodeURIComponent(m.nombre));
      const bytes = await r.arrayBuffer();
      return { base64: aBase64(bytes), mime: r.headers.get('Content-Type') || 'application/octet-stream' };
    }
    case 'subido':
      return pedir('/subido', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ carpeta: m.carpeta, modelo: m.modelo }),
      });
    // ---- descargas (descargas.js): la extensión descarga de Printables y la app lo guarda
    case 'descargas-trabajo':
      return pedir('/descargas/trabajo');
    case 'descargas': {
      if (!RUTAS_DESCARGAS.includes(m.ruta)) throw new Error('Ruta de descargas desconocida: ' + m.ruta);
      return pedir('/descargas/' + m.ruta, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(m.cuerpo),
      });
    }
    case 'descargas-bajar': {
      // Se baja aquí (no en la página) para que el archivo no tenga que pasar por los mensajes: la
      // extensión puede leer de files/media.printables.com (host_permissions) y se lo manda a la app.
      if (!ORIGENES_ARCHIVOS.some((o) => String(m.url).startsWith(o))) throw new Error('Dirección de descarga no válida');
      let r;
      try {
        r = await fetch(m.url);
      } catch (_) {
        throw new Error('No se ha podido descargar ' + m.nombre + ' de Printables');
      }
      if (!r.ok) throw new Error('Printables responde ' + r.status + ' al descargar ' + m.nombre);
      const datos = await r.arrayBuffer();
      return pedir('/descargas/archivo?trabajo=' + encodeURIComponent(m.trabajo) + '&carpeta=' +
        encodeURIComponent(m.carpeta) + '&nombre=' + encodeURIComponent(m.nombre), {
        method: 'POST',
        headers: { 'Content-Type': 'application/octet-stream' },
        body: datos,
      });
    }
    default:
      throw new Error('Mensaje desconocido: ' + m.tipo);
  }
}

async function llamar(ruta, opciones) {
  let r;
  try {
    r = await fetch(APP + ruta, opciones);
  } catch (_) {
    throw new Error('No contesta JujaMaker. ¿Está abierta la app?');
  }
  if (!r.ok) {
    let detalle = '';
    try { detalle = (await r.json()).error || ''; } catch (_) { /* sin detalle */ }
    // 409: lo que llega es de una descarga que ya no está en marcha (ver DescargasPrintables)
    throw new Error((r.status === 409 ? 'PARAR: ' : '') + 'JujaMaker responde ' + r.status + (detalle ? ': ' + detalle : ''));
  }
  return r;
}

async function pedir(ruta, opciones) {
  return (await llamar(ruta, opciones)).json();
}

// Los mensajes entre partes de la extensión van en JSON: los archivos viajan en base64
function aBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) {
    s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  }
  return btoa(s);
}
