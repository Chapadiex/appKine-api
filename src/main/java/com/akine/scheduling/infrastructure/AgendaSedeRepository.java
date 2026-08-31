package com.akine.scheduling.infrastructure;

import com.akine.scheduling.domain.AgendaSede;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.AgendaSedeRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Ver {@link AgendaSede}: esta tabla existe unicamente para el lock de abajo. */
public interface AgendaSedeRepository extends JpaRepository<AgendaSede, Long>, AgendaSedeRepositoryPort {

	/**
	 * Lock exclusivo sobre la fila de agenda de la sede. Serializa TODAS las reservas de esa sede.
	 *
	 * <p>Consulta NATIVA a proposito, igual que {@code CalendarioSedeRepository#lockByScope} y por
	 * el mismo motivo: la clausula que decide la correctitud —{@code FOR UPDATE}— tiene que estar
	 * a la vista de quien lee la consulta, y no escondida detras de un
	 * {@code @Lock(PESSIMISTIC_WRITE)} sobre un metodo derivado del nombre.
	 *
	 * <p><b>No bloquea filas de {@code turno}.</b> Bloquear las que ya existen no impediria que
	 * otra transaccion inserte una nueva en el hueco, que es el caso a evitar; MySQL 8.4 no tiene
	 * exclusion constraints. Por eso se bloquea una fila que siempre existe.
	 */
	@Query(value = """
			SELECT * FROM agenda_sede
			 WHERE organization_id = :organizationId
			   AND consultorio_id = :consultorioId
			 FOR UPDATE
			""", nativeQuery = true)
	@Override
	Optional<AgendaSede> lockByScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId);

	/**
	 * Crea la fila si no existe. <b>Sin lanzar nunca</b>, y ese es todo el punto.
	 *
	 * <p>El camino obvio —leer, y si no esta insertar— tiene dos defectos que solo aparecen con
	 * concurrencia real, y los dos los encontro {@code TurnoConcurrenteIT} contra MySQL:
	 *
	 * <ol>
	 *   <li><b>Deadlock.</b> Cuando N reservas concurrentes son las primeras de una sede, las N
	 *       leen "no existe" y las N intentan el mismo INSERT. InnoDB traba los locks del hueco del
	 *       indice unico y el perdedor no recibe una violacion limpia sino
	 *       {@code CannotAcquireLockException}.</li>
	 *   <li><b>Rollback-only.</b> Envolver el INSERT en un try/catch <b>no alcanza</b>: atrapar una
	 *       excepcion de persistencia no des-marca la transaccion, asi que Spring igual intenta
	 *       commitear una transaccion marcada para rollback y el llamador recibe
	 *       {@code UnexpectedRollbackException}. La excepcion hay que EVITARLA, no atraparla.</li>
	 * </ol>
	 *
	 * <p>{@code ON DUPLICATE KEY UPDATE id = id} es un no-op deliberado: el motor resuelve la
	 * carrera en una sola sentencia atomica, sin excepcion y sin lectura previa. Es especifico de
	 * MySQL, que es la base fijada por DP-09.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO agenda_sede (organization_id, consultorio_id, created_at)
			VALUES (:organizationId, :consultorioId, UTC_TIMESTAMP(6))
			ON DUPLICATE KEY UPDATE id = id
			""", nativeQuery = true)
	@Override
	void crearSiFalta(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId);
}
