package com.akine.assistant.application;

import com.akine.assistant.spi.ClavesDeFila;
import com.akine.assistant.spi.Intencion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * El texto de cada respuesta, armado a partir de las filas. Determinista: el modelo no redacta
 * (diseno, §4.1), asi que el texto dice exactamente lo que dicen los datos y nada mas.
 *
 * <p>Regla de modelo que estas plantillas no pueden romper: <b>turno no es sesion</b>. "Vino" se
 * cuenta por el estado operativo del turno (presente, en atencion, atendido, retirado); "sesion
 * cargada" se dice solo de {@code ATENDIDO} (sesion cerrada) o {@code EN_ATENCION} (abierta). Y en
 * los numeros economicos <b>no se suman conceptos</b>: deuda, cobro y caja se listan separados,
 * como en el reporte.
 */
final class Plantillas {

	static final Locale ES_AR = Locale.forLanguageTag("es-AR");
	static final int MAX_EN_LISTA = 15;
	static final int MAX_CLASES = 3;

	static final Set<String> VINO = Set.of("PRESENTE", "EN_ATENCION", "ATENDIDO", "RETIRADO");
	static final Set<String> PENDIENTE = Set.of("RESERVADO", "CONFIRMADO", "PROGRAMADO");

	static final String AYUDA = "Todavía no sé responder eso. Puedo decirte si una persona vino o tiene "
			+ "la sesión cargada, quiénes vienen un día, quiénes fueron a una clase, y los números de "
			+ "facturación, turnos u ocupación de clases de un período.";
	static final String SIN_PERMISO = "No tenés permiso para ver eso.";
	static final String FALTA_PERSONA = "¿De qué persona? Escribí el nombre.";

	private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("H:mm");

	private Plantillas() {
	}

	static final String CONSULTA_FALLIDA = "No pude armar una consulta para eso. Probá con otras palabras.";

	/**
	 * Texto de una consulta libre. Un solo valor se dice ("pacientes: 22"); una fila con varias columnas
	 * se enumera; varias filas se cuentan y van a la tabla.
	 */
	static String consultaLibre(List<Map<String, Object>> filas, boolean truncado) {
		if (filas.isEmpty()) {
			return "No encontré datos para esa pregunta.";
		}
		if (filas.size() == 1) {
			Map<String, Object> fila = filas.getFirst();
			if (fila.size() == 1) {
				Map.Entry<String, Object> unico = fila.entrySet().iterator().next();
				return capitalizar(etiqueta(unico.getKey())) + ": " + formatear(unico.getValue()) + ".";
			}
			StringBuilder texto = new StringBuilder("Resultado:");
			fila.forEach((clave, valor) -> texto.append("\n· ").append(etiqueta(clave)).append(": ")
					.append(formatear(valor)));
			return texto.toString();
		}
		return "Encontré " + filas.size() + (truncado ? " resultados o más (se muestran los primeros)." : " resultados.")
				+ " Están en la tabla.";
	}

	private static String etiqueta(String columna) {
		return columna.replace('_', ' ');
	}

	private static String capitalizar(String texto) {
		return texto.isEmpty() ? texto : Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
	}

	private static String formatear(Object valor) {
		if (valor == null) {
			return "sin dato";
		}
		if (valor instanceof Boolean b) {
			return b ? "sí" : "no";
		}
		if (valor instanceof Number n) {
			java.text.NumberFormat formato = java.text.NumberFormat.getNumberInstance(ES_AR);
			formato.setMaximumFractionDigits(2);
			return formato.format(n);
		}
		return String.valueOf(valor);
	}

	static String personaNoEncontrada(String texto) {
		return "No encontré a \"" + texto + "\" en el padrón.";
	}

	static String variasPersonas(String texto) {
		return "Encontré varias personas con \"" + texto + "\". ¿Cuál?";
	}

