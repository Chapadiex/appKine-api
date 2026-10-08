package com.akine.identity.infrastructure;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Acceso a la tabla {@code cuenta}.
 *
 * <p>Extiende el puerto en vez de adaptarlo: Spring Data deriva por nombre los metodos que el
 * puerto declara igual que si estuvieran escritos aca, y {@code application} sigue viendo solo
 * la interfaz plana de {@code domain}. Es el mismo patron que {@code organization}: sin clase
 * adaptadora, sin una segunda definicion de las mismas consultas.
 *
 * <p><b>No hay ningun listado.</b> Ni por organizacion ni por estado: la cuenta es global
 * (ADR-0009) y una consulta que devolviera cuentas ajenas no tendria por donde filtrar por
 * tenant. Todo acceso a una cuenta que no es la del actor pasa por su id y se valida contra
 * {@code organization.spi} antes de tocarla.
 */
public interface CuentaRepository extends JpaRepository<Cuenta, Long>, CuentaRepositoryPort {

	/**
	 * Busca por la forma canonica del email.
	 *
	 * <p>Devuelve tambien bloqueadas y desactivadas: filtrar aca haria que un login contra una
	 * cuenta bloqueada tomara el camino de "no existe" y se perderia el evento que dice lo que
	 * realmente paso.
	 */
	@Override
	Optional<Cuenta> findByEmailNormalizado(String emailNormalizado);

	/**
	 * Busca por clave primaria.
	 *
	 * <p>Se redeclara aca a proposito: el puerto y {@code CrudRepository} declaran la misma
	 * firma y, sin esta linea, cualquier llamada desde {@code infrastructure} es ambigua para el
	 * compilador. Redeclararla las unifica en una sola definicion.
	 */
	@Override
	Optional<Cuenta> findById(Long id);

	/**
	 * Por que es un UPDATE nativo y no la entidad: ver
	 * {@link CuentaRepositoryPort#registrarLoginExitoso(long, Instant)}.
	 *
	 * <p>No toca {@code version} a proposito: la marca de login no es una edicion de la cuenta.
	 * Las condiciones del {@code WHERE} son las de {@link Cuenta#puedeAutenticarse()}.
	 */
	@Override
	default boolean registrarLoginExitoso(long cuentaId, Instant ahora) {
		return marcarLoginExitoso(cuentaId, ahora) > 0;
	}

	@Override
	default int registrarLoginFallido(long cuentaId, Instant ahora) {
		incrementarIntentosFallidos(cuentaId, ahora);
		// Bajo REPEATABLE READ la transaccion ve sus propias escrituras: esto devuelve el valor
		// que dejo el incremento, que a su vez partio del ultimo commiteado (lectura actual).
		return leerIntentosFallidos(cuentaId);
	}

	@Modifying
	@Query(value = """
			UPDATE cuenta
			   SET intentos_fallidos = 0,
			       ultimo_login_en = :ahora,
			       updated_at = :ahora
			 WHERE id = :cuentaId
			   AND estado = 'ACTIVA'
			   AND active = TRUE
			   AND password_hash IS NOT NULL
			""", nativeQuery = true)
	int marcarLoginExitoso(@Param("cuentaId") long cuentaId, @Param("ahora") Instant ahora);

	@Modifying
	@Query(value = """
			UPDATE cuenta
			   SET intentos_fallidos = intentos_fallidos + 1,
			       updated_at = :ahora
			 WHERE id = :cuentaId
			""", nativeQuery = true)
	int incrementarIntentosFallidos(@Param("cuentaId") long cuentaId, @Param("ahora") Instant ahora);

	@Query(value = "SELECT intentos_fallidos FROM cuenta WHERE id = :cuentaId", nativeQuery = true)
	int leerIntentosFallidos(@Param("cuentaId") long cuentaId);
}
