package com.akine.scheduling.spi;

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
}
