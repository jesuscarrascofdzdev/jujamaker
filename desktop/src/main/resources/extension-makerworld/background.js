// JujaMaker · MakerWorld — parte de fondo de la extensión.
//
// Es la única parte que habla con la app (http://127.0.0.1:47811, ver ServidorMakerWorld en core):
// el panel de la página le pide cosas con mensajes y esto las trae. Así las peticiones a la app salen
// con el origen de la extensión ("chrome-extension://…"), que es lo único que la app acepta.

const APP = 'http://127.0.0.1:47811/makerworld';
const VERSION_PROTOCOLO = 1;

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
        body: JSON.stringify({ carpeta: m.carpeta, borrador: m.borrador }),
      });
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
    throw new Error('JujaMaker responde ' + r.status + (detalle ? ': ' + detalle : ''));
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
