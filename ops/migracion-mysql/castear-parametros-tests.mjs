// Castea los parametros "?" de los tests que se comparan o asignan a columnas boolean, fecha,
// instante, hora o decimal.
//
// Herramienta de la migracion MySQL -> PostgreSQL (11/11/2026). MySQL convertia en silencio el
// texto o el numero que mandaba el test ("2026-09-01", 1); PostgreSQL no convierte un varchar ni
// un integer explicitos a date, timestamptz o boolean. CAST(? AS tipo) sirve igual si el test pasa
// el tipo correcto. Solo toca columnas cuyo nombre tiene UN tipo en todo el esquema; las ambiguas
// las lista.
//
// Uso: node castear-parametros-tests.mjs <raiz-java> <mysql-schema.json>

import fs from 'node:fs';
import path from 'node:path';

const [raiz, esquema] = process.argv.slice(2);
const j = JSON.parse(fs.readFileSync(esquema, 'utf8'));
const tipoPg = (c) => c.type === 'tinyint(1)' ? 'boolean'
	: c.dt === 'datetime' ? 'timestamptz'
	: c.dt === 'date' ? 'date'
	: c.dt === 'time' ? 'time'
	: c.dt === 'decimal' ? 'numeric'
	: null;
const tipos = {};
for (const c of j.columns) (tipos[c.c] ||= new Set()).add(tipoPg(c));
const ambiguas = new Set();

function castear(sql) {
	return sql.replace(/\b((?:\w+\.)?(\w+))(\s*)(=|<>|>=|<=|<|>)(\s*)\?(?!\s*AS\b)/g, (m, ref, col, e1, op, e2) => {
		const t = tipos[col];
		if (!t) return m;
		if (t.size > 1) {
			if ([...t].some(Boolean)) ambiguas.add(col);
			return m;
		}
		const [unico] = t;
		return unico ? `${ref}${e1}${op}${e2}CAST(? AS ${unico})` : m;
	});
}

const esSql = (s) => /\b(SELECT|INSERT|UPDATE|DELETE)\b/i.test(s);
let cambios = 0;
(function rec(d) {
	for (const e of fs.readdirSync(d, { withFileTypes: true })) {
		const p = path.join(d, e.name);
		if (e.isDirectory()) { rec(p); continue; }
		if (!e.name.endsWith('.java')) continue;
		const original = fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n');
		let src = original.replace(/"""\n([\s\S]*?)"""/g, (m, cuerpo) => esSql(cuerpo) ? `"""\n${castear(cuerpo)}"""` : m);
		src = src.replace(/"((?:[^"\\\n]|\\.)*)"/g, (m, cuerpo) => esSql(cuerpo) ? `"${castear(cuerpo)}"` : m);
		if (src !== original) {
			cambios += (src.match(/CAST\(\? AS/g) || []).length - (original.match(/CAST\(\? AS/g) || []).length;
			fs.writeFileSync(p, src);
		}
	}
})(raiz);
console.log(`casts nuevos ${cambios}; columnas ambiguas sin tocar: ${[...ambiguas].join(', ')}`);
