package com.akine.encounter.infrastructure;

import com.akine.encounter.domain.ConteoDeSesionesPorOferta;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SesionRepository extends JpaRepository<Sesion, Long>, SesionRepositoryPort {

	@Override
	@Query("""
			SELECT s FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.consultorioId = :consultorioId
			   AND s.id = :sesionId
			""")
	Optional<Sesion> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("sesionId") long sesionId);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Es la MISMA consulta que la de arriba y lo unico que la distingue es el
	 * {@code OPTIMISTIC_FORCE_INCREMENT}. El nombre lleva {@code WithLock} para documentarlo:
	 * Spring Data ignora el texto entre {@code find} y {@code By}, asi que no cambia nada de la
	 * consulta. Mismo patron que {@code CasoClinicoRepository} y {@code EntradaClinicaRepository}.
	 */
	@Override
	@Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
	@Query("""
			SELECT s FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.consultorioId = :consultorioId
			   AND s.id = :sesionId
			""")
	Optional<Sesion> findWithLockByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("sesionId") long sesionId);

	/**
	 * <p>Sin filtro por sede a proposito: un turno pertenece a UNA sede, asi que agregarlo no
	 * acota nada y abriria la puerta a que un llamador pase la sede equivocada y reciba
	 * {@code empty} en vez de la sesion que existe — lo que haria que el segundo inicio creara una
	 * segunda sesion y chocara contra el unique.
	 */
	@Override
	@Query("""
			SELECT s FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.turnoId = :turnoId
			   AND s.deletedAt IS NULL
			""")
	Optional<Sesion> findVivaPorTurno(
			@Param("organizationId") long organizationId,
			@Param("turnoId") long turnoId);

	/**
	 * <p>{@code ORDER BY iniciadaEn DESC} con {@code LIMIT 1} via {@code Optional}: Spring Data lo
	 * traduce a un {@code LIMIT}, y el indice {@code ix_sesion_comparacion} de V34 lo sostiene. Sin
	 * ese indice esta consulta recorre toda la historia del paciente en cada apertura de sesion.
	 */
	@Override
	@Query("""
			SELECT s FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.historiaClinicaId = :historiaClinicaId
			   AND s.iniciadaEn < :antesDe
			   AND s.evaluadaEn IS NOT NULL
			   AND s.deletedAt IS NULL
			 ORDER BY s.iniciadaEn DESC
			 LIMIT 1
			""")
	Optional<Sesion> findPreviaEvaluada(
			@Param("organizationId") long organizationId,
			@Param("historiaClinicaId") long historiaClinicaId,
			@Param("antesDe") Instant antesDe);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Nativa y con {@code LIMIT :limite} porque la pagina del timeline se recorta <b>en la
	 * base</b>: un paciente cronico con 900 sesiones no puede traerlas todas para descartar 890 en
	 * memoria. Calza con {@code ix_sesion_cerradas}
	 * {@code (organization_id, historia_clinica_id, cerrada_en)}.
	 *
	 * <p>Se filtra por {@code estado} <b>y</b> por {@code cerrada_en IS NOT NULL} aunque el CHECK
	 * de V35 los ate: el {@code ORDER BY} sobre una columna nullable ordenaria las abiertas al
	 * final en vez de dejarlas afuera, y un dato que el timeline no puede datar no es un evento.
	 *
	 * <p>El desempate por {@code id DESC} mantiene el orden estable entre paginas, igual que en las
	 * tres fuentes de {@code clinical}.
	 */
	@Override
	@Query(value = """
			SELECT * FROM sesion s
			 WHERE s.organization_id = :organizationId
			   AND s.historia_clinica_id = :historiaClinicaId
			   AND s.estado = 'CERRADA'
			   AND s.cerrada_en IS NOT NULL
			   AND s.cerrada_en <= :hasta
			   AND s.deleted_at IS NULL
			   AND (:casoId IS NULL OR s.caso_id = :casoId)
			 ORDER BY s.cerrada_en DESC, s.id DESC
			 LIMIT :limite
			""", nativeQuery = true)
	List<Sesion> buscarCerradasParaTimeline(
			@Param("organizationId") long organizationId,
			@Param("historiaClinicaId") long historiaClinicaId,
			@Param("hasta") Instant hasta,
			@Param("limite") int limite,
			@Param("casoId") Long casoId);

	/**
	 * {@inheritDoc}
	 *
	 * <p>JPQL con expresion de constructor y no nativa, al reves que la consulta del timeline. La
	 * diferencia es lo que devuelve cada una: aquella trae filas enteras y se recorta con
	 * {@code LIMIT}, esto trae una agregacion de tres columnas que Hibernate valida contra el
	 * modelo al arrancar la aplicacion. Con una nativa, un alias mal escrito aparece recien en
	 * runtime — y esta etapa no puede correr tests de integracion.
	 *
	 * <p>Calza con {@code ix_sesion_caso} {@code (organization_id, caso_id, cerrada_en)}. Se filtra
	 * por {@code estado} y por {@code deletedAt} y <b>no</b> por historia: el caso ya acota a un
	 * paciente, y agregar la historia obligaria al llamador a resolverla para preguntar algo que no
	 * la necesita.
	 */
	@Override
	@Query("""
			SELECT new com.akine.encounter.domain.ConteoDeSesionesPorOferta(
			           s.ofertaId,
			           SUM(CASE WHEN s.asistencia = com.akine.encounter.domain.Asistencia.PRESENTE
			                    THEN 1L ELSE 0L END),
			           SUM(CASE WHEN s.asistencia = com.akine.encounter.domain.Asistencia.AUSENTE
			                    THEN 1L ELSE 0L END))
			  FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.casoId = :casoId
			   AND s.estado = com.akine.encounter.domain.EstadoSesion.CERRADA
			   AND s.deletedAt IS NULL
			 GROUP BY s.ofertaId
			""")
	List<ConteoDeSesionesPorOferta> contarCerradasPorOferta(
			@Param("organizationId") long organizationId,
			@Param("casoId") long casoId);
}
