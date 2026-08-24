package com.akine.organization.infrastructure;

import com.akine.organization.domain.Consultorio;
import com.akine.organization.domain.port.ConsultorioRepositoryPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a los consultorios de una organizacion. Toda consulta filtra por
 * {@code organizationId}: un id de consultorio de otro tenant no debe resolver nunca.
 */
public interface ConsultorioRepository extends JpaRepository<Consultorio, Long>, ConsultorioRepositoryPort {

	/**
	 * Busca por id DENTRO del tenant.
	 *
	 * <p>Nunca se usa {@code findById} pelado para resolver un consultorio del contexto: eso
	 * permitiria que un id ajeno inyectado en la URL resuelva una fila de otra organizacion.
	 * Si no aparece, el resultado es 404, igual que si no existiera.
	 */
	Optional<Consultorio> findByIdAndOrganizationIdAndActiveTrue(Long id, Long organizationId);

	/** Sin filtro de estado: una sede dada de baja sigue siendo legible (RF-M03-004). */
	Optional<Consultorio> findByIdAndOrganizationId(Long id, Long organizationId);

	List<Consultorio> findAllByOrganizationIdAndActiveTrue(Long organizationId);

	List<Consultorio> findAllByOrganizationId(Long organizationId);

	Page<Consultorio> findAllByOrganizationIdAndActiveTrue(Long organizationId, Pageable pageable);

	/**
	 * Conteo de uso para el limite MAX_CONSULTORIOS.
	 *
	 * <p>Cuenta solo filas ACTIVAS: dar de baja un consultorio libera cupo, lo que es
	 * coherente con RN-M01-004 porque no invalida nada de lo que ocurrio en esa sede.
	 *
	 * <p>Se ejecuta DESPUES de bloquear la suscripcion y dentro de la misma transaccion que el
	 * alta. Contarlo antes convierte el limite en una sugerencia.
	 */
	long countByOrganizationIdAndActiveTrue(Long organizationId);

	/**
	 * Chequeo previo del nombre entre las sedes VIGENTES. La garantia real es
	 * {@code uk_consultorio_org_name_vigente}: entre este SELECT y el INSERT hay una ventana.
	 */
	boolean existsByOrganizationIdAndNameAndActiveTrue(Long organizationId, String name);

	/**
	 * Conteo de sedes activas con lock compartido sobre las filas contadas. La explicacion
	 * completa —y por que quitarle el {@code FOR SHARE} reintroduce el bug de las dos bajas
	 * simetricas— esta en el puerto.
	 *
	 * <p>Es una consulta NATIVA a proposito: {@code FOR SHARE} sobre un agregado no tiene
	 * equivalente confiable en JPQL, y {@code @Lock(PESSIMISTIC_READ)} sobre una consulta de
	 * agregacion no garantiza que Hibernate emita la clausula de bloqueo. Aca la sentencia es
	 * exactamente la que corre, y eso es justamente lo que hay que poder auditar.
	 */
	@Query(value = """
			SELECT COUNT(*) FROM consultorio
			 WHERE organization_id = :organizationId
			   AND active = 1
			   AND id <> :excludedConsultorioId
			 FOR SHARE
			""", nativeQuery = true)
	long countActiveForShare(
			@Param("organizationId") Long organizationId,
			@Param("excludedConsultorioId") Long excludedConsultorioId);
}
