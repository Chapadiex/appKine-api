package com.akine.organization.domain.port;

import com.akine.organization.domain.PanelDePlataforma;

import java.time.Instant;

/** Lecturas agregadas para el panel de la plataforma: conteos y ultimos movimientos. */
public interface PanelDePlataformaPort {

	/**
	 * @param ahora     instante de referencia para leer las pruebas vencidas y por vencer
	 * @param porVencer fin de la ventana de "prueba por vencer"
	 * @param horizonte fin de la ventana del listado de pruebas que terminan
	 * @param cuantos   largo de cada listado
	 */
	PanelDePlataforma leer(Instant ahora, Instant porVencer, Instant horizonte, int cuantos);
}
