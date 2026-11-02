package com.akine.scheduling.application;

import com.akine.platform.spi.audit.AuditoriaAparte;
import org.springframework.transaction.PlatformTransactionManager;
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
import com.akine.scheduling.domain.EstadoDeSerie;
import com.akine.scheduling.domain.OrigenDeTurno;
import com.akine.scheduling.domain.ReglaDeRecurrencia;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.TurnoSerie;
import com.akine.clinical.spi.ContextoClinicoDeAgenda;
import com.akine.platform.spi.listado.OrdenDeListado;
import com.akine.scheduling.domain.exception.OcurrenciaSinLugarException;
import com.akine.scheduling.domain.exception.OfertaNoAgendableException;
import com.akine.scheduling.domain.exception.PersonaNotAccessibleException;
import com.akine.scheduling.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.scheduling.domain.exception.PlanSinSesionesException;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.TransicionDeTurnoNoPermitidaException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.AgendaSedeRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.OcurrenciaOmitidaRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoEventoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoSerieRepositoryPort;
import com.akine.scheduling.spi.AtencionProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import com.akine.scheduling.domain.exception.CantidadConfirmadaDesactualizadaException;
import org.springframework.security.access.AccessDeniedException;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
	@Mock private OcurrenciaOmitidaRepositoryPort omisiones;
	@Mock private com.akine.organization.spi.ConsultorioMembershipDirectory membresias;
	@Mock private com.akine.platform.spi.identity.AccountIdentityDirectory identidades;
	@Mock private ContextoClinicoDeAgenda contextoDeAgenda;
	@Mock private SesionesDelPlan sesionesDelPlan;

	private SerieDeTurnosService service;
	private final OperatingActor actor = new OperatingActor(9L, false, ORG_ID, CONSULTORIO_ID);
	private final ReglaDeRecurrencia tresLunes = new ReglaDeRecurrencia(
			Set.of(DayOfWeek.MONDAY), LocalTime.of(9, 0), LocalDate.now().plusDays(1), null, 3);

	@BeforeEach
	void prepararServicio() {
		service = new SerieDeTurnosService(series, turnos, eventos, agendas, ofertas, pacientes,
				consultorios, new SoporteEnAgenda(permissionGuard, mock(AuditoriaAparte.class)), iniciador, revalidador, atenciones, ciclo, auditTrail, avisos,
				recepciones, omisiones, mock(PlatformTransactionManager.class),
				membresias, identidades,
				new ContextoClinicoDeTurno(contextoDeAgenda),
				sesionesDelPlan);

		given(revalidador.feriadosQueCierran(anyLong(), any(), any(), any(), any(), any())).willReturn(Set.of());
		given(revalidador.revalidar(any())).willReturn(new RevalidadorDeSlot.Asignacion(31L, null));
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
	@DisplayName("U-22: la ocurrencia que cae en feriado se omite y se registra; las demas se reservan")
	void el_feriado_se_omite() {
		java.time.ZoneId zona = java.time.ZoneId.of(ZONA);
		LocalDate segundoLunes = tresLunes.ocurrencias().get(1).toLocalDate();
		given(revalidador.feriadosQueCierran(eq(ORG_ID), any(), any(), eq(31L), any(), any()))
				.willReturn(Set.of(segundoLunes));
		given(revalidador.revalidar(any())).willReturn(new RevalidadorDeSlot.Asignacion(31L, null));
		given(omisiones.registrar(any())).willAnswer(invocacion -> invocacion.getArgument(0));

		SerieView serie = service.crear(actor, CONSULTORIO_ID,
				new AltaDeSerieCommand(42L, 128L, 31L, tresLunes, null)).serie();

		verify(turnos, org.mockito.Mockito.times(2)).save(any());
		verify(revalidador, org.mockito.Mockito.times(2)).revalidar(any());
		assertThat(serie.turnos()).hasSize(2);
		assertThat(serie.omitidas()).singleElement().satisfies(omitida -> {
			assertThat(omitida.fecha()).isEqualTo(segundoLunes);
			assertThat(omitida.inicio()).isEqualTo(tresLunes.ocurrencias().get(1).atZone(zona).toInstant());
			assertThat(omitida.motivo()).isEqualTo("FERIADO");
		});
		verify(series).save(argThat(guardada -> guardada.getCantidadGenerada() == 2));
	}

	@Test
	@DisplayName("U-22: si todas caen en feriado no se crea nada y el 409 nombra la primera")
	void todas_en_feriado() {
		given(revalidador.feriadosQueCierran(eq(ORG_ID), any(), any(), eq(31L), any(), any()))
				.willReturn(tresLunes.ocurrencias().stream()
						.map(java.time.LocalDateTime::toLocalDate)
						.collect(java.util.stream.Collectors.toSet()));

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID,
				new AltaDeSerieCommand(42L, 128L, 31L, tresLunes, null)))
				.isInstanceOfSatisfying(OcurrenciaSinLugarException.class, error -> assertThat(error.getCausa())
						.isInstanceOf(com.akine.scheduling.domain.exception.SlotNoDisponibleException.class));
		verify(series, never()).save(any());
		verify(turnos, never()).save(any());
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
				.isInstanceOf(CantidadConfirmadaDesactualizadaException.class)
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

	@Test
	@DisplayName("bandeja (E-8): una consulta de turnos y una de pacientes por pagina, y la oferta una vez por oferta")
	void bandeja_sin_n_mas_uno() {
		TurnoSerie primera = serieConId(20L);
		TurnoSerie segunda = serieConId(21L);
		given(series.contar(eq(ORG_ID), eq(CONSULTORIO_ID),
				isNull(), isNull(), any()))
				.willReturn(2L);
		given(series.listar(eq(ORG_ID), eq(CONSULTORIO_ID),
				isNull(), isNull(), any(), eq(java.util.Optional.empty()),
				eq(0), eq(20)))
				.willReturn(List.of(segunda, primera));
		Turno deLaPrimera = futuro(301L, 3);
		ReflectionTestUtils.setField(deLaPrimera, "serieId", 20L);
		given(turnos.findDeLasSeries(ORG_ID, List.of(21L, 20L))).willReturn(List.of(deLaPrimera));
		given(pacientes.findAll(ORG_ID, List.of(128L))).willReturn(java.util.Map.of(128L, new PacienteSnapshot(
				128L, ORG_ID, "Sintetico", "Paciente", null, null, null, true, true, 5L, Instant.now())));

		SeriePagina pagina = service.listar(actor, CONSULTORIO_ID, null, null, 0, 20);

		assertThat(pagina.total()).isEqualTo(2);
		assertThat(pagina.contenido()).extracting(SerieResumenView::id).containsExactly(21L, 20L);
		assertThat(pagina.contenido()).extracting(SerieResumenView::estado)
				.containsExactly(EstadoDeSerie.FINALIZADA, EstadoDeSerie.VIGENTE);
		assertThat(pagina.contenido().get(1).ofertaNombre()).isEqualTo("Kinesiologia");
		verify(ofertas, org.mockito.Mockito.times(1)).find(ORG_ID, CONSULTORIO_ID, 42L);
		verify(permissionGuard).requirePermission(argThat(
				consulta -> consulta.permissionCode().equals("turno:read")));
	}

	@Test
	@DisplayName("bandeja (DP-45): q busca en paciente, oferta y profesional, en lote, y corta la pagina en memoria")
	void bandeja_con_q() {
		TurnoSerie dePaula = serieConId(20L);
		TurnoSerie deOtro = serieConId(21L);
		ReflectionTestUtils.setField(deOtro, "personaId", 129L);
		ReflectionTestUtils.setField(deOtro, "profesionalMembershipId", 32L);
		given(series.todas(eq(ORG_ID), eq(CONSULTORIO_ID), isNull(), isNull(), any()))
				.willReturn(List.of(deOtro, dePaula));
		given(pacientes.findAll(eq(ORG_ID), any())).willReturn(java.util.Map.of(
				128L, new PacienteSnapshot(128L, ORG_ID, "Pérez", "Paula", "DNI", "30111222", null,
						true, true, 5L, Instant.now()),
				129L, new PacienteSnapshot(129L, ORG_ID, "Gomez", "Gaston", "DNI", "30999888", null,
						true, true, 6L, Instant.now())));
		OfertaSnapshot kinesiologia = ofertas.find(ORG_ID, CONSULTORIO_ID, 42L).orElseThrow();
		given(ofertas.findTodas(eq(ORG_ID), eq(CONSULTORIO_ID), any()))
				.willReturn(java.util.Map.of(42L, kinesiologia));
		given(membresias.findTodas(eq(ORG_ID), any())).willReturn(java.util.Map.of(
				31L, new com.akine.organization.spi.ConsultorioMembershipSnapshot(31L, 501L, ORG_ID,
						CONSULTORIO_ID, "PROFESIONAL", "ACTIVA", Instant.EPOCH, null, true, true),
				32L, new com.akine.organization.spi.ConsultorioMembershipSnapshot(32L, 502L, ORG_ID,
						CONSULTORIO_ID, "PROFESIONAL", "ACTIVA", Instant.EPOCH, null, true, true)));
		given(identidades.identidadesDe(any())).willReturn(java.util.Map.of(
				501L, new com.akine.platform.spi.identity.AccountIdentity(501L, "Lic. Ana Ruiz", "a@x.test"),
				502L, new com.akine.platform.spi.identity.AccountIdentity(502L, "Lic. Beto Sosa", "b@x.test")));

		assertThat(service.listar(actor, CONSULTORIO_ID, null, null, "PEREZ", java.util.Optional.empty(),
				0, 20).contenido()).as("apellido sin acento ni mayusculas")
				.extracting(SerieResumenView::id).containsExactly(20L);
		assertThat(service.listar(actor, CONSULTORIO_ID, null, null, "30999", java.util.Optional.empty(),
				0, 20).contenido()).as("documento")
				.extracting(SerieResumenView::id).containsExactly(21L);
		assertThat(service.listar(actor, CONSULTORIO_ID, null, null, "sosa", java.util.Optional.empty(),
				0, 20).contenido()).as("profesional")
				.extracting(SerieResumenView::id).containsExactly(21L);
		SeriePagina porOferta = service.listar(actor, CONSULTORIO_ID, null, null, "kinesio",
				com.akine.platform.spi.listado.OrdenDeListado.leer("creadaEn,asc", "creadaEn"), 0, 1);
		assertThat(porOferta.total()).as("el total es de lo que coincide, no de la pagina").isEqualTo(2);
		assertThat(porOferta.contenido()).extracting(SerieResumenView::id)
				.as("la mas vieja primero (o el id si comparten instante)").containsExactly(20L);
		verify(series, never()).contar(anyLong(), anyLong(), any(), any(), any());
		verify(pacientes, org.mockito.Mockito.atMost(8)).findAll(anyLong(), any());
	}

	@Test
	@DisplayName("bandeja (E-8): una pagina mas alla del total no consulta series ni turnos")
	void bandeja_pagina_fuera_de_rango() {
		given(series.contar(eq(ORG_ID), eq(CONSULTORIO_ID),
				any(), any(), any())).willReturn(3L);

		SeriePagina pagina = service.listar(actor, CONSULTORIO_ID, 128L, EstadoDeSerie.VIGENTE, 1, 20);

		assertThat(pagina.contenido()).isEmpty();
		assertThat(pagina.total()).isEqualTo(3);
		verify(series, never()).listar(anyLong(), anyLong(), any(), any(), any(), anyInt(),
				anyInt());
		verify(turnos, never()).findDeLasSeries(anyLong(), any());
	}

	@Test
	@DisplayName("resumen (E-8): pendientes son los RESERVADO o CONFIRMADO futuros; el proximo es el mas temprano de ellos")
	void resumen_cuenta_pendientes() {
		Turno pasado = futuro(300L, -7);
		Turno cancelado = futuro(301L, 2);
		cancelado.cancelar("Viaje", 9L, Instant.now());
		Turno ausente = futuro(302L, 3);
		ReflectionTestUtils.setField(ausente, "estado", com.akine.scheduling.domain.EstadoTurno.AUSENTE);
		Turno proximo = futuro(303L, 9);
		Turno despues = futuro(304L, 16);
		despues.confirmar(Instant.now());

		SerieResumenView fila = SerieDeTurnosService.resumen(serieConId(20L),
				List.of(pasado, cancelado, ausente, proximo, despues), null, "Kinesiologia", Instant.now());

		assertThat(fila.totalTurnos()).isEqualTo(5);
		assertThat(fila.turnosPendientes()).isEqualTo(2);
		assertThat(fila.proximoTurnoInicio()).isEqualTo(proximo.getInicio());
		assertThat(fila.estado()).isEqualTo(EstadoDeSerie.VIGENTE);
		assertThat(fila.personaNombre()).isEqualTo("(ficha no disponible)");
		assertThat(fila.diasSemana()).containsExactly(1);

		// DP-20: sin pendientes y con el ultimo turno cancelado, la serie se corto.
		SerieResumenView cortada = SerieDeTurnosService.resumen(serieConId(20L),
				List.of(pasado, cancelado), null, "Kinesiologia", Instant.now());
		assertThat(cortada.estado()).isEqualTo(EstadoDeSerie.CANCELADA);
		assertThat(cortada.proximoTurnoInicio()).isNull();

		SerieResumenView terminada = SerieDeTurnosService.resumen(serieConId(20L),
				List.of(cancelado, ausente), null, "Kinesiologia", Instant.now());
		assertThat(terminada.estado()).isEqualTo(EstadoDeSerie.FINALIZADA);
	}

	// =================================================================================
	// Alta: lo que se revisa antes de crear (flujo unico, §4 y §7)
	// =================================================================================

	@Test
	@DisplayName("la misma clave con otra serie es 409, y no crea ni devuelve nada")
	void clave_con_otra_serie() {
		TurnoSerie existente = serieConId(SERIE_ID);
		ReflectionTestUtils.setField(existente, "requestHash", "otra-huella");
		given(series.findByIdempotencyKey(ORG_ID, "clave-1")).willReturn(Optional.of(existente));

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, new AltaDeSerieCommand(42L, 128L, 31L, tresLunes, "clave-1")))
				.isInstanceOf(IdempotencyKeyConflictException.class);

		verify(series, never()).save(any());
	}

	@Test
	@DisplayName("se reservan exactamente las ocurrencias revisadas, cada una con su profesional si lo eligio")
	void ocurrencias_elegidas() {
		Instant primera = Instant.now().plus(Duration.ofDays(3));
		Instant segunda = Instant.now().plus(Duration.ofDays(5));
		AltaDeSerieCommand pedido = new AltaDeSerieCommand(42L, 128L, 31L, tresLunes, null, List.of(
				new AltaDeSerieCommand.OcurrenciaElegida(segunda, 32L),
				new AltaDeSerieCommand.OcurrenciaElegida(primera, null)), null, null, OrigenDeTurno.PLAN);

		ResultadoDeSerie resultado = service.crear(actor, CONSULTORIO_ID, pedido);

		assertThat(resultado.serie().turnos()).extracting(TurnoView::inicio).containsExactly(primera, segunda);
		ArgumentCaptor<RevalidadorDeSlot.Pedido> revalidado = ArgumentCaptor.forClass(RevalidadorDeSlot.Pedido.class);
		verify(revalidador, org.mockito.Mockito.times(2)).revalidar(revalidado.capture());
		assertThat(revalidado.getAllValues()).extracting(RevalidadorDeSlot.Pedido::profesionalId).containsExactly(31L, 32L);
	}

	@Test
	@DisplayName("un horario repetido en las ocurrencias elegidas es 400: cada sesion va en el suyo")
	void horario_repetido() {
		Instant repetido = Instant.now().plus(Duration.ofDays(3));
		AltaDeSerieCommand pedido = new AltaDeSerieCommand(42L, 128L, 31L, tresLunes, null, List.of(
				new AltaDeSerieCommand.OcurrenciaElegida(repetido, 31L),
				new AltaDeSerieCommand.OcurrenciaElegida(repetido, 32L)), null, null, null);

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, pedido))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("repite un horario");

		verify(series, never()).save(any());
		verify(turnos, never()).save(any());
	}

	@Test
	@DisplayName("con ocurrencias elegidas, el feriado de la regla que quedo afuera se registra como omitido, y uno elegido no")
	void feriado_con_ocurrencias_elegidas() {
		java.time.ZoneId zona = java.time.ZoneId.of(ZONA);
		List<Instant> deLaRegla = tresLunes.ocurrencias().stream().map(local -> local.atZone(zona).toInstant()).toList();
		LocalDate segundoLunes = tresLunes.ocurrencias().get(1).toLocalDate();
		given(revalidador.feriadosQueCierran(eq(ORG_ID), any(), any(), eq(31L), any(), any())).willReturn(Set.of(segundoLunes));
		given(omisiones.registrar(any())).willAnswer(invocacion -> invocacion.getArgument(0));

		SerieView sinElFeriado = service.crear(actor, CONSULTORIO_ID, new AltaDeSerieCommand(42L, 128L, 31L, tresLunes, null, List.of(
				new AltaDeSerieCommand.OcurrenciaElegida(deLaRegla.get(0), null),
				new AltaDeSerieCommand.OcurrenciaElegida(deLaRegla.get(2), null)), null, null, null)).serie();
		assertThat(sinElFeriado.turnos()).hasSize(2);
		assertThat(sinElFeriado.omitidas()).singleElement()
				.satisfies(omitida -> assertThat(omitida.fecha()).isEqualTo(segundoLunes));

		SerieView conElFeriado = service.crear(actor, CONSULTORIO_ID, new AltaDeSerieCommand(42L, 128L, 31L, tresLunes, null, List.of(
				new AltaDeSerieCommand.OcurrenciaElegida(deLaRegla.get(1), null)), null, null, null)).serie();
		assertThat(conElFeriado.turnos()).as("si lo eligio se intenta, y que lo rechace la revalidacion").hasSize(1);
		assertThat(conElFeriado.omitidas()).isEmpty();
	}

	@Test
	@DisplayName("una primera ocurrencia en el pasado es 400: el pasado no se reserva")
	void primera_ocurrencia_pasada() {
		AltaDeSerieCommand pedido = new AltaDeSerieCommand(42L, 128L, 31L, tresLunes, null, List.of(
				new AltaDeSerieCommand.OcurrenciaElegida(Instant.now().minus(Duration.ofDays(1)), null)), null, null, null);

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, pedido))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ya paso");

		verify(series, never()).save(any());
	}

	@Test
	@DisplayName("el tope del plan se exige por el total de la serie, antes de crear nada")
	void tope_del_plan() {
		var plan = dadoElPlan();
		org.mockito.Mockito.doThrow(new PlanSinSesionesException(57L, 2, 3)).when(sesionesDelPlan)
				.exigirDisponibles(ORG_ID, plan, 42L, 3);

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, conPlan()))
				.isInstanceOfSatisfying(PlanSinSesionesException.class, sinSesiones -> {
					assertThat(sinSesiones.getPendientes()).isEqualTo(2);
					assertThat(sinSesiones.getPedidas()).isEqualTo(3);
				});

		verify(series, never()).save(any());
		verify(turnos, never()).save(any());
	}

	@Test
	@DisplayName("cada turno de una serie con plan queda con caso y plan")
	void turnos_con_caso_y_plan() {
		dadoElPlan();

		ResultadoDeSerie resultado = service.crear(actor, CONSULTORIO_ID, conPlan());

		assertThat(resultado.serie().turnos()).hasSize(3).allSatisfy(turno -> {
			assertThat(turno.casoId()).isEqualTo(9L);
			assertThat(turno.planId()).isEqualTo(57L);
		});
	}

	@Test
	@DisplayName("no se crea una serie para quien no es paciente o para una persona ajena")
	void persona_no_paciente() {
		given(pacientes.find(ORG_ID, 128L)).willReturn(Optional.of(new PacienteSnapshot(
				128L, ORG_ID, "Sintetico", "Persona", null, null, null, true, false, null, null)));
		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, new AltaDeSerieCommand(42L, 128L, 31L, tresLunes, null)))
				.isInstanceOf(PersonaSinPerfilPacienteException.class);

		given(pacientes.find(ORG_ID, 128L)).willReturn(Optional.empty());
		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, new AltaDeSerieCommand(42L, 128L, 31L, tresLunes, null)))
				.isInstanceOf(PersonaNotAccessibleException.class);
	}

	@Test
	@DisplayName("una oferta vencida o de baja en una ocurrencia nombra esa ocurrencia, con la causa de la reserva")
	void ocurrencia_fuera_de_vigencia() {
		LocalDate primeraOcurrencia = tresLunes.ocurrencias().getFirst().toLocalDate();
		given(ofertas.find(ORG_ID, CONSULTORIO_ID, 42L)).willReturn(Optional.of(new OfertaSnapshot(
				42L, ORG_ID, CONSULTORIO_ID, 3L, "Kinesiologia", 60, 1, false, true, false, false, false,
				LocalDate.now().minusYears(1), primeraOcurrencia, true)));
		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, new AltaDeSerieCommand(42L, 128L, 31L, tresLunes, null)))
				.isInstanceOf(OcurrenciaSinLugarException.class)
				.hasCauseInstanceOf(OfertaNoAgendableException.class)
				.hasMessageContaining("no esta vigente");

		given(ofertas.find(ORG_ID, CONSULTORIO_ID, 42L)).willReturn(Optional.of(new OfertaSnapshot(
				42L, ORG_ID, CONSULTORIO_ID, 3L, "Kinesiologia", 60, 1, false, true, false, false, false,
				LocalDate.now().minusYears(1), null, false)));
		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, new AltaDeSerieCommand(42L, 128L, 31L, tresLunes, null)))
				.isInstanceOf(OcurrenciaSinLugarException.class)
				.hasMessageContaining("dada de baja");
	}

	@Test
	@DisplayName("sin contexto de trabajo es 403 y no 401")
	void sin_contexto() {
		assertThatThrownBy(() -> service.crear(new OperatingActor(9L, false, null, null), CONSULTORIO_ID,
				new AltaDeSerieCommand(42L, 128L, 31L, tresLunes, null)))
				.isInstanceOf(AccessDeniedException.class);
	}

	// =================================================================================
	// Operaciones por alcance
	// =================================================================================

	@Test
	@DisplayName("el alcance se exige para previsualizar, y uno que se cuenta desde un turno necesita el turno")
	void alcance_obligatorio() {
		assertThatThrownBy(() -> service.previsualizar(actor, CONSULTORIO_ID, SERIE_ID, null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("obligatorio");

		dadaLaSerie(futuro(301L, 3));
		assertThatThrownBy(() -> service.previsualizar(actor, CONSULTORIO_ID, SERIE_ID, AlcanceDeSerie.ESTE_Y_SIGUIENTES, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("falta turnoId");
		assertThatThrownBy(() -> service.previsualizar(actor, CONSULTORIO_ID, SERIE_ID, AlcanceDeSerie.ESTE_Y_SIGUIENTES, 999L))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("no pertenece");
	}

	@Test
	@DisplayName("sin nada pendiente en el alcance es 409: no hay que confirmar una operacion vacia")
	void nada_pendiente_en_el_alcance() {
		dadaLaSerie();

		assertThatThrownBy(() -> service.cancelar(actor, CONSULTORIO_ID, SERIE_ID,
				OperacionDeSerieCommand.cancelacion(AlcanceDeSerie.TODA_LA_SERIE, null, "Baja", 0)))
				.isInstanceOf(TransicionDeTurnoNoPermitidaException.class);
	}

	@Test
	@DisplayName("reprogramar necesita pivote y horario nuevo, y mover al mismo horario sin cambiar profesional es 400")
	void reprogramar_rechazos() {
		Turno pivote = futuro(301L, 3);
		TurnoSerie serie = dadaLaSerie(pivote);
		ReflectionTestUtils.setField(pivote, "serieId", serie.getId());
		given(turnos.findByIdInScope(ORG_ID, CONSULTORIO_ID, 301L)).willReturn(Optional.of(pivote));

		assertThatThrownBy(() -> service.reprogramar(actor, CONSULTORIO_ID, SERIE_ID, new OperacionDeSerieCommand(
				AlcanceDeSerie.TODA_LA_SERIE, null, "Mover", 1, pivote.getInicio(), null)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.reprogramar(actor, CONSULTORIO_ID, SERIE_ID, new OperacionDeSerieCommand(
				AlcanceDeSerie.TODA_LA_SERIE, 301L, "Mover", 1, null, null)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.reprogramar(actor, CONSULTORIO_ID, SERIE_ID, new OperacionDeSerieCommand(
				AlcanceDeSerie.TODA_LA_SERIE, 301L, "Mover", 1, pivote.getInicio(), null)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("mismo que el actual");

		verify(ciclo, never()).aplicarReprogramacion(any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("el profesional nuevo se aplica a cada turno movido, aunque el horario sea el mismo")
	void reprogramar_solo_el_profesional() {
		Turno pivote = futuro(301L, 3);
		TurnoSerie serie = dadaLaSerie(pivote);
		ReflectionTestUtils.setField(pivote, "serieId", serie.getId());
		given(turnos.findByIdInScope(ORG_ID, CONSULTORIO_ID, 301L)).willReturn(Optional.of(pivote));
		given(ciclo.aplicarReprogramacion(any(), any(), any(), any(), any(), any(), any(), any())).willReturn(pivote);

		service.reprogramar(actor, CONSULTORIO_ID, SERIE_ID, new OperacionDeSerieCommand(
				AlcanceDeSerie.TODA_LA_SERIE, 301L, "Cambia de profesional", 1, pivote.getInicio(), 32L));

		verify(ciclo).aplicarReprogramacion(any(), any(), any(), eq(pivote), any(), eq(32L), eq("Cambia de profesional"), any());
	}

	@Test
	@DisplayName("un turno que no es de esa serie no sirve de pivote")
	void pivote_de_otra_serie() {
		Turno pivote = futuro(301L, 3);
		dadaLaSerie(pivote);
		given(turnos.findByIdInScope(ORG_ID, CONSULTORIO_ID, 301L)).willReturn(Optional.of(pivote));

		assertThatThrownBy(() -> service.reprogramar(actor, CONSULTORIO_ID, SERIE_ID, new OperacionDeSerieCommand(
				AlcanceDeSerie.TODA_LA_SERIE, 301L, "Mover", 1, pivote.getInicio().plus(Duration.ofHours(1)), null)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("no pertenece");
	}

	@Test
	@DisplayName("la bandeja con busqueda se ordena tambien por fechas de la regla")
	void orden_por_fechas_de_la_regla() {
		TurnoSerie antes = serieConId(20L);
		TurnoSerie despues = serieConId(21L);
		ReflectionTestUtils.setField(despues, "fechaDesde", LocalDate.now().plusDays(30));
		ReflectionTestUtils.setField(despues, "fechaHasta", LocalDate.now().plusDays(60));
		ReflectionTestUtils.setField(despues, "cantidad", null);
		given(series.todas(eq(ORG_ID), eq(CONSULTORIO_ID), isNull(), isNull(), any())).willReturn(List.of(antes, despues));
		given(pacientes.findAll(eq(ORG_ID), any())).willReturn(java.util.Map.of(128L, new PacienteSnapshot(
				128L, ORG_ID, "Sintetico", "Paciente", "DNI", "30111222", null, true, true, 5L, Instant.now())));
		given(ofertas.findTodas(eq(ORG_ID), eq(CONSULTORIO_ID), any())).willReturn(java.util.Map.of());
		given(membresias.findTodas(eq(ORG_ID), any())).willReturn(java.util.Map.of());
		given(identidades.identidadesDe(any())).willReturn(java.util.Map.of());

		assertThat(service.listar(actor, CONSULTORIO_ID, null, null, "sintetico",
				OrdenDeListado.leer("fechaDesde,desc", "fechaDesde"), 0, 20).contenido())
				.extracting(SerieResumenView::id).containsExactly(21L, 20L);
		assertThat(service.listar(actor, CONSULTORIO_ID, null, null, "sintetico",
				OrdenDeListado.leer("fechaHasta,asc", "fechaHasta"), 0, 20).contenido())
				.as("sin fecha fin va al final")
				.extracting(SerieResumenView::id).containsExactly(21L, 20L);
	}

	private TurnoSerie dadaLaSerie(Turno... deLaSerie) {
		TurnoSerie serie = serieConId(SERIE_ID);
		given(series.findByIdInScope(ORG_ID, CONSULTORIO_ID, SERIE_ID)).willReturn(Optional.of(serie));
		given(turnos.findDeLaSerie(ORG_ID, SERIE_ID)).willReturn(List.of(deLaSerie));
		return serie;
	}

	private ContextoClinicoDeAgenda.PlanAgendable dadoElPlan() {
		var plan = new ContextoClinicoDeAgenda.PlanAgendable(57L, 9L, 128L, 1, "ACTIVO", 3, 4, List.of(
				new ContextoClinicoDeAgenda.ItemAgendable(42L, CONSULTORIO_ID, "Kinesiologia", 10, 0)));
		given(contextoDeAgenda.plan(ORG_ID, 57L)).willReturn(Optional.of(plan));
		given(contextoDeAgenda.caso(ORG_ID, 9L)).willReturn(Optional.of(
				new ContextoClinicoDeAgenda.CasoAgendable(9L, 128L, 1, 42L, true, null, List.of(plan))));
		return plan;
	}

	private AltaDeSerieCommand conPlan() {
		return new AltaDeSerieCommand(42L, 128L, 31L, tresLunes, null, null, null, 57L, OrigenDeTurno.PLAN);
	}

	private TurnoSerie serieConId(long id) {
		TurnoSerie serie = new TurnoSerie(ORG_ID, CONSULTORIO_ID, 42L, 128L, 31L, tresLunes, ZONA, 3,
				9L, Instant.now(), null, null);
		ReflectionTestUtils.setField(serie, "id", id);
		return serie;
	}

	private static Turno futuro(long id, int enDias) {
		Instant inicio = Instant.now().plus(Duration.ofDays(enDias));
		Turno turno = new Turno(ORG_ID, CONSULTORIO_ID, 42L, 128L, 31L, null, inicio,
				inicio.plus(Duration.ofHours(1)), 9L, Instant.now(), null, null);
		ReflectionTestUtils.setField(turno, "id", id);
		return turno;
	}
}
