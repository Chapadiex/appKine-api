package com.akine.identity.domain.port;

import com.akine.identity.domain.RefreshToken;

import java.util.List;
import java.util.Optional;

/** Acceso a la tabla {@code refresh_token}. */
public interface RefreshTokenRepositoryPort {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/**
	 * Sesiones vivas de la cuenta.
	 *
	 * <p>Es la consulta de la revocacion en masa: bloquear, desactivar y resetear la
	 * contrasena la ejecutan DENTRO de su transaccion de negocio, asi que tiene que resolverse
	 * por {@code ix_refresh_token_cuenta} y no por un scan.
	 */
	List<RefreshToken> findByCuentaIdAndRevocadoEnIsNull(Long cuentaId);

	/** Eslabones vivos de una misma sesion. Es la consulta de la respuesta a un reuso. */
	List<RefreshToken> findByFamiliaIdAndRevocadoEnIsNull(String familiaId);

	RefreshToken save(RefreshToken token);

	List<RefreshToken> saveAll(Iterable<RefreshToken> tokens);
}
