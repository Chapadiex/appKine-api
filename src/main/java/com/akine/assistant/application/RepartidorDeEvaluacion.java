package com.akine.assistant.application;

import com.akine.assistant.domain.exception.DictadoNoDisponibleException;
import com.akine.assistant.domain.exception.ModeloNoDisponibleException;
import com.akine.assistant.domain.port.MensajeChat;
import com.akine.assistant.domain.port.ModeloDeLenguaje;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Reparte un texto dictado entre los campos de la evaluacion con el modelo local del asistente.
 *
 * <p>El modelo solo propone. Lo que no valida se descarta aca, sin error: un dolor fuera de 0 a
 * 10, una lateralidad o evolucion fuera del catalogo, un texto vacio. Lo que excede el largo de su
 * campo se corta, y el resto se suma a {@code restante} para que no se pierda.
 *
 * <p>No registra el texto ni la respuesta del modelo.
 */
public class RepartidorDeEvaluacion {

	private static final Logger log = LoggerFactory.getLogger(RepartidorDeEvaluacion.class);

	/** Tope del texto dictado que se reparte. Unos cinco minutos de habla. */
	public static final int MAX_LARGO_TEXTO = 4000;

	/** Copia frases enteras: un texto de 4000 caracteres son unos 1200 tokens. */
	static final int MAX_TOKENS = 1500;
	static final int MAX_ZONA = 120;
	static final int MAX_TEXTO_CAMPO = 500;
	static final Set<String> LATERALIDADES = Set.of("IZQUIERDA", "DERECHA", "BILATERAL", "NO_APLICA");
	static final Set<String> EVOLUCIONES = Set.of("MEJOR", "IGUAL", "PEOR", "SIN_REFERENCIA");

	private final Supplier<ModeloDeLenguaje> modelo;
	private final String prompt;
	private final Duration plazo;
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * @param modelo devuelve {@code null} si el modelo del asistente no esta prendido
	 */
	public RepartidorDeEvaluacion(Supplier<ModeloDeLenguaje> modelo, String prompt, Duration plazo) {
		this.modelo = modelo;
		this.prompt = prompt;
		this.plazo = plazo;
	}

	public PropuestaDeEvaluacion repartir(String texto) {
		ModeloDeLenguaje disponible = modelo.get();
		if (disponible == null) {
			throw new DictadoNoDisponibleException();
		}
		String crudo;
		try {
			crudo = disponible.completarJson(List.of(
					new MensajeChat(MensajeChat.Rol.SISTEMA, prompt),
					new MensajeChat(MensajeChat.Rol.USUARIO, texto)), plazo, MAX_TOKENS);
		}
		catch (ModeloNoDisponibleException e) {
			throw new DictadoNoDisponibleException(e);
		}
		return leer(crudo);
	}

	PropuestaDeEvaluacion leer(String crudo) {
		JsonNode raiz;
		try {
			raiz = json.readTree(crudo == null ? "" : crudo);
		}
		catch (JacksonException e) {
			log.warn("El modelo devolvio una propuesta de evaluacion ilegible");
			return PropuestaDeEvaluacion.VACIA;
		}
		if (raiz == null || !raiz.isObject()) {
			return PropuestaDeEvaluacion.VACIA;
		}
		StringBuilder sobrante = new StringBuilder();
		String zona = texto(raiz, "dolorZona", MAX_ZONA, sobrante);
		String objetivo = texto(raiz, "objetivoSesion", MAX_TEXTO_CAMPO, sobrante);
		String motivo = texto(raiz, "motivoClinico", MAX_TEXTO_CAMPO, sobrante);
		String limitacion = texto(raiz, "limitacionFuncional", MAX_TEXTO_CAMPO, sobrante);
		String restante = unir(texto(raiz, "restante", Integer.MAX_VALUE, sobrante), sobrante.toString());
		return new PropuestaDeEvaluacion(
				dolor(raiz.get("dolorEva")),
				zona,
				catalogo(raiz, "dolorLateralidad", LATERALIDADES),
				catalogo(raiz, "evolucion", EVOLUCIONES),
				objetivo,
				motivo,
				limitacion,
				restante);
	}

	/** Entero de 0 a 10. Acepta "7" o 7.0 porque los modelos chicos no siempre respetan el tipo. */
	static Integer dolor(JsonNode nodo) {
		if (nodo == null || nodo.isNull()) {
			return null;
		}
		double valor;
		if (nodo.isNumber()) {
			valor = nodo.asDouble();
		}
		else if (nodo.isString()) {
			try {
				valor = Double.parseDouble(nodo.asString().strip().replace(',', '.'));
			}
			catch (NumberFormatException e) {
				return null;
			}
		}
		else {
			return null;
		}
		if (valor < 0 || valor > 10 || valor != Math.rint(valor)) {
			return null;
		}
		return (int) valor;
	}

	/** Mayusculas y sin tildes: el modelo a veces devuelve "izquierda" o "Mejor". */
	static String catalogo(JsonNode raiz, String campo, Set<String> valores) {
		JsonNode nodo = raiz.get(campo);
		if (nodo == null || !nodo.isString()) {
			return null;
		}
		String normal = Normalizer.normalize(nodo.asString().strip(), Normalizer.Form.NFD)
				.replaceAll("\\p{M}", "")
				.toUpperCase(Locale.ROOT)
				.replace(' ', '_');
		return valores.contains(normal) ? normal : null;
	}

	/** El texto del campo, cortado en una palabra si excede su largo; lo cortado va a {@code sobrante}. */
	private static String texto(JsonNode raiz, String campo, int maximo, StringBuilder sobrante) {
		JsonNode nodo = raiz.get(campo);
		if (nodo == null || !nodo.isString()) {
			return null;
		}
		String valor = nodo.asString().strip();
		if (valor.isEmpty()) {
			return null;
		}
		if (valor.length() <= maximo) {
			return valor;
		}
		String corte = valor.substring(0, maximo);
		int espacio = corte.lastIndexOf(' ');
		if (espacio > maximo / 2) {
			corte = corte.substring(0, espacio);
		}
		if (!sobrante.isEmpty()) {
			sobrante.append(' ');
		}
		sobrante.append(valor.substring(corte.length()).strip());
		return corte.strip();
	}

	private static String unir(String a, String b) {
		boolean hayA = a != null && !a.isBlank();
		boolean hayB = b != null && !b.isBlank();
		if (hayA && hayB) {
			return a + " " + b;
		}
		return hayA ? a : (hayB ? b : null);
	}
}
