package com.akine.assistant.application;

import com.akine.platform.spi.listado.TextoDeBusqueda;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Del periodo que devolvio el modelo a dias de la sede.
 *
 * <p><b>El modelo no calcula fechas</b> (diseno, §4.5): devuelve una palabra del vocabulario
 * ({@code ESTA_SEMANA}, {@code DIA:MARTES}, {@code MES:OCTUBRE}) y la cuenta la hace este resolutor
 * con el "hoy" de la organizacion. Dos reglas salieron de la medicion del 15/11
 * ({@code docs/asistente/evaluacion/}):
 * <ul>
 *   <li>los alias evidentes se normalizan antes de validar ({@code MES:PASADO} es
 *       {@code MES_PASADO});</li>
 *   <li>una fecha escrita en la pregunta ({@code 20/11}) se lee del texto y el anio lo pone el
 *       servidor: el modelo escribio {@code 2023} para un {@code 20/11} sin anio.</li>
 * </ul>
 *
 * <p>La semana empieza el lunes.
 */
public final class ResolutorDePeriodo {

	static final Set<String> FIJOS = Set.of(
			"HOY", "AYER", "MANANA", "ESTA_SEMANA", "SEMANA_PASADA", "ESTE_MES", "MES_PASADO");

	private static final Map<String, DayOfWeek> DIAS = Map.of(
			"LUNES", DayOfWeek.MONDAY, "MARTES", DayOfWeek.TUESDAY, "MIERCOLES", DayOfWeek.WEDNESDAY,
			"JUEVES", DayOfWeek.THURSDAY, "VIERNES", DayOfWeek.FRIDAY, "SABADO", DayOfWeek.SATURDAY,
			"DOMINGO", DayOfWeek.SUNDAY);

	private static final Map<String, Month> MESES = Map.ofEntries(
			Map.entry("ENERO", Month.JANUARY), Map.entry("FEBRERO", Month.FEBRUARY),
			Map.entry("MARZO", Month.MARCH), Map.entry("ABRIL", Month.APRIL), Map.entry("MAYO", Month.MAY),
			Map.entry("JUNIO", Month.JUNE), Map.entry("JULIO", Month.JULY), Map.entry("AGOSTO", Month.AUGUST),
			Map.entry("SEPTIEMBRE", Month.SEPTEMBER), Map.entry("SETIEMBRE", Month.SEPTEMBER),
			Map.entry("OCTUBRE", Month.OCTOBER), Map.entry("NOVIEMBRE", Month.NOVEMBER),
			Map.entry("DICIEMBRE", Month.DECEMBER));

	/** Lo que el modelo escribe a veces en lugar del valor del vocabulario. */
	private static final Map<String, String> ALIAS = Map.of(
			"MES:PASADO", "MES_PASADO",
			"SEMANA:PASADA", "SEMANA_PASADA",
			"ESTE:MES", "ESTE_MES",
			"ESTA:SEMANA", "ESTA_SEMANA",
			"DIA:HOY", "HOY",
			"DIA:AYER", "AYER",
			"DIA:MANANA", "MANANA");

	private static final Pattern FECHA_CANONICA = Pattern.compile("FECHA:(\\d{4})-(\\d{2})-(\\d{2})");

	/** {@code 20/11}, {@code 3/9}, {@code 20/11/2026}, {@code 20/11/26}. */
	private static final Pattern FECHA_EN_TEXTO =
			Pattern.compile("(?<!\\d)(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}|\\d{2}))?(?!\\d)");

	private ResolutorDePeriodo() {
	}

	/**
	 * El valor del vocabulario, o {@code null} si el texto no es ninguno: un periodo ilegible se trata
	 * como "sin periodo" y no tumba la interpretacion entera.
	 */
	public static String normalizar(String crudo) {
		if (crudo == null) {
			return null;
		}
		String valor = TextoDeBusqueda.plegar(crudo.strip()).toUpperCase(Locale.ROOT).replace(' ', '_');
		if (valor.isEmpty() || valor.equals("NULL")) {
			return null;
		}
		valor = ALIAS.getOrDefault(valor, valor);
		if (FIJOS.contains(valor)) {
			return valor;
		}
		if (valor.startsWith("DIA:") && DIAS.containsKey(valor.substring(4))) {
			return valor;
		}
		if (valor.startsWith("MES:") && MESES.containsKey(valor.substring(4))) {
			return valor.equals("MES:SETIEMBRE") ? "MES:SEPTIEMBRE" : valor;
		}
		Matcher fecha = FECHA_CANONICA.matcher(valor);
		if (fecha.matches() && fechaValida(fecha.group(1), fecha.group(2), fecha.group(3)).isPresent()) {
			return valor;
		}
		return null;
	}

