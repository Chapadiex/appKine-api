package com.akine.scheduling.application;

import com.akine.offering.spi.PracticasDeOfertaDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.CoberturaAplicable;
import com.akine.person.spi.CoberturasAplicablesDirectory;
import com.akine.person.spi.ElegibilidadAdministrativaDirectory;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.scheduling.spi.PrepagoDeTurnoProbe;
import com.akine.person.spi.ReferenciaCongelada;
import com.akine.person.spi.VeredictoDeElegibilidad;
import com.akine.scheduling.domain.EstadoRecepcion;
import com.akine.scheduling.domain.Recepcion;
import com.akine.scheduling.domain.RecepcionEvento;
import com.akine.scheduling.domain.TipoEventoRecepcion;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.RecepcionNotAccessibleException;
import com.akine.scheduling.domain.exception.TransicionDeRecepcionNoPermitidaException;
import com.akine.scheduling.domain.exception.TransicionDeTurnoNoPermitidaException;
import com.akine.scheduling.domain.exception.TurnoNotAccessibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.BloqueoDeTurnoPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.RecepcionEventoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Las reglas de {@link CicloDeRecepcionService} que no necesitan base: la idempotencia del
 * check-in, cada desenlace de la validacion y las precondiciones. Lo que si necesita base —el
 * {@code FOR UPDATE}, la version forzada, los CHECK— esta en {@code RecepcionIT} y
 * {@code RecepcionConcurrenteIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CicloDeRecepcionServiceTest {

	private static final long ORG = 1L;
	private static final long SEDE = 7L;
	private static final long TURNO = 301L;
	private static final long OFERTA = 42L;
	private static final long PERSONA = 128L;
	private static final long PRACTICA = 33L;
	private static final String ZONA = "America/Argentina/Cordoba";

	@Mock private TurnoRepositoryPort turnos;
	@Mock private BloqueoDeTurnoPort bloqueos;
	@Mock private RegistroDeRecepcion registro;
	@Mock private RecepcionEventoRepositoryPort eventos;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;
	@Mock private PracticasDeOfertaDirectory practicas;
	@Mock private CoberturasAplicablesDirectory coberturas;
	@Mock private ElegibilidadAdministrativaDirectory elegibilidad;
	@Mock private OfertaDirectory ofertas;
	@Mock private PrepagoDeTurnoProbe prepagos;

	private CicloDeRecepcionService service;
	private final OperatingActor actor = new OperatingActor(9L, false, ORG, SEDE);
	private Turno turno;
	private LocalDate diaDelTurno;

	@BeforeEach
	void preparar() {
		service = new CicloDeRecepcionService(turnos, bloqueos, registro, eventos, consultorios,
				permissionGuard, practicas, coberturas, elegibilidad,
				new PrepagoDeRecepcion(ofertas, prepagos));

		Instant inicio = Instant.now().plus(Duration.ofDays(2));
		turno = new Turno(ORG, SEDE, OFERTA, PERSONA, 31L, null,
				inicio, inicio.plus(Duration.ofMinutes(45)), 9L, Instant.now(), null, null);
		ReflectionTestUtils.setField(turno, "id", TURNO);
		diaDelTurno = inicio.atZone(ZoneId.of(ZONA)).toLocalDate();

		given(consultorios.find(ORG, SEDE)).willReturn(Optional.of(
				new ConsultorioSnapshot(SEDE, ORG, "Sede", ZONA, true)));
		given(turnos.findByIdInScope(ORG, SEDE, TURNO)).willReturn(Optional.of(turno));
		given(bloqueos.bloquear(ORG, SEDE, TURNO)).willReturn(Optional.of(turno));
		given(registro.registrar(any(), any(), any(), any(), anyLong(), any())).willAnswer(invocacion -> {
			Recepcion recepcion = invocacion.getArgument(0);
			if (recepcion.getId() == null) {
				ReflectionTestUtils.setField(recepcion, "id", 77L);
			}
			return recepcion;
		});
	}

	// =================================================================================
	// Llegada
	// =================================================================================

	@Test
	@DisplayName("el check-in crea la recepcion y fuerza la version del turno")
	void check_in_crea() {
		CicloDeRecepcionService.ResultadoDeLlegada resultado = service.registrarLlegada(actor, SEDE, TURNO);

		assertThat(resultado.creada()).isTrue();
		assertThat(resultado.recepcion().estado()).isEqualTo("LLEGO");
		assertThat(resultado.turno().id()).isEqualTo(TURNO);
		verify(bloqueos).forzarVersion(turno);
		verify(registro).registrar(any(), eq(TipoEventoRecepcion.LLEGADA), isNull(), isNull(), eq(9L), any());
	}

	@Test
	@DisplayName("con una recepcion abierta el check-in la devuelve sin tocar nada")
	void check_in_idempotente() {
		Recepcion abierta = abierta();
		given(registro.vigente(ORG, TURNO)).willReturn(Optional.of(abierta));

		CicloDeRecepcionService.ResultadoDeLlegada resultado = service.registrarLlegada(actor, SEDE, TURNO);

		assertThat(resultado.creada()).isFalse();
		assertThat(resultado.recepcion().id()).isEqualTo(77L);
		verify(bloqueos, never()).forzarVersion(any());
		verify(registro, never()).registrar(any(), any(), any(), any(), anyLong(), any());
	}

	@Test
	@DisplayName("sobre un turno cancelado no hay llegada, ni un turno de otra sede: 409 y 404")
	void check_in_rechazado() {
		turno.cancelar("Aviso", 9L, Instant.now());
		assertThatThrownBy(() -> service.registrarLlegada(actor, SEDE, TURNO))
				.isInstanceOf(TransicionDeTurnoNoPermitidaException.class);

		given(bloqueos.bloquear(ORG, SEDE, 999L)).willReturn(Optional.empty());
		assertThatThrownBy(() -> service.registrarLlegada(actor, SEDE, 999L))
				.isInstanceOf(TurnoNotAccessibleException.class);
		verify(bloqueos, never()).forzarVersion(any());
	}

	@Test
	@DisplayName("sin contexto de trabajo es 403, nunca 401")
	void sin_contexto() {
		assertThatThrownBy(() -> service.registrarLlegada(
				new OperatingActor(9L, false, null, null), SEDE, TURNO))
				.isInstanceOf(AccessDeniedException.class);
		verifyNoInteractions(bloqueos);
	}

	// =================================================================================
	// Validacion
	// =================================================================================

	@Test
	@DisplayName("sin practica en la oferta: OBSERVADA con OFERTA_SIN_PRACTICA, sin consultar coberturas")
	void validar_sin_practica() {
		conAbierta();
		given(practicas.practicaPrincipal(ORG, SEDE, OFERTA)).willReturn(Optional.empty());

		RecepcionView vista = service.validar(actor, SEDE, TURNO, null, 0L);

		assertThat(vista.estado()).isEqualTo("OBSERVADA");
		assertThat(vista.observacion()).startsWith(CicloDeRecepcionService.OFERTA_SIN_PRACTICA);
		verifyNoInteractions(coberturas, elegibilidad);
	}

	@Test
	@DisplayName("sin cobertura aplicable el dia del turno: OBSERVADA con SIN_COBERTURA_APLICABLE")
	void validar_sin_cobertura() {
		conAbierta();
		conPractica();
		given(coberturas.aplicables(ORG, SEDE, PERSONA, PRACTICA, OFERTA, diaDelTurno)).willReturn(List.of());

		RecepcionView vista = service.validar(actor, SEDE, TURNO, null, 0L);

		assertThat(vista.estado()).isEqualTo("OBSERVADA");
		assertThat(vista.observacion()).startsWith(CicloDeRecepcionService.SIN_COBERTURA_APLICABLE);
		assertThat(vista.practicaId()).isEqualTo(PRACTICA);
		verifyNoInteractions(elegibilidad);
	}

	@Test
	@DisplayName("la cobertura elegida que no aplica: OBSERVADA con COBERTURA_NO_APLICABLE")
	void validar_cobertura_elegida_no_aplica() {
		conAbierta();
		conPractica();
		conCoberturas(412L, 413L);

		RecepcionView vista = service.validar(actor, SEDE, TURNO, 999L, 0L);

		assertThat(vista.observacion()).startsWith(CicloDeRecepcionService.COBERTURA_NO_APLICABLE);
		verifyNoInteractions(elegibilidad);
	}

	@Test
	@DisplayName("elegible: VALIDADA con la cobertura elegida (o la principal) y el convenio")
	void validar_elegible() {
		conAbierta();
		conPractica();
		conCoberturas(412L, 413L);
		given(elegibilidad.evaluar(ORG, SEDE, PERSONA, 413L, PRACTICA, diaDelTurno))
				.willReturn(new VeredictoDeElegibilidad(true, null, 9L, List.of()));

		RecepcionView vista = service.validar(actor, SEDE, TURNO, 413L, 0L);

		assertThat(vista.estado()).isEqualTo("VALIDADA");
		assertThat(vista.modalidad()).isEqualTo("COBERTURA");
		assertThat(vista.coberturaId()).isEqualTo(413L);
		assertThat(vista.convenioId()).isEqualTo(9L);
		verify(registro).registrar(any(), eq(TipoEventoRecepcion.VALIDACION), eq(EstadoRecepcion.LLEGO),
				isNull(), eq(9L), any());
	}

	@Test
	@DisplayName("no elegible: OBSERVADA con cada faltante; sin faltantes, con el motivo")
	void validar_no_elegible() {
		conAbierta();
		conPractica();
		conCoberturas(412L);
		given(elegibilidad.evaluar(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
				.willReturn(new VeredictoDeElegibilidad(false, null, 9L,
						List.of("ORDEN: falta", "CREDENCIAL: vencida")));

		RecepcionView vista = service.validar(actor, SEDE, TURNO, null, 0L);
		assertThat(vista.observacion())
				.isEqualTo("DOCUMENTACION_INCOMPLETA: ORDEN: falta | CREDENCIAL: vencida");
		assertThat(vista.coberturaId()).isEqualTo(412L);

		given(elegibilidad.evaluar(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
				.willReturn(new VeredictoDeElegibilidad(false, "COBERTURA_NO_ACCESIBLE", null, List.of()));
		RecepcionView revalidada = service.validar(actor, SEDE, TURNO, null, vista.version());
		assertThat(revalidada.observacion()).isEqualTo("DOCUMENTACION_INCOMPLETA: COBERTURA_NO_ACCESIBLE");
	}

	// =================================================================================
	// Precondiciones de las transiciones
	// =================================================================================

	@Test
	@DisplayName("sin recepcion abierta las transiciones son 409; con version vieja, conflicto optimista")
	void precondiciones() {
		assertThatThrownBy(() -> service.pasarAEspera(actor, SEDE, TURNO, 0L))
				.isInstanceOf(TransicionDeRecepcionNoPermitidaException.class);

		conAbierta();
		assertThatThrownBy(() -> service.llamar(actor, SEDE, TURNO, 5L))
				.isInstanceOf(OptimisticLockingFailureException.class);
		assertThatThrownBy(() -> service.validar(actor, SEDE, TURNO, null, 5L))
				.isInstanceOf(OptimisticLockingFailureException.class);
	}

	@Test
	@DisplayName("Particular, espera, llamado y anulacion registran su tipo de evento")
	void transiciones() {
		Recepcion recepcion = conAbierta();

		assertThat(service.atenderComoParticular(actor, SEDE, TURNO, " Abona ", 0L).modalidad())
				.isEqualTo("PARTICULAR");
		assertThat(service.pasarAEspera(actor, SEDE, TURNO, recepcion.getVersion()).estado())
				.isEqualTo("EN_ESPERA");
		assertThat(service.llamar(actor, SEDE, TURNO, recepcion.getVersion()).estado())
				.isEqualTo("LLAMADA");
		assertThat(service.anular(actor, SEDE, TURNO, null, null).estado()).isEqualTo("ANULADA");

		verify(registro).registrar(any(), eq(TipoEventoRecepcion.PARTICULAR), eq(EstadoRecepcion.LLEGO),
				eq("Abona"), eq(9L), any());
		verify(registro).registrar(any(), eq(TipoEventoRecepcion.ESPERA), any(), isNull(), eq(9L), any());
		verify(registro).registrar(any(), eq(TipoEventoRecepcion.LLAMADO), any(), isNull(), eq(9L), any());
		verify(registro).registrar(any(), eq(TipoEventoRecepcion.ANULACION), eq(EstadoRecepcion.LLAMADA),
				isNull(), eq(9L), any());
	}

	@Test
	@DisplayName("E-6: con el prepago pendiente pasa a espera igual, y el evento lo deja escrito")
	void espera_con_prepago_pendiente() {
		Recepcion recepcion = conAbierta();
		recepcion.atenderComoParticular("Sin cobertura", 9L, Instant.now());
		given(ofertas.precioDe(ORG, SEDE, OFERTA)).willReturn(Optional.of(
				new com.akine.offering.spi.PrecioDeOferta(OFERTA, new java.math.BigDecimal("8500.00"),
						"ARS", false, true)));

		RecepcionView enEspera = service.pasarAEspera(actor, SEDE, TURNO, recepcion.getVersion());

		assertThat(enEspera.estado()).as("alerta, no bloqueo (DP-06)").isEqualTo("EN_ESPERA");
		assertThat(enEspera.prepago().estado()).isEqualTo(PrepagoView.PENDIENTE);
		assertThat(enEspera.prepago().importeSugerido()).isEqualByComparingTo("8500.00");
		verify(registro).registrar(any(), eq(TipoEventoRecepcion.ESPERA), eq(EstadoRecepcion.VALIDADA),
				org.mockito.ArgumentMatchers.startsWith(CicloDeRecepcionService.PREPAGO_PENDIENTE),
				eq(9L), any());
	}

	@Test
	@DisplayName("E-6: con el prepago registrado, la espera no lleva motivo y la vista lo muestra")
	void espera_con_prepago_registrado() {
		Recepcion recepcion = conAbierta();
		recepcion.atenderComoParticular("Sin cobertura", 9L, Instant.now());
		given(ofertas.precioDe(ORG, SEDE, OFERTA)).willReturn(Optional.of(
				new com.akine.offering.spi.PrecioDeOferta(OFERTA, new java.math.BigDecimal("8500.00"),
						"ARS", false, true)));
		given(prepagos.prepagosDe(ORG, List.of(TURNO))).willReturn(java.util.Map.of(TURNO,
				new PrepagoDeTurnoProbe.PrepagoDeTurno(TURNO, 88L, new java.math.BigDecimal("8500.00"),
						new java.math.BigDecimal("8500.00"), "ARS")));

		RecepcionView enEspera = service.pasarAEspera(actor, SEDE, TURNO, recepcion.getVersion());

		assertThat(enEspera.prepago().estado()).isEqualTo(PrepagoView.REGISTRADO);
		assertThat(enEspera.prepago().cobroId()).isEqualTo(88L);
		verify(registro).registrar(any(), eq(TipoEventoRecepcion.ESPERA), any(), isNull(), eq(9L), any());
	}

	@Test
	@DisplayName("el DELETE deprecado de 05.04 anula sin version y devuelve el turno")
	void deshacer_deprecado() {
		conAbierta();

		TurnoView vista = service.deshacerLlegadaDeprecada(actor, SEDE, TURNO);

		assertThat(vista.id()).isEqualTo(TURNO);
		verify(registro).registrar(any(), eq(TipoEventoRecepcion.ANULACION), any(), any(), eq(9L), any());
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	@Test
	@DisplayName("ver sin recepcion es 404 de recepcion; el historial exige que el turno sea de la sede")
	void lecturas() {
		assertThatThrownBy(() -> service.ver(actor, SEDE, TURNO))
				.isInstanceOf(RecepcionNotAccessibleException.class);

		Recepcion recepcion = conAbierta();
		assertThat(service.ver(actor, SEDE, TURNO).id()).isEqualTo(77L);

		RecepcionEvento evento = RecepcionEvento.de(
				recepcion, TipoEventoRecepcion.LLEGADA, null, null, 9L, Instant.now());
		ReflectionTestUtils.setField(evento, "id", 5001L);
		given(eventos.historial(ORG, TURNO)).willReturn(List.of(evento));
		assertThat(service.historial(actor, SEDE, TURNO)).extracting(EventoDeRecepcionView::tipo)
				.containsExactly("LLEGADA");

		given(turnos.findByIdInScope(ORG, SEDE, 999L)).willReturn(Optional.empty());
		assertThatThrownBy(() -> service.historial(actor, SEDE, 999L))
				.isInstanceOf(TurnoNotAccessibleException.class);
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	private Recepcion abierta() {
		Recepcion recepcion = Recepcion.llegada(turno, Instant.now(), 9L);
		ReflectionTestUtils.setField(recepcion, "id", 77L);
		return recepcion;
	}

	private Recepcion conAbierta() {
		Recepcion recepcion = abierta();
		given(registro.vigente(ORG, TURNO)).willReturn(Optional.of(recepcion));
		return recepcion;
	}

	private void conPractica() {
		given(practicas.practicaPrincipal(ORG, SEDE, OFERTA)).willReturn(Optional.of(PRACTICA));
	}

	private void conCoberturas(long... ids) {
		List<CoberturaAplicable> lista = java.util.Arrays.stream(ids)
				.mapToObj(id -> new CoberturaAplicable(id, id == ids[0],
						new ReferenciaCongelada(1L, "Financiador", 2L, "Plan"), null, false, null))
				.toList();
		given(coberturas.aplicables(ORG, SEDE, PERSONA, PRACTICA, OFERTA, diaDelTurno)).willReturn(lista);
	}
}
