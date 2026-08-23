package com.akine.identity.infrastructure;

import com.akine.identity.domain.RefreshToken;
import com.akine.identity.domain.port.RefreshTokenRepositoryPort;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
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

	/**
	 * Busca el refresh presentado y <b>bloquea su fila</b> hasta el fin de la transaccion.
	 *
	 * <h2>Por que el lock no es opcional</h2>
	 *
	 * <p>El canje del refresh lee la fila, comprueba que {@code usado_en} sea nulo, emite un
	 * sucesor y <i>recien entonces</i> marca la rotacion. Sin serializar, dos
	 * {@code POST /auth/refresh} disparados a la vez con la misma cookie ven las dos el
	 * {@code usado_en} nulo, insertan las dos un sucesor en la misma familia y devuelven las dos
	 * un par valido. <b>La familia queda bifurcada</b>: atacante y victima terminan con cadenas
	 * de rotacion independientes que nunca se cruzan, asi que la premisa central de ADR-0017
	 * —"su uso genera reuso y mata la familia entera"— deja de cumplirse y el robo de la cookie
	 * se vuelve persistente, en silencio, hasta las 12 h absolutas. Es una carrera que el
	 * atacante controla: elige cuando dispararla.
	 *
	 * <p>{@code PESSIMISTIC_WRITE} la cierra en el unico punto donde se puede: el segundo hilo
	 * espera en el {@code SELECT ... FOR UPDATE} y cuando entra ya ve el {@code usado_en} que
	 * escribio el primero, o sea que su presentacion es <b>reuso</b> y revoca la familia
	 * completa. Es el desenlace correcto: ante dos portadores de la misma cadena no se sabe cual
	 * es el legitimo, y se los expulsa a los dos.
	 *
	 * <p>Se eligio el lock pesimista sobre {@code @Version} con reintento porque el conflicto es
	 * el caso <i>esperado</i> bajo ataque, no la excepcion rara: con optimista habria que
	 * deshacer un sucesor ya insertado y rehacer el camino de reuso en otra transaccion. Ademas
	 * no necesita migracion sobre una tabla de credenciales vivas.
	 */
	@Override
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/**
	 * Sesiones vivas de la cuenta. Es la consulta de la revocacion en masa —bloqueo,
	 * desactivacion, reset— y se resuelve por {@code ix_refresh_token_cuenta}.
	 */
	@Override
	List<RefreshToken> findByCuentaIdAndRevocadoEnIsNull(Long cuentaId);

	/**
	 * Eslabones vivos de una sesion. Se resuelve por {@code ix_refresh_token_familia}.
	 *
	 * <p>Tambien con lock, y por una razon que no es la misma: esta es la consulta con la que se
	 * revoca la familia entera al detectar un reuso, y una lectura consistente comun podria
	 * correr sobre el snapshot que la transaccion tomo <b>antes</b> de que el hilo ganador
	 * insertara su sucesor. En ese caso la revocacion dejaria vivo justamente el eslabon nuevo:
	 * la mitad de la familia que le queda al atacante. Una lectura con lock siempre ve la ultima
	 * version confirmada, asi que la revocacion es completa sin depender del nivel de
	 * aislamiento configurado en el motor.
	 */
	@Override
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	List<RefreshToken> findByFamiliaIdAndRevocadoEnIsNull(String familiaId);
}
