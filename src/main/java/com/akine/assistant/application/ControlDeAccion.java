package com.akine.assistant.application;

import com.akine.assistant.spi.DatosDePersona;
import com.akine.platform.spi.listado.TextoDeBusqueda;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 *
 * <p>Que un valor este escrito no alcanza: tambien importa <b>el papel</b> que cumple. El modelo lee
 * al reves las negaciones, las deudas, el pasado y el futuro, y "faltaron todos menos Maria" (medido
 * en docs/asistente/evaluacion/resultados-2026-11-19-acciones-adversas.md). Esas frases se frenan
 * aca, por reglas sobre la frase plegada: o la accion entera pasa a {@code NINGUNA}, o el dato dudoso
 * queda vacio para que la persona lo complete.
 */
final class ControlDeAccion {

	private static final Pattern MILES = Pattern.compile("(?<=\\d)[.\\s](?=\\d{3}(\\D|$))");
	/** "15 mil", "15 lucas", "15k" y "2 palos": el numero y su magnitud. */
	private static final Pattern MAGNITUD = Pattern.compile("(\\d+)(?:\\s*(mil|lucas?|palos?)|(k))\\b");
	private static final Pattern CADA_UNO = Pattern.compile("\\bcada un[ao]\\b|\\bc/u\\b");
	private static final BigInteger MINIMO_DE_UN_IMPORTE = BigInteger.valueOf(100);
	private static final Pattern NUMERO = Pattern.compile("\\d+");
	private static final Pattern FECHA = Pattern.compile("\\b(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})\\b");
	private static final Pattern NO_DIGITOS = Pattern.compile("\\D+");
	/**
	 * Todo lo que no es letra ni numero corta palabra, la puntuacion tambien: el dictado termina casi
	 * siempre en punto, y con el punto pegado "María." no era "María" (la frase "todos menos María."
	 * perdia el nombre y la marcaba presente). El mail no pasa por aca: se controla por contenido.
	 */
	private static final Pattern NO_PALABRA = Pattern.compile("[^\\p{L}\\p{N}]+");

	/** "mañana" suelta; "a la mañana", "esta mañana" y "por la mañana" son de hoy. */
	private static final String MANANA = "(?<!\\bla )(?<!\\besta )\\bmanana\\b";
	private static final Pattern MANANA_SUELTA = Pattern.compile(MANANA);
	private static final Pattern OTRO_DIA = Pattern.compile("\\bayer\\b|\\banteayer\\b|" + MANANA
			+ "|\\bla semana pasada\\b|\\bel (lunes|martes|miercoles|jueves|viernes|sabado|domingo)\\b");
	private static final Pattern FUTURO = Pattern.compile("\\bla proxima\\b|\\bviene\\b|\\bva a venir\\b");
	private static final Pattern HECHO = Pattern.compile("\\b(vino|hizo|hice|hicimos|atendi|trabajamos)\\b");
	private static final Pattern NO_ES_UN_COBRO = Pattern.compile("\\bno pago\\b|\\bdebe\\b|\\bdebia\\b"
			+ "|\\bdebiendo\\b|\\badeuda\\b|\\bnecesito saber\\b|\\bfijate si\\b|\\bme podes decir\\b");
	private static final Pattern NO_ES_UN_ALTA = Pattern.compile(
			"inscrib|\\banota\\w*\\b.*\\b(en|a la clase)\\b|\\bpase de\\b|\\babono de\\b");
	private static final Pattern INYECCION = Pattern.compile(
			"ignora|ignore|instrucciones|instructions|system|sistema:|devolve|[{}]");

	/** Frases de asistencia que el modelo devuelve con el modo invertido. */
	private static final Pattern MODO_DUDOSO = Pattern.compile("\\b(nadie|ninguno|ninguna)\\b"
			+ "|\\bfaltaron tod[ao]s\\b|\\btodos faltaron\\b|\\bl[ao]s demas faltaron\\b|\\bel resto falto\\b");
	private static final Set<String> CANTIDADES = Set.of("uno", "una", "dos", "tres", "cuatro", "cinco", "seis",
			"siete", "ocho", "nueve", "diez", "varios", "algunos", "mitad");
	private static final Set<String> NO_SON_NOMBRES = Set.of("todos", "todas", "nadie", "demas", "resto");
	private static final Set<String> ARTICULOS = Set.of("el", "la", "los", "las");
	/** Verbos que el modelo arrastra pegados al nombre ("marcelo abono", "Tolero"). */
	private static final Set<String> VERBOS_PEGADOS = Set.of("pago", "abono", "transfirio", "tolero", "vino",
			"hizo", "dejo", "debe");

