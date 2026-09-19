package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.CasoClinico;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoClinicoRepositoryPort;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Persistencia de {@link CasoClinico}.
 *
 * <p>Implementa el puerto de {@code domain} en vez de exponerse, igual que
 * {@link EntradaClinicaRepository}: la capa de aplicacion no sabe que existe Spring Data.
 *
 * <p><b>No hay ni un metodo que resuelva por id pelado.</b> Toda consulta lleva
 * {@code organizationId}. En un modulo clinico un {@code findById} heredado y usado por descuido
 * no es una fuga de tenant cualquiera: es la historia clinica de otro centro.
 *
 * <p>Los dos listados desempatan por {@code id DESC} ademas de por instante, mismo criterio que el
 * resto del modulo: dos casos abiertos en la misma transaccion comparten instante hasta el
 * microsegundo, y sin el desempate el orden entre ellos seria el que quiera MySQL.
 */
public interface CasoClinicoRepository
		extends JpaRepository<CasoClinico, Long>, CasoClinicoRepositoryPort {

	@Override
	Optional<CasoClinico> findByIdAndOrganizationId(Long id, Long organizationId);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Es la MISMA consulta derivada que la de arriba y lo unico que la distingue es el
	 * {@code OPTIMISTIC_FORCE_INCREMENT}. El nombre es derivable a proposito y no lleva
	 * {@code @Query}: Spring Data ignora el texto entre {@code find} y {@code By}, asi que
	 * {@code WithLock} documenta el metodo sin cambiar la consulta y sigue validandose contra el
	 * esquema real. Mismo patron que {@link EntradaClinicaRepository}.
	 */
	@Override
	@Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
	Optional<CasoClinico> findWithLockByIdAndOrganizationId(Long id, Long organizationId);

	@Override
	@Query("""
			SELECT c FROM CasoClinico c
			 WHERE c.organizationId = :organizationId
			   AND c.historiaClinicaId = :historiaClinicaId
			   AND (:soloActivos = false OR c.estado = com.akine.clinical.domain.EstadoCaso.ACTIVO)
			 ORDER BY c.abiertoEn DESC, c.id DESC
			""")
	List<CasoClinico> buscarDeHistoria(
			@Param("organizationId") Long organizationId,
			@Param("historiaClinicaId") Long historiaClinicaId,
			@Param("soloActivos") boolean soloActivos);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Calza con {@code ix_caso_clinico_oferta}
	 * {@code (organization_id, historia_clinica_id, oferta_id, estado)}. No tiene tope: un paciente
	 * no acumula cientos de casos activos de la misma oferta, y si los acumulara, eso es
	 * justamente lo que quien recibe el 409 tiene que ver entero.
	 */
	@Override
	@Query("""
			SELECT c FROM CasoClinico c
			 WHERE c.organizationId = :organizationId
			   AND c.historiaClinicaId = :historiaClinicaId
			   AND c.ofertaId = :ofertaId
			   AND c.estado = com.akine.clinical.domain.EstadoCaso.ACTIVO
			 ORDER BY c.abiertoEn DESC, c.id DESC
			""")
	List<CasoClinico> buscarActivosPorOferta(
			@Param("organizationId") Long organizationId,
			@Param("historiaClinicaId") Long historiaClinicaId,
			@Param("ofertaId") Long ofertaId);
}
