package com.akine.knowledge.application;

import com.akine.knowledge.application.Interpretacion.Aclarar;
import com.akine.knowledge.application.Interpretacion.Buscar;
import com.akine.knowledge.domain.port.MensajeChat;
import com.akine.knowledge.domain.port.MensajeChat.Rol;
import com.akine.knowledge.domain.port.ModeloDeLenguaje;
import com.akine.knowledge.domain.port.OpcionesGeneracion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interpreta la consulta antes de buscar (diseño consulta v2, §4.1, §4.2, §7.3, §7.5 y §7.8).
 *
 * <ul>
 * <li>Sin historial, una consulta que es solo un aspecto ("ejercicios", "dosis") pide la afeccion, y
 * una con 1 o 2 palabras de contenido y sin intencion repregunta con los aspectos que tienen evidencia. Ninguna de
 * las dos llama al modelo.</li>
 * <li>Con intencion o con historial, una llamada al modelo en modo JSON devuelve tema, tema nuevo,
 * intencion y pregunta completa. Reemplaza a la reescritura de v1.</li>
 * <li>Modelo caido, JSON invalido, plazo vencido o un tema que ni la consulta ni la charla nombran:
 * respaldo de v1.</li>
 * </ul>
 *
 * No guarda ni registra la consulta, el historial ni lo que devuelve el modelo.
 */
public class Interpretador {

	private static final Logger log = LoggerFactory.getLogger(Interpretador.class);

	public static final double CORTE_VECTOR_POR_DEFECTO = 0.818;
	public static final String TEXTO_SIN_AFECCION = "¿Para qué afección o tema?";
	static final String TEXTO_REPREGUNTA = "¿Qué buscás sobre %s?";
	static final OpcionesGeneracion OPCIONES = new OpcionesGeneracion(BigDecimal.ZERO, 160, true);
	static final Duration PLAZO = Duration.ofSeconds(10);
	/** Hasta cuantas palabras puede tener una consulta que es solo un aspecto ("evaluación de"). */
	static final int PALABRAS_AMBIGUA = 3;
	/** Sin intencion, una consulta con hasta tantas palabras de contenido es ambigua (ruling R6). */
	static final int CONTENIDO_AMBIGUA = 2;

