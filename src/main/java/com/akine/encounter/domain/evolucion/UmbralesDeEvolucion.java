package com.akine.encounter.domain.evolucion;

/**
 * Los umbrales de las alertas (RN-M31-004). Son configuracion, no reglas de negocio: los valores
 * por defecto son del diseno y esperan aprobacion clinica.
 */
public record UmbralesDeEvolucion(
		int minimoSesiones,
		int dolorVentana,
		int dolorCambioRelevante,
		int dolorPiso,
		int dolorVentanaAumento,
		int evolucionPeorSeguidas,
		int evolucionIgualSeguidas,
		int toleranciaMalaSeguidas,
		int medicionMinimoPuntos,
		int medicionVariacionMinimaPorcentaje,
		int adherenciaVentanaCitas,
		int adherenciaInasistenciasPorcentaje,
		int frecuenciaVentanaSemanas,
		int sinMedicionesVentana) {

	/** Los valores del diseno, seccion 5.3. */
	public static UmbralesDeEvolucion porDefecto() {
		return new UmbralesDeEvolucion(3, 4, 2, 4, 3, 2, 4, 2, 4, 5, 6, 30, 4, 4);
	}
}
