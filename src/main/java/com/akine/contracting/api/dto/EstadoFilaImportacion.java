package com.akine.contracting.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** El desenlace de una fila de una importacion de aranceles (B-7). */
@Schema(description = "ALTA: la fila entra como arancel nuevo. RECHAZADA: no entraria; problemType "
		+ "dice por que, con el mismo valor que daria el alta unitaria")
public enum EstadoFilaImportacion {
	ALTA,
	RECHAZADA
}
