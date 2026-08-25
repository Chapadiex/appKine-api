package com.akine.resource.application;

import com.akine.resource.domain.CatalogoSolicitud;

import java.time.Instant;

/**
 * Una solicitud de alta de catalogo global, tal como sale del servicio (RF-M06-005).
 *
 * <p>Lleva {@code organizationId} incluso en la vista del propio tenant, que ya lo sabe: la
 * bandeja de la plataforma es cross-tenant y necesita decir de quien es cada pedido, y tener dos
 * formas —una con tenant y otra sin— por una sola diferencia obligaria al cliente a aprender dos
 * schemas para la misma pantalla.
 */
public record CatalogoSolicitudView(

		long id,

		long organizationId,

		Long consultorioId,

		String tipo,

		String nombrePropuesto,

		String codigoPropuesto,

		String justificacion,

		String estado,

		long solicitadaPorAccountId,

		Long resueltaPorAccountId,

		Instant resueltaAt,

		String resolucionNota,

		/** Concepto global creado al aprobar, si se creo. */
		Long conceptoId,

		Instant createdAt,

		long version) {

	public static CatalogoSolicitudView de(CatalogoSolicitud solicitud) {
		return new CatalogoSolicitudView(
				solicitud.getId(),
				solicitud.getOrganizationId(),
				solicitud.getConsultorioId(),
				solicitud.getTipo().name(),
				solicitud.getNombrePropuesto(),
				solicitud.getCodigoPropuesto(),
				solicitud.getJustificacion(),
				solicitud.getEstado().name(),
				solicitud.getSolicitadaPorAccountId(),
				solicitud.getResueltaPorAccountId(),
				solicitud.getResueltaAt(),
				solicitud.getResolucionNota(),
				solicitud.getConceptoId(),
				solicitud.getCreatedAt(),
				solicitud.getVersion());
	}
}
