package com.akine.assistant.domain;

import com.akine.assistant.spi.TipoDeAccion;

import java.time.Instant;
import java.util.Map;

/**
 * Una accion que el asistente propuso, tal como queda guardada: <b>solo ids</b>. Ni la frase, ni los
 * nombres, ni la nota clinica (docs/diseno/AKINE-asistente-acciones.md, §5.4).
 *
 * @param referencias los ids de lo propuesto: la confirmacion tiene que hablar de lo mismo
 * @param resultado   el resultado guardado al confirmar, como JSON; {@code null} hasta entonces
 */
public record AccionPropuesta(
		Long id,
		long organizationId,
		long consultorioId,
		long cuentaId,
		TipoDeAccion tipo,
		Estado estado,
		Map<String, Long> referencias,
		String resultado,
		Instant creadaEn,
		Instant venceEn) {

	/** {@code EN_CURSO}: alguien la esta confirmando; nadie mas puede tomarla. */
	public enum Estado {
		PROPUESTA, EN_CURSO, CONFIRMADA, DESCARTADA
	}

	public AccionPropuesta {
		referencias = referencias == null ? Map.of() : Map.copyOf(referencias);
	}

	public static AccionPropuesta nueva(long organizationId, long consultorioId, long cuentaId, TipoDeAccion tipo,
			Map<String, Long> referencias, Instant ahora, Instant venceEn) {
		return new AccionPropuesta(null, organizationId, consultorioId, cuentaId, tipo, Estado.PROPUESTA,
				referencias, null, ahora, venceEn);
	}

	public boolean vencida(Instant ahora) {
		return !venceEn.isAfter(ahora);
	}
}
