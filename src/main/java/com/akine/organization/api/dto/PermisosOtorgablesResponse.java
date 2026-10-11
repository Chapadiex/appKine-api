package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Permisos que la matriz admite otorgar como grant a un rol de membership.
 *
 * <p>Objeto con un array y no el array pelado por lo mismo que
 * {@link EffectivePermissionsResponse}: el generador produce un tipo nombrado, y deja lugar a
 * crecer sin romper el contrato.
 */
@Schema(description = "Permisos que la matriz admite otorgar como permiso adicional a un rol")
public record PermisosOtorgablesResponse(

		@Schema(description = "Rol consultado", example = "CONSULTORIO_ADMIN")
		String roleCode,

		@Schema(description = "Codigos del catalogo otorgables a ese rol, en orden alfabetico "
				+ "estable. Lista vacia si no admite ninguno",
				example = "[\"auditoria:read-clinica\", \"hc:read\", \"hc:write\"]")
		List<String> permissionCodes) {
}
