package com.akine.person.spi;

import com.akine.contracting.spi.MotivoSinArancel;

/** Una cobertura vigente que no aplica a la practica por convenio, con su motivo. */
public record CoberturaNoAplicable(
		long coberturaId,
		boolean principal,
		ReferenciaCongelada referencia,
		MotivoSinArancel motivo) {
}
