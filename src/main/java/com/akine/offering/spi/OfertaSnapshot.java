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
 * @param grupal          la modalidad de la oferta es {@code GRUPAL}. <b>No es derivable de
 *                        {@link #capacidad}</b>: {@code V24} obliga a que una GRUPAL tenga
 *                        capacidad mayor a 1, pero no la reciproca — una oferta INDIVIDUAL en un
 *                        box de dos camillas puede tener capacidad 2 y sigue siendo individual.
 *                        RN-M28-001 exige que una clase cuelgue de una oferta GRUPAL, y deducirlo
 *                        del cupo dejaria programar clases sobre ofertas individuales
 * @param requiereCasoClinico   si la atencion de esta oferta abre o exige un Caso Clinico
 *                              (RF-M10-007). <b>No decide si se puede derivar</b> —derivar ES
 *                              elegir el Caso, y siempre lo exige—: decide si 08.05 va a exigir
 *                              Caso al atender. Viaja porque AKINE-08.04 lo congela en la fila de
 *                              la derivacion, para que 08.05 no tenga que volver a la oferta, que
 *                              para entonces puede haber cambiado
 * @param generaRegistroClinico si la atencion genera registro clinico. Es el <b>gate duro</b> de
 *                              RF-M09-007, que lo dice en una linea: en {@code false} impide crear
 *                              evolucion clinica. Una participacion a una oferta asi no toca la
 *                              Historia Clinica, no abre historia y no escribe una fila —
 *                              CA-M09-007-06 pide exactamente eso, que una asistencia a Yoga quede
 *                              fuera del timeline clinico. La Oferta manda sobre el default del
 *                              Servicio (V24)
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
		boolean grupal,
		boolean requiereProfesional,
		boolean requiereEspacio,
		boolean requiereCasoClinico,
		boolean generaRegistroClinico,
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
