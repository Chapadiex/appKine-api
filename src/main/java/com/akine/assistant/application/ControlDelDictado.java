package com.akine.assistant.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Contrasta la propuesta del modelo con lo que se dicto, sin usar el modelo: compara palabras.
 *
 * <p>Los modelos chicos fallan para los dos lados, y los dos se midieron con qwen3:4b el 18/11/2026:
 * <ul>
 * <li><b>Se saltean</b> una parte del texto en vez de mandarla a las notas ("rodilla derecha post
 * operatoria de ligamento cruzado" quedaba como zona "rodilla" y nada mas). {@link #sinUbicar}
 * devuelve esas frases para sumarlas a las notas.</li>
 * <li><b>Inventan</b>: copian el ejemplo del prompt ("ganar flexion" como objetivo de un dictado que
 * no lo decia), ponen "NO_APLICA" en un campo de texto o una evolucion que nadie menciono.
 * {@link #estaDicho} y los controles de catalogo descartan lo que no sale del dictado.</li>
 * </ul>
 *
 * <p>Los dos son a proposito permisivos con el dato dicho: una frase de mas en las notas se borra con
 * una tecla; una de menos es un dato clinico perdido, y uno inventado es peor.
 */
final class ControlDelDictado {

	/** Frases: se corta en punto, punto y coma, dos puntos, coma y salto de linea. */
	private static final Pattern SEPARADOR = Pattern.compile("[.;:,\\n]+");
	private static final Pattern PALABRA = Pattern.compile("[a-z0-9]+");

	/** Palabras que no dicen nada por si solas en un dictado de kinesiologia. */
	private static final Set<String> VACIAS = Set.of(
			"paciente", "refiere", "comenta", "dice", "cuenta", "viene", "vino", "esta", "estaba", "este",
			"esto", "para", "como", "pero", "porque", "sobre", "desde", "hasta", "tiene", "tenia",
			"hace", "hizo", "tambien", "ademas", "muy", "bien", "mucho", "poco", "algo", "todo", "toda",
			"cada", "otra", "otro", "entonces", "luego", "despues", "ahora", "sesion", "objetivo",
			"motivo", "dolor", "zona", "nivel", "consulta", "trae", "dias", "cuando", "donde");

	private static final Set<String> DE_DOLOR = Set.of(
			"cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
			"eva", "escala");

	private static final Set<String> DE_LADO = Set.of(
			"izquierda", "izquierdo", "derecha", "derecho", "bilateral", "ambos", "ambas", "lados", "lado");

	private static final Set<String> DE_EVOLUCION = Set.of(
			"mejor", "peor", "igual", "mismo", "misma", "semana", "pasada", "pasado", "anterior", "ultima",
			"ultimo", "antes", "vez", "referencia");

	/** Lo que tiene que aparecer en el dictado para aceptar una evolucion. */
	private static final Set<String> DICHO_DE_EVOLUCION = Set.of(
			"mejor", "mejora", "mejoro", "peor", "empeoro", "igual", "mismo", "misma", "anterior", "pasada",
			"referencia", "primera");

	/** Lo que tiene que aparecer en el dictado para aceptar un lado. */
	private static final Set<String> DICHO_DE_LADO = Set.of(
			"izquierda", "izquierdo", "derecha", "derecho", "bilateral", "ambos", "ambas", "aplica");

	/** Valores que el modelo pone en un campo de texto cuando no tiene nada que poner. */
	private static final Set<String> RELLENOS = Set.of(
			"no aplica", "no_aplica", "n/a", "na", "ninguno", "ninguna", "null", "none", "-", "sin datos",
			"no especificado", "no se menciona", "no dicho");

	/** Prefijo comun que basta para considerar la misma palabra ("trabajamos" y "trabajar"). */
	private static final int RAIZ = 5;

	private ControlDelDictado() {
	}

	/**
	 * Si un texto propuesto sale del dictado: la mayoria de sus palabras con contenido aparecen en
	 * el. Un relleno ("NO_APLICA", "ninguno") nunca esta dicho.
	 */
	static boolean estaDicho(String valor, String dictado) {
		if (valor == null || RELLENOS.contains(valor.strip().toLowerCase(Locale.ROOT))) {
			return false;
		}
		List<String> conContenido = conContenido(valor);
		if (conContenido.isEmpty()) {
			// "core", "Eva": palabras cortas o vacias. Se exige que aparezcan tal cual.
			return !palabras(valor).isEmpty() && new HashSet<>(palabras(dictado)).containsAll(palabras(valor));
		}
		Set<String> delDictado = new HashSet<>(palabras(dictado));
		long dichas = conContenido.stream().filter(p -> cubierta(p, delDictado)).count();
		return dichas * 2 >= conContenido.size();
	}

	/** Si el dictado menciona la evolucion respecto de la sesion anterior. */
	static boolean mencionaEvolucion(String dictado) {
		return menciona(dictado, DICHO_DE_EVOLUCION);
	}

	/** Si el dictado menciona un lado del cuerpo. */
	static boolean mencionaLado(String dictado) {
		return menciona(dictado, DICHO_DE_LADO);
	}

	/** Si el dictado dice un numero, en cifras o en palabras. */
	static boolean mencionaNumero(String dictado) {
		return palabras(dictado).stream().anyMatch(p -> p.chars().allMatch(Character::isDigit) || DE_DOLOR.contains(p));
	}

	/**
	 * @param dictado el texto que se repartio
	 * @param propuesta lo que se repartio, incluidas las notas
	 * @return las frases del dictado, tal como se dijeron, que no quedaron en ningun campo
	 */
	static List<String> sinUbicar(String dictado, PropuestaDeEvaluacion propuesta) {
		Set<String> cubiertas = new HashSet<>();
		for (String campo : new String[] { propuesta.dolorZona(), propuesta.objetivoSesion(),
				propuesta.motivoClinico(), propuesta.limitacionFuncional(), propuesta.restante() }) {
			cubiertas.addAll(palabras(campo));
		}
		if (propuesta.dolorEva() != null) {
			cubiertas.addAll(DE_DOLOR);
		}
		if (propuesta.dolorLateralidad() != null) {
			cubiertas.addAll(DE_LADO);
		}
		if (propuesta.evolucion() != null) {
			cubiertas.addAll(DE_EVOLUCION);
		}
		List<String> perdidas = new ArrayList<>();
		for (String frase : SEPARADOR.split(dictado)) {
			List<String> conContenido = conContenido(frase);
			if (conContenido.isEmpty()) {
				continue;
			}
			long ubicadas = conContenido.stream().filter(p -> cubierta(p, cubiertas)).count();
			if (ubicadas * 2 < conContenido.size()) {
				perdidas.add(frase.strip());
			}
		}
		return perdidas;
	}

	private static boolean menciona(String dictado, Set<String> palabras) {
		return palabras(dictado).stream().anyMatch(palabras::contains);
	}

	private static List<String> conContenido(String texto) {
		return palabras(texto).stream().filter(p -> p.length() >= 4 && !VACIAS.contains(p)).toList();
	}

	private static boolean cubierta(String palabra, Set<String> cubiertas) {
		if (cubiertas.contains(palabra)) {
			return true;
		}
		if (palabra.length() < RAIZ) {
			return false;
		}
		String raiz = palabra.substring(0, RAIZ);
		return cubiertas.stream().anyMatch(c -> c.startsWith(raiz));
	}

	/** Minusculas, sin tildes, solo letras y numeros. */
	static List<String> palabras(String texto) {
		if (texto == null || texto.isBlank()) {
			return List.of();
		}
		String normal = Normalizer.normalize(texto, Normalizer.Form.NFD)
				.replaceAll("\\p{M}", "")
				.toLowerCase(Locale.ROOT);
		return PALABRA.matcher(normal).results().map(r -> r.group()).toList();
	}
}
