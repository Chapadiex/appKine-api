package com.akine.resource.api.dto;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * La frontera HTTP de una hora de pared de M05, con la medianoche escrita como {@code "24:00"}.
 *
 * <h2>El problema que resuelve, que no es cosmetico</h2>
 *
 * <p>Un bloque que llega hasta la medianoche se representa internamente con
 * {@code IntervaloLocal.FIN_DE_DIA}, que es {@link LocalTime#MAX}: <b>23:59:59.999999999</b>. Es
 * el unico valor de {@code LocalTime} que puede expresar "el final del dia" en un intervalo con
 * extremo superior EXCLUSIVO, porque {@code 00:00} significaria el principio.
 *
 * <p>Serializado tal cual, ese valor produce dos danos distintos:
 *
 * <ol>
 *   <li><b>Muestra algo que el usuario nunca cargo.</b> El administrador escribio "hasta las
 *       12 de la noche" y la pantalla le devuelve {@code 23:59:59.999999999}.</li>
 *   <li><b>Rompe el ida y vuelta.</b> El cliente que lee el bloque y lo reenvia para editarlo no
 *       puede reproducir ese valor: {@code 23:59:59.999999999} escrito a mano pierde nanos en
 *       cualquier formulario, y el bloque deja de llegar a la medianoche sin que nadie lo
 *       pida.</li>
 * </ol>
 *
 * <p>Por eso el contrato publica {@code "24:00"} y lo acepta de vuelta. La traduccion vive
 * <b>solo</b> aca, en la frontera HTTP: el dominio sigue usando {@code LocalTime.MAX} y el
 * converter de la base sigue traduciendo del otro lado. Que la frontera con la base ya estuviera
 * resuelta y esta no era justamente el hueco.
 *
 * <h2>Que se acepta en la entrada</h2>
 *
 * <p>{@code "24:00"} y {@code "24:00:00"} son la medianoche. Cualquier otra hora se parsea con el
 * formato ISO local ({@code "09:00"}, {@code "09:30"}, {@code "09:30:15"}). Un texto invalido sale
 * por {@link DateTimeParseException}, que el advice global ya traduce a 400.
 */
public final class HoraDelDia {

	/** Lo que viaja en el JSON cuando la hora es {@link LocalTime#MAX}. */
	public static final String MEDIANOCHE = "24:00";

	/** Forma larga que tambien se acepta en la entrada, por si el cliente normaliza a HH:mm:ss. */
	private static final String MEDIANOCHE_LARGA = "24:00:00";

	private HoraDelDia() {
		// Utilidad de frontera.
	}

	/**
	 * Texto de contrato de una hora de pared. {@code null} adentro, {@code null} afuera.
	 *
	 * <p>Se usa {@link LocalTime#toString()} y <b>no</b> {@code format(ISO_LOCAL_TIME)}, que es la
	 * trampa: el formateador ISO tiene la seccion de segundos como opcional solo para el PARSEO
	 * —al formatear, {@code SECOND_OF_MINUTE} siempre esta disponible en un {@code LocalTime}, asi
	 * que las 09:00 salen como {@code "09:00:00"}—. {@code toString()} si omite los segundos
	 * cuando son cero, que es como el usuario escribe la hora y como la espera de vuelta.
	 */
	public static String aTexto(LocalTime hora) {
		if (hora == null) {
			return null;
		}
		return LocalTime.MAX.equals(hora) ? MEDIANOCHE : hora.toString();
	}

	/** Hora de pared a partir del texto de contrato. */
	public static LocalTime deTexto(String texto) {
		if (texto == null) {
			return null;
		}
		String limpio = texto.trim();
		if (limpio.isEmpty()) {
			return null;
		}
		if (MEDIANOCHE.equals(limpio) || MEDIANOCHE_LARGA.equals(limpio)) {
			return LocalTime.MAX;
		}
		return LocalTime.parse(limpio);
	}

	/** Escritura: {@link LocalTime#MAX} sale como {@code "24:00"}. */
	public static final class Serializador extends ValueSerializer<LocalTime> {

		@Override
		public void serialize(LocalTime valor, JsonGenerator gen, SerializationContext ctxt) {
			gen.writeString(aTexto(valor));
		}
	}

	/** Lectura: {@code "24:00"} entra como {@link LocalTime#MAX}. */
	public static final class Deserializador extends ValueDeserializer<LocalTime> {

		@Override
		public LocalTime deserialize(JsonParser parser, DeserializationContext ctxt) {
			return deTexto(parser.getString());
		}
	}
}
