package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Cuentas vigentes por estado")
public record CuentasDePlataformaResponse(
		@Schema(description = "Pueden iniciar sesion", example = "40") long activas,
		@Schema(description = "Recibieron el enlace y todavia no lo usaron", example = "3")
		long pendientesDeActivar,
		@Schema(description = "Bloqueadas por intentos fallidos o por la plataforma", example = "1")
		long bloqueadas) {
}
