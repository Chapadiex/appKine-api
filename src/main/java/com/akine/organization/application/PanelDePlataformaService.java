package com.akine.organization.application;

import com.akine.organization.domain.PanelDePlataforma;
import com.akine.organization.domain.port.PanelDePlataformaPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Panel de control de la plataforma (19/11/2026): lo que necesita atencion y lo ultimo que paso.
 * Solo lectura; quien lo pide ya paso por el control de rol de plataforma.
 */
@Service
public class PanelDePlataformaService {

	/** Una prueba "por vencer" termina en esta semana. */
	static final Duration POR_VENCER = Duration.ofDays(7);

	/** El listado de pruebas que terminan mira un mes hacia adelante. */
	static final Duration HORIZONTE = Duration.ofDays(30);

	/** Cuantos elementos trae cada listado del panel. */
	static final int CUANTOS = 6;

	private final PanelDePlataformaPort port;
	private final Clock clock;

	@Autowired
	PanelDePlataformaService(PanelDePlataformaPort port) {
		this(port, Clock.systemUTC());
	}

	PanelDePlataformaService(PanelDePlataformaPort port, Clock clock) {
		this.port = port;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PanelDePlataforma leer() {
		Instant ahora = clock.instant();
		return port.leer(ahora, ahora.plus(POR_VENCER), ahora.plus(HORIZONTE), CUANTOS);
	}
}
