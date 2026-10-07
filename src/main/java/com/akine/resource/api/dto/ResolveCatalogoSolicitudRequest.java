package com.akine.resource.api.dto;

import com.akine.resource.domain.SolicitudEstado;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Decision de la plataforma sobre una solicitud de alta de catalogo (RF-M06-005).
 *
 * <p>La nota es obligatoria en los dos desenlaces. Aprobar sin decir con que criterio deja al
 * centro sin saber si su pedido se entendio; rechazar sin decir por que lo deja sin nada
 * accionable, y la unica salida es volver a pedir lo mismo.
 *
 * <p><b>Aprobar publica el concepto global (AKINE-A-7)</b>, en la misma transaccion. Los cuatro
 * campos de concepto son la normalizacion de la plataforma: el centro propone, la plataforma
 * dispone. Si {@code codigo} o {@code nombre} faltan se usan los propuestos; asi el concepto
 * publicado no es el texto crudo del pedido salvo que la plataforma lo acepte tal cual, y el
 * unique del catalogo global rechaza el duplicado. En un rechazo no se mandan.
 */
@Schema(description = "Aprobacion o rechazo de una solicitud de catalogo. Aprobar publica el "
		+ "concepto global en el mismo acto")
public record ResolveCatalogoSolicitudRequest(

		@Schema(description = "Desenlace. PENDIENTE no es una resolucion y se rechaza con 400",
				example = "APROBADA",
				allowableValues = {"APROBADA", "RECHAZADA"},
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El desenlace de la resolucion es obligatorio")
		SolicitudEstado estado,

		@Schema(description = "Motivo de la decision", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "La nota de resolucion es obligatoria")
		@Size(max = 500, message = "La nota no puede superar los 500 caracteres")
		String nota,

		@Schema(description = "Version que el cliente leyo. Si quedo vieja, 409 "
				+ "concurrent-modification", example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version es obligatoria para resolver una solicitud")
		Long version,

		@Schema(description = "Solo al aprobar: codigo con el que se publica el concepto global. "
				+ "Si falta, el propuesto; si tampoco hay propuesto, 400", example = "TO-01")
		@Size(max = 48, message = "El codigo no puede superar los 48 caracteres")
		String codigo,

		@Schema(description = "Solo al aprobar: nombre con el que se publica. Si falta, el "
				+ "propuesto", example = "Terapia ocupacional")
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String nombre,

		@Schema(description = "Solo al aprobar: descripcion del concepto publicado")
		@Size(max = 280, message = "La descripcion no puede superar los 280 caracteres")
		String descripcion,

		@Schema(description = "Solo al aprobar una PRACTICA, y obligatorio en ese caso: "
				+ "especialidad GLOBAL de la que cuelga", example = "1")
		Long especialidadId) {
}
