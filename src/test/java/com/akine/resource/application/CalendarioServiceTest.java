package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.Feriado;
import com.akine.resource.domain.FranjaHorarioGeneral;
import com.akine.resource.domain.FranjaHorarioGeneral.Franja;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.BloqueDisponibilidadRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.DisponibilidadExcepcionRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.FeriadoRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.HorarioGeneralRepositoryPort;
import com.akine.resource.spi.DisponibilidadImpactProbe;
import com.akine.resource.spi.DisponibilidadImpactProbe.TurnoPendiente;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * La politica de calendario de la sede: como nace, como se edita y —lo que mas importa— que
 * efectivamente llegue al calculo.
 *
 * <h2>Por que el puerto de calendario es un doble EN MEMORIA y no un mock</h2>
 *
 * <p>Las dos preguntas de esta clase son sobre ESTADO que sobrevive entre llamadas: "la fila se
 * crea a demanda y la segunda vez se reusa" y "lo que guardo el PUT es lo que lee el calculo".
 * Un mock de Mockito no tiene estado: habria que stubear la respuesta de la segunda llamada a
 * mano, y entonces el test estaria afirmando su propio stub en vez de la persistencia. El doble
 * en memoria guarda de verdad, que es lo unico que hace que estas dos pruebas prueben algo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CalendarioServiceTest {

	private static final long ORG_ID = 10L;
	private static final long CONSULTORIO_ID = 20L;
	private static final long MEMBERSHIP_ID = 30L;
	private static final long ACCOUNT_ID = 40L;
	private static final long BLOQUE_ID = 501L;

	private static final String ZONA = "America/Argentina/Cordoba";

	/** 2026-07-09, Dia de la Independencia. Cae JUEVES. */
	private static final LocalDate FERIADO = LocalDate.of(2026, 7, 9);

	private static final int DIA_JUEVES = 4;

	@Mock
	private FeriadoRepositoryPort feriados;

	@Mock
	private ConsultorioDirectory consultorioDirectory;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private AuditTrail auditTrail;

	@Mock
	private BloqueDisponibilidadRepositoryPort bloques;

	@Mock
	private DisponibilidadExcepcionRepositoryPort excepciones;

	@Mock
	private ConsultorioMembershipDirectory membershipDirectory;

	@Mock
	private DisponibilidadImpactProbe sonda;

	private final CalendarioEnMemoria calendarios = new CalendarioEnMemoria();

	private final HorarioEnMemoria horarios = new HorarioEnMemoria();

	private CalendarioService service;
	private DisponibilidadEfectivaService efectiva;

	private final OperatingActor actor =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		// El iniciador real sobre el doble en memoria: su REQUIRES_NEW es un no-op sin proxy de
		// Spring, y lo que estos tests necesitan es que la fila quede creada de verdad.
		service = new CalendarioService(
				calendarios, new CalendarioSedeIniciador(calendarios), feriados,
				consultorioDirectory, permissionGuard, auditTrail, horarios,
				new SimuladorDeImpacto(bloques, excepciones, feriados, calendarios, sonda, horarios));
		efectiva = new DisponibilidadEfectivaService(
				bloques, excepciones, feriados, calendarios,
				consultorioDirectory, membershipDirectory, permissionGuard, horarios);

		given(consultorioDirectory.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(CONSULTORIO_ID, ORG_ID, "Sede Sintetica", ZONA, true)));
		given(feriados.findByPaisAndFechaBetween(anyString(), any(), any())).willReturn(List.of());
		given(bloques.findVigentesEn(anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(List.of());
		given(excepciones.findQueCubren(anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(List.of());
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID)).willReturn(Optional.of(
				new ConsultorioMembershipSnapshot(
						MEMBERSHIP_ID, ACCOUNT_ID, ORG_ID, CONSULTORIO_ID, "PROFESIONAL", "ACTIVA",
						Instant.parse("2020-01-01T00:00:00Z"), null, true, true)));
	}

	// =================================================================================
	// La fila se crea a demanda
	// =================================================================================

	@Test
	@DisplayName("La LECTURA no crea la fila: devuelve los valores por defecto y lo dice")
	void la_lectura_no_crea_la_fila() {
		CalendarioView vista = service.ver(actor, CONSULTORIO_ID, FERIADO, FERIADO.plusDays(1));

		assertThat(vista.existePersistida())
				.as("un GET que escribe no puede correr en una transaccion de solo lectura")
				.isFalse();
		assertThat(vista.cierraPorFeriado()).isTrue();
		assertThat(vista.pais()).isEqualTo(CalendarioSede.PAIS_POR_DEFECTO);
		assertThat(calendarios.filas()).isEmpty();
	}

	@Test
	@DisplayName("La primera edicion crea la fila con los valores por defecto, y la segunda la reusa")
	void la_edicion_crea_la_fila_a_demanda_y_la_segunda_la_reusa() {
		service.actualizar(actor, CONSULTORIO_ID, null, false);

		assertThat(calendarios.filas())
				.as("la fila nace en la primera edicion, no antes")
				.hasSize(1);
		assertThat(calendarios.filas().getFirst().getPais())
				.as("el pais no viajaba en el pedido: queda el default de V23")
				.isEqualTo(CalendarioSede.PAIS_POR_DEFECTO);

		service.actualizar(actor, CONSULTORIO_ID, null, true);

		assertThat(calendarios.filas())
				.as("una segunda fila para la misma sede violaria uk_consultorio_calendario_sede, "
						+ "y ademas partiria en dos el punto de serializacion de los writes")
				.hasSize(1);
		assertThat(calendarios.filas().getFirst().isCierraPorFeriado()).isTrue();
	}

	@Test
	@DisplayName("La edicion parcial deja intacto lo que no viajo, y audita solo lo que cambio")
	void la_edicion_es_parcial_y_audita_el_cambio() {
		service.actualizar(actor, CONSULTORIO_ID, "UY", false);

		CalendarioView vista = service.actualizar(actor, CONSULTORIO_ID, null, null);

		assertThat(vista.pais())
				.as("cierraPorFeriado nulo NO es false y pais nulo NO borra el pais")
				.isEqualTo("UY");
		assertThat(vista.cierraPorFeriado()).isFalse();

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail, org.mockito.Mockito.times(2)).record(auditoria.capture());
		assertThat(auditoria.getAllValues().getFirst().details())
				.containsEntry("cierraPorFeriado", "true -> false")
				.containsEntry("pais", "AR -> UY");
		assertThat(auditoria.getAllValues().getLast().details())
				.as("la segunda edicion no cambio nada: no hay detalle que declarar")
				.isEmpty();
	}

	// =================================================================================
	// La politica llega al calculo
	// =================================================================================

	@Test
	@DisplayName("Apagar cierraPorFeriado por el servicio deja de vaciar los feriados en la disponibilidad efectiva")
	void apagar_cierra_por_feriado_deja_de_vaciar_los_feriados() {
		darFeriado();
		darBloqueDelJueves();

		// Con la politica por defecto —sin fila todavia— el feriado cierra el dia.
		DisponibilidadEfectivaView conCierre = efectiva.efectiva(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, FERIADO, FERIADO.plusDays(1));
		assertThat(conCierre.dias().getFirst().franjas()).isEmpty();
		assertThat(conCierre.dias().getFirst().razonVacio()).isEqualTo("FERIADO");

		// El centro declara que atiende los feriados. Es el unico paso del test: nadie stubea la
		// politica a mano, la escribe el servicio que la pantalla usa.
		service.actualizar(actor, CONSULTORIO_ID, null, false);

		DisponibilidadEfectivaView sinCierre = efectiva.efectiva(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, FERIADO, FERIADO.plusDays(1));

		assertThat(sinCierre.dias().getFirst().franjas())
				.as("si la politica de la sede no estuviera cableada al calculo, este dia seguiria "
						+ "vacio y nada mas lo delataria")
				.hasSize(1);
		assertThat(sinCierre.dias().getFirst().razonVacio()).isNull();
		assertThat(sinCierre.dias().getFirst().esFeriado())
				.as("sigue siendo feriado: que el centro atienda es otro dato")
				.isTrue();
		assertThat(sinCierre.dias().getFirst().feriadoNombre()).isEqualTo("Dia de la Independencia");
	}

	@Test
	@DisplayName("El pais de la politica es el que se usa para buscar los feriados")
	void el_pais_de_la_politica_decide_que_feriados_se_buscan() {
		service.actualizar(actor, CONSULTORIO_ID, "UY", null);

		service.ver(actor, CONSULTORIO_ID, FERIADO, FERIADO.plusDays(1));

		// El extremo superior se resta un dia: findByPaisAndFechaBetween es INCLUSIVO en los dos
		// extremos y la ventana de la etapa es [desde, hasta).
		verify(feriados).findByPaisAndFechaBetween("UY", FERIADO, FERIADO);
	}

	// =================================================================================
	// Horario general (A-8, RF-M03-002 / RF-M03-003)
	// =================================================================================

	@Test
	@DisplayName("El horario de una sede nueva se guarda ordenado, se lee con el calendario y se audita")
	void el_horario_de_la_sede_nueva_se_guarda_y_se_lee() {
		service.fijarHorarioDeSedeNueva(ORG_ID, CONSULTORIO_ID, ACCOUNT_ID, List.of(
				franja(2, 9, 13),
				new Franja(1, LocalTime.of(14, 0), LocalTime.MAX),
				franja(1, 9, 13)));

		CalendarioView vista = service.ver(actor, CONSULTORIO_ID, FERIADO, FERIADO.plusDays(1));

		assertThat(vista.horarioGeneral()).containsExactly(
				franja(1, 9, 13), new Franja(1, LocalTime.of(14, 0), LocalTime.MAX), franja(2, 9, 13));
		assertThat(vista.existePersistida())
				.as("el horario no necesita la fila de politica: la sede nueva no la tiene")
				.isFalse();

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().eventType()).isEqualTo(AuditEvents.HORARIO_GENERAL_UPDATED);
		assertThat(auditoria.getValue().details().get("horarioGeneral"))
				.isEqualTo("[] -> [1 09:00-13:00, 1 14:00-24:00, 2 09:00-13:00]");
	}

	@Test
	@DisplayName("Dos franjas del mismo dia que se pisan se rechazan sin guardar nada; contiguas valen")
	void el_horario_que_se_solapa_se_rechaza() {
		assertThatThrownBy(() -> service.fijarHorarioDeSedeNueva(ORG_ID, CONSULTORIO_ID, ACCOUNT_ID,
				List.of(franja(1, 9, 13), franja(1, 12, 18))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("se solapan");
		assertThat(horarios.filas()).isEmpty();

		service.fijarHorarioDeSedeNueva(ORG_ID, CONSULTORIO_ID, ACCOUNT_ID,
				List.of(franja(1, 9, 13), franja(1, 13, 18)));
		assertThat(horarios.filas()).hasSize(2);
	}

	@Test
	@DisplayName("Una franja que termina antes de empezar no se puede construir")
	void la_franja_invertida_no_existe() {
		assertThatThrownBy(() -> franja(3, 18, 9)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("Editar el horario lo reemplaza dando de baja lo anterior; omitirlo no lo toca")
	void editar_el_horario_reemplaza_y_conserva_la_historia() {
		service.fijarHorarioDeSedeNueva(ORG_ID, CONSULTORIO_ID, ACCOUNT_ID, List.of(franja(1, 9, 13)));

		CalendarioView editada = service.actualizar(
				actor, CONSULTORIO_ID, null, null, List.of(franja(5, 8, 12)));
		assertThat(editada.horarioGeneral()).containsExactly(franja(5, 8, 12));
		assertThat(horarios.filas())
				.as("la franja reemplazada queda como historia, dada de baja logica")
				.hasSize(2)
				.anySatisfy(f -> {
					assertThat(f.isActive()).isFalse();
					assertThat(f.getDeletedAt()).isNotNull();
				});

		CalendarioView sinTocar = service.actualizar(actor, CONSULTORIO_ID, null, false);
		assertThat(sinTocar.horarioGeneral())
				.as("horarioGeneral omitido deja el horario como estaba")
				.containsExactly(franja(5, 8, 12));

		CalendarioView vaciada = service.actualizar(actor, CONSULTORIO_ID, null, null, List.of());
		assertThat(vaciada.horarioGeneral()).as("una lista vacia lo borra").isEmpty();
	}

	// =================================================================================
	// El horario de la sede limita la agenda (A-8b, DP-19)
	// =================================================================================

	@Test
	@DisplayName("Con horario de sede cargado, la disponibilidad efectiva se recorta a el; sin horario no cambia")
	void el_horario_de_la_sede_recorta_la_disponibilidad_efectiva() {
		darBloqueDelJueves();
		LocalDate jueves = FERIADO.plusWeeks(1);

		DisponibilidadEfectivaView sinHorario = efectiva.efectiva(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, jueves, jueves.plusDays(1));
		assertThat(sinHorario.dias().getFirst().franjas())
				.as("una sede sin horario cargado no limita nada")
				.singleElement()
				.satisfies(f -> assertThat(f.recortadoPor()).isNull());

		service.actualizar(actor, CONSULTORIO_ID, null, null, List.of(franja(DIA_JUEVES, 10, 12)));
		DisponibilidadEfectivaView recortada = efectiva.efectiva(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, jueves, jueves.plusDays(1));
		assertThat(recortada.dias().getFirst().franjas()).singleElement().satisfies(f -> {
			assertThat(f.desde()).isEqualTo(jueves.atTime(10, 0).atZone(ZoneId.of(ZONA)).toInstant());
			assertThat(f.hasta()).isEqualTo(jueves.atTime(12, 0).atZone(ZoneId.of(ZONA)).toInstant());
			assertThat(f.recortadoPor()).isEqualTo("HORARIO_SEDE");
		});

		service.actualizar(actor, CONSULTORIO_ID, null, null, List.of(franja(1, 9, 18)));
		DisponibilidadEfectivaView sinElJueves = efectiva.efectiva(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, jueves, jueves.plusDays(1));
		assertThat(sinElJueves.dias().getFirst().franjas()).isEmpty();
		assertThat(sinElJueves.dias().getFirst().razonVacio())
				.as("el profesional tiene horario: lo que hay que corregir es el de la sede")
				.isEqualTo("HORARIO_SEDE");
	}

	@Test
	@DisplayName("El PUT del horario informa los turnos que quedan fuera y no cuenta los que siguen cubiertos")
	void el_put_del_horario_informa_los_turnos_que_quedan_afuera() {
		darBloqueDelJueves();
		ZoneId zona = ZoneId.of(ZONA);
		LocalDate jueves = LocalDate.now(zona).plusDays(1)
				.with(TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY));
		TurnoPendiente temprano = new TurnoPendiente(71L, MEMBERSHIP_ID,
				jueves.atTime(9, 0).atZone(zona).toInstant(),
				jueves.atTime(10, 0).atZone(zona).toInstant());
		TurnoPendiente cubierto = new TurnoPendiente(72L, MEMBERSHIP_ID,
				jueves.atTime(11, 0).atZone(zona).toInstant(),
				jueves.atTime(12, 0).atZone(zona).toInstant());
		given(sonda.pendientesEn(eq(ORG_ID), eq(CONSULTORIO_ID), isNull(), any(), any()))
				.willReturn(List.of(temprano, cubierto));

		CalendarioView vista = service.actualizar(
				actor, CONSULTORIO_ID, null, null, List.of(franja(DIA_JUEVES, 10, 18)));

		assertThat(vista.impactoDelHorario().turnosAfectados()).isEqualTo(1L);
		assertThat(vista.impactoDelHorario().turnos())
				.singleElement()
				.satisfies(t -> assertThat(t.turnoId()).isEqualTo(71L));
		assertThat(vista.horarioGeneral())
				.as("el impacto se informa y no bloquea: el horario se reemplaza igual")
				.containsExactly(franja(DIA_JUEVES, 10, 18));

		CalendarioView sinCambio = service.actualizar(
				actor, CONSULTORIO_ID, null, null, List.of(franja(DIA_JUEVES, 10, 18)));
		assertThat(sinCambio.impactoDelHorario().turnosAfectados())
				.as("un PUT que no cambia el horario no informa nada")
				.isZero();
		assertThat(sinCambio.impactoDelHorario().evaluadoHasta()).isNull();
	}

	// =================================================================================
	// Auxiliares
	// =================================================================================

	private static Franja franja(int dia, int desde, int hasta) {
		return new Franja(dia, LocalTime.of(desde, 0), LocalTime.of(hasta, 0));
	}

	private void darFeriado() {
		Feriado feriado = new Feriado("AR", FERIADO, "Dia de la Independencia", "INAMOVIBLE");
		ReflectionTestUtils.setField(feriado, "id", 900L);
		given(feriados.findByPaisAndFechaBetween(anyString(), any(), any()))
				.willReturn(List.of(feriado));
	}

	private void darBloqueDelJueves() {
		BloqueDisponibilidad bloque = new BloqueDisponibilidad(
				ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID, DIA_JUEVES,
				LocalTime.of(9, 0), LocalTime.of(13, 0), LocalDate.of(2020, 1, 1), null);
		ReflectionTestUtils.setField(bloque, "id", BLOQUE_ID);
		given(bloques.findVigentesEn(anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(bloque));
	}

	/**
	 * Doble en memoria del puerto de calendario. Guarda de verdad, que es justamente lo que un
	 * mock no hace y lo que estos tests necesitan.
	 *
	 * <p>{@code lockByScope} devuelve lo mismo que {@code findByScope}: el {@code FOR UPDATE} es
	 * una garantia del motor y no se puede simular en memoria. Que el lock se PIDA antes de la
	 * primera lectura es lo unico verificable sin base, y eso ya lo fija el test del servicio
	 * hermano con {@code InOrder}.
	 */
	private static final class CalendarioEnMemoria implements CalendarioSedeRepositoryPort {

		private final List<CalendarioSede> filas = new ArrayList<>();
		private final AtomicLong secuencia = new AtomicLong(800L);

		@Override
		public Optional<CalendarioSede> findByScope(Long organizationId, Long consultorioId) {
			return filas.stream()
					.filter(fila -> fila.getOrganizationId().equals(organizationId)
							&& fila.getConsultorioId().equals(consultorioId))
					.findFirst();
		}

		@Override
		public Optional<CalendarioSede> lockByScope(Long organizationId, Long consultorioId) {
			return findByScope(organizationId, consultorioId);
		}

		@Override
		public CalendarioSede save(CalendarioSede calendario) {
			if (calendario.getId() == null) {
				ReflectionTestUtils.setField(calendario, "id", secuencia.incrementAndGet());
				filas.add(calendario);
			}
			return calendario;
		}

		/** Equivalente en memoria del {@code INSERT ... ON DUPLICATE KEY UPDATE}: no lanza nunca. */
		@Override
		public void crearSiFalta(long organizationId, long consultorioId) {
			if (findByScope(organizationId, consultorioId).isEmpty()) {
				save(new CalendarioSede(organizationId, consultorioId));
			}
		}

		List<CalendarioSede> filas() {
			return List.copyOf(filas);
		}
	}

	/** Doble en memoria del horario general: guarda de verdad, igual que el de calendario. */
	private static final class HorarioEnMemoria implements HorarioGeneralRepositoryPort {

		private final List<FranjaHorarioGeneral> filas = new ArrayList<>();
		private final AtomicLong secuencia = new AtomicLong(700L);

		@Override
		public List<FranjaHorarioGeneral> findVigentes(Long organizationId, Long consultorioId) {
			return filas.stream()
					.filter(f -> f.isActive()
							&& f.getOrganizationId().equals(organizationId)
							&& f.getConsultorioId().equals(consultorioId))
					.sorted(java.util.Comparator
							.comparingInt((FranjaHorarioGeneral f) -> f.franja().diaSemana())
							.thenComparing(f -> f.franja().horaDesde()))
					.toList();
		}

		@Override
		public FranjaHorarioGeneral save(FranjaHorarioGeneral franja) {
			if (franja.getId() == null) {
				ReflectionTestUtils.setField(franja, "id", secuencia.incrementAndGet());
				filas.add(franja);
			}
			return franja;
		}

		List<FranjaHorarioGeneral> filas() {
			return List.copyOf(filas);
		}
	}
}
