package com.akine.offering.infrastructure;

import com.akine.offering.domain.OfertaServicioConsultorio;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaRepositoryPort;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a las Ofertas de servicio de una sede. Toda consulta filtra por
 * {@code organizationId} y por {@code consultorioId}: un id de otro tenant o de otra sede no
 * debe resolver nunca. No hay FK compuesta {@code (organization_id, consultorio_id)} en la
 * migracion V24 —mismo caso que {@code espacio}—, asi que esta garantia la sostienen
 * exclusivamente estas consultas, no el esquema. Ver el javadoc completo en
 * {@code OfertaRepositoryPort}.
 *
 * <p>{@code save} y {@code saveAndFlush} los satisface {@link JpaRepository} tal cual: las
 * firmas del puerto coinciden con las suyas. Las cuatro consultas restantes son derivadas: no
 * hay columnas generadas que consultar (a diferencia de {@code servicio} y del catalogo clinico)
 * y Spring Data valida sus nombres contra el esquema real al levantar el contexto — un typo aca
 * es un fallo de arranque, no un no-op silencioso.
 */
public interface OfertaRepository
		extends JpaRepository<OfertaServicioConsultorio, Long>, OfertaRepositoryPort {

	@Override
	Optional<OfertaServicioConsultorio> findByIdAndOrganizationIdAndConsultorioId(
			Long id, Long organizationId, Long consultorioId);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Es la MISMA consulta derivada que la de arriba y lo unico que la distingue es el
	 * {@code OPTIMISTIC_FORCE_INCREMENT}: Hibernate le sube la version a la oferta al cerrar la
	 * transaccion aunque el reemplazo no haya tocado ninguna de sus columnas. Sin eso el control
	 * optimista de las habilitaciones no serializa nada. Ver el javadoc del puerto.
	 *
	 * <p><b>El nombre es derivable a proposito y no lleva {@code @Query}.</b> Spring Data ignora
	 * el texto entre {@code find} y {@code By}, asi que {@code WithLock} documenta el metodo sin
	 * cambiar la consulta. Un JPQL escrito a mano aca solo se valida al levantar el contexto —un
	 * typo seria un fallo de arranque— mientras que el nombre derivado lo valida Spring Data
	 * contra el esquema real, que es la misma garantia que ya tienen las otras cuatro consultas.
	 */
	@Override
	@Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
	Optional<OfertaServicioConsultorio> findWithLockByIdAndOrganizationIdAndConsultorioId(
			Long id, Long organizationId, Long consultorioId);

	@Override
	List<OfertaServicioConsultorio> findAllByOrganizationIdAndConsultorioIdOrderByNombreComercialAsc(
			Long organizationId, Long consultorioId);

	@Override
	List<OfertaServicioConsultorio>
			findAllByOrganizationIdAndConsultorioIdAndActiveOrderByNombreComercialAsc(
					Long organizationId, Long consultorioId, boolean active);

	@Override
	List<OfertaServicioConsultorio>
			findAllByOrganizationIdAndConsultorioIdAndServicioIdOrderByNombreComercialAsc(
					Long organizationId, Long consultorioId, Long servicioId);
}
