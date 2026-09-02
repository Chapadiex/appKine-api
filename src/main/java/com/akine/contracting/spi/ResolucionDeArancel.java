package com.akine.contracting.spi;

import java.util.Optional;

/**
 * El resultado de resolver un arancel: o el arancel, o el motivo por el que no hay.
 *
 * <p><b>Nunca los dos, y nunca ninguno.</b> El constructor lo hace cumplir, para que no exista la
 * instancia que dice "no hay arancel" sin decir por que — que es exactamente el resultado inutil
 * que un {@code Optional} pelado produce.
 *
 * <p>Ver {@link MotivoSinArancel} para por que la ausencia necesita motivo.
 */
public record ResolucionDeArancel(ArancelVigente arancel, MotivoSinArancel motivo) {

	public ResolucionDeArancel {
		if ((arancel == null) == (motivo == null)) {
			throw new IllegalArgumentException(
					"Una resolucion trae el arancel O el motivo por el que no hay, nunca las dos "
							+ "cosas ni ninguna");
		}
	}

	public static ResolucionDeArancel resuelta(ArancelVigente arancel) {
		return new ResolucionDeArancel(arancel, null);
	}

	public static ResolucionDeArancel sinArancel(MotivoSinArancel motivo) {
		return new ResolucionDeArancel(null, motivo);
	}

	public boolean estaResuelta() {
		return arancel != null;
	}

	public Optional<ArancelVigente> valor() {
		return Optional.ofNullable(arancel);
	}
}
