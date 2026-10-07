package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.TurnoDelDiaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un turno de la agenda del dia, con el paciente y la oferta ya resueltos.
 *
 * <p>Es distinto de {@link TurnoResponse}, que no lleva datos del paciente. Aca si: la recepcion
 * es una lista de personas que van llegando, y "turno 12:00, persona #5" no sirve para llamar a
 * nadie. Resolverlo del lado del cliente seria una consulta por fila.
 *
 * <p><b>Nada clinico.</b> Ni motivo de consulta ni diagnostico: quien atiende el mostrador no lo
 * necesita para decir que pase.
 */
@Schema(
		name = "TurnoDelDia",
		description = "Turno de la agenda del dia de una sede, con el paciente resuelto. "
				+ "PHI minima: no lleva ningun dato clinico.")
public record TurnoDelDiaResponse(

		@Schema(example = "301")
		long id,

		@Schema(description = "Inicio, UTC. La pantalla lo muestra en la zona de la sede.", example = "2026-09-15T12:00:00Z")
		Instant inicio,

		@Schema(description = "Fin, UTC y EXCLUSIVO", example = "2026-09-15T12:45:00Z")
		Instant fin,

		@Schema(
				description = "`EN_ESPERA` significa que el paciente llego y aguarda. No significa "
						+ "que lo esten atendiendo: la prestacion la registra la Sesion.",
				allowableValues = {"RESERVADO", "CONFIRMADO", "EN_ESPERA", "CANCELADO", "AUSENTE"},
				example = "CONFIRMADO")
		String estado,

		@Schema(example = "128")
		long personaId,

		@Schema(description = "Apellido y nombre, ya compuestos", example = "Perez, Ana")
		String personaNombre,

		@Schema(description = "Tipo y numero, para desambiguar homonimos. Ausente si la ficha no lo tiene.", example = "DNI 30111222")
		String documento,

		@Schema(example = "42")
		long ofertaId,

		@Schema(example = "Kinesiologia - sesion individual")
		String ofertaNombre,

		@Schema(description = "Membership del profesional. Ausente si la oferta no lo requiere.", example = "31")
		Long profesionalId,

		@Schema(description = "Box asignado, si la oferta requiere espacio", example = "55")
		Long espacioId,

		@Schema(description = "Hora REAL de llegada, puesta por el servidor. Ausente si no llego.", example = "2026-09-15T11:52:00Z")
		Instant llegadaEn,

		@Schema(
				description = "Por que se cancelo. **Los cancelados se devuelven igual**: alguien "
						+ "puede presentarse al mostrador con un turno que se cancelo, y esconderlo "
						+ "deja a la recepcion sin nada que decirle.",
				example = "El profesional se enfermo")
		String motivoCancelacion,

		@Schema(description = "Serie que genero el turno (AKINE E-3). Ausente en un turno suelto: la pantalla lo usa para ofrecer el alcance al cancelar o mover.", example = "12")
		Long serieId,

		@Schema(description = "Version para el control optimista de las transiciones", example = "0")
		long version) {

	public static TurnoDelDiaResponse de(TurnoDelDiaView vista) {
		return new TurnoDelDiaResponse(
				vista.id(), vista.inicio(), vista.fin(), vista.estado(),
				vista.personaId(), vista.personaNombre(), vista.documento(),
				vista.ofertaId(), vista.ofertaNombre(),
				vista.profesionalId(), vista.espacioId(), vista.llegadaEn(),
				vista.motivoCancelacion(), vista.serieId(), vista.version());
	}
}
