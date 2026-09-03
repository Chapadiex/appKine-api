package com.akine.person.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * La respuesta del financiador aplicada a una autorizacion (RF-M17-001).
 *
 * <h2>Es una ACCION, no un estado destino, y la diferencia importa</h2>
 *
 * <p>Con un estado destino, {@code {"estado": "APROBADA"}} sobre una autorizacion RECHAZADA es una
 * peticion sintacticamente valida que el servidor tiene que rechazar por semantica, y el cliente
 * puede construir cualquier transicion imaginable. Con una accion, la unica forma de llegar a
 * APROBADA es APROBAR, y el conjunto de transiciones posibles queda del lado del backend, que es
 * donde AGENT.md seccion 8 regla 12 lo pone. Es lo que la etapa pide con "comandos especificos de
 * estado, sin asignacion arbitraria de estado".
 *
 * <h2>La autorizacion parcial</h2>
 *
 * <p>{@code cantidadAutorizada} y las dos vigencias solo se aplican al APROBAR, y son lo que
 * resuelve ese caso borde: el financiador puede otorgar diez sesiones donde se pidieron veinte, o
 * una ventana mas corta. Lo que vale es lo que concedio, no lo que el centro pidio.
 */
@Schema(description = "Respuesta del financiador sobre una autorizacion")
public record ResolverAutorizacionRequest(

		@Schema(
				description = "Que hizo el financiador. APROBAR habilita; OBSERVAR pide corregir y "
						+ "deja la autorizacion viva; RECHAZAR la deniega y es terminal",
				example = "APROBAR",
				allowableValues = {"APROBAR", "OBSERVAR", "RECHAZAR"},
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La resolucion necesita declarar la accion")
		@Pattern(regexp = "APROBAR|OBSERVAR|RECHAZAR",
				message = "La accion tiene que ser APROBAR, OBSERVAR o RECHAZAR")
		String accion,

		@Schema(
				description = "Por que se observo o se rechazo. OBLIGATORIO en esas dos acciones: "
						+ "sin motivo, el mostrador no sabe que corregir. Ignorado al aprobar",
				example = "Falta la orden medica firmada",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String motivo,

		@Schema(
				description = "Sesiones que el financiador realmente otorgo. Puede ser MENOR que "
						+ "la pedida: eso es la autorizacion parcial. Solo se aplica al APROBAR",
				example = "6",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Positive(message = "La cantidad autorizada tiene que ser mayor que cero")
		Integer cantidadAutorizada,

		@Schema(description = "Inicio de vigencia concedido. Solo se aplica al APROBAR",
				example = "2026-09-15", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaDesde,

		@Schema(description = "Fin de vigencia concedido, INCLUSIVE. Solo se aplica al APROBAR",
				example = "2026-11-30", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta,

		@Schema(
				description = "Version que el cliente leyo. Es tambien lo que hace segura la "
						+ "aprobacion concurrente: el segundo en llegar recibe 409",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		long expectedVersion) {
}
