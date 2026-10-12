package com.akine.organization.infrastructure;

import java.util.List;

import com.akine.organization.domain.PlatformRole;
import com.akine.organization.domain.port.PlatformRoleRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface PlatformRoleRepository
		extends JpaRepository<PlatformRole, Long>, PlatformRoleRepositoryPort {

	/**
	 * {@code SUPPORTS}: dentro de una transaccion se une a ella, como cualquier consulta; fuera de
	 * una corre como una sola sentencia en autocommit, sin abrir transaccion propia.
	 *
	 * <p>Es la lectura que {@code JwtAuthenticationFilter} hace en cada pedido autenticado
	 * (ADR-0020). Una sola sentencia no necesita transaccion para ser consistente, y abrirla
	 * costaba cinco idas y vueltas a MySQL que no leian nada —{@code SET autocommit},
	 * {@code SET SESSION TRANSACTION READ ONLY}, {@code COMMIT} y las dos que lo deshacen—
	 * en cada pedido (docs/carga.md §9).
	 */
	@Override
	@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
	List<PlatformRole> findAllByAccountIdAndActiveTrue(Long accountId);
}
