package com.akine.encounter.domain;

import java.util.List;

/**
 * Lo que una version de la sesion fotografia ademas del relato: los tratamientos vigentes con
 * sus parametros y las mediciones, tal como estan en las tablas vivas en ese instante (C-6).
 *
 * <p>Es un valor de paso entre quien lee las tablas vivas ({@code SesionService}) y quien copia
 * la foto ({@link SesionVersion}): la version no lee repositorios, y el servicio no sabe como se
 * guarda una foto.
 *
 * @param tratamientos los tratamientos VIGENTES, en orden cronologico
 * @param mediciones   las mediciones de la sesion
 */
public record FotoClinica(List<Tratamiento> tratamientos, List<SesionMedicion> mediciones) {

	public FotoClinica {
		tratamientos = tratamientos == null ? List.of() : List.copyOf(tratamientos);
		mediciones = mediciones == null ? List.of() : List.copyOf(mediciones);
	}

	/** Un tratamiento vivo con sus parametros. */
	public record Tratamiento(TratamientoRealizado tratamiento, List<TratamientoParametro> parametros) {

		public Tratamiento {
			parametros = parametros == null ? List.of() : List.copyOf(parametros);
		}
	}
}
