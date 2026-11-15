package com.akine.encounter.infrastructure.config;

import com.akine.encounter.domain.evolucion.ConfiguracionDeEvolucion;
import com.akine.encounter.domain.evolucion.UmbralesDeEvolucion;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code akine.evolucion.*}: el interruptor de M31 (apagado por defecto) y los umbrales de las
 * alertas. Cada umbral arranca en el valor del diseno; cambiarlo en produccion requiere la
 * aprobacion clinica de U-02 (RN-M31-004).
 */
@ConfigurationProperties(prefix = "akine.evolucion")
public class EvolucionProperties {

	private static final UmbralesDeEvolucion DEFECTO = UmbralesDeEvolucion.porDefecto();

	private boolean enabled;
	private int minimoSesiones = DEFECTO.minimoSesiones();
	private int dolorVentana = DEFECTO.dolorVentana();
	private int dolorCambioRelevante = DEFECTO.dolorCambioRelevante();
	private int dolorPiso = DEFECTO.dolorPiso();
	private int dolorVentanaAumento = DEFECTO.dolorVentanaAumento();
	private int evolucionPeorSeguidas = DEFECTO.evolucionPeorSeguidas();
	private int evolucionIgualSeguidas = DEFECTO.evolucionIgualSeguidas();
	private int toleranciaMalaSeguidas = DEFECTO.toleranciaMalaSeguidas();
	private int medicionMinimoPuntos = DEFECTO.medicionMinimoPuntos();
	private int medicionVariacionMinimaPorcentaje = DEFECTO.medicionVariacionMinimaPorcentaje();
	private int adherenciaVentanaCitas = DEFECTO.adherenciaVentanaCitas();
	private int adherenciaInasistenciasPorcentaje = DEFECTO.adherenciaInasistenciasPorcentaje();
	private int frecuenciaVentanaSemanas = DEFECTO.frecuenciaVentanaSemanas();
	private int sinMedicionesVentana = DEFECTO.sinMedicionesVentana();

	ConfiguracionDeEvolucion aConfiguracion() {
		return new ConfiguracionDeEvolucion(enabled, new UmbralesDeEvolucion(
				minimoSesiones, dolorVentana, dolorCambioRelevante, dolorPiso, dolorVentanaAumento,
				evolucionPeorSeguidas, evolucionIgualSeguidas, toleranciaMalaSeguidas,
				medicionMinimoPuntos, medicionVariacionMinimaPorcentaje, adherenciaVentanaCitas,
				adherenciaInasistenciasPorcentaje, frecuenciaVentanaSemanas, sinMedicionesVentana));
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getMinimoSesiones() {
		return minimoSesiones;
	}

	public void setMinimoSesiones(int minimoSesiones) {
		this.minimoSesiones = minimoSesiones;
	}

	public int getDolorVentana() {
		return dolorVentana;
	}

	public void setDolorVentana(int dolorVentana) {
		this.dolorVentana = dolorVentana;
	}

	public int getDolorCambioRelevante() {
		return dolorCambioRelevante;
	}

	public void setDolorCambioRelevante(int dolorCambioRelevante) {
		this.dolorCambioRelevante = dolorCambioRelevante;
	}

	public int getDolorPiso() {
		return dolorPiso;
	}

	public void setDolorPiso(int dolorPiso) {
		this.dolorPiso = dolorPiso;
	}

	public int getDolorVentanaAumento() {
		return dolorVentanaAumento;
	}

	public void setDolorVentanaAumento(int dolorVentanaAumento) {
		this.dolorVentanaAumento = dolorVentanaAumento;
	}

	public int getEvolucionPeorSeguidas() {
		return evolucionPeorSeguidas;
	}

	public void setEvolucionPeorSeguidas(int evolucionPeorSeguidas) {
		this.evolucionPeorSeguidas = evolucionPeorSeguidas;
	}

	public int getEvolucionIgualSeguidas() {
		return evolucionIgualSeguidas;
	}

	public void setEvolucionIgualSeguidas(int evolucionIgualSeguidas) {
		this.evolucionIgualSeguidas = evolucionIgualSeguidas;
	}

	public int getToleranciaMalaSeguidas() {
		return toleranciaMalaSeguidas;
	}

	public void setToleranciaMalaSeguidas(int toleranciaMalaSeguidas) {
		this.toleranciaMalaSeguidas = toleranciaMalaSeguidas;
	}

	public int getMedicionMinimoPuntos() {
		return medicionMinimoPuntos;
	}

	public void setMedicionMinimoPuntos(int medicionMinimoPuntos) {
		this.medicionMinimoPuntos = medicionMinimoPuntos;
	}

	public int getMedicionVariacionMinimaPorcentaje() {
		return medicionVariacionMinimaPorcentaje;
	}

	public void setMedicionVariacionMinimaPorcentaje(int medicionVariacionMinimaPorcentaje) {
		this.medicionVariacionMinimaPorcentaje = medicionVariacionMinimaPorcentaje;
	}

	public int getAdherenciaVentanaCitas() {
		return adherenciaVentanaCitas;
	}

	public void setAdherenciaVentanaCitas(int adherenciaVentanaCitas) {
		this.adherenciaVentanaCitas = adherenciaVentanaCitas;
	}

	public int getAdherenciaInasistenciasPorcentaje() {
		return adherenciaInasistenciasPorcentaje;
	}

	public void setAdherenciaInasistenciasPorcentaje(int adherenciaInasistenciasPorcentaje) {
		this.adherenciaInasistenciasPorcentaje = adherenciaInasistenciasPorcentaje;
	}

	public int getFrecuenciaVentanaSemanas() {
		return frecuenciaVentanaSemanas;
	}

	public void setFrecuenciaVentanaSemanas(int frecuenciaVentanaSemanas) {
		this.frecuenciaVentanaSemanas = frecuenciaVentanaSemanas;
	}

	public int getSinMedicionesVentana() {
		return sinMedicionesVentana;
	}

	public void setSinMedicionesVentana(int sinMedicionesVentana) {
		this.sinMedicionesVentana = sinMedicionesVentana;
	}
}
