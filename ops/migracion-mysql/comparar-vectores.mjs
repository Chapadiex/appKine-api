// Compara los embeddings de la biblioteca entre MySQL (BLOB float32 little-endian) y PostgreSQL
// (vector(768)), fragmento por fragmento y bit a bit.
//
// Herramienta de la migracion (12/11/2026). Uso:
//   node comparar-vectores.mjs <base-mysql> <base-postgres>
// Necesita los contenedores akine-mysql y akine-postgres.
import { execFileSync } from 'node:child_process';

const [baseMy, basePg] = process.argv.slice(2);
const opciones = { maxBuffer: 1 << 30, encoding: 'utf8' };

const deMysql = execFileSync('docker', ['exec', 'akine-mysql', 'sh', '-c',
	`MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N ${baseMy} -e "SELECT id, HEX(vector) FROM libro_fragmento WHERE vector IS NOT NULL ORDER BY id"`],
opciones).trim().split('\n').map((l) => l.split('\t'));
const dePg = new Map(execFileSync('docker', ['exec', 'akine-postgres', 'psql', '-U', 'postgres', '-d', basePg, '-At', '-F', '\t', '-c',
	'SELECT id, CAST(vector AS text) FROM libro_fragmento WHERE vector IS NOT NULL ORDER BY id'],
opciones).trim().split('\n').map((l) => l.split('\t')));

let iguales = 0;
const distintos = [];
for (const [id, hex] of deMysql) {
	const texto = dePg.get(id);
	if (!texto) { distintos.push(`${id}: falta en PostgreSQL`); continue; }
	const floats = texto.slice(1, -1).split(',').map(Number);
	const buf = Buffer.alloc(floats.length * 4);
	floats.forEach((f, i) => buf.writeFloatLE(f, i * 4));
	if (buf.toString('hex').toUpperCase() === hex) iguales++;
	else distintos.push(`${id}: ${floats.length} componentes, bytes distintos`);
}
console.log(`fragmentos con vector: MySQL ${deMysql.length}, PostgreSQL ${dePg.size}; iguales bit a bit: ${iguales}`);
if (distintos.length) {
	console.log(distintos.slice(0, 20).join('\n'));
	process.exit(1);
}
