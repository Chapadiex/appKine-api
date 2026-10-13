package com.akine.scheduling.domain;

import java.time.Instant;

/**
 * Lo unico que el motor de slots necesita de un turno vivo para descontar cupo: a que hora
 * empieza y que recursos ocupa.
 *
 * <p>Es una proyeccion y no la entidad a proposito (docs/carga.md §9): la busqueda de slots lee
 * los turnos de cada dia de la ventana, y cargar el {@link Turno} entero —treinta columnas,
 * registrado en el contexto de persistencia— era la consulta que mas CPU gastaba de toda la
 * agenda.
 */
public record OcupacionDeTurno(Instant inicio, Long profesionalMembershipId, Long espacioId) {
}
