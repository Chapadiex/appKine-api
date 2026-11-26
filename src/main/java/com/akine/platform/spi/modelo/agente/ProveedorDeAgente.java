package com.akine.platform.spi.modelo.agente;

/**
 * Puerto de un modelo de lenguaje que conversa y puede pedir herramientas (tool calling).
 */
public interface ProveedorDeAgente {

	/** Nombre estable del proveedor: "claude", "compatible" o "prueba". */
	String nombre();

	/**
	 * Un turno del modelo.
	 *
	 * @throws com.akine.platform.spi.modelo.ModeloNoDisponibleException si no responde
	 */
	TurnoDelAgente responder(PedidoAlAgente pedido);
}
