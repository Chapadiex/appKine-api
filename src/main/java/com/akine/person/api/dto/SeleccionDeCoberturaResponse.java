package com.akine.person.api.dto;

import com.akine.person.application.SeleccionDeCobertura;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * Lo que se puede elegir para atender a un paciente un dia dado (RF-M08-004, RF-M08-005).
 *
 * <p><b>{@code particularSiempreDisponible} es siempre {@code true}</b> y no es un campo inutil:
 * es RN-M08-001 dicho en el contrato. Si esta respuesta fuera una lista a secas, "atender como
 * particular" seria un caso especial que cada pantalla tendria que acordarse de agregar, y la
 * primera que lo olvide deja al mostrador sin poder cobrar una consulta a un paciente con obra
 * social —que es exactamente el escenario de RF-M08-005—.
 *
 * <p><b>Esto NO afirma que la prestacion sea facturable a esa cobertura.</b> RN-M08-004: que el
 * paciente tenga cobertura no implica que el consultorio tenga convenio con ese financiador.
 * Resolver la elegibilidad por oferta y convenio es RF-M08-006 y necesita M16 y M17.
 */
@Schema(description = "Coberturas elegibles del paciente para una fecha, con Particular siempre disponible")
public record SeleccionDeCoberturaResponse(

		@Schema(description = "Dia contra el que se resolvio", example = "2026-09-02")
		LocalDate fecha,

		@Schema(
				description = "La cobertura marcada principal y vigente ese dia, o null. Es la "
						+ "seleccion por defecto y es determinista: no puede haber dos")
		CoberturaResponse principal,

		@Schema(
				description = "Todas las coberturas que aplican ese dia, incluida la principal. "
						+ "Vacia es un estado normal: un paciente sin cobertura se atiende particular")
		List<CoberturaResponse> vigentes,

		@Schema(
				description = "Siempre true (RN-M08-001). Atender como particular no depende de "
						+ "ningun dato y se puede elegir aunque el paciente tenga cobertura",
				example = "true")
		boolean particularSiempreDisponible) {

	public static SeleccionDeCoberturaResponse de(SeleccionDeCobertura seleccion) {
		return new SeleccionDeCoberturaResponse(
				seleccion.fecha(),
				seleccion.principal() == null
						? null
						: CoberturaResponse.de(seleccion.principal()),
				seleccion.vigentes().stream().map(CoberturaResponse::de).toList(),
				seleccion.particularSiempreDisponible());
	}
}
