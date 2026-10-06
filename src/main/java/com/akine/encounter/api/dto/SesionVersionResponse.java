package com.akine.encounter.api.dto;

import com.akine.encounter.application.SesionVersionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
		long registradaPor,

		@Schema(description = "Los tratamientos **vigentes en esta version**, en orden cronologico. "
				+ "Foto inmutable: una enmienda posterior que los corrija no cambia lo que dice "
				+ "esta version (C-6)")
		List<TratamientoEnVersionResponse> tratamientos,

		@Schema(description = "Las mediciones **de esta version**. Foto inmutable (C-6)")
		List<MedicionEnVersionResponse> mediciones) {

	@Schema(
			name = "TratamientoEnVersion",
			description = "Un tratamiento realizado tal como estaba en una version de la sesion")
	public record TratamientoEnVersionResponse(
			@Schema(description = "Id del tratamiento vivo. Permite seguir el mismo tratamiento "
					+ "de una version a otra y es el que se manda para corregirlo", example = "301")
			long tratamientoId,
			@Schema(example = "1") int orden,
			@Schema(example = "41") long practicaId,
			String practicaCodigo,
			String practicaNombre,
			String tecnica,
			String zona,
			@Schema(allowableValues = {"IZQUIERDA", "DERECHA", "BILATERAL", "NO_APLICA"})
			String lateralidad,
			Integer duracionMinutos,
			long profesionalMembershipId,
			Long espacioId,
			String espacioNombre,
			String observacion,
			List<TratamientoResponse.ParametroResponse> parametros) {

		static TratamientoEnVersionResponse de(SesionVersionView.TratamientoEnVersion vista) {
			return new TratamientoEnVersionResponse(
					vista.tratamientoId(),
					vista.orden(),
					vista.practicaId(),
					vista.practicaCodigo(),
					vista.practicaNombre(),
					vista.tecnica(),
					vista.zona(),
					vista.lateralidad(),
					vista.duracionMinutos(),
					vista.profesionalMembershipId(),
					vista.espacioId(),
					vista.espacioNombre(),
					vista.observacion(),
					vista.parametros().stream().map(TratamientoResponse.ParametroResponse::de).toList());
		}
	}

	@Schema(
			name = "MedicionEnVersion",
			description = "Una medicion tal como estaba en una version de la sesion, con el "
					+ "significado de la definicion congelado")
	public record MedicionEnVersionResponse(
			@Schema(example = "7") long definicionId,
			String codigo,
			String nombre,
			@Schema(allowableValues = {"NUMERICO", "ESCALA", "TEXTO", "BOOLEANO"}) String tipo,
			String unidad,
			@Schema(allowableValues = {"IZQUIERDA", "DERECHA", "NO_APLICA"}) String lateralidad,
			BigDecimal valorNumerico,
			String valorTexto,
			Boolean valorBooleano,
			String nota,
			Instant registradaEn,
			@Schema(description = "Quien la cargo o la corrigio por ultima vez antes de esta "
					+ "version", example = "8")
			long registradaPor) {

		static MedicionEnVersionResponse de(SesionVersionView.MedicionEnVersion vista) {
			return new MedicionEnVersionResponse(
					vista.definicionId(),
					vista.codigo(),
					vista.nombre(),
					vista.tipo().name(),
					vista.unidad(),
					vista.lateralidad().name(),
					vista.valorNumerico(),
					vista.valorTexto(),
					vista.valorBooleano(),
					vista.nota(),
					vista.registradaEn(),
					vista.registradaPor());
		}
	}

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
				vista.registradaPor(),
				vista.tratamientos().stream().map(TratamientoEnVersionResponse::de).toList(),
				vista.mediciones().stream().map(MedicionEnVersionResponse::de).toList());
	}
}