	/**
	 * Los dias que abarca el periodo.
	 *
	 * @param periodo        un valor ya normalizado, o {@code null}
	 * @param pregunta       el texto original: una fecha escrita en el manda sobre lo que dijo el modelo
	 * @param hoy            el dia de hoy en la zona de la organizacion
	 * @param haciaAdelante  para un dia de la semana: {@code true} toma el proximo (la agenda, "quien
	 *                       viene el jueves"), {@code false} el ultimo ("vino el lunes"); hoy cuenta
	 *                       en los dos casos
	 * @return vacio si no hay periodo: la intencion decide el default
	 */
	public static Optional<RangoDeDias> resolver(String periodo, String pregunta, LocalDate hoy, boolean haciaAdelante) {
		Optional<LocalDate> escrita = fechaEscrita(pregunta, hoy);
		if (escrita.isPresent()) {
			return escrita.map(RangoDeDias::delDia);
		}
		if (periodo == null) {
			return Optional.empty();
		}
		return Optional.of(switch (periodo) {
			case "HOY" -> RangoDeDias.delDia(hoy);
			case "AYER" -> RangoDeDias.delDia(hoy.minusDays(1));
			case "MANANA" -> RangoDeDias.delDia(hoy.plusDays(1));
			case "ESTA_SEMANA" -> semanaDe(hoy);
			case "SEMANA_PASADA" -> semanaDe(hoy.minusWeeks(1));
			case "ESTE_MES" -> mes(YearMonth.from(hoy));
			case "MES_PASADO" -> mes(YearMonth.from(hoy).minusMonths(1));
			default -> compuesto(periodo, hoy, haciaAdelante);
		});
	}

	private static RangoDeDias compuesto(String periodo, LocalDate hoy, boolean haciaAdelante) {
		if (periodo.startsWith("DIA:")) {
			DayOfWeek dia = DIAS.get(periodo.substring(4));
			LocalDate fecha = hoy.with(haciaAdelante
					? TemporalAdjusters.nextOrSame(dia)
					: TemporalAdjusters.previousOrSame(dia));
			return RangoDeDias.delDia(fecha);
		}
		if (periodo.startsWith("MES:")) {
			// "en octubre" se pregunta por lo que ya paso: un mes posterior al actual es del anio pasado.
			Month mes = MESES.get(periodo.substring(4));
			int anio = mes.getValue() > hoy.getMonthValue() ? hoy.getYear() - 1 : hoy.getYear();
			return mes(YearMonth.of(anio, mes));
		}
		Matcher fecha = FECHA_CANONICA.matcher(periodo);
		if (fecha.matches()) {
			// El anio del modelo no es confiable: se usa el de hoy.
			return fechaValida(String.valueOf(hoy.getYear()), fecha.group(2), fecha.group(3))
					.map(RangoDeDias::delDia)
					.orElseThrow(() -> new IllegalArgumentException("Fecha invalida: " + periodo));
		}
		throw new IllegalArgumentException("Periodo fuera del vocabulario: " + periodo);
	}

	/** La primera fecha {@code dd/mm[/aaaa]} valida del texto; sin anio, el de hoy. */
	static Optional<LocalDate> fechaEscrita(String pregunta, LocalDate hoy) {
		if (pregunta == null) {
			return Optional.empty();
		}
		Matcher m = FECHA_EN_TEXTO.matcher(pregunta);
		while (m.find()) {
			String anio = m.group(3) == null ? String.valueOf(hoy.getYear())
					: m.group(3).length() == 2 ? "20" + m.group(3) : m.group(3);
			Optional<LocalDate> fecha = fechaValida(anio, m.group(2), m.group(1));
			if (fecha.isPresent()) {
				return fecha;
			}
		}
		return Optional.empty();
	}

	private static Optional<LocalDate> fechaValida(String anio, String mes, String dia) {
		try {
			return Optional.of(LocalDate.of(Integer.parseInt(anio), Integer.parseInt(mes), Integer.parseInt(dia)));
		}
		catch (DateTimeException | NumberFormatException e) {
			return Optional.empty();
		}
	}

	private static RangoDeDias semanaDe(LocalDate dia) {
		LocalDate lunes = dia.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		return new RangoDeDias(lunes, lunes.plusDays(6));
	}

	private static RangoDeDias mes(YearMonth mes) {
		return new RangoDeDias(mes.atDay(1), mes.atEndOfMonth());
	}
}
