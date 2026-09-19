package com.akine.encounter.infrastructure;

import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
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
			 ORDER BY s.cerrada_en DESC, s.id DESC
			 LIMIT :limite
			""", nativeQuery = true)
	List<Sesion> buscarCerradasParaTimeline(
			@Param("organizationId") long organizationId,
			@Param("historiaClinicaId") long historiaClinicaId,
			@Param("hasta") Instant hasta,
			@Param("limite") int limite);
}
