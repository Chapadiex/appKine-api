package com.akine.identity.api.dto;

import com.akine.identity.domain.TerminosSettings;
import io.swagger.v3.oas.annotations.media.Schema;

/** Version vigente de los terminos y condiciones del alta (DP-45). Sin el texto legal. */
@Schema(description = "Terminos y condiciones vigentes para el alta de cuentas")
public record TermsResponse(

		@Schema(
				description = "Version que el alta exige aceptar, tal cual hay que mandarla en "
						+ "versionTerminos. null si el despliegue no exige terminos",
				example = "2026-10",
				nullable = true)
		String versionVigente,

		@Schema(
				description = "Donde se publica el texto de los terminos. null si no se configuro",
				example = "https://akine.example.test/terminos",
				nullable = true)
		String url,

		@Schema(
				description = "true si el registro y la aceptacion de invitacion con cuenta nueva "
						+ "exigen aceptaTerminos=true con versionVigente",
				example = "true",
				requiredMode = Schema.RequiredMode.REQUIRED)
		boolean aceptacionObligatoria) {

	public static TermsResponse de(TerminosSettings settings) {
		return new TermsResponse(
				settings.versionVigente(), settings.url(), settings.exigeAceptacion());
	}
}
