package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.Vectores;
import com.akine.knowledge.domain.port.AlmacenVectorial;
import com.akine.knowledge.domain.port.BibliotecaVersionPort;
import com.akine.knowledge.infrastructure.config.BibliotecaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Busqueda sobre MySQL: los vectores de cada organizacion se leen una vez y quedan en memoria; el
 * texto completo va por el indice {@code ft_libro_fragmento_texto}.
 *
 * <h2>Cache</h2>
 *
 * <p>Por organizacion, una entrada con los ids y los vectores de los fragmentos buscables (libro
 * activo y LISTO, fragmento no sospechoso, vector del modelo pedido), cargada con UNA consulta.
 * La entrada recuerda la {@code biblioteca_version} leida ANTES de cargar y el modelo: en cada
 * busqueda se relee la version (una consulta por clave primaria) y, si cambio o el modelo es otro,
 * se recarga. Toda escritura que cambia lo buscable avanza la version en su misma transaccion, asi
 * que una entrada vieja no se usa despues de que el cambio se ve confirmado.
 *
 * <p>Tope global en bytes ({@code akine.biblioteca.busqueda.cache-max-bytes}) con desalojo LRU por
 * organizacion. Una organizacion que sola supera el tope se busca igual, sin cachear. Dos busquedas
 * simultaneas de la misma organizacion sin entrada pueden cargar las dos: se acepta (queda la de
 * version mas nueva) para no retener un lock durante la consulta.
 *
 * <p>El recorrido es exacto: coseno contra todos los vectores y un monticulo de {@code k}.
 */
@Repository
public class MySqlAlmacenVectorial implements AlmacenVectorial {

	private static final Logger log = LoggerFactory.getLogger(MySqlAlmacenVectorial.class);

	/** Condiciones de un fragmento buscable; {@code f} es el fragmento y {@code l} su libro. */
	private static final String BUSCABLE = "l.organization_id = f.organization_id AND l.activo = 1"
			+ " AND l.estado = 'LISTO' AND f.sospechoso = 0";

	/** Cabecera aproximada de un arreglo en la JVM. */
	private static final long CABECERA_ARREGLO = 16;

	private record Entrada(String modelo, long version, long[] ids, float[][] vectores, long bytes) {
	}

	private record Puntuado(int indice, float puntaje) {
	}

	private final JdbcTemplate jdbc;
	private final BibliotecaVersionPort versiones;
	private final long topeBytes;
	private final LinkedHashMap<Long, Entrada> cache = new LinkedHashMap<>(16, 0.75f, true);
	private long bytesEnUso;

	public MySqlAlmacenVectorial(
			JdbcTemplate jdbc, BibliotecaVersionPort versiones, BibliotecaProperties properties) {
		this.jdbc = jdbc;
		this.versiones = versiones;
		this.topeBytes = properties.busqueda().cacheMaxBytes();
	}

	// ---------------------------------------------------------------- vector

	@Override
	public List<Coincidencia> porVector(long orgId, String modelo, float[] consulta, int k) {
		long version = versiones.actual(orgId);
		Entrada entrada;
		synchronized (cache) {
			entrada = cache.get(orgId);
		}
		if (entrada == null || entrada.version() != version || !entrada.modelo().equals(modelo)) {
			entrada = cargar(orgId, modelo, version, consulta.length);
			guardar(orgId, entrada);
		}
		return mejores(entrada, consulta, k);
	}

