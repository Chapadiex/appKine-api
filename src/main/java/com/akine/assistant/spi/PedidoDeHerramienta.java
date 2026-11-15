package com.akine.assistant.spi;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Los parametros ya resueltos de una consulta. Nada de esto sale crudo del modelo: el periodo llega
 * convertido a fechas con la zona de la organizacion y la persona, como id.
 *
 * <p>{@code desde} y {@code hasta} son dias de la sede, ambos inclusive; {@code desdeInstante} y
 * {@code hastaInstante} son los mismos limites como instantes, con {@code hastaInstante} exclusivo.
 *
 * @param texto   el texto que hace falta buscar: el nombre de una persona para
 *                {@link Intencion#BUSCAR_PERSONA}, la pregunta original para resolver el titulo de
 *                una clase; {@code null} si no aplica
 * @param reporte el codigo del reporte de M23 para {@link Intencion#REPORTE}, o {@code null}
 */
public record PedidoDeHerramienta(
		Long personaId,
		Long claseId,
		String texto,
		LocalDate desde,
		LocalDate hasta,
		Instant desdeInstante,
		Instant hastaInstante,
		ZoneId zona,
		String reporte) {
}
