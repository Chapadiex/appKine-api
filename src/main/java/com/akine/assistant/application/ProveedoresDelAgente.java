package com.akine.assistant.application;

import com.akine.assistant.domain.exception.ModeloNoDisponibleException;
import com.akine.platform.spi.modelo.agente.PedidoAlAgente;
import com.akine.platform.spi.modelo.agente.ProveedorDeAgente;
import com.akine.platform.spi.modelo.agente.TurnoDelAgente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * El proveedor principal del agente y, si lo hay, el alternativo que se usa cuando el principal no
 * responde (503, plazo vencido, sin key, key revocada o modelo inexistente: todos llegan como
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

		/** Con un plazo fijo: igual que {@link #responder(PedidoAlAgente, Supplier)}. */
		public TurnoDelAgente responder(PedidoAlAgente pedido) {
			return responder(pedido, pedido::plazo);
		}

		/**
		 * @param restante lo que queda del tope total; se vuelve a pedir antes de ir al alternativo, para
		 *                 que el plazo que gasto el principal no se pague dos veces
		 * @throws ModeloNoDisponibleException si no responde ninguno o ya no queda tiempo (503)
		 */
		public TurnoDelAgente responder(PedidoAlAgente pedido, Supplier<Duration> restante) {
			if (!enAlternativo) {
				try {
					return principal.responder(conPlazo(pedido, restante.get()));
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
			Duration plazo = restante.get();
			if (plazo.isNegative() || plazo.isZero()) {
				throw new ModeloNoDisponibleException();
			}
			try {
				return alternativo.responder(conPlazo(pedido, plazo));
			}
			catch (com.akine.platform.spi.modelo.ModeloNoDisponibleException e) {
				throw new ModeloNoDisponibleException(e);
			}
		}

		private static PedidoAlAgente conPlazo(PedidoAlAgente pedido, Duration plazo) {
			return new PedidoAlAgente(pedido.system(), pedido.herramientas(), pedido.mensajes(), plazo);
		}

		/** El nombre del proveedor que respondio la ultima vuelta, para la auditoria. */
		public String nombre() {
			ProveedorDeAgente actual = enAlternativo ? alternativo : principal;
			return actual == null ? "ninguno" : actual.nombre();
		}
	}
}
