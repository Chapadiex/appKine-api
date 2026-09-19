package com.akine.clinical.application;

import com.akine.clinical.domain.exception.CursorInvalidoException;
import com.akine.clinical.spi.EventoClinico;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Comparator;

/**
 * La posicion de lectura dentro del timeline: el ultimo evento entregado.
 *
 * <h2>Por que keyset y no offset</h2>
 *
 * <p>Un {@code OFFSET} sobre un agregado de fuentes heterogeneas se desordena en cuanto una
 * fuente inserta: basta que se registre una entrada mientras alguien pagina para que la pagina 2
 * repita o saltee filas. El keyset no depende de cuantas filas hay antes, sino de <b>cual fue la
 * ultima</b>.
 *
 * <h2>Por que el orden total tiene tres columnas</h2>
 *
 * <p>{@code ocurrioEn} sola no ordena: cuatro fuentes distintas producen empates con facilidad
 * —una sesion que cierra y el adjunto que se sube en el mismo segundo— y un empate sin desempate
 * se ordena como quiera la JVM, o sea distinto entre una pagina y la siguiente. El orden total es
 * {@code (ocurrioEn DESC, origen ASC, referencia DESC)}: determinista, y replicable por cada
 * contribuyente contra su propio indice.
 *
 * <h2>Opaco, y por que importa</h2>
 *
 * <p>Viaja en base64 sin relleno. No es seguridad —se decodifica en dos segundos— sino contrato:
 * un cursor que se lee como {@code "2026-09-19T...|SESION|41"} invita a que un cliente lo
 * construya a mano, y a partir de ahi el formato deja de poder cambiar sin romperlo. Opaco
 * significa que el unico modo legitimo de obtener uno es haber leido la pagina anterior.
 */
record TimelineCursor(Instant ocurrioEn, String origen, long referencia) {

	/**
	 * El orden total del timeline. Mas nuevo primero; los empates los desempata el origen y,
	 * dentro de un origen, la referencia mas alta —o sea la fila mas nueva— primero.
	 */
	static final Comparator<EventoClinico> ORDEN =
			Comparator.comparing(EventoClinico::ocurrioEn).reversed()
					.thenComparing(EventoClinico::origen)
					.thenComparing(Comparator.comparingLong(EventoClinico::referencia).reversed());

	private static final String SEPARADOR = "|";

	/** El cursor que apunta a ese evento. */
	static TimelineCursor de(EventoClinico evento) {
		return new TimelineCursor(evento.ocurrioEn(), evento.origen(), evento.referencia());
	}

	/**
	 * {@code true} si ese evento va <b>despues</b> del cursor en el orden total.
	 *
	 * <p>Es estrictamente despues: el evento al que apunta el cursor ya se entrego en la pagina
	 * anterior y repetirlo seria un duplicado visible. Los del mismo instante que el cursor pero
	 * posteriores en el desempate <b>si</b> entran, que es la razon por la que el tope temporal
	 * que reciben los contribuyentes es inclusivo.
	 */
	boolean precedeA(EventoClinico evento) {
		return ORDEN.compare(comoEvento(), evento) < 0;
	}

	/** El cursor codificado para el cliente. */
	String codificar() {
		String plano = ocurrioEn.toString() + SEPARADOR + origen + SEPARADOR + referencia;
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(plano.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * El cursor que trae el pedido, o {@code null} si no trae ninguno —primera pagina—.
	 *
	 * <p>Todo lo que no decodifique exactamente es {@link CursorInvalidoException}, o sea 400. No
	 * se degrada a "primera pagina": ver el javadoc de esa excepcion.
	 */
	static TimelineCursor decodificar(String crudo) {
		if (crudo == null || crudo.isBlank()) {
			return null;
		}
		String plano;
		try {
			plano = new String(
					Base64.getUrlDecoder().decode(crudo.strip()), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException noEsBase64) {
			throw new CursorInvalidoException("no es base64");
		}

		// -1 para que un origen con separadores no se pierda en silencio: si aparecen mas de tres
		// partes, el cursor esta corrupto y se rechaza en vez de adivinar cual es cual.
		String[] partes = plano.split("\\" + SEPARADOR, -1);
		if (partes.length != 3) {
			throw new CursorInvalidoException("no tiene los tres campos esperados");
		}

		try {
			return new TimelineCursor(
					Instant.parse(partes[0]), partes[1], Long.parseLong(partes[2]));
		}
		catch (DateTimeParseException | NumberFormatException malFormado) {
			throw new CursorInvalidoException("sus campos no tienen el formato esperado");
		}
	}

	private EventoClinico comoEvento() {
		// El tipo y el titulo no participan del orden, asi que no hace falta reconstruirlos: el
		// cursor guarda exactamente las tres columnas que ordenan y ni una mas.
		return new EventoClinico(ocurrioEn, origen, null, null, referencia);
	}
}
