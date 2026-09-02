package com.akine.contracting.api.dto;

import com.akine.contracting.spi.ArancelVigente;
import com.akine.contracting.spi.ResolucionDeArancel;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * El arancel que se aplica a una practica en una fecha, <b>o el motivo por el que no hay</b>
 * (RF-M16-006, RF-M16-010).
 *
 * <h2>Por que esto responde 200 y no 404 cuando no hay convenio</h2>
 *
 * <p>Porque no encontrar convenio es el desenlace mas frecuente, no un error: la mayoria de los
 * pacientes de un centro se atienden como particulares. Un 404 obligaria a la pantalla a tratar el
 * caso normal como una excepcion, y sobre todo perderia la diferencia entre "no hay convenio" y
 * "hay convenio pero esa practica no esta tarifada", que mandan al administrador a lugares
 * distintos. RN-M16-005: sin convenio valido no se asume cobertura — y para no asumirla hay que
 * poder verlo.
 *
 * <h2>Por que viaja la explicacion y no solo el numero</h2>
 *
 * <p>La etapa exige que el resultado sea explicable. Un endpoint que devuelve 12000 y nada mas deja
 * al administrador sin poder responder la unica pregunta que importa cuando el numero no es el
 * esperado: de que convenio salio. Por eso viajan el convenio, su codigo y las dos vigencias que
 * intervinieron —la del convenio y la del arancel—, que juntas son la derivacion completa.
 */
@Schema(description = "Arancel efectivo para una fecha, con la explicacion de la regla aplicada")
public record ArancelEfectivoResponse(

		@Schema(description = "Si se pudo resolver un arancel para esa fecha y ese contexto")
		boolean resuelto,

		@Schema(
				description = "Por que no hay arancel. Null cuando resuelto = true. "
						+ "SIN_CONVENIO_VIGENTE significa que la prestacion no tiene cobertura "
						+ "pactada y se cobra como particular; SIN_ARANCEL_VIGENTE significa que el "
						+ "convenio existe pero esa practica no esta tarifada para esa fecha",
				example = "SIN_CONVENIO_VIGENTE",
				allowableValues = {"SIN_CONVENIO_VIGENTE", "SIN_ARANCEL_VIGENTE"})
		String motivo,

		@Schema(description = "Dia contra el que se resolvio", example = "2026-03-15")
		LocalDate fecha,

		@Schema(description = "Convenio aplicado. Null si no se resolvio", example = "140")
		Long convenioId,

		@Schema(description = "Codigo del convenio aplicado", example = "OSDE-210-2026")
		String convenioCodigo,

		@Schema(description = "Nombre del convenio aplicado", example = "OSDE 210 - 2026")
		String convenioNombre,

		@Schema(description = "Modalidad pactada", example = "POR_PRESTACION")
		String modalidad,

		@Schema(description = "Arancel aplicado", example = "901")
		Long arancelId,

		@Schema(description = "Lo que vale la practica bajo ese convenio", example = "12000.00")
		BigDecimal importeTotal,

		@Schema(description = "La parte que paga el financiador", example = "9600.00")
		BigDecimal importeFinanciador,

		@Schema(description = "La parte que paga el paciente", example = "2400.00")
		BigDecimal coseguro,

		@Schema(description = "ISO 4217", example = "ARS")
		String moneda,

		@Schema(description = "Si la prestacion exige orden medica bajo ese convenio")
		Boolean requiereOrden,

		@Schema(description = "Si exige autorizacion previa del financiador")
		Boolean requiereAutorizacion,

		@Schema(description = "Si exige numero de credencial del paciente")
		Boolean requiereCredencial,

		@Schema(description = "Tope de sesiones por mes pactado. Null = sin tope", example = "20")
		Integer limiteSesionesMensual,

		@Schema(description = "Primer dia de vigencia del CONVENIO", example = "2026-01-01")
		LocalDate convenioVigenciaDesde,

		@Schema(description = "Ultimo dia de vigencia del CONVENIO, INCLUSIVE")
		LocalDate convenioVigenciaHasta,

		@Schema(description = "Primer dia de vigencia de ESTE importe", example = "2026-01-01")
		LocalDate arancelVigenciaDesde,

		@Schema(description = "Ultimo dia de vigencia de ESTE importe, INCLUSIVE")
		LocalDate arancelVigenciaHasta) {

	public static ArancelEfectivoResponse de(ResolucionDeArancel resolucion, LocalDate fecha) {
		if (!resolucion.estaResuelta()) {
			return new ArancelEfectivoResponse(
					false, resolucion.motivo().name(), fecha,
					null, null, null, null, null, null, null, null, null,
					null, null, null, null, null, null, null, null);
		}

		ArancelVigente a = resolucion.arancel();
		return new ArancelEfectivoResponse(
				true,
				null,
				a.resueltoPara(),
				a.convenioId(),
				a.convenioCodigo(),
				a.convenioNombre(),
				a.modalidad(),
				a.arancelId(),
				a.importeTotal(),
				a.importeFinanciador(),
				a.coseguro(),
				a.moneda(),
				a.requiereOrden(),
				a.requiereAutorizacion(),
				a.requiereCredencial(),
				a.limiteSesionesMensual(),
				a.convenioVigenciaDesde(),
				a.convenioVigenciaHasta(),
				a.arancelVigenciaDesde(),
				a.arancelVigenciaHasta());
	}
}
