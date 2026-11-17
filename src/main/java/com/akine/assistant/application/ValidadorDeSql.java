package com.akine.assistant.application;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decide si la consulta que escribio el modelo se puede ejecutar. Es la primera de tres barreras; las
 * otras dos son la transaccion READ ONLY del ejecutor, con el {@code search_path} en
 * {@code asistente_sql}, y las vistas, que se recortan solas a la organizacion y la sede.
 *
 * <p>Acepta solo: <b>una</b> sentencia que empiece con {@code SELECT} o {@code WITH}; sin palabras que
 * escriban, bloqueen o cambien la sesion; que lea de las vistas (o de sus propios {@code WITH}); sin
 * comillas dobles, dolares ni comentarios, que esconderian lo demas; y que llame solo a funciones de
 * la lista. La lista importa: {@code set_config} cambiaria la organizacion de las vistas y
 * {@code current_setting} o {@code pg_read_file} leerian lo que no deben, y ninguna escribe, asi que
 * READ ONLY no las para.
 *
 * <p>Sin parser de SQL a proposito: con JSqlParser en el classpath, Spring Data JPA 4 lo usa para
 * todas las consultas nativas del proyecto y no arranca (docs/diseno/AKINE-asistente-sql.md, §6).
 */
public final class ValidadorDeSql {

	public static final String ESQUEMA = "asistente_sql";

	/** Las vistas sin datos clinicos de {@code V21} y {@code V23}. */
	public static final Set<String> VISTAS = Set.of("personas", "coberturas", "profesionales", "servicios", "turnos",
			"sesiones", "casos", "clases", "inscripciones", "asistencias_clase", "deudas", "cobros",
			"movimientos_caja", "egresos", "financiadores",
			"sedes", "espacios", "jornadas_caja", "presentaciones", "presentacion_items", "pagos_financiador",
			"pagos_egreso", "liquidaciones_honorarios", "liquidaciones_honorarios_clase", "practicas", "convenios",
			"aranceles", "ordenes_medicas", "autorizaciones", "lista_espera", "recepciones", "productos", "pases",
			"feriados", "disponibilidad");

	/**
	 * Las vistas {@code clinico_*} de {@code V23}: diagnosticos, motivos, tratamientos, mediciones. Solo
	 * con {@code hc:read} sin recorte, y cada consulta que las lee se audita como lectura clinica.
	 */
	public static final Set<String> VISTAS_CLINICAS = Set.of("clinico_casos", "clinico_sesiones",
			"clinico_tratamientos", "clinico_tratamiento_parametros", "clinico_mediciones", "clinico_antecedentes",
			"clinico_planes", "clinico_derivaciones");

	static final Set<String> FUNCIONES = Set.of("count", "sum", "avg", "min", "max", "coalesce", "nullif",
			"greatest", "least", "round", "floor", "ceil", "abs", "lower", "upper", "trim", "concat", "concat_ws",
			"length", "substring", "left", "right", "replace", "initcap", "date_trunc", "date_part", "extract", "age",
			"now", "to_char", "make_date", "string_agg", "bool_or", "bool_and", "cast", "row_number", "rank",
			"dense_rank", "lag", "lead");

	/** Palabras que van seguidas de un parentesis sin ser funciones. */
	private static final Set<String> PALABRAS_ANTES_DE_PARENTESIS = Set.of("in", "exists", "any", "all", "over",
			"filter", "as", "and", "or", "not", "on", "from", "join", "select", "where", "using", "when", "then",
			"else", "case", "by", "having", "with", "union", "intersect", "except", "distinct", "is", "between",
			"interval", "values");

