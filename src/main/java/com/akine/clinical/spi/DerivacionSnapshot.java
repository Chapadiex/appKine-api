package com.akine.clinical.spi;

import java.time.Instant;

/**
 * Lo que otro modulo necesita saber de una derivacion, sin poder tocar su entity.
 *
 * <p><b>No viaja nada clinico.</b> Ni diagnostico, ni objetivo terapeutico, ni el numero de Caso
 * que un profesional dice en voz alta: viajan ids y estados. Es el mismo argumento, y casi la misma
 * linea, que {@link CasoSnapshot} y {@link HistoriaClinicaSnapshot}. Lo que un modulo necesita es
 * saber que el vinculo existe y a que apunta, no que dice.
 *
 * @param yaExistia {@code true} cuando este registro no creo nada porque la derivacion ya estaba.
 *                  Es el estado de ESTA llamada, no de la fila: un doble submit devuelve la misma
 *                  fila con {@code true}. Ver {@code DerivacionClinicaRegistry#registrar}
 */
public record DerivacionSnapshot(
		long id,
		long organizationId,
		long consultorioId,
		OrigenDeParticipacion origen,
		long participacionId,
		long personaId,
		long historiaClinicaId,
		long casoClinicoId,
		Long planTratamientoId,
		long ofertaId,
		boolean requiereCasoClinico,
		Long autorizacionId,
		String estado,
		String motivo,
		Instant derivadaEn,
		Long derivadaPorCuentaId,
		Instant revertidaEn,
		Long revertidaPorCuentaId,
		String motivoReversion,
		long version,
		boolean yaExistia) {

	/** {@code true} si el vinculo esta en pie. */
	public boolean estaVigente() {
		return "VIGENTE".equals(estado);
	}
}