	private static final int VENTANA_ANTES = 30;
	private static final int VENTANA_DESPUES = 20;
	/** Lo que, dicho justo antes de una practica, dice que no se hizo hoy. */
	private static final Pattern NO_SE_HIZO = Pattern.compile("\\b(no|sin|habia|le hago|voy a|iba a)\\b"
			+ "|contraindicad|suspend|\\bla proxima\\b|\\bproxima sesion\\b|\\bla semana pasada\\b");
	/** Lo que, dicho justo despues, tambien: "ultrasonido contraindicado por el marcapasos". */
	private static final Pattern DESCARTADA_DESPUES =
			Pattern.compile("^\\s*(esta |queda |quedo )?(contraindicad|suspend)");
	/** Donde la frase vuelve a lo que si se hizo: lo anterior ya no alcanza a la practica. */
	private static final Pattern VUELVE_A_HOY = Pattern.compile("\\b(hoy|solo|solamente|pero|hicimos|trabajamos"
			+ "|asi que|al final|[ey] hizo|y le hice|y hice)\\b");

	private ControlDeAccion() {
	}

	static AccionInterpretada controlar(AccionInterpretada leida, String frase) {
		if (leida.ninguna()) {
			return leida;
		}
		String plegada = TextoDeBusqueda.plegar(frase == null ? "" : frase);
		if (noEsUnaAccionDeHoy(leida.accion(), plegada)) {
			return AccionInterpretada.NINGUNA;
		}
		Set<String> palabras = palabras(plegada);
		String digitos = NO_DIGITOS.matcher(plegada).replaceAll("");
		DatosDePersona d = leida.datos();

		String modo = leida.modoAsistencia();
		List<String> nombres = leida.nombres().stream()
				.map(ControlDeAccion::sinVerbosPegados)
				.filter(n -> dicho(n, palabras) != null)
				.filter(n -> !soloDe(n, NO_SON_NOMBRES))
				.toList();
		if (AccionInterpretada.ASISTENCIA_CLASE.equals(leida.accion())) {
			List<String> sinCantidades = nombres.stream().filter(n -> !soloDe(n, CANTIDADES)).toList();
			if (MODO_DUDOSO.matcher(plegada).find()) {
				// "faltaron todos menos Maria" vuelve como TODOS_MENOS [Maria]: la tarjeta sale sin marcar.
				modo = null;
				nombres = List.of();
			}
			else if (sinCantidades.size() != nombres.size()) {
				// "faltaron tres": no se sabe quienes, y sin nombres TODOS_MENOS los daria presentes a todos.
				modo = null;
				nombres = sinCantidades;
			}
		}

		String nombre = dicho(d.nombre(), palabras);
		String apellido = dicho(d.apellido(), palabras);
		if (nombre != null && apellido != null
				&& TextoDeBusqueda.plegar(nombre).equals(TextoDeBusqueda.plegar(apellido))) {
			apellido = null;
		}
		return new AccionInterpretada(leida.accion(),
				dicho(sinVerbosPegados(leida.persona()), palabras),
				contenido(leida.clase(), plegada),
				modo,
				nombres,
				monto(leida.monto(), plegada),
				medio(plegada),
				leida.practicas().stream().filter(p -> hecha(p, plegada)).toList(),
				new DatosDePersona(nombre, apellido,
						porDigitos(d.documento(), digitos), porDigitos(d.telefono(), digitos),
						d.email() != null && plegada.contains(TextoDeBusqueda.plegar(d.email())) ? d.email() : null,
						fecha(plegada)));
	}

