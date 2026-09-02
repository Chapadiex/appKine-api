package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * La agenda del dia de una sede.
 *
 * <h2>Por que es un objeto y no un array de turnos</h2>
 *
 * <p>Un array no puede llevar la <b>zona horaria</b>, y sin ella la pantalla no puede mostrar las
 * horas. Los instantes viajan en UTC —que es lo correcto— pero "las 09:00" es una hora local de la
 * sede: convertir con la zona del navegador de quien mira corre la agenda entera y <b>no falla</b>,
 * simplemente muestra otra cosa.
 *
 * <p>La primera version de este endpoint devolvia el array pelado y la pantalla terminaba
 * deduciendo la zona con una segunda lectura sobre la agenda de la oferta del primer turno. Eso es
 * fragil —un dia vacio no tiene primer turno— y ademas convierte un dato de la sede en un efecto
 * lateral de tener turnos. {@code AgendaResponse} ya la publicaba por la misma razon; esta
 * respuesta simplemente deja de ser la excepcion.
 *
 * <p>Que sea un objeto tambien deja lugar para lo que M13 va a necesitar cuando existan las
 * autorizaciones: un resumen del dia —cuantos esperan, cuantos faltan— no cabe en un array.
 */
@Schema(
		name = "AgendaDelDia",
		description = "Los turnos de una sede en un dia, con la zona horaria con la que hay que "
				+ "mostrarlos. PHI minima: ningun dato clinico.")
public record AgendaDelDiaResponse(

		@Schema(description = "El dia pedido, interpretado en la zona de la sede", example = "2026-09-15")
		LocalDate fecha,

		@Schema(
				description = "Zona horaria de la sede. Viaja para que la pantalla pueda convertir "
						+ "los instantes y rotularla, en vez de usar la del navegador de quien mira.",
				example = "America/Argentina/Cordoba")
		String timezone,

		@Schema(description = "Turnos del dia, del mas temprano al mas tarde. Incluye los cancelados.")
		List<TurnoDelDiaResponse> turnos) {
}
