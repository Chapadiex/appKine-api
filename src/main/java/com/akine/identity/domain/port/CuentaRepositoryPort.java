package com.akine.identity.domain.port;

import com.akine.identity.domain.Cuenta;

import java.util.Optional;

/** Acceso a la tabla {@code cuenta}. */
public interface CuentaRepositoryPort {

	/**
	 * Busca por la forma canonica del email.
	 *
	 * <p>Devuelve tambien las cuentas bloqueadas y desactivadas: quien decide si el estado
	 * habilita algo es la capa de aplicacion, no la consulta. Filtrar aca haria que un login
	 * contra una cuenta bloqueada tomara el camino de "no existe" y perderiamos el evento de
	 * auditoria que dice lo que realmente paso.
	 */
	Optional<Cuenta> findByEmailNormalizado(String emailNormalizado);

	Optional<Cuenta> findById(Long id);

	/**
	 * Inserta o actualiza forzando el flush.
	 *
	 * <p>El flush no es opcional en el alta: la violacion de
	 * {@code uk_cuenta_email_normalizado} tiene que manifestarse dentro del bloque que la
	 * captura, no al cerrar la transaccion, cuando ya no hay a quien avisarle.
	 */
	Cuenta saveAndFlush(Cuenta cuenta);

	Cuenta save(Cuenta cuenta);
}
