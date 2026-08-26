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
 * <h2>{@code turnosAfectados} responde hoy siempre 0, y no es un bug</h2>
 *
 * <p>Es el mismo cero estructural que {@code EspacioAvailabilityResponse} declara para
 * {@code lugaresComprometidos}, y por el mismo motivo: los turnos son del modulo
 * {@code scheduling} (F5, M12), que todavia no existe. El servicio SI consulta la sonda de
 * impacto en la edicion y en la baja —no es codigo muerto esperando a F5—, pero la unica
 * implementacion registrada responde "ningun impacto".
 *
 * <p>Cuando F5 traiga la implementacion real este numero deja de ser cero <b>sin ningun cambio
 * de contrato</b>: un cliente escrito hoy sigue funcionando. Lo que NO hay que hacer es leer
 * "cero conflictos" como "se puede cambiar sin consecuencias": eso va a dejar turnos huerfanos
 * en cuanto exista la agenda, y el bug no va a parecer de esta etapa.
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

		@Schema(description = "Turnos futuros que este cambio dejaria en conflicto (RN-M05-004). "
				+ "SIEMPRE 0 en esta version del contrato: el modulo de agenda no existe todavia. "
				+ "En un ALTA es 0 por construccion —agregar disponibilidad no deja ningun turno "
				+ "afuera—; en una edicion o una baja va a dejar de ser 0 cuando exista F5, sin "
				+ "cambiar este contrato", example = "0")
		long turnosAfectados,

		@Schema(description = "Instante del primero de esos turnos, para que la pantalla pueda "
				+ "decir \"desde el martes\". null cuando no hay ninguno, que es siempre hoy")
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
