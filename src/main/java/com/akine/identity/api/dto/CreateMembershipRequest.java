package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta directa de un colaborador sobre una cuenta que ya existe.
 *
 * <h2>Lo que este cuerpo NO lleva, y por que</h2>
 *
 * <p><b>No lleva {@code organizationId}.</b> El tenant sale del contexto que
 * {@code TenantContextFilter} ya revalido contra la base. Aceptarlo en el cuerpo dejaria que el
 * cliente eligiera en que organizacion vincular a alguien, que es la definicion de una fuga
 * cross-tenant (RN-M01-003).
 *
 * <p><b>No lleva {@code accountId}.</b> Un administrador conoce el email de la persona, no su
 * id interno. Recibir el id seria dejar que el cliente elija a quien vincular por numero, y
 * ademas convertiria un error de tipeo en un vinculo silencioso sobre otra persona.
 *
 * <p><b>No lleva contrasena ni crea cuentas.</b> El alta directa vincula a alguien que ya se
 * registro. Si el email no tiene cuenta, la respuesta es 404: no se crea nada.
 *
 * @param email         direccion de la persona a vincular. Se normaliza igual que en el alta
 *                      —recorte y minusculas—, asi que las mayusculas no importan
 * @param consultorioId sede a la que se acota el vinculo, o {@code null} para alcance
 *                      organizacion. Con un valor, el actor tiene que poder administrar ESA
 *                      sede: quien administra la sede A no da de alta en la sede B
 * @param roleCode      rol de la matriz de permisos. {@code PLATFORM_ADMIN} no es un valor
 *                      legal aca (ADR-0020): ese rol se otorga por {@code /platform/roles}
 * @param reason        motivo declarado del alta. Obligatorio y queda en la auditoria: sin el,
 *                      dentro de seis meses nadie puede responder por que esa persona tiene
 *                      acceso a los datos del centro
 */
@Schema(description = "Datos del vinculo a crear entre una cuenta existente y la organizacion")
public record CreateMembershipRequest(

		@Schema(
				description = "Email de la cuenta a vincular. Tiene que existir",
				example = "kine@centro.test",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El email de la persona a vincular es obligatorio")
		@Email(message = "El email no tiene un formato valido")
		@Size(max = 320, message = "El email no puede superar los 320 caracteres")
		String email,

		@Schema(
				description = "Sede del vinculo. Ausente o null para alcance organizacion",
				example = "3")
		Long consultorioId,

		@Schema(
				description = "Rol de la matriz de permisos",
				example = "CONSULTORIO_ADMIN",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El rol del colaborador es obligatorio")
		@Size(max = 64, message = "El codigo de rol no puede superar los 64 caracteres")
		String roleCode,

		@Schema(
				description = "Motivo declarado del alta. Queda en la auditoria",
				example = "Incorporacion del kinesiologo de la sede centro",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo del alta es obligatorio")
		@Size(max = 500, message = "El motivo no puede superar los 500 caracteres")
		String reason) {
}
