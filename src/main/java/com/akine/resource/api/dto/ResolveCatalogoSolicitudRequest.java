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
 * <p><b>Aprobar no crea el concepto global.</b> El concepto que la plataforma termina publicando
 * casi nunca es el que el centro propuso —el codigo se normaliza, el nombre se unifica con los
 * que ya existen— y crearlo automaticamente desde el texto de un pedido llenaria el catalogo
 * comun de duplicados con nombres parecidos, que es exactamente lo que este circuito existe para
 * evitar.
 */
@Schema(description = "Aprobacion o rechazo de una solicitud de catalogo")
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
		Long version) {
}
