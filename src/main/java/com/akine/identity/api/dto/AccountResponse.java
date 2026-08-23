package com.akine.identity.api.dto;

import com.akine.identity.application.AccountView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Estado de una cuenta despues de una transicion administrativa.
 *
 * <p><b>Lo que NO sale, y por que.</b> Ni el hash de la contrasena, ni el contador de intentos
 * fallidos, ni el ultimo login: el primero es una credencial, y los otros dos le dirian a un
 * administrador de la organizacion A cosas sobre la actividad global de una persona que
 * tambien trabaja en B. La identidad es cross-tenant (ADR-0019) y por eso lo que se publica se
 * acota a lo que la operacion acaba de cambiar.
 *
 * <p>Se construye desde la entity, pero <b>la entity no cruza el borde</b>: lo que se
 * serializa es este record, y ArchUnit lo verifica.
 */
@Schema(description = "Cuenta despues de la transicion administrativa")
public record AccountResponse(

		@Schema(description = "Identificador de la cuenta", example = "42")
		long id,

		@Schema(description = "Direccion de correo tal como se registro",
				example = "ana.perez@ejemplo.test")
		String email,

		@Schema(description = "Nombre de la persona", example = "Ana")
		String firstName,

		@Schema(description = "Apellido de la persona", example = "Perez")
		String lastName,

		@Schema(
				description = "Estado de la cuenta despues de la transicion",
				example = "BLOQUEADA",
				allowableValues = {"PENDIENTE_ACTIVACION", "ACTIVA", "BLOQUEADA", "DESACTIVADA"})
		String status,

		@Schema(description = "Momento del bloqueo, null si no esta bloqueada",
				example = "2026-08-23T14:05:00Z")
		Instant blockedAt,

		@Schema(description = "Ultima modificacion de la cuenta", example = "2026-08-23T14:05:00Z")
		Instant updatedAt) {

	public static AccountResponse from(AccountView cuenta) {
		return new AccountResponse(
				cuenta.id(),
				cuenta.email(),
				cuenta.nombre(),
				cuenta.apellido(),
				cuenta.estado(),
				cuenta.bloqueadaEn(),
				cuenta.actualizada());
	}
}
