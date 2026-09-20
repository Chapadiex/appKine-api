package com.akine.activity.application;

import java.time.Instant;
import java.util.List;

/**
 * El detalle operativo de una clase: cabecera mas una pagina de participantes (RF-M28-009).
 *
 * <p><b>Paginado y no completo</b> por RNF-M28-003 y CA-M28-009-06, y con los nombres resueltos en
 * un solo batch contra {@code person.spi}: de a uno, la pantalla haria tantas consultas como
 * participantes, que crece justo con lo exitosa que sea la clase.
 *
 * @param sinResolver cuantos tienen lugar y todavia no tienen resultado. Es lo que el cierre va a
 *                    marcar como ausentes, y mostrarlo antes de cerrar es lo que evita que el
 *                    mostrador cierre sin darse cuenta
 */
public record DetalleOperativoView(
		long claseId,
		long ofertaId,
		String titulo,
		Instant inicio,
		Instant fin,
		String estado,
		Instant iniciadaEn,
		Instant cerradaEn,
		Long profesionalMembershipId,
		int capacidad,
		int capacidadEfectiva,
		int ocupados,
		int enEspera,
		int presentes,
		int ausentes,
		int sinResolver,
		List<ParticipanteOperativoView> contenido,
		int page,
		int size,
		long totalElements) {
}
