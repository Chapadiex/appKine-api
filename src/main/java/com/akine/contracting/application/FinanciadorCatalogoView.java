package com.akine.contracting.application;

import com.akine.contracting.domain.FinanciadorCatalogo;

import java.time.Instant;
import java.util.List;

/**
 * Un financiador del catalogo global (DP-43) con sus planes.
 *
 * <p>{@code financiadorAdoptadoId} y {@code actualizacionDisponible} solo tienen sentido en la
 * lectura de un CENTRO: la copia vigente que ese centro adopto, y si el catalogo cambio despues de
 * adoptarla. En la consola de plataforma viajan en {@code null} y {@code false}.
 *
 * @param actualizadoEn la ultima edicion de la entrada: de la cabecera o de cualquiera de sus
 *                      planes, incluidas altas y bajas de plan
 */
public record FinanciadorCatalogoView(
		long id,
		String codigo,
		String nombre,
		String tipo,
		String cuit,
		String emailContacto,
		String telefonoContacto,
		String observaciones,
		String estado,
		Instant deletedAt,
		String deactivationReason,
		long version,
		Instant actualizadoEn,
		List<PlanCatalogoView> planes,
		Long financiadorAdoptadoId,
		boolean actualizacionDisponible) {

	static FinanciadorCatalogoView de(
			FinanciadorCatalogo financiador,
			Instant actualizadoEn,
			List<PlanCatalogoView> planes,
			Long financiadorAdoptadoId,
			boolean actualizacionDisponible) {

		return new FinanciadorCatalogoView(
				financiador.getId(),
				financiador.getCodigo(),
				financiador.getNombre(),
				financiador.getTipo().name(),
				financiador.getCuit(),
				financiador.getEmailContacto(),
				financiador.getTelefonoContacto(),
				financiador.getObservaciones(),
				financiador.isActive() ? "ACTIVO" : "INACTIVO",
				financiador.getDeletedAt(),
				financiador.getDeactivationReason(),
				financiador.getVersion(),
				actualizadoEn,
				planes,
				financiadorAdoptadoId,
				actualizacionDisponible);
	}
}
