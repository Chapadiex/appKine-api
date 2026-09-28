package com.akine.activity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Un lote de asistencias (RF-M13-008).
 *
 * <p><b>Cada item va en su propia transaccion</b> y un fallo individual no revierte a los demas:
 * "resultados parciales explicitos". El tope existe porque mas que eso no es una lista compacta.
 */
@Schema(
		name = "RegistrarAsistenciaLote",
		description = "Marca a varios participantes. La respuesta es **200 aunque haya fallos "
				+ "parciales**: no es un error del pedido, es el resultado del pedido.")
public record RegistrarAsistenciaLoteRequest(

		@Schema(description = "Hasta 200 participantes")
		@NotEmpty @Size(max = 200) @Valid List<RegistrarAsistenciaRequest> items) {
}
