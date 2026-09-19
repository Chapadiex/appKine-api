package com.akine.clinical.api.dto;

import com.akine.clinical.spi.EventoClinico;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un hecho clinico datado, tal como sale en el timeline.
 *
 * <p><b>Es deliberadamente pobre y no le falta nada.</b> No trae el cuerpo de una evolucion, el
 * texto de un informe ni ninguna medicion: el timeline es un indice, no un visor. Quien quiera el
 * detalle va al recurso dueño con {@code origen} + {@code referencia} y su propio permiso, y ese
 * acceso se audita alli. Si esta respuesta trajera contenido, una sola lectura entregaria la
 * historia entera y los eventos de acceso de cada modulo dejarian de significar algo.
 */
@Schema(description = "Un hecho clinico del timeline. Indice, no contenido")
public record EventoClinicoResponse(

		@Schema(description = "Instante UTC en que ocurrio el hecho clinico. NO es el de su carga")
		Instant ocurrioEn,

		@Schema(description = "Que produjo el hecho, para saber a quien pedirle el detalle",
				example = "ENTRADA_CLINICA")
		String origen,

		@Schema(description = "Subtipo dentro de ese origen", example = "EVOLUCION")
		String tipo,

		@Schema(description = "Etiqueta corta del tipo de hecho. NUNCA contenido clinico",
				example = "Evolucion")
		String titulo,

		@Schema(description = "Id de la entidad de origen dentro de su modulo", example = "312")
		long referencia) {

	public static EventoClinicoResponse from(EventoClinico evento) {
		return new EventoClinicoResponse(
				evento.ocurrioEn(),
				evento.origen(),
				evento.tipo(),
				evento.titulo(),
				evento.referencia());
	}
}
