package com.akine.activity.application;

/**
 * Los cupos de una clase (RF-M12-010).
 *
 * <p>Sostiene CA-M12-010-06 literal: "la agenda muestra 6/8 y admite exactamente dos confirmaciones
 * adicionales".
 *
 * <p><b>Ningun dato de persona.</b> Es la misma proyeccion que veria un paciente en una pantalla
 * publica futura, y que no haya nada que recortar despues es lo que lo hace seguro.
 *
 * @param capacidadEfectiva {@code min(clase, oferta, espacio)} (RN-M28-002). <b>Se calcula al
 *                          leer</b>: guardarla dejaria clases prometiendo lugares que el box ya no
 *                          tiene
 * @param ocupados          lugares otorgados. Sale de {@code clase_programada.cupo_ocupado}, que es
 *                          quien los otorga, no de un COUNT sobre las inscripciones
 * @param disponibles       nunca negativo: si la capacidad efectiva bajo por debajo de lo ocupado
 *                          —porque el box se cambio por uno mas chico— la respuesta correcta es
 *                          cero, no un numero rojo que ninguna pantalla sabe mostrar
 * @param enEspera          cuantos esperan. No consumen cupo (RN-M28-005)
 */
public record CuposView(
		long claseId,
		int capacidad,
		int capacidadEfectiva,
		int ocupados,
		int disponibles,
		int enEspera) {

	public static CuposView de(
			long claseId, int capacidad, int capacidadEfectiva, int ocupados, int enEspera) {

		return new CuposView(claseId, capacidad, capacidadEfectiva, ocupados,
				Math.max(0, capacidadEfectiva - ocupados), enEspera);
	}
}
