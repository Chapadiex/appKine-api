package com.akine.contracting.api.dto;

import com.akine.contracting.domain.ModalidadConvenio;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Edicion parcial de un convenio, incluido el cierre de vigencia (RF-M16-002 y RF-M16-003).
 *
 * <p>Lo que llega en {@code null} no se toca. Ni el codigo, ni el financiador, ni el plan estan
 * aca: son la identidad del convenio y lo que ya se liquido bajo el los referencia.
 *
 * <p><b>Mover la vigencia puede producir un 409 {@code convenio-solapado}</b>, igual que el alta:
 * estirar el fin de un convenio hasta pisar al siguiente es exactamente lo que RN-M16-002 prohibe,
 * y una etapa que validara el solapamiento solo al crear dejaria abierta la puerta mas ancha.
 */
@Schema(description = "Cambios a aplicar sobre un convenio. Los campos nulos no se tocan")
public record UpdateConvenioRequest(

		@Schema(description = "Nombre visible del convenio", example = "OSDE 210 - 2026")
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String nombre,

		@Schema(description = "Como se pacto la prestacion", example = "POR_PRESTACION")
		ModalidadConvenio modalidad,

		@Schema(description = "Primer dia en que el convenio se aplica", example = "2026-01-01")
		LocalDate vigenciaDesde,

		@Schema(
				description = "ULTIMO dia en que se aplica, INCLUSIVE. CERRAR LA VIGENCIA ES ESTO: "
						+ "el convenio queda ACTIVO y consultable, y solo deja de aplicarse despues "
						+ "de esa fecha. Dar de baja es otra operacion",
				example = "2026-12-31")
		LocalDate vigenciaHasta,

		@Schema(description = "Si la prestacion exige orden medica")
		Boolean requiereOrden,

		@Schema(description = "Si exige autorizacion previa del financiador")
		Boolean requiereAutorizacion,

		@Schema(description = "Si exige numero de credencial del paciente")
		Boolean requiereCredencial,

		@Schema(description = "Tope de sesiones por mes. Cero no es un valor valido", example = "20")
		@Min(value = 1, message = "El tope mensual tiene que ser mayor que cero")
		Integer limiteSesionesMensual,

		@Schema(description = "Documentacion administrativa que el financiador exige")
		@Size(max = 500, message = "La documentacion requerida no puede superar los 500 caracteres")
		String documentacionRequerida,

		@Schema(description = "Notas administrativas. Nunca contenido clinico")
		@Size(max = 500, message = "Las observaciones no pueden superar los 500 caracteres")
		String observaciones,

		@Schema(
				description = "Version leida del convenio. Una version vieja produce 409 en vez de "
						+ "pisar el cambio ajeno en silencio",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@PositiveOrZero(message = "La version no puede ser negativa")
		long expectedVersion) {
}
