package com.akine.platform.spi.problem;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Con que choca una operacion de agenda, dicho igual en los tres modulos que la validan (disponibilidad
 * central por persona, contrato 0.158.0).
 *
 * <p>Una persona tiene una membership por sede, y lo que le impide tomar una obligacion puede estar en
 * otra sede y ser de otro tipo: un turno, una clase (como principal o adicional), un bloqueo o una
 * ausencia suya, el cierre de la sede o su horario de atencion en otra sede. {@code scheduling},
 * {@code activity} y {@code resource} lo rechazan cada uno con su {@code problemType} de siempre
 * ({@code recurso-ocupado}, {@code slot-no-disponible}, {@code bloque-solapado}), y todos escriben las
 * mismas extensiones con {@link #propiedades()} y el mismo {@code detail} con {@link #detalle}. Las
 * extensiones estan declaradas en el schema {@code ProblemDetail} del contrato.
 *
 * <p><b>Nunca lleva datos del paciente ni clinicos</b>: tipo, id del evento, sede, franja y, si aplica,
 * el instructor (que es personal de la organizacion, no un paciente).
 *
 * @param eventoId               id del turno, la clase, la excepcion o el bloque; {@code null} si no se
 *                               expone (una franja de disponibilidad calculada)
 * @param mismaSede              {@code true} si el conflicto es en la sede de la operacion
 * @param zona                   zona IANA de la sede del conflicto, para escribir las horas locales
 * @param instructorMembershipId a quien ocupa, cuando la operacion valida a varias personas (clase)
 */
public record ConflictoDeAgenda(
		Tipo tipo,
		Long eventoId,
		Long consultorioId,
		String consultorioNombre,
		boolean mismaSede,
		Instant desde,
		Instant hasta,
		String zona,
		Long instructorMembershipId,
		String instructorNombre) {

	/** La causa. Estable: el frontend ramifica por este valor, nunca por el {@code detail}. */
	public enum Tipo {
		/** Un turno vivo de la misma persona, en cualquier sede. */
		TURNO,
		/** Una clase viva, como principal o como instructor adicional, en cualquier sede. */
		CLASE,
		/** Un cierre de agenda del profesional (motivo BLOQUEO, CAPACITACION, OTRO...). */
		BLOQUEO,
		/** Un cierre del profesional por ausencia, licencia o vacaciones. */
		AUSENCIA,
		/** Un cierre de la sede entera (o feriado). */
		CIERRE_DE_SEDE,
		/**
		 * Horario de atencion (habitual o apertura) de la misma persona en otra sede; con {@code mismaSede},
		 * un instructor que no atiende en esta sede en todo el horario de la clase (D-6).
		 */
		DISPONIBILIDAD
	}

	private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
	private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

	public ConflictoDeAgenda conInstructor(Long membershipId, String nombre) {
		return new ConflictoDeAgenda(tipo, eventoId, consultorioId, consultorioNombre, mismaSede, desde, hasta,
				zona, membershipId, nombre);
	}

	/** {@code true} si la causa es algo reservado (turno o clase), no la disponibilidad. */
	public boolean esActividadReservada() {
		return tipo == Tipo.TURNO || tipo == Tipo.CLASE;
	}

	/**
	 * El {@code detail} en castellano, con la causa, la franja y la sede reales.
	 *
	 * <p>"El profesional ya tiene un turno el 08/03/2027 de 10:00 a 11:00 en la sede Norte. No es posible
	 * asignarle otro turno en ese horario."
	 *
	 * @param consecuencia la segunda oracion, la del llamador ("No es posible asignarle otro turno en ese
	 *                     horario."); {@code null} para no agregar nada
	 */
	public String detalle(String consecuencia) {
		String sujeto = instructorMembershipId == null
				? "El profesional"
				: "El instructor " + (instructorNombre == null ? "#" + instructorMembershipId : instructorNombre);
		String franja = franja();
		String donde = mismaSede || consultorioNombre == null
				? (mismaSede ? "en esta sede" : "en otra sede")
				: "en la sede " + consultorioNombre;
		String causa = switch (tipo) {
			case TURNO -> sujeto + " ya tiene un turno " + franja + " " + donde + ".";
			case CLASE -> sujeto + " ya tiene una clase " + franja + " " + donde + ".";
			case BLOQUEO -> sujeto + " tiene la agenda bloqueada " + franja + " " + donde + ".";
			case AUSENCIA -> sujeto + " tiene una ausencia registrada " + franja + " " + donde + ".";
			case CIERRE_DE_SEDE -> (consultorioNombre == null ? "La sede" : "La sede " + consultorioNombre)
					+ " esta cerrada " + franja + ".";
			case DISPONIBILIDAD -> sujeto + " ya tiene horario de atencion " + franja + " " + donde + ".";
		};
		return consecuencia == null || consecuencia.isBlank() ? causa : causa + " " + consecuencia;
	}

	private String franja() {
		if (desde == null || hasta == null) {
			return "en ese horario";
		}
		ZoneId z = zona == null ? ZoneId.of("UTC") : ZoneId.of(zona);
		var inicio = desde.atZone(z);
		var fin = hasta.atZone(z);
		return "el " + FECHA.format(inicio) + " de " + HORA.format(inicio) + " a "
				+ (fin.toLocalDate().isAfter(inicio.toLocalDate()) && fin.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
						? "24:00"
						: HORA.format(fin));
	}

	/**
	 * Las extensiones del Problem Detail, con nombres estables. Los ausentes no viajan.
	 *
	 * <p>Las de 0.156.0 se conservan con su nombre ({@code conflictoTipo}, {@code conflictoConsultorioId},
	 * {@code conflictoInicio}, {@code conflictoFin}, {@code turnoEnConflictoId}, {@code claseEnConflictoId});
	 * se suman {@code conflictoConsultorioNombre}, {@code conflictoMismaSede},
	 * {@code excepcionEnConflictoId}, {@code instructorMembershipId} e {@code instructorNombre}.
	 */
	public Map<String, Object> propiedades() {
		Map<String, Object> propiedades = new LinkedHashMap<>();
		propiedades.put("conflictoTipo", tipo.name());
		if (eventoId != null) {
			switch (tipo) {
				case TURNO -> propiedades.put("turnoEnConflictoId", eventoId);
				case CLASE -> propiedades.put("claseEnConflictoId", eventoId);
				case BLOQUEO, AUSENCIA, CIERRE_DE_SEDE -> propiedades.put("excepcionEnConflictoId", eventoId);
				case DISPONIBILIDAD -> { }
			}
		}
		if (consultorioId != null) {
			propiedades.put("conflictoConsultorioId", consultorioId);
		}
		if (consultorioNombre != null) {
			propiedades.put("conflictoConsultorioNombre", consultorioNombre);
		}
		propiedades.put("conflictoMismaSede", mismaSede);
		if (desde != null) {
			propiedades.put("conflictoInicio", desde.toString());
		}
		if (hasta != null) {
			propiedades.put("conflictoFin", hasta.toString());
		}
		if (instructorMembershipId != null) {
			propiedades.put("instructorMembershipId", instructorMembershipId);
		}
		if (instructorNombre != null) {
			propiedades.put("instructorNombre", instructorNombre);
		}
		return propiedades;
	}
}