	static String texto(Intencion intencion, String persona, String clase, String reporte, RangoDeDias rango,
			ZoneId zona, String sede, List<Map<String, Object>> filas, Instant ahora) {
		return switch (intencion) {
			case ASISTENCIA_PERSONA -> asistencia(persona, rango, zona, sede, filas, ahora);
			case SESION_DE_PERSONA -> sesiones(persona, rango, zona, sede, filas);
			case AGENDA_DEL_DIA -> agenda(rango, zona, sede, filas);
			case ASISTENTES_CLASE -> clases(clase, rango, zona, sede, filas);
			case REPORTE -> reporte(reporte, rango, sede, filas);
			case BUSCAR_PERSONA, FUERA_DE_ALCANCE -> AYUDA;
		};
	}

	// --- Turnos de una persona ---------------------------------------------------------------

	/**
	 * Un turno reservado que ya paso sin llegada registrada no es "pendiente": se informa aparte, y
	 * el asistente no afirma que la persona falto (eso lo dice solo {@code AUSENTE}).
	 */
	static String asistencia(String persona, RangoDeDias rango, ZoneId zona, String sede, List<Map<String, Object>> filas,
			Instant ahora) {
		if (filas.isEmpty()) {
			return persona + " no tiene turnos " + periodo(rango) + " en " + sede + ".";
		}
		List<Map<String, Object>> vino = conEstado(filas, VINO);
		List<Map<String, Object>> falto = conEstado(filas, Set.of("AUSENTE"));
		List<Map<String, Object>> cancelados = conEstado(filas, Set.of("CANCELADO"));
		List<Map<String, Object>> sinLlegada = conEstado(filas, PENDIENTE).stream()
				.filter(f -> !((Instant) f.get(ClavesDeFila.INICIO)).isAfter(ahora)).toList();
		List<Map<String, Object>> proximos = conEstado(filas, PENDIENTE).stream()
				.filter(f -> ((Instant) f.get(ClavesDeFila.INICIO)).isAfter(ahora)).toList();

		StringBuilder texto = new StringBuilder();
		boolean sinLlegadaEnElEncabezado = vino.isEmpty() && !sinLlegada.isEmpty();
		if (sinLlegadaEnElEncabezado) {
			texto.append(persona).append(" no tiene llegadas registradas ").append(periodo(rango)).append(" en ")
					.append(sede).append(": tenía turno ").append(lista(sinLlegada, f -> cuando(f, zona))).append('.');
		}
		else if (vino.isEmpty()) {
			texto.append(persona).append(" no tiene llegadas registradas ").append(periodo(rango)).append(" en ").append(sede).append('.');
		}
		else {
			texto.append(persona).append(" vino ").append(veces(vino.size())).append(' ').append(periodo(rango))
					.append(" en ").append(sede).append(": ")
					.append(lista(vino, f -> cuando(f, zona) + " (" + estadoDeTurno(f) + ")")).append('.');
		}
		if (!falto.isEmpty()) {
			texto.append(" Faltó: ").append(lista(falto, f -> cuando(f, zona))).append('.');
		}
		if (!cancelados.isEmpty()) {
			texto.append(" Cancelados: ").append(lista(cancelados, f -> cuando(f, zona))).append('.');
		}
		if (!sinLlegada.isEmpty() && !sinLlegadaEnElEncabezado) {
			texto.append(" Sin llegada registrada: ").append(lista(sinLlegada, f -> cuando(f, zona))).append('.');
		}
		if (!proximos.isEmpty()) {
			texto.append(" Próximos: ").append(lista(proximos, f -> cuando(f, zona))).append('.');
		}
		return texto.toString();
	}

