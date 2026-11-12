package com.akine.knowledge.application;

import com.akine.knowledge.domain.port.AlmacenVectorial;
import com.akine.knowledge.domain.port.AlmacenVectorial.Coincidencia;
import com.akine.knowledge.domain.port.AlmacenVectorial.FragmentoEncontrado;
import com.akine.knowledge.domain.port.ModeloEmbeddings;
import com.akine.knowledge.spi.CaminoBusqueda;
import com.akine.knowledge.spi.PasajeRecuperado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Busqueda hibrida de la consulta: por cada consulta, vector (e5) y texto completo de MySQL, y
 * fusion con Reciprocal Rank Fusion ({@code sum 1/(k+posicion)}, posicion desde 1).
 *
 * <p>Los filtros de tenant, libro activo, LISTO y no sospechoso viven dentro de las consultas del
 * {@link AlmacenVectorial}. No se guarda ni se cachea nada entre consultas y los logs llevan solo
 * cantidades, nunca texto.
 *
 * <p>Hay evidencia si cada palabra de tema (las de {@link Interpretador}: sin vacias, relleno ni
 * aspectos, comparadas por base) aparece en algun pasaje devuelto y al menos {@code minimoPasajes}
 * coinciden por palabras ("coincide": esta en el top de texto completo y nombra alguna). Sin
 * palabras de tema decide el corte vectorial.
 */
@Service
public class BusquedaHibrida {

	private static final Logger log = LoggerFactory.getLogger(BusquedaHibrida.class);

	/** Letras minimas de la palabra corta para que una sea comienzo de la otra. */
	private static final int LARGO_PREFIJO = 5;
	/** Hasta cuantas letras una palabra del tema puede ser una sigla. */
	private static final int LARGO_SIGLA = 4;

	/** Configuracion; los pesos van en {@code double} porque son de aplicacion, no de dominio. */
	public record Ajustes(
			int topKVector, int topKPalabras, int rrfK, int pasajesBreve, int pasajesProfundo,
			double bonusLibro, int minimoPasajes, BigDecimal corteVector) {
	}

	private final AlmacenVectorial almacen;
	private final ObjectProvider<ModeloEmbeddings> modelo;
	private final Ajustes ajustes;

	public BusquedaHibrida(AlmacenVectorial almacen, ObjectProvider<ModeloEmbeddings> modelo, Ajustes ajustes) {
		this.almacen = almacen;
		this.modelo = modelo;
		this.ajustes = ajustes;
	}

	/**
	 * @param consultas textos a buscar (la pregunta y sus reformulaciones); cada uno se embebe una vez
	 * @param tema para decidir si un pasaje coincide por palabras; puede ser nulo
	 * @param librosPreferidos libros que reciben {@code bonus-libro}; solo reordena
	 */
	public ResultadoHibrido buscar(long orgId, List<String> consultas, String tema, Set<Long> librosPreferidos) {
		if (orgId <= 0) {
			throw new IllegalArgumentException("La organizacion es obligatoria");
		}
		List<String> textos = consultas == null ? List.of() : consultas.stream()
				.filter(c -> c != null && !c.isBlank()).map(String::strip).toList();
		Map<Long, Acumulado> candidatos = new LinkedHashMap<>();
		boolean hayVector = false;
		String fallaDelModelo = null;
		ModeloEmbeddings m = modelo.getIfAvailable();
		for (String texto : textos) {
			float[] vector = null;
			if (m != null) {
				// El catch cubre solo al modelo: una falla del almacen se propaga.
				try {
					vector = m.consulta(texto);
				} catch (RuntimeException falla) {
					fallaDelModelo = falla.getClass().getSimpleName();
				}
			}
			List<Coincidencia> porVector = vector == null
					? List.of() : almacen.porVector(orgId, m.id(), vector, ajustes.topKVector());
			hayVector |= !porVector.isEmpty();
			sumar(candidatos, porVector, true);
			sumar(candidatos, almacen.porPalabras(orgId, texto, ajustes.topKPalabras()), false);
		}
		if (fallaDelModelo != null) {
			// Solo la clase, y una vez por busqueda: el mensaje podria repetir la consulta.
			log.warn("El modelo de embeddings fallo al calcular la consulta ({}); se busca por palabras", fallaDelModelo);
		}
		CaminoBusqueda camino = hayVector ? CaminoBusqueda.VECTOR : CaminoBusqueda.PALABRAS;
		if (candidatos.isEmpty()) {
			return new ResultadoHibrido(camino, List.of(), false);
		}

		Map<Long, FragmentoEncontrado> fragmentos = almacen.fragmentos(orgId, List.copyOf(candidatos.keySet()))
				.stream().collect(Collectors.toMap(FragmentoEncontrado::fragmentoId, Function.identity()));
		Set<String> palabrasDelTema = Interpretador.palabrasDeTemaDe(tema);
		Set<Long> preferidos = librosPreferidos == null ? Set.of() : librosPreferidos;
		List<PasajeFusionado> fusionados = new ArrayList<>(candidatos.size());
		for (Map.Entry<Long, Acumulado> e : candidatos.entrySet()) {
			FragmentoEncontrado f = fragmentos.get(e.getKey());
			if (f == null) {
				continue;
			}
			Acumulado a = e.getValue();
			boolean supera = a.mejorVector != null && a.mejorVector.compareTo(ajustes.corteVector()) >= 0;
			boolean coincide = a.enPalabras && contieneAlguna(f.texto(), palabrasDelTema);
			double rrf = a.rrf + (preferidos.contains(f.libroId()) ? ajustes.bonusLibro() : 0d);
			BigDecimal puntaje = a.mejorVector != null ? a.mejorVector : a.mejorPalabras;
			PasajeRecuperado pasaje = new PasajeRecuperado(f.libroId(), f.titulo(), f.paginaDesde(),
					f.paginaHasta(), f.texto(), puntaje.doubleValue(), supera);
			fusionados.add(new PasajeFusionado(pasaje, rrf, supera, coincide));
		}
		// Orden estable: a igual RRF queda primero el que llego antes.
		fusionados.sort(Comparator.comparingDouble(PasajeFusionado::rrf).reversed());

		int tope = Math.max(ajustes.pasajesBreve(), ajustes.pasajesProfundo());
		List<PasajeFusionado> devueltos = List.copyOf(fusionados.subList(0, Math.min(tope, fusionados.size())));
		// El tema se busca en todos los candidatos; la cantidad que coincide, en lo que se le manda al modelo.
		boolean hayEvidencia = palabrasDelTema.isEmpty()
				? devueltos.stream().filter(PasajeFusionado::superaCorte).count() >= ajustes.minimoPasajes()
				: nombraTodoElTema(fusionados, palabrasDelTema)
						&& devueltos.stream().filter(PasajeFusionado::coincidePalabra).count() >= ajustes.minimoPasajes();
		log.debug("Busqueda hibrida: {} consultas, {} candidatos, evidencia {}, camino {}",
				textos.size(), candidatos.size(), hayEvidencia, camino);
		return new ResultadoHibrido(camino, devueltos, hayEvidencia);
	}

