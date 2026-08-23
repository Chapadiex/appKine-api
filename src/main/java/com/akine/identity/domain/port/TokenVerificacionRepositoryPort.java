package com.akine.identity.domain.port;

import com.akine.identity.domain.TipoTokenVerificacion;
import com.akine.identity.domain.TokenVerificacion;

import java.util.List;
import java.util.Optional;

/** Acceso a la tabla {@code token_verificacion}. */
public interface TokenVerificacionRepositoryPort {

	/**
	 * Busca por la huella del token.
	 *
	 * <p>La busqueda es por hash y nunca por el valor plano: el valor plano no esta en la
	 * base. Devuelve la fila aunque este usada, invalidada o vencida — el estado lo evalua el
	 * dominio con {@code esUtilizableEn}, y asi el camino "no sirve" es uno solo.
	 */
	Optional<TokenVerificacion> findByTokenHash(String tokenHash);

	/** Tokens de la cuenta de ese tipo que todavia no se consumieron ni se invalidaron. */
	List<TokenVerificacion> findByCuentaIdAndTipoAndUsadoEnIsNullAndInvalidadoEnIsNull(
			Long cuentaId, TipoTokenVerificacion tipo);

	TokenVerificacion save(TokenVerificacion token);

	List<TokenVerificacion> saveAll(Iterable<TokenVerificacion> tokens);
}
