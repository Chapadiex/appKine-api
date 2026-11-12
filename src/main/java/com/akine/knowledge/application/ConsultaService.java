package com.akine.knowledge.application;

import com.akine.knowledge.application.Interpretacion.Aclarar;
import com.akine.knowledge.application.Interpretacion.Buscar;
import com.akine.knowledge.domain.BibliotecaAjustes;
import com.akine.knowledge.domain.exception.BibliotecaDeshabilitadaException;
import com.akine.knowledge.domain.port.OpcionesGeneracion;
import com.akine.knowledge.spi.CaminoBusqueda;
import com.akine.knowledge.spi.PasajeRecuperado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Consulta a la biblioteca (diseño consulta v2): valida, interpreta, busca con la busqueda hibrida,
 * decide si hay material y arma los mensajes para el modelo, breves o en profundidad.
 *
 * <p>Cada consulta busca y arma de cero: no hay cache de respuestas ni de busquedas. Una consulta
 * independiente (nombra su tema) no hereda nada de la charla: ni libros preferidos ni historial al
 * modelo que redacta (§7.8). No guarda ni registra la pregunta, el historial, los pasajes ni la
 * respuesta; el log lleva solo cantidades.
 */
@Service
public class ConsultaService {

	private static final Logger log = LoggerFactory.getLogger(ConsultaService.class);

	public static final int PREGUNTA_MAXIMA = 600;
	public static final int HISTORIAL_MAXIMO = 6;
	public static final int TURNO_MAXIMO = 2000;
	public static final int LIBROS_PREFERIDOS_MAXIMO = 10;
	public static final int EXTRACTO_MAXIMO = 300;

	/** Breve: 3 a 6 vinetas de una linea. */
	public static final OpcionesGeneracion OPCIONES_BREVE = new OpcionesGeneracion(new BigDecimal("0.2"), 250);
	/** Profundo: hasta 8 vinetas. */
	public static final OpcionesGeneracion OPCIONES_PROFUNDO = new OpcionesGeneracion(new BigDecimal("0.2"), 450);

	private final BibliotecaAcceso acceso;
	private final BibliotecaAjustes ajustes;
	private final Interpretador interpretador;
	private final BusquedaHibrida busqueda;
	private final BusquedaHibrida.Ajustes busquedaAjustes;
	private final String promptBreve;
	private final String promptProfundo;

	public ConsultaService(
			BibliotecaAcceso acceso,
			BibliotecaAjustes ajustes,
			Interpretador interpretador,
			BusquedaHibrida busqueda,
			BusquedaHibrida.Ajustes busquedaAjustes) {
		this.acceso = acceso;
		this.ajustes = ajustes;
		this.interpretador = interpretador;
		this.busqueda = busqueda;
		this.busquedaAjustes = busquedaAjustes;
		this.promptBreve = recurso("knowledge/prompt-consulta.txt");
		this.promptProfundo = recurso("knowledge/prompt-profundo.txt");
	}

	/**
	 * @throws BibliotecaDeshabilitadaException si la biblioteca o la consulta estan apagadas (409)
	 * @throws IllegalArgumentException si la pregunta, el historial o los libros pasan los limites (400)
	 */
	public void validar(OperatingActor actor, long orgId, ConsultaPedido pedido) {
		if (!ajustes.habilitada() || !ajustes.consultaHabilitada()) {
			throw new BibliotecaDeshabilitadaException();
		}
		acceso.exigirConsulta(actor, orgId);
		String pregunta = pedido == null ? null : pedido.pregunta();
		if (pregunta == null || pregunta.isBlank() || pregunta.length() > PREGUNTA_MAXIMA) {
			throw new IllegalArgumentException(
					"La pregunta debe tener entre 1 y " + PREGUNTA_MAXIMA + " caracteres.");
		}
		List<TurnoDeConsulta> historial = historialDe(pedido);
		if (historial.size() > HISTORIAL_MAXIMO) {
			throw new IllegalArgumentException("El historial admite hasta " + HISTORIAL_MAXIMO + " turnos.");
		}
		for (TurnoDeConsulta turno : historial) {
			if (turno == null || turno.rol() == null || turno.texto() == null
					|| turno.texto().length() > TURNO_MAXIMO) {
				throw new IllegalArgumentException(
						"Cada turno del historial necesita rol y un texto de hasta " + TURNO_MAXIMO
								+ " caracteres.");
			}
		}
		List<Long> libros = pedido.librosPreferidos();
		if (libros != null && (libros.size() > LIBROS_PREFERIDOS_MAXIMO
				|| libros.stream().anyMatch(id -> id == null || id <= 0))) {
			throw new IllegalArgumentException("librosPreferidos admite hasta " + LIBROS_PREFERIDOS_MAXIMO
					+ " ids positivos.");
		}
	}

