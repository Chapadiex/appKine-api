#!/usr/bin/env node
/**
 * Vulnerabilidades conocidas de las dependencias Maven contra OSV.dev (AKINE G-6).
 *
 * Es el respaldo de OWASP dependency-check para cuando no hay `NVD_API_KEY`: sin clave, el NVD
 * responde 429 a los runners compartidos de GitHub antes de terminar la primera descarga (medido el
 * 11/10/2026: cortó al 15 %). OSV no pide clave, responde en segundos y agrega GitHub Advisory,
 * que además ve CVE que el NVD no matchea por CPE (los de Jackson del 09/10/2026).
 *
 * Uso:
 *   ./mvnw -q dependency:list -DincludeScope=runtime -DoutputFile=target/dependencias.txt
 *   node ops/vulnerabilidades-osv.mjs target/dependencias.txt
 *
 * Falla (exit 1) con severidad HIGH o CRITICAL —el equivalente de CVSS >= 7 del pom.xml— salvo
 * que el CVE figure, vigente, en dependency-check-suppressions.xml: las excepciones son las MISMAS
 * para los dos analizadores.
 */
import { readFileSync } from 'node:fs';

const OSV = 'https://api.osv.dev/v1';
const BLOQUEANTES = new Set(['HIGH', 'CRITICAL']);

const archivo = process.argv[2];
if (!archivo) {
  console.error('Uso: node ops/vulnerabilidades-osv.mjs <salida de dependency:list>');
  process.exit(2);
}

// "   grupo:artefacto:jar:version:scope -- module ..." -> {grupo, artefacto, version}
const dependencias = readFileSync(archivo, 'utf8')
  .split('\n')
  .map((l) => l.trim().split(' ')[0])
  .filter((c) => c.split(':').length >= 5)
  .map((c) => {
    const p = c.split(':');
    return { nombre: `${p[0]}:${p[1]}`, version: p[p.length - 2] };
  });

if (dependencias.length === 0) {
  console.error(`No hay dependencias en ${archivo}: ¿corrió dependency:list?`);
  process.exit(2);
}

/** CVE suprimidos y vigentes (sin `until`, o con `until` futuro). */
function suprimidos() {
  const xml = readFileSync(new URL('../dependency-check-suppressions.xml', import.meta.url), 'utf8')
    .replace(/<!--[\s\S]*?-->/g, '');
  const hoy = new Date();
  const cves = new Set();
  for (const [, atributos, cuerpo] of xml.matchAll(/<suppress([^>]*)>([\s\S]*?)<\/suppress>/g)) {
    const until = /until="([^"]+)"/.exec(atributos);
    if (until && new Date(until[1].replace(/Z$/, 'T00:00:00Z')) < hoy) continue;
    for (const [, cve] of cuerpo.matchAll(/<cve>([^<]+)<\/cve>/g)) cves.add(cve.trim());
  }
  return cves;
}

async function json(url, cuerpo) {
  const r = await fetch(url, cuerpo ? { method: 'POST', body: JSON.stringify(cuerpo) } : undefined);
  if (!r.ok) throw new Error(`${url} respondió ${r.status}`);
  return r.json();
}

const lote = await json(`${OSV}/querybatch`, {
  queries: dependencias.map((d) => ({
    package: { name: d.nombre, ecosystem: 'Maven' },
    version: d.version,
  })),
});

const excepciones = suprimidos();
const bloqueantes = [];
const avisos = [];

for (const [i, resultado] of lote.results.entries()) {
  for (const { id } of resultado.vulns ?? []) {
    const v = await json(`${OSV}/vulns/${id}`);
    const severidad = (v.database_specific?.severity ?? 'DESCONOCIDA').toUpperCase();
    const cves = (v.aliases ?? []).filter((a) => a.startsWith('CVE-'));
    const linea = `${dependencias[i].nombre}:${dependencias[i].version}  ${id} ${cves.join(',')}  ${severidad}`;
    if (cves.some((c) => excepciones.has(c))) avisos.push(`${linea}  (suprimido)`);
    else if (BLOQUEANTES.has(severidad)) bloqueantes.push(linea);
    else avisos.push(linea);
  }
}

console.log(`OSV: ${dependencias.length} dependencias de runtime analizadas.`);
for (const a of avisos) console.log(`  aviso      ${a}`);
for (const b of bloqueantes) console.log(`::error::Vulnerabilidad alta o critica: ${b}`);
if (bloqueantes.length > 0) process.exit(1);
console.log('Sin vulnerabilidades altas ni criticas.');
