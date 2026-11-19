package com.akine.assistant.application;

import com.akine.assistant.spi.DatosDePersona;
import com.akine.platform.spi.listado.TextoDeBusqueda;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deja pasar de una {@link AccionInterpretada} solo lo que <b>esta dicho en la frase</b>, sin modelo
 * (docs/diseno/AKINE-asistente-acciones.md, §5.2). Es la misma idea que {@link ControlDelDictado}: un
 * modelo de 4B completa lo que falta con algo plausible, y aca lo plausible es un monto o un
 * tratamiento que nadie dijo.
 *
 * <p>Lo que no pasa queda en {@code null} (o fuera de la lista) y la tarjeta lo muestra vacio para
 * que la persona lo complete. El medio de pago y la fecha de nacimiento ni siquiera se le piden al
 * modelo: se leen de la frase.
 */
final class ControlDeAccion {

	private static final Pattern MILES = Pattern.compile("(?<=\\d)[.\\s](?=\\d{3}(\\D|$))");
	private static final Pattern MIL = Pattern.compile("(\\d+)\\s*mil\\b");
	private static final Pattern NUMERO = Pattern.compile("\\d+");
	private static final Pattern FECHA = Pattern.compile("\\b(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})\\b");
	private static final Pattern NO_DIGITOS = Pattern.compile("\\D+");
	private static final Pattern NO_PALABRA = Pattern.compile("[^\\p{L}\\p{N}@._+-]+");

	private ControlDeAccion() {
	}

	static AccionInterpretada controlar(AccionInterpretada leida, String frase) {
		if (leida.ninguna()) {
			return leida;
		}
		String plegada = TextoDeBusqueda.plegar(frase == null ? "" : frase);
		Set<String> palabras = palabras(plegada);
		String digitos = NO_DIGITOS.matcher(plegada).replaceAll("");
		DatosDePersona d = leida.datos();
		return new AccionInterpretada(leida.accion(),
				dicho(leida.persona(), palabras),
				contenido(leida.clase(), plegada),
				leida.modoAsistencia(),
				leida.nombres().stream().filter(n -> dicho(n, palabras) != null).toList(),
				monto(leida.monto(), plegada),
				medio(plegada),
				leida.practicas().stream().filter(p -> contenido(p, plegada) != null).toList(),
				new DatosDePersona(dicho(d.nombre(), palabras), dicho(d.apellido(), palabras),
						porDigitos(d.documento(), digitos), porDigitos(d.telefono(), digitos),
						d.email() != null && plegada.contains(TextoDeBusqueda.plegar(d.email())) ? d.email() : null,
						fecha(plegada)));
	}

	/** El importe como numero: "15.000" y "15000" son 15000; "15.000,50" es 15000.50. */
	static BigDecimal importe(String valor) {
		if (valor == null) {
			return null;
		}
		String limpio = valor.replaceAll("[^\\d.,]", "");
		limpio = limpio.contains(",") ? limpio.replace(".", "").replace(',', '.')
				: MILES.matcher(limpio).replaceAll("");
		try {
			BigDecimal importe = new BigDecimal(limpio);
			return importe.signum() > 0 ? importe : null;
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	/** El monto pasa si su parte entera esta escrita en la frase ("15.000", "15000", "15 mil"). */
	private static BigDecimal monto(BigDecimal monto, String plegada) {
		if (monto == null) {
			return null;
		}
		String normal = MILES.matcher(MIL.matcher(plegada).replaceAll("$1000")).replaceAll("");
		String entero = monto.toBigInteger().toString();
		Matcher numeros = NUMERO.matcher(normal);
		while (numeros.find()) {
			if (numeros.group().equals(entero)) {
				return monto;
			}
		}
		return null;
	}

	/** El medio sale de la frase, no del modelo. "Tarjeta" a secas no dice cual: queda sin elegir. */
	private static String medio(String plegada) {
		if (plegada.contains("efectivo")) {
			return "EFECTIVO";
		}
		if (plegada.contains("transfer") || plegada.contains("transfir")) {
			return "TRANSFERENCIA";
		}
		if (plegada.contains("debito")) {
			return "TARJETA_DEBITO";
		}
		if (plegada.contains("credito")) {
			return "TARJETA_CREDITO";
		}
		return null;
	}

	private static LocalDate fecha(String plegada) {
		Matcher m = FECHA.matcher(plegada);
		if (!m.find()) {
			return null;
		}
		try {
			return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
		}
		catch (DateTimeException e) {
			return null;
		}
	}

	/** Un nombre pasa si cada una de sus palabras esta en la frase. */
	private static String dicho(String valor, Set<String> palabras) {
		if (valor == null || valor.isBlank()) {
			return null;
		}
		Set<String> suyas = palabras(TextoDeBusqueda.plegar(valor));
		return !suyas.isEmpty() && palabras.containsAll(suyas) ? valor.strip() : null;
	}

	/** Un nombre de clase o de tratamiento pasa si su texto esta en la frase. */
	private static String contenido(String valor, String plegada) {
		if (valor == null || valor.isBlank()) {
			return null;
		}
		return plegada.contains(TextoDeBusqueda.plegar(valor.strip())) ? valor.strip() : null;
	}

	/** Un telefono o un documento pasan si sus digitos estan en la frase, en ese orden. */
	private static String porDigitos(String valor, String digitosDeLaFrase) {
		if (valor == null) {
			return null;
		}
		String suyos = NO_DIGITOS.matcher(valor).replaceAll("");
		return suyos.length() >= 6 && digitosDeLaFrase.contains(suyos) ? suyos : null;
	}

	private static Set<String> palabras(String plegada) {
		return Arrays.stream(NO_PALABRA.split(plegada)).filter(p -> !p.isEmpty()).collect(Collectors.toSet());
	}
}
