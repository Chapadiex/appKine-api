package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.MotivoExcepcion;
import com.akine.resource.domain.TipoExcepcion;
import com.akine.resource.domain.exception.BloqueoConTurnosAfectadosException;
import com.akine.resource.domain.exception.MotivoOtroSinNotasException;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.DisponibilidadExcepcionRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * La edicion de una excepcion (agenda operativa, checklist §2.5 y §13.2): el 409 con la lista cuando
 * deja turnos afuera sin confirmar, la auditoria con el antes y el despues, y "Otro" sin notas.
 */
class ExcepcionEdicionTest {

	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long MEMBERSHIP_ID = 30L;
	private static final long EXCEPCION_ID = 701L;
	private static final LocalDate DESDE = LocalDate.of(2027, 3, 8);
	private static final LocalDate HASTA = LocalDate.of(2027, 3, 9);

	private final DisponibilidadExcepcionRepositoryPort excepciones = mock(DisponibilidadExcepcionRepositoryPort.class);
	private final CalendarioSedeRepositoryPort calendarios = mock(CalendarioSedeRepositoryPort.class);
	private final ConsultorioDirectory sedes = mock(ConsultorioDirectory.class);
	private final AuditTrail auditTrail = mock(AuditTrail.class);
	private final SimuladorDeImpacto simulador = mock(SimuladorDeImpacto.class);
	private final OperatingActor actor = new OperatingActor(40L, false, ORG_ID, SEDE_ID);

	private ExcepcionService service;
	private DisponibilidadExcepcion existente;

	@BeforeEach
	void setUp() {
		service = new ExcepcionService(excepciones, calendarios,
				new CalendarioSedeIniciador(calendarios, mock(PlatformTransactionManager.class)), sedes,
				mock(ConsultorioMembershipDirectory.class), mock(PermissionGuard.class), auditTrail, simulador,
				mock(PlatformTransactionManager.class));
		given(sedes.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(SEDE_ID, ORG_ID, "Sede Sintetica", "America/Argentina/Cordoba", true)));
		given(calendarios.lockByScope(ORG_ID, SEDE_ID)).willReturn(Optional.of(new CalendarioSede(ORG_ID, SEDE_ID)));
		existente = new DisponibilidadExcepcion(ORG_ID, SEDE_ID, MEMBERSHIP_ID, TipoExcepcion.CIERRE,
				MotivoExcepcion.LICENCIA, DESDE, HASTA, null, null, null, null);
		ReflectionTestUtils.setField(existente, "id", EXCEPCION_ID);
		given(excepciones.findByIdScoped(EXCEPCION_ID, ORG_ID, SEDE_ID)).willReturn(Optional.of(existente));
		given(excepciones.save(any())).willAnswer(invocacion -> invocacion.getArgument(0));
	}

	@Test
	@DisplayName("una edicion que deja turnos afuera sin confirmar es 409 con la lista y no guarda nada")
	void sin_confirmar_es_409_con_la_lista() {
		given(simulador.deEdicionDeExcepcion(any(), any(), any(), any())).willReturn(impactoDeUnTurno());

		assertThatThrownBy(() -> service.editar(actor, SEDE_ID, EXCEPCION_ID, edicion(MotivoExcepcion.VACACIONES,
				DESDE.minusDays(1), null, false)))
				.isInstanceOfSatisfying(BloqueoConTurnosAfectadosException.class, rechazo -> {
					assertThat(rechazo.getTurnosAfectados()).isEqualTo(1);
					assertThat(rechazo.getTurnos()).singleElement().satisfies(turno -> {
						assertThat(turno.turnoId()).isEqualTo(4100L);
						assertThat(turno.personaNombre()).isEqualTo("Perez, Ana");
						assertThat(turno.estado()).isEqualTo("CONFIRMADO");
					});
				});
		verify(excepciones, never()).save(any());
		verify(auditTrail, never()).record(any());
		assertThat(existente.getMotivo()).isEqualTo(MotivoExcepcion.LICENCIA);
	}

	@Test
	@DisplayName("confirmada, la edicion se guarda y la auditoria lleva el antes y el despues")
	void confirmada_guarda_y_audita_el_cambio() {
		given(simulador.deEdicionDeExcepcion(any(), any(), any(), any())).willReturn(impactoDeUnTurno());

		ExcepcionView vista = service.editar(actor, SEDE_ID, EXCEPCION_ID, edicion(MotivoExcepcion.VACACIONES,
				DESDE.minusDays(1), null, true));

		assertThat(vista.motivo()).isEqualTo("VACACIONES");
		assertThat(vista.fechaDesde()).isEqualTo(DESDE.minusDays(1));
		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().eventType()).isEqualTo("DISPONIBILIDAD_EXCEPCION_UPDATED");
		assertThat(auditoria.getValue().details())
				.containsEntry("motivo", "LICENCIA -> VACACIONES")
				.containsEntry("fechaDesde", DESDE + " -> " + DESDE.minusDays(1))
				.containsEntry("turnosAfectados", "1")
				.doesNotContainKey("fechaHasta");
	}

	@Test
	@DisplayName("la version vieja es 409 antes de simular nada")
	void version_vieja() {
		assertThatThrownBy(() -> service.editar(actor, SEDE_ID, EXCEPCION_ID,
				new ExcepcionEdicionCommand(MotivoExcepcion.LICENCIA, DESDE, HASTA, null, null, null, 5L, false)))
				.isInstanceOf(OptimisticLockingFailureException.class);
		verify(simulador, never()).deEdicionDeExcepcion(any(), any(), any(), any());
	}

	@Test
	@DisplayName("con motivo Otro las notas son obligatorias, al editar y al cargar")
	void otro_exige_notas() {
		assertThatThrownBy(() -> service.editar(actor, SEDE_ID, EXCEPCION_ID,
				edicion(MotivoExcepcion.OTRO, DESDE, "   ", true)))
				.isInstanceOf(MotivoOtroSinNotasException.class);
		verify(excepciones, never()).save(any());

		assertThatThrownBy(() -> new DisponibilidadExcepcion(ORG_ID, SEDE_ID, null, TipoExcepcion.CIERRE,
				MotivoExcepcion.OTRO, DESDE, HASTA, null, null, null, null))
				.isInstanceOf(MotivoOtroSinNotasException.class);
		assertThat(new DisponibilidadExcepcion(ORG_ID, SEDE_ID, null, TipoExcepcion.CIERRE,
				MotivoExcepcion.OTRO, DESDE, HASTA, null, null, null, "Corte de luz").getNotes())
				.isEqualTo("Corte de luz");
	}

	private static ExcepcionEdicionCommand edicion(
			MotivoExcepcion motivo, LocalDate desde, String notas, boolean confirma) {
		return new ExcepcionEdicionCommand(motivo, desde, HASTA, null, null, notas, 0L, confirma);
	}

	private static ImpactoDeDisponibilidad impactoDeUnTurno() {
		Instant inicio = Instant.parse("2027-03-07T13:00:00Z");
		return new ImpactoDeDisponibilidad(1L, inicio, List.of(new ImpactoDeDisponibilidad.TurnoAfectado(
				4100L, MEMBERSHIP_ID, inicio, inicio.plusSeconds(3600), 77L, "Perez, Ana", "Gomez, Laura",
				"CONFIRMADO")), HASTA);
	}
}