	static String sesiones(String persona, RangoDeDias rango, ZoneId zona, String sede, List<Map<String, Object>> filas) {
		List<Map<String, Object>> cerradas = conEstado(filas, Set.of("ATENDIDO"));
		List<Map<String, Object>> abiertas = conEstado(filas, Set.of("EN_ATENCION"));
		List<Map<String, Object>> sinSesion = conEstado(filas, Set.of("PRESENTE", "RETIRADO"));
		if (cerradas.isEmpty() && abiertas.isEmpty() && sinSesion.isEmpty()) {
			return persona + " no tiene turnos atendidos " + periodo(rango) + " en " + sede + ".";
		}
		List<String> partes = new ArrayList<>();
		if (!cerradas.isEmpty()) {
			partes.add(cantidad(cerradas.size(), "sesión cerrada", "sesiones cerradas") + " ("
					+ lista(cerradas, f -> cuando(f, zona)) + ")");
		}
		if (!abiertas.isEmpty()) {
			partes.add(cantidad(abiertas.size(), "sesión abierta sin cerrar", "sesiones abiertas sin cerrar") + " ("
					+ lista(abiertas, f -> cuando(f, zona)) + ")");
		}
		if (!sinSesion.isEmpty()) {
			partes.add(cantidad(sinSesion.size(), "turno en que vino y no tiene sesión",
					"turnos en que vino y no tiene sesión") + " (" + lista(sinSesion, f -> cuando(f, zona)) + ")");
		}
		return persona + ", " + periodo(rango) + " en " + sede + ": " + String.join("; ", partes) + ".";
	}

	static String agenda(RangoDeDias rango, ZoneId zona, String sede, List<Map<String, Object>> filas) {
		String periodo = periodo(rango);
		String dia = Character.toUpperCase(periodo.charAt(0)) + periodo.substring(1);
		List<Map<String, Object>> vigentes = filas.stream()
				.filter(f -> !"CANCELADO".equals(f.get(ClavesDeFila.ESTADO)))
				.toList();
		if (vigentes.isEmpty()) {
			return dia + " no hay turnos en " + sede + ".";
		}
		StringBuilder texto = new StringBuilder(dia).append(" hay ")
				.append(cantidad(vigentes.size(), "turno", "turnos")).append(" en ").append(sede).append(':');
		vigentes.stream().limit(MAX_EN_LISTA).forEach(f -> texto.append("\n· ")
				.append(rango.esUnDia() ? hora(f, zona) : cuando(f, zona)).append(' ')
				.append(f.get(ClavesDeFila.PACIENTE)).append(" — ").append(f.get(ClavesDeFila.OFERTA))
				.append(conProfesional(f)).append(" · ").append(estadoDeTurno(f)));
		if (vigentes.size() > MAX_EN_LISTA) {
			texto.append("\n· y ").append(vigentes.size() - MAX_EN_LISTA).append(" más");
		}
		return texto.toString();
	}

	// --- Clases ------------------------------------------------------------------------------

	static String clases(String clase, RangoDeDias rango, ZoneId zona, String sede, List<Map<String, Object>> filas) {
		if (filas.isEmpty()) {
			return "No encontré una clase de \"" + clase + "\" " + periodo(rango) + " en " + sede + ".";
		}
		Map<Object, List<Map<String, Object>>> porClase = filas.stream().collect(
				Collectors.groupingBy(f -> f.get(ClavesDeFila.CLASE_ID), LinkedHashMap::new, Collectors.toList()));
		List<List<Map<String, Object>>> ordenadas = porClase.values().stream()
				.sorted(Comparator.comparing((List<Map<String, Object>> c) -> (Instant) c.getFirst().get(ClavesDeFila.INICIO))
						.reversed())
				.toList();
		List<String> bloques = new ArrayList<>();
		for (List<Map<String, Object>> una : ordenadas.subList(0, Math.min(MAX_CLASES, ordenadas.size()))) {
			bloques.add(unaClase(una, zona));
		}
		String texto = String.join("\n", bloques);
		if (ordenadas.size() > MAX_CLASES) {
			texto += "\nHay " + (ordenadas.size() - MAX_CLASES) + " clases más en el período; mostré las más recientes.";
		}
		return texto;
	}

