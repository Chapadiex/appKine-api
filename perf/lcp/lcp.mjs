// LCP de las pantallas principales del frontend (AKINE G-10), con la Performance API del
// navegador via Playwright. Ver docs/carga.md.
//
//   cd perf/lcp && npm ci && node lcp.mjs
//
// Contra la IMAGEN del frontend (g10-web, puerto 4294), que hace de proxy a g10-api: es el bundle
// de produccion servido por nginx, no el dev server. Ingresa por la pantalla con la cuenta
// administradora del sembrado y abre cada pantalla en una pestana NUEVA (carga dura: el LCP solo
// existe en la primera navegacion de un documento, no en la navegacion interna del router).
//
// Dos perfiles, los mismos que usa Lighthouse:
//   escritorio  RTT 40 ms, 10 Mbps, CPU sin ralentizar
//   movil-4g    RTT 150 ms, 1,6 Mbps de bajada, CPU 4x mas lenta ("slow 4G" de Lighthouse)
// Siempre con la cache HTTP apagada: es la primera visita, la peor.
//
// Cada carga dura restaura la sesion con un refresh, y refresh comparte con el login el limite de
// 30/min/IP: por eso se espacian las cargas.

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

const AQUI = dirname(fileURLToPath(import.meta.url));
const SALIDA = join(AQUI, '..', 'salida');
const WEB = process.env.AKINE_CARGA_WEB ?? 'http://localhost:4294';
const CORRIDAS = Number(process.env.AKINE_LCP_CORRIDAS ?? 5);
const PASSWORD = process.env.AKINE_CARGA_PASSWORD ?? 'carga-g10-contrasena-sintetica-2026';
const ESPACIO_MS = 2_500;

const centro = JSON.parse(readFileSync(join(SALIDA, 'sembrado.json'), 'utf8'));

const PERFILES = {
  escritorio: { latency: 40, download: (10 * 1024 * 1024) / 8, upload: (10 * 1024 * 1024) / 8, cpu: 1 },
  'movil-4g': { latency: 150, download: (1.6 * 1024 * 1024) / 8, upload: (750 * 1024) / 8, cpu: 4 },
};

const PANTALLAS = [
  { nombre: 'login', ruta: '/auth/ingresar', anonima: true },
  { nombre: 'agenda', ruta: '/agenda' },
  { nombre: 'recepcion', ruta: '/agenda/recepcion' },
  { nombre: 'padron', ruta: '/pacientes' },
  { nombre: 'paciente-360', ruta: `/pacientes/${centro.personas[0]}` },
  { nombre: 'caja', ruta: '/caja' },
  { nombre: 'reportes', ruta: '/reportes' },
];

/** Observa LCP y FCP desde el primer byte del documento. */
const OBSERVADOR = `
  window.__lcp = null; window.__fcp = null;
  new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__lcp = e; })
    .observe({ type: 'largest-contentful-paint', buffered: true });
  new PerformanceObserver((l) => { for (const e of l.getEntries()) if (e.name === 'first-contentful-paint') window.__fcp = e.startTime; })
    .observe({ type: 'paint', buffered: true });
`;

const pausa = (ms) => new Promise((r) => setTimeout(r, ms));

function percentil(valores, p) {
  const v = valores.filter((x) => x !== null).sort((a, b) => a - b);
  if (v.length === 0) return null;
  const i = Math.ceil((p / 100) * v.length) - 1;
  return v[Math.max(0, i)];
}

async function ingresar(contexto) {
  const page = await contexto.newPage();
  await page.goto(`${WEB}/auth/ingresar`);
  await page.locator('form').getByLabel('Email').fill(centro.cuentaAdmin);
  await page.locator('form').getByLabel('Contrasena').fill(PASSWORD);
  await page.getByRole('button', { name: 'Iniciar sesion' }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/auth/'), { timeout: 30_000 });
  await page.close();
}

async function medir(contexto, pantalla, perfil) {
  const page = await contexto.newPage();
  const cdp = await contexto.newCDPSession(page);
  await cdp.send('Network.enable');
  await cdp.send('Network.setCacheDisabled', { cacheDisabled: true });
  await cdp.send('Network.emulateNetworkConditions', {
    offline: false,
    latency: perfil.latency,
    downloadThroughput: perfil.download,
    uploadThroughput: perfil.upload,
  });
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: perfil.cpu });
  await page.addInitScript(OBSERVADOR);
  await page.goto(`${WEB}${pantalla.ruta}`, { waitUntil: 'networkidle', timeout: 60_000 });
  await pausa(1_000);
  const r = await page.evaluate(() => {
    const nav = performance.getEntriesByType('navigation')[0];
    const lcp = window.__lcp;
    return {
      url: location.pathname,
      lcp: lcp ? lcp.startTime : null,
      lcpElemento: lcp && lcp.element ? `${lcp.element.tagName.toLowerCase()}${lcp.element.className ? '.' + String(lcp.element.className).split(' ')[0] : ''}` : null,
      fcp: window.__fcp,
      ttfb: nav ? nav.responseStart : null,
    };
  });
  await page.close();
  return r;
}

const navegador = await chromium.launch();
const resultado = { web: WEB, corridas: CORRIDAS, medidoEn: new Date().toISOString(), perfiles: {} };
try {
  for (const [nombrePerfil, perfil] of Object.entries(PERFILES)) {
    const anonimo = await navegador.newContext();
    const conSesion = await navegador.newContext();
    await ingresar(conSesion);
    resultado.perfiles[nombrePerfil] = {};
    for (const pantalla of PANTALLAS) {
      const muestras = [];
      for (let i = 0; i < CORRIDAS; i++) {
        const m = await medir(pantalla.anonima ? anonimo : conSesion, pantalla, perfil);
        if (!pantalla.anonima && m.url.startsWith('/auth/')) {
          throw new Error(`${pantalla.ruta} redirigio a ${m.url}: se perdio la sesion`);
        }
        muestras.push(m);
        await pausa(ESPACIO_MS);
      }
      const lcps = muestras.map((m) => m.lcp);
      resultado.perfiles[nombrePerfil][pantalla.nombre] = {
        ruta: pantalla.ruta,
        lcpP75: percentil(lcps, 75),
        lcpMediana: percentil(lcps, 50),
        fcpP75: percentil(muestras.map((m) => m.fcp), 75),
        ttfbP75: percentil(muestras.map((m) => m.ttfb), 75),
        elemento: muestras[muestras.length - 1].lcpElemento,
        muestras: lcps.map((x) => (x === null ? null : Math.round(x))),
      };
      const r = resultado.perfiles[nombrePerfil][pantalla.nombre];
      console.log(
        `${nombrePerfil.padEnd(10)} ${pantalla.nombre.padEnd(13)} LCP p75 ${String(Math.round(r.lcpP75)).padStart(5)} ms ` +
          `(FCP p75 ${Math.round(r.fcpP75)} ms, elemento ${r.elemento}) ${r.lcpP75 <= 2500 ? 'cumple' : 'NO CUMPLE'}`,
      );
    }
    await anonimo.close();
    await conSesion.close();
  }
} finally {
  await navegador.close();
}

mkdirSync(SALIDA, { recursive: true });
writeFileSync(join(SALIDA, 'lcp.json'), JSON.stringify(resultado, null, 2));
console.log('perf/salida/lcp.json');
