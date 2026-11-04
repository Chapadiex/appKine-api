package com.akine.scheduling.application;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Los cambios que muestra el historial de agenda de un profesional, cada uno con el {@code event_type}
 * de {@code audit_event} que lo registra. La fuente de verdad es la auditoria transversal: este enum
 * solo le pone nombre de pantalla.
 *
 * <p>Las ausencias, licencias y vacaciones son excepciones de tipo cierre ({@code EXCEPCION_*}), y un
 * desbloqueo es la baja de una excepcion. El cambio de profesional de un turno es una reprogramacion
 * (la vista lo marca con {@code cambioDeProfesional}).
 */
public enum TipoDeCambioDeAgenda {

	HORARIO_ALTA("DISPONIBILIDAD_BLOQUE_CREATED", Entidad.HORARIO),
	HORARIO_EDICION("DISPONIBILIDAD_BLOQUE_UPDATED", Entidad.HORARIO),
	HORARIO_BAJA("DISPONIBILIDAD_BLOQUE_DEACTIVATED", Entidad.HORARIO),
	EXCEPCION_ALTA("DISPONIBILIDAD_EXCEPCION_CREATED", Entidad.EXCEPCION),
	EXCEPCION_EDICION("DISPONIBILIDAD_EXCEPCION_UPDATED", Entidad.EXCEPCION),
	EXCEPCION_BAJA("DISPONIBILIDAD_EXCEPCION_DEACTIVATED", Entidad.EXCEPCION),
	TURNO_RESERVA("TURNO_RESERVADO", Entidad.TURNO),
	TURNO_SOBRETURNO("TURNO_SOBRETURNO", Entidad.TURNO),
	TURNO_CONFIRMACION("TURNO_CONFIRMADO", Entidad.TURNO),
	TURNO_CANCELACION("TURNO_CANCELADO", Entidad.TURNO),
	TURNO_AUSENCIA("TURNO_AUSENTE", Entidad.TURNO),
	TURNO_REPROGRAMACION("TURNO_REPROGRAMADO", Entidad.TURNO),
	TURNO_TRASLADO("TURNO_TRASLADADO", Entidad.TURNO),
	TURNO_OBSERVACIONES("TURNO_OBSERVACIONES", Entidad.TURNO);

	/** Sobre que es el cambio. */
	public enum Entidad { HORARIO, EXCEPCION, TURNO }

	private final String eventType;
	private final Entidad entidad;

	TipoDeCambioDeAgenda(String eventType, Entidad entidad) {
		this.eventType = eventType;
		this.entidad = entidad;
	}

	public String eventType() {
		return eventType;
	}

	public Entidad entidad() {
		return entidad;
	}

	static Optional<TipoDeCambioDeAgenda> deEvento(String eventType) {
		return Arrays.stream(values()).filter(tipo -> tipo.eventType.equals(eventType)).findFirst();
	}

	/** Vacio o {@code null} = todos. */
	static Set<String> eventTypes(Collection<TipoDeCambioDeAgenda> tipos) {
		Collection<TipoDeCambioDeAgenda> elegidos = tipos == null || tipos.isEmpty() ? Arrays.asList(values()) : tipos;
		return elegidos.stream().map(TipoDeCambioDeAgenda::eventType).collect(Collectors.toSet());
	}

	/**
	 * Lee un valor del parametro {@code tipo}, sin distinguir mayusculas.
	 *
	 * @throws IllegalArgumentException si no es uno del enum (400)
	 */
	public static TipoDeCambioDeAgenda leer(String valor) {
		try {
			return valueOf(valor.strip().toUpperCase(Locale.ROOT));
		} catch (RuntimeException invalido) {
			throw new IllegalArgumentException("El tipo de cambio no existe. Valores: "
					+ Arrays.toString(values()));
		}
	}
}
