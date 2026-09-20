package com.akine.encounter.api.dto;

import com.akine.encounter.application.SesionVersionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Una version del contenido de una sesion cerrada (RF-M24-005).
 *
 * <p>Las versiones <b>no se dan de baja, ni siquiera logicamente</b>: una version es un hecho
 * pasado y desactivarla seria reescribir historia clinica, que ADR-0011 prohibe. Por eso este
 * record no tiene ningun campo de ciclo de vida — no es una omision, es que no existe el estado.
 *
 * <p>Trae el contenido <b>completo</b> de esa version, no un diff: lo que se necesita leer es que
 * decia el registro en ese momento. Calcular la diferencia es trabajo de la pantalla, que recibe
 * las dos versiones enteras.
 *
 * <p>Lo que no trae —asistencia, modo, correlativos, fechas de la atencion— es lo que <b>no es
 * enmendable</b>: vale lo mismo en todas las versiones y se lee de la sesion una sola vez.
 */
@Schema(
		name = "SesionVersion",
		description = "Una version del contenido de una sesion cerrada. Inmutable. La 1 es lo que "
				+ "se asento al cerrar; cada enmienda agrega la siguiente")
public record SesionVersionResponse(

		@Schema(description = "Numero de version. **La 1 es el original**", example = "2")
		int numeroVersion,

		@Schema(description = "No es un diagnostico") String motivoClinico,
		@Schema(description = "Escala 0 a 10", example = "4") Integer dolorEva,
		String dolorZona,
		@Schema(allowableValues = {"IZQUIERDA", "DERECHA", "BILATERAL", "NO_APLICA"}) String dolorLateralidad,
		@Schema(allowableValues = {"MEJOR", "IGUAL", "PEOR", "SIN_REFERENCIA"}) String evolucion,
		String objetivoSesion,
		String limitacionFuncional,
		String notaDeCierre,
		String respuestaTratamiento,
		@Schema(allowableValues = {"BUENA", "REGULAR", "MALA"}) String tolerancia,
		String indicaciones,
		@Schema(allowableValues = {"CONTINUA", "ALTA", "DERIVA", "REEVALUA"}) String proximaConducta,

		@Schema(description = "Por que se escribio esta version. **Ausente en la 1**, que no "
				+ "enmienda nada",
				example = "Se corrigio la lateralidad: el dolor era del lado derecho")
		String motivoEnmienda,

		@Schema(description = "Instante UTC en que se escribio. En la version 1 es el del cierre",
				example = "2026-09-15T12:48:00Z")
		Instant registradaEn,

		@Schema(description = "Cuenta que la escribio. Es **por version**: quien enmienda no suele "
				+ "ser quien cerro", example = "8")
		long registradaPor) {

	public static SesionVersionResponse de(SesionVersionView vista) {
		return new SesionVersionResponse(
				vista.numeroVersion(),
				vista.motivoClinico(),
				vista.dolorEva(),
				vista.dolorZona(),
				vista.dolorLateralidad(),
				vista.evolucion(),
				vista.objetivoSesion(),
				vista.limitacionFuncional(),
				vista.notaDeCierre(),
				vista.respuestaTratamiento(),
				vista.tolerancia(),
				vista.indicaciones(),
				vista.proximaConducta(),
				vista.motivoEnmienda(),
				vista.registradaEn(),
				vista.registradaPor());
	}
}
