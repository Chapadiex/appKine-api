package com.akine.person.spi;

import java.time.LocalDate;

/**
 * El pedido de consumir unidades autorizadas a raiz de una atencion realizada (RF-M17-004).
 *
 * <p>Lleva lo minimo que {@code person} necesita para descontar sin obligarlo a leer la sesion, y
 * <b>nada clinico</b>: ni el motivo, ni la evaluacion, ni la nota. Quien consume una autorizacion
 * es el circuito administrativo, y el saldo de un financiador no necesita saber que le duele al
 * paciente. Es el mismo criterio con el que {@code encounter.spi.SesionCerrada} se nego a llevarlo.
 *
 * @param fecha          dia LOCAL de la sede contra el que se evalua vigencia y saldo. Se calcula
 *                       afuera, con la zona IANA del consultorio: el instante UTC del cierre y el
 *                       dia en que la autorizacion vence no estan en la misma escala, y resolverlo
 *                       en UTC corre el dia para media Argentina despues de las 21:00
 * @param cantidad       unidades a descontar. Una por sesion hoy; el parametro existe porque una
 *                       prestacion que vale dos sesiones es una configuracion de oferta que ya se
 *                       discute en M27, y cambiarlo despues seria cambiar el contrato
 * @param actorCuentaId  quien cerro la sesion, para que el movimiento diga quien lo produjo
 */
public record ConsumoPorSesion(
		long organizationId,
		long personaId,
		Long consultorioId,
		long sesionId,
		LocalDate fecha,
		int cantidad,
		Long actorCuentaId) {
}
