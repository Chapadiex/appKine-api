package com.akine.scheduling.application;

import com.akine.scheduling.domain.ReglaDeRecurrencia;

import com.akine.scheduling.domain.OrigenDeTurno;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Pedido de alta de una serie de turnos (AKINE E-3). */
public record AltaDeSerieCommand(
		long ofertaId,
		long personaId,
		Long profesionalId,
		ReglaDeRecurrencia regla,
		String idempotencyKey,
		List<OcurrenciaElegida> ocurrencias,
		Long casoId,
		Long planId,
		OrigenDeTurno origen) {

	/**
	 * Lo que quedo despues de revisar la previsualizacion (TURNO - Tecnico §20): se reservan EXACTAMENTE
	 * estos horarios y no los de la regla. La regla queda como dato de la serie.
	 *
	 * @param profesionalId {@code null} = el de la serie
	 */
	public record OcurrenciaElegida(Instant inicio, Long profesionalId) {
	}

	public AltaDeSerieCommand(
			long ofertaId, long personaId, Long profesionalId, ReglaDeRecurrencia regla, String idempotencyKey) {
		this(ofertaId, personaId, profesionalId, regla, idempotencyKey, null, null, null, null);
	}

	public boolean conOcurrenciasElegidas() {
		return ocurrencias != null && !ocurrencias.isEmpty();
	}

	/**
	 * Huella del pedido para la idempotencia: misma clave con otro contenido es 409, no un replay de
	 * la serie anterior. Misma decision que {@link ReservaCommand#huella}.
	 */
	public String huella(long consultorioId) {
		String canonico = consultorioId + "|" + ofertaId + "|" + personaId + "|" + profesionalId
				+ "|" + regla.diasComoTexto() + "|" + regla.hora() + "|" + regla.desde()
				+ "|" + regla.hasta() + "|" + regla.cantidad()
				// Solo si vienen: la huella de un pedido sin nada de esto no cambia.
				+ (casoId == null ? "" : "|k" + casoId)
				+ (planId == null ? "" : "|p" + planId)
				+ (conOcurrenciasElegidas() ? "|o" + ocurrencias : "");
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonico.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException imposible) {
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
		}
	}
}