	private static String unaClase(List<Map<String, Object>> filas, ZoneId zona) {
		Map<String, Object> primera = filas.getFirst();
		String encabezado = primera.get(ClavesDeFila.CLASE) + " del " + cuando(primera, zona);
		if ("CANCELADA".equals(primera.get(ClavesDeFila.ESTADO_CLASE))) {
			return encabezado + ": la clase se canceló.";
		}
		List<Map<String, Object>> conParticipante = filas.stream()
				.filter(f -> f.get(ClavesDeFila.PARTICIPANTE) != null).toList();
		List<String> presentes = nombres(conParticipante, Set.of("PRESENTE", "PRESENTE_TARDE"));
		List<String> ausentes = nombres(conParticipante, Set.of("AUSENTE"));
		List<String> sinRegistrar = conParticipante.stream()
				.filter(f -> f.get(ClavesDeFila.RESULTADO) == null)
				.map(f -> String.valueOf(f.get(ClavesDeFila.PARTICIPANTE)))
				.toList();
		if (conParticipante.isEmpty()) {
			return encabezado + ": no tiene inscriptos.";
		}
		// "¿Quienes van a yoga manana?": una clase que no empezo no tiene asistencia que contar,
		// tiene inscriptos.
		if ("PROGRAMADA".equals(primera.get(ClavesDeFila.ESTADO_CLASE))) {
			return encabezado + " (todavía no empezó): " + cantidad(sinRegistrar.size(), "inscripto", "inscriptos")
					+ ": " + listaDeTexto(sinRegistrar) + ".";
		}
		StringBuilder texto = new StringBuilder(encabezado).append(": ")
				.append(cantidad(presentes.size(), "presente", "presentes")).append(", ")
				.append(cantidad(ausentes.size(), "ausente", "ausentes"));
		if (!sinRegistrar.isEmpty()) {
			texto.append(" y ").append(sinRegistrar.size()).append(" sin registrar");
		}
		texto.append('.');
		if (!presentes.isEmpty()) {
			texto.append(" Vinieron: ").append(listaDeTexto(presentes)).append('.');
		}
		if (!ausentes.isEmpty()) {
			texto.append(" Faltaron: ").append(listaDeTexto(ausentes)).append('.');
		}
		if (!sinRegistrar.isEmpty()) {
			texto.append(" Sin asistencia registrada: ").append(listaDeTexto(sinRegistrar)).append('.');
		}
		return texto.toString();
	}

	// --- Reportes ----------------------------------------------------------------------------

	static String reporte(String reporte, RangoDeDias rango, String sede, List<Map<String, Object>> filas) {
		String titulo = switch (Objects.requireNonNullElse(reporte, "")) {
			case "ECONOMICO" -> "Números económicos";
			case "TURNOS" -> "Turnos";
			case "CLASES" -> "Ocupación de clases";
			default -> "Reporte";
		};
		if (filas.isEmpty()) {
			return titulo + " " + periodo(rango) + " en " + sede + ": no hay datos para mostrar.";
		}
		StringBuilder texto = new StringBuilder(titulo).append(' ').append(periodo(rango)).append(" en ")
				.append(sede).append(':');
		filas.stream().limit(MAX_EN_LISTA).forEach(f -> texto.append("\n· ").append(f.get(ClavesDeFila.ETIQUETA))
				.append(": ").append(valor(f)));
		if ("ECONOMICO".equals(reporte)) {
			texto.append("\nSon conceptos distintos (lo devengado no es lo cobrado ni lo que entró a caja) y no se suman.");
		}
		return texto.toString();
	}

