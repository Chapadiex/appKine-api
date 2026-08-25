package com.akine.resource.application;

import java.time.Instant;
import java.util.List;

/**
 * Respuesta de la consulta base de disponibilidad (RF-M04-003).
 *
 * <h2>Que responde hoy, y que NO — dicho antes de que alguien lo asuma</h2>
 *
 * <p>Responde <b>si el recurso esta en servicio</b> para la ventana pedida: existe, no esta
 * dado de baja (RN-M04-002) y su vigencia cubre todo el intervalo. Eso es lo que la etapa
 * AKINE-02.02 llama "consulta base de disponibilidad", y es todo lo que se puede responder con
 * lo que existe.
 *
 * <p><b>NO responde si el recurso esta libre de reservas.</b> Los turnos son de
 * {@code scheduling} (F5, M12) y las inscripciones de {@code activity} (M28); ninguno de los
 * dos modulos existe. La forma de sumar esa mitad ya esta declarada
 * ({@code resource.spi.EspacioOccupancyProbe}) y no cambia este contrato: cuando haya
 * implementaciones, {@code lugaresComprometidos} deja de ser cero y
 * {@code lugaresDisponibles} baja. Un cliente escrito hoy sigue funcionando.
 *
 * <p>Decirlo por escrito importa: una pantalla que interprete {@code disponible = true} como
 * "el box esta libre" va a mostrar un box ocupado como libre en cuanto exista la agenda, y el
 * bug no va a parecer de esta etapa.
 *
 * @param lugaresComprometidos pico de ocupacion simultanea declarado por los modulos
 *                             consumidores. <b>Cero en F2</b>, porque no hay ninguno
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
