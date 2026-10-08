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

	/** Un turno de la serie {@code s}, con alias {@code t}, vivo o no: los cancelados llevan {@code deleted_at}. */
	String TURNO_DE_LA_SERIE = """
			SELECT 1 FROM Turno t
			 WHERE t.organizationId = s.organizationId
			   AND t.serieId = s.id
			""";

	/** Le queda un turno pendiente: RESERVADO o CONFIRMADO, vivo y que todavia no empezo. */
	String HAY_PENDIENTE = " EXISTS (" + TURNO_DE_LA_SERIE + """
			   AND t.deletedAt IS NULL
			   AND t.estado IN (
			       com.akine.scheduling.domain.EstadoTurno.RESERVADO,
			       com.akine.scheduling.domain.EstadoTurno.CONFIRMADO)
			   AND t.inicio > :ahora) """;

	/**
	 * Su ultimo turno esta CANCELADO (DP-20): existe un cancelado sin ningun turno no cancelado que
	 * empiece en el mismo instante o despues. Sin {@link #HAY_PENDIENTE} delante no alcanza: un
	 * ultimo turno cancelado suelto con pendientes antes sigue siendo una serie VIGENTE.
	 */
	String ULTIMO_CANCELADO = " EXISTS (" + TURNO_DE_LA_SERIE + """
			   AND t.estado = com.akine.scheduling.domain.EstadoTurno.CANCELADO
			   AND NOT EXISTS (
			       SELECT 1 FROM Turno u
			        WHERE u.organizationId = s.organizationId
			          AND u.serieId = s.id
			          AND u.estado <> com.akine.scheduling.domain.EstadoTurno.CANCELADO
			          AND u.inicio >= t.inicio)) """;

	/**
	 * El {@code WHERE} de la bandeja (E-8, E-8b). El estado es DERIVADO de los turnos —la serie no
	 * tiene columna de estado— y es exactamente {@link EstadoDeSerie#de} escrito en JPQL:
	 * {@code VIGENTE} si le queda un pendiente; si no, {@code CANCELADA} si su ultimo turno esta
	 * cancelado y {@code FINALIZADA} si no. Los subqueries usan {@code ix_turno_serie_inicio
	 * (organization_id, serie_id, inicio)}. Una sola constante para {@link #buscarBandeja} y
	 * {@link #contarBandeja}: no pueden divergir.
	 */
	String WHERE_BANDEJA = """
			 WHERE s.organizationId = :organizationId
			   AND s.consultorioId = :consultorioId
			   AND (:personaId = -1 OR s.personaId = :personaId)
			   AND (:estadoFiltro = -1
			        OR (:estadoFiltro = 1 AND""" + HAY_PENDIENTE + """
			)
			        OR (:estadoFiltro = 2 AND NOT""" + HAY_PENDIENTE + "AND" + ULTIMO_CANCELADO + """
			)
			        OR (:estadoFiltro = 0 AND NOT""" + HAY_PENDIENTE + "AND NOT" + ULTIMO_CANCELADO + """
			))
			""";

	/** La bandeja de series (E-8), mas nuevas primero. */
	@Query("SELECT s FROM TurnoSerie s " + WHERE_BANDEJA + " ORDER BY s.id DESC")
	List<TurnoSerie> buscarBandeja(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("personaId") long personaId,
			@Param("estadoFiltro") int estadoFiltro,
			@Param("ahora") Instant ahora,
			Pageable pagina);

	@Query("SELECT COUNT(s) FROM TurnoSerie s " + WHERE_BANDEJA)
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
		return switch (estado) {
			case VIGENTE -> 1;
			case CANCELADA -> 2;
			case FINALIZADA -> 0;
		};
	}
}
