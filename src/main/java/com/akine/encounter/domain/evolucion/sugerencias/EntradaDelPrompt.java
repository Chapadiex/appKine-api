package com.akine.encounter.domain.evolucion.sugerencias;

import com.akine.encounter.domain.evolucion.DatosDelCaso;

/** Lo del caso que viaja al modelo; la arma la capa de aplicacion (el dominio no conoce {@code CasoAnalizado}). */
public record EntradaDelPrompt(String diagnostico, String objetivoTerapeutico, String objetivosDelPlan,
		DatosDelCaso datos) {
}
