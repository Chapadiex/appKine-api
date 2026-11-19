package com.akine.scheduling.spi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Lectura de turnos para modulos que no son duenos de M12.
 *
 * <p><b>No autoriza nada.</b> Es una costura entre modulos: quien la llama ya resolvio pertenencia
 * y permiso con su propio criterio. El permiso de la agenda —{@code turno:read}— no es el mismo que
 * el de atender, asi que reusar el servicio de aplicacion de turnos habria exigido el permiso
 * equivocado.
 *
 * <p>La firma lleva {@code organizationId} y {@code consultorioId} en el {@code WHERE}, como todas
 * las de este proyecto: sin el predicado, datos de dos ambitos se mezclan bajo un mismo id y el
 * resultado no falla, inventa.
 */
public interface TurnoDirectory {

	Optional<TurnoSnapshot> find(long organizationId, long consultorioId, long turnoId);

	/**
	 * Los turnos de una persona en la sede que EMPIEZAN en {@code [desde, hasta)}, del mas temprano
	 * al mas tardio, <b>en cualquier estado y aunque esten cancelados</b>: quien llama mira
	 * {@link TurnoSnapshot#estado()}. Lo usa {@code encounter} para saber con que turno de hoy se
	 * corresponde una atencion dictada al asistente (AKINE-asistente-acciones §6.3).
	 */
	List<TurnoSnapshot> deLaPersonaEnVentana(
			long organizationId, long consultorioId, long personaId, Instant desde, Instant hasta);

	/**
	 * {@code true} si el profesional tiene, en esa sede, un turno vivo con esa persona: RESERVADO,
	 * o CONFIRMADO, tambien con el paciente en la recepcion (desde E-4 la espera no es un estado del
	 * turno). Un turno CANCELADO o AUSENTE no cuenta. Existe para que otros modulos
	 * puedan demostrar relacion asistencial por agenda sin traer turnos.
	 */
	boolean existeTurnoVivoDeProfesionalConPersona(
			long organizationId, long consultorioId, long profesionalMembershipId, long personaId);

	/**
	 * {@code true} si la recepcion vigente de ese turno se resolvio como <b>Particular</b>
	 * (RF-M13-005, RF-M08-007; AKINE E-7). {@code false} si no hubo recepcion, si se anulo, si se
	 * valido con cobertura o si quedo observada sin resolver: en todos esos casos el devengo sigue
	 * buscando cobertura como antes de E-7.
	 *
	 * <p>Lo lee {@code encounter} una sola vez, al cerrar la sesion, y lo pone en
	 * {@code SesionCerrada}: asi el devengo y el consumo de autorizaciones ven la misma decision
	 * del mostrador. Es una foto: si la recepcion cambia despues del cierre, la deuda ya devengada
	 * no se mueve.
	 */
	boolean atendidoComoParticular(long organizationId, long consultorioId, long turnoId);
}
