package com.akine.billing.infrastructure;

import com.akine.billing.domain.Cobro;
import com.akine.billing.domain.port.CobroRepositoryPort;
import com.akine.scheduling.spi.PrepagoDeTurnoProbe;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Responde a la recepcion si un turno ya tiene su prepago (AKINE E-6). Implementa el contrato
 * que {@code scheduling} declara: la dependencia queda {@code billing -> scheduling}, en el mismo
 * sentido que la que ya existia a traves de {@code encounter}.
 *
 * <p>Un solo prepago vigente por turno lo garantiza {@code uk_cobro_prepago_turno_vigente}, asi
 * que el mapa no puede perder filas por clave repetida.
 */
@Component
public class PrepagosDeTurnoEnCobros implements PrepagoDeTurnoProbe {

	private final CobroRepositoryPort cobros;

	public PrepagosDeTurnoEnCobros(CobroRepositoryPort cobros) {
		this.cobros = cobros;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Long, PrepagoDeTurno> prepagosDe(long organizationId, Collection<Long> turnoIds) {
		if (turnoIds.isEmpty()) {
			return Map.of();
		}
		return cobros.prepagosVigentesDeTurnos(organizationId, turnoIds).stream()
				.map(PrepagosDeTurnoEnCobros::proyectar)
				.collect(Collectors.toMap(PrepagoDeTurno::turnoId, Function.identity()));
	}

	private static PrepagoDeTurno proyectar(Cobro cobro) {
		return new PrepagoDeTurno(cobro.getTurnoId(), cobro.getId(), cobro.getTotal(),
				cobro.getSaldoAFavor(), cobro.getMoneda());
	}
}
