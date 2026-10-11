#!/usr/bin/env node
// Corre TODAS las consultas SQL de la guia de QA contra el esquema real (AKINE G-12).
//
//   node docs/qa/validacion/validar-consultas.mjs <estado.json> [contenedor] [base]
//
// Toma cada bloque ```sql de docs/qa/*.md, le antepone los SET de las variables de la corrida con
// los ids que dejo `sembrar.mjs` (README §4), y lo ejecuta con el cliente mysql del contenedor
// (default g12-mysql, base akine_local). Falla si algun bloque da error de sintaxis, de tabla o de
// columna. Una consulta que devuelve cero filas NO es error: el sembrado es minimo y varias
// consultas miran estados que solo existen despues de un paso de la guia.
//
// No escribe nada: cada bloque corre en su propia sesion y la guia no tiene INSERT/UPDATE/DELETE
// (lo verifica tambien este script: un bloque con una sentencia de escritura es un error).

import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const [archivoEstado, contenedor = 'g12-mysql', base = 'akine_local'] = process.argv.slice(2);
if (!archivoEstado) {
	console.error('uso: validar-consultas.mjs <estado.json> [contenedor] [base]');
	process.exit(2);
}
const estado = JSON.parse(readFileSync(archivoEstado, 'utf8'));
const dirQa = join(dirname(fileURLToPath(import.meta.url)), '..');

// Las variables de la corrida, con los mismos nombres que la guia (README §4).
const variables = {};
for (const [k, v] of Object.entries(estado)) {
	if (typeof v === 'number') variables[k] = v;
}
for (const [rol, email] of Object.entries(estado.cuentas ?? {})) {
	variables[`email_${rol.replace(/-/g, '_')}`] = email;
}
const literal = (v) => (typeof v === 'number' ? String(v) : `'${String(v).replace(/'/g, "''")}'`);
const prelude = [
	"SET @desde := UTC_TIMESTAMP(6) - INTERVAL 1 DAY;",
	...Object.entries(variables).map(([k, v]) => `SET @${k} := ${literal(v)};`),
	// Las que la guia captura con un SET en un paso anterior: aca se derivan del sembrado.
	'SET @cuenta_admin_a := (SELECT id FROM cuenta WHERE email_normalizado = @email_admin_a);',
	'SET @cuenta_profesional := (SELECT id FROM cuenta WHERE email_normalizado = @email_profesional);',
	'SET @recepcion := (SELECT id FROM recepcion WHERE turno_id = @turno);',
	'SET @membership_admin_b := (SELECT m.id FROM membership m JOIN cuenta c ON c.id = m.account_id'
		+ ' WHERE m.organization_id = @org_b AND c.email_normalizado = @email_admin_b AND m.is_founder = 1);',
	'SET @rep_desde := UTC_TIMESTAMP() - INTERVAL 30 DAY;',
	'SET @rep_hasta := UTC_TIMESTAMP();',
].join('\n');

const escritura = /^\s*(INSERT|UPDATE|DELETE|REPLACE|ALTER|DROP|TRUNCATE|CREATE|GRANT)\b/im;
const archivos = readdirSync(dirQa).filter((f) => f.endsWith('.md')).sort();
let total = 0;
const errores = [];
const vacios = [];
for (const archivo of archivos) {
	const texto = readFileSync(join(dirQa, archivo), 'utf8');
	const bloques = [...texto.matchAll(/```sql\r?\n([\s\S]*?)```/g)];
	for (const [, sql] of bloques) {
		total++;
		const linea = texto.slice(0, texto.indexOf(sql)).split('\n').length;
		const donde = `${archivo}:${linea}`;
		if (escritura.test(sql)) {
			errores.push(`${donde}: el bloque escribe en la base (la guia solo lee)`);
			continue;
		}
		try {
			const salida = execFileSync('docker', ['exec', '-i', contenedor, 'mysql', '-uakine', '-pakine', '-N', '-B', base], {
				input: `${prelude}\n${sql}`,
				stdio: ['pipe', 'pipe', 'pipe'],
			});
			// Con -N no hay encabezados: cada linea es una fila. "NULL" solo es un SELECT @var vacio.
			const filas = String(salida).split('\n').filter((l) => l.trim() && !/^(NULL\s*)+$/.test(l.trim()));
			if (!filas.length) vacios.push(donde);
		} catch (e) {
			const msg = String(e.stderr ?? e.message)
				.split('\n')
				.filter((l) => l && !l.includes('Using a password'))
				.join(' ');
			errores.push(`${donde}: ${msg}`);
		}
	}
}
console.log(`${total} bloques SQL en ${archivos.length} archivos; ${total - errores.length} corren, ${errores.length} con error`);
for (const e of errores) console.log(`  ERROR ${e}`);
if (process.env.QA_INFORME) for (const v of vacios) console.log(`  sin filas en el sembrado: ${v}`);
console.log(`${vacios.length} bloques sin filas en el sembrado (QA_INFORME=1 para listarlos; no es error)`);
process.exit(errores.length ? 1 : 0);
