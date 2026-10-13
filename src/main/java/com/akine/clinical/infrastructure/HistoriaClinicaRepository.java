package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Persistencia de {@link HistoriaClinica}.
 *
 * <p>Implementa el puerto de {@code domain} en vez de exponerse: la capa de aplicacion inyecta
 * {@code HistoriaClinicaRepositoryPort} y no sabe que existe Spring Data. Es la regla que 01.01
 * dejo fijada.
 *
 * <p><b>No hay ni un metodo que resuelva por id pelado.</b> Toda consulta lleva
 * {@code organizationId}: un {@code findById} heredado de {@code JpaRepository} usado por descuido
 * en un modulo clinico es una fuga de tenant, y la unica forma segura de que no aparezca es no
 * declarar la variante que lo invite.
 */
public interface HistoriaClinicaRepository
		extends JpaRepository<HistoriaClinica, Long>, HistoriaClinicaRepositoryPort {

	@Override
	Optional<HistoriaClinica> findByIdAndOrganizationId(Long id, Long organizationId);

	@Override
	@Query("""
			SELECT hc FROM HistoriaClinica hc
			 WHERE hc.organizationId = :organizationId
			   AND hc.personaId = :personaId
			   AND hc.active = true
			""")
	Optional<HistoriaClinica> buscarVigentePorPersona(
			@Param("organizationId") Long organizationId, @Param("personaId") Long personaId);

	/**
	 * {@code INSERT IGNORE}: el choque contra el unique queda en advertencia y no en excepcion.
	 *
	 * <p>{@code IGNORE} tambien degrada otros errores —una FK, un {@code NOT NULL}— a advertencias.
	 * Por eso el servicio relee despues y falla si no encuentra la historia, y por eso esta lista
	 * de columnas tiene que seguir a la tabla: una columna {@code NOT NULL} nueva sin default
	 * entraria aca con el valor implicito del tipo, sin error.
	 */
	@Override
	@Modifying
	@Query(value = """
			INSERT IGNORE INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
			                                     active, version, created_at, updated_at)
			VALUES (:organizationId, :personaId, UTC_TIMESTAMP(6), :abiertaPor, 1, 0,
			        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
			""", nativeQuery = true)
	int abrirSiFalta(
			@Param("organizationId") long organizationId,
			@Param("personaId") long personaId,
			@Param("abiertaPor") long abiertaPor);

	@Override
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("""
			SELECT hc FROM HistoriaClinica hc
			 WHERE hc.organizationId = :organizationId
			   AND hc.personaId = :personaId
			   AND hc.active = true
			""")
	Optional<HistoriaClinica> releerVigenteBloqueando(
			@Param("organizationId") long organizationId, @Param("personaId") long personaId);
}
