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
 *
 * <p>{@code CIERRE_DE_SEDE_*} comparten el {@code event_type} con {@code EXCEPCION_*}: son la misma
 * excepcion, pero de la sede entera y sin profesional. Se distinguen por la entidad que el historial
 * resolvio que afecta a la persona, no por el tipo de evento.
 */
public enum TipoDeCambioDeAgenda {

	HORARIO_ALTA("DISPONIBILIDAD_BLOQUE_CREATED", Entidad.HORARIO),
	HORARIO_EDICION("DISPONIBILIDAD_BLOQUE_UPDATED", Entidad.HORARIO),
	HORARIO_BAJA("DISPONIBILIDAD_BLOQUE_DEACTIVATED", Entidad.HORARIO),
	EXCEPCION_ALTA("DISPONIBILIDAD_EXCEPCION_CREATED", Entidad.EXCEPCION),
	EXCEPCION_EDICION("DISPONIBILIDAD_EXCEPCION_UPDATED", Entidad.EXCEPCION),
	EXCEPCION_BAJA("DISPONIBILIDAD_EXCEPCION_DEACTIVATED", Entidad.EXCEPCION),
	CIERRE_DE_SEDE_ALTA("DISPONIBILIDAD_EXCEPCION_CREATED", Entidad.CIERRE_DE_SEDE),
	CIERRE_DE_SEDE_EDICION("DISPONIBILIDAD_EXCEPCION_UPDATED", Entidad.CIERRE_DE_SEDE),
	CIERRE_DE_SEDE_BAJA("DISPONIBILIDAD_EXCEPCION_DEACTIVATED", Entidad.CIERRE_DE_SEDE),
	TURNO_RESERVA("TURNO_RESERVADO", Entidad.TURNO),
	TURNO_SOBRETURNO("TURNO_SOBRETURNO", Entidad.TURNO),
	TURNO_CONFIRMACION("TURNO_CONFIRMADO", Entidad.TURNO),
	TURNO_CANCELACION("TURNO_CANCELADO", Entidad.TURNO),
	TURNO_AUSENCIA("TURNO_AUSENTE", Entidad.TURNO),
	TURNO_REPROGRAMACION("TURNO_REPROGRAMADO", Entidad.TURNO),
	TURNO_TRASLADO("TURNO_TRASLADADO", Entidad.TURNO),
	TURNO_OBSERVACIONES("TURNO_OBSERVACIONES", Entidad.TURNO);

	/** Sobre que es el cambio. */
	public enum Entidad { HORARIO, EXCEPCION, CIERRE_DE_SEDE, TURNO }

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

	/**
	 * @param deSede {@code true} si la fila es de una excepcion de la sede entera (sin profesional)
	 */
	static Optional<TipoDeCambioDeAgenda> deEvento(String eventType, boolean deSede) {
		return Arrays.stream(values())
				.filter(tipo -> tipo.eventType.equals(eventType))
				.filter(tipo -> (tipo.entidad == Entidad.CIERRE_DE_SEDE) == deSede)
				.findFirst();
	}

	/** Los eventos de la agenda PROPIA del profesional. Vacio o {@code null} = todos. */
	static Set<String> eventTypes(Collection<TipoDeCambioDeAgenda> tipos) {
		return elegidos(tipos).stream().filter(tipo -> tipo.entidad != Entidad.CIERRE_DE_SEDE)
				.map(TipoDeCambioDeAgenda::eventType).collect(Collectors.toSet());
	}

	/** Los eventos de los cierres de sede pedidos. Vacio o {@code null} = los tres. */
	static Set<String> eventTypesDeCierreDeSede(Collection<TipoDeCambioDeAgenda> tipos) {
		return elegidos(tipos).stream().filter(tipo -> tipo.entidad == Entidad.CIERRE_DE_SEDE)
				.map(TipoDeCambioDeAgenda::eventType).collect(Collectors.toSet());
	}

	private static Collection<TipoDeCambioDeAgenda> elegidos(Collection<TipoDeCambioDeAgenda> tipos) {
		return tipos == null || tipos.isEmpty() ? Arrays.asList(values()) : tipos;
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
