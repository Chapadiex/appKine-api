package com.akine.organization.domain.port;

import com.akine.organization.domain.Consultorio;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a los consultorios. Toda consulta filtra por {@code organizationId}: un id de sede de
 * otro tenant no debe resolver nunca.
 */
public interface ConsultorioRepositoryPort {

	Consultorio save(Consultorio consultorio);

	/**
	 * Busca por id DENTRO del tenant.
	 *
	 * <p>Nunca se resuelve una sede del contexto con {@code findById} pelado: eso permitiria
	 * que un id ajeno inyectado en la URL devuelva una fila de otra organizacion.
	 */
	Optional<Consultorio> findByIdAndOrganizationIdAndActiveTrue(Long id, Long organizationId);

	List<Consultorio> findAllByOrganizationIdAndActiveTrue(Long organizationId);

	/**
	 * Conteo de uso para {@code MAX_CONSULTORIOS}.
	 *
	 * <p>Solo filas ACTIVAS: dar de baja una sede libera cupo, coherente con RN-M01-004 porque
	 * no invalida nada de lo que ocurrio ahi.
	 *
	 * <p>Cuando decide un alta, se ejecuta DENTRO de la transaccion y DESPUES de bloquear la
	 * suscripcion. Contarlo antes convierte el limite en una sugerencia.
	 */
	long countByOrganizationIdAndActiveTrue(Long organizationId);
}
