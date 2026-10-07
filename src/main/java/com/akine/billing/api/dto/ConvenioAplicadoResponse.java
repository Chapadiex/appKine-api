package com.akine.billing.api.dto;

import com.akine.billing.application.ObligacionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * El convenio que se aplico a una prestacion, congelado al devengar (AKINE F-4, RN-M16-004).
 *
 * <p>Es una copia: subir el arancel o dar de baja el convenio manana no cambia nada de esto.
 */
@Schema(
		name = "ConvenioAplicado",
		description = "Convenio y arancel aplicados a la prestacion, **congelados al devengar** "
				+ "(RN-M16-003/004). Cambiar el convenio despues no los modifica. Los tres "
				+ "`requeria*` son los requisitos documentales del convenio el dia de la "
				+ "prestacion (RF-M21-003).")
public record ConvenioAplicadoResponse(

		@Schema(example = "12")
		long convenioId,

		@Schema(description = "Codigo del convenio, inmutable", example = "OSDE-2026")
		String convenioCodigo,

		@Schema(description = "Nombre del convenio el dia de la prestacion", example = "OSDE 2026")
		String convenioNombre,

		@Schema(example = "4")
		long planId,

		@Schema(example = "77")
		long arancelId,

		@Schema(description = "Cobertura del paciente que se aplico", example = "310")
		long coberturaId,

		@Schema(description = "Lo que valia la practica bajo el convenio", example = "12000.00")
		BigDecimal importeTotal,

		@Schema(description = "La parte del financiador", example = "10500.00")
		BigDecimal importeFinanciador,

		@Schema(description = "La parte del paciente", example = "1500.00")
		BigDecimal coseguro,

		@Schema(description = "El convenio exigia orden medica", example = "true")
		boolean requeriaOrden,

		@Schema(description = "El convenio exigia autorizacion previa", example = "false")
		boolean requeriaAutorizacion,

		@Schema(description = "El convenio exigia credencial", example = "true")
		boolean requeriaCredencial,

		@Schema(
				description = "La credencial de la cobertura estaba vencida el dia de la "
						+ "prestacion. Es una alerta: no excluyo la cobertura",
				example = "false")
		boolean credencialVencida,

		@Schema(description = "Dia de la prestacion, en la zona de la sede, contra el que se resolvio",
				example = "2026-10-06")
		LocalDate vigenteEl) {

	static ConvenioAplicadoResponse de(ObligacionView.ConvenioAplicado c) {
		return c == null ? null : new ConvenioAplicadoResponse(
				c.convenioId(), c.convenioCodigo(), c.convenioNombre(), c.planId(), c.arancelId(),
				c.coberturaId(), c.importeTotal(), c.importeFinanciador(), c.coseguro(),
				c.requeriaOrden(), c.requeriaAutorizacion(), c.requeriaCredencial(),
				c.credencialVencida(), c.vigenteEl());
	}
}
