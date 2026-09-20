package com.akine.person.spi;

import java.time.LocalDate;

/**
 * Lo que otro modulo necesita saber de una Autorizacion sin depender de su entidad.
 *
 * <p>Deliberadamente <b>no</b> incluye el snapshot congelado del convenio ni el adjunto: quien la
 * consulta desde afuera quiere saber <b>si sirve y cuanto queda</b>, no con que reglas se otorgo.
 * Agregarlo "por si acaso" le daria a {@code clinical} una copia de las exigencias del convenio que
 * nadie alla sabe interpretar, y que envejeceria distinto de la original.
 *
 * <p>{@code habilita} y {@code motivoNoElegible} viajan <b>calculados contra {@code fecha}</b>, no
 * como columnas: vencida y agotada no son estados persistidos, porque materializarlos exigiria un
 * job y un job que no corre deja autorizaciones vencidas que el sistema cree vigentes.
 *
 * @param saldo            restantes. {@code null} si no hay tope declarado, que no es cero
 * @param motivoNoElegible {@code VENCIDA}, {@code AGOTADA}, {@code AUN_NO_VIGENTE} o
 *                         {@code NO_APROBADA}. {@code null} exactamente cuando habilita
 */
public record AutorizacionSnapshot(
		long id,
		long organizationId,
		long personaId,
		long coberturaId,
		long practicaId,
		String numero,
		Integer cantidadAutorizada,
		int cantidadConsumida,
		Integer saldo,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		LocalDate fecha,
		boolean habilita,
		String motivoNoElegible) {
}
