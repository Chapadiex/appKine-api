package com.akine.encounter.application;

import com.akine.encounter.domain.TipoDatoParametro;
import com.akine.encounter.domain.TratamientoParametro;
import com.akine.encounter.domain.TratamientoRealizado;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Lo que sale del service cuando se lee o se escribe una intervencion realizada.
 *
 * <p>Es una vista y no la entity: <b>las entities nunca cruzan el borde del service</b>
 * (AGENT.md seccion 4, regla 6, verificada por ArchUnit).
 *
 * <p>Lleva {@code practicaCodigo}, {@code practicaNombre} y {@code espacioNombre} desde el
 * <b>snapshot</b> de la fila y no resolviendolos contra el catalogo: es el punto de tenerlos
 * congelados. Una sesion de marzo leida en septiembre dice que practica fue en marzo.
 *
 * @param sesionVersion version de la SESION despues de la escritura. Viaja con cada mutacion para
 *                      que la pantalla no tenga que repedir la sesion: la escritura de un
 *                      tratamiento hace avanzar esa version por el force-increment, y un cliente
 *                      que siguiera mandando la vieja comeria un 409 en su proxima operacion
 */
public record TratamientoView(
		long id,
		long sesionId,
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
		Instant registradoEn,
		boolean vigente,
		List<ParametroView> parametros,
		long sesionVersion) {

	/** Un parametro tipado, con el valor ya desempaquetado de la columna que le toca. */
	public record ParametroView(
			String clave,
			TipoDatoParametro tipoDato,
			BigDecimal valorNumerico,
			String valorTexto,
			Boolean valorBooleano,
			String unidad,
			int orden) {

		static ParametroView de(TratamientoParametro parametro) {
			return new ParametroView(
					parametro.getClave(),
					parametro.getTipoDato(),
					parametro.getValorNumerico(),
					parametro.getValorTexto(),
					parametro.getValorBooleano(),
					parametro.getUnidad(),
					parametro.getOrden());
		}
	}

	static TratamientoView de(
			TratamientoRealizado tratamiento,
			List<TratamientoParametro> parametros,
			long sesionVersion) {

		return new TratamientoView(
				tratamiento.getId(),
				tratamiento.getSesionId(),
				tratamiento.getOrden(),
				tratamiento.getPracticaId(),
				tratamiento.getPracticaCodigo(),
				tratamiento.getPracticaNombre(),
				tratamiento.getTecnica(),
				tratamiento.getZona(),
				tratamiento.getLateralidad() == null ? null : tratamiento.getLateralidad().name(),
				tratamiento.getDuracionMinutos(),
				tratamiento.getProfesionalMembershipId(),
				tratamiento.getEspacioId(),
				tratamiento.getEspacioNombre(),
				tratamiento.getObservacion(),
				tratamiento.getRegistradoEn(),
				tratamiento.estaVigente(),
				parametros.stream().map(ParametroView::de).toList(),
				sesionVersion);
	}
}
