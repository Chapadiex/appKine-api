// Latencia medida DEL LADO DEL SERVIDOR durante la corrida (AKINE G-10).
//
//   node perf/prometheus-latencia.mjs prometheus-antes.txt prometheus-despues.txt
//
// Resta dos fotos de /actuator/prometheus y calcula, por ruta, p50/p95/p99 con la misma
// interpolacion lineal dentro del bucket que usa `histogram_quantile` de Prometheus, mas la
// fraccion de pedidos dentro de los buckets exactos del SLO (le=0.3 y le=0.8, docs/observabilidad.md).
// Sirve para separar lo que tarda la aplicacion de lo que agrega la red o la cola de k6.

import { readFileSync } from 'node:fs';

const [antesArchivo, despuesArchivo] = process.argv.slice(2);

function leer(archivo) {
  const series = new Map();
  for (const linea of readFileSync(archivo, 'utf8').split('\n')) {
    const m = /^http_server_requests_seconds_bucket\{(.*)\} ([0-9.eE+-]+)$/.exec(linea);
    if (!m) continue;
    const etiquetas = Object.fromEntries(
      [...m[1].matchAll(/(\w+)="([^"]*)"/g)].map(([, k, v]) => [k, v]),
    );
    if (!etiquetas.uri.startsWith('/api/')) continue;
    const clave = `${etiquetas.method} ${etiquetas.uri}`;
    const le = etiquetas.le === '+Inf' ? Infinity : Number(etiquetas.le);
    const porLe = series.get(clave) ?? new Map();
    porLe.set(le, (porLe.get(le) ?? 0) + Number(m[2]));
    series.set(clave, porLe);
  }
  return series;
}

function cuantil(q, buckets) {
  const total = buckets[buckets.length - 1][1];
  const objetivo = q * total;
  let previoLe = 0;
  let previoCuenta = 0;
  for (const [le, cuenta] of buckets) {
    if (cuenta >= objetivo) {
      if (le === Infinity) return previoLe;
      const enBucket = cuenta - previoCuenta;
      return enBucket === 0 ? le : previoLe + ((le - previoLe) * (objetivo - previoCuenta)) / enBucket;
    }
    previoLe = le;
    previoCuenta = cuenta;
  }
  return NaN;
}

const antes = leer(antesArchivo);
const despues = leer(despuesArchivo);
const filas = [];
for (const [clave, porLe] of despues) {
  const previo = antes.get(clave) ?? new Map();
  const buckets = [...porLe.entries()]
    .map(([le, c]) => [le, c - (previo.get(le) ?? 0)])
    .sort((a, b) => a[0] - b[0]);
  const total = buckets[buckets.length - 1][1];
  if (total <= 0) continue;
  const dentro = (limite) => buckets.find(([le]) => le === limite)?.[1] ?? NaN;
  filas.push({
    clave,
    total,
    p50: cuantil(0.5, buckets) * 1000,
    p95: cuantil(0.95, buckets) * 1000,
    p99: cuantil(0.99, buckets) * 1000,
    en300: (100 * dentro(0.3)) / total,
    en800: (100 * dentro(0.8)) / total,
  });
}
filas.sort((a, b) => b.total - a.total);

console.log('| Ruta (servidor) | Pedidos | p50 ms | p95 ms | p99 ms | ≤300 ms | ≤800 ms |');
console.log('|---|---:|---:|---:|---:|---:|---:|');
for (const f of filas) {
  console.log(
    `| ${f.clave} | ${f.total} | ${f.p50.toFixed(0)} | ${f.p95.toFixed(0)} | ${f.p99.toFixed(0)} | ` +
      `${f.en300.toFixed(1)} % | ${f.en800.toFixed(1)} % |`,
  );
}
