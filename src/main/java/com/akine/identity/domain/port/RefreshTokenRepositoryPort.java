package com.akine.identity.domain.port;

import com.akine.identity.domain.RefreshToken;

import java.util.List;
import java.util.Optional;

/** Acceso a la tabla {@code refresh_token}. */
public interface RefreshTokenRepositoryPort {

	/**
	 * Busca el refresh presentado.
	 *
	 * <p><b>Contrato: el adaptador serializa el acceso a esa fila</b> hasta el fin de la
	 * transaccion. La rotacion con deteccion de reuso de ADR-0017 es "leer, comprobar
	 * {@code usadoEn}, emitir sucesor, marcar" y sin serializar dos canjes simultaneos del mismo
	 * token bifurcan la familia: los dos ven el {@code usadoEn} nulo y los dos emiten. Ver
	 * {@code RefreshTokenRepository#findByTokenHash}.
	 */
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