	private static final Pattern LITERAL = Pattern.compile("'(?:[^']|'')*'");
	private static final Pattern INICIO = Pattern.compile("^\\s*\\(*\\s*(select|with)\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern PROHIBIDO = Pattern.compile(
			"--|/\\*|\"|\\$|::\\s*reg|\\bpg_|information_schema|\\bpublic\\s*\\.|"
					+ "\\b(into|insert|update|delete|merge|truncate|alter|create|drop|grant|revoke|copy|call|do|"
					+ "execute|prepare|listen|notify|unlisten|set|reset|vacuum|analyze|lock|refresh|comment|"
					+ "security|declare|fetch|cursor)\\b|\\bfor\\s+(update|share|no\\s+key|key)\\b",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern LLAMADA = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(");
	private static final Pattern CTE = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s+as\\s*\\(",
			Pattern.CASE_INSENSITIVE);
	/** Lo que sigue a FROM o JOIN; si es un nombre seguido de ")" es el FROM de extract/substring. */
	private static final Pattern ORIGEN = Pattern.compile(
			"\\b(from|join)\\s+([a-zA-Z_][a-zA-Z0-9_]*)(\\s*\\.\\s*([a-zA-Z_][a-zA-Z0-9_]*))?(\\s*\\))?",
			Pattern.CASE_INSENSITIVE);

	private ValidadorDeSql() {
	}

	/**
	 * @return el motivo del rechazo, o vacio si la consulta se puede ejecutar
	 */
	public static Optional<String> rechazo(String sql) {
		return rechazo(sql, false);
	}

	/**
	 * @param clinicas si el actor puede leer las vistas {@code clinico_*} (asistente:sql y hc:read sin
	 *                 recorte); sin eso, una consulta que las nombra se rechaza
	 * @return el motivo del rechazo, o vacio si la consulta se puede ejecutar
	 */
	public static Optional<String> rechazo(String sql, boolean clinicas) {
		if (sql == null || sql.isBlank()) {
			return Optional.of("La consulta esta vacia");
		}
		String limpio = sql.strip();
		if (limpio.endsWith(";")) {
			limpio = limpio.substring(0, limpio.length() - 1).strip();
		}
		String texto = LITERAL.matcher(limpio).replaceAll("0");
		if (texto.contains("'")) {
			return Optional.of("Hay una comilla sin cerrar");
		}
		if (texto.contains(";")) {
			return Optional.of("Una sola sentencia");
		}
		if (!INICIO.matcher(texto).find()) {
			return Optional.of("Solo se permite SELECT");
		}
		Matcher prohibido = PROHIBIDO.matcher(texto);
		if (prohibido.find()) {
			return Optional.of("La consulta usa algo que no esta permitido: " + prohibido.group().strip());
		}

		Set<String> propias = new HashSet<>();
		Matcher cte = CTE.matcher(texto);
		while (cte.find()) {
			propias.add(cte.group(1).toLowerCase(Locale.ROOT));
		}

		Matcher origen = ORIGEN.matcher(texto);
		while (origen.find()) {
			if (origen.group(5) != null && origen.group(3) == null) {
				continue;
			}
			String primero = origen.group(2).toLowerCase(Locale.ROOT);
			String nombre = primero;
			if (origen.group(4) != null) {
				if (!primero.equals(ESQUEMA)) {
					return Optional.of("Solo las vistas del asistente: " + origen.group(2) + "." + origen.group(4));
				}
				nombre = origen.group(4).toLowerCase(Locale.ROOT);
			}
			if (VISTAS_CLINICAS.contains(nombre) && !propias.contains(nombre)) {
				if (!clinicas) {
					return Optional.of("Esa vista tiene datos clinicos y hace falta hc:read: " + nombre);
				}
			}
			else if (!VISTAS.contains(nombre) && !propias.contains(nombre)) {
				return Optional.of("Esa tabla no existe para el asistente: " + nombre);
			}
		}

		Matcher llamada = LLAMADA.matcher(texto);
		while (llamada.find()) {
			String nombre = llamada.group(1).toLowerCase(Locale.ROOT);
			if (!FUNCIONES.contains(nombre) && !PALABRAS_ANTES_DE_PARENTESIS.contains(nombre)
					&& !propias.contains(nombre)) {
				return Optional.of("Funcion no permitida: " + nombre);
			}
		}
		return Optional.empty();
	}

	/** Las vistas {@code clinico_*} que nombra una consulta ya validada: si hay alguna, la lectura se audita como clinica. */
	public static Set<String> vistasClinicas(String sql) {
		Set<String> encontradas = new java.util.TreeSet<>();
		Matcher origen = ORIGEN.matcher(LITERAL.matcher(sql).replaceAll("0"));
		while (origen.find()) {
			String nombre = (origen.group(4) != null ? origen.group(4) : origen.group(2)).toLowerCase(Locale.ROOT);
			if (VISTAS_CLINICAS.contains(nombre)) {
				encontradas.add(nombre);
			}
		}
		return encontradas;
	}
}
