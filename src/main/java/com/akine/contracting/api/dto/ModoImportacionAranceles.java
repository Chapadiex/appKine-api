package com.akine.contracting.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Mirar sin escribir, o aplicar todo o nada (B-7, RF-M16-007). */
@Schema(description = "PREVIEW valida cada fila sin escribir ni bloquear. CONFIRMAR revalida todo "
		+ "bajo el lock del convenio y aplica el lote entero, o nada")
public enum ModoImportacionAranceles {
	PREVIEW,
	CONFIRMAR
}
