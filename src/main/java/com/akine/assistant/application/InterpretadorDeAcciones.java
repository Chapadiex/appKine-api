package com.akine.assistant.application;

import com.akine.assistant.domain.exception.ModeloNoDisponibleException;
import com.akine.assistant.spi.DatosDePersona;
import com.akine.platform.spi.modelo.MensajeChat;
import com.akine.platform.spi.modelo.MensajeChat.Rol;
import com.akine.platform.spi.modelo.ModeloDeLenguaje;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Convierte una frase que cuenta o pide algo ("vinieron todos menos María", "Juan pagó 15.000") en una
 * {@link AccionInterpretada}, con <b>una</b> llamada al modelo y un JSON schema
 * (docs/diseno/AKINE-asistente-acciones.md, §5.2). Tiene su propio prompt: el de las consultas no se
 * toca, porque cada cambio ahi movio preguntas ya medidas.
 *
 * <p>Nada de lo que devuelve se usa sin pasar por {@link ControlDeAccion}. Un JSON ilegible o una
 * accion que no es del catalogo terminan en {@link AccionInterpretada#NINGUNA}, nunca en una
 * excepcion. Sin historial: una frase, una accion.
 */
public class InterpretadorDeAcciones {

	private static final Logger log = LoggerFactory.getLogger(InterpretadorDeAcciones.class);

	/** Una atencion dictada con su nota es mas larga que una pregunta. */
	public static final int MAX_LARGO = 600;

	static final String ESQUEMA = """
			{"type":"object","properties":{\
			"accion":{"type":"string","enum":["ASISTENCIA_CLASE","PAGO","ATENCION","PACIENTE_ALTA","PACIENTE_EDICION","NINGUNA"]},\
			"persona":{"type":["string","null"]},\
			"clase":{"type":["string","null"]},\
			"modo":{"type":["string","null"],"enum":["TODOS_MENOS","SOLO",null]},\
			"nombres":{"type":"array","items":{"type":"string"}},\
			"monto":{"type":["string","null"]},\
			"practicas":{"type":"array","items":{"type":"string"}},\
			"nombre":{"type":["string","null"]},\
			"apellido":{"type":["string","null"]},\
			"telefono":{"type":["string","null"]},\
			"email":{"type":["string","null"]},\
			"documento":{"type":["string","null"]}},\
			"required":["accion","persona","clase","modo","nombres","monto","practicas","nombre","apellido","telefono","email","documento"]}""";

	private final Supplier<ModeloDeLenguaje> modelo;
	private final String prompt;
	private final Duration plazo;
	private final JsonMapper json = JsonMapper.builder().build();

	public InterpretadorDeAcciones(Supplier<ModeloDeLenguaje> modelo, String prompt, Duration plazo) {
		this.modelo = Objects.requireNonNull(modelo, "modelo");
		this.prompt = Objects.requireNonNull(prompt, "prompt");
		this.plazo = Objects.requireNonNull(plazo, "plazo");
	}

	/** Lo que el modelo entendio, <b>sin controlar</b>. */
	AccionInterpretada interpretar(String frase) {
		String texto = frase == null ? "" : frase.strip();
		if (texto.isEmpty() || texto.length() > MAX_LARGO) {
			return AccionInterpretada.NINGUNA;
		}
		String crudo;
		try {
			crudo = modelo.get().completarJson(
					List.of(new MensajeChat(Rol.SISTEMA, prompt), new MensajeChat(Rol.USUARIO, texto)), ESQUEMA, plazo);
		}
		catch (com.akine.platform.spi.modelo.ModeloNoDisponibleException e) {
			throw new ModeloNoDisponibleException(e);
		}
		return leer(crudo);
	}

	AccionInterpretada leer(String crudo) {
		JsonNode raiz;
		try {
			raiz = json.readTree(crudo == null ? "" : crudo);
		}
		catch (JacksonException e) {
			log.debug("El interpretador de acciones devolvio un JSON ilegible");
			return AccionInterpretada.NINGUNA;
		}
		if (raiz == null || !raiz.isObject()) {
			return AccionInterpretada.NINGUNA;
		}
		String accion = texto(raiz, "accion");
		accion = accion == null ? null : accion.toUpperCase(Locale.ROOT);
		if (accion == null || !AccionInterpretada.ACCIONES.contains(accion)) {
			return AccionInterpretada.NINGUNA;
		}
		String modo = texto(raiz, "modo");
		modo = modo == null ? null : modo.toUpperCase(Locale.ROOT);
		return new AccionInterpretada(accion,
				Interpretador.persona(texto(raiz, "persona")),
				Interpretador.clase(texto(raiz, "clase")),
				"TODOS_MENOS".equals(modo) || "SOLO".equals(modo) ? modo : null,
				lista(raiz, "nombres"),
				ControlDeAccion.importe(texto(raiz, "monto")),
				null,
				lista(raiz, "practicas"),
				new DatosDePersona(texto(raiz, "nombre"), texto(raiz, "apellido"), texto(raiz, "documento"),
						texto(raiz, "telefono"), texto(raiz, "email"), null));
	}

	private static List<String> lista(JsonNode raiz, String campo) {
		JsonNode nodo = raiz.path(campo);
		List<String> valores = new ArrayList<>();
		if (nodo.isArray()) {
			for (JsonNode item : nodo) {
				if (item.isValueNode() && !item.isNull() && !item.asString("").isBlank()) {
					valores.add(item.asString("").strip());
				}
			}
		}
		return valores;
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
