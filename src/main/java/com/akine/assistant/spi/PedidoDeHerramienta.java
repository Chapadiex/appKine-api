package com.akine.assistant.spi;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Los parametros ya resueltos de una consulta. Nada de esto sale crudo del modelo: el periodo llega
 * convertido a dias de la sede y la persona, como id.
 *
 * <p>{@code desde} y {@code hasta} son dias de la sede, <b>los dos inclusive</b>. Para un servicio
 * que toma el fin exclusivo estan {@link #hastaExclusivo()} y, como instantes en la zona de la sede,
 * {@link #desdeInstante()} y {@link #hastaInstante()}: la conversion vive aca y no en cada
 * herramienta (la que la hizo a mano perdio el ultimo dia, bitacora 15).
 *
 * @param texto    lo que hay que buscar: el nombre de una persona para
 *                 {@link Intencion#BUSCAR_PERSONA}, la actividad para {@link Intencion#ASISTENTES_CLASE};
 *                 {@code null} si no aplica
 * @param pregunta la pregunta original: la herramienta de clases la usa para elegir el titulo real
 *                 mas largo que aparece en ella ("yoga para embarazadas" y no "yoga")
 * @param reporte  el codigo del reporte de M23 para {@link Intencion#REPORTE}, o {@code null}
 * @param rutaActual la pantalla del frontend desde la que se pregunto, para
 *                 {@link Intencion#COMO_SE_HACE}; {@code null} si no aplica
 */
public record PedidoDeHerramienta(
		Long personaId,
		String texto,
		String pregunta,
		LocalDate desde,
		LocalDate hasta,
		ZoneId zona,
		String reporte,
		String rutaActual) {

	/** Sin pantalla actual: las intenciones de datos no la usan. */
	public PedidoDeHerramienta(Long personaId, String texto, String pregunta, LocalDate desde, LocalDate hasta,
			ZoneId zona, String reporte) {
		this(personaId, texto, pregunta, desde, hasta, zona, reporte, null);
	}

	/** El dia siguiente a {@code hasta}: el fin del periodo como limite exclusivo. */
	public LocalDate hastaExclusivo() {
		return hasta.plusDays(1);
	}

	/** El comienzo de {@code desde} en la zona de la sede. */
	public Instant desdeInstante() {
		return desde.atStartOfDay(zona).toInstant();
	}

	/** El comienzo del dia siguiente a {@code hasta} en la zona de la sede: fin exclusivo. */
	public Instant hastaInstante() {
		return hastaExclusivo().atStartOfDay(zona).toInstant();
	}
}