	/**
	 * Frases que el modelo toma por una accion y no lo son: una inyeccion, la asistencia de otro dia,
	 * una deuda o una consulta leidas como cobro, una atencion que todavia no ocurrio, una inscripcion
	 * leida como alta.
	 */
	private static boolean noEsUnaAccionDeHoy(String accion, String plegada) {
		if (INYECCION.matcher(plegada).find()) {
			return true;
		}
		return switch (accion) {
			case AccionInterpretada.ASISTENCIA_CLASE -> OTRO_DIA.matcher(plegada).find();
			case AccionInterpretada.PAGO -> NO_ES_UN_COBRO.matcher(plegada).find();
			case AccionInterpretada.ATENCION -> MANANA_SUELTA.matcher(plegada).find()
					|| (FUTURO.matcher(plegada).find() && !HECHO.matcher(plegada).find());
			case AccionInterpretada.PACIENTE_ALTA -> NO_ES_UN_ALTA.matcher(plegada).find();
			default -> false;
		};
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

	/**
	 * El monto pasa si su parte entera esta escrita en la frase ("15.000", "15000", "15 mil", "15
	 * lucas", "15k"). Si el modelo devolvio el numero sin su magnitud ("15" por "15 lucas"), vale el de
	 * la frase. Con dos importes distintos, o "cada una", no se elige: queda vacio.
	 */
	private static BigDecimal monto(BigDecimal monto, String plegada) {
		if (monto == null || CADA_UNO.matcher(plegada).find()) {
			return null;
		}
		Map<String, String> conMagnitud = new HashMap<>();
		String normal = MAGNITUD.matcher(MILES.matcher(plegada).replaceAll("")).replaceAll(m -> {
			String magnitud = m.group(2) == null ? m.group(3) : m.group(2);
			String entero = m.group(1) + (magnitud.startsWith("palo") ? "000000" : "000");
			conMagnitud.put(m.group(1), entero);
			return entero;
		});
		Set<String> escritos = new HashSet<>();
		Set<BigInteger> importes = new HashSet<>();
		Matcher numeros = NUMERO.matcher(normal);
		while (numeros.find()) {
			escritos.add(numeros.group());
			BigInteger valor = new BigInteger(numeros.group());
			if (valor.compareTo(MINIMO_DE_UN_IMPORTE) >= 0) {
				importes.add(valor);
			}
		}
		if (importes.size() > 1) {
			return null;
		}
		String entero = monto.toBigInteger().toString();
		if (escritos.contains(entero)) {
			return monto;
		}
		boolean sinDecimales = monto.stripTrailingZeros().scale() <= 0;
		return sinDecimales && conMagnitud.containsKey(entero) ? new BigDecimal(conMagnitud.get(entero)) : null;
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

	/**
	 * Una practica pasa si esta en la frase y, en alguna de sus apariciones, lo que la rodea no dice
	 * que no se hizo hoy ("no le hice", "sin", "contraindicado", "la proxima le hago").
	 */
	private static boolean hecha(String practica, String plegada) {
		if (contenido(practica, plegada) == null) {
			return false;
		}
		String buscada = TextoDeBusqueda.plegar(practica.strip());
		for (int en = plegada.indexOf(buscada); en >= 0; en = plegada.indexOf(buscada, en + 1)) {
			String antes = plegada.substring(Math.max(0, en - VENTANA_ANTES), en);
			if (en > VENTANA_ANTES) {
				// La ventana puede arrancar en medio de una palabra: de "vino" quedaria "no".
				antes = antes.substring(antes.indexOf(' ') + 1);
			}
			Matcher vuelve = VUELVE_A_HOY.matcher(antes);
			int desde = 0;
			while (vuelve.find()) {
				desde = vuelve.end();
			}
			int fin = en + buscada.length();
			String despues = plegada.substring(fin, Math.min(plegada.length(), fin + VENTANA_DESPUES));
			if (!NO_SE_HIZO.matcher(antes.substring(desde)).find() && !DESCARTADA_DESPUES.matcher(despues).find()) {
				return true;
			}
		}
		return false;
	}

	/** El nombre sin los verbos que el modelo le dejo pegados al final; {@code null} si no queda nada. */
	private static String sinVerbosPegados(String valor) {
		if (valor == null) {
			return null;
		}
		String limpio = valor.strip();
		while (!limpio.isEmpty()) {
			int corte = limpio.lastIndexOf(' ');
			if (!VERBOS_PEGADOS.contains(TextoDeBusqueda.plegar(limpio.substring(corte + 1)))) {
				return limpio;
			}
			limpio = corte < 0 ? "" : limpio.substring(0, corte).strip();
		}
		return null;
	}

	/** Si, sacando articulos, todas sus palabras son de {@code vocabulario} ("tres", "los demas"). */
	private static boolean soloDe(String valor, Set<String> vocabulario) {
		Set<String> suyas = new HashSet<>(palabras(TextoDeBusqueda.plegar(valor)));
		suyas.removeAll(ARTICULOS);
		return !suyas.isEmpty() && vocabulario.containsAll(suyas);
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
