package com.akine.assistant.application;

import com.akine.assistant.domain.port.MensajeChat;
import com.akine.assistant.domain.port.MensajeChat.Rol;
import com.akine.assistant.domain.port.ModeloDeLenguaje;
import com.akine.assistant.spi.Intencion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Convierte una pregunta en una {@link Interpretacion} con <b>una sola</b> llamada al modelo en modo
 * JSON (diseno, §4.1). El modelo no ve datos del centro: solo el prompt, las preguntas y lo que se
 * entendio de las anteriores.
 *
 * <p>Nada de lo que devuelve el modelo se usa sin validar. Un JSON ilegible, una intencion que no es
 * del catalogo o un {@code REPORTE} sin reporte valido terminan en
 * {@link Interpretacion#FUERA_DE_ALCANCE}, nunca en una excepcion: ese es tambien el limite de
 * cualquier intento de cambiarle las instrucciones, que a lo sumo elige mal una intencion que igual
 * corre con los permisos del usuario.
 *
 * <p><b>Seguimientos</b> (medicion del 15/11): en "¿y tiene la sesion cargada?" el modelo pierde el
 * periodo de la pregunta anterior. Un campo que la intencion usa y que el modelo deja vacio se hereda
 * de la interpretacion anterior. Es la misma regla que necesito la biblioteca v2.
 *
 * <p>Si el modelo no responde, {@code ModeloNoDisponibleException} se propaga: no se responde
 * "fuera de alcance" a una pregunta que no se llego a interpretar.
 */
public class Interpretador {

	private static final Logger log = LoggerFactory.getLogger(Interpretador.class);

	/** Las que el modelo puede elegir. {@code BUSCAR_PERSONA} es interna. */
	static final Set<Intencion> ELEGIBLES = EnumSet.of(Intencion.ASISTENCIA_PERSONA,
			Intencion.SESION_DE_PERSONA, Intencion.AGENDA_DEL_DIA, Intencion.ASISTENTES_CLASE,
			Intencion.REPORTE, Intencion.FUERA_DE_ALCANCE);

	static final Set<String> REPORTES = Set.of("ECONOMICO", "TURNOS", "CLASES");

	/** Mas atras que esto el modelo de 4B se confunde y el prompt crece sin aportar. */
	static final int MAX_TURNOS_PREVIOS = 3;

	/** Una pregunta mas larga que esto no es una pregunta del catalogo. */
	public static final int MAX_LARGO_PREGUNTA = 300;

	private static final Set<String> TITULOS = Set.of(
			"sr", "sr.", "sra", "sra.", "senor", "senora", "don", "dona", "el", "la");

	private final Supplier<ModeloDeLenguaje> modelo;
	private final String prompt;
	private final Duration plazo;
	private final JsonMapper json = JsonMapper.builder().build();

	public Interpretador(Supplier<ModeloDeLenguaje> modelo, String prompt, Duration plazo) {
		this.modelo = Objects.requireNonNull(modelo, "modelo");
		this.prompt = Objects.requireNonNull(prompt, "prompt");
		this.plazo = Objects.requireNonNull(plazo, "plazo");
	}

	public Interpretacion interpretar(String pregunta, List<TurnoPrevio> historial) {
		String texto = pregunta == null ? "" : pregunta.strip();
		if (texto.isEmpty() || texto.length() > MAX_LARGO_PREGUNTA) {
			return Interpretacion.FUERA_DE_ALCANCE;
		}
		List<TurnoPrevio> previos = historial == null ? List.of()
				: historial.subList(Math.max(0, historial.size() - MAX_TURNOS_PREVIOS), historial.size());

		String crudo = modelo.get().completarJson(mensajes(texto, previos), plazo);
		Interpretacion leida = leer(crudo);
		return previos.isEmpty() ? leida : heredar(leida, previos.getLast().interpretacion());
	}

	List<MensajeChat> mensajes(String pregunta, List<TurnoPrevio> previos) {
		List<MensajeChat> mensajes = new ArrayList<>();
		mensajes.add(new MensajeChat(Rol.SISTEMA, prompt));
		for (TurnoPrevio previo : previos) {
			mensajes.add(new MensajeChat(Rol.USUARIO, previo.pregunta()));
			mensajes.add(new MensajeChat(Rol.ASISTENTE, comoJson(previo.interpretacion())));
		}
		mensajes.add(new MensajeChat(Rol.USUARIO, pregunta));
		return mensajes;
	}

	/** El mismo formato que se le pide al modelo, para que el historial le hable en su idioma. */
	String comoJson(Interpretacion i) {
		Map<String, Object> objeto = new LinkedHashMap<>();
		objeto.put("intencion", i.intencion().name());
		objeto.put("persona", i.persona());
		objeto.put("clase", i.clase());
		objeto.put("periodo", i.periodo());
		objeto.put("reporte", i.reporte());
		return json.writeValueAsString(objeto);
	}

	Interpretacion leer(String crudo) {
		JsonNode raiz;
		try {
			raiz = json.readTree(crudo == null ? "" : crudo);
		}
		catch (JacksonException e) {
			log.debug("El interpretador devolvio un JSON ilegible");
			return Interpretacion.FUERA_DE_ALCANCE;
		}
		if (raiz == null || !raiz.isObject()) {
			return Interpretacion.FUERA_DE_ALCANCE;
		}
		Intencion intencion = intencion(texto(raiz, "intencion"));
		if (intencion == Intencion.FUERA_DE_ALCANCE) {
			return Interpretacion.FUERA_DE_ALCANCE;
		}
		String reporte = intencion == Intencion.REPORTE ? reporte(texto(raiz, "reporte")) : null;
		if (intencion == Intencion.REPORTE && reporte == null) {
			return Interpretacion.FUERA_DE_ALCANCE;
		}
		boolean usaPersona = intencion == Intencion.ASISTENCIA_PERSONA || intencion == Intencion.SESION_DE_PERSONA;
		return new Interpretacion(
				intencion,
				usaPersona ? persona(texto(raiz, "persona")) : null,
				intencion == Intencion.ASISTENTES_CLASE ? clase(texto(raiz, "clase")) : null,
				ResolutorDePeriodo.normalizar(texto(raiz, "periodo")),
				reporte);
	}

	/** Completa lo que falta con la interpretacion anterior, solo en los campos que la intencion usa. */
	static Interpretacion heredar(Interpretacion actual, Interpretacion anterior) {
		if (actual.fueraDeAlcance() || anterior.fueraDeAlcance()) {
			return actual;
		}
		return new Interpretacion(
				actual.intencion(),
				actual.usaPersona() && actual.persona() == null ? anterior.persona() : actual.persona(),
				actual.usaClase() && actual.clase() == null ? anterior.clase() : actual.clase(),
				actual.periodo() == null ? anterior.periodo() : actual.periodo(),
				actual.usaReporte() && actual.reporte() == null ? anterior.reporte() : actual.reporte());
	}

	private static Intencion intencion(String valor) {
		if (valor == null) {
			return Intencion.FUERA_DE_ALCANCE;
		}
		try {
			Intencion intencion = Intencion.valueOf(valor.strip().toUpperCase(Locale.ROOT));
			return ELEGIBLES.contains(intencion) ? intencion : Intencion.FUERA_DE_ALCANCE;
		}
		catch (IllegalArgumentException e) {
			return Intencion.FUERA_DE_ALCANCE;
		}
	}

	private static String reporte(String valor) {
		if (valor == null) {
			return null;
		}
		String normal = ResolutorDePeriodo.sinTildes(valor.strip()).toUpperCase(Locale.ROOT);
		return REPORTES.contains(normal) ? normal : null;
	}

	/** Sin titulos adelante ("el senor Rodriguez" es "Rodriguez"); respeta como lo escribio el usuario. */
	static String persona(String valor) {
		if (valor == null) {
			return null;
		}
		List<String> palabras = new ArrayList<>(List.of(valor.strip().split("\\s+")));
		while (!palabras.isEmpty() && TITULOS.contains(
				ResolutorDePeriodo.sinTildes(palabras.getFirst()).toLowerCase(Locale.ROOT))) {
			palabras.removeFirst();
		}
		String limpio = String.join(" ", palabras).strip();
		return limpio.isEmpty() ? null : limpio;
	}

	static String clase(String valor) {
		if (valor == null) {
			return null;
		}
		String limpio = valor.strip().toLowerCase(Locale.ROOT).replaceFirst("^(la )?clase (de )?", "").strip();
		return limpio.isEmpty() ? null : limpio;
	}

	/** El texto del campo, o {@code null} si falta, es {@code null}, esta en blanco o dice "null". */
	private static String texto(JsonNode raiz, String campo) {
		JsonNode nodo = raiz.path(campo);
		if (nodo.isMissingNode() || nodo.isNull() || !nodo.isValueNode()) {
			return null;
		}
		String valor = nodo.asString("").strip();
		return valor.isEmpty() || valor.equalsIgnoreCase("null") ? null : valor;
	}
}
