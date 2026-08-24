package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Set;

/**
 * Permisos efectivos de la cuenta autenticada en su contexto activo.
 *
 * <h2>Es insumo de UX, no un mecanismo de seguridad</h2>
 *
 * <p>Lo que el frontend hace con esto es ocultar o deshabilitar acciones. Cada pantalla que
 * oculte esta igualmente protegida en el backend, y el test que lo prueba es de integracion del
 * backend: si esta lista mintiera, no se abriria ninguna puerta — se veria un boton que despues
 * responde 403.
 *
 * <h2>Por que un objeto con un array y no el array pelado</h2>
 *
 * <p>Un schema nombrado es lo que hace que el generador de clientes produzca un tipo y un
 * metodo de servicio en vez de un {@code string[]} anonimo. Ademas deja lugar a agregar campos
 * —vencimiento del contexto, por ejemplo— sin romper el contrato.
 *
 * <p>Los codigos vienen <b>ya resueltos</b> por el evaluador: rol base, permisos adicionales
 * vigentes y acceso de soporte, todo aplanado. El cliente no compone nada.
 */
@Schema(description = "Permisos efectivos de la cuenta en su contexto de trabajo activo")
public record EffectivePermissionsResponse(

		@Schema(
				description = "Codigos del catalogo que la cuenta tiene vigentes en este contexto, "
						+ "en orden alfabetico estable",
				example = "[\"auditoria:read\", \"colaborador:manage\", \"colaborador:read\"]")
		List<String> permissions) {

	/** Ordena para que dos respuestas iguales se serialicen igual: un {@code Set} no lo garantiza. */
	public static EffectivePermissionsResponse of(Set<String> permisos) {
		return new EffectivePermissionsResponse(permisos.stream().sorted().toList());
	}
}
