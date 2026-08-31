package com.akine.offering.spi;

import java.time.LocalDate;

/**
 * Lo que otro modulo necesita saber de una Oferta sin depender de su entidad.
 *
 * <p>Deliberadamente <b>no</b> incluye precio, esquema de cobro ni nada economico: el motor de
 * agenda no cotiza. Cuando 07.01 tenga que armar la obligacion va a pedir su propia proyeccion,
 * y ese acoplamiento tiene que ser explicito y no llegar de arrastre por este record.
 *
 * @param duracionMinutos duracion de UN turno de esta oferta. Es lo que determina el tamano del
 *                        slot
 * @param capacidad       cuantos pacientes admite un turno. 1 individual, mas grupal
 * @param vigenciaHasta   {@code null} = sin vencimiento
 */
public record OfertaSnapshot(
		long id,
		long organizationId,
		long consultorioId,
		long servicioId,
		String nombreComercial,
		int duracionMinutos,
		int capacidad,
		boolean requiereProfesional,
		boolean requiereEspacio,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		boolean active) {

	/**
	 * La oferta esta operativa y vigente ese dia local.
	 *
	 * <p>Se evalua <b>dia por dia</b> y no contra la ventana entera, por el mismo motivo que el
	 * ruling R13 fijo para la vigencia del vinculo: una oferta que vence el 15 no puede seguir
	 * ofreciendo turnos el 20 porque la consulta abarco todo el mes.
	 */
	public boolean vigenteEl(LocalDate fecha) {
		if (!active) {
			return false;
		}
		if (fecha.isBefore(vigenciaDesde)) {
			return false;
		}
		return vigenciaHasta == null || !fecha.isAfter(vigenciaHasta);
	}

	/** {@code true} si la oferta no puede producir agenda en NINGUN dia de {@code [desde, hasta)}. */
	public boolean noAgendableEn(LocalDate desde, LocalDate hasta) {
		if (!active) {
			return true;
		}
		if (!vigenciaDesde.isBefore(hasta)) {
			return true;
		}
		return vigenciaHasta != null && vigenciaHasta.isBefore(desde);
	}
}
