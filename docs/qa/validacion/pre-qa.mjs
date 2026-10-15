#!/usr/bin/env node
// PRE-QA automatizado de la guia de QA manual (docs/qa/01..12). Indice: corre los circuitos de
// docs/qa/validacion/pre-qa/ en el orden de la guia y escribe el informe. Ver pre-qa/lib.mjs.
//
//   export QA_PREFIJO=preqa QA_PUERTO_MYSQL=3359 QA_PUERTO_API=8129 QA_PUERTO_MAILPIT=8079 QA_PUERTO_SMTP=1079
//   docs/qa/validacion/stack.sh build && docs/qa/validacion/stack.sh up
//   QA_SIN_FERIADOS=1 QA_API_URL=http://localhost:8129 QA_MAILPIT_URL=http://localhost:8079 \
//     node docs/qa/validacion/sembrar.mjs <estado.json>        # deja tomada la cuenta de plataforma (DP-14)
//   QA_PREFIJO=preqa QA_API_URL=http://localhost:8129 QA_MAILPIT_URL=http://localhost:8079 \
//     node docs/qa/validacion/pre-qa.mjs docs/qa/corridas/<fecha>-pre-qa.md [resultado.json]
//   docs/qa/validacion/stack.sh down --imagen
//
// QA_SOLO=<regex de ids> corre solo esos pasos (depuracion; los demas no se ejecutan y lo que
// dependa de ellos queda BLOQUEADO).

import { writeFileSync } from 'node:fs';
import { R, CIRC, GUIA, P, MYSQL, MAILC, SALIDA, SALIDA_JSON, suf, inicioCorrida, fmtSql, docker } from './pre-qa/lib.mjs';

const esc = (t) => String(t ?? '').replace(/\|/g, '\\|').replace(/\n/g, ' ');
function informe() {
	const cont = { OK: 0, FALLA: 0, MANUAL: 0, BLOQUEADO: 0 };
	for (const r of R) cont[r.estado]++;
	const L = [];
	L.push(`# Pre-QA automatizado — ${inicioCorrida.toISOString().slice(0, 10)}`, '');
	L.push(`> Corrida de \`docs/qa/validacion/pre-qa.mjs\` sobre el stack aislado \`${P}-*\` (MySQL, Mailpit y backend en contenedores propios; nunca \`akine-mysql\` ni los puertos 8080/4200).`,
		`> Backend: imagen construida desde \`origin/main\` de \`appKine-api\` (\`${process.env.QA_COMMIT ?? 's/d'}\`). Inicio ${inicioCorrida.toISOString()}, sufijo de la corrida \`${suf}\`.`,
		'> **Esto no es el QA manual del §6**: cada paso se ejecutó por la API, con la operación HTTP equivalente a la pantalla, y se verificó la SQL de la guía contra la base. Lo que solo se ve en la pantalla, o que necesita tiempos u otra infraestructura, queda marcado **MANUAL** y sigue siendo del usuario.', '');
	L.push('## Resumen', '', '| Resultado | Pasos |', '|---|---:|');
	for (const k of Object.keys(cont)) L.push(`| ${k} | ${cont[k]} |`);
	L.push(`| **Total** | **${R.length}** |`, '');
	L.push('Criterio: **OK** = todos los chequeos automáticos pasaron (las partes de pantalla quedan anotadas como "MANUAL:" en las notas); **FALLA** = al menos un chequeo no dio lo esperado (detalle abajo); **MANUAL** = el paso es de pantalla o de infraestructura que el runner no arma; **BLOQUEADO** = no se pudo correr porque faltó un prerequisito.', '');
	const porCirc = new Map();
	for (const r of R) { const c = CIRC[r.id.split('-')[0]] ?? 'otros'; if (!porCirc.has(c)) porCirc.set(c, []); porCirc.get(c).push(r); }
	for (const [c, rs] of [...porCirc.entries()].sort()) {
		L.push(`## ${c}`, '', '| Paso | Título | Resultado | Chequeos | Notas |', '|---|---|---|---:|---|');
		for (const r of rs) {
			const notas = [...(r.estado === 'FALLA' ? r.fallas.map((f) => `**${esc(f.desc)}**`) : []), ...(r.bloqueo ? [esc(r.bloqueo)] : []), ...r.notas.map(esc)].join('<br>');
			L.push(`| ${r.id} | ${esc(r.titulo)} | ${r.estado === 'FALLA' ? `**FALLA** ([detalle](#falla-${r.id.toLowerCase()}))` : r.estado} | ${r.checks} | ${notas} |`);
		}
		L.push('');
	}
	const fallas = R.filter((r) => r.estado === 'FALLA');
	if (fallas.length) {
		L.push('## Detalle de las fallas', '');
		for (const r of fallas) {
			L.push(`### FALLA ${r.id}`, '', `<a id="falla-${r.id.toLowerCase()}"></a>**${r.titulo}**`, '');
			for (const f of r.fallas) {
				L.push(`- **Esperado vs obtenido:** ${f.desc}`);
				if (f.ctx?.length || f.sql) {
					L.push('', '```text');
					for (const c of f.ctx ?? []) L.push(c);
					if (f.sql) L.push(fmtSql(f.sql));
					L.push('```', '');
				}
			}
			L.push('');
		}
	}
	if (GUIA.length) {
		L.push('## Discrepancias de la guía (no son defectos del producto)', '', 'La guía dice algo que el producto hace distinto, o su consulta no encuentra lo que busca. Corregir la guía antes de la corrida manual:', '');
		for (const g of [...new Set(GUIA)]) L.push(`- ${g}`);
		L.push('');
	}
	return L.join('\n');
}

const MODULOS = [
	['./pre-qa/01-onboarding.mjs', ['c10inicio', 'c01']],
	['./pre-qa/02-organizacion.mjs', ['c02a']],
	['./pre-qa/03-catalogo.mjs', ['c03']],
	['./pre-qa/04-personas.mjs', ['c04']],
	['./pre-qa/05-agenda.mjs', ['c05']],
	['./pre-qa/06-clinica.mjs', ['c06']],
	['./pre-qa/07-cobros.mjs', ['c07']],
	['./pre-qa/08-presentaciones.mjs', ['c08']],
	['./pre-qa/09-reportes.mjs', ['c09']],
	['./pre-qa/10-plataforma.mjs', ['c10resto']],
	['./pre-qa/02-organizacion.mjs', ['c02b']],
	['./pre-qa/11-aislamiento.mjs', ['c11']],
	['./pre-qa/99-final.mjs', ['cfinal']],
];
try {
	for (const [ruta, fns] of MODULOS) {
		let m;
		try { m = await import(ruta); } catch (e) { if (e.code === 'ERR_MODULE_NOT_FOUND') continue; throw e; }
		for (const f of fns) if (typeof m[f] === 'function') await m[f]();
	}
} finally {
	try { docker('unpause', MYSQL); } catch { /* no estaba pausada */ }
	try { docker('start', MAILC); } catch { /* ya estaba arriba */ }
	writeFileSync(SALIDA, informe());
	if (SALIDA_JSON) writeFileSync(SALIDA_JSON, JSON.stringify(R.map(({ sqls, ultimo, ...r }) => r), null, 1));
	const cont = R.reduce((a, r) => ({ ...a, [r.estado]: (a[r.estado] ?? 0) + 1 }), {});
	console.log(`\n${R.length} pasos: ${JSON.stringify(cont)} -> ${SALIDA}`);
}