	/**
	 * Intencion, sin tildes ni mayusculas. Los interrogativos y "mas" van como palabra entera para que
	 * "quebradura" o "masaje" no cuenten; el resto, como comienzo de palabra ("tratamiento", "evaluar").
	 */
	private static final Pattern INTENCION = Pattern.compile(
			"\\b(?:(?:que|como|cuales?|quien(?:es)?|mas)\\b"
					+ "|trat|interv|ejerc|eval|defin|rehab|profesion|dosis|tiempo|detalle|explic|profundiz)");
	/** Pide profundizar lo anterior; solo cuenta con historial. */
	private static final Pattern PROFUNDO = Pattern.compile(
			"\\b(?:mas\\b|detalle|profundiz|explic|quien|cuant|como se hace)");
	/** Palabras que por si solas son un aspecto o una dosis, no una afeccion. Se aplica a una palabra. */
	private static final Pattern ASPECTO = Pattern.compile(
			"^(?:ejerc|dosis|eval|trat|defin|profesion|interv|rehab|tiempo|detalle|explic|profundiz|repetic|frecuen"
					+ "|minut|vez$|veces$|semanas?$|dias?$|horas?$|series?$|duracion$)");
	private static final Pattern SIGLA = Pattern.compile("\\b\\p{Lu}{2,6}\\b");
	private static final Pattern ESPACIOS = Pattern.compile("\\s+");
	/** Hasta cuantas letras puede tener una palabra de la consulta para leerse como sigla ("acv", "epoc"). */
	private static final int SIGLA_MAXIMA = 5;
	/** Palabras que pueden ir dentro del desarrollo de una sigla sin aportar letra. */
	private static final Set<String> CONECTORES = Set.of("de", "del", "la", "el", "los", "las", "y", "e");
	private static final Pattern NO_PALABRA = Pattern.compile("[^\\p{L}\\p{N}]+");
	private static final Pattern BORDES = Pattern.compile("^[\\p{Punct}¿¡\\s]+|[\\p{Punct}¿¡\\s]+$");
	private static final Set<String> VACIAS = Set.of(
			"a", "al", "de", "del", "el", "la", "las", "lo", "los", "le", "les", "un", "una", "unos", "unas",
			"y", "e", "o", "u", "en", "con", "por", "para", "sin", "sobre", "que", "es", "son", "se", "su", "sus",
			"cual", "cuales", "como", "mas", "muy", "me", "mi", "esto", "esta", "este", "estos",
			"esas", "esos", "ella", "ello", "otro", "otra", "otros", "otras", "tambien", "hace", "hacen",
			"hacer", "sirve", "sirven", "dura", "duran", "usa", "usar", "puede", "pueden", "debe", "deben",
			"anterior", "ahora", "cosa", "cosas", "hay", "tiene", "tienen", "quien", "quienes", "cuanto",
			"cuanta", "cuantos", "cuantas", "donde", "cuando", "algo", "todo", "todos", "eso", "aca", "ahi",
			"cada", "hago", "haga", "hacerlo", "hacerla", "quiero", "saber", "conviene", "mejor", "puedo",
			"vale", "bien", "igual", "entonces", "asi", "ya", "si", "no");
	/** Relleno que no nombra un tema: personas, sesion, materiales y verbos comunes de la consulta. */
	private static final Pattern RELLENO = Pattern.compile(
			"^(?:sesion(?:es)?$|material(?:es)?$|necesit|pacientes?$|trabaj|ninos?$|ninas?$|adultos?$"
					+ "|mayores$|personas?$|casos?$|tipos?$|principal(?:es)?$|tras$|indica|recomiend|utiliz"
					+ "|realiz|aplica$|aplican$|sirv|usad|empez|comenz|hacen?$)");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private enum Aspecto {
		DEFINICION(Intencion.DEFINICION, "qué es", "¿Qué es %s?"),
		EVALUACION(Intencion.EVALUACION, "evaluación de", "Evaluación de %s"),
		TRATAMIENTO(Intencion.TRATAMIENTO, "intervenciones de rehabilitación para",
				"Intervenciones de rehabilitación para %s"),
		EJERCICIOS(Intencion.EJERCICIOS, "ejercicios para", "Ejercicios para %s"),
		PROFESIONALES(Intencion.PROFESIONALES, "profesionales que intervienen en",
				"Profesionales que intervienen en %s");

		final Intencion intencion;
		final String texto;
		final String etiqueta;

		Aspecto(Intencion intencion, String texto, String etiqueta) {
			this.intencion = intencion;
			this.texto = texto;
			this.etiqueta = etiqueta;
		}

		static Aspecto de(Intencion intencion) {
			for (Aspecto a : values()) {
				if (a.intencion == intencion) {
					return a;
				}
			}
			return null;
		}
	}

	/** Lo que devolvio el modelo, ya validado en forma. */
	private record Leida(String tema, boolean temaNuevo, Intencion intencion, String preguntaCompleta) {
	}

	private final Supplier<ModeloDeLenguaje> modelo;
	private final SondaDeEvidencia sonda;
	private final double corteVector;
	private final String prompt;

	public Interpretador(Supplier<ModeloDeLenguaje> modelo, SondaDeEvidencia sonda, double corteVector) {
		this.modelo = modelo;
		this.sonda = sonda;
		this.corteVector = corteVector;
		this.prompt = ConsultaService.recurso("knowledge/prompt-interpretacion.txt");
	}

	/** {@code limite} es el fin del plazo de la consulta; vencido, no se llama al modelo. */
	public Interpretacion interpretar(long orgId, String pregunta, List<TurnoDeConsulta> historial, Instant limite) {
		String consulta = pregunta.strip();
		String normal = normalizar(consulta);
		List<String> palabras = palabras(normal);
		boolean conIntencion = INTENCION.matcher(normal).find();
		long deContenido = palabras.stream().filter(Interpretador::esDeContenido).count();
		boolean ambigua = deContenido >= 1 && deContenido <= CONTENIDO_AMBIGUA && !conIntencion;

		if (!historial.isEmpty() && ambigua && esAspectoSuelto(palabras(normalizar(ultimaPregunta(historial))))) {
			// Respuesta tecleada a "¿Para qué afección o tema?": el aspecto de antes y el tema de ahora, sin charla.
			return interpretar(orgId, ultimaPregunta(historial).strip() + " " + consulta, List.of(), limite);
		}
		if (historial.isEmpty()) {
			// Antes que la intencion: "ejercicios" matchea "ejerc" y nunca seria ambigua.
			if (esAspectoSuelto(palabras)) {
				return new Aclarar(TEXTO_SIN_AFECCION, List.of());
			}
			if (ambigua) {
				return aclararOBuscar(orgId, sinBordes(consulta), consulta);
			}
		}
		return conModelo(orgId, consulta, normal, historial, limite, ambigua);
	}

