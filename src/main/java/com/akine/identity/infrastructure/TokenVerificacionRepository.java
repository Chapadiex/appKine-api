package com.akine.identity.infrastructure;

import com.akine.identity.domain.TipoTokenVerificacion;
import com.akine.identity.domain.TokenVerificacion;
import com.akine.identity.domain.port.TokenVerificacionRepositoryPort;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a {@code token_verificacion}.
 *
 * <p><b>Por que extiende {@code Repository} y no {@code JpaRepository}.</b> El puerto declara
 * {@code List<TokenVerificacion> saveAll(Iterable<TokenVerificacion>)} y {@code CrudRepository}
 * declara {@code <S extends T> List<S> saveAll(Iterable<S>)}: misma erasure, ninguna sobrescribe
 * a la otra, y heredar las dos no compila. Con el marcador {@code Repository} el puerto es la
 * unica definicion y Spring Data resuelve igual los metodos contra {@code SimpleJpaRepository}.
 * La alternativa —cambiar la firma del puerto— tocaria {@code domain}, que no es de esta etapa.
 *
 * <p>La busqueda es siempre por {@code token_hash} —resuelta por
 * {@code uk_token_verificacion_hash}— y jamas por el valor plano: el valor plano no esta en la
 * base y no puede estarlo (RN-M02-003).
 */
public interface TokenVerificacionRepository
		extends Repository<TokenVerificacion, Long>, TokenVerificacionRepositoryPort {

	@Override
	Optional<TokenVerificacion> findByTokenHash(String tokenHash);

	/**
	 * Tokens vivos de la cuenta para ese tipo. Se resuelve por
	 * {@code ix_token_verificacion_cuenta} y es la consulta con la que cada emision nueva
	 * invalida a las anteriores.
	 */
	@Override
	List<TokenVerificacion> findByCuentaIdAndTipoAndUsadoEnIsNullAndInvalidadoEnIsNull(
			Long cuentaId, TipoTokenVerificacion tipo);

	/**
	 * Busca por id de fila.
	 *
	 * <p>La usa la reconstruccion del enlace seguro, que solo conoce la referencia opaca que
	 * guarda el outbox: el id de la fila, nunca el token.
	 */
	Optional<TokenVerificacion> findById(Long id);
}
