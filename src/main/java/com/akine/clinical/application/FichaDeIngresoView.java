package com.akine.clinical.application;

import com.akine.clinical.domain.FichaDeIngreso;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** La ficha de ingreso tal como sale del servicio, con el IMC calculado y los nombres resueltos. */
public record FichaDeIngresoView(
		long version,
		Long ocupacionId,
		String ocupacionNombre,
		BigDecimal pesoKg,
		BigDecimal alturaCm,
		BigDecimal imc,
		String actividad,
		String detalle,
		String origen,
		String cargadoPorNombre,
		String revisadoPorNombre,
		Instant revisadoEn,
		Instant actualizadaEn) {

	static FichaDeIngresoView de(FichaDeIngreso ficha, String ocupacionNombre, Map<Long, String> nombres) {
		return new FichaDeIngresoView(
				ficha.getVersion(),
				ficha.getOcupacionId(),
				ocupacionNombre,
				ficha.getPesoKg(),
				ficha.getAlturaCm(),
				ficha.imc(),
				ficha.getActividadFisica() == null ? null : ficha.getActividadFisica().name(),
				ficha.getActividadDetalle(),
				ficha.getOrigen().name(),
				nombres.get(ficha.getCargadoPor()),
				ficha.getRevisadoPor() == null ? null : nombres.get(ficha.getRevisadoPor()),
				ficha.getRevisadoEn(),
				ficha.getUpdatedAt());
	}
}
