package com.akine.clinical.spi;

import java.time.Instant;

/**
 * Lo que otro modulo necesita saber de una derivacion, sin poder tocar su entity.
 *
 * <p><b>No viaja nada clinico.</b> Ni diagnostico ni objetivo terapeutico: viajan ids, estados y
 * el numero visible del Caso. Es el mismo argumento, y casi la misma linea, que
 * {@link CasoSnapshot} y {@link HistoriaClinicaSnapshot}. Lo que un modulo necesita es saber que el
 * vinculo existe y a que apunta, no que dice.
 *
 * @param numeroCaso el numero del Caso que un profesional dice en voz alta, el mismo que ya viaja
 *                   en {@link CasoSnapshot#numeroCaso()}: es como la pantalla nombra a que Caso
 *                   esta derivado alguien sin mostrar un id (pedido de web #104, 08.05). Se
 *                   resuelve al leer, y es {@code null} solo si el Caso no se encontro
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
		Integer numeroCaso,
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
