package com.akine.clinical.spi;

/**
 * Si de una derivacion ya cuelga una atencion clinica (AKINE-08.05).
 *
 * <p>08.04 dejo escrito que revertir una derivacion de la que cuelga una Sesion tiene que ser
 * imposible, y que el cerrojo lo cierra la etapa que crea la tabla que lo hace posible. Esa tabla
 * es {@code sesion}, y es de {@code encounter}.
 *
 * <p><b>La interfaz vive aca y la implementa {@code encounter}</b>, por la misma razon que
 * {@link RealizadoEnElCasoProbe}: {@code clinical -> encounter} cerraria el ciclo con
 * {@code encounter -> clinical.spi}, que existe desde 06.01. {@code clinical} pregunta sin saber
 * quien responde.
 *
 * <p>No autoriza nada y no devuelve nada clinico: responde si existe, que es lo unico que la
 * reversion necesita para negarse.
 */
public interface AtencionesDeLaDerivacionProbe {

	/** {@code true} si alguna Sesion no dada de baja cuelga de esa derivacion. */
	boolean tieneAtencion(long organizationId, long derivacionId);
}
