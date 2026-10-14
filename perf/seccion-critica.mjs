// Que corre bajo los dos locks de escritura por sede, desde las fotos de `perf/stack.sh historia`.
//
//   node perf/seccion-critica.mjs perf/salida/historia-*.tsv
//
// Para cada transaccion que toma el lock —el UPDATE del numerador de comprobantes o el
// SELECT ... FOR UPDATE de agenda_sede— suma lo que pasa desde que lo obtiene hasta el COMMIT o el
// ROLLBACK, que es cuando lo suelta: cuantas sentencias, cuanto tardan y cuanto de eso es el COMMIT.
// La espera es la duracion de la sentencia que pide el lock. Tiempos en ms (performance_schema
// mide en picosegundos). Ver docs/carga.md §10.
import { readFileSync } from 'node:fs';

const LOCKS = [
  { nombre: 'cobro (comprobante_numerador)', toma: /^UPDATE `comprobante_numerador`/ },
  { nombre: 'reserva (agenda_sede)', toma: /^SELECT \* FROM `agenda_sede`/ },
];

const porLock = new Map(LOCKS.map((l) => [l.nombre, []]));

for (const archivo of process.argv.slice(2)) {
  const porConexion = new Map();
  for (const linea of readFileSync(archivo, 'utf8').split('\n')) {
    if (!linea.trim()) continue;
    const [hilo, , inicio, fin, texto = ''] = linea.split('\t');
    if (!porConexion.has(hilo)) porConexion.set(hilo, []);
    porConexion.get(hilo).push({ ms: (Number(fin) - Number(inicio)) / 1e9, texto });
  }
  for (const sentencias of porConexion.values()) {
    sentencias.forEach((sentencia, i) => {
      const lock = LOCKS.find((l) => l.toma.test(sentencia.texto));
      if (!lock) return;
      const cierre = sentencias.findIndex((s, j) => j > i && /^(COMMIT|ROLLBACK)/.test(s.texto));
      if (cierre < 0) return; // la foto corto la transaccion
      const bajoLock = sentencias.slice(i + 1, cierre + 1);
      porLock.get(lock.nombre).push({
        espera: sentencia.ms,
        bajoLock: bajoLock.reduce((t, s) => t + s.ms, 0),
        cierre: sentencias[cierre].ms,
        sentencias: bajoLock.length,
        ejemplo: bajoLock.map((s) => s.texto.slice(0, 40)).join(' | '),
      });
    });
  }
}

const percentil = (valores, q) => {
  const orden = [...valores].sort((a, b) => a - b);
  return orden[Math.min(orden.length - 1, Math.floor(q * orden.length))];
};
const fila = (titulo, valores) => {
  const promedio = valores.reduce((a, b) => a + b, 0) / valores.length;
  return `| ${titulo} | ${promedio.toFixed(1)} | ${percentil(valores, 0.5).toFixed(1)} | ${percentil(valores, 0.95).toFixed(1)} |`;
};

for (const [nombre, transacciones] of porLock) {
  console.log(`\n### ${nombre}: ${transacciones.length} transacciones\n`);
  if (!transacciones.length) continue;
  const de = (k) => transacciones.map((t) => t[k]);
  console.log('| ms | promedio | p50 | p95 |\n|---|---:|---:|---:|');
  console.log(fila('espera del lock', de('espera')));
  console.log(fila('sentencias bajo el lock, con el cierre', de('bajoLock')));
  console.log(fila('el COMMIT o ROLLBACK', de('cierre')));
  console.log(fila('bajo el lock sin el cierre', transacciones.map((t) => t.bajoLock - t.cierre)));
  console.log(`\nSentencias bajo el lock (con el cierre): ${(de('sentencias').reduce((a, b) => a + b, 0) / transacciones.length).toFixed(1)} en promedio.`);
  const tipica = transacciones.reduce((a, b) => (b.sentencias > a.sentencias ? b : a));
  console.log(`La mas larga: ${tipica.ejemplo}`);
}
