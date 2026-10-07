package com.akine.billing.api.dto;

import com.akine.billing.application.ObligacionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Una deuda derivada de una prestacion.
 *
 * <p><b>Los importes viajan como decimal exacto, nunca como float.</b> Un numero de punto flotante
 * sobre una cuenta corriente produce centavos que no cuadran y que nadie puede explicar despues.
 */
@Schema(
		name = "Obligacion",
		description = "Deuda derivada de una prestacion. **No es el cobro ni la caja**: son tres "
				+ "cosas distintas (M18, M19, M20).")
public record ObligacionResponse(

		@Schema(example = "9001")
		long id,

		@Schema(example = "7")
		long consultorioId,

		@Schema(description = "Atencion que la genero. Es lo que la hace trazable.", example = "501")
		long sesionId,

		@Schema(example = "128")
		long personaId,

		@Schema(
				description = "Quien debe. Una prestacion cubierta por un convenio devenga dos "
						+ "obligaciones: la parte del `FINANCIADOR` y el coseguro del `PACIENTE`. "
						+ "Ver `concepto`.",
				allowableValues = {"PACIENTE", "FINANCIADOR"},
				example = "PACIENTE")
		String responsable,

		@Schema(
				description = "Quien es el financiador cuando `responsable` es `FINANCIADOR`; "
						+ "ausente cuando debe el paciente. Sin este campo, M21 no tiene por donde "
						+ "agrupar un lote.",
				example = "31")
		Long financiadorId,

		@Schema(description = "Lo devengado. No cambia nunca.", example = "8500.00")
		BigDecimal importeOriginal,

		@Schema(description = "Lo que falta pagar", example = "8500.00")
		BigDecimal saldo,

		@Schema(example = "ARS")
		String moneda,

		@Schema(
				description = "Los tres primeros se derivan del saldo; `ANULADA` es una decision.",
				allowableValues = {"PENDIENTE", "PARCIAL", "PAGADA", "ANULADA"},
				example = "PENDIENTE")
		String estado,

		@Schema(example = "42")
		long ofertaId,

		@Schema(description = "Que se presto, al momento de devengar", example = "Sesion 8")
		String snapshotNombre,

		@Schema(
				description = "Importe congelado **al momento de devengar**: el precio de la oferta "
						+ "en `PARTICULAR`, la parte del arancel en `FINANCIADOR` y `COSEGURO`. "
						+ "Editar la oferta o el convenio manana no cambia esto: seria reescribir "
						+ "una cuenta corriente.",
				example = "8500.00")
		BigDecimal snapshotPrecio,

		@Schema(example = "2026-09-15T12:48:00Z")
		Instant devengadaEn,

		@Schema(description = "Ausente si la deuda sigue vigente")
		Instant anuladaEn,

		@Schema(description = "Obligatorio al anular: una deuda que se borra sin explicacion es lo que una auditoria busca")
		String motivoAnulacion,

		@Schema(description = "Version para el control optimista", example = "0")
		long version,

		@Schema(
				description = "Que parte de la prestacion es esta deuda (AKINE F-4). `PARTICULAR`: "
						+ "sin cobertura aplicable, el precio de la oferta. `FINANCIADOR`: la parte "
						+ "del arancel que paga el financiador. `COSEGURO`: la parte del arancel que "
						+ "paga el paciente.",
				allowableValues = {"PARTICULAR", "FINANCIADOR", "COSEGURO"},
				example = "PARTICULAR")
		String concepto,

		@Schema(
				description = "Practica facturada (DP-11: la realizada, o la principal de la oferta "
						+ "si la sesion cerro sin tratamientos). Ausente en `PARTICULAR`.",
				example = "55")
		Long practicaId,

		@Schema(
				description = "DP-11: la practica facturada no esta entre las que la oferta declara. "
						+ "Es una alerta para revisar, no un rechazo.",
				example = "false")
		boolean alertaPracticaNoHabilitada,

		@Schema(description = "Convenio aplicado, congelado. Ausente en `PARTICULAR`.")
		ConvenioAplicadoResponse convenio) {

	public static ObligacionResponse de(ObligacionView vista) {
		return new ObligacionResponse(
				vista.id(), vista.consultorioId(), vista.sesionId(), vista.personaId(),
				vista.responsable(), vista.financiadorId(), vista.importeOriginal(), vista.saldo(),
				vista.moneda(),
				vista.estado(), vista.ofertaId(), vista.snapshotNombre(), vista.snapshotPrecio(),
				vista.devengadaEn(), vista.anuladaEn(), vista.motivoAnulacion(), vista.version(),
				vista.concepto(), vista.practicaId(), vista.alertaPracticaNoHabilitada(),
				ConvenioAplicadoResponse.de(vista.convenio()));
	}
}
