package com.akine.scheduling.spi;

import java.time.Instant;
import java.util.List;

/**
 * Los eventos de agenda que <b>no son turnos</b>, para la proyeccion unificada de M12
 * (RF-M12-011, RF-M12-013). Es la costura hacia M28 en el camino de lectura.
 *
 * <h2>Por que es una interfaz aparte de {@link OcupacionExternaProbe}</h2>
 *
 * <p>La implementa la misma clase, pero tienen razones distintas para cambiar: aquella se consulta
 * <b>bajo el lock</b>, en el camino que decide la correctitud, y esta fuera de toda transaccion, en
 * el camino que dibuja una pantalla. Mezclarlas haria que un cambio pensado para la grilla pueda
 * tocar la exclusion.
 *
 * <h2>Lo que esta proyeccion NO es</h2>
 *
 * <p><b>No es una entidad {@code EventoAgenda}.</b> No hay tabla, no hay herencia y no se migra un
 * solo turno: Turno y ClaseProgramada siguen siendo agregados separados, con sus propias reglas y
 * sus propios comandos. RF-M12-013 lo pide explicitamente —"no migrar automaticamente historicos de
 * Turno a una nueva entidad destructiva"— y el plan de la etapa lo repite: "no polimorfismo
 * prematuro destructivo".
 *
 * <p><b>No lleva participantes.</b> Ni nombres, ni lista de inscriptos, ni nada personal: la
 * seguridad de la etapa exige vista sin lista de participantes, y la decision queda escrita aca
 * para que 08.02 no la agregue por inercia al proyectar.
 */
public interface EventoExternoDeAgenda {

	/**
	 * Los eventos no-turno de una sede que empiezan dentro de {@code [desde, hasta)}.
	 *
	 * <p>Se filtra por {@code inicio} y no por solapamiento, igual que la agenda del dia de los
	 * turnos: la recepcion piensa en "lo de hoy", que es lo que empieza hoy.
	 *
	 * <p><b>Incluye los cancelados.</b> Alguien puede presentarse a una clase que se cancelo, y
	 * esconderla deja al mostrador sin nada que decirle.
	 */
	List<EventoDeAgendaExterno> enVentana(
			long organizationId, long consultorioId, Instant desde, Instant hasta);

	/**
	 * Un evento externo, ya proyectado a la forma comun de la agenda (RF-M12-013, paso 5).
	 *
	 * @param tipo      discriminador del evento: {@code CLASE} hoy. <b>La union es discriminada</b>,
	 *                  y el cliente decide por este campo que acciones ofrece
	 * @param capacidad capacidad efectiva, ya resuelta por el modulo dueno
	 * @param ocupados  cuantos lugares estan tomados
	 */
	record EventoDeAgendaExterno(
			String tipo,
			long eventoId,
			Instant inicio,
			Instant fin,
			String estado,
			long ofertaId,
			String ofertaNombre,
			String titulo,
			Long profesionalId,
			Long espacioId,
			int capacidad,
			int ocupados) {
	}
}
