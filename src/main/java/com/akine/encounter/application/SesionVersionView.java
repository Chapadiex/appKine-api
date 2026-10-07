package com.akine.encounter.application;

import com.akine.encounter.domain.LateralidadMedicion;
import com.akine.encounter.domain.SesionVersion;
import com.akine.encounter.domain.SesionVersionMedicion;
import com.akine.encounter.domain.SesionVersionTratamiento;
import com.akine.resource.spi.MedicionTipo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Una version del contenido de una sesion cerrada, tal como sale del servicio (RF-M24-005).
 *
 * <h2>Trae el contenido completo, no un diff</h2>
 *
 * <p>Un diff seria mas compacto y menos util: lo que el profesional necesita leer es <b>que decia
 * el registro en ese momento</b>, no que caracteres cambiaron. Calcular la diferencia es trabajo de
 * la pantalla, que recibe las dos versiones enteras y puede resaltar lo que quiera; calcularla
 * aca la congelaria en una representacion —por palabra, por campo, por caracter— que despues no se
 * puede cambiar sin romper el contrato. Es el mismo criterio de {@code EntradaClinicaVersionView}.
 *
 * <h2>Lo que la version NO trae, y por que</h2>
 *
 * <p>Ni la asistencia, ni el modo, ni los dos correlativos, ni las fechas de la atencion. Ninguno
 * es enmendable, asi que <b>valen lo mismo en todas las versiones</b> y repetirlos en cada fila
 * seria duplicar sin ganar nada. Quien arma la comparacion los lee de la sesion, una sola vez.
 *
 * <p>No lleva {@code id}: la version se identifica por su numero dentro de la sesion, que es lo
 * unico que significa algo para quien la lee. Exponer la clave primaria invitaria a una API que
 * las direccione sueltas, y una version fuera de su sesion no tiene interpretacion clinica.
 */
public record SesionVersionView(
		int numeroVersion,
		String motivoClinico,
		Integer dolorEva,
		String dolorZona,
		String dolorLateralidad,
		String evolucion,
		String objetivoSesion,
		String limitacionFuncional,
		String notaDeCierre,
		String respuestaTratamiento,
		String tolerancia,
		String indicaciones,
		String proximaConducta,
		String motivoEnmienda,
		Instant registradaEn,
		long registradaPor,
		List<TratamientoEnVersion> tratamientos,
		List<MedicionEnVersion> mediciones) {

	/**
	 * Un tratamiento tal como estaba en esta version (C-6). {@code tratamientoId} es el del
	 * tratamiento vivo: es lo que permite seguir el mismo tratamiento de una version a otra.
	 */
	public record TratamientoEnVersion(
			long tratamientoId,
			int orden,
			long practicaId,
			String practicaCodigo,
			String practicaNombre,
			String tecnica,
			String zona,
			String lateralidad,
			Integer duracionMinutos,
			long profesionalMembershipId,
			Long espacioId,
			String espacioNombre,
			String observacion,
			List<TratamientoView.ParametroView> parametros) {

		static TratamientoEnVersion de(SesionVersionTratamiento foto) {
			return new TratamientoEnVersion(
					foto.getTratamientoRealizadoId(),
					foto.getOrden(),
					foto.getPracticaId(),
					foto.getPracticaCodigo(),
					foto.getPracticaNombre(),
					foto.getTecnica(),
					foto.getZona(),
					nombre(foto.getLateralidad()),
					foto.getDuracionMinutos(),
					foto.getProfesionalMembershipId(),
					foto.getEspacioId(),
					foto.getEspacioNombre(),
					foto.getObservacion(),
					foto.getParametros().stream()
							.map(parametro -> new TratamientoView.ParametroView(
									parametro.getClave(),
									parametro.getTipoDato(),
									parametro.getValorNumerico(),
									parametro.getValorTexto(),
									parametro.getValorBooleano(),
									parametro.getUnidad(),
									parametro.getOrden()))
							.toList());
		}
	}

	/** Una medicion tal como estaba en esta version (C-6). */
	public record MedicionEnVersion(
			long definicionId,
			String codigo,
			String nombre,
			MedicionTipo tipo,
			String unidad,
			LateralidadMedicion lateralidad,
			BigDecimal valorNumerico,
			String valorTexto,
			Boolean valorBooleano,
			String nota,
			Instant registradaEn,
			long registradaPor) {

		static MedicionEnVersion de(SesionVersionMedicion foto) {
			return new MedicionEnVersion(
					foto.getDefinicionId(),
					foto.getDefinicionCodigo(),
					foto.getDefinicionNombre(),
					foto.getDefinicionTipo(),
					foto.getDefinicionUnidad(),
					foto.getLateralidad(),
					foto.getValorNumerico(),
					foto.getValorTexto(),
					foto.getValorBooleano(),
					foto.getNota(),
					foto.getRegistradaEn(),
					foto.getRegistradaPorCuentaId());
		}
	}

	public static SesionVersionView de(SesionVersion version) {
		return new SesionVersionView(
				version.getNumeroVersion(),
				version.getMotivoClinico(),
				version.getDolorEva(),
				version.getDolorZona(),
				nombre(version.getDolorLateralidad()),
				nombre(version.getEvolucion()),
				version.getObjetivoSesion(),
				version.getLimitacionFuncional(),
				version.getNotaDeCierre(),
				version.getRespuestaTratamiento(),
				nombre(version.getTolerancia()),
				version.getIndicaciones(),
				nombre(version.getProximaConducta()),
				version.getMotivoEnmienda(),
				version.getRegistradaEn(),
				version.getRegistradaPor(),
				version.getTratamientos().stream().map(TratamientoEnVersion::de).toList(),
				version.getMediciones().stream().map(MedicionEnVersion::de).toList());
	}

	/** Los enums viajan como texto para que el cliente no dependa del enum de este modulo. */
	private static String nombre(Enum<?> valor) {
		return valor == null ? null : valor.name();
	}
}
