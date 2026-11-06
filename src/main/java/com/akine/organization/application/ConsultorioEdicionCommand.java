package com.akine.organization.application;

/**
 * Edicion parcial de una sede (RF-M03-003).
 *
 * <p>Semantica de PATCH: cada campo {@code null} deja lo que estaba. Para BORRAR un campo
 * institucional se manda cadena vacia, que la entity normaliza a {@code null}.
 *
 * <p>{@code expectedVersion} es obligatoria y se compara ANTES de mutar: sin ella dos ediciones
 * concurrentes se pisan y el segundo en guardar borra el cambio del primero sin que nadie se
 * entere. Es el mismo contrato que ya tiene {@code OrganizationService.update}.
 *
 * <p><b>Cambiar la zona horaria no reinterpreta el pasado.</b> Los instantes ya guardados son
 * UTC y no se tocan; cambia como se proyectan de aca en adelante, y la UI tiene que decirlo con
 * esas palabras.
 *
 * @param addressLine direccion FISCAL de la sede (texto libre). La fisica va en {@code datos}
 * @param datos       direccion fisica, ubicacion y opciones (V140). {@code null} equivale a
 *                    {@link SedeDatosCommand#ninguno()}
 */
public record ConsultorioEdicionCommand(
		String name,
		String timezone,
		Integer slotMinutes,
		String legalName,
		String taxId,
		String addressLine,
		String phone,
		String contactEmail,
		long expectedVersion,
		SedeDatosCommand datos) {

	public ConsultorioEdicionCommand {
		datos = datos == null ? SedeDatosCommand.ninguno() : datos;
	}

	/** Edicion sin direccion ni ubicacion: la forma anterior a V140. */
	public ConsultorioEdicionCommand(
			String name,
			String timezone,
			Integer slotMinutes,
			String legalName,
			String taxId,
			String addressLine,
			String phone,
			String contactEmail,
			long expectedVersion) {
		this(name, timezone, slotMinutes, legalName, taxId, addressLine, phone, contactEmail,
				expectedVersion, null);
	}
}
