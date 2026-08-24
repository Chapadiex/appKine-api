package com.akine.organization.domain.port;

import com.akine.organization.domain.PlatformRole;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a {@code platform_role}, la tabla que dice quien administra la plataforma.
 *
 * <p>Se consulta una vez por request autenticado y sin cache (ADR-0020): la ventana de
 * revocacion del permiso mas alto del sistema tiene que ser cero. El indice
 * {@code ix_platform_role_account} hace que sea un seek.
 *
 * <p>Ningun modulo fuera de {@code organization} llega a esta tabla; {@code platform} la ve
 * unicamente por el puerto invertido {@code platform.spi.tenant.PlatformRoleDirectory}.
 */
public interface PlatformRoleRepositoryPort {

	PlatformRole save(PlatformRole role);

	PlatformRole saveAndFlush(PlatformRole role);

	/** Roles activos de una cuenta. El unique garantiza a lo sumo uno; el llamador filtra vigencia. */
	List<PlatformRole> findAllByAccountIdAndActiveTrue(Long accountId);

	Optional<PlatformRole> findByIdAndActiveTrue(Long id);

	/** Todos los roles vigentes, para el listado de plataforma y para el chequeo de arranque. */
	List<PlatformRole> findAllByActiveTrue();

	/** Cuantos roles de plataforma activos hay. Lo usa el chequeo de arranque. */
	long countByActiveTrue();
}
