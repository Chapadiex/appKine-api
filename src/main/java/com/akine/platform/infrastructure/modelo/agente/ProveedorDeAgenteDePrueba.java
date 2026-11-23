package com.akine.platform.infrastructure.modelo.agente;

import com.akine.platform.spi.modelo.agente.MensajeDelAgente;
import com.akine.platform.spi.modelo.agente.PedidoAlAgente;
import com.akine.platform.spi.modelo.agente.ProveedorDeAgente;
import com.akine.platform.spi.modelo.agente.TurnoDelAgente;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proveedor para tests: devuelve turnos de un guion elegido por la primera palabra de la ultima
 * pregunta de la persona. Cada pedido del mismo guion avanza al turno siguiente.
 */
public class ProveedorDeAgenteDePrueba implements ProveedorDeAgente {

	private static final TurnoDelAgente SIN_GUION = new TurnoDelAgente("Sin guion", List.of(), null, null);

	private final Map<String, List<TurnoDelAgente>> guiones;
	private final Map<String, Integer> posicion = new ConcurrentHashMap<>();

	public ProveedorDeAgenteDePrueba(Map<String, List<TurnoDelAgente>> guiones) {
		this.guiones = Map.copyOf(guiones);
	}

	@Override
	public String nombre() {
		return "prueba";
	}

	@Override
	public TurnoDelAgente responder(PedidoAlAgente pedido) {
		String clave = claveDe(pedido);
		List<TurnoDelAgente> turnos = guiones.get(clave);
		if (turnos == null || turnos.isEmpty()) {
			return SIN_GUION;
		}
		int i = posicion.merge(clave, 1, Integer::sum) - 1;
		return turnos.get(Math.min(i, turnos.size() - 1));
	}

	private static String claveDe(PedidoAlAgente pedido) {
		String pregunta = "";
		for (MensajeDelAgente m : pedido.mensajes()) {
			if (m instanceof MensajeDelAgente.DelUsuario u) {
				pregunta = u.texto();
			}
		}
		String[] palabras = pregunta.strip().split("\\s+");
		return palabras[0].toLowerCase(Locale.ROOT);
	}
}
