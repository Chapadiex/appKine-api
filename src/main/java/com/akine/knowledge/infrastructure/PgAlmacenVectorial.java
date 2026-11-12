package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.port.AlmacenVectorial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Busqueda sobre PostgreSQL: vectores con pgvector y texto completo con {@code tsvector}.
 *
 * <h2>Vector</h2>
 *
 * <p>Coseno contra {@code libro_fragmento.vector} ({@code vector(768)}): el puntaje es
 * {@code 1 - distancia coseno}, el mismo numero que calculaba el recorrido en Java que reemplaza, asi
 * que los cortes ({@code min-score}) no cambian. El indice HNSW es aproximado; la consulta lo usa con
 * recorrido iterativo en orden estricto ({@code hnsw.iterative_scan = strict_order}): sin eso, el
 * filtro por organizacion y estado se aplica despues del indice y una organizacion chica podia
 * recibir menos de {@code k} fragmentos aunque los tuviera. El filtro de organizacion va siempre en
 * el {@code WHERE}: el indice propone candidatos, nunca decide que se devuelve.
 *
 * <h2>Palabras</h2>
 *
 * <p>Cada palabra de la consulta alcanza (semantica OR, como el modo de lenguaje natural del
 * FULLTEXT de MySQL que reemplaza), en espanol y en ingles, sin tildes ({@code akine_es} y
 * {@code akine_en}, V10). El orden es {@code ts_rank_cd} y el puntaje {@code s / (s + 1)}, en
 * {@code [0,1)}.
 */
@Repository
public class PgAlmacenVectorial implements AlmacenVectorial {

	private static final Logger log = LoggerFactory.getLogger(PgAlmacenVectorial.class);

	/** Dimension de {@code libro_fragmento.vector}: la de multilingual-e5-base. */
	static final int DIMENSION = 768;

	/** Condiciones de un fragmento buscable; {@code f} es el fragmento y {@code l} su libro. */
	private static final String BUSCABLE = "l.organization_id = f.organization_id AND l.activo = true"
			+ " AND l.estado = 'LISTO' AND f.sospechoso = false";

	/** Piso de candidatos del indice por busqueda; el default de pgvector es 40. */
	private static final int EF_SEARCH_MINIMO = 100;

	private final JdbcTemplate jdbc;
	private final TransactionTemplate lectura;

	public PgAlmacenVectorial(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.lectura = new TransactionTemplate(transactionManager);
		this.lectura.setReadOnly(true);
	}

	// ---------------------------------------------------------------- vector

	@Override
	public List<Coincidencia> porVector(long orgId, String modelo, float[] consulta, int k) {
		if (consulta.length != DIMENSION) {
			log.warn("La consulta tiene {} dimensiones y la biblioteca guarda {}: no hay coincidencias por vector",
					consulta.length, DIMENSION);
			return List.of();
		}
		String vector = VectorPg.literal(consulta);
		// set_config(..., true) dura lo que la transaccion: no queda en la conexion del pool.
		List<Coincidencia> resultado = lectura.execute(estado -> {
			jdbc.queryForList("SELECT set_config('hnsw.iterative_scan', 'strict_order', true),"
					+ " set_config('hnsw.ef_search', ?, true)", String.valueOf(Math.max(EF_SEARCH_MINIMO, k)));
			return jdbc.query("SELECT f.id, 1 - (f.vector <=> CAST(? AS vector)) AS similitud"
							+ " FROM libro_fragmento f JOIN libro l ON l.id = f.libro_id"
							+ " WHERE f.organization_id = ? AND " + BUSCABLE
							+ " AND f.modelo_embeddings = ? AND f.vector IS NOT NULL"
							+ " ORDER BY f.vector <=> CAST(? AS vector), f.id LIMIT ?",
					(rs, n) -> new Coincidencia(rs.getLong(1), puntaje(rs.getDouble(2))),
					vector, orgId, modelo, vector, k);
		});
		return resultado == null ? List.of() : resultado;
	}

	// ---------------------------------------------------------------- palabras

	/** Sin filtro de modelo: alcanza tambien a los fragmentos todavia sin vector. */
	@Override
	public List<Coincidencia> porPalabras(long orgId, String consulta, int k) {
		String alguna = alMenosUna(consulta);
		if (alguna.isEmpty()) {
			return List.of();
		}
		return jdbc.query("WITH q AS (SELECT websearch_to_tsquery('akine_es', ?)"
						+ " || websearch_to_tsquery('akine_en', ?) AS consulta)"
						+ " SELECT f.id, ts_rank_cd(f.texto_tsv, q.consulta) AS relevancia"
						+ " FROM libro_fragmento f JOIN libro l ON l.id = f.libro_id CROSS JOIN q"
						+ " WHERE f.organization_id = ? AND " + BUSCABLE
						+ " AND f.texto_tsv @@ q.consulta"
						+ " ORDER BY relevancia DESC, f.id LIMIT ?",
				(rs, n) -> {
					double s = rs.getDouble(2);
					return new Coincidencia(rs.getLong(1), puntaje(s / (s + 1)));
				}, alguna, alguna, orgId, k);
	}

	/**
	 * "rodilla dolor" pasa a "rodilla or dolor": {@code websearch_to_tsquery} une las palabras con AND
	 * y el FULLTEXT de MySQL las sumaba. Las palabras salen sin signos, asi que el texto del usuario no
	 * puede armar operadores.
	 */
	static String alMenosUna(String consulta) {
		if (consulta == null) {
			return "";
		}
		return Arrays.stream(consulta.split("[^\\p{L}\\p{N}]+"))
				.filter(p -> !p.isBlank())
				.collect(Collectors.joining(" or "));
	}

	// ---------------------------------------------------------------- textos

	@Override
	public List<FragmentoEncontrado> fragmentos(long orgId, List<Long> fragmentoIds) {
		if (fragmentoIds.isEmpty()) {
			return List.of();
		}
		List<Object> args = new ArrayList<>(fragmentoIds.size() + 1);
		args.add(orgId);
		args.addAll(fragmentoIds);
		String marcas = String.join(",", Collections.nCopies(fragmentoIds.size(), "?"));
		return jdbc.query("SELECT f.id, f.libro_id, l.titulo, f.pagina_desde, f.pagina_hasta, f.texto"
						+ " FROM libro_fragmento f JOIN libro l ON l.id = f.libro_id"
						+ " WHERE f.organization_id = ? AND " + BUSCABLE + " AND f.id IN (" + marcas + ")",
				(rs, n) -> new FragmentoEncontrado(rs.getLong(1), rs.getLong(2), rs.getString(3),
						rs.getInt(4), rs.getInt(5), rs.getString(6)),
				args.toArray());
	}

	private static BigDecimal puntaje(double valor) {
		return BigDecimal.valueOf(valor).setScale(4, RoundingMode.HALF_UP);
	}
}