	static String valor(Map<String, Object> fila) {
		Object crudo = fila.get(ClavesDeFila.VALOR);
		if (!(crudo instanceof BigDecimal valor)) {
			return "—";
		}
		String tipo = String.valueOf(fila.get(ClavesDeFila.TIPO));
		return switch (tipo) {
			case "DINERO" -> "$ " + numero(valor, 2);
			case "PORCENTAJE" -> numero(valor, 1) + " %";
			default -> numero(valor, 0);
		};
	}

	private static String numero(BigDecimal valor, int decimales) {
		DecimalFormat formato = new DecimalFormat(decimales == 0 ? "#,##0" : "#,##0." + "0".repeat(decimales),
				DecimalFormatSymbols.getInstance(ES_AR));
		return formato.format(valor.setScale(decimales, RoundingMode.HALF_UP));
	}

	// --- Piezas ------------------------------------------------------------------------------

	static String periodo(RangoDeDias rango) {
		if (rango.esUnDia()) {
			return "el " + dia(rango.desde());
		}
		return "entre el " + diaCorto(rango.desde()) + " y el " + diaCorto(rango.hasta());
	}

	/** "sáb 15/11". */
	static String dia(LocalDate fecha) {
		return fecha.getDayOfWeek().getDisplayName(TextStyle.SHORT, ES_AR).replace(".", "") + " " + diaCorto(fecha);
	}

	static String diaCorto(LocalDate fecha) {
		return fecha.getDayOfMonth() + "/" + fecha.getMonthValue();
	}

	/** "lun 10/11 18:00". */
	static String cuando(Map<String, Object> fila, ZoneId zona) {
		ZonedDateTime inicio = ((Instant) fila.get(ClavesDeFila.INICIO)).atZone(zona);
		return dia(inicio.toLocalDate()) + " " + HORA.format(inicio);
	}

	private static String hora(Map<String, Object> fila, ZoneId zona) {
		return HORA.format(((Instant) fila.get(ClavesDeFila.INICIO)).atZone(zona));
	}

	static String estadoDeTurno(Map<String, Object> fila) {
		return switch (String.valueOf(fila.get(ClavesDeFila.ESTADO))) {
			case "ATENDIDO" -> "atendido";
			case "EN_ATENCION" -> "en atención";
			case "PRESENTE" -> "presente, esperando";
			case "RETIRADO" -> "se retiró sin atenderse";
			case "AUSENTE" -> "ausente";
			case "CANCELADO" -> "cancelado";
			case "CONFIRMADO" -> "confirmado";
			default -> "reservado";
		};
	}

	private static String conProfesional(Map<String, Object> fila) {
		Object profesional = fila.get(ClavesDeFila.PROFESIONAL);
		return profesional == null ? "" : " (" + profesional + ")";
	}

	private static List<Map<String, Object>> conEstado(List<Map<String, Object>> filas, Set<String> estados) {
		return filas.stream().filter(f -> estados.contains(String.valueOf(f.get(ClavesDeFila.ESTADO)))).toList();
	}

	private static List<String> nombres(List<Map<String, Object>> filas, Set<String> resultados) {
		return filas.stream()
				.filter(f -> resultados.contains(String.valueOf(f.get(ClavesDeFila.RESULTADO))))
				.map(f -> String.valueOf(f.get(ClavesDeFila.PARTICIPANTE)))
				.toList();
	}

	private static String lista(List<Map<String, Object>> filas, java.util.function.Function<Map<String, Object>, String> como) {
		return listaDeTexto(filas.stream().map(como).toList());
	}

	private static String listaDeTexto(List<String> elementos) {
		if (elementos.size() <= MAX_EN_LISTA) {
			return String.join(", ", elementos);
		}
		return String.join(", ", elementos.subList(0, MAX_EN_LISTA)) + " y " + (elementos.size() - MAX_EN_LISTA) + " más";
	}

	private static String veces(int n) {
		return n == 1 ? "1 vez" : n + " veces";
	}

	private static String cantidad(long n, String singular, String plural) {
		return n + " " + (n == 1 ? singular : plural);
	}
}
