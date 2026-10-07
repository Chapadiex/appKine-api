package com.akine.scheduling.infrastructure;

import com.akine.scheduling.domain.EstadoDeSerie;
import com.akine.scheduling.domain.TurnoSerie;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoSerieRepositoryPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TurnoSerieRepository
		extends JpaRepository<TurnoSerie, Long>, TurnoSerieRepositoryPort {

	/** Centinela de "sin filtro" para los parametros numericos de la bandeja (E-8). */
	long SIN_FILTRO = -1L;

	@Override
	@Query("""
			SELECT s FROM TurnoSerie s
			 WHERE s.organizationId = :organizationId
			   AND s.consultorioId = :consultorioId
			   AND s.id = :serieId
			""")
	Optional<TurnoSerie> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("serieId") long serieId);

	@Override
	@Query("""
			SELECT s FROM TurnoSerie s
			 WHERE s.organizationId = :organizationId
			   AND s.idempotencyKey = :idempotencyKey
			""")
	Optional<TurnoSerie> findByIdempotencyKey(
			@Param("organizationId") long organizationId,
			@Param("idempotencyKey") String idempotencyKey);

	@Override
	default List<TurnoSerie> listar(
			long organizationId, long consultorioId, Long personaId, EstadoDeSerie estado,
			Instant ahora, int pagina, int tamano) {

		return buscarBandeja(organizationId, consultorioId, filtroPersona(personaId),
				filtroEstado(estado), ahora, PageRequest.of(pagina, tamano));
	}

	@Override
	default long contar(
			long organizationId, long consultorioId, Long personaId, EstadoDeSerie estado, Instant ahora) {

		return contarBandeja(organizationId, consultorioId, filtroPersona(personaId),
				filtroEstado(estado), ahora);
	}

	/**
	 * La bandeja de series (E-8). El estado es DERIVADO de los turnos —la serie no tiene columna de
	 * estado—: {@code VIGENTE} si existe un turno de la serie RESERVADO o CONFIRMADO, vivo y que
	 * todavia no empezo. El subquery usa {@code ix_turno_serie_inicio (organization_id, serie_id,
	 * inicio)}. Mismo {@code WHERE} que {@link #contarBandeja}, palabra por palabra.
	 */
	@Query("""
			SELECT s FROM TurnoSerie s
			 WHERE s.organizationId = :organizationId
			   AND s.consultorioId = :consultorioId
			   AND (:personaId = -1 OR s.personaId = :personaId)
			   AND (:estadoFiltro = -1
			        OR (:estadoFiltro = 1 AND EXISTS (
			              SELECT 1 FROM Turno t
			               WHERE t.organizationId = s.organizationId
			                 AND t.serieId = s.id
			                 AND t.deletedAt IS NULL
			                 AND t.estado IN (
			                     com.akine.scheduling.domain.EstadoTurno.RESERVADO,
			                     com.akine.scheduling.domain.EstadoTurno.CONFIRMADO)
			                 AND t.inicio > :ahora))
			        OR (:estadoFiltro = 0 AND NOT EXISTS (
			              SELECT 1 FROM Turno t
			               WHERE t.organizationId = s.organizationId
			                 AND t.serieId = s.id
			                 AND t.deletedAt IS NULL
			                 AND t.estado IN (
			                     com.akine.scheduling.domain.EstadoTurno.RESERVADO,
			                     com.akine.scheduling.domain.EstadoTurno.CONFIRMADO)
			                 AND t.inicio > :ahora)))
			 ORDER BY s.id DESC
			""")
	List<TurnoSerie> buscarBandeja(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("personaId") long personaId,
			@Param("estadoFiltro") int estadoFiltro,
			@Param("ahora") Instant ahora,
			Pageable pagina);

	@Query("""
			SELECT COUNT(s) FROM TurnoSerie s
			 WHERE s.organizationId = :organizationId
			   AND s.consultorioId = :consultorioId
			   AND (:personaId = -1 OR s.personaId = :personaId)
			   AND (:estadoFiltro = -1
			        OR (:estadoFiltro = 1 AND EXISTS (
			              SELECT 1 FROM Turno t
			               WHERE t.organizationId = s.organizationId
			                 AND t.serieId = s.id
			                 AND t.deletedAt IS NULL
			                 AND t.estado IN (
			                     com.akine.scheduling.domain.EstadoTurno.RESERVADO,
			                     com.akine.scheduling.domain.EstadoTurno.CONFIRMADO)
			                 AND t.inicio > :ahora))
			        OR (:estadoFiltro = 0 AND NOT EXISTS (
			              SELECT 1 FROM Turno t
			               WHERE t.organizationId = s.organizationId
			                 AND t.serieId = s.id
			                 AND t.deletedAt IS NULL
			                 AND t.estado IN (
			                     com.akine.scheduling.domain.EstadoTurno.RESERVADO,
			                     com.akine.scheduling.domain.EstadoTurno.CONFIRMADO)
			                 AND t.inicio > :ahora)))
			""")
	long contarBandeja(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("personaId") long personaId,
			@Param("estadoFiltro") int estadoFiltro,
			@Param("ahora") Instant ahora);

	private static long filtroPersona(Long personaId) {
		return personaId == null ? SIN_FILTRO : personaId;
	}

	private static int filtroEstado(EstadoDeSerie estado) {
		if (estado == null) {
			return (int) SIN_FILTRO;
		}
		return estado == EstadoDeSerie.VIGENTE ? 1 : 0;
	}
}
