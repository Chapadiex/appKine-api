package com.akine.scheduling.infrastructure;

import com.akine.scheduling.domain.TurnoEvento;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoEventoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Persistencia del historial de turnos.
 *
 * <p>Extiende {@code JpaRepository} —que trae {@code delete} y {@code save}— pero el resto del
 * codigo solo alcanza el puerto {@link TurnoEventoRepositoryPort}, que no los declara. Es la misma
 * forma que usa la auditoria de plataforma: la inmutabilidad la sostiene el contrato que los
 * servicios ven, no la clase base.
 */
public interface TurnoEventoRepository
		extends JpaRepository<TurnoEvento, Long>, TurnoEventoRepositoryPort {

	@Override
	default TurnoEvento registrar(TurnoEvento evento) {
		return save(evento);
	}

	/**
	 * <p>{@code ORDER BY} por instante y despues por id: dos eventos de la misma operacion
	 * comparten el {@code ocurridoEn} —se toma una sola vez por transaccion, a proposito, para que
	 * el historial no invente diferencias de microsegundos— y sin el desempate por id el orden
	 * entre ellos quedaria a criterio del motor.
	 */
	@Override
	@Query("""
			SELECT e FROM TurnoEvento e
			 WHERE e.organizationId = :organizationId
			   AND e.turnoId = :turnoId
			 ORDER BY e.ocurridoEn ASC, e.id ASC
			""")
	List<TurnoEvento> historial(
			@Param("organizationId") long organizationId,
			@Param("turnoId") long turnoId);
}
