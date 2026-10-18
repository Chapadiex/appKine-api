package com.akine.activity.application;

import com.akine.clinical.spi.DerivacionSnapshot;

import java.time.Instant;

/**
 * Una derivacion vista desde {@code activity}: ids y estados, nada clinico. Es la proyeccion de
 * {@link DerivacionSnapshot} sin el tenant ni el origen, que el llamador ya conoce por la ruta.
 *
 * @param numeroCaso el numero visible del Caso, no el id. {@code null} si no se resolvio
 * @param yaExistia {@code true} cuando el pedido no creo nada porque la derivacion ya estaba
 */
public record DerivacionView(
		long id,
		long inscripcionId,
		long asistenciaId,
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

	static DerivacionView de(DerivacionSnapshot s, long inscripcionId) {
		return new DerivacionView(
				s.id(), inscripcionId, s.participacionId(), s.personaId(), s.historiaClinicaId(),
				s.casoClinicoId(), s.numeroCaso(), s.planTratamientoId(), s.ofertaId(),
				s.requiereCasoClinico(),
				s.autorizacionId(), s.estado(), s.motivo(), s.derivadaEn(), s.derivadaPorCuentaId(),
				s.revertidaEn(), s.revertidaPorCuentaId(), s.motivoReversion(), s.version(),
				s.yaExistia());
	}
}
