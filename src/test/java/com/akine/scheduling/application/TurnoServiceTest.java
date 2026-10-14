package com.akine.scheduling.application;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.CoberturasAplicablesDirectory;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.scheduling.domain.AgendaSede;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.CoberturaNoAccesibleException;
import com.akine.scheduling.domain.exception.PersonaNotAccessibleException;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.AgendaSedeRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoEventoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
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
	@Mock private CoberturasAplicablesDirectory coberturas;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AgendaSedeIniciador iniciador;
	@Mock private RevalidadorDeSlot revalidador;
	@Mock private AvisosDeTurno avisos;

	private TurnoService service;
	private final OperatingActor actor = new OperatingActor(9L, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void prepararServicio() {
		// Un estado real y no null: la reserva puede marcar su transaccion para revertir.
		PlatformTransactionManager transacciones = mock(PlatformTransactionManager.class);
		given(transacciones.getTransaction(any())).willReturn(new SimpleTransactionStatus());
		service = new TurnoService(turnos, eventos, agendas, ofertas, pacientes, coberturas, consultorios,
				permissionGuard, iniciador, revalidador, avisos, transacciones);

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

	@Test
	@DisplayName("05.02: una cobertura elegida que no es de la persona o no esta vigente ese dia es 404 y no reserva")
	void cobertura_elegida_ajena_no_reserva() {
		Instant manana = Instant.now().plus(Duration.ofDays(1));
		given(coberturas.vigenteDeLaPersona(eq(ORG_ID), eq(128L), eq(412L), any()))
				.willReturn(false);

		assertThatThrownBy(() -> service.reservar(actor, CONSULTORIO_ID, 42L,
				new ReservaCommand(128L, manana, 31L, null, 412L)))
				.isInstanceOf(CoberturaNoAccesibleException.class);

		verify(turnos, never()).save(any());
	}

	@Test
	@DisplayName("05.02: la cobertura elegida y vigente queda en el turno; sin elegir no se consulta")
	void cobertura_elegida_queda_en_el_turno() {
		Instant manana = Instant.now().plus(Duration.ofDays(1));
		given(coberturas.vigenteDeLaPersona(eq(ORG_ID), eq(128L), eq(412L), any()))
				.willReturn(true);
		given(revalidador.revalidarBajoLock(any(), any())).willReturn(new RevalidadorDeSlot.Asignacion(31L, null));
		given(turnos.save(any(Turno.class))).willAnswer(inv -> {
			Turno t = inv.getArgument(0);
			org.springframework.test.util.ReflectionTestUtils.setField(t, "id", 900L);
			return t;
		});

		var conCobertura = service.reservar(actor, CONSULTORIO_ID, 42L,
				new ReservaCommand(128L, manana, 31L, null, 412L));
		var sinCobertura = service.reservar(actor, CONSULTORIO_ID, 42L,
				new ReservaCommand(128L, manana, 31L, null));

		assertThat(conCobertura.turno().coberturaId()).isEqualTo(412L);
		assertThat(sinCobertura.turno().coberturaId()).isNull();
		verify(coberturas).vigenteDeLaPersona(anyLong(), anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("carga §10: lo que no lee turnos va ANTES del lock de la agenda; cupo y solapamiento, despues")
	void lo_que_no_lee_turnos_va_antes_del_lock() {
		Instant manana = Instant.now().plus(Duration.ofDays(1));
		given(revalidador.prevalidar(any())).willReturn(new RevalidadorDeSlot.Previa(31L));
		given(revalidador.revalidarBajoLock(any(), any())).willReturn(new RevalidadorDeSlot.Asignacion(31L, null));
		given(turnos.save(any(Turno.class))).willAnswer(inv -> {
			Turno t = inv.getArgument(0);
			org.springframework.test.util.ReflectionTestUtils.setField(t, "id", 900L);
			return t;
		});

		service.reservar(actor, CONSULTORIO_ID, 42L, new ReservaCommand(128L, manana, 31L, null));

		// El lock serializa todas las reservas de la sede: lo que corre despues de tomarlo corre con
		// las demas esperando. Oferta, paciente, habilitacion y franja no leen turnos y nadie que
		// los cambie toma este lock, asi que van antes.
		InOrder orden = inOrder(ofertas, pacientes, revalidador, avisos, agendas, turnos);
		orden.verify(ofertas).find(ORG_ID, CONSULTORIO_ID, 42L);
		orden.verify(pacientes).find(ORG_ID, 128L);
		orden.verify(revalidador).prevalidar(any());
		orden.verify(avisos).contactoDe(ORG_ID, 128L);
		orden.verify(agendas).lockByScope(ORG_ID, CONSULTORIO_ID);
		orden.verify(revalidador).revalidarBajoLock(any(), eq(new RevalidadorDeSlot.Previa(31L)));
		orden.verify(turnos).save(any(Turno.class));
	}

	@Test
	@DisplayName("carga §10: un rechazo leido antes del lock sale DESPUES de la idempotencia: el reintento sigue devolviendo su turno")
	void el_rechazo_previo_no_tapa_el_reintento() {
		Instant manana = Instant.now().plus(Duration.ofDays(1));
		ReservaCommand command = new ReservaCommand(128L, manana, 31L, "clave-1");
		Turno existente = new Turno(ORG_ID, CONSULTORIO_ID, 42L, 128L, 31L, null,
				manana, manana.plus(Duration.ofHours(1)), 9L, Instant.now(),
				"clave-1", command.huella(CONSULTORIO_ID, 42L));
		org.springframework.test.util.ReflectionTestUtils.setField(existente, "id", 900L);
		given(turnos.findByIdempotencyKey(ORG_ID, "clave-1")).willReturn(Optional.of(existente));
		// Desde el primer intento la persona dejo de ser paciente: bajo el lock, antes, la
		// idempotencia salia primero y devolvia el turno. Tiene que seguir siendo asi.
		given(pacientes.find(ORG_ID, 128L)).willReturn(Optional.empty());

		ResultadoDeReserva reintento = service.reservar(actor, CONSULTORIO_ID, 42L, command);

		assertThat(reintento.creado()).isFalse();
		assertThat(reintento.turno().id()).isEqualTo(900L);
		verify(turnos, never()).save(any());
	}

	@Test
	@DisplayName("carga §10: sin clave repetida, el rechazo leido antes del lock se lanza bajo el lock")
	void el_rechazo_previo_se_lanza_bajo_el_lock() {
		Instant manana = Instant.now().plus(Duration.ofDays(1));
		given(pacientes.find(ORG_ID, 128L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.reservar(actor, CONSULTORIO_ID, 42L,
				new ReservaCommand(128L, manana, 31L, null)))
				.isInstanceOf(PersonaNotAccessibleException.class);

		verify(agendas).lockByScope(ORG_ID, CONSULTORIO_ID);
		verify(revalidador, never()).revalidarBajoLock(any(), any());
		verify(turnos, never()).save(any());
	}

	@Test
	@DisplayName("05.02: sin cobertura la huella es la de antes; con cobertura cambia")
	void la_huella_sin_cobertura_no_cambia() {
		Instant inicio = Instant.parse("2026-11-02T12:00:00Z");
		String sin = new ReservaCommand(128L, inicio, 31L, "k").huella(CONSULTORIO_ID, 42L);
		String con = new ReservaCommand(128L, inicio, 31L, "k", 412L).huella(CONSULTORIO_ID, 42L);

		// SHA-256 de "7|42|128|2026-11-02T12:00:00Z|31", el formato anterior a 05.02.
		assertThat(sin).isEqualTo(sha256("7|42|128|2026-11-02T12:00:00Z|31"));
		assertThat(con).isNotEqualTo(sin);
	}

	private static String sha256(String texto) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(texto.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
