package com.akine.encounter.infrastructure.config;

import com.akine.encounter.domain.evolucion.ConfiguracionDeEvolucion;
import com.akine.encounter.domain.evolucion.UmbralesDeEvolucion;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code akine.evolucion.*}: el interruptor de M31 (apagado por defecto) y los umbrales de las
 * alertas. Cada umbral arranca en el valor del diseno (los mismos que
 * {@link UmbralesDeEvolucion#porDefecto()}, cosa que verifica {@code EvolucionPropertiesTest});
 * cambiarlo en produccion requiere la aprobacion clinica de U-02 (RN-M31-004).
 */
@ConfigurationProperties(prefix = "akine.evolucion")
public record EvolucionProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("3") int minimoSesiones,
		@DefaultValue("4") int dolorVentana,
		@DefaultValue("2") int dolorCambioRelevante,
		@DefaultValue("4") int dolorPiso,
		@DefaultValue("3") int dolorVentanaAumento,
		@DefaultValue("2") int evolucionPeorSeguidas,
		@DefaultValue("4") int evolucionIgualSeguidas,
		@DefaultValue("2") int toleranciaMalaSeguidas,
		@DefaultValue("4") int medicionMinimoPuntos,
		@DefaultValue("5") int medicionVariacionMinimaPorcentaje,
		@DefaultValue("6") int adherenciaVentanaCitas,
		@DefaultValue("30") int adherenciaInasistenciasPorcentaje,
		@DefaultValue("4") int frecuenciaVentanaSemanas,
		@DefaultValue("4") int sinMedicionesVentana) {

	ConfiguracionDeEvolucion aConfiguracion() {
		return new ConfiguracionDeEvolucion(enabled, new UmbralesDeEvolucion(
				minimoSesiones, dolorVentana, dolorCambioRelevante, dolorPiso, dolorVentanaAumento,
				evolucionPeorSeguidas, evolucionIgualSeguidas, toleranciaMalaSeguidas,
				medicionMinimoPuntos, medicionVariacionMinimaPorcentaje, adherenciaVentanaCitas,
				adherenciaInasistenciasPorcentaje, frecuenciaVentanaSemanas, sinMedicionesVentana));
	}
}
