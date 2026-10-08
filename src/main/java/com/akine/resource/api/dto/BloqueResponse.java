package com.akine.resource.api.dto;

import com.akine.resource.application.BloqueView;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonSerialize;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Un bloque recurrente de disponibilidad tal como lo publica la API (RF-M05-003, RF-M05-005).
 *
 * <h2>Que cuenta {@code turnosAfectados}</h2>
 *
 * <p>En la edicion y en la baja, los turnos pendientes de ese profesional en esa sede, dentro de
 * la ventana del cambio, que la disponibilidad efectiva cubria antes y no cubre despues
 * ({@code SimuladorDeImpacto}, A-11). En el alta es cero por construccion. Hasta A-11 era una
 * cota superior que incluia turnos de otros bloques vigentes del mismo profesional; ya no.
 *
 * <p>El impacto se informa y no bloquea (RN-M05-004): la pantalla decide que hacer con esos
 * turnos.
 *
 * <h2>La medianoche viaja como {@code "24:00"}</h2>
 *
 * <p>Ver {@link HoraDelDia}. Internamente el fin del dia es {@code LocalTime.MAX}
 * ({@code 23:59:59.999999999}); publicarlo asi mostraria algo que el usuario nunca cargo y
 * rompería el ida y vuelta, porque el cliente no puede reenviar ese valor.
 */
@Schema(description = "Bloque recurrente de disponibilidad de un profesional en una sede")
public record BloqueResponse(

		@Schema(description = "Identificador del bloque", example = "1")
		long id,

		@Schema(description = "Organizacion propietaria", example = "1")
		long organizationId,

		@Schema(description = "Sede en la que rige el bloque", example = "1")
		long consultorioId,

		@Schema(description = "Vinculo del profesional al que pertenece el bloque", example = "1")
		long membershipId,

		@Schema(description = "Dia de la semana, ISO-8601: lunes = 1 .. domingo = 7",
				example = "2")
		int diaSemana,

		@Schema(description = "Hora local de inicio, en la zona de la sede",
				type = "string", example = "09:00")
		@JsonSerialize(using = HoraDelDia.Serializador.class)
		LocalTime horaDesde,

		@Schema(description = "Hora local de fin, EXCLUSIVA. Un bloque que llega a la medianoche "
				+ "viaja como 24:00 y se reenvia igual: 00:00 significaria el principio del dia",
				type = "string", example = "13:00")
		@JsonSerialize(using = HoraDelDia.Serializador.class)
		LocalTime horaHasta,

		@Schema(description = "Primer dia en que el bloque rige", example = "2026-09-01")
		LocalDate vigenciaDesde,

		@Schema(description = "Primer dia en que el bloque ya NO rige, EXCLUSIVO. null = sin fin "
				+ "previsto", example = "2027-01-01")
		LocalDate vigenciaHasta,

		@Schema(description = "ACTIVO o INACTIVO. DERIVADO, no una columna", example = "ACTIVO")
		String estado,

		@Schema(description = "Instante de la baja logica. null si el bloque esta vigente")
		Instant deletedAt,

		@Schema(description = "Motivo declarado en la baja. null si el bloque esta vigente")
		String deactivationReason,

		@Schema(description = "Version a reenviar para editar. Una version vieja da 409",
				example = "0")
		long version,

		@Schema(description = "Turnos futuros que este cambio podria dejar en conflicto "
				+ "(RN-M05-004): los pendientes de ese profesional en esa sede que empiezan entre "
				+ "ahora y el fin de vigencia mas lejano entre el estado anterior y el nuevo, con un "
				+ "horizonte de 90 dias si no hay fin, y que la disponibilidad efectiva cubria antes "
				+ "del cambio y no cubre despues: un turno cubierto por otro bloque vigente del "
				+ "mismo profesional no cuenta. En un ALTA es 0 por construccion "
				+ "—agregar disponibilidad no deja ningun turno afuera—", example = "0")
		long turnosAfectados,

		@Schema(description = "Instante del primero de esos turnos, para que la pantalla pueda "
				+ "decir \"desde el martes\". null cuando no hay ninguno")
		Instant primerTurnoAfectado) {

	/**
	 * Proyeccion de la vista de aplicacion.
	 *
	 * <p>{@code BloqueView.nuevo()} <b>no</b> se copia: es una senal interna para que el
	 * controller elija entre 201 y 200, y publicarla obligaria al cliente a mirar dos cosas para
	 * responder la misma pregunta.
	 */
	public static BloqueResponse from(BloqueView view) {
		return new BloqueResponse(
				view.id(),
				view.organizationId(),
				view.consultorioId(),
				view.membershipId(),
				view.diaSemana(),
				view.horaDesde(),
				view.horaHasta(),
				view.vigenciaDesde(),
				view.vigenciaHasta(),
				view.estado(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version(),
				view.turnosAfectados(),
				view.primerTurnoAfectado());
	}
}
