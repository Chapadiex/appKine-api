package com.akine.scheduling.infrastructure;

import com.akine.resource.spi.DisponibilidadImpactProbe;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Implementacion de {@code resource.spi.DisponibilidadImpactProbe} sobre la tabla {@code turno}
 * (paquete E-1). <b>Reemplaza</b> a {@code resource.infrastructure.DisponibilidadImpactProbeSinAgenda},
 * que devolvia siempre cero y se borro en este mismo cambio: la interfaz es un bean singular y las
 * dos no pueden convivir.
 *
 * <h2>Que cuenta, y que aproximacion declara</h2>
 *
 * <p>Los turnos pendientes de ese profesional, en esa sede, que <b>empiezan</b> dentro de la
 * ventana que {@code DisponibilidadService} calcula —desde ahora hasta el fin de vigencia mas
 * lejano entre el anterior y el nuevo, acotado a noventa dias—.
 *
 * <p><b>Es una cota superior, no el conjunto exacto.</b> La firma recibe la ventana y no el bloque
 * ni la disponibilidad resultante, asi que no puede distinguir un turno que cae en el bloque que
 * se recorta de uno que cae en OTRO bloque del mismo profesional que sigue vigente. Calcularlo
 * exacto obligaria a cambiar el {@code spi} de {@code resource} —pasar dia, horas y vigencia— y
 * ademas no funcionaria en la baja, donde la sonda se consulta ANTES de desactivar el bloque.
 * Para lo que la sonda existe —que la pantalla avise "revisa estos turnos" antes de confirmar
 * (RN-M05-004)— una cota superior es la direccion segura del error: muestra de mas, nunca deja un
 * turno huerfano sin avisar. <b>Informa, no bloquea</b>: el servicio no rechaza nada por esto.
 */
@Component
public class DisponibilidadImpactoSobreTurnos implements DisponibilidadImpactProbe {

	private final TurnoRepositoryPort turnos;

	public DisponibilidadImpactoSobreTurnos(TurnoRepositoryPort turnos) {
		this.turnos = turnos;
	}

	@Override
	public Impacto turnosEn(
			long organizationId, long consultorioId, long membershipId, Instant desde, Instant hasta) {

		if (!hasta.isAfter(desde)) {
			return Impacto.ninguno();
		}
		List<Turno> pendientes = turnos.findPendientesDelProfesionalEnLaSede(
				organizationId, consultorioId, membershipId, desde, hasta);
		if (pendientes.isEmpty()) {
			return Impacto.ninguno();
		}
		Instant primero = pendientes.stream().map(Turno::getInicio).min(Instant::compareTo).orElseThrow();
		return new Impacto(pendientes.size(), primero);
	}
}
