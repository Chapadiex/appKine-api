package com.akine.scheduling.application;

import com.akine.offering.spi.OfertaDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.CoberturasAplicablesDirectory;
import com.akine.person.spi.PacienteDirectory;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.audit.AuditoriaAparte;
import com.akine.resource.spi.BloqueoDeAgendaDeProfesional;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.AgendaSedeRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoEventoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.spi.AtencionProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Cambio de sede con alcance propio (agenda de profesionales): el {@code PROFESIONAL} tiene
 * {@code turno:manage} en las dos sedes, pero solo puede llevar el turno a SU agenda en la otra sede.
 * Nombrar a otro profesional de destino es 403 y se corta antes de tomar ningun lock.
 */
class TrasladoDeTurnoServiceTest {

	private static final long ORG_ID = 7L;
	private static final long ORIGEN = 11L;
	private static final long DESTINO = 12L;
	private static final long CUENTA = 42L;
	private static final long PROFESIONAL_AJENO_EN_DESTINO = 200L;

	@Test
	@DisplayName("Con alcance propio, trasladar a la agenda de otro profesional es 403 y no toma locks")
	void trasladar_a_la_agenda_ajena_es_403() {
		PermissionGuard guard = mock(PermissionGuard.class);
		given(guard.requirePermission(any())).willReturn(
				PermissionDecision.concedida(PermissionDecision.ALCANCE_ACTIVIDAD_PROPIA, false));
		ConsultorioMembershipDirectory memberships = mock(ConsultorioMembershipDirectory.class);
		given(memberships.find(ORG_ID, PROFESIONAL_AJENO_EN_DESTINO)).willReturn(Optional.of(
				new ConsultorioMembershipSnapshot(PROFESIONAL_AJENO_EN_DESTINO, 43L, ORG_ID, DESTINO,
						"PROFESIONAL", "ACTIVA", Instant.parse("2026-01-01T00:00:00Z"), null, true, true)));
		SoporteEnAgenda soporte = new SoporteEnAgenda(guard, mock(AuditoriaAparte.class),
				new AgendaDelProfesional(memberships, mock(BloqueoDeAgendaDeProfesional.class)));

		ConsultorioDirectory consultorios = mock(ConsultorioDirectory.class);
		given(consultorios.find(ORG_ID, ORIGEN))
				.willReturn(Optional.of(new ConsultorioSnapshot(ORIGEN, ORG_ID, "Centro", "UTC", true)));
		given(consultorios.find(ORG_ID, DESTINO))
				.willReturn(Optional.of(new ConsultorioSnapshot(DESTINO, ORG_ID, "Norte", "UTC", true)));
		AgendaSedeIniciador iniciador = mock(AgendaSedeIniciador.class);
		TurnoRepositoryPort turnos = mock(TurnoRepositoryPort.class);

		TrasladoDeTurnoService servicio = new TrasladoDeTurnoService(
				turnos, mock(TurnoEventoRepositoryPort.class), mock(AgendaSedeRepositoryPort.class),
				mock(OfertaDirectory.class), consultorios, mock(PacienteDirectory.class),
				mock(CoberturasAplicablesDirectory.class), soporte, iniciador, mock(RevalidadorDeSlot.class),
				mock(AtencionProbe.class), mock(RegistroDeRecepcion.class), mock(ContextoClinicoDeTurno.class),
				mock(AvisosDeTurno.class), mock(AuditTrail.class), mock(CicloDeTurnoService.class),
				mock(PlatformTransactionManager.class));

		OperatingActor profesional = new OperatingActor(CUENTA, false, ORG_ID, ORIGEN, "PROFESIONAL");
		TrasladoCommand command = new TrasladoCommand(DESTINO, 5L, Instant.parse("2099-01-05T12:00:00Z"),
				PROFESIONAL_AJENO_EN_DESTINO, null, "Se muda de sede", 0L);

		assertThatThrownBy(() -> servicio.trasladar(profesional, ORIGEN, 99L, command))
				.isInstanceOf(AccessDeniedException.class);
		verifyNoInteractions(iniciador, turnos);
	}
}
