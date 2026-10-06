package com.akine.scheduling.application;

import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.scheduling.domain.AgendaSede;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.AgendaSedeRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoEventoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Defecto encontrado en E-3: <b>se podia reservar un turno en el pasado</b>.
 *
 * <p>Reprogramar ya rechazaba un destino pasado y {@code TurnoCicloConcurrenteIT} daba por hecho
 * que "la reserva exige futuro", pero la reserva no lo controlaba: el motor de 05.01 tambien
 * dibuja los slots de hoy que ya pasaron, y el revalidador solo mira que el hueco exista. El turno
 * nacia inalterable (DP-04: no se cancela ni se mueve lo que ya empezo), asi que un error de fecha
 * solo podia cerrarse marcando AUSENTE a un paciente que nunca falto.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TurnoService")
class TurnoServiceTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;

	@Mock private TurnoRepositoryPort turnos;
	@Mock private TurnoEventoRepositoryPort eventos;
	@Mock private AgendaSedeRepositoryPort agendas;
	@Mock private OfertaDirectory ofertas;
	@Mock private PacienteDirectory pacientes;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AgendaSedeIniciador iniciador;
	@Mock private RevalidadorDeSlot revalidador;
	@Mock private AvisosDeTurno avisos;

	private TurnoService service;
	private final OperatingActor actor = new OperatingActor(9L, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void prepararServicio() {
		service = new TurnoService(turnos, eventos, agendas, ofertas, pacientes, consultorios,
				permissionGuard, iniciador, revalidador, avisos);

		given(consultorios.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(CONSULTORIO_ID, ORG_ID, "Sede", "America/Argentina/Cordoba", true)));
		given(agendas.lockByScope(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(mock(AgendaSede.class)));
		given(ofertas.find(ORG_ID, CONSULTORIO_ID, 42L)).willReturn(Optional.of(new OfertaSnapshot(
				42L, ORG_ID, CONSULTORIO_ID, 3L, "Kinesiologia", 60, 1, false, true, false, false, false,
				LocalDate.now().minusYears(1), null, true)));
		given(pacientes.find(ORG_ID, 128L)).willReturn(Optional.of(new PacienteSnapshot(
				128L, ORG_ID, "Sintetico", "Paciente", null, null, null, true, true, 5L, Instant.now())));
	}

	@Test
	@DisplayName("un horario que ya paso no se reserva: 409 slot-no-disponible y ninguna fila")
	void no_se_reserva_en_el_pasado() {
		Instant haceUnaHora = Instant.now().minus(Duration.ofHours(1));

		assertThatThrownBy(() -> service.reservar(actor, CONSULTORIO_ID, 42L,
				new ReservaCommand(128L, haceUnaHora, 31L, null)))
				.isInstanceOf(SlotNoDisponibleException.class)
				.hasMessageContaining("ya paso");

		verify(turnos, never()).save(any());
	}
}
