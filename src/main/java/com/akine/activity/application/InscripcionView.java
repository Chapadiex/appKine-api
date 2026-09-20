package com.akine.activity.application;

import com.akine.activity.domain.InscripcionClase;

import java.time.Instant;

/**
 * Una inscripcion, tal como sale del backend.
 *
 * <p><b>No lleva nombre ni documento</b>: esta proyeccion la devuelven las operaciones de escritura,
 * que ya saben a quien inscribieron. Los datos de persona viajan solo en {@link ParticipanteView},
 * que es lo que devuelve el endpoint protegido por {@code inscripcion:read}.
 *
 * @param posicionEspera posicion de ingreso a la cola, o {@code null} si entro con lugar.
 *                       <b>Se conserva despues de promover</b>: es la prueba de en que orden habia
 *                       llegado (CA-M28-004-06 pide prioridad reproducible)
 */
public record InscripcionView(
		long id,
		long claseId,
		long personaId,
		String estado,
		Integer posicionEspera,
		Instant inscriptoEn,
		Instant confirmadaEn,
		Instant promovidaEn,
		String motivoCancelacion,
		Instant canceladaEn,
		long version) {

	public static InscripcionView de(InscripcionClase inscripcion) {
		return new InscripcionView(
				inscripcion.getId(),
				inscripcion.getClaseId(),
				inscripcion.getPersonaId(),
				inscripcion.getEstado().name(),
				inscripcion.getPosicionEspera(),
				inscripcion.getInscriptoEn(),
				inscripcion.getConfirmadaEn(),
				inscripcion.getPromovidaEn(),
				inscripcion.getMotivoCancelacion(),
				inscripcion.getCanceladaEn(),
				inscripcion.getVersion());
	}
}