	/** Corre dentro del turno de la cola; {@code limite} es el fin de los 90 s de la consulta. */
	public PlanDeRespuesta preparar(long orgId, ConsultaPedido pedido, Instant limite) {
		List<TurnoDeConsulta> historial = historialDe(pedido);
		long inicio = System.nanoTime();
		Interpretacion interpretacion = interpretador.interpretar(orgId, pedido.pregunta(), historial, limite);
		long msInterpretar = msDesde(inicio);

		if (interpretacion instanceof Aclarar aclarar) {
			log.info("Consulta a la biblioteca con repregunta: opciones={}", aclarar.opciones().size());
			// Sin busqueda ni generacion; la sonda de la repregunta es vectorial.
			return new PlanDeRespuesta(false, CaminoBusqueda.VECTOR, List.of(), List.of(), aclarar, null,
					msInterpretar, -1);
		}
		Buscar buscar = (Buscar) interpretacion;

		Set<Long> preferidos = buscar.independiente() ? Set.of() : librosDe(pedido);
		long inicioBusqueda = System.nanoTime();
		ResultadoHibrido resultado = busqueda.buscar(orgId, buscar.consultas(), buscar.tema(), preferidos);
		long msBuscar = msDesde(inicioBusqueda);
		if (!resultado.hayEvidencia()) {
			log.info("Consulta a la biblioteca sin material: pasajes={}", resultado.pasajes().size());
			return sinMaterial(resultado.camino(), msInterpretar, msBuscar);
		}

		int tope = buscar.profundo() ? busquedaAjustes.pasajesProfundo() : busquedaAjustes.pasajesBreve();
		// Al modelo van solo los pasajes con evidencia, en el orden de la fusion.
		List<PasajeRecuperado> pasajes = resultado.pasajes().stream()
				.filter(p -> p.superaCorte() || p.coincidePalabra())
				.map(PasajeFusionado::pasaje)
				.limit(tope)
				.toList();
		// Quien redacta no recibe la charla, solo la pregunta completa (R14).
		PresupuestoDeContexto.Armado armado = PresupuestoDeContexto.armar(
				buscar.profundo() ? promptProfundo : promptBreve, pasajes, buscar.preguntaCompleta());
		if (armado.pasajes().isEmpty()) {
			log.info("Consulta a la biblioteca sin material: el presupuesto dejo fuera todos los pasajes");
			return sinMaterial(resultado.camino(), msInterpretar, msBuscar);
		}
		List<FuenteDeConsulta> fuentes = armado.pasajes().stream()
				.map(p -> new FuenteDeConsulta(p.libroId(), p.titulo(), p.paginaDesde(), p.paginaHasta(),
						primeros(p.texto(), EXTRACTO_MAXIMO)))
				.toList();
		log.info("Consulta a la biblioteca con material: pasajes={} profundo={} independiente={}",
				armado.pasajes().size(), buscar.profundo(), buscar.independiente());
		return new PlanDeRespuesta(false, resultado.camino(), fuentes, armado.mensajes(), null,
				buscar.profundo() ? OPCIONES_PROFUNDO : OPCIONES_BREVE, msInterpretar, msBuscar);
	}

	private static PlanDeRespuesta sinMaterial(CaminoBusqueda camino, long msInterpretar, long msBuscar) {
		return new PlanDeRespuesta(true, camino, List.of(), List.of(), null, null, msInterpretar, msBuscar);
	}

	private static Set<Long> librosDe(ConsultaPedido pedido) {
		return pedido.librosPreferidos() == null ? Set.of() : new LinkedHashSet<>(pedido.librosPreferidos());
	}

	private static long msDesde(long inicio) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);
	}

	/** Hasta {@code n} caracteres del principio, sin partir un par sustituto en el corte. */
	static String primeros(String texto, int n) {
		if (texto.length() <= n) {
			return texto;
		}
		int fin = Character.isHighSurrogate(texto.charAt(n - 1)) ? n - 1 : n;
		return texto.substring(0, fin);
	}

	/** Hasta {@code n} caracteres del final, sin partir un par sustituto en el corte. */
	static String ultimos(String texto, int n) {
		if (texto.length() <= n) {
			return texto;
		}
		int inicio = texto.length() - n;
		if (Character.isHighSurrogate(texto.charAt(inicio - 1))) {
			inicio++;
		}
		return texto.substring(inicio);
	}

	private static List<TurnoDeConsulta> historialDe(ConsultaPedido pedido) {
		return pedido.historial() == null ? List.of() : pedido.historial();
	}

	static String recurso(String ruta) {
		try (InputStream in = ConsultaService.class.getClassLoader().getResourceAsStream(ruta)) {
			if (in == null) {
				throw new IllegalStateException("Falta el recurso " + ruta);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
		} catch (IOException e) {
			throw new IllegalStateException("No se pudo leer " + ruta, e);
		}
	}
}
