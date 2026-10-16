package com.akine.encounter.spi;

import java.time.Instant;

/**
 * La atencion clinica de un participante, vista desde afuera de {@code encounter} (AKINE-08.05).
 *
 * <p><b>No viaja nada clinico</b>: ni evaluacion, ni evolucion, ni nota. Viajan ids, estado y
 * numeros, que es lo que un panel de clase necesita para mostrar progreso sin mezclar datos de
 * pacientes (RF-M14-013). El contenido se lee por la Sesion, con su permiso y su auditoria.
 *
 * @param numeroSesion correlativo por Historia Clinica, {@code null} mientras esta abierta
 * @param numeroEnCaso correlativo dentro del Caso (RN-M14-010), {@code null} mientras esta abierta
 * @param yaExistia    {@code true} cuando este pedido no creo nada porque la Sesion ya estaba. Es
 *                     el estado de ESTA llamada, no de la fila
 */
public record AtencionDeParticipante(
		long sesionId,
		long consultorioId,
		long claseId,
		long participacionId,
		long derivacionId,
		long historiaClinicaId,
		long casoId,
		long profesionalMembershipId,
		String estado,
		Integer numeroSesion,
		Integer numeroEnCaso,
		Instant iniciadaEn,
		Instant cerradaEn,
		long version,
		boolean yaExistia) {
}
