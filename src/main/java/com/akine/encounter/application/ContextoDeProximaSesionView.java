package com.akine.encounter.application;

/** Ver {@code SesionService#contextoDeProximaSesion}. */
public record ContextoDeProximaSesionView(
		long personaId, Long casoId, Long planId, long ofertaId, long profesionalId) {
}
