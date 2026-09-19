package com.akine.clinical.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Clase de hecho clinico que registra una entrada (RF-M09-006).
 *
 * <h2>Por que la lista es corta, y por que es cerrada</h2>
 *
 * <p>El tipo es lo unico de la entrada que el <b>timeline</b> puede mostrar: el challenge seccion
 * 4 prohibe que un evento del timeline lleve texto de evolucion, diagnostico o medicion, asi que
 * la etiqueta de la fila es esta y nada mas. Una lista larga no haria mas legible ese indice; una
 * lista abierta lo haria ilegible, porque cada centro nombraria distinto al mismo hecho.
 *
 * <p>Es una lista <b>del producto</b> y no del tenant, mismo criterio que {@link TipoAntecedente},
 * {@code espacio.tipo} (V19) y {@code persona.tipo_documento} (V27).
 *
 * <p><b>{@code OTRO} existe para no bloquear un registro clinico</b> por una clase que este
 * catalogo no previo. En un modulo clinico el costo de los dos errores no es simetrico: una
 * entrada mal clasificada se reclasifica, una entrada que no se pudo escribir se pierde.
 */
public enum TipoEntradaClinica {

	/** Evolucion del paciente escrita fuera de una sesion cerrada. */
	EVOLUCION,

	/** Indicacion clinica: reposo, pautas, derivacion a ejercicio. */
	INDICACION,

	/** Pedido o devolucion de interconsulta con otro profesional. */
	INTERCONSULTA,

	/** Observacion clinica que no dirige la conducta ni evoluciona al paciente. */
	OBSERVACION,

	OTRO;

	/** El tipo con ese nombre, o vacio si no existe: es un 400 del cliente, no un 500. */
	public static Optional<TipoEntradaClinica> desde(String nombre) {
		if (nombre == null || nombre.isBlank()) {
			return Optional.empty();
		}
		String normalizado = nombre.strip().toUpperCase();
		return Arrays.stream(values()).filter(t -> t.name().equals(normalizado)).findFirst();
	}
}
