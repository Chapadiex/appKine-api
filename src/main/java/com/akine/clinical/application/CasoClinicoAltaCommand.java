package com.akine.clinical.application;

import java.util.List;

/**
 * Lo que hace falta para abrir un Caso Clinico (RF-M10-001).
 *
 * <p>Es un record y no siete parametros sueltos por lo mismo que
 * {@code AdjuntoClinicoAltaCommand}: el alta ya tiene seis datos y el equipo, y una firma de ocho
 * posiciones del mismo tipo primitivo es la clase de llamada donde un dia alguien invierte dos
 * argumentos y el compilador no dice nada.
 *
 * @param equipo                   integrantes iniciales del equipo tratante. Puede venir vacio:
 *                                 un caso abierto desde el mostrador todavia no sabe quien lo va a
 *                                 atender, y exigir el equipo en el alta obligaria a inventarlo
 * @param confirmaPosibleDuplicado el profesional ya vio los casos activos que coinciden y declara
 *                                 que este es otro. Sin esto, un alta que coincide se rechaza con
 *                                 409 y la lista de candidatos. <b>No saltea ningun invariante</b>
 *                                 —no hay invariante: RN-M10-002 admite varios casos activos— sino
 *                                 que registra que alguien miro
 */
public record CasoClinicoAltaCommand(
		long historiaClinicaId,
		long ofertaId,
		String diagnosticoPresuntivo,
		String objetivoTerapeutico,
		List<IntegranteDelEquipo> equipo,
		boolean confirmaPosibleDuplicado) {

	public CasoClinicoAltaCommand {
		equipo = equipo == null ? List.of() : List.copyOf(equipo);
	}
}
