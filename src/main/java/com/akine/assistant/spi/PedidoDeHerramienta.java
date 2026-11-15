package com.akine.assistant.spi;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Los parametros ya resueltos de una consulta. Nada de esto sale crudo del modelo: el periodo llega
 * convertido a fechas con la zona de la sede y la persona, como id.
 *
 * <p>{@code desde} y {@code hasta} son dias de la sede, ambos inclusive; {@code desdeInstante} y
 * {@code hastaInstante} son los mismos limites como instantes, con {@code hastaInstante} exclusivo.
 *
 * @param texto    lo que hay que buscar: el nombre de una persona para
 *                 {@link Intencion#BUSCAR_PERSONA}, la actividad para {@link Intencion#ASISTENTES_CLASE};
 *                 {@code null} si no aplica
 * @param pregunta la pregunta original: la herramienta de clases la usa para elegir el titulo real
 *                 mas largo que aparece en ella ("yoga para embarazadas" y no "yoga")
 * @param reporte  el codigo del reporte de M23 para {@link Intencion#REPORTE}, o {@code null}
 */
public record PedidoDeHerramienta(
		Long personaId,
		String texto,
		String pregunta,
		LocalDate desde,
		LocalDate hasta,
		Instant desdeInstante,
		Instant hastaInstante,
		ZoneId zona,
		String reporte) {
}
