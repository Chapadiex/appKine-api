package com.akine.organization.api.dto;

import com.akine.organization.domain.SolicitudDePrueba;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Pedido de prueba gratis, como lo ve la plataforma")
public record SolicitudDePruebaResponse(
		long id,
		String nombre,
		String apellido,
		String email,
		String telefono,
		String nombreCentro,
		String ciudad,
		boolean kinesiologia,
		boolean actividades,
		SolicitudDePrueba.Sedes sedes,
		boolean interesaIa,
		String comentario,
		SolicitudDePrueba.Estado estado,
		@Schema(description = "Motivo, si se descarto") String motivoDescarte,
		@Schema(description = "Centro creado, si se aprobo") Long organizationId,
		@Schema(description = "Cuando llego") Instant recibidaEn,
		@Schema(description = "Cuando se resolvio") Instant resueltaEn,
		long version) {

	public static SolicitudDePruebaResponse from(SolicitudDePrueba s) {
		return new SolicitudDePruebaResponse(
				s.getId(), s.getNombre(), s.getApellido(), s.getEmail(), s.getTelefono(),
				s.getNombreCentro(), s.getCiudad(), s.isKinesiologia(), s.isActividades(),
				s.getSedes(), s.isInteresaIa(), s.getComentario(), s.getEstado(),
				s.getMotivoDescarte(), s.getOrganizationId(), s.getCreatedAt(), s.getResueltaEn(),
				s.getVersion());
	}
}
