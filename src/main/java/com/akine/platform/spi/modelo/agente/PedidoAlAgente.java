package com.akine.platform.spi.modelo.agente;

import java.time.Duration;
import java.util.List;

/** Lo que se le manda al modelo en cada turno. */
public record PedidoAlAgente(String system, List<DefinicionDeHerramienta> herramientas,
		List<MensajeDelAgente> mensajes, Duration plazo) {
}
