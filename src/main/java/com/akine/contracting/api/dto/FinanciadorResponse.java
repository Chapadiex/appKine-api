package com.akine.contracting.api.dto;

import com.akine.contracting.application.FinanciadorView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un financiador del catalogo de la organizacion (M15).
 *
 * <p>No lleva {@code organizationId}: es siempre la del contexto del request, y devolverla
 * invitaria a que un cliente creyera que puede pedir otra.
 */
@Schema(description = "Financiador del catalogo de la organizacion")
public record FinanciadorResponse(

		@Schema(description = "Identificador del financiador", example = "31")
		long id,

		@Schema(
				description = "Clave estable con la que las coberturas lo referencian. No cambia "
						+ "nunca: renombrar es cambiar nombre, no codigo",
				example = "OSDE")
		String codigo,

		@Schema(description = "Nombre visible", example = "OSDE Binario")
		String nombre,

		@Schema(
				description = "Que clase de financiador es. Clasifica, no habilita",
				example = "PREPAGA",
				allowableValues = {"OBRA_SOCIAL", "PREPAGA", "ART", "MUTUAL", "ORGANISMO_PUBLICO",
						"OTRO"})
		String tipo,

		@Schema(description = "CUIT normalizado a 11 digitos, o null", example = "30712345678")
		String cuit,

		@Schema(description = "Contacto administrativo del financiador")
		String emailContacto,

		@Schema(description = "Telefono administrativo del financiador")
		String telefonoContacto,

		@Schema(description = "Notas administrativas. Nunca contenido clinico")
		String observaciones,

		@Schema(
				description = "Ciclo de vida. Un financiador INACTIVO se sigue leyendo con 200: "
						+ "los historicos que lo referencian tienen que seguir resolviendo",
				example = "ACTIVO",
				allowableValues = {"ACTIVO", "INACTIVO"})
		String estado,

		@Schema(description = "Instante UTC de la baja logica. Null mientras este vigente")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja. Null mientras este vigente")
		String deactivationReason,

		@Schema(description = "Version a reenviar para editar", example = "0")
		long version) {

	public static FinanciadorResponse de(FinanciadorView view) {
		return new FinanciadorResponse(
				view.id(),
				view.codigo(),
				view.nombre(),
				view.tipo(),
				view.cuit(),
				view.emailContacto(),
				view.telefonoContacto(),
				view.observaciones(),
				view.estado(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version());
	}
}
