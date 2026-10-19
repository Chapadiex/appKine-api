package com.akine.offering.api.dto;

import com.akine.offering.application.OfertaHonorarioClaseView;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Cuerpos del honorario por clase de una oferta (AKINE-08.09b, DP-38, RF-M22-006). */
public final class HonorarioPorClaseDtos {

	private HonorarioPorClaseDtos() {
	}

	/** Alta de una tarifa con vigencia. */
	@Schema(name = "CreateHonorarioPorClaseRequest",
			description = "Honorario por participante presente de la oferta para un periodo")
	public record CreateHonorarioPorClaseRequest(

			@Schema(description = "Lo que se paga por cada participante PRESENTE o PRESENTE_TARDE. "
					+ "Mayor que cero", example = "1500.00", requiredMode = Schema.RequiredMode.REQUIRED)
			@NotNull(message = "El importe por participante es obligatorio")
			@DecimalMin(value = "0.01", message = "El importe por participante tiene que ser mayor que cero")
			@Digits(integer = 10, fraction = 2, message = "El importe admite 2 decimales")
			BigDecimal importePorParticipante,

			@Schema(description = "ISO 4217. Si se omite, la del precio de lista de la oferta",
					example = "ARS", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
			@Pattern(regexp = "[A-Za-z]{3}", message = "La moneda es un codigo ISO 4217 de 3 letras")
			String moneda,

			@Schema(description = "Primer dia en que rige (dia local de la clase en la sede)",
					example = "2026-11-01", requiredMode = Schema.RequiredMode.REQUIRED)
			@NotNull(message = "La vigencia necesita una fecha de inicio")
			LocalDate vigenciaDesde,

			@Schema(description = "ULTIMO dia en que rige, INCLUSIVE. Null = sin fin previsto",
					example = "2027-03-31", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
			LocalDate vigenciaHasta) {
	}

	/** Cambio del fin de la vigencia: lo unico editable de una tarifa. */
	@Schema(name = "CambiarFinHonorarioPorClaseRequest",
			description = "Nuevo fin de vigencia del honorario por clase")
	public record CambiarFinHonorarioPorClaseRequest(

			@Schema(description = "ULTIMO dia en que rige, INCLUSIVE. Null reabre la vigencia sin fin",
					example = "2027-02-28", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
			LocalDate vigenciaHasta,

			@Schema(description = "Version del honorario que se esta editando", example = "0",
					requiredMode = Schema.RequiredMode.REQUIRED)
			@NotNull(message = "expectedVersion es obligatoria")
			Long expectedVersion) {
	}

	/** Una tarifa de la grilla. */
	@Schema(name = "HonorarioPorClase", description = "Honorario por clase de la oferta para un periodo")
	public record HonorarioPorClaseResponse(

			@Schema(description = "Identificador del honorario", example = "12")
			long id,

			@Schema(description = "Oferta", example = "34")
			long ofertaId,

			@Schema(description = "Importe por participante presente", example = "1500.00")
			BigDecimal importePorParticipante,

			@Schema(description = "ISO 4217", example = "ARS")
			String moneda,

			@Schema(description = "Primer dia en que rige", example = "2026-11-01")
			LocalDate vigenciaDesde,

			@Schema(description = "Ultimo dia en que rige, INCLUSIVE. Null = sin fin previsto")
			LocalDate vigenciaHasta,

			@Schema(description = "Si es el que rige hoy")
			boolean vigente,

			@Schema(description = "Ciclo de vida", allowableValues = {"ACTIVO", "INACTIVO"})
			String estado,

			@Schema(description = "Instante de la baja. Null mientras este activo")
			Instant deletedAt,

			@Schema(description = "Motivo de la baja. Null mientras este activo")
			String deactivationReason,

			@Schema(description = "Version a reenviar para cambiar el fin", example = "0")
			long version) {

		public static HonorarioPorClaseResponse de(OfertaHonorarioClaseView v) {
			return new HonorarioPorClaseResponse(v.id(), v.ofertaId(), v.importePorParticipante(),
					v.moneda(), v.vigenciaDesde(), v.vigenciaHasta(), v.vigente(), v.estado(),
					v.deletedAt(), v.deactivationReason(), v.version());
		}
	}
}
