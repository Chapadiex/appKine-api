package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Invitacion a colaborar (RF-M05-001).
 *
 * <h2>En que se diferencia de {@link CreateMembershipRequest}</h2>
 *
 * <p>El alta directa exige que la persona <b>ya tenga cuenta</b> y la vincula en el acto. Esta
 * le escribe: puede no tener cuenta, y el vinculo nace cuando ella acepta. Las dos conviven a
 * proposito — la primera es un click para quien ya esta en AKINE.
 *
 * <p><b>No lleva motivo</b>, y es la otra diferencia. El alta directa lo exige porque es una
 * decision unilateral: alguien le dio acceso a otro a los datos del centro y tiene que responder
 * por que. Una invitacion no da acceso a nada por si sola —lo da la aceptacion, que es un acto
 * de la otra persona y queda auditado con su propio evento—, asi que pedir un motivo aca seria
 * pedir que se justifique un correo.
 *
 * <p>Tampoco lleva {@code organizationId}, por lo mismo de siempre: el tenant sale del contexto
 * ya revalidado, y aceptarlo del cliente seria dejarlo elegir a que organizacion invitar.
 *
 * @param email         direccion a la que se invita. Se normaliza igual que en el registro
 * @param consultorioId sede del vinculo propuesto, o {@code null} para alcance organizacion
 * @param roleCode      rol con el que quedaria vinculada. {@code PLATFORM_ADMIN} no es legal
 *                      aca (ADR-0020)
 */
@Schema(description = "Datos de la invitacion a emitir")
public record CreateInvitacionRequest(

		@Schema(
				description = "Email de la persona a invitar. Puede no tener cuenta todavia",
				example = "kine@centro.test",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El email de la persona a invitar es obligatorio")
		@Email(message = "El email no tiene un formato valido")
		@Size(max = 320, message = "El email no puede superar los 320 caracteres")
		String email,

		@Schema(
				description = "Sede del vinculo propuesto. Ausente o null para alcance organizacion",
				example = "3")
		Long consultorioId,

		@Schema(
				description = "Rol con el que quedaria vinculada al aceptar",
				example = "PROFESIONAL",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El rol propuesto es obligatorio")
		@Size(max = 64, message = "El codigo de rol no puede superar los 64 caracteres")
		String roleCode) {
}
