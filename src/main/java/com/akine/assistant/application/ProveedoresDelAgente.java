package com.akine.assistant.application;

import com.akine.assistant.domain.exception.ModeloNoDisponibleException;
import com.akine.platform.spi.modelo.agente.PedidoAlAgente;
import com.akine.platform.spi.modelo.agente.ProveedorDeAgente;
import com.akine.platform.spi.modelo.agente.TurnoDelAgente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * El proveedor principal del agente y, si lo hay, el alternativo que se usa cuando el principal no
 * responde (503, plazo vencido o sin key: todos llegan como
 * {@link com.akine.platform.spi.modelo.ModeloNoDisponibleException}).
 *
 * <p>Una conversacion que paso al alternativo se queda en el: {@link Conversacion} lo recuerda, asi el
 * principal no vuelve a pagar su plazo en cada vuelta.
 *
 * @param principal   {@code null} si no hay un bean con ese nombre (por ejemplo, sin key)
 * @param alternativo {@code null} si no se configuro
 */
public record ProveedoresDelAgente(ProveedorDeAgente principal, ProveedorDeAgente alternativo) {

	private static final Logger log = LoggerFactory.getLogger(ProveedoresDelAgente.class);

	public Conversacion conversacion() {
		return new Conversacion();
	}

	/** El proveedor elegido para una consulta; no es thread-safe ni se comparte entre consultas. */
	public final class Conversacion {

		private boolean enAlternativo = principal == null;

		private Conversacion() {
		}

		/** @throws ModeloNoDisponibleException si no responde ninguno (503) */
		public TurnoDelAgente responder(PedidoAlAgente pedido) {
			if (!enAlternativo) {
				try {
					return principal.responder(pedido);
				}
				catch (com.akine.platform.spi.modelo.ModeloNoDisponibleException e) {
					if (alternativo == null) {
						throw new ModeloNoDisponibleException(e);
					}
					log.warn("El proveedor {} del agente no respondio; sigue {}", principal.nombre(), alternativo.nombre());
					enAlternativo = true;
				}
			}
			if (alternativo == null) {
				throw new ModeloNoDisponibleException();
			}
			try {
				return alternativo.responder(pedido);
			}
			catch (com.akine.platform.spi.modelo.ModeloNoDisponibleException e) {
				throw new ModeloNoDisponibleException(e);
			}
		}

		/** El nombre del proveedor que respondio la ultima vuelta, para la auditoria. */
		public String nombre() {
			ProveedorDeAgente actual = enAlternativo ? alternativo : principal;
			return actual == null ? "ninguno" : actual.nombre();
		}
	}
}
