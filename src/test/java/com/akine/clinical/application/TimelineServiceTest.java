package com.akine.clinical.application;

import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.exception.AccesoClinicoNoJustificadoException;
import com.akine.clinical.domain.exception.CursorInvalidoException;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoClinicoRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.spi.EventoClinico;
import com.akine.clinical.spi.EventoClinicoContributor;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * El timeline: se agrega al leer, se pagina por keyset y se audita siempre.
 *
 * <p>Lo que estos tests fijan son las cuatro cosas que cuestan caro si se rompen: que la mezcla
 * respete el orden total de las cuatro fuentes, que la pagina siguiente <b>no repita</b> el evento
 * del cursor, que un cursor roto sea 400 y no "primera pagina", y que ninguna lectura pase sin
 * dejar {@code TIMELINE_ACCESSED}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TimelineService")
class TimelineServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;
	private static final long HC_ID = 700L;
	private static final Instant T = Instant.parse("2026-09-19T10:00:00Z");

	@Mock
	private HistoriaClinicaRepositoryPort historias;

	/** 04.03: solo se consulta cuando se filtra por caso. Los tests de aca no filtran. */
	@Mock
	private CasoClinicoRepositoryPort casos;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private RelacionAsistencialProbe relaciones;

	@Mock
	private AuditTrail auditTrail;

	@Mock
	private ClinicalSupportAccessAuditor supportAccessAuditor;

	private final OperatingActor profesional =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
		given(relaciones.tieneRelacionAsistencial(anyLong(), anyLong(), anyLong(), anyLong()))
				.willReturn(true);
		given(historias.findByIdAndOrganizationId(HC_ID, ORG_ID))
				.willReturn(Optional.of(historia()));
	}

	// =================================================================================
	// Agregacion
	// =================================================================================

	@Test
	@DisplayName("mezcla las fuentes en el orden total, mas nuevo primero")
	void mezcla_ordenada() {
		TimelineService service = conFuentes(
				fija(evento(T.minusSeconds(120), "SESION", 1L)),
				fija(evento(T, "ADJUNTO_CLINICO", 5L), evento(T.minusSeconds(60), "ENTRADA", 3L)));

		TimelinePagina pagina = service.ver(profesional, HC_ID, null, null, null, null);

		assertThat(pagina.eventos())
				.extracting(EventoClinico::origen)
				.containsExactly("ADJUNTO_CLINICO", "ENTRADA", "SESION");
		assertThat(pagina.proximoCursor()).isNull();
	}

	@Test
	@DisplayName("sin contribuyentes devuelve una pagina vacia, no un error")
	void sin_fuentes() {
		TimelinePagina pagina = conFuentes().ver(profesional, HC_ID, null, null, null, null);

		assertThat(pagina.eventos()).isEmpty();
		assertThat(pagina.proximoCursor()).isNull();
	}

	// =================================================================================
	// Paginacion por keyset
	// =================================================================================

	@Test
	@DisplayName("recorta al limite y devuelve cursor cuando hay mas")
	void recorta_y_deja_cursor() {
		TimelineService service = conFuentes(fija(
				evento(T, "SESION", 3L),
				evento(T.minusSeconds(60), "SESION", 2L),
				evento(T.minusSeconds(120), "SESION", 1L)));

		TimelinePagina pagina = service.ver(profesional, HC_ID, null, 2, null, null);

		assertThat(pagina.eventos()).extracting(EventoClinico::referencia).containsExactly(3L, 2L);
		assertThat(pagina.proximoCursor()).isNotNull();

		TimelineCursor cursor = TimelineCursor.decodificar(pagina.proximoCursor());
		assertThat(cursor.referencia()).isEqualTo(2L);
		assertThat(cursor.ocurrioEn()).isEqualTo(T.minusSeconds(60));
	}

	@Test
	@DisplayName("la pagina siguiente no repite el evento del cursor")
	void segunda_pagina() {
		EventoClinico ultimoDeLaPrimera = evento(T.minusSeconds(60), "SESION", 2L);
		TimelineService service = conFuentes(fija(
				evento(T, "SESION", 3L),
				ultimoDeLaPrimera,
				evento(T.minusSeconds(120), "SESION", 1L)));
		String cursor = TimelineCursor.de(ultimoDeLaPrimera).codificar();

		TimelinePagina pagina = service.ver(profesional, HC_ID, cursor, 2, null, null);

		assertThat(pagina.eventos()).extracting(EventoClinico::referencia).containsExactly(1L);
		assertThat(pagina.proximoCursor()).isNull();
	}

	@Test
	@DisplayName("el tope temporal que reciben las fuentes es el del cursor, y es inclusivo")
	void tope_temporal_inclusivo() {
		EventoClinico ancla = evento(T, "SESION", 9L);
		FuenteEspia espia = new FuenteEspia(evento(T, "SESION", 7L));

		TimelinePagina pagina = conFuentes(espia)
				.ver(profesional, HC_ID, TimelineCursor.de(ancla).codificar(), 10, null, null);

		assertThat(espia.hasta).isEqualTo(T);
		// El desempate del mismo instante llega desde la fuente y lo filtra el agregador: el 7 es
		// posterior al 9 en el orden total, asi que entra.
		assertThat(pagina.eventos()).extracting(EventoClinico::referencia).containsExactly(7L);
	}

	@Test
	@DisplayName("pide DOS eventos de mas por fuente: uno es el lookahead y el otro la fila del cursor")
	void sobre_lectura() {
		// Fueron `limite + 1` hasta la primera corrida con base real, y con eso la paginacion
		// PERDIA eventos: la fuente a la que apunta el cursor devuelve tambien el evento del cursor
		// —el filtro es `ocurrio_en <= hasta`—, el agregador lo descarta, y el uno de mas se lo come
		// esa fila. La mezcla quedaba en `limite` exacto, "hay siguiente" daba falso y el recorrido
		// terminaba antes de tiempo. Ver TimelineIT#el_cursor_no_repite_ni_saltea.
		FuenteEspia espia = new FuenteEspia();

		conFuentes(espia).ver(profesional, HC_ID, null, 5, null, null);

		assertThat(espia.limite).isEqualTo(7);
	}

	@Test
	@DisplayName("un limite fuera de rango se recorta al default o al tope, sin fallar")
	void limite_acotado() {
		FuenteEspia sinLimite = new FuenteEspia();
		conFuentes(sinLimite).ver(profesional, HC_ID, null, null, null, null);
		assertThat(sinLimite.limite).isEqualTo(TimelineService.LIMITE_POR_DEFECTO + 2);

		FuenteEspia desmedida = new FuenteEspia();
		conFuentes(desmedida).ver(profesional, HC_ID, null, 100_000, null, null);
		assertThat(desmedida.limite).isEqualTo(TimelineService.LIMITE_MAXIMO + 2);
	}

	@Test
	@DisplayName("un cursor ilegible es 400 y no la primera pagina")
	void cursor_roto() {
		assertThatThrownBy(() -> conFuentes().ver(profesional, HC_ID, "%%%", null, null, null))
				.isInstanceOf(CursorInvalidoException.class);
	}

	// =================================================================================
	// Autorizacion y auditoria (DP-03)
	// =================================================================================

	@Test
	@DisplayName("deja TIMELINE_ACCESSED con la via, la cantidad y sin ningun id de evento")
	void audita_la_lectura() {
		TimelineService service = conFuentes(fija(evento(T, "SESION", 3L)));

		service.ver(profesional, HC_ID, null, null, null, null);

		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		AuditEntry evento = captor.getValue();
		assertThat(evento.eventType()).isEqualTo("TIMELINE_ACCESSED");
		assertThat(evento.entityId()).isEqualTo(HC_ID);
		assertThat(evento.details())
				.containsEntry("eventos", "1")
				.containsEntry("pagina", "PRIMERA")
				.containsEntry("viaDeAcceso", "RELACION_ASISTENCIAL");
		assertThat(evento.details()).doesNotContainKey("referencia");
	}

	@Test
	@DisplayName("sin relacion asistencial exige justificacion declarada")
	void exige_justificacion() {
		given(relaciones.tieneRelacionAsistencial(anyLong(), anyLong(), anyLong(), anyLong()))
				.willReturn(false);

		assertThatThrownBy(() -> conFuentes().ver(profesional, HC_ID, null, null, null, null))
				.isInstanceOf(AccesoClinicoNoJustificadoException.class);
		verify(auditTrail, never()).record(any());
	}

	@Test
	@DisplayName("sin contexto de trabajo es 403, y ni siquiera se busca la historia")
	void sin_contexto() {
		OperatingActor sinSede = new OperatingActor(ACCOUNT_ID, false, ORG_ID, null);

		assertThatThrownBy(() -> conFuentes().ver(sinSede, HC_ID, null, null, null, null))
				.isInstanceOf(AccessDeniedException.class);
		verify(historias, never()).findByIdAndOrganizationId(anyLong(), anyLong());
	}

	@Test
	@DisplayName("una historia de otro tenant es indistinguible de una que no existe")
	void otro_tenant() {
		given(historias.findByIdAndOrganizationId(HC_ID, ORG_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> conFuentes().ver(profesional, HC_ID, null, null, null, null))
				.isInstanceOf(HistoriaClinicaNotAccessibleException.class);
	}

	// =================================================================================
	// Filtros de origen y fecha (0.107.0, appKine-web #83)
	// =================================================================================

	@Test
	@DisplayName("el filtro por origen saltea la fuente entera y pagina sin perder eventos")
	void filtro_por_origen_pagina_sin_perder() {
		// Cinco entradas MAS NUEVAS que las tres sesiones. Si el filtro descartara despues de pedir,
		// la fuente de entradas no influiria; lo que se prueba es que la de sesiones recorre entera.
		FuenteReal entradas = new FuenteReal("ENTRADA_CLINICA",
				evento(T, "ENTRADA_CLINICA", 15L), evento(T.minusSeconds(10), "ENTRADA_CLINICA", 14L),
				evento(T.minusSeconds(20), "ENTRADA_CLINICA", 13L),
				evento(T.minusSeconds(30), "ENTRADA_CLINICA", 12L),
				evento(T.minusSeconds(40), "ENTRADA_CLINICA", 11L));
		FuenteReal sesiones = new FuenteReal("SESION",
				evento(T.minusSeconds(100), "SESION", 3L), evento(T.minusSeconds(200), "SESION", 2L),
				evento(T.minusSeconds(300), "SESION", 1L));
		TimelineService service = conFuentes(entradas, sesiones);

		TimelinePagina primera = service.ver(profesional, HC_ID, null, 2, null,
				Set.of("SESION"), null, null, null);
		TimelinePagina segunda = service.ver(profesional, HC_ID, primera.proximoCursor(), 2, null,
				Set.of("SESION"), null, null, null);

		assertThat(primera.eventos()).extracting(EventoClinico::referencia).containsExactly(3L, 2L);
		assertThat(segunda.eventos()).extracting(EventoClinico::referencia).containsExactly(1L);
		assertThat(segunda.proximoCursor()).isNull();
		assertThat(entradas.llamadas).as("la fuente excluida no se consulta").isZero();
	}

	@Test
	@DisplayName("un origen que ninguna fuente declara es 400, no una pagina vacia")
	void origen_desconocido() {
		TimelineService service = conFuentes(new FuenteReal("SESION"));

		assertThatThrownBy(() -> service.ver(profesional, HC_ID, null, null, null,
				Set.of("TURNO"), null, null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("TURNO");
	}

	@Test
	@DisplayName("hasta baja el tope de las fuentes; desde descarta la cola; los dos se auditan")
	void filtro_por_fecha() {
		FuenteReal sesiones = new FuenteReal("SESION",
				evento(T, "SESION", 4L), evento(T.minusSeconds(60), "SESION", 3L),
				evento(T.minusSeconds(120), "SESION", 2L), evento(T.minusSeconds(180), "SESION", 1L));
		Instant desde = T.minusSeconds(120);
		Instant hasta = T.minusSeconds(30);

		TimelinePagina pagina = conFuentes(sesiones).ver(profesional, HC_ID, null, 10, null,
				null, desde, hasta, null);

		assertThat(sesiones.ultimoHasta).isEqualTo(hasta);
		assertThat(pagina.eventos()).extracting(EventoClinico::referencia).containsExactly(3L, 2L);
		assertThat(pagina.proximoCursor()).isNull();
		assertThat(auditado().details())
				.containsEntry("filtroDesde", desde.toString())
				.containsEntry("filtroHasta", hasta.toString());
	}

	@Test
	@DisplayName("desde posterior a hasta es 400")
	void desde_posterior_a_hasta() {
		assertThatThrownBy(() -> conFuentes(new FuenteReal("SESION")).ver(profesional, HC_ID, null,
				null, null, null, T, T.minusSeconds(1), null))
				.isInstanceOf(IllegalArgumentException.class);
		verify(auditTrail, never()).record(any());
	}

	// =================================================================================
	// Andamiaje
	// =================================================================================

	/** Una fuente que se porta como las reales: un origen, tope inclusivo y limite respetados. */
	private static final class FuenteReal implements EventoClinicoContributor {

		private final String origen;
		private final List<EventoClinico> eventos;
		private int llamadas;
		private Instant ultimoHasta;

		private FuenteReal(String origen, EventoClinico... eventos) {
			this.origen = origen;
			this.eventos = List.of(eventos);
		}

		@Override
		public List<EventoClinico> eventosDe(
				long organizationId, long historiaClinicaId, Instant hasta, int limite, Long casoId) {

			llamadas++;
			ultimoHasta = hasta;
			return eventos.stream()
					.filter(evento -> !evento.ocurrioEn().isAfter(hasta))
					.sorted(TimelineCursor.ORDEN)
					.limit(limite)
					.toList();
		}

		@Override
		public String origen() {
			return origen;
		}
	}

	private AuditEntry auditado() {
		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		return captor.getValue();
	}

	private TimelineService conFuentes(EventoClinicoContributor... fuentes) {
		return new TimelineService(historias, casos, List.of(fuentes), permissionGuard, relaciones,
				auditTrail, supportAccessAuditor);
	}

	/** Una fuente que siempre devuelve los mismos eventos, sin mirar el tope ni el limite. */
	private static EventoClinicoContributor fija(EventoClinico... eventos) {
		return (organizationId, historiaClinicaId, hasta, limite, casoId) -> List.of(eventos);
	}

	/** Una fuente que ademas recuerda con que argumentos la llamaron. */
	private static final class FuenteEspia implements EventoClinicoContributor {

		private final List<EventoClinico> eventos;
		private Instant hasta;
		private int limite;

		private FuenteEspia(EventoClinico... eventos) {
			this.eventos = List.of(eventos);
		}

		@Override
		public List<EventoClinico> eventosDe(
				long organizationId, long historiaClinicaId, Instant hasta, int limite, Long casoId) {

			this.hasta = hasta;
			this.limite = limite;
			return eventos;
		}
	}

	private static EventoClinico evento(Instant cuando, String origen, long referencia) {
		return new EventoClinico(cuando, origen, "TIPO", "Etiqueta", referencia);
	}

	private static HistoriaClinica historia() {
		HistoriaClinica historia =
				new HistoriaClinica(ORG_ID, PERSONA_ID, Instant.now(), ACCOUNT_ID);
		ReflectionTestUtils.setField(historia, "id", HC_ID);
		return historia;
	}
}
