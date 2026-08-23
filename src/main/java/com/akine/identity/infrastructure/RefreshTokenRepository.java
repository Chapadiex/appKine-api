package com.akine.identity.infrastructure;

import com.akine.identity.domain.RefreshToken;
import com.akine.identity.domain.port.RefreshTokenRepositoryPort;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a {@code refresh_token}.
 *
 * <p>Extiende el marcador {@code Repository} y no {@code JpaRepository} por el mismo motivo que
 * {@link TokenVerificacionRepository}: el {@code saveAll} del puerto y el de
 * {@code CrudRepository} comparten erasure y heredar los dos no compila.
 *
 * <p>Las tres consultas estan acotadas a UNA cuenta o a UNA familia, nunca a un listado
 * abierto: un refresh es una credencial viva y una consulta que devolviera las de varias
 * cuentas seria un inventario de sesiones ajenas.
 */
public interface RefreshTokenRepository
		extends Repository<RefreshToken, Long>, RefreshTokenRepositoryPort {

	@Override
	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/**
	 * Sesiones vivas de la cuenta. Es la consulta de la revocacion en masa —bloqueo,
	 * desactivacion, reset— y se resuelve por {@code ix_refresh_token_cuenta}.
	 */
	@Override
	List<RefreshToken> findByCuentaIdAndRevocadoEnIsNull(Long cuentaId);

	/** Eslabones vivos de una sesion. Se resuelve por {@code ix_refresh_token_familia}. */
	@Override
	List<RefreshToken> findByFamiliaIdAndRevocadoEnIsNull(String familiaId);
}
