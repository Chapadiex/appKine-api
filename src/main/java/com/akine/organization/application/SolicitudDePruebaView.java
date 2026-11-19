package com.akine.organization.application;

import com.akine.organization.domain.SolicitudDePrueba;

import java.time.Instant;

/** Un pedido de prueba gratis, como lo ve la plataforma. La api no toca la entidad (AGENT.md sec. 4). */
public record SolicitudDePruebaView(
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
		String motivoDescarte,
		Long organizationId,
		Instant recibidaEn,
		Instant resueltaEn,
		long version) {

	static SolicitudDePruebaView de(SolicitudDePrueba s) {
		return new SolicitudDePruebaView(
				s.getId(), s.getNombre(), s.getApellido(), s.getEmail(), s.getTelefono(),
				s.getNombreCentro(), s.getCiudad(), s.isKinesiologia(), s.isActividades(),
				s.getSedes(), s.isInteresaIa(), s.getComentario(), s.getEstado(),
				s.getMotivoDescarte(), s.getOrganizationId(), s.getCreatedAt(), s.getResueltaEn(),
				s.getVersion());
	}
}
