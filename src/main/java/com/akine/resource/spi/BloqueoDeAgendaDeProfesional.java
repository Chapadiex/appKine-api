package com.akine.resource.spi;

import java.util.Collection;
import java.util.Set;

/**
 * Serializa las escrituras de agenda de UNA PERSONA en toda la organizacion (checklist de agenda
 * §9.2 y §9.3, V129).
 *
 * <p>Los locks de agenda son por sede. Una persona con membership en dos sedes tiene un id de
 * membership distinto en cada una, asi que dos reservas —o dos horarios habituales— cargados al
 * mismo tiempo en dos sedes no se veian entre si. Esta fila es de la cuenta y se toma con
 * {@code FOR UPDATE} <b>despues</b> del lock de la sede, nunca antes: con ese orden fijo dos
 * escrituras en sedes distintas esperan la misma fila sin tener nunca la una lo que la otra pide.
 *
 * <p>Vive en {@code resource} porque {@code scheduling} ya depende de {@code resource.spi} y no al
 * reves: la tabla tiene un solo propietario y los dos modulos la toman por este contrato.
 */
public interface BloqueoDeAgendaDeProfesional {

	/**
	 * Crea las filas de las personas detras de esas memberships, cada una en su propia transaccion.
	 * Se llama SIN transaccion abierta, antes de la de negocio ({@code FilaLockPrevia}). Las
	 * memberships que no existen se ignoran: el rechazo lo da la validacion de negocio.
	 */
	void asegurar(long organizationId, Collection<Long> membershipIds);

	/**
	 * Bloquea la fila de la persona detras de esa membership, dentro de la transaccion en curso,
	 * y devuelve TODAS las memberships de esa persona en la organizacion (incluida la pedida).
	 *
	 * <p>Si la fila no existe —porque {@link #asegurar} no corrio para esa persona, lo que solo
	 * pasa si el turno cambio de profesional entre la lectura previa y la transaccion— lanza
	 * {@link org.springframework.dao.OptimisticLockingFailureException}: 409 y reintentar.
	 */
	Set<Long> bloquear(long organizationId, long membershipId);

	/**
	 * Bloquea las filas de varias personas en orden ascendente de cuenta. Es lo que hace quien va a
	 * tocar la agenda de mas de una persona en la misma transaccion (una serie con ocurrencias de
	 * profesionales distintos): con un orden unico dos transacciones no se cruzan.
	 */
	void bloquearEnOrden(long organizationId, Collection<Long> membershipIds);

	/**
	 * Las memberships de la misma persona en la organizacion, SIN lock. Para las consultas
	 * informativas, que no serializan nada.
	 */
	Set<Long> deLaMismaPersona(long organizationId, long membershipId);
}
