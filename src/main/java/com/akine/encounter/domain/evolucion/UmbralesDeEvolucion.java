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

	/** Una ventana en cero dejaria series vacias y una division por cero: se rechaza al arrancar. */
	public UmbralesDeEvolucion {
		if (minimoSesiones < 1 || dolorVentana < 1 || dolorVentanaAumento < 1 || evolucionPeorSeguidas < 1
				|| evolucionIgualSeguidas < 1 || toleranciaMalaSeguidas < 1 || medicionMinimoPuntos < 1
				|| adherenciaVentanaCitas < 1 || frecuenciaVentanaSemanas < 1 || sinMedicionesVentana < 1) {
			throw new IllegalStateException("akine.evolucion: las ventanas y los minimos deben ser 1 o mas");
		}
		if (dolorCambioRelevante < 0 || dolorPiso < 0 || medicionVariacionMinimaPorcentaje < 0
				|| adherenciaInasistenciasPorcentaje < 0) {
			throw new IllegalStateException("akine.evolucion: los umbrales no pueden ser negativos");
		}
	}

	/** Los valores del diseno, seccion 5.3. */
	public static UmbralesDeEvolucion porDefecto() {
		return new UmbralesDeEvolucion(3, 4, 2, 4, 3, 2, 4, 2, 4, 5, 6, 30, 4, 4);
	}
}
