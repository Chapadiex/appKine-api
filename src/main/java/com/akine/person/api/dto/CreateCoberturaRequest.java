package com.akine.person.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Alta de una cobertura del paciente (RF-M08-001).
 *
 * <p><b>No lleva financiadorId</b>, y no es un olvido: el financiador sale de la referencia
 * congelada del plan. Aceptarlo permitiria un alta cuyo financiador contradice al del plan, y esa
 * contradiccion es mejor inexpresable que validada.
 *
 * <p>Tampoco lleva nada del texto del plan —nombre, copago, si exige autorizacion—: eso lo copia
 * el backend del catalogo en el momento de firmar y el cliente no lo puede influir. Dejarselo
 * mandar volveria la copia congelada un dato del cliente.
 */
@Schema(description = "Datos para agregar una cobertura a un paciente")
public record CreateCoberturaRequest(

		@Schema(
				description = "PARTICULAR o FINANCIADA. PARTICULAR es la ausencia de plan y no "
						+ "necesita ningun dato de financiador: siempre esta disponible "
						+ "(RN-M08-001)",
				example = "FINANCIADA",
				allowableValues = {"PARTICULAR", "FINANCIADA"},
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El tipo de cobertura es obligatorio")
		// Se valida aca y no dejando que Jackson deserialice el enum: un valor desconocido tipado
		// sale como el error generico de deserializacion, que dice "no se pudo leer el cuerpo" y
		// no cual campo esta mal.
		@Pattern(regexp = "PARTICULAR|FINANCIADA",
				message = "El tipo de cobertura tiene que ser PARTICULAR o FINANCIADA")
		String tipo,

		@Schema(
				description = "Plan de cobertura a congelar. Obligatorio si tipo es FINANCIADA y "
						+ "prohibido si es PARTICULAR. Un plan dado de baja, de un financiador dado "
						+ "de baja, ajeno, inexistente, o fuera de vigencia el dia vigenciaDesde "
						+ "responde 409 plan-no-seleccionable: los cinco casos igual, para no "
						+ "convertir este endpoint en un oraculo del catalogo ajeno",
				example = "88",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Long planId,

		@Schema(
				description = "Numero de credencial del paciente ante el financiador. Obligatorio "
						+ "si el plan congelado exigia credencial",
				example = "62000123456",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 64, message = "El numero de afiliado no puede superar los 64 caracteres")
		String numeroAfiliado,

		@Schema(
				description = "Ultimo dia de validez de la credencial, INCLUSIVE. Es METADATA: una "
						+ "credencial vencida NO invalida la cobertura, se informa como alerta",
				example = "2027-12-31",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate credencialVigenciaHasta,

		@Schema(
				description = "Primer dia en que la cobertura aplica. Es tambien el dia contra el "
						+ "que se evalua si el plan se puede elegir",
				example = "2026-09-01",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La vigencia de la cobertura necesita una fecha de inicio")
		LocalDate vigenciaDesde,

		@Schema(
				description = "ULTIMO dia en que aplica, INCLUSIVE. Null = sin fin previsto. Puede "
						+ "coincidir con vigenciaDesde",
				example = "2027-08-31",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta,

		@Schema(
				description = "Cobertura preferida del paciente. Ausente = false. Como maximo una "
						+ "activa con vigencias solapadas: si ya hay otra, 409",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean principal,

		@Schema(description = "Notas administrativas. NUNCA contenido clinico",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 500, message = "Las observaciones no pueden superar los 500 caracteres")
		String observaciones) {
}
