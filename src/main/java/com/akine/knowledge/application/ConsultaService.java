package com.akine.knowledge.application;

import com.akine.knowledge.domain.BibliotecaAjustes;
import com.akine.knowledge.domain.exception.BibliotecaDeshabilitadaException;
import com.akine.knowledge.domain.port.MensajeChat;
import com.akine.knowledge.domain.port.MensajeChat.Rol;
import com.akine.knowledge.domain.port.ModeloDeLenguaje;
import com.akine.knowledge.domain.port.OpcionesGeneracion;
import com.akine.knowledge.spi.BibliotecaDirectory;
import com.akine.knowledge.spi.PasajeRecuperado;
import com.akine.knowledge.spi.ResultadoBusqueda;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Consulta a la biblioteca: valida, reescribe la repregunta, busca, decide si hay material y arma
 * los mensajes para el modelo. No guarda ni registra la pregunta, el historial, los pasajes ni la
 * respuesta; el log lleva solo cantidades.
 */
@Service
public class ConsultaService {

	private static final Logger log = LoggerFactory.getLogger(ConsultaService.class);

	public static final int PREGUNTA_MAXIMA = 600;
	public static final int HISTORIAL_MAXIMO = 6;
	public static final int TURNO_MAXIMO = 2000;
	public static final int K = 5;
	/** Pasajes que llegan al modelo: menos texto que leer en CPU, menos tiempo hasta la primera palabra. */
	public static final int PASAJES_AL_MODELO = 3;
	public static final int EXTRACTO_MAXIMO = 300;

	static final OpcionesGeneracion OPCIONES_REESCRITURA = new OpcionesGeneracion(java.math.BigDecimal.ZERO, 80);
	static final Duration PLAZO_REESCRITURA = Duration.ofSeconds(10);

	private final BibliotecaAcceso acceso;
	private final BibliotecaAjustes ajustes;
	private final BibliotecaDirectory directory;
	private final ObjectProvider<ModeloDeLenguaje> modelo;
	private final String promptConsulta;
	private final String promptReescritura;

	public ConsultaService(
			BibliotecaAcceso acceso,
			BibliotecaAjustes ajustes,
			BibliotecaDirectory directory,
			ObjectProvider<ModeloDeLenguaje> modelo) {
		this.acceso = acceso;
		this.ajustes = ajustes;
		this.directory = directory;
		this.modelo = modelo;
		this.promptConsulta = recurso("knowledge/prompt-consulta.txt");
		this.promptReescritura = recurso("knowledge/prompt-reescritura.txt");
	}

	/**
	 * @throws BibliotecaDeshabilitadaException si la biblioteca o la consulta estan apagadas (409)
	 * @throws IllegalArgumentException si la pregunta o el historial pasan los limites (400)
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
	}

	/** Corre dentro del turno de la cola; {@code limite} es el fin de los 90 s de la consulta. */
	public PlanDeRespuesta preparar(long orgId, ConsultaPedido pedido, Instant limite) {
		List<TurnoDeConsulta> historial = historialDe(pedido);
		String pregunta = pedido.pregunta();
		String paraBuscar = historial.isEmpty() ? pregunta : reescribir(historial, pregunta, limite);

		ResultadoBusqueda resultado = directory.buscar(orgId, paraBuscar, K);
		if (!resultado.alcanza()) {
			log.info("Consulta a la biblioteca sin material: pasajes={}", resultado.pasajes().size());
			return new PlanDeRespuesta(true, resultado.camino(), List.of(), List.of());
		}
		List<PasajeRecuperado> pasajes = resultado.pasajes().stream()
				.filter(PasajeRecuperado::superaCorte).limit(PASAJES_AL_MODELO).toList();
		PresupuestoDeContexto.Armado armado = PresupuestoDeContexto.armar(promptConsulta, pasajes, historial, pregunta);
		if (armado.pasajes().isEmpty()) {
			log.info("Consulta a la biblioteca sin material: el presupuesto dejo fuera todos los pasajes");
			return new PlanDeRespuesta(true, resultado.camino(), List.of(), List.of());
		}
		List<FuenteDeConsulta> fuentes = armado.pasajes().stream()
				.map(p -> new FuenteDeConsulta(p.libroId(), p.titulo(), p.paginaDesde(), p.paginaHasta(),
						primeros(p.texto(), EXTRACTO_MAXIMO)))
				.toList();
		log.info("Consulta a la biblioteca con material: pasajes={}", armado.pasajes().size());
		return new PlanDeRespuesta(false, resultado.camino(), fuentes, armado.mensajes());
	}

	private String reescribir(List<TurnoDeConsulta> historial, String pregunta, Instant limite) {
		Duration restante = Duration.between(Instant.now(), limite);
		Duration plazo = restante.compareTo(PLAZO_REESCRITURA) < 0 ? restante : PLAZO_REESCRITURA;
		if (!plazo.isNegative() && !plazo.isZero()) {
			try {
				List<MensajeChat> mensajes = new ArrayList<>();
				mensajes.add(new MensajeChat(Rol.SISTEMA, promptReescritura));
				for (TurnoDeConsulta turno : historial) {
					mensajes.add(new MensajeChat(
							turno.rol() == TurnoDeConsulta.Rol.ASISTENTE ? Rol.ASISTENTE : Rol.USUARIO,
							turno.texto()));
				}
				mensajes.add(new MensajeChat(Rol.USUARIO, pregunta));
				String respuesta = modelo.getObject().completar(mensajes, OPCIONES_REESCRITURA, plazo);
				if (respuesta != null && !respuesta.isBlank()) {
					String reescrita = respuesta.strip();
					return primeros(reescrita, PREGUNTA_MAXIMA);
				}
			} catch (RuntimeException e) {
				log.info("Reescritura de la pregunta fallida, se usa el respaldo: {}", e.getClass().getSimpleName());
			}
		}
		return Interpretador.respaldoConcatenado(historial, pregunta);
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