	private Entrada cargar(long orgId, String modelo, long version, int dimension) {
		List<Long> ids = new ArrayList<>();
		List<float[]> vectores = new ArrayList<>();
		int[] descartados = {0};
		jdbc.query("SELECT f.id, f.vector FROM libro_fragmento f JOIN libro l ON l.id = f.libro_id"
						+ " WHERE f.organization_id = ? AND " + BUSCABLE
						+ " AND f.modelo_embeddings = ? AND f.vector IS NOT NULL ORDER BY f.id",
				rs -> {
					float[] v = Vectores.deBytes(rs.getBytes(2));
					if (v.length != dimension) {
						descartados[0]++;
						return;
					}
					ids.add(rs.getLong(1));
					vectores.add(v);
				}, orgId, modelo);
		if (descartados[0] > 0) {
			log.warn("{} vectores de la organizacion {} no tienen la dimension del modelo {}",
					descartados[0], orgId, modelo);
		}
		long[] arregloIds = ids.stream().mapToLong(Long::longValue).toArray();
		long bytes = CABECERA_ARREGLO + (long) arregloIds.length * Long.BYTES
				+ (long) vectores.size() * (CABECERA_ARREGLO + (long) dimension * Float.BYTES);
		return new Entrada(modelo, version, arregloIds, vectores.toArray(float[][]::new), bytes);
	}

	private void guardar(long orgId, Entrada entrada) {
		synchronized (cache) {
			Entrada actual = cache.get(orgId);
			if (actual != null && actual.version() > entrada.version()
					&& actual.modelo().equals(entrada.modelo())) {
				return;
			}
			if (actual != null) {
				cache.remove(orgId);
				bytesEnUso -= actual.bytes();
			}
			if (entrada.bytes() > topeBytes) {
				return;
			}
			cache.put(orgId, entrada);
			bytesEnUso += entrada.bytes();
			Iterator<Map.Entry<Long, Entrada>> viejas = cache.entrySet().iterator();
			while (bytesEnUso > topeBytes && viejas.hasNext()) {
				Map.Entry<Long, Entrada> vieja = viejas.next();
				if (vieja.getKey() != orgId) {
					bytesEnUso -= vieja.getValue().bytes();
					viejas.remove();
				}
			}
		}
	}

	/** Primero se acota a {@code k} con un monticulo de minimos, despues se ordena. */
	private static List<Coincidencia> mejores(Entrada entrada, float[] consulta, int k) {
		long[] ids = entrada.ids();
		// Peor primero: menor puntaje y, a igual puntaje, mayor id.
		Comparator<Puntuado> peorPrimero = Comparator.comparingDouble(Puntuado::puntaje)
				.thenComparing(Comparator.comparingLong((Puntuado p) -> ids[p.indice()]).reversed());
		PriorityQueue<Puntuado> monticulo = new PriorityQueue<>(k + 1, peorPrimero);
		for (int i = 0; i < ids.length; i++) {
			Puntuado candidato = new Puntuado(i, Vectores.coseno(consulta, entrada.vectores()[i]));
			if (monticulo.size() < k) {
				monticulo.add(candidato);
			} else if (peorPrimero.compare(candidato, monticulo.peek()) > 0) {
				monticulo.poll();
				monticulo.add(candidato);
			}
		}
		List<Puntuado> elegidos = new ArrayList<>(monticulo);
		elegidos.sort(peorPrimero.reversed());
		return elegidos.stream()
				.map(p -> new Coincidencia(ids[p.indice()], puntaje(p.puntaje())))
				.toList();
	}

	// ---------------------------------------------------------------- palabras

	/** Sin filtro de modelo: alcanza tambien a los fragmentos todavia sin vector. */
	@Override
	public List<Coincidencia> porPalabras(long orgId, String consulta, int k) {
		return jdbc.query("SELECT f.id, MATCH(f.texto) AGAINST (? IN NATURAL LANGUAGE MODE) AS relevancia"
						+ " FROM libro_fragmento f JOIN libro l ON l.id = f.libro_id"
						+ " WHERE f.organization_id = ? AND " + BUSCABLE
						+ " AND MATCH(f.texto) AGAINST (? IN NATURAL LANGUAGE MODE)"
						+ " ORDER BY relevancia DESC, f.id LIMIT ?",
				(rs, n) -> {
					double s = rs.getDouble(2);
					return new Coincidencia(rs.getLong(1), puntaje(s / (s + 1)));
				}, consulta, orgId, consulta, k);
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
