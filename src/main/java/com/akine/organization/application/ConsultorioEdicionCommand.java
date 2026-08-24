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
		long expectedVersion) {
}
