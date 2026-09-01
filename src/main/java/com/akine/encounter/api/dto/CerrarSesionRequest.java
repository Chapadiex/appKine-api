package com.akine.encounter.api.dto;

import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.ProximaConducta;
import com.akine.encounter.domain.Tolerancia;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * El cierre de una atencion.
 *
 * <p><b>Aca si hay minimos</b>, al reves que en la evaluacion: el cierre es el acto que declara que
 * la prestacion ocurrio, y de el se deriva la obligacion economica. Una sesion cerrada sin decir
 * que se hizo ni como resulto es un registro que no sirve y que ademas va a generar un cobro.
 */
@Schema(
		name = "CerrarSesion",
		description = "Cierre clinico de la atencion. Es idempotente: cerrar dos veces devuelve el "
				+ "mismo resultado y NO renumera.")
public record CerrarSesionRequest(

		@Schema(
				description = "**Obligatorio.** Sin esto no se sabe si hubo prestacion. `AUSENTE` es "
						+ "un cierre legitimo —la ausencia tambien es un hecho— y evita que el turno "
						+ "quede abierto para siempre. Con `AUSENTE` nada mas es obligatorio: pedir "
						+ "resultado de una atencion que no ocurrio seria pedir que se invente.",
				allowableValues = {"PRESENTE", "AUSENTE"},
				example = "PRESENTE")
		@NotNull Asistencia asistencia,

		@Schema(
				description = "**Obligatorio si el paciente asistio.** El detalle estructurado de "
						+ "tratamientos es AKINE-06.04, fuera de alcance, asi que esta nota es lo "
						+ "unico que registra que se hizo.",
				example = "Terapia manual lumbar, ejercicios de estabilizacion, 30 minutos")
		@Size(max = 2000) String notaDeCierre,

		@Schema(description = "Como respondio a lo realizado hoy", example = "Alivio inmediato del dolor")
		@Size(max = 500) String respuestaTratamiento,

		@Schema(
				description = "Como tolero lo que se le hizo. Es distinto del resultado: se puede "
						+ "tolerar mal algo que funciona.",
				allowableValues = {"BUENA", "REGULAR", "MALA"},
				example = "BUENA")
		Tolerancia tolerancia,

		@Schema(description = "Lo que el paciente se lleva", example = "Repetir el ejercicio 2 veces por dia")
		@Size(max = 1000) String indicaciones,

		@Schema(
				description = "Que sigue. Es lo que convierte una sesion suelta en un tratamiento.",
				allowableValues = {"CONTINUA", "ALTA", "DERIVA", "REEVALUA"},
				example = "CONTINUA")
		ProximaConducta proximaConducta,

		@Schema(description = "Version que el cliente leyo", example = "4")
		@NotNull @PositiveOrZero Long version) {

	public CierreDeSesion aDominio() {
		return new CierreDeSesion(
				asistencia, notaDeCierre, respuestaTratamiento, tolerancia, indicaciones, proximaConducta);
	}
}
