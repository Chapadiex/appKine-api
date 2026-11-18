package com.akine.assistant.application;

import com.akine.assistant.domain.exception.DictadoNoDisponibleException;
import com.akine.platform.spi.modelo.ModeloNoDisponibleException;
import com.akine.platform.spi.modelo.MensajeChat;
import com.akine.platform.spi.modelo.ModeloDeLenguaje;
import com.akine.platform.spi.modelo.OpcionesGeneracion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Reparte un texto dictado entre los campos de la evaluacion con el modelo local del asistente.
 *
 * <p>El modelo solo propone. Lo que no valida se descarta aca, sin error: un dolor fuera de 0 a
 * 10, una lateralidad o evolucion fuera del catalogo, un texto vacio. Lo que excede el largo de su
 * campo se corta, y el resto se suma a las notas para que no se pierda.
 *
 * <p>Las notas ({@code restante}) salen como una lista, una linea por item: {@code - Tema: texto}
 * ("- Realizo: tren delantero", "- Estado: perfecto"). Al final, {@link ControlDelDictado} busca las
 * frases del dictado que el modelo no puso en ningun lado y las suma como {@code - Dictado: ...}.
 *
 * <p>No registra el texto ni la respuesta del modelo.
 */
public class RepartidorDeEvaluacion {

	private static final Logger log = LoggerFactory.getLogger(RepartidorDeEvaluacion.class);

	/** Tope del texto dictado que se reparte. Unos cinco minutos de habla. */
	public static final int MAX_LARGO_TEXTO = 4000;

	/** Copia frases enteras: un texto de 4000 caracteres son unos 1200 tokens. */
	static final int MAX_TOKENS = 1500;
	/** Temperatura 0: repartir no es crear. Misma ventana que el asistente, para que Ollama no recargue. */
	private static final OpcionesGeneracion OPCIONES = new OpcionesGeneracion(BigDecimal.ZERO, MAX_TOKENS, true);
	static final int MAX_ZONA = 120;
	static final int MAX_TEXTO_CAMPO = 500;
	/** Un tema mas largo no es un rotulo: se descarta y queda el texto solo. */
	static final int MAX_TEMA = 30;
	static final String TEMA_SIN_UBICAR = "Dictado";
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
					new MensajeChat(MensajeChat.Rol.USUARIO, texto)), null, OPCIONES, plazo);
		}
		catch (ModeloNoDisponibleException e) {
			throw new DictadoNoDisponibleException(e);
		}
		return leer(crudo, texto);
	}

	PropuestaDeEvaluacion leer(String crudo, String dictado) {
		PropuestaDeEvaluacion propuesta = interpretar(crudo, dictado);
		List<String> perdidas = ControlDelDictado.sinUbicar(dictado, propuesta);
		if (perdidas.isEmpty()) {
			return propuesta;
		}
		List<String> items = new ArrayList<>();
		if (propuesta.restante() != null) {
			items.add(propuesta.restante());
		}
		perdidas.forEach(frase -> items.add(item(TEMA_SIN_UBICAR, frase)));
		return propuesta.conRestante(String.join("\n", items));
	}

	private PropuestaDeEvaluacion interpretar(String crudo, String dictado) {
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
		String zona = dicho(texto(raiz, "dolorZona", MAX_ZONA, sobrante), dictado);
		String objetivo = dicho(texto(raiz, "objetivoSesion", MAX_TEXTO_CAMPO, sobrante), dictado);
		String motivo = dicho(texto(raiz, "motivoClinico", MAX_TEXTO_CAMPO, sobrante), dictado);
		String limitacion = dicho(texto(raiz, "limitacionFuncional", MAX_TEXTO_CAMPO, sobrante), dictado);
		String restante = notas(raiz, sobrante.toString(), dictado);
		return new PropuestaDeEvaluacion(
				ControlDelDictado.mencionaNumero(dictado) ? dolor(raiz.get("dolorEva")) : null,
				zona,
				ControlDelDictado.mencionaLado(dictado) ? catalogo(raiz, "dolorLateralidad", LATERALIDADES) : null,
				ControlDelDictado.mencionaEvolucion(dictado) ? catalogo(raiz, "evolucion", EVOLUCIONES) : null,
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

	/**
	 * Las notas como lista. Acepta {@code "notas": [{"tema","texto"}]} y tambien un {@code restante}
	 * en texto plano, por si el modelo no respeta la forma. Lo cortado de otros campos va al final.
	 */
	private static String notas(JsonNode raiz, String cortado, String dictado) {
		List<String> items = new ArrayList<>();
		JsonNode notas = raiz.get("notas");
		if (notas != null && notas.isArray()) {
			for (JsonNode nota : notas) {
				String texto = nota.isString() ? nota.asString().strip() : textoDe(nota.get("texto"));
				if (texto == null || texto.isEmpty() || !ControlDelDictado.estaDicho(texto, dictado)) {
					continue;
				}
				items.add(item(nota.isObject() ? textoDe(nota.get("tema")) : null, texto));
			}
		}
		String plano = textoDe(raiz.get("restante"));
		if (plano != null && !plano.isEmpty() && ControlDelDictado.estaDicho(plano, dictado)) {
			items.add(item(null, plano));
		}
		if (!cortado.isBlank()) {
			items.add(item(null, cortado));
		}
		return items.isEmpty() ? null : String.join("\n", items);
	}

	/** {@code - Tema: texto}, o {@code - texto} sin tema. El tema va con mayuscula inicial. */
	static String item(String tema, String texto) {
		String limpio = texto.strip();
		String rotulo = tema == null ? "" : tema.strip().replaceAll(":+$", "").strip();
		if (rotulo.isEmpty() || rotulo.length() > MAX_TEMA) {
			return "- " + limpio;
		}
		return "- " + Character.toUpperCase(rotulo.charAt(0)) + rotulo.substring(1) + ": " + limpio;
	}

	/** El valor si sale del dictado; lo inventado (o un relleno como NO_APLICA) se descarta. */
	private static String dicho(String valor, String dictado) {
		return ControlDelDictado.estaDicho(valor, dictado) ? valor : null;
	}

	private static String textoDe(JsonNode nodo) {
		return nodo != null && nodo.isString() ? nodo.asString().strip() : null;
	}
}
