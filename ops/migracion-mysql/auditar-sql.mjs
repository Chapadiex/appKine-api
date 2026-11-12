// Inventario del SQL escrito a mano en el codigo Java y marcas de lo que no es PostgreSQL.
//
// Herramienta de la migracion MySQL -> PostgreSQL (11/11/2026). Recorre los .java, junta los
// literales de texto que parecen SQL (bloques """ y concatenaciones "..." + "...") y marca los
// patrones de MySQL. No reemplaza nada: el reemplazo se revisa sentencia por sentencia.
//
// Uso: node auditar-sql.mjs <raiz-java> <mysql-schema.json> [--todas]

import fs from 'node:fs';
import path from 'node:path';

const [raiz, esquema, flag] = process.argv.slice(2);
const j = JSON.parse(fs.readFileSync(esquema, 'utf8'));
const tipos = {};
for (const c of j.columns) (tipos[c.c] ||= new Set()).add(c.type === 'tinyint(1)' ? 'bool' : c.dt);
const booleanas = new Set(Object.keys(tipos).filter((c) => tipos[c].has('bool')));

const reglas = [
	['bool-01', (s) => [...s.matchAll(/(?:\b\w+\.)?(\w+)\s*(?:=|<>|!=)\s*([01])\b/g)].some((m) => booleanas.has(m[1]))
		|| [...s.matchAll(/\b([01])\s*(?:=|<>)\s*(?:\w+\.)?(\w+)\b/g)].some((m) => booleanas.has(m[2]))],
	['bool-set', (s) => [...s.matchAll(/\bSET\b[\s\S]*?\b(\w+)\s*=\s*([01])\b/gi)].some((m) => booleanas.has(m[1]))],
	['on-duplicate', (s) => /ON DUPLICATE KEY/i.test(s)],
	['insert-ignore', (s) => /INSERT IGNORE/i.test(s)],
	['ifnull', (s) => /\bIFNULL\s*\(/i.test(s)],
	['if(', (s) => /\bIF\s*\(/i.test(s)],
	['group_concat', (s) => /GROUP_CONCAT/i.test(s)],
	['fechas-mysql', (s) => /\b(DATE_FORMAT|DATE_ADD|DATE_SUB|TIMESTAMPDIFF|DATEDIFF|CURDATE|UTC_TIMESTAMP|UTC_DATE|UNIX_TIMESTAMP|FROM_UNIXTIME|STR_TO_DATE|ADDDATE|WEEKDAY|DAYOFWEEK|LAST_DAY)\s*\(/i.test(s) || /INTERVAL\s+\S+\s+(DAY|HOUR|MINUTE|SECOND|MONTH|YEAR)\b/i.test(s)],
	['date()', (s) => /(?<![_\w])DATE\s*\(/i.test(s)],
	['backtick', (s) => /`/.test(s)],
	['limit-coma', (s) => /LIMIT\s+[\w:?]+\s*,\s*[\w:?]+/i.test(s)],
	['share-mode', (s) => /LOCK IN SHARE MODE/i.test(s)],
	['json-mysql', (s) => /JSON_(EXTRACT|UNQUOTE|CONTAINS|SET|OBJECT|ARRAY|TABLE)|->>|->/i.test(s)],
	['last_insert_id', (s) => /LAST_INSERT_ID/i.test(s)],
	['match-against', (s) => /MATCH\s*\(/i.test(s)],
	['regexp', (s) => /\bREGEXP\b|RLIKE/i.test(s)],
	['null-param', (s) => /[:?]\w*\s+IS\s+(NOT\s+)?NULL/i.test(s)],
	['locate/position', (s) => /\b(LOCATE|INSTR|POSITION|STRPOS|SUBSTRING_INDEX)\s*\(/i.test(s)],
	['concat-null', (s) => /\bCONCAT\s*\(/i.test(s)],
	['delete-join', (s) => /DELETE\s+\w+\s+FROM|DELETE\s+FROM\s+\w+\s+\w*\s*JOIN/i.test(s)],
	['update-join', (s) => /UPDATE\s+\w+(\s+\w+)?\s+(INNER\s+|LEFT\s+)?JOIN/i.test(s)],
	['greatest/least', (s) => /\b(GREATEST|LEAST)\s*\(/i.test(s)],
	['truncate/round', (s) => /\b(TRUNCATE|ROUND)\s*\(/i.test(s)],
	['now()', (s) => /\bNOW\s*\(|CURRENT_TIMESTAMP\(/i.test(s)],
	['order-null', (s) => /ORDER BY/i.test(s)],
	['for-update', (s) => /FOR UPDATE|FOR SHARE/i.test(s)],
];

const archivos = [];
(function rec(d) {
	for (const e of fs.readdirSync(d, { withFileTypes: true })) {
		const p = path.join(d, e.name);
		if (e.isDirectory()) rec(p);
		else if (e.name.endsWith('.java')) archivos.push(p);
	}
})(raiz);

const esSql = (s) => /^\s*(SELECT|INSERT|UPDATE|DELETE|WITH|CREATE|ALTER|DROP|SET|TRUNCATE|CALL|LOCK)\b/i.test(s);
let total = 0;
const conteo = {};
for (const f of archivos) {
	const src = fs.readFileSync(f, 'utf8').replace(/\r\n/g, '\n');
	const sentencias = [];
	for (const m of src.matchAll(/"""\n([\s\S]*?)"""/g)) sentencias.push([m.index, m[1]]);
	// cadenas "..." concatenadas con +
	for (const m of src.matchAll(/"((?:[^"\\\n]|\\.)*)"(?:\s*\+\s*(?:"(?:[^"\\\n]|\\.)*"|[A-Z_]+))*/g)) {
		const texto = m[0].replace(/"\s*\+\s*"/g, '').replace(/^"|"$/g, '').replace(/"\s*\+\s*([A-Z_]+)/g, ' $1 ');
		if (esSql(texto) && texto.length > 15) sentencias.push([m.index, texto]);
	}
	for (const [idx, s] of sentencias) {
		if (!esSql(s)) continue;
		total++;
		const marcas = reglas.filter(([, r]) => r(s)).map(([n]) => n);
		for (const n of marcas) conteo[n] = (conteo[n] || 0) + 1;
		const relevantes = marcas.filter((n) => !['order-null', 'for-update', 'concat-null', 'now()', 'truncate/round'].includes(n));
		if (flag === '--todas' || relevantes.length) {
			const linea = src.slice(0, idx).split('\n').length;
			console.log(`\n### ${path.relative(raiz, f)}:${linea} [${marcas.join(' ')}]\n${s.trim()}`);
		}
	}
}
console.log(`\n\nsentencias ${total}`, conteo);
