package com.akine.resource.infrastructure;

import com.akine.resource.domain.Espacio;
import com.akine.resource.domain.port.EspacioRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a los espacios de una sede. Toda consulta filtra por {@code organizationId} y por
 * {@code consultorioId}: un id de otro tenant o de otra sede no debe resolver nunca.
 */
public interface EspacioRepository extends JpaRepository<Espacio, Long>, EspacioRepositoryPort {

	@Override
	Optional<Espacio> findByIdAndOrganizationIdAndConsultorioId(
			Long id, Long organizationId, Long consultorioId);

	/**
	 * Lock exclusivo sobre la fila del espacio.
	 *
	 * <p>Consulta NATIVA a proposito, igual que {@code countActiveForShare} en
	 * {@code organization}: aca la sentencia es exactamente la que corre y eso es justamente lo
	 * que hay que poder auditar. {@code @Lock(PESSIMISTIC_WRITE)} sobre un metodo derivado
	 * funciona, pero deja la clausula que decide la correccion fuera de la vista de quien lee
	 * la consulta.
	 *
	 * <p>Devuelve {@code Espacio} y no un proyeccion porque el llamador la muta a continuacion
	 * dentro de la misma transaccion; Hibernate la trae ya gestionada.
	 */
	@Query(value = """
			SELECT * FROM espacio
			 WHERE id = :id
			   AND organization_id = :organizationId
			   AND consultorio_id = :consultorioId
			 FOR UPDATE
			""", nativeQuery = true)
	Optional<Espacio> findByIdForUpdate(
			@Param("id") Long id,
			@Param("organizationId") Long organizationId,
			@Param("consultorioId") Long consultorioId);

	@Override
	List<Espacio> findAllByOrganizationIdAndConsultorioIdOrderByNameAsc(
			Long organizationId, Long consultorioId);

	@Override
	List<Espacio> findAllByOrganizationIdAndConsultorioIdAndActiveOrderByNameAsc(
			Long organizationId, Long consultorioId, boolean active);

	/**
	 * Espacios en servicio durante toda la ventana {@code [desde, hasta)}.
	 *
	 * <p>Las tres condiciones son RN-M04-002 escrita en SQL:
	 * <ul>
	 *   <li>{@code active = true} — no se ofrece un recurso dado de baja;</li>
	 *   <li>{@code valid_from <= :desde} — ya entro en servicio cuando la ventana empieza;</li>
	 *   <li>{@code valid_until IS NULL OR valid_until >= :hasta} — sigue en servicio cuando la
	 *       ventana termina. Se exige que cubra TODA la ventana y no solo su inicio: una sesion
	 *       que arranca el ultimo dia de servicio de un box y termina despues no se puede
	 *       reservar ahi.</li>
	 * </ul>
	 *
	 * <p>Es JPQL y no nativa porque no lleva ninguna clausula de bloqueo: es una lectura pura y
	 * el planificador la resuelve por {@code ix_espacio_vigencia}.
	 */
	@Query("""
			SELECT e FROM Espacio e
			 WHERE e.organizationId = :organizationId
			   AND e.consultorioId = :consultorioId
			   AND e.active = true
			   AND e.validFrom <= :desde
			   AND (e.validUntil IS NULL OR e.validUntil >= :hasta)
			 ORDER BY e.name ASC
			""")
	List<Espacio> findEnServicio(
			@Param("organizationId") Long organizationId,
			@Param("consultorioId") Long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);
}