	private Interpretacion conModelo(long orgId, String consulta, String normal, List<TurnoDeConsulta> historial,
			Instant limite, boolean ambigua) {
		Duration restante = Duration.between(Instant.now(), limite);
		Duration plazo = restante.compareTo(PLAZO) < 0 ? restante : PLAZO;
		if (plazo.isNegative() || plazo.isZero()) {
			log.info("Interpretacion sin modelo: plazo vencido");
			return respaldo(consulta, historial);
		}
		Leida leida;
		try {
			leida = leer(modelo.get().completar(mensajes(consulta, historial), OPCIONES, plazo));
		}
		catch (RuntimeException e) {
			log.info("Interpretacion fallida, se usa el respaldo: {}", e.getClass().getSimpleName());
			return respaldo(consulta, historial);
		}
		Set<String> enCharla = palabrasDeLaCharla(historial);
		Set<String> enConsulta = Set.copyOf(palabras(normal));
		Set<String> expandidas = leida == null ? Set.of()
				: expansiones(enConsulta, enCharla, leida.tema(), leida.preguntaCompleta());
		Set<String> fuente = new HashSet<>(enCharla);
		fuente.addAll(enConsulta);
		fuente.addAll(expandidas);
		if (leida == null || inventaTema(leida, fuente)) {
			log.info("Interpretacion descartada, se usa el respaldo");
			return respaldo(consulta, historial);
		}
		boolean profundo = !historial.isEmpty() && PROFUNDO.matcher(normal).find();
		// Un seguimiento ("¿quién lo hace y cuánto dura cada sesión?") hereda: decide el modelo, no las palabras.
		List<String> nuevas = profundo ? List.of() : nuevasRespectoDe(normal, enCharla);
		boolean nombraOtroTema = !historial.isEmpty() && !nuevas.isEmpty();
		Set<String> enTema = Set.copyOf(palabras(normalizar(leida.tema())));
		if (nombraOtroTema && nuevas.stream().noneMatch(p -> estaEn(p, enTema))) {
			// La consulta nombra un tema nuevo y el modelo lo cambio por el de la charla.
			return respaldo(consulta, historial);
		}
		// Con palabras de tema propias es independiente aunque la charla ya las nombrara (R15).
		boolean independiente = leida.temaNuevo() || historial.isEmpty() || nombraOtroTema
				|| !profundo && !palabrasDeTema(normal).isEmpty()
				|| !contenido(normalizar(leida.tema()), enCharla);
		String tema = leida.tema();
		String completa = leida.preguntaCompleta();
		if (independiente) {
			// Ni el tema ni la pregunta completa suman temas que la consulta no nombra (ni los de la charla),
			// salvo el desarrollo de una sigla de la consulta (§4.1).
			Set<String> admitidas = new HashSet<>(enConsulta);
			admitidas.addAll(expandidas);
			tema = recortarTema(tema, consulta, admitidas);
			if (!contenido(normalizar(completa), admitidas)) {
				completa = consulta;
			}
		}
		if (independiente && !historial.isEmpty() && ambigua) {
			return aclararOBuscar(orgId, tema, completa);
		}
		return new Buscar(tema, leida.intencion(), completa,
				consultas(completa, leida.intencion(), tema), independiente, profundo);
	}

