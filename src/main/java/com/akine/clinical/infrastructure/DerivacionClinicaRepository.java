package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.DerivacionClinica;
import com.akine.clinical.domain.port.DerivacionRepositoryPorts.DerivacionClinicaRepositoryPort;
import com.akine.clinical.spi.OrigenDeParticipacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Persistencia del vinculo participacion-contexto clinico.
 *
 * <p><b>No expone ningun borrado</b>, ni se usa el {@code delete} heredado: deshacer una derivacion
 * es {@code estado = REVERTIDA} con motivo, actor e instante, y la fila queda (regla maestra 10).
 *
 * <p>Las tres consultas llevan {@code organizationId} en el {@code WHERE}, incluso la que resuelve
 * por PK. Sin ese predicado, un id de otro tenant devolveria la fila y la unica proteccion seria
 * que el servicio se acordara de comparar — que es exactamente la clase de garantia que se rompe
 * sola.
 */
public interface DerivacionClinicaRepository
		extends JpaRepository<DerivacionClinica, Long>, DerivacionClinicaRepositoryPort {

	@Override
	@Query("""
			SELECT d FROM DerivacionClinica d
			 WHERE d.organizationId = :organizationId
			   AND d.id = :derivacionId
			""")
	Optional<DerivacionClinica> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("derivacionId") long derivacionId);

	@Override
	@Query("""
			SELECT d FROM DerivacionClinica d
			 WHERE d.organizationId = :organizationId
			   AND d.origen = :origen
			   AND d.participacionId = :participacionId
			   AND d.casoClinicoId = :casoClinicoId
			   AND d.estado = com.akine.clinical.domain.EstadoDerivacion.VIGENTE
			""")
	Optional<DerivacionClinica> findVigenteAlCaso(
			@Param("organizationId") long organizationId,
			@Param("origen") OrigenDeParticipacion origen,
			@Param("participacionId") long participacionId,
			@Param("casoClinicoId") long casoClinicoId);

	@Override
	@Query("""
			SELECT d FROM DerivacionClinica d
			 WHERE d.organizationId = :organizationId
			   AND d.origen = :origen
			   AND d.participacionId = :participacionId
			 ORDER BY d.id DESC
			""")
	List<DerivacionClinica> findDeLaParticipacion(
			@Param("organizationId") long organizationId,
			@Param("origen") OrigenDeParticipacion origen,
			@Param("participacionId") long participacionId);
}
