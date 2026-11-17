package com.akine.encounter.application;

import com.akine.encounter.domain.SugerenciaEvolucion;
import com.akine.encounter.spi.CitaGuardada;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lo que devuelve un pedido de sugerencias de evolucion (diseno fase 2, §8.1).
 *
 * @param generacionId agrupa las sugerencias guardadas en este pedido
 * @param descartadas  cuantas propuso el modelo y no pasaron el validador; no se muestran ni se guardan
 * @param advertencias {@code TEXTO_OMITIDO} si algun texto se reemplazo por {@code [texto omitido]}, mas
 *                     las de la fase 1 (recorte por tope, sesiones sin caso)
 */
public record SugerenciasView(UUID generacionId, Resultado resultado, List<Sugerencia> sugerencias, int descartadas,
		List<String> advertencias) {

	public SugerenciasView {
		sugerencias = List.copyOf(sugerencias);
		advertencias = List.copyOf(advertencias);
	}

	/** Como termino el pedido. */
	public enum Resultado {
		SUGERENCIAS,
		SIN_MATERIAL,
		SIN_HALLAZGOS_ELEGIBLES
	}

	/** Una sugerencia guardada, con su respuesta. */
	public record Sugerencia(
			long id,
			UUID generacionId,
			String hallazgo,
			String texto,
			String fundamento,
			List<CitaGuardada> citas,
			List<Long> sesiones,
			String modelo,
			String respuesta,
			long solicitadaPorCuentaId,
			Instant solicitadaEn,
			Long respondidaPorCuentaId,
			Instant respondidaEn,
			String motivoDescarte,
			long version) {

		static Sugerencia de(SugerenciaEvolucion s) {
			return new Sugerencia(s.getId(), s.getGeneracionId(), s.getHallazgoCodigo(), s.getTexto(),
					s.getFundamento(), s.getCitas(), s.getSesiones(), s.getModelo(), s.getRespuesta().name(),
					s.getSolicitadaPorCuentaId(), s.getSolicitadaEn(), s.getRespondidaPorCuentaId(),
					s.getRespondidaEn(), s.getMotivoDescarte(), s.getVersion());
		}
	}
}
