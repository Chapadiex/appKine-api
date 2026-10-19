package com.akine.billing.infrastructure;

import com.akine.billing.domain.LiquidacionHonorario;
import com.akine.billing.domain.LiquidacionHonorarioClase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Consultas de {@code liquidacion_honorario} y su detalle (AKINE-08.09b). Todas llevan
 * {@code organizationId}. La implementacion del puerto es {@link LiquidacionHonorarioRepositoryAdapter}:
 * el puerto mezcla las dos entidades y Spring Data no puede derivar el alta del detalle.
 */
interface LiquidacionHonorarioRepository extends JpaRepository<LiquidacionHonorario, Long> {

	@Query("""
			SELECT l FROM LiquidacionHonorario l
			 WHERE l.organizationId = :organizationId
			   AND l.consultorioId = :consultorioId
			   AND l.id = :liquidacionId
			""")
	Optional<LiquidacionHonorario> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("liquidacionId") long liquidacionId);

	@Query("""
			SELECT l FROM LiquidacionHonorario l
			 WHERE l.organizationId = :organizationId
			   AND l.idempotencyKey = :clave
			""")
	Optional<LiquidacionHonorario> findByIdempotencyKey(
			@Param("organizationId") long organizationId, @Param("clave") String clave);

	@Query("""
			SELECT l FROM LiquidacionHonorario l
			 WHERE l.organizationId = :organizationId
			   AND l.egresoId = :egresoId
			""")
	Optional<LiquidacionHonorario> findDelEgreso(
			@Param("organizationId") long organizationId, @Param("egresoId") long egresoId);

	@Query("""
			SELECT c FROM LiquidacionHonorarioClase c
			 WHERE c.organizationId = :organizationId
			   AND c.liquidacionId = :liquidacionId
			 ORDER BY c.claseInicio, c.claseId
			""")
	List<LiquidacionHonorarioClase> clasesDe(
			@Param("organizationId") long organizationId, @Param("liquidacionId") long liquidacionId);

	@Query("""
			SELECT c.claseId, l.egresoId
			  FROM LiquidacionHonorarioClase c, LiquidacionHonorario l
			 WHERE l.id = c.liquidacionId
			   AND c.organizationId = :organizationId
			   AND c.colaboradorMembershipId = :colaborador
			   AND c.claseId IN :claseIds
			   AND c.liberadaKey = 0
			""")
	List<Object[]> clasesVigentesDelColaborador(
			@Param("organizationId") long organizationId,
			@Param("colaborador") long colaboradorMembershipId,
			@Param("claseIds") Collection<Long> claseIds);

	@Query("""
			SELECT COUNT(c) > 0 FROM LiquidacionHonorarioClase c
			 WHERE c.organizationId = :organizationId
			   AND c.claseId = :claseId
			   AND c.liberadaKey = 0
			""")
	boolean existeVigenteDeLaClase(
			@Param("organizationId") long organizationId, @Param("claseId") long claseId);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE liquidacion_honorario_clase
			   SET liberada_key = id
			 WHERE organization_id = :organizationId
			   AND liquidacion_id = :liquidacionId
			   AND liberada_key = 0
			""", nativeQuery = true)
	int liberarClases(
			@Param("organizationId") long organizationId, @Param("liquidacionId") long liquidacionId);
}
