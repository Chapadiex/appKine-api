package com.akine.scheduling.application;

import com.akine.offering.spi.OfertaDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.scheduling.domain.EstadoTurno;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.TurnoConAtencionException;
import com.akine.scheduling.domain.exception.TurnoNotAccessibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.AgendaSedeRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoEventoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.spi.AtencionProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Las reglas del servicio que no dependen de la base.
 *
 * <p>Solo tres, y las tres son decisiones de negocio de esta etapa: que un turno con atencion no se
 * deshace, que la version se compara antes de mutar, y que el historial de un turno inexistente es
 * 404 y no una lista vacia. Todo lo que decide la concurrencia real vive en
 * {@code TurnoCicloConcurrenteIT}, porque un mock no reproduce el gestor de locks de InnoDB.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CicloDeTurnoService")
class CicloDeTurnoServiceTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long TURNO_ID = 301L;

	@Mock private TurnoRepositoryPort turnos;
	@Mock private TurnoEventoRepositoryPort eventos;
	@Mock private AgendaSedeRepositoryPort agendas;
	@Mock private OfertaDirectory ofertas;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AgendaSedeIniciador iniciador;
	@Mock private RevalidadorDeSlot revalidador;
	@Mock private AtencionProbe atenciones;
	@Mock private AuditTrail auditTrail;

	private CicloDeTurnoService service;
	private final OperatingActor actor = new OperatingActor(9L, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void prepararServicio() {
		service = new CicloDeTurnoService(turnos, eventos, agendas, ofertas, consultorios,
				permissionGuard, iniciador, revalidador, atenciones, auditTrail);

		given(consultorios.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(CONSULTORIO_ID, ORG_ID, "Sede", "America/Argentina/Cordoba", true)));
	}

	@Test
	@DisplayName("un turno con atencion registrada no se cancela: la Sesion es la prueba de que ocurrio")
	void no_se_cancela_un_turno_con_atencion() {
		Turno turno = turnoFuturo();
		given(turnos.findByIdInScope(ORG_ID, CONSULTORIO_ID, TURNO_ID)).willReturn(Optional.of(turno));
		given(atenciones.tieneAtencion(ORG_ID, CONSULTORIO_ID, TURNO_ID)).willReturn(true);

		assertThatThrownBy(() -> service.cancelar(actor, CONSULTORIO_ID, TURNO_ID, "me equivoque", 0L))
				.isInstanceOf(TurnoConAtencionException.class);

		assertThat(turno.getEstado())
				.as("y el turno no queda a medio cancelar")
				.isEqualTo(EstadoTurno.RESERVADO);
		verify(turnos, never()).save(turno);
	}

	@Test
	@DisplayName("una version vieja no pisa lo que otro operador ya cambio")
	void la_version_se_compara_antes_de_mutar() {
		Turno turno = turnoFuturo();
		given(turnos.findByIdInScope(ORG_ID, CONSULTORIO_ID, TURNO_ID)).willReturn(Optional.of(turno));

		assertThatThrownBy(() -> service.cancelar(actor, CONSULTORIO_ID, TURNO_ID, "motivo", 3L))
				.isInstanceOf(OptimisticLockingFailureException.class);

		verify(turnos, never()).save(turno);
	}

	@Test
	@DisplayName("el historial de un turno de otra sede es 404, no una lista vacia")
	void el_historial_no_es_un_oraculo_de_existencia() {
		given(turnos.findByIdInScope(ORG_ID, CONSULTORIO_ID, TURNO_ID)).willReturn(Optional.empty());

		// Una lista vacia con 200 diria "ese id existe pero no tiene eventos", que es informacion
		// sobre datos de otro tenant. ADR-0018.
		assertThatThrownBy(() -> service.historial(actor, CONSULTORIO_ID, TURNO_ID))
				.isInstanceOf(TurnoNotAccessibleException.class);

		verify(eventos, never()).historial(anyLong(), anyLong());
	}

	private static Turno turnoFuturo() {
		Instant inicio = Instant.now().plus(Duration.ofDays(3));
		Turno turno = new Turno(ORG_ID, CONSULTORIO_ID, 42L, 128L, 31L, null,
				inicio, inicio.plus(Duration.ofMinutes(45)), 9L, Instant.now(), null, null);
		// El id lo asigna la base. Sin el, el servicio no puede preguntar por la atencion del
		// turno y el test fallaria por una razon que no es la que esta probando.
		ReflectionTestUtils.setField(turno, "id", TURNO_ID);
		return turno;
	}
}