	/**
	 * El tema con solo sus palabras de tema admitidas. Las vacias y los aspectos que unian una palabra
	 * quitada se van con ella: "manguito rotador y lumbalgia" queda "manguito rotador". Si no queda
	 * ninguna, las palabras de tema de la consulta.
	 */
	private static String recortarTema(String tema, String consulta, Set<String> admitidas) {
		List<String> quedan = new ArrayList<>();
		boolean trasQuitada = false;
		for (String token : ESPACIOS.split(tema.strip())) {
			List<String> deTema = palabrasDeTema(normalizar(token));
			if (deTema.isEmpty()) {
				if (!trasQuitada) {
					quedan.add(token);
				}
			}
			else if (deTema.stream().allMatch(p -> estaEn(p, admitidas))) {
				quedan.add(token);
				trasQuitada = false;
			}
			else {
				sinNeutrosAlFinal(quedan);
				trasQuitada = true;
			}
		}
		sinNeutrosAlFinal(quedan);
		while (!quedan.isEmpty() && palabrasDeTema(normalizar(quedan.get(0))).isEmpty()) {
			quedan.remove(0);
		}
		if (quedan.isEmpty()) {
			for (String token : ESPACIOS.split(consulta.strip())) {
				if (!palabrasDeTema(normalizar(token)).isEmpty()) {
					quedan.add(token);
				}
			}
		}
		return sinBordes(String.join(" ", quedan));
	}

	private static void sinNeutrosAlFinal(List<String> tokens) {
		while (!tokens.isEmpty() && palabrasDeTema(normalizar(tokens.get(tokens.size() - 1))).isEmpty()) {
			tokens.remove(tokens.size() - 1);
		}
	}

	/**
	 * Palabras que desarrollan una sigla de la consulta en el tema o la pregunta completa del modelo: una
	 * corrida de dos o mas palabras cuyas iniciales deletrean la sigla; la ultima puede aportar tambien,
	 * en orden, las letras que faltan ("accidente cerebrovascular" es "acv"). Una corrida con una palabra
	 * de la charla que la consulta no nombra no cuenta: la fuga de §7.8 sale de la charla.
	 */
	private static Set<String> expansiones(Set<String> enConsulta, Set<String> enCharla, String... textos) {
		Set<String> expandidas = new HashSet<>();
		for (String sigla : enConsulta) {
			if (sigla.length() < 2 || sigla.length() > SIGLA_MAXIMA
					|| !sigla.chars().allMatch(Character::isLetter) || palabrasDeTema(sigla).isEmpty()) {
				continue;
			}
			for (String texto : textos) {
				List<String> ws = palabras(normalizar(texto));
				for (int i = 0; i < ws.size(); i++) {
					List<String> corrida = new ArrayList<>();
					if (deletrea(sigla, 0, ws, i, corrida) && corrida.size() >= 2
							&& corrida.stream().noneMatch(p -> enCharla.contains(p) && !enConsulta.contains(p))) {
						expandidas.addAll(corrida);
					}
				}
			}
		}
		return expandidas;
	}

	/** {@code ws[wi..]} deletrea {@code sigla[si..]}; deja en {@code corrida} las palabras que aportan. */
	private static boolean deletrea(String sigla, int si, List<String> ws, int wi, List<String> corrida) {
		if (wi >= ws.size()) {
			return false;
		}
		String w = ws.get(wi);
		if (si > 0 && CONECTORES.contains(w)) {
			return deletrea(sigla, si, ws, wi + 1, corrida);
		}
		if (w.charAt(0) != sigla.charAt(si)) {
			return false;
		}
		corrida.add(w);
		if (si + 1 == sigla.length() || deletrea(sigla, si + 1, ws, wi + 1, corrida)
				|| esSubsecuencia(sigla.substring(si + 1), w.substring(1))) {
			return true;
		}
		corrida.remove(corrida.size() - 1);
		return false;
	}

	private static boolean esSubsecuencia(String letras, String palabra) {
		int desde = 0;
		for (char c : letras.toCharArray()) {
			desde = palabra.indexOf(c, desde) + 1;
			if (desde == 0) {
				return false;
			}
		}
		return true;
	}

	/** Repregunta con los aspectos que tienen evidencia; sin ninguno, busqueda normal. */
	private Interpretacion aclararOBuscar(long orgId, String tema, String pregunta) {
		List<OpcionDeAclaracion> opciones = new ArrayList<>();
		for (Aspecto aspecto : Aspecto.values()) {
			if (puntaje(orgId, aspecto.texto + " " + tema) >= corteVector) {
				String etiqueta = aspecto.etiqueta.formatted(tema);
				opciones.add(new OpcionDeAclaracion(etiqueta, etiqueta));
			}
		}
		if (opciones.isEmpty()) {
			return new Buscar(tema, Intencion.OTRA, pregunta, List.of(pregunta), true, false);
		}
		return new Aclarar(TEXTO_REPREGUNTA.formatted(tema), opciones);
	}

