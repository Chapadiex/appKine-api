#!/usr/bin/env node
// Borrador de release notes entre dos refs (tags o commits), desde los PR mergeados (G-13).
//
//   node ops/release-notes.mjs <desde> [hasta=HEAD] [--repo-dir <ruta>] [--out <archivo>]
//
//   node ops/release-notes.mjs v0.9.0 v0.10.0
//   node ops/release-notes.mjs 4265306 origin/main --out notas.md
//   node ops/release-notes.mjs <desde> origin/main --repo-dir ../appKine-web
//
// De donde sale cada dato:
//   - PR: los merges de primer padre del rango ("Merge pull request #N", o "(#N)" de un squash),
//     leidos del git LOCAL. Titulo, autor, etiquetas y cuerpo, de la API de GitHub.
//   - Contrato: info.version de openapi/akine-api.yaml en cada punta (si el repo lo tiene).
//   - Migraciones: archivos AGREGADOS bajo src/main/resources/db/migration en el rango. Es el dato
//     que decide si el rollback es "volver a la imagen anterior" o "restore"
//     (docs/operaciones/rollback.md), por eso va arriba.
//
// Token: GITHUB_TOKEN si esta definido; si no, el de `git credential fill` para github.com.
// Nunca se imprime ni se escribe. Sin token igual funciona contra un repo publico, con el limite
// de 60 pedidos por hora.
//
// El resultado es un BORRADOR sobre docs/operaciones/plantilla-release-notes.md: los bloques
// marcados "COMPLETAR" los escribe una persona.

import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const opcion = (nombre) => {
	const i = args.indexOf(nombre);
	if (i < 0) return undefined;
	const valor = args[i + 1];
	args.splice(i, 2);
	return valor;
};
const repoDir = resolve(opcion('--repo-dir') ?? process.cwd());
const salida = opcion('--out');
const [desde, hasta = 'HEAD'] = args;
if (!desde) {
	console.error('uso: release-notes.mjs <desde> [hasta] [--repo-dir <ruta>] [--out <archivo>]');
	process.exit(2);
}

const git = (...a) => execFileSync('git', ['-C', repoDir, ...a], { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] }).trim();

// ---- Repo en GitHub, desde el remote (no desde el nombre de la carpeta: ver CLAUDE.md) --------
const remote = git('remote', 'get-url', 'origin');
const m = remote.match(/github\.com[:/]([^/]+)\/([^/.]+)(\.git)?$/);
if (!m) {
	console.error(`origin no es un repo de GitHub: ${remote}`);
	process.exit(2);
}
const [, owner, repo] = m;

// ---- Token -----------------------------------------------------------------------------------
function token() {
	if (process.env.GITHUB_TOKEN) return process.env.GITHUB_TOKEN;
	const r = spawnSync('git', ['credential', 'fill'], {
		input: 'protocol=https\nhost=github.com\n\n',
		encoding: 'utf8',
		env: { ...process.env, GIT_TERMINAL_PROMPT: '0' },
	});
	const linea = (r.stdout ?? '').split('\n').find((l) => l.startsWith('password='));
	return linea ? linea.slice('password='.length) : undefined;
}
const tk = token();

async function gh(ruta) {
	const res = await fetch(`https://api.github.com${ruta}`, {
		headers: {
			Accept: 'application/vnd.github+json',
			'X-GitHub-Api-Version': '2022-11-28',
			'User-Agent': 'akine-release-notes',
			...(tk ? { Authorization: `Bearer ${tk}` } : {}),
		},
	});
	if (!res.ok) throw new Error(`GitHub ${ruta}: HTTP ${res.status}`);
	return res.json();
}

