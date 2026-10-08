package com.akine.identity.domain.port;

import com.akine.identity.domain.Cuenta;

import java.time.Instant;
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

	/**
	 * Registra un login exitoso con un {@code UPDATE} directo, sin pasar por la entidad.
	 *
	 * <h2>Por que no {@code cuenta.registrarLoginExitoso(...)} + {@code save}</h2>
	 *
	 * <p>Por la entidad, el {@code UPDATE cuenta} se difiere al flush del commit y lleva
	 * {@code WHERE version = ?}. Mientras tanto el login inserta su {@code refresh_token}, cuya
	 * foreign key toma un lock COMPARTIDO sobre la fila de la cuenta. Dos logins simultaneos de
	 * la misma cuenta quedaban los dos con el S tomado pidiendo el X: deadlock, {@code 500}. Y
	 * el que sobrevivia encontraba la version movida: {@code 409}. Cualquiera que abriera AKINE
	 * en dos dispositivos a la vez se quedaba afuera.
	 *
	 * <p>Este {@code UPDATE} corre ANTES de cualquier insert que referencie la cuenta, asi que
	 * el primer lock que el login toma sobre la fila ya es el exclusivo: los logins de la misma
	 * cuenta se serializan en vez de cruzarse. Y no toca {@code version}: la marca de ultimo
	 * login no es una edicion de la cuenta y no tiene por que invalidar la de un administrador
	 * —ni la de otro login—. La contracara la cubre {@code @DynamicUpdate} en {@link Cuenta}:
	 * una edicion por la entidad escribe solo las columnas que cambio y no pisa esta marca.
	 *
	 * <p>Condicionado al estado que habilita el login. Es lectura actual con lock, no la foto de
	 * la transaccion: si la cuenta se bloqueo entre la lectura y este punto, no afecta filas y
	 * el login se rechaza igual que cualquier otro (ADR-0018).
	 *
	 * @return {@code true} si la cuenta seguia habilitada y quedo registrado el login
	 */
	boolean registrarLoginExitoso(long cuentaId, Instant ahora);

	/**
	 * Suma un fallo de login con un incremento atomico y devuelve el total acumulado.
	 *
	 * <p>Mismo motivo que {@link #registrarLoginExitoso(long, Instant)}, con un agravante: por la
	 * entidad, dos contrasenas equivocadas simultaneas sobre una cuenta EXISTENTE terminaban una
	 * en {@code 401} y otra en {@code 409}, mientras que un email inexistente da siempre
	 * {@code 401}. La diferencia es un oraculo de enumeracion de cuentas (ADR-0018).
	 */
	int registrarLoginFallido(long cuentaId, Instant ahora);
}
