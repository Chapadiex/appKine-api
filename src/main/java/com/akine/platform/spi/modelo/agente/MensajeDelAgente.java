package com.akine.platform.spi.modelo.agente;

import java.util.List;

/** Un mensaje del historial de la conversacion con el agente. */
public sealed interface MensajeDelAgente {

	/** Lo que escribio la persona. */
	record DelUsuario(String texto) implements MensajeDelAgente {
	}

	/** Un turno anterior del modelo, reenviado sin cambios. */
	record DelModelo(TurnoDelAgente turno) implements MensajeDelAgente {
	}

	/** Los resultados de las herramientas que el modelo pidio. */
	record Resultados(List<ResultadoDeLlamada> resultados) implements MensajeDelAgente {
	}
}
