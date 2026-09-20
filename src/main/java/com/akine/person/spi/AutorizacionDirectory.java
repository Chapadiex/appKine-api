package com.akine.person.spi;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Lectura de autorizaciones para otros modulos (M17, AKINE-04.05).
 *
 * <p>Existe por <b>RF-M11-007</b>: el Plan de Tratamiento de 04.04 registra su cantidad autorizada
 * como {@code DECLARADA} —la escribio a mano quien planifico— y necesita poder atarla a una
 * autorizacion real. Para validar esa referencia, {@code clinical} tiene que poder mirar la
 * autorizacion sin importar {@code person.domain}.
 *
 * <p><b>Solo lectura, y a proposito.</b> Este puerto no ofrece ninguna mutacion: quien consume, quien
 * revierte y quien resuelve una autorizacion es {@code person}, por sus propios servicios. Un
 * {@code clinical} que pudiera descontar saldo seria un segundo dueño del ledger.
 *
 * <p>La direccion {@code clinical -> person.spi} ya existe desde 04.01 y no introduce ninguna
 * arista nueva.
 */
public interface AutorizacionDirectory {

	/**
	 * Una autorizacion de ese paciente y ese tenant, con su veredicto para {@code fecha}.
	 *
	 * <p>{@code personaId} viaja y no es redundante: sin el, el plan de un paciente podria quedar
	 * atado a la autorizacion de otro de la misma organizacion, y eso publicaria en su ficha
	 * clinica un numero de autorizacion que no es suyo.
	 *
	 * <p>Devuelve vacio si no existe, si es de otro tenant, si es de otra persona o si esta dada
	 * de baja. Los cuatro colapsan a proposito: distinguirlos confirmaria que ese id existe.
	 */
	Optional<AutorizacionSnapshot> find(
			long organizationId, long personaId, long autorizacionId, LocalDate fecha);
}
