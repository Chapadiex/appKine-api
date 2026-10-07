package com.akine.encounter.domain;

/**
 * Un tratamiento tal como tiene que quedar despues de una enmienda (C-6).
 *
 * @param tratamientoId el tratamiento vigente que se corrige, o {@code null} si es uno nuevo
 * @param aplicado      el contenido completo que tiene que quedar
 */
public record TratamientoEnmendado(Long tratamientoId, TratamientoAplicado aplicado) {

	public TratamientoEnmendado {
		if (aplicado == null) {
			throw new IllegalArgumentException("Un tratamiento enmendado necesita su contenido");
		}
	}

	public boolean esNuevo() {
		return tratamientoId == null;
	}
}
