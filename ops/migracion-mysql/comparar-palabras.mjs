// Recuperacion por palabras de la biblioteca: MySQL (MATCH ... NATURAL LANGUAGE) contra las variantes
// de PostgreSQL, sobre los casos de un turno del set de evaluacion de la consulta.
//
// Herramienta de la migracion (12/11/2026). Determinista: no pasa por el modelo de lenguaje. Para cada
// caso "responder" sin historial, mira si el top-k por palabras trae alguna pagina esperada (+-1) del
// libro esperado. Uso: node comparar-palabras.mjs <consultas.json> <base-mysql> <base-postgres> [k]
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';

const [archivo, baseMy, basePg, kTxt] = process.argv.slice(2);
const k = Number(kTxt || 10);
const set = JSON.parse(fs.readFileSync(archivo, 'utf8'));
const casos = (set.casos || set).filter((c) => c.esperado === 'responder' && !(c.historial || []).length);
const sh = (args) => execFileSync('docker', args, { encoding: 'utf8', maxBuffer: 1 << 26 }).trim();
const lit = (s) => s.replace(/'/g, "''");

const org = sh(['exec', 'akine-postgres', 'psql', '-U', 'postgres', '-d', basePg, '-At', '-c', 'SELECT organization_id FROM libro LIMIT 1']);
const configs = (process.env.CONFIGS || 'akine_es,akine_en').split(',');
const base = (q) => `terminos AS (${configs.map((c) => `SELECT lexeme FROM unnest(to_tsvector('${c}', '${lit(q)}'))`).join(' UNION ')}),
 buscables AS NOT MATERIALIZED (SELECT f.id, f.texto_tsv FROM libro_fragmento f JOIN libro l ON l.id = f.libro_id
   WHERE f.organization_id = ${org} AND l.activo AND l.estado = 'LISTO' AND NOT f.sospechoso),
 total AS (SELECT COUNT(*) AS n, AVG(length(texto_tsv)) AS largo FROM buscables),
 pesos AS (SELECT t.lexeme, plainto_tsquery('simple', t.lexeme) AS q,
   ln((total.n + 1.0) / (1 + (SELECT COUNT(*) FROM buscables b WHERE b.texto_tsv @@ plainto_tsquery('simple', t.lexeme)))) AS idf
   FROM terminos t CROSS JOIN total)`;
const variantes = {
	'pg-idf': (q) => `WITH ${base(q)}, top AS (SELECT b.id, SUM(p.idf) r FROM buscables b JOIN pesos p ON b.texto_tsv @@ p.q GROUP BY b.id ORDER BY r DESC, b.id LIMIT ${k})`,
	'pg-bm25': (q) => `WITH ${base(q)}, top AS (SELECT b.id, SUM(p.idf * (tf.n * 2.2) / (tf.n + 1.2 * (0.25 + 0.75 * length(b.texto_tsv) / total.largo))) r
		FROM buscables b JOIN pesos p ON b.texto_tsv @@ p.q CROSS JOIN total
		CROSS JOIN LATERAL (SELECT COALESCE(SUM(cardinality(u.positions)), 1) AS n FROM unnest(b.texto_tsv) u WHERE u.lexeme = p.lexeme) tf
		GROUP BY b.id ORDER BY r DESC, b.id LIMIT ${k})`,
	// La formula documentada de InnoDB FULLTEXT: TF * IDF * IDF, IDF = log10(N / fragmentos con la palabra).
	'pg-innodb': (q) => `WITH ${base(q)}, top AS (SELECT b.id, SUM(tf.n * power(log(total.n::numeric / GREATEST(p.df, 1)), 2)) r
		FROM buscables b JOIN (SELECT pesos.*, (SELECT COUNT(*) FROM buscables b2 WHERE b2.texto_tsv @@ pesos.q) AS df FROM pesos) p
		  ON b.texto_tsv @@ p.q CROSS JOIN total
		CROSS JOIN LATERAL (SELECT COALESCE(SUM(cardinality(u.positions)), 1) AS n FROM unnest(b.texto_tsv) u WHERE u.lexeme = p.lexeme) tf
		GROUP BY b.id ORDER BY r DESC, b.id LIMIT ${k})`,
	// La de PgAlmacenVectorial: InnoDB sin los terminos que nombra mas de la mitad de los fragmentos.
	'pg-final': (q) => `WITH terminos AS MATERIALIZED (SELECT DISTINCT lexeme, plainto_tsquery('simple', lexeme) AS q FROM unnest(to_tsvector('akine_simple', '${lit(q)}'))),
		buscables AS MATERIALIZED (SELECT f.id, f.texto_tsv FROM libro_fragmento f JOIN libro l ON l.id = f.libro_id
		  WHERE f.organization_id = ${org} AND l.organization_id = f.organization_id AND l.activo AND l.estado = 'LISTO' AND NOT f.sospechoso),
		total AS MATERIALIZED (SELECT COUNT(*) AS n FROM buscables),
		frecuencia AS MATERIALIZED (SELECT t.lexeme, t.q, COUNT(b.id) AS df FROM terminos t LEFT JOIN buscables b ON b.texto_tsv @@ t.q GROUP BY t.lexeme, t.q),
		pesos AS MATERIALIZED (SELECT f.lexeme, f.q, power(log(total.n::numeric / f.df), 2) AS idf2 FROM frecuencia f CROSS JOIN total
		  WHERE f.df > 0 AND (f.df * 2 <= total.n OR NOT EXISTS (SELECT 1 FROM frecuencia g WHERE g.df > 0 AND g.df * 2 <= total.n))),
		top AS (SELECT b.id, SUM(tf.veces * p.idf2) AS r FROM buscables b JOIN pesos p ON b.texto_tsv @@ p.q
		  CROSS JOIN LATERAL (SELECT COALESCE(SUM(cardinality(u.positions)), 1) AS veces FROM unnest(b.texto_tsv) u WHERE u.lexeme = p.lexeme) tf
		  GROUP BY b.id ORDER BY r DESC, b.id LIMIT ${k})`,
	// Igual, con ts_rank del termino solo como frecuencia: una funcion escalar en vez de unnest por fila.
	'pg-innodb-rank': (q) => `WITH ${base(q).replace('pesos AS (', 'pesos AS MATERIALIZED (')},
		df AS MATERIALIZED (SELECT p.*, (SELECT COUNT(*) FROM buscables b2 WHERE b2.texto_tsv @@ p.q) AS n FROM pesos p),
		top AS (SELECT b.id, SUM(ts_rank(b.texto_tsv, d.q, 0) * power(log(total.n::numeric / GREATEST(d.n, 1)), 2)) r
		FROM buscables b JOIN df d ON b.texto_tsv @@ d.q CROSS JOIN total
		GROUP BY b.id ORDER BY r DESC, b.id LIMIT ${k})`,
};

const acierta = (filas, c) => filas.some(([libro, desde, hasta]) => libro === c.libro
	&& c.paginas.some((p) => p >= Number(desde) - 1 && p <= Number(hasta) + 1));
const totales = { mysql: 0 };
for (const v of Object.keys(variantes)) totales[v] = 0;
for (const c of casos) {
	const my = sh(['exec', 'akine-mysql', 'sh', '-c', `MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N ${baseMy} -e "SELECT l.titulo, f.pagina_desde, f.pagina_hasta FROM libro_fragmento f JOIN libro l ON l.id = f.libro_id WHERE MATCH(f.texto) AGAINST ('${lit(c.consulta)}' IN NATURAL LANGUAGE MODE) ORDER BY MATCH(f.texto) AGAINST ('${lit(c.consulta)}' IN NATURAL LANGUAGE MODE) DESC LIMIT ${k}"`])
		.split('\n').filter(Boolean).map((l) => l.split('\t'));
	const fila = [c.id, acierta(my, c) ? 'si' : 'no'];
	if (acierta(my, c)) totales.mysql++;
	for (const [v, sql] of Object.entries(variantes)) {
		const pg = sh(['exec', 'akine-postgres', 'psql', '-U', 'postgres', '-d', basePg, '-At', '-F', '\t', '-c',
			`${sql(c.consulta)} SELECT l.titulo, f.pagina_desde, f.pagina_hasta FROM top JOIN libro_fragmento f ON f.id = top.id JOIN libro l ON l.id = f.libro_id`])
			.split('\n').filter(Boolean).map((l) => l.split('\t'));
		const ok = acierta(pg, c);
		if (ok) totales[v]++;
		fila.push(ok ? 'si' : 'no');
	}
	console.log(fila.join('\t'), '\t', c.consulta);
}
console.log(`aciertos en top-${k} sobre ${casos.length} casos:`, totales);
