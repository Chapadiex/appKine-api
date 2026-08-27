package com.akine.offering.application;

import com.akine.offering.domain.Servicio;

/**
 * Proyeccion de lectura de un Servicio del catalogo global. Es lo unico que cruza el borde del
 * servicio de aplicacion: la entity nunca sale (AGENT.md seccion 4, regla 6).
 *
 * <p>No lleva {@code organizationId} ni {@code consultorioId} porque no los tiene: un Servicio es
 * global (ADR-0023). Quien busque "como presta este centro este servicio" esta buscando una
 * Oferta, que es otra vista y otra ruta — esa es la regla maestra 14.
 *
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
		String estado,
		java.time.Instant deletedAt,
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
				servicio.isActive() ? "ACTIVO" : "INACTIVO",
				servicio.getDeletedAt(),
				servicio.getDeactivationReason(),
				servicio.getVersion());
	}
}
