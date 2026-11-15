package com.akine.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.akine.clinical.domain.EstadoPlan;
import com.akine.clinical.domain.PlanTratamiento;
import com.akine.clinical.domain.PlanTratamientoVersion;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanTratamientoRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanTratamientoVersionRepositoryPort;
import com.akine.clinical.spi.PlanVigenteSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** M31: el plan vigente del caso lleva el mismo avance que la pantalla del plan. */
class PlanVigenteDelCasoTest {

	private static final long ORG = 7L;
	private static final long CASO = 41L;

	private final PlanTratamientoRepositoryPort planes = mock(PlanTratamientoRepositoryPort.class);
	private final PlanTratamientoVersionRepositoryPort versiones =
			mock(PlanTratamientoVersionRepositoryPort.class);
	private final PlanTratamientoService planService = mock(PlanTratamientoService.class);
	private PlanVigenteDelCaso directorio;

	@BeforeEach
	void setUp() {
		directorio = new PlanVigenteDelCaso(planes, versiones, planService);
	}

	@Test
	void sinPlanQueOcupeElLugarDevuelveVacio() {
		when(planes.buscarQueOcupaElLugarDelCaso(ORG, CASO)).thenReturn(Optional.empty());

		assertThat(directorio.delCaso(ORG, CASO)).isEmpty();
	}

	@Test
	void conPlanActivoDevuelveVersionVigenteYAvance() {
		PlanVigenteSnapshot snapshot = conPlan(EstadoPlan.ACTIVO);

		assertThat(snapshot.activo()).isTrue();
		assertThat(snapshot.versionVigente()).isEqualTo(2);
		assertThat(snapshot.frecuenciaSemanal()).isEqualTo(3);
		assertThat(snapshot.items()).singleElement()
				.satisfies(item -> {
					assertThat(item.planificadas()).isEqualTo(10);
					assertThat(item.realizadas()).isEqualTo(4);
				});
		assertThat(snapshot.completo()).isFalse();
	}

	@Test
	void conPlanSuspendidoLoInformaComoTal() {
		PlanVigenteSnapshot snapshot = conPlan(EstadoPlan.SUSPENDIDO);

		assertThat(snapshot.activo()).isFalse();
		assertThat(snapshot.estado()).isEqualTo("SUSPENDIDO");
	}

	private PlanVigenteSnapshot conPlan(EstadoPlan estado) {
		PlanTratamiento plan = mock(PlanTratamiento.class);
		when(plan.getId()).thenReturn(90L);
		when(plan.getEstado()).thenReturn(estado);
		when(plan.getActivadoEn()).thenReturn(Instant.parse("2026-09-01T12:00:00Z"));
		PlanTratamientoVersion version = mock(PlanTratamientoVersion.class);
		when(version.getNumeroVersion()).thenReturn(2);
		when(version.getRegistradaEn()).thenReturn(Instant.parse("2026-10-01T12:00:00Z"));
		when(version.getObjetivos()).thenReturn("Recuperar la flexion");
		when(version.getFrecuenciaSemanal()).thenReturn(3);
		when(version.getDuracionSemanas()).thenReturn(8);
		when(planes.buscarQueOcupaElLugarDelCaso(ORG, CASO)).thenReturn(Optional.of(plan));
		when(versiones.buscarVigente(ORG, 90L)).thenReturn(Optional.of(version));
		when(planService.calcularAvance(plan, version)).thenReturn(new AvanceDelPlanView(
				90L, CASO, estado.name(), 2,
				List.of(new AvanceDeItemView(5L, "Kinesiologia", 10, null, 4, 1, 6, false)),
				false));

		return directorio.delCaso(ORG, CASO).orElseThrow();
	}
}
