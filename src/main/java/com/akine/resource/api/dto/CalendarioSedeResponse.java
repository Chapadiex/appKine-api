package com.akine.resource.api.dto;

import com.akine.resource.application.CalendarioView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * La politica de calendario de una sede y, cuando se pidio con una ventana, los feriados que
 * caen dentro de ella (RF-M05-004).
 *
 * <h2>{@code feriados} vacia significa "no se pregunto", nunca "no hay feriados"</h2>
 *
 * <p>La LECTURA responde las dos cosas que la pantalla necesita juntas: si esta sede cierra los
 * feriados, y cuales son los feriados del periodo que se esta mirando. La EDICION no lleva
 * ventana —un {@code PUT} no tiene {@code desde} ni {@code hasta}—, asi que devuelve la lista
 * vacia. Quien quiera la lista despues de editar vuelve a leer con su ventana.
 *
 * <p>Un cliente que lea la respuesta del {@code PUT} como "esta sede no tiene feriados" va a
 * dibujar un calendario limpio justo despues de que alguien active el cierre por feriados.
 */
@Schema(description = "Politica de calendario de la sede y feriados de la ventana consultada")
public record CalendarioSedeResponse(

		@Schema(description = "Sede a la que pertenece la politica", example = "1")
		long consultorioId,

		@Schema(description = "Pais cuyo calendario de feriados usa la sede", example = "AR")
		String pais,

		@Schema(description = "Si la sede cierra los feriados de ese pais. Un centro de guardia "
				+ "atiende los feriados y el modelo tiene que poder decirlo", example = "true")
		boolean cierraPorFeriado,

		@Schema(description = "Version de la fila de politica. Viaja aunque la edicion NO la "
				+ "exija: es lo que le permite a la pantalla detectar que alguien mas la cambio",
				example = "0")
		long version,

		@Schema(description = "false cuando la sede todavia no tiene fila propia y lo que se "
				+ "devuelve son los valores por defecto. Es informacion util: dice que nadie "
				+ "edito nunca la politica de esa sede, no que no tenga una. La LECTURA no crea "
				+ "la fila —un GET que escribe es una mutacion que nadie pidio—; aparece en la "
				+ "primera edicion o en el primer write de disponibilidad de la sede",
				example = "false")
		boolean existePersistida,

		@Schema(description = "Feriados del pais que caen en [desde, hasta). VACIA en la "
				+ "respuesta del PUT, que no tiene ventana: vacia significa \"no se pregunto\"")
		List<FeriadoResponse> feriados) {

	public static CalendarioSedeResponse from(CalendarioView view) {
		return new CalendarioSedeResponse(
				view.consultorioId(),
				view.pais(),
				view.cierraPorFeriado(),
				view.version(),
				view.existePersistida(),
				view.feriados().stream().map(FeriadoResponse::from).toList());
	}
}
