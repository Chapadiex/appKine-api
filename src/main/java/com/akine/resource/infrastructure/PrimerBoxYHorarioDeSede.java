package com.akine.resource.infrastructure;

import com.akine.organization.spi.AltaDeSedeExtension;
import com.akine.resource.application.CalendarioService;
import com.akine.resource.application.EspacioService;
import com.akine.resource.domain.FranjaHorarioGeneral.Franja;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * La parte de {@code resource} del alta de sede en un acto (RF-M03-002, CA-M03-002): el primer
 * box y el horario general. Ver {@link AltaDeSedeExtension} para el contrato de la invocacion.
 *
 * <p>El horario va primero a proposito: es lo que mas probablemente falle por validacion, y
 * fallar antes de escribir el box ahorra un INSERT que igual se iba a revertir.
 */
@Component
class PrimerBoxYHorarioDeSede implements AltaDeSedeExtension {

	private final EspacioService espacioService;
	private final CalendarioService calendarioService;

	PrimerBoxYHorarioDeSede(EspacioService espacioService, CalendarioService calendarioService) {
		this.espacioService = espacioService;
		this.calendarioService = calendarioService;
	}

	@Override
	public void completarAlta(
			long organizationId, long consultorioId, long accountId, Complemento complemento) {

		if (!complemento.horarioGeneral().isEmpty()) {
			List<Franja> franjas = complemento.horarioGeneral().stream()
					.map(f -> new Franja(f.diaSemana(), f.horaDesde(), f.horaHasta()))
					.toList();
			calendarioService.fijarHorarioDeSedeNueva(
					organizationId, consultorioId, accountId, franjas);
		}

		PrimerBox box = complemento.primerBox();
		if (box != null) {
			espacioService.crearPrimerBoxDeSedeNueva(
					organizationId, consultorioId, accountId, box.nombre(), box.capacidad());
		}
	}
}
