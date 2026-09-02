package com.akine.person.application;

import java.time.LocalDate;
import java.util.List;

/**
 * Lo que se puede elegir para atender a un paciente un dia dado (RF-M08-004 y RF-M08-005).
 *
 * <h2>Particular SIEMPRE viaja, y por eso este record existe</h2>
 *
 * <p>RN-M08-001 dice que PARTICULAR siempre debe estar disponible, y RF-M08-005 que se lo puede
 * elegir <b>aunque el paciente tenga cobertura</b>. Si esta respuesta fuera una lista de
 * coberturas a secas, "atender como particular" seria un caso especial que cada pantalla tendria
 * que acordarse de agregar, y la primera que lo olvide deja al mostrador sin poder cobrar una
 * consulta. Por eso {@link #particularSiempreDisponible} es un campo y no una fila: es una verdad
 * del sistema, no un dato de la base.
 *
 * <h2>Esto NO es una reserva ni una imputacion</h2>
 *
 * <p>Es una <b>lectura</b>. No persiste nada, y elegir aca no obliga a nada despues: quien
 * registra el hecho —el turno, la sesion, la obligacion— es el que copia la cobertura elegida a
 * sus propias columnas, con el mismo criterio con que esta etapa copia el plan. RN-M08-004 vale
 * entera: que el paciente tenga esta cobertura <b>no</b> implica que el consultorio tenga convenio
 * con ese financiador, y este record no lo afirma en ninguna parte. Resolver la elegibilidad por
 * convenio y por oferta es RF-M08-006, que necesita M16 y M17 y no existe todavia.
 *
 * @param principal la cobertura marcada principal y vigente ese dia, o {@code null}. Es la
 *                  seleccion por defecto, y es determinista porque el invariante del lock impide
 *                  que haya dos
 * @param vigentes  todas las coberturas que aplican ese dia, incluida la principal. Vacia es un
 *                  estado normal: un paciente sin cobertura financiada se atiende particular
 */
public record SeleccionDeCobertura(
		LocalDate fecha,
		CoberturaView principal,
		List<CoberturaView> vigentes,
		boolean particularSiempreDisponible) {

	public static SeleccionDeCobertura de(
			LocalDate fecha, CoberturaView principal, List<CoberturaView> vigentes) {

		// El literal true no es un descuido: RN-M08-001 no depende de ningun dato.
		return new SeleccionDeCobertura(fecha, principal, vigentes, true);
	}
}