// ---- Rango -----------------------------------------------------------------------------------
const desdeSha = git('rev-parse', '--short', `${desde}^{commit}`);
const hastaSha = git('rev-parse', '--short', `${hasta}^{commit}`);
const asuntos = git('log', '--first-parent', '--format=%s', `${desde}..${hasta}`).split('\n').filter(Boolean);
const numeros = [];
for (const s of asuntos) {
	const n = s.match(/^Merge pull request #(\d+)/)?.[1] ?? s.match(/\(#(\d+)\)\s*$/)?.[1];
	if (n && !numeros.includes(Number(n))) numeros.push(Number(n));
}
const sinPr = asuntos.filter((s) => !/^Merge pull request #\d+/.test(s) && !/\(#\d+\)\s*$/.test(s));

const prs = [];
for (const n of numeros.sort((a, b) => a - b)) {
	try {
		const pr = await gh(`/repos/${owner}/${repo}/pulls/${n}`);
		prs.push({ n, titulo: pr.title, autor: pr.user?.login, rama: pr.head?.ref, etiquetas: (pr.labels ?? []).map((l) => l.name), url: pr.html_url, mergeado: pr.merged_at });
	} catch (e) {
		prs.push({ n, titulo: `(no se pudo leer: ${e.message})`, url: `https://github.com/${owner}/${repo}/pull/${n}`, etiquetas: [] });
	}
}

// ---- Contrato y migraciones ------------------------------------------------------------------
function versionContrato(ref) {
	try {
		return git('show', `${ref}:openapi/akine-api.yaml`).match(/^info:[\s\S]*?^ {2}version: (.+)$/m)?.[1]?.trim();
	} catch {
		return undefined;
	}
}
const contratoDesde = versionContrato(desde);
const contratoHasta = versionContrato(hasta);
let migraciones = [];
try {
	migraciones = git('diff', '--name-only', '--diff-filter=A', `${desde}..${hasta}`, '--', 'src/main/resources/db/migration')
		.split('\n').filter(Boolean).map((f) => f.split('/').pop()).sort((a, b) => {
			const v = (x) => Number(x.match(/^V(\d+)__/)?.[1] ?? 0);
			return v(a) - v(b);
		});
} catch { /* repo sin migraciones */ }
const tieneMigraciones = existsSync(join(repoDir, 'src/main/resources/db/migration'));

// ---- Clasificacion ---------------------------------------------------------------------------
// Por etiqueta si el PR tiene; si no, por el prefijo de la rama (akine-<ID>-...), que es la
// convencion del repo: G = CI/operacion, DU = decision del usuario, fix = correccion.
function seccion(pr) {
	const e = pr.etiquetas.map((x) => x.toLowerCase());
	const rama = (pr.rama ?? '').toLowerCase();
	if (e.includes('bug') || /^akine-(web-)?fix/.test(rama) || /^fix/i.test(pr.titulo)) return 'Correcciones';
	if (e.includes('ops') || /^akine-(web-)?g-\d/.test(rama)) return 'Operacion, CI y seguridad';
	if (/^akine-(web-)?du-\d/.test(rama)) return 'Decisiones de producto implementadas';
	return 'Funcionalidad';
}
const secciones = new Map();
for (const pr of prs) {
	const s = seccion(pr);
	if (!secciones.has(s)) secciones.set(s, []);
	secciones.get(s).push(`- ${pr.titulo} ([#${pr.n}](${pr.url}))${pr.autor ? ` — @${pr.autor}` : ''}`);
}
const orden = ['Funcionalidad', 'Decisiones de producto implementadas', 'Correcciones', 'Operacion, CI y seguridad'];
const cambios = orden.filter((s) => secciones.has(s))
	.map((s) => `### ${s}\n\n${secciones.get(s).join('\n')}`).join('\n\n') || '_Sin PR mergeados en el rango._';

const bloqueMigraciones = !tieneMigraciones
	? '_Este repositorio no tiene migraciones._'
	: migraciones.length === 0
		? 'Ninguna. **El rollback es volver a la imagen anterior** (docs/operaciones/rollback.md §2).'
		: `${migraciones.length} nuevas:\n\n${migraciones.map((f) => `- \`${f}\``).join('\n')}\n\n` +
			'**COMPLETAR:** para cada una, ¿la imagen anterior sigue funcionando con ella aplicada? ' +
			'(ADR-0007; checklist de docs/operaciones/rollback.md §4). Si alguna no lo es, el rollback de esta release es **restore**.';

const bloqueContrato = contratoHasta
	? (contratoDesde === contratoHasta
		? `Sin cambios: \`${contratoHasta}\`.`
		: `\`${contratoDesde ?? '?'}\` → \`${contratoHasta}\`. ` +
			(contratoDesde && contratoHasta.split('.')[0] !== contratoDesde.split('.')[0]
				? '**Cambio MAYOR: incompatible. Exige ventana de compatibilidad y cliente regenerado.**'
				: 'Cambio menor o de parche: aditivo.'))
	: '_Este repositorio no publica contrato._';

// ---- Plantilla -------------------------------------------------------------------------------
const aqui = dirname(fileURLToPath(import.meta.url));
const plantilla = readFileSync(join(aqui, '..', 'docs', 'operaciones', 'plantilla-release-notes.md'), 'utf8');
const cuerpo = plantilla.split('<!-- PLANTILLA -->')[1] ?? plantilla;
const notas = cuerpo
	.replaceAll('{{REPO}}', `${owner}/${repo}`)
	.replaceAll('{{DESDE}}', `${desde} (\`${desdeSha}\`)`)
	.replaceAll('{{HASTA}}', `${hasta} (\`${hastaSha}\`)`)
	.replaceAll('{{FECHA}}', new Date().toISOString().slice(0, 10))
	.replaceAll('{{CANTIDAD_PR}}', String(prs.length))
	.replaceAll('{{CONTRATO}}', bloqueContrato)
	.replaceAll('{{MIGRACIONES}}', bloqueMigraciones)
	.replaceAll('{{CAMBIOS}}', cambios)
	.replaceAll('{{SIN_PR}}', sinPr.length ? sinPr.map((s) => `- ${s}`).join('\n') : '_Ninguno: todo entro por PR._')
	.trim() + '\n';

if (salida) {
	writeFileSync(salida, notas);
	console.error(`borrador escrito en ${salida} (${prs.length} PR)`);
} else {
	process.stdout.write(notas);
}
