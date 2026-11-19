package com.akine.assistant.spi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Lo que se entendio de la frase, ya controlado: nada de esto se usa si no estaba dicho. La persona
 * llega resuelta a un id (salvo en el alta).
 *
 * @param personaId      la persona de la que se habla, o {@code null} si la accion no la usa
 * @param personaNombre  su nombre como figura en el padron, o {@code null}
 * @param clase          el nombre de la actividad, o {@code null}
 * @param modoAsistencia {@link #TODOS_MENOS}, {@link #SOLO} o {@code null}
 * @param nombres        los nombrados en la asistencia
 * @param monto          el importe dicho, o {@code null}
 * @param medio          {@code EFECTIVO}, {@code TRANSFERENCIA}, {@code TARJETA_DEBITO},
 *                       {@code TARJETA_CREDITO} o {@code null}
 * @param practicas      los tratamientos nombrados
 * @param datos          los datos de persona dichos; nunca {@code null}
 * @param frase          la frase original: la nota de la atencion
 * @param hoy            el dia de la sede
 */
public record PedidoDeAccion(
		TipoDeAccion tipo,
		Long personaId,
		String personaNombre,
		String clase,
		String modoAsistencia,
		List<String> nombres,
		BigDecimal monto,
		String medio,
		List<String> practicas,
		DatosDePersona datos,
		String frase,
		LocalDate hoy,
		ZoneId zona) {

	public static final String TODOS_MENOS = "TODOS_MENOS";
	public static final String SOLO = "SOLO";

	public PedidoDeAccion {
		nombres = nombres == null ? List.of() : List.copyOf(nombres);
		practicas = practicas == null ? List.of() : List.copyOf(practicas);
		datos = datos == null ? DatosDePersona.VACIO : datos;
	}
}
