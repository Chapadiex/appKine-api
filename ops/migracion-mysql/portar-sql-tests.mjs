// Porta a PostgreSQL el SQL de los tests de integracion (fixtures y consultas de verificacion).
//
// Herramienta de la migracion MySQL -> PostgreSQL (11/11/2026). Corre una vez sobre src/test/java;
// el resultado se revisa con git diff y los casos que no puede resolver solos quedan listados para
// hacerlos a mano. Sabe que columnas son boolean por el esquema, asi que 0/1 solo se traduce donde
// la columna lo es.
//
// Uso: node portar-sql-tests.mjs <raiz-java> <mysql-schema.json>

import fs from 'node:fs';
import path from 'node:path';

const [raiz, esquema] = process.argv.slice(2);
const j = JSON.parse(fs.readFileSync(esquema, 'utf8'));
const colsBool = new Set(j.columns.filter((c) => c.type === 'tinyint(1)').map((c) => `${c.t}.${c.c}`));
const nombresBool = new Set(j.columns.filter((c) => c.type === 'tinyint(1)').map((c) => c.c));
const nombresNoBool = new Set(j.columns.filter((c) => c.type !== 'tinyint(1)').map((c) => c.c));
const tablas = new Set(j.tables.map((t) => t.t));

const pendientes = [];

/** Corta por comas de primer nivel (fuera de parentesis y de comillas). */
function partir(s) {
	const r = [];
	let prof = 0, q = false, act = '';
	for (let i = 0; i < s.length; i++) {
		const ch = s[i];
		if (ch === "'" && s[i - 1] !== '\\') q = !q;
		if (!q && ch === '(') prof++;
		if (!q && ch === ')') prof--;
		if (!q && prof === 0 && ch === ',') { r.push(act); act = ''; continue; }
		act += ch;
	}
	r.push(act);
	return r;
}

/** Indice del parentesis que cierra el que abre en `desde`. */
function cierre(s, desde) {
	let prof = 0, q = false;
	for (let i = desde; i < s.length; i++) {
		const ch = s[i];
		if (ch === "'" && s[i - 1] !== '\\') q = !q;
		if (q) continue;
		if (ch === '(') prof++;
		if (ch === ')' && --prof === 0) return i;
	}
	return -1;
}

const unidad = { DAY: 'days', HOUR: 'hours', MINUTE: 'mins', SECOND: 'secs', MONTH: 'months', YEAR: 'years', WEEK: 'weeks', MICROSECOND: 'microseconds' };

function intervalo(cant, u) {
	const nombre = unidad[u.toUpperCase()];
	if (!nombre) throw new Error(`unidad ${u}`);
	if (/^-?\d+$/.test(cant)) return `INTERVAL '${cant} ${nombre}'`;
	return `make_interval(${nombre} => ${cant})`;
}

/** DATE_ADD(x, INTERVAL n U) / DATE_SUB(...) -> (x +/- intervalo). Sobre una fecha, sigue siendo fecha. */
function fechas(s) {
	let cambio = true;
	while (cambio) {
		cambio = false;
		const m = s.match(/\b(DATE_ADD|DATE_SUB)\s*\(/i);
		if (!m) break;
		const ini = m.index + m[0].length - 1;
		const fin = cierre(s, ini);
		const partes = partir(s.slice(ini + 1, fin));
		const iv = partes[1]?.trim().match(/^INTERVAL\s+(.+?)\s+(\w+)$/i);
		if (partes.length !== 2 || !iv) throw new Error(`DATE_ADD sin forma conocida: ${s.slice(m.index, fin + 1)}`);
		const base = partes[0].trim();
		const op = m[1].toUpperCase() === 'DATE_ADD' ? '+' : '-';
		const esFecha = /^CURRENT_DATE$|^CURDATE\(\)$/i.test(base);
		const expr = esFecha ? `CAST(${base} ${op} ${intervalo(iv[1], iv[2])} AS date)` : `(${base} ${op} ${intervalo(iv[1], iv[2])})`;
		s = s.slice(0, m.index) + expr + s.slice(fin + 1);
		cambio = true;
	}
	return s;
}

/** 0/1 -> false/true en las posiciones de columnas boolean de un INSERT ... VALUES. */
function insertBooleanos(s) {
	return s.replace(/INSERT\s+(?:IGNORE\s+)?INTO\s+(\w+)\s*\(([^)]*)\)\s*VALUES\s*/gi, (m, t, cols, off) => {
		// se reemplaza despues, sobre las tuplas que siguen
		return m;
	}).replace(/(INSERT\s+(?:IGNORE\s+)?INTO\s+(\w+)\s*\(([^)]*)\)\s*VALUES\s*)([\s\S]*)$/i, (m, cab, t, cols, resto) => {
		if (!tablas.has(t)) return m;
		const nombres = cols.split(',').map((c) => c.trim());
		const bools = nombres.map((c) => colsBool.has(`${t}.${c}`));
		if (!bools.some(Boolean)) return m;
		let out = '', i = 0;
		while (i < resto.length) {
			if (resto[i] === '(') {
				const f = cierre(resto, i);
				const vals = partir(resto.slice(i + 1, f));
				if (vals.length === nombres.length) {
					out += '(' + vals.map((v, k) => {
						if (!bools[k]) return v;
						return v.replace(/^(\s*)([01])(\s*)$/, (x, a, n, b) => `${a}${n === '1' ? 'true' : 'false'}${b}`);
					}).join(',') + ')';
				} else {
					out += resto.slice(i, f + 1);
				}
				i = f + 1;
				// despues de las tuplas puede venir ON CONFLICT, RETURNING, etc.
				const sig = resto.slice(i).match(/^\s*,\s*/);
				if (sig) { out += sig[0]; i += sig[0].length; continue; }
				out += resto.slice(i);
				break;
			}
			out += resto[i++];
		}
		return cab + out;
	});
}