	/**
	 * Cada palabra de tema aparece en algun candidato (vector o texto completo: si el libro la nombra, el
	 * texto completo la trae aunque no quede entre los devueltos). El coseno de e5 no separa un tema
	 * ausente de uno presente (los dos rondan 0,82–0,85): lo que los separa es que el libro lo nombre.
	 */
	private static boolean nombraTodoElTema(List<PasajeFusionado> candidatos, Set<String> palabrasDelTema) {
		Set<String> nombradas = new HashSet<>();
		for (PasajeFusionado p : candidatos) {
			nombradas.addAll(Arrays.asList(normalizar(p.pasaje().texto()).split("[^\\p{L}\\p{N}]+")));
		}
		// Una sigla ("acv") el libro la escribe desarrollada: no es obligatoria si el tema dice algo mas.
		Set<String> obligatorias = palabrasDelTema.stream().filter(w -> w.length() > LARGO_SIGLA)
				.collect(Collectors.toSet());
		return (obligatorias.isEmpty() ? palabrasDelTema : obligatorias).stream()
				.allMatch(w -> nombra(nombradas, w));
	}

	private void sumar(Map<Long, Acumulado> candidatos, List<Coincidencia> lista, boolean vector) {
		Set<Long> vistos = new HashSet<>();
		int posicion = 0;
		for (Coincidencia c : lista) {
			if (!vistos.add(c.fragmentoId())) {
				continue;
			}
			posicion++;
			Acumulado a = candidatos.computeIfAbsent(c.fragmentoId(), id -> new Acumulado());
			a.rrf += 1d / (ajustes.rrfK() + posicion);
			if (vector) {
				a.mejorVector = max(a.mejorVector, c.puntaje());
			} else {
				a.enPalabras = true;
				a.mejorPalabras = max(a.mejorPalabras, c.puntaje());
			}
		}
	}

	private static BigDecimal max(BigDecimal actual, BigDecimal nuevo) {
		return actual == null || nuevo.compareTo(actual) > 0 ? nuevo : actual;
	}

	private static boolean contieneAlguna(String texto, Set<String> palabras) {
		if (palabras.isEmpty()) {
			return false;
		}
		Set<String> delTexto = new HashSet<>(Arrays.asList(normalizar(texto).split("[^\\p{L}\\p{N}]+")));
		return palabras.stream().anyMatch(w -> nombra(delTexto, w));
	}

	/**
	 * El texto nombra la palabra: igual, misma base ({@link Interpretador#aparece}), o una es comienzo
	 * de la otra con 5 letras o mas en la corta ("doloroso" y "dolor", "medular" y "medula"; no
	 * "fibromialgia" y "fibrosis").
	 */
	private static boolean nombra(Set<String> delTexto, String palabra) {
		if (Interpretador.aparece(palabra, delTexto)) {
			return true;
		}
		return delTexto.stream().anyMatch(t -> Math.min(t.length(), palabra.length()) >= LARGO_PREFIJO
				&& (t.startsWith(palabra) || palabra.startsWith(t)));
	}

	/** Sin tildes ni mayusculas. */
	static String normalizar(String texto) {
		return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
				.toLowerCase(Locale.ROOT);
	}

	private static final class Acumulado {
		double rrf;
		BigDecimal mejorVector;
		BigDecimal mejorPalabras;
		boolean enPalabras;
	}
}
