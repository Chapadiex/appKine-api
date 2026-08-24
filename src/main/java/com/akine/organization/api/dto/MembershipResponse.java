package com.akine.organization.api.dto;

import com.akine.organization.application.MembershipView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un vinculo entre una cuenta y la organizacion, tal como lo ve el cliente.
 *
 * <p>Se construye desde {@link MembershipView} y nunca desde la entity: las entities no cruzan
 * el borde del service (AGENT.md sec. 4) y publicar una ataria el contrato al modelo fisico.
 *
 * <p><b>Trae el nombre y el email de la cuenta, no solo su id.</b> Con {@code accountId} a secas
 * la tabla de colaboradores dice "Cuenta 100" y no hay endpoint que resuelva ese numero a una
 * persona. Resolver {@code accountId -> nombre} dentro del tenant en el que el actor ya tiene
 * {@code colaborador:read} no revela nada que no pudiera ver de todos modos; lo que sigue sin
 * existir —y no puede existir— es el camino inverso, {@code email -> cuenta}, que si seria un
 * padron (ADR-0018, {@code AccountAdminController}).
 *
 * <p><b>Las revocadas tambien se publican.</b> Quien administra tiene que poder ver quien estuvo,
 * quien lo desvinculo y por que; para eso existe la baja logica. El cliente distingue por
 * {@code estado}, no por ausencia.
 */
@Schema(description = "Vinculo de una cuenta con la organizacion")
public record MembershipResponse(

		@Schema(description = "Identificador del vinculo", example = "42")
		long id,

		@Schema(description = "Organizacion a la que pertenece el vinculo", example = "7")
		long organizationId,

		@Schema(description = "Sede a la que esta acotado, o null si alcanza toda la organizacion",
				example = "3")
		Long consultorioId,

		@Schema(description = "Cuenta vinculada", example = "18")
		long accountId,

		@Schema(description = "Nombre completo de la cuenta vinculada. null si la cuenta no se "
				+ "pudo resolver",
				example = "Ana Perez")
		String accountName,

		@Schema(description = "Email de la cuenta vinculada. null si la cuenta no se pudo "
				+ "resolver",
				example = "ana.perez@ejemplo.test")
		String accountEmail,

		@Schema(description = "Rol asignado en la matriz de permisos",
				example = "PROFESIONAL",
				allowableValues = {"ORG_ADMIN", "CONSULTORIO_ADMIN", "PROFESIONAL", "ADMINISTRATIVO"})
		String roleCode,

		@Schema(description = "Estado del vinculo",
				example = "ACTIVA",
				allowableValues = {"ACTIVA", "SUSPENDIDA", "REVOCADA"})
		String estado,

		@Schema(description = "Indica si es el vinculo de quien creo la organizacion",
				example = "false")
		boolean founder,

		@Schema(description = "Momento desde el que el vinculo es valido")
		Instant validFrom,

		@Schema(description = "Momento hasta el que el vinculo es valido, o null si no vence")
		Instant validUntil,

		@Schema(description = "Baja logica: false cuando el vinculo ya no cuenta", example = "true")
		boolean active,

		@Schema(description = "Cuenta que revoco el vinculo, si fue revocado", example = "4")
		Long revokedByAccountId,

		@Schema(description = "Motivo declarado de la revocacion, si fue revocado")
		String revokedReason) {

	public static MembershipResponse from(MembershipView view) {
		return new MembershipResponse(
				view.id(),
				view.organizationId(),
				view.consultorioId(),
				view.accountId(),
				view.accountName(),
				view.accountEmail(),
				view.roleCode(),
				view.estado(),
				view.founder(),
				view.validFrom(),
				view.validUntil(),
				view.active(),
				view.revokedByAccountId(),
				view.revokedReason());
	}
}
