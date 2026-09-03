package com.akine.person.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Alta de una autorizacion de financiador (RF-M17-001).
 *
 * <p><b>No lleva nada del convenio</b>, y no es un olvido: el codigo, el nombre y las tres
 * exigencias que el convenio tenia ese dia los copia el backend de {@code contracting} en el
 * momento de registrar, y el cliente no los puede influir. Dejarselos mandar volveria la copia
 * congelada un dato del cliente.
 *
 * <p>Tampoco lleva {@code cantidadConsumida}: autorizado y consumido son conceptos distintos
 * (RN-M17-001) y el consumo lo mueve la sesion clinica en una integracion posterior. Nace en cero.
 */
@Schema(description = "Datos de la autorizacion que otorgo el financiador")
public record CreateAutorizacionRequest(

		@Schema(
				description = "Cobertura contra la que el financiador autorizo. Obligatoria: una "
						+ "autorizacion sin cobertura no significa nada, porque no hay financiador "
						+ "que la haya dado. Tiene que estar activa",
				example = "412",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La autorizacion necesita la cobertura contra la que se otorgo")
		Long coberturaId,

		@Schema(
				description = "Practica del catalogo clinico autorizada. Es el eje sobre el que se "
						+ "pacta con un financiador, el mismo que usa el arancel del convenio",
				example = "33",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La autorizacion necesita la practica autorizada")
		Long practicaId,

		@Schema(
				description = "Orden medica que respalda el pedido, si el convenio la exigia. Tiene "
						+ "que ser de esta persona y estar activa. Null es legitimo: hay convenios "
						+ "que autorizan sin orden",
				example = "51",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Long ordenMedicaId,

		@Schema(
				description = "Numero que devolvio el financiador. Obligatorio: es el dato con el "
						+ "que se presenta la liquidacion. Unico por cobertura mientras este "
						+ "vigente; el de una dada de baja si se puede reusar",
				example = "AUT-99120034",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El numero de autorizacion es obligatorio")
		@Size(max = 64, message = "El numero no puede superar los 64 caracteres")
		String numero,

		@Schema(
				description = "Estado con el que se carga. PENDIENTE (default) si se pidio y falta "
						+ "respuesta, APROBADA si el financiador ya la otorgo. OBSERVADA y "
						+ "RECHAZADA NO se pueden cargar de entrada: son la respuesta a un pedido "
						+ "y se aplican con POST /{id}/estado",
				example = "PENDIENTE",
				allowableValues = {"PENDIENTE", "APROBADA"},
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Pattern(regexp = "PENDIENTE|APROBADA",
				message = "Una autorizacion se carga PENDIENTE o APROBADA")
		String estadoInicial,

		@Schema(
				description = "Sesiones otorgadas. Null = sin tope declarado, que es distinto de "
						+ "cero",
				example = "10",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Positive(message = "La cantidad autorizada tiene que ser mayor que cero")
		Integer cantidadAutorizada,

		@Schema(
				description = "Primer dia en que la autorizacion habilita",
				example = "2026-09-01",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La autorizacion necesita una fecha de inicio")
		LocalDate vigenciaDesde,

		@Schema(
				description = "ULTIMO dia, INCLUSIVE. Null = sin vencimiento declarado",
				example = "2026-12-31",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta,

		@Schema(description = "Notas administrativas. NUNCA contenido clinico",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 500, message = "Las observaciones no pueden superar los 500 caracteres")
		String observaciones) {
}
