package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.PlanTratamiento;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanTratamientoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Persistencia de {@link PlanTratamiento}.
 *
 * <p><b>No hay ni un metodo que resuelva por id pelado.</b> Toda consulta lleva
 * {@code organizationId}. En un modulo clinico un {@code findById} heredado y usado por descuido no
 * es una fuga de tenant cualquiera: es el tratamiento en curso de un paciente de otro centro.
 *
 * <p><b>Y no hay ningun {@code findWithLock...}</b>, a diferencia de {@link CasoClinicoRepository}.
 * Alla el cambio de equipo solo tocaba tablas hijas y hacia falta forzar el incremento de la
 * version del padre; aca toda escritura ensucia {@code plan_tratamiento} —el estado, o el contador
 * de versiones— asi que el UPDATE versionado que emite JPA ya da la garantia, y forzarlo ademas la
 * haria avanzar dos veces y devolveria {@code leida + 1}. Es el defecto que 04.02 corrigio.
 *
 * <p>Los listados desempatan por {@code id DESC} ademas de por instante, mismo criterio que el
 * resto del modulo: dos planes creados en la misma transaccion comparten instante hasta el
 * microsegundo, y sin el desempate el orden entre ellos seria el que quiera MySQL.
 */
public interface PlanTratamientoRepository
		extends JpaRepository<PlanTratamiento, Long>, PlanTratamientoRepositoryPort {

	@Override
	Optional<PlanTratamiento> findByIdAndOrganizationId(Long id, Long organizationId);

	@Override
	@Query("""
			SELECT p FROM PlanTratamiento p
			 WHERE p.organizationId = :organizationId
			   AND p.casoClinicoId = :casoClinicoId
			   AND (:soloVigentes = false
			        OR p.estado <> com.akine.clinical.domain.EstadoPlan.FINALIZADO)
			 ORDER BY p.creadoEn DESC, p.id DESC
			""")
	List<PlanTratamiento> buscarDeCaso(
			@Param("organizationId") Long organizationId,
			@Param("casoClinicoId") Long casoClinicoId,
			@Param("soloVigentes") boolean soloVigentes);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Calza con {@code ix_plan_tratamiento_caso}
	 * {@code (organization_id, caso_clinico_id, estado, creado_en)}. Devuelve como mucho una fila
	 * porque {@code uk_plan_activo_por_caso} lo hace cumplir del lado del motor: el {@code LIMIT 1}
	 * esta para que un esquema roto no explote con un {@code NonUniqueResultException} en medio de
	 * una activacion, no para elegir entre varios.
	 */
	@Override
	@Query("""
			SELECT p FROM PlanTratamiento p
			 WHERE p.organizationId = :organizationId
			   AND p.casoClinicoId = :casoClinicoId
			   AND p.estado IN (com.akine.clinical.domain.EstadoPlan.ACTIVO,
			                    com.akine.clinical.domain.EstadoPlan.SUSPENDIDO)
			 ORDER BY p.id DESC
			 LIMIT 1
			""")
	Optional<PlanTratamiento> buscarQueOcupaElLugarDelCaso(
			@Param("organizationId") Long organizationId,
			@Param("casoClinicoId") Long casoClinicoId);
}
