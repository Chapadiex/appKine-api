package com.akine.platform.spi.modelo.agente;

/**
 * Que proveedor usa el agente del asistente en este despliegue, para mostrarlo en el estado del motor. Solo
 * lee la configuracion: nunca devuelve la key, solo si hay una.
 */
public interface ConfiguracionDelAgente {

	ProveedorConfigurado principal();

	/** Nulo si no hay alternativo configurado. */
	ProveedorConfigurado alternativo();

	/**
	 * @param nombre   {@code claude}, {@code compatible} o {@code prueba}
	 * @param modelo   el modelo configurado; nulo si el proveedor no tiene uno
	 * @param tieneKey si hay una key cargada (nunca la key)
	 */
	record ProveedorConfigurado(String nombre, String modelo, boolean tieneKey) {
	}
}