	private double puntaje(long orgId, String consulta) {
		try {
			return sonda.mejorPuntaje(orgId, consulta);
		}
		catch (RuntimeException e) {
			log.info("Sonda de evidencia fallida: {}", e.getClass().getSimpleName());
			return Double.NEGATIVE_INFINITY;
		}
	}

	/**
	 * Respaldo de v1. Sin historial, o si la consulta nombra su propio tema (R15), la
	 * pregunta tal cual e independiente; si no, la ultima pregunta de la charla mas la nueva.
	 */
	private static Buscar respaldo(String consulta, List<TurnoDeConsulta> historial) {
		String normal = normalizar(consulta);
		boolean profundo = PROFUNDO.matcher(normal).find();
		if (historial.isEmpty() || !profundo && !palabrasDeTema(normal).isEmpty()) {
			return new Buscar(consulta, Intencion.OTRA, consulta, List.of(consulta), true, false);
		}
		String junta = respaldoConcatenado(historial, consulta);
		return new Buscar(consulta, Intencion.OTRA, junta, List.of(junta), false, false);
	}

	/** Ultima pregunta del historial, un espacio y la nueva; si pasa de 600, se conserva el final. */
	static String respaldoConcatenado(List<TurnoDeConsulta> historial, String pregunta) {
		String anterior = ultimaPregunta(historial);
		String junta = anterior.isBlank() ? pregunta : anterior.strip() + " " + pregunta;
		return ConsultaService.ultimos(junta, ConsultaService.PREGUNTA_MAXIMA);
	}

	/** Solo las preguntas del usuario: el tema de la charla sale de ahi, y el prompt queda corto. */
	private List<MensajeChat> mensajes(String consulta, List<TurnoDeConsulta> historial) {
		StringBuilder usuario = new StringBuilder("Charla previa:");
		List<String> preguntas = preguntasDe(historial);
		if (preguntas.isEmpty()) {
			usuario.append(" ninguna");
		}
		for (String anterior : preguntas) {
			usuario.append("\n- ").append(anterior.strip());
		}
		usuario.append("\nConsulta: ").append(consulta);
		return List.of(new MensajeChat(Rol.SISTEMA, prompt), new MensajeChat(Rol.USUARIO, usuario.toString()));
	}

	/** La ultima pregunta del usuario en el historial, o {@code ""}. */
	private static String ultimaPregunta(List<TurnoDeConsulta> historial) {
		for (int i = historial.size() - 1; i >= 0; i--) {
			if (historial.get(i).rol() == TurnoDeConsulta.Rol.USUARIO) {
				return historial.get(i).texto();
			}
		}
		return "";
	}

	private static Set<String> palabrasDeLaCharla(List<TurnoDeConsulta> historial) {
		return Set.copyOf(palabras(normalizar(String.join(" ", preguntasDe(historial)))));
	}

	private static List<String> preguntasDe(List<TurnoDeConsulta> historial) {
		return historial.stream()
				.filter(t -> t.rol() == TurnoDeConsulta.Rol.USUARIO)
				.map(TurnoDeConsulta::texto)
				.toList();
	}

	/** {@code null} si no es un objeto JSON con tema y pregunta completa. */
	private static Leida leer(String respuesta) {
		if (respuesta == null) {
			return null;
		}
		int desde = respuesta.indexOf('{');
		int hasta = respuesta.lastIndexOf('}');
		if (desde < 0 || hasta <= desde) {
			return null;
		}
		JsonNode nodo;
		try {
			nodo = JSON.readTree(respuesta.substring(desde, hasta + 1));
		}
		catch (JacksonException e) {
			return null;
		}
		if (!nodo.isObject()) {
			return null;
		}
		String tema = sinBordes(nodo.path("tema").asString("").strip());
		String completa = nodo.path("pregunta_completa").asString("").strip();
		if (tema.isEmpty() || completa.isEmpty()) {
			return null;
		}
		return new Leida(tema, nodo.path("tema_nuevo").asBoolean(false),
				intencion(nodo.path("intencion").asString("")),
				ConsultaService.primeros(completa, ConsultaService.PREGUNTA_MAXIMA));
	}

