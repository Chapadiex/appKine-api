package com.akine.person.application;

import java.time.LocalDate;
import java.util.List;

/**
 * El veredicto administrativo preliminar de atender una practica a un paciente (RF-M17-007).
 *
 * <h2>Lo que esta consulta NO hace</h2>
 *
 * <p><b>No persiste nada y no consume nada.</b> Preguntar si se puede atender y descontar una
 * sesion son dos cosas distintas (RN-M17-001), y esta etapa entrega solo la primera. El consumo es
 * RF-M17-004 y llega con la integracion clinica.
 *
 * <p><b>No afirma que la prestacion sea facturable.</b> RN-M08-004: la cobertura del paciente no
 * implica que el consultorio tenga convenio con ese financiador. Lo que dice es si la
 * documentacion administrativa que el convenio exige esta presente.
 *
 * <h2>La lista vacia es el caso normal, no un error</h2>
 *
 * <p>RN-M17-005 y RN-M17-006: los requisitos se aplican SOLO cuando el convenio los exige, y una
 * actividad no cubierta no debe pedir orden ni autorizacion artificialmente. Un paciente
 * particular, o uno cuya obra social no tiene convenio con la sede, sale <b>elegible con cero
 * requisitos</b>. Pedirle una orden seria un bug, no una precaucion.
 *
 * @param fecha                  dia contra el que se evaluo todo
 * @param elegible               ningun requisito exigido quedo sin cumplir
 * @param motivo                 por que no hay requisitos que evaluar, o {@code null} si los hay
 * @param requisitos             lo que el convenio exige y su veredicto, uno por uno
 * @param convenioId             convenio VIVO que se aplico, o {@code null}
 * @param convenioNombre         nombre del convenio aplicado, o {@code null}
 * @param limiteSesionesMensual  tope mensual pactado. INFORMATIVO: nadie lo verifica todavia
 */
public record ElegibilidadAdministrativa(
		LocalDate fecha,
		boolean elegible,
		String motivo,
		List<RequisitoAdministrativo> requisitos,
		Long convenioId,
		String convenioNombre,
		Integer limiteSesionesMensual) {

	/** La cobertura es PARTICULAR: no hay financiador que exija nada (RN-M08-001, RN-M17-006). */
	public static final String PARTICULAR = "COBERTURA_PARTICULAR";

	/**
	 * No hay convenio de esa sede con ese plan, o el convenio no tiene arancel para esa practica.
	 *
	 * <p>Reusa los nombres que {@code contracting.spi.MotivoSinArancel} ya publica en
	 * {@code GET /aranceles/efectivo}: el mismo desenlace no puede llamarse distinto segun por que
	 * endpoint se lo mire.
	 */
	public static ElegibilidadAdministrativa sinRequisitos(LocalDate fecha, String motivo) {
		return new ElegibilidadAdministrativa(fecha, true, motivo, List.of(), null, null, null);
	}

	public static ElegibilidadAdministrativa evaluada(
			LocalDate fecha,
			List<RequisitoAdministrativo> requisitos,
			Long convenioId,
			String convenioNombre,
			Integer limiteSesionesMensual) {

		boolean elegible = requisitos.stream().allMatch(RequisitoAdministrativo::cumplido);
		return new ElegibilidadAdministrativa(
				fecha, elegible, null, List.copyOf(requisitos), convenioId, convenioNombre,
				limiteSesionesMensual);
	}
}
