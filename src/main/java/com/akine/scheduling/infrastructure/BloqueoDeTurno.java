package com.akine.scheduling.infrastructure;

import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.BloqueoDeTurnoPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * El control de concurrencia del check-in (AKINE E-4). Ver {@link BloqueoDeTurnoPort}.
 *
 * <p>Usa el {@code EntityManager} y no un repositorio derivado porque forzar la version de una
 * entidad ya cargada es {@code EntityManager#lock}, y Spring Data no lo expone.
 */
@Component
public class BloqueoDeTurno implements BloqueoDeTurnoPort {

	@PersistenceContext
	private EntityManager em;

	@Override
	public Optional<Turno> bloquear(long organizationId, long consultorioId, long turnoId) {
		List<Turno> encontrados = em.createQuery("""
						SELECT t FROM Turno t
						 WHERE t.organizationId = :organizationId
						   AND t.consultorioId = :consultorioId
						   AND t.id = :turnoId
						""", Turno.class)
				.setParameter("organizationId", organizationId)
				.setParameter("consultorioId", consultorioId)
				.setParameter("turnoId", turnoId)
				.setLockMode(LockModeType.PESSIMISTIC_WRITE)
				.getResultList();
		return encontrados.stream().findFirst();
	}

	/**
	 * <p>{@code PESSIMISTIC_FORCE_INCREMENT} y no {@code OPTIMISTIC_FORCE_INCREMENT}: el primero
	 * avanza la version en el acto y la entidad en memoria queda con la nueva, asi que el turno que
	 * se devuelve en la respuesta ya trae la version con la que el cliente puede seguir operando.
	 * El optimista la avanza al commitear, despues de armar la respuesta.
	 */
	@Override
	public void forzarVersion(Turno turno) {
		em.lock(turno, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
	}
}