	private static Intencion intencion(String texto) {
		String n = normalizar(texto).strip();
		if (n.startsWith("defin")) {
			return Intencion.DEFINICION;
		}
		if (n.startsWith("eval")) {
			return Intencion.EVALUACION;
		}
		if (n.startsWith("trat") || n.startsWith("interv")) {
			return Intencion.TRATAMIENTO;
		}
		if (n.startsWith("ejerc")) {
			return Intencion.EJERCICIOS;
		}
		if (n.startsWith("profesion")) {
			return Intencion.PROFESIONALES;
		}
		return Intencion.OTRA;
	}

	/**
	 * El modelo agrego un tema: alguna palabra del tema, o alguna sigla de la pregunta completa, que
	 * ni la consulta ni la charla nombran.
	 */
	private static boolean inventaTema(Leida leida, Set<String> fuente) {
		if (!contenido(normalizar(leida.tema()), fuente)) {
			return true;
		}
		Matcher siglas = SIGLA.matcher(leida.preguntaCompleta());
		while (siglas.find()) {
			if (!fuente.contains(normalizar(siglas.group()))) {
				return true;
			}
		}
		return false;
	}

	/** Cada palabra de tema de {@code texto} esta, como palabra, en {@code fuente}. */
	private static boolean contenido(String texto, Set<String> fuente) {
		return palabrasDeTema(texto).stream().allMatch(p -> estaEn(p, fuente));
	}

	/**
	 * La palabra esta en el conjunto, comparando palabras enteras y tolerando solo numero y genero al
	 * final: misma base despues de quitar -es, -as, -os, -s, -a u -o. "fractura" esta en
	 * "fracturas" y "rotador" en "rotadores"; "cadera" no esta en "cadena" ni "dolor" en "doloroso".
	 */
	private static boolean estaEn(String palabra, Set<String> palabras) {
		if (palabras.contains(palabra)) {
			return true;
		}
		String base = base(palabra);
		return palabras.stream().anyMatch(w -> base(w).equals(base));
	}

	private static final List<String> TERMINACIONES = List.of("es", "as", "os", "s", "a", "o");

	/** La palabra sin su terminacion de numero o genero, si queda una base de 3 letras o mas. */
	private static String base(String palabra) {
		for (String fin : TERMINACIONES) {
			if (palabra.endsWith(fin) && palabra.length() - fin.length() >= 3) {
				return palabra.substring(0, palabra.length() - fin.length());
			}
		}
		return palabra;
	}

	/** Palabras que pueden nombrar un tema: ni vacias, ni relleno, ni aspectos. */
	private static List<String> palabrasDeTema(String normal) {
		return palabras(normal).stream()
				.filter(p -> p.length() >= 3 && esDeContenido(p) && !ASPECTO.matcher(p).find())
				.toList();
	}

	private static boolean esDeContenido(String palabra) {
		return !VACIAS.contains(palabra) && !RELLENO.matcher(palabra).find();
	}

	/** Palabras de tema de la consulta que la charla no nombra. */
	private static List<String> nuevasRespectoDe(String normal, Set<String> charla) {
		return palabrasDeTema(normal).stream().filter(p -> !estaEn(p, charla)).toList();
	}

	/** Solo aspectos ("ejercicios", "evaluación de", "dosis"), sin afeccion. */
	private static boolean esAspectoSuelto(List<String> palabras) {
		if (palabras.isEmpty() || palabras.size() > PALABRAS_AMBIGUA) {
			return false;
		}
		List<String> llenas = palabras.stream().filter(p -> !VACIAS.contains(p)).toList();
		return llenas.size() <= 2 && llenas.stream().allMatch(p -> ASPECTO.matcher(p).find());
	}

	private static List<String> consultas(String preguntaCompleta, Intencion intencion, String tema) {
		Set<String> consultas = new LinkedHashSet<>();
		consultas.add(preguntaCompleta);
		Aspecto aspecto = Aspecto.de(intencion);
		if (aspecto != null) {
			consultas.add(aspecto.texto + " " + tema);
		}
		return List.copyOf(consultas);
	}

	/** Sin tildes ni mayusculas. */
	static String normalizar(String texto) {
		return Normalizer.normalize(texto, Normalizer.Form.NFD)
				.replaceAll("\\p{M}+", "")
				.toLowerCase(Locale.ROOT);
	}

	private static List<String> palabras(String normal) {
		return Arrays.stream(NO_PALABRA.split(normal)).filter(p -> !p.isEmpty()).toList();
	}

	private static String sinBordes(String texto) {
		return BORDES.matcher(texto).replaceAll("");
	}
}
