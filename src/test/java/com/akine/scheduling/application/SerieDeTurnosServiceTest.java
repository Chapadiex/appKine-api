package com.akine.scheduling.application;

import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.scheduling.domain.AgendaSede;
import com.akine.scheduling.domain.AlcanceDeSerie;
import com.akine.scheduling.domain.ReglaDeRecurrencia;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.TurnoSerie;
import com.akine.scheduling.domain.exception.OcurrenciaSinLugarException;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.AgendaSedeRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoEventoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoSerieRepositoryPort;
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

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Las dos reglas de E-3 que no dependen de la base: la confirmacion explicita y el todo o nada con
 * la ocurrencia nombrada. La concurrencia real vive en {@code SerieDeTurnosIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SerieDeTurnosService")
class SerieDeTurnosServiceTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long SERIE_ID = 12L;
	private static final String ZONA = "America/Argentina/Cordoba";

	@Mock private TurnoSerieRepositoryPort series;
	@Mock private TurnoRepositoryPort turnos;
	@Mock private TurnoEventoRepositoryPort eventos;
	@Mock private AgendaSedeRepositoryPort agendas;
	@Mock private OfertaDirectory ofertas;
	@Mock private PacienteDirectory pacientes;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AgendaSedeIniciador iniciador;
	@Mock private RevalidadorDeSlot revalidador;
	@Mock private AtencionProbe atenciones;
	@Mock private CicloDeTurnoService ciclo;
	@Mock private AuditTrail auditTrail;
	@Mock private AvisosDeTurno avisos;
	@Mock private RegistroDeRecepcion recepciones;

	private SerieDeTurnosService service;
	private final OperatingActor actor = new OperatingActor(9L, false, ORG_ID, CONSULTORIO_ID);
	private final ReglaDeRecurrencia tresLunes = new ReglaDeRecurrencia(
			Set.of(DayOfWeek.MONDAY), LocalTime.of(9, 0), LocalDate.now().plusDays(1), null, 3);

	@BeforeEach
	void prepararServicio() {
		service = new SerieDeTurnosService(series, turnos, eventos, agendas, ofertas, pacientes,
				consultorios, permissionGuard, iniciador, revalidador, atenciones, ciclo, auditTrail, avisos,
				recepciones);

		given(consultorios.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(CONSULTORIO_ID, ORG_ID, "Sede", ZONA, true)));
		given(agendas.lockByScope(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(mock(AgendaSede.class)));
		given(ofertas.find(ORG_ID, CONSULTORIO_ID, 42L)).willReturn(Optional.of(new OfertaSnapshot(
				42L, ORG_ID, CONSULTORIO_ID, 3L, "Kinesiologia", 60, 1, false, true, false, false, false,
				LocalDate.now().minusYears(1), null, true)));
		given(pacientes.find(ORG_ID, 128L)).willReturn(Optional.of(new PacienteSnapshot(
				128L, ORG_ID, "Sintetico", "Paciente", null, null, null, true, true, 5L, Instant.now())));
		given(series.save(any())).willAnswer(invocacion -> {
			TurnoSerie serie = invocacion.getArgument(0);
			ReflectionTestUtils.setField(serie, "id", SERIE_ID);
			return serie;
		});
		given(turnos.save(any())).willAnswer(invocacion -> {
			Turno turno = invocacion.getArgument(0);
			ReflectionTestUtils.setField(turno, "id", 300L);
			return turno;
		});
	}

	@Test
	@DisplayName("si la segunda ocurrencia no tiene lugar, el error la nombra y no se reserva la tercera")
	void todo_o_nada_nombra_la_ocurrencia() {
		given(revalidador.revalidar(any()))
				.willReturn(new RevalidadorDeSlot.Asignacion(31L, null))
				.willThrow(new RecursoOcupadoException("profesional"));

		AltaDeSerieCommand pedido = new AltaDeSerieCommand(42L, 128L, 31L, tresLunes, null);
		Instant segunda = tresLunes.ocurrencias().get(1)
				.atZone(java.time.ZoneId.of(ZONA)).toInstant();

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, pedido))
				.isInstanceOfSatisfying(OcurrenciaSinLugarException.class, error -> {
					assertThat(error.getOcurrenciaInicio()).isEqualTo(segunda);
					assertThat(error.getCausa()).isInstanceOf(RecursoOcupadoException.class);
				});
		// El rollback lo hace la transaccion; lo que el servicio garantiza es no seguir.
		verify(turnos, org.mockito.Mockito.times(1)).save(any());
	}

	@Test
	@DisplayName("una cantidad confirmada distinta de la real es 409 y no cancela ningun turno")
	void la_confirmacion_desactualizada_no_toca_nada() {
		TurnoSerie serie = mock(TurnoSerie.class);
		given(serie.getId()).willReturn(SERIE_ID);
		given(serie.getOrganizationId()).willReturn(ORG_ID);
		given(serie.getConsultorioId()).willReturn(CONSULTORIO_ID);
		given(series.findByIdInScope(ORG_ID, CONSULTORIO_ID, SERIE_ID)).willReturn(Optional.of(serie));
		given(turnos.findDeLaSerie(ORG_ID, SERIE_ID)).willReturn(List.of(
				futuro(301L, 3), futuro(302L, 10), futuro(303L, 17)));

		assertThatThrownBy(() -> service.cancelar(actor, CONSULTORIO_ID, SERIE_ID,
				OperacionDeSerieCommand.cancelacion(AlcanceDeSerie.TODA_LA_SERIE, null, "Baja", 2)))
				.isInstanceOf(OptimisticLockingFailureException.class)
				.hasMessageContaining("afecta 3");

		verify(ciclo, never()).aplicarCancelacion(any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("ESTE_Y_SIGUIENTES sin turno pivote es 400, antes de tomar ningun lock")
	void sin_pivote() {
		assertThatThrownBy(() -> OperacionDeSerieCommand.cancelacion(
				AlcanceDeSerie.ESTE_Y_SIGUIENTES, null, "Baja", 1))
				.isInstanceOf(IllegalArgumentException.class);
		verify(agendas, never()).lockByScope(anyLong(), anyLong());
	}

	private static Turno futuro(long id, int enDias) {
		Instant inicio = Instant.now().plus(Duration.ofDays(enDias));
		Turno turno = new Turno(ORG_ID, CONSULTORIO_ID, 42L, 128L, 31L, null, inicio,
				inicio.plus(Duration.ofHours(1)), 9L, Instant.now(), null, null);
		ReflectionTestUtils.setField(turno, "id", id);
		return turno;
	}
}
