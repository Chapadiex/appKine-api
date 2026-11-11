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
 * <p>Hay evidencia si al menos {@code minimoPasajes} pasajes fusionados superan el corte vectorial
 * o coinciden por palabras con el tema ("coincide": esta en el top de texto completo y su texto
 * contiene, sin tildes ni mayusculas, alguna palabra del tema de 4 letras o mas).
 */
@Service
public class BusquedaHibrida {

	private static final Logger log = LoggerFactory.getLogger(BusquedaHibrida.class);

	private static final int LARGO_MINIMO_PALABRA = 4;

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
		Set<String> palabrasDelTema = palabrasDelTema(tema);
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
		// La evidencia se mide sobre lo que se le manda al modelo, no sobre todos los candidatos.
		long conEvidencia = devueltos.stream().filter(p -> p.superaCorte() || p.coincidePalabra()).count();
		log.debug("Busqueda hibrida: {} consultas, {} candidatos, {} con evidencia, camino {}",
				textos.size(), candidatos.size(), conEvidencia, camino);
		return new ResultadoHibrido(camino, devueltos, conEvidencia >= ajustes.minimoPasajes());
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

	static Set<String> palabrasDelTema(String tema) {
		if (tema == null || tema.isBlank()) {
			return Set.of();
		}
		Set<String> palabras = new HashSet<>();
		for (String p : normalizar(tema).split("[^\\p{L}\\p{N}]+")) {
			if (p.length() >= LARGO_MINIMO_PALABRA) {
				palabras.add(p);
			}
		}
		return palabras;
	}

	private static boolean contieneAlguna(String texto, Set<String> palabras) {
		if (palabras.isEmpty()) {
			return false;
		}
		Set<String> delTexto = new HashSet<>(Arrays.asList(normalizar(texto).split("[^\\p{L}\\p{N}]+")));
		return palabras.stream().anyMatch(delTexto::contains);
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
