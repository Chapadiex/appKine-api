package com.akine.person.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Edicion de una cobertura (RF-M08-002) y cierre de su vigencia (RF-M08-003).
 *
 * <p><b>Lo que no esta aca no se puede cambiar, y esa ausencia es la regla.</b> El tipo y todo lo
 * copiado del plan —nombre, codigo, copago, si exigia autorizacion o credencial— son inmutables:
 * cambiar de plan es OTRA cobertura. Se finaliza la vigente y se agrega la nueva, porque editarla
 * en el lugar reescribiria con que cobertura se atendio al paciente el mes pasado (RN-M08-003).
 *
 * <p>La marca principal tampoco viaja aca: tiene su propia operacion, porque es un invariante
 * entre filas y no un dato de esta.
 *
 * <p>Cada campo nulo significa "no lo toques".
 */
@Schema(description = "Datos editables de una cobertura. Lo copiado del plan no se puede cambiar")
public record UpdateCoberturaRequest(

		@Schema(description = "Nuevo numero de credencial",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 64, message = "El numero de afiliado no puede superar los 64 caracteres")
		String numeroAfiliado,

		@Schema(description = "Nuevo ultimo dia de validez de la credencial, INCLUSIVE",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate credencialVigenciaHasta,

		@Schema(description = "Correccion del primer dia de vigencia",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaDesde,

		@Schema(
				description = "ULTIMO dia en que la cobertura aplica, INCLUSIVE. Mandarlo es "
						+ "FINALIZAR LA VIGENCIA (RF-M08-003): la cobertura queda ACTIVA y "
						+ "consultable, y deja de aplicar despues de esa fecha. NO es dar de baja",
				example = "2026-12-31",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta,

		@Schema(description = "Notas administrativas. NUNCA contenido clinico",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 500, message = "Las observaciones no pueden superar los 500 caracteres")
		String observaciones,

		@Schema(
				description = "Version leida por el cliente. Una version vieja responde 409 en vez "
						+ "de pisar el cambio ajeno en silencio",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		long expectedVersion) {
}
