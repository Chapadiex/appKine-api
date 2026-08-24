package com.akine.organization.application;

import com.akine.organization.domain.Consultorio;

import java.time.Instant;

/**
 * Proyeccion de lectura de un consultorio.
 *
 * <p>Nacio minima en 01.01 —lo justo para armar el contexto de trabajo— y AKINE-02.01 la
 * expande con la configuracion de la sede. Se agregaron campos; ninguno de los cuatro
 * originales cambio de nombre ni de significado, asi que el cambio es aditivo tambien para el
 * contrato.
 *
 * <p>{@code estado} es DERIVADO y no una columna: ver el javadoc de {@link Consultorio}.
 *
 * @param deletedAt          instante UTC de la baja, o {@code null} si la sede esta activa
 * @param deactivationReason motivo declarado de la baja (RF-M03-004), o {@code null}
 * @param version            la que hay que reenviar para editar. Sin ella dos ediciones
 *                           concurrentes se pisan en silencio
 */
public record ConsultorioView(
		long id,
		long organizationId,
		String name,
		String timezone,
		int slotMinutes,
		String legalName,
		String taxId,
		String addressLine,
		String phone,
		String contactEmail,
		boolean active,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	public static ConsultorioView de(Consultorio consultorio) {
		return new ConsultorioView(
				consultorio.getId(),
				consultorio.getOrganizationId(),
				consultorio.getName(),
				consultorio.getTimezone(),
				consultorio.getSlotMinutes(),
				consultorio.getLegalName(),
				consultorio.getTaxId(),
				consultorio.getAddressLine(),
				consultorio.getPhone(),
				consultorio.getContactEmail(),
				consultorio.isActive(),
				consultorio.getDeletedAt(),
				consultorio.getDeactivationReason(),
				consultorio.getVersion());
	}

	/**
	 * Estado de la sede, calculado.
	 *
	 * <p>Dos valores y ninguna columna. Un tercer estado —"suspendido por refaccion"— no lo pide
	 * ningun RF de M03 y es una decision abierta (D-3).
	 */
	public String estado() {
		return active ? "ACTIVO" : "INACTIVO";
	}
}
