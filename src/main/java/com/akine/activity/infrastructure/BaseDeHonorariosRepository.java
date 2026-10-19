package com.akine.activity.infrastructure;

import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.port.HonorariosRepositoryPorts.BaseDeHonorariosPort;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Las lecturas del borrador de honorarios por clase (AKINE-08.09b, DP-38).
 *
 * <p>Nativas y a la vista por lo mismo que {@code ClaseProgramadaRepository#lockByIdInScope}: en la
 * de liquidar, la clausula que decide la correctitud es el {@code FOR UPDATE ... ORDER BY id}.
 *
 * <p>{@code inicio_us} sale de {@code TIMESTAMPDIFF(MICROSECOND, ...)} contra la epoca y no de
 * {@code UNIX_TIMESTAMP}, que interpreta el {@code DATETIME} con la zona de la sesion; la columna
 * guarda UTC. Mismo criterio que {@code ActividadEnElReporteRepository}.
 */
public interface BaseDeHonorariosRepository extends Repository<ClaseProgramada, Long>, BaseDeHonorariosPort {

	String CLASES_DICTADAS = """
			SELECT c.id,
			       c.oferta_id,
			       TIMESTAMPDIFF(MICROSECOND, '1970-01-01', c.inicio) AS inicio_us,
			       c.titulo,
			       c.profesional_membership_id
			  FROM clase_programada c
			 WHERE c.organization_id = :organizationId
			   AND c.consultorio_id = :consultorioId
			   AND c.estado = 'REALIZADA'
			   AND c.deleted_at IS NULL
			   AND c.inicio >= :desde
			   AND c.inicio < :hasta
			   AND (c.profesional_membership_id = :colaborador
			        OR EXISTS (SELECT 1 FROM clase_instructor i
			                    WHERE i.organization_id = c.organization_id
			                      AND i.clase_id = c.id
			                      AND i.membership_id = :colaborador
			                      AND i.removido_en IS NULL))
			""";

	@Override
	@Query(value = CLASES_DICTADAS + " ORDER BY c.inicio, c.id", nativeQuery = true)
	List<Object[]> clasesDictadas(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("colaborador") long colaboradorMembershipId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);

	@Override
	@Query(value = CLASES_DICTADAS + " ORDER BY c.id FOR UPDATE", nativeQuery = true)
	List<Object[]> clasesDictadasParaLiquidar(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("colaborador") long colaboradorMembershipId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);

	@Override
	@Query(value = """
			SELECT a.clase_id, COUNT(*)
			  FROM asistencia_actividad a
			 WHERE a.organization_id = :organizationId
			   AND a.clase_id IN (:claseIds)
			   AND a.resultado IN ('PRESENTE', 'PRESENTE_TARDE')
			 GROUP BY a.clase_id
			""", nativeQuery = true)
	List<Object[]> presentesPorClase(
			@Param("organizationId") long organizationId, @Param("claseIds") Collection<Long> claseIds);
}
