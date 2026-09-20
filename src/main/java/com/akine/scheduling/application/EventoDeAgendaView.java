package com.akine.scheduling.application;

import java.time.Instant;

/**
 * Un evento de la agenda unificada (RF-M12-011, RF-M12-013).
 *
 * <h2>Es una proyeccion de lectura, no una entidad</h2>
 *
 * <p><b>No existe una tabla {@code evento_agenda}, ni herencia, ni migracion de un solo turno.</b>
 * Turno y ClaseProgramada siguen siendo agregados separados, con sus propias reglas, sus propias
 * maquinas de estado y sus propios comandos. RF-M12-013 lo pide explicitamente —"no migrar
 * automaticamente historicos de Turno a una nueva entidad destructiva"— y el plan de la etapa lo
 * repite: "no polimorfismo prematuro destructivo".
 *
 * <p>Lo que esto da es una <b>union discriminada</b>: el cliente lee {@link #tipo} y sabe que
 * acciones ofrecer y a que endpoint mandarlas. Un turno se cancela en {@code /turnos/{id}/cancelacion}
 * y una clase en {@code /clases/{id}/cancelacion}; la grilla los dibuja juntos, el backend no los
 * fusiona.
 *
 * <p><b>El id no es unico entre tipos.</b> Un turno 7 y una clase 7 existen a la vez, y la clave
 * real de una fila de esta lista es el par {@code (tipo, eventoId)}. Un cliente que use solo el id
 * va a mezclar dos eventos distintos.
 *
 * <h2>Lo que NO lleva</h2>
 *
 * <p><b>Nada clinico y ninguna lista de participantes.</b> De un turno viaja la persona, que es lo
 * que la recepcion necesita para llamar a alguien; de una clase no viaja ninguna, porque una lista
 * de inscriptos en la grilla es PHI a la vista de todo el mostrador. La decision queda escrita para
 * que 08.02 no la agregue por inercia.
 *
 * @param tipo       {@code TURNO} o {@code CLASE}
 * @param capacidad  cuantas personas admite el evento. 1 para un turno individual
 * @param ocupados   cuantos lugares estan tomados
 * @param personaId  solo para {@code TURNO}. {@code null} en una clase
 */
public record EventoDeAgendaView(
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
		int ocupados,
		Long personaId) {
}
