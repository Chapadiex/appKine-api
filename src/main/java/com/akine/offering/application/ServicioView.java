package com.akine.offering.application;

import com.akine.offering.domain.Servicio;

import java.time.Instant;

/**
 * Proyeccion de lectura de un Servicio. Es lo unico que cruza el borde del servicio de aplicacion:
 * la entity nunca sale (AGENT.md seccion 4, regla 6).
 *
 * <p>No lleva {@code organizationId}: lleva {@code alcance}. Quien mira solo ve el catalogo global
 * y los servicios de su propia organizacion, asi que el id del duenio no le dice nada que el alcance
 * no diga. Quien busque "como presta este centro este servicio" esta buscando una Oferta (regla
 * maestra 14).
 *
 * @param alcance  {@code GLOBAL} (catalogo de la plataforma) u {@code ORGANIZACION} (propio del centro)
 * @param estado   DERIVADO de {@code active}, no una columna. Ver {@link Servicio}
 * @param version  la que hay que reenviar para editar
 */
public record ServicioView(
		long id,
		String codigo,
		String nombre,
		String descripcion,
		String naturaleza,
		String modalidadDefault,
		boolean requiereCasoClinicoDefault,
		boolean generaRegistroClinicoDefault,
		String alcance,
		String estado,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	public static ServicioView de(Servicio servicio) {
		return new ServicioView(
				servicio.getId(),
				servicio.getCodigo(),
				servicio.getNombre(),
				servicio.getDescripcion(),
				servicio.getNaturaleza().name(),
				servicio.getModalidadDefault().name(),
				servicio.isRequiereCasoClinicoDefault(),
				servicio.isGeneraRegistroClinicoDefault(),
				alcanceDe(servicio),
				servicio.isActive() ? "ACTIVO" : "INACTIVO",
				servicio.getDeletedAt(),
				servicio.getDeactivationReason(),
				servicio.getVersion());
	}

	static String alcanceDe(Servicio servicio) {
		return servicio.isGlobal() ? "GLOBAL" : "ORGANIZACION";
	}
}
