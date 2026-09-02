package com.akine.contracting.application;

import com.akine.contracting.domain.ModalidadConvenio;

import java.time.LocalDate;

/**
 * Edicion parcial de un convenio, incluido el cierre de vigencia (RF-M16-002 y RF-M16-003).
 *
 * <p>Lo que llega en {@code null} no se toca. <b>Cerrar la vigencia es esta operacion</b>, mandando
 * {@code vigenciaHasta}: el convenio queda ACTIVO y consultable, y lo unico que cambia es que deja
 * de aplicarse despues de esa fecha. Dar de baja es otra cosa y exige motivo.
 *
 * <p>Ni el codigo, ni la sede, ni el financiador, ni el plan estan aca: son la identidad del
 * convenio, y lo que ya se liquido bajo el los referencia.
 *
 * @param expectedVersion version leida. Una version vieja produce 409 en vez de pisar en silencio
 */
public record ConvenioEdicionCommand(
		String nombre,
		ModalidadConvenio modalidad,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		Boolean requiereOrden,
		Boolean requiereAutorizacion,
		Boolean requiereCredencial,
		Integer limiteSesionesMensual,
		String documentacionRequerida,
		String observaciones,
		long expectedVersion) {
}
