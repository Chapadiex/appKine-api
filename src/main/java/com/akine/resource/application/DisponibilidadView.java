package com.akine.resource.application;

import java.time.Instant;
import java.util.List;

/**
 * Respuesta de la consulta base de disponibilidad (RF-M04-003).
 *
 * <h2>Que responde, y que NO</h2>
 *
 * <p>Responde si el recurso esta en servicio para la ventana pedida —existe, no esta dado de
 * baja (RN-M04-002) y su vigencia cubre todo el intervalo— y cuantos lugares tiene
 * comprometidos segun las implementaciones de {@code resource.spi.EspacioOccupancyProbe}. Desde
 * el paquete E-1 hay una, {@code scheduling.infrastructure.EspacioOcupadoPorTurnos}, que
 * declara el pico de turnos pendientes desde el inicio de la ventana en adelante.
 *
 * <p>No responde si una franja concreta esta libre: el pico no se acota a {@code hasta}, asi
 * que es una cota conservadora. Las inscripciones de {@code activity} no tienen sonda.
 *
 * @param lugaresComprometidos pico de ocupacion simultanea declarado por los modulos
 *                             consumidores, desde el inicio de la ventana en adelante
 * @param lugaresDisponibles   {@code capacidad - lugaresComprometidos}, nunca negativo
 */
public record DisponibilidadView(
		long espacioId,
		String name,
		String tipo,
		int capacidad,
		Instant desde,
		Instant hasta,
		boolean disponible,
		long lugaresComprometidos,
		long lugaresDisponibles) {
}