/** col = 0/1 (y <>) para columnas que son boolean en todas las tablas que las tienen. */
function comparacionesBooleanas(s) {
	return s.replace(/\b((?:\w+\.)?(\w+))\s*(=|<>|!=)\s*([01])\b(?!\s*\.)/g, (m, ref, col, op, n) => {
		if (!nombresBool.has(col)) return m;
		if (nombresNoBool.has(col)) { pendientes.push(`ambigua ${col}: ${m}`); return m; }
		return `${ref} ${op === '!=' ? '<>' : op} ${n === '1' ? 'true' : 'false'}`;
	});
}

function portar(sql) {
	let s = sql;
	s = s.replace(/UTC_TIMESTAMP\(\s*\d*\s*\)/gi, 'CURRENT_TIMESTAMP');
	s = s.replace(/\bNOW\(\s*\d*\s*\)/gi, 'CURRENT_TIMESTAMP');
	s = s.replace(/CURRENT_TIMESTAMP\(\s*\d+\s*\)/gi, 'CURRENT_TIMESTAMP');
	s = s.replace(/CURDATE\(\)/gi, 'CURRENT_DATE');
	s = s.replace(/UTC_DATE\(\)/gi, 'CURRENT_DATE');
	s = fechas(s);
	s = s.replace(/\bIFNULL\s*\(/gi, 'COALESCE(');
	s = insertBooleanos(s);
	s = comparacionesBooleanas(s);
	if (/^\s*INSERT\s+IGNORE\s+INTO/i.test(s)) {
		s = s.replace(/INSERT\s+IGNORE\s+INTO/i, 'INSERT INTO').replace(/\s*;?\s*$/, (fin) => ` ON CONFLICT DO NOTHING${fin.includes('\n') ? '\n' : ''}`);
	}
	for (const [n, re] of [['on-duplicate', /ON DUPLICATE KEY/i], ['json', /JSON_|->>/i], ['if(', /\bIF\s*\(/i], ['group_concat', /GROUP_CONCAT/i], ['last_insert_id', /LAST_INSERT_ID/i], ['timestampdiff', /TIMESTAMPDIFF|DATE_FORMAT|CONVERT_TZ/i], ['backtick', /`/], ['delete-join', /DELETE\s+\w+\s+FROM/i]]) {
		if (re.test(s)) pendientes.push(`${n}: ${s.trim().slice(0, 160).replace(/\s+/g, ' ')}`);
	}
	return s;
}

const esSql = (s) => /^\s*(SELECT|INSERT|UPDATE|DELETE|WITH)\b/i.test(s);
let archivosCambiados = 0, sentencias = 0;
(function rec(d) {
	for (const e of fs.readdirSync(d, { withFileTypes: true })) {
		const p = path.join(d, e.name);
		if (e.isDirectory()) { rec(p); continue; }
		if (!e.name.endsWith('.java')) continue;
		const original = fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n');
		let src = original;
		// bloques de texto
		src = src.replace(/"""\n([\s\S]*?)"""/g, (m, cuerpo) => {
			if (!esSql(cuerpo)) return m;
			const n = portar(cuerpo);
			if (n !== cuerpo) sentencias++;
			return `"""\n${n}"""`;
		});
		// literales simples, uno por uno (las concatenaciones se portan pedazo a pedazo)
		src = src.replace(/"((?:[^"\\\n]|\\.)*)"/g, (m, cuerpo) => {
			if (!/(UTC_TIMESTAMP|NOW\(|CURDATE|UTC_DATE|DATE_ADD|DATE_SUB|IFNULL|INSERT\s+IGNORE|INSERT\s+INTO|\b\w+\s*(=|<>)\s*[01]\b)/i.test(cuerpo)) return m;
			let n;
			try { n = portar(cuerpo); } catch (err) { pendientes.push(`${p}: ${err.message}`); return m; }
			if (n !== cuerpo) sentencias++;
			return `"${n}"`;
		});
		if (src !== original) {
			fs.writeFileSync(p, src);
			archivosCambiados++;
		}
	}
})(raiz);

console.log(`archivos ${archivosCambiados}, literales cambiados ${sentencias}`);
console.log([...new Set(pendientes)].join('\n'));
