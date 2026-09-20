package com.akine.activity.application;

import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseEventoRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseProgramadaRepositoryPort;
import com.akine.activity.domain.exception.CapacidadNoAdmitidaException;
import com.akine.activity.domain.exception.ClaseNoProgramableException;
import com.akine.activity.domain.exception.RecursoOcupadoException;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.spi.DisponibilidadDirectory;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import com.akine.scheduling.spi.AgendaDeSede;
import com.akine.scheduling.spi.OcupacionDeAgenda;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;

/**
 * Las reglas de AKINE-08.01 que cuestan caro si se rompen.
 *
 * <p><b>Cinco casos y no cuarenta, a proposito.</b> Lo que esta etapa necesita probar de verdad es
 * la exclusion concurrente entre una clase y un turno, y <b>eso no se puede probar aca</b>: un mock
 * que devuelve cero filas no reproduce el gestor de locks de InnoDB. Escribir veinte tests con
 * mocks alrededor de esa carrera daria una sensacion de cobertura sobre lo unico que los mocks no
 * pueden contestar. Queda anotado en {@code docs/tests-diferidos.md}, no simulado.
 *
 * <p>Lo que si se prueba aca es lo que un unitario decide sin ambiguedad: el ORDEN de las llamadas
 * al lock —que es lo que hace confiable a todo lo demas— y las cuatro reglas de dominio que un
 * refactor puede perder en silencio.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ClaseService")
class ClaseServiceTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 10L;
	private static final long OFERTA_ID = 45L;
	private static final long PROFESIONAL_ID = 31L;
	private static final long ESPACIO_ID = 8L;
	private static final long CUENTA_ID = 99L;

	private static final Instant INICIO = Instant.parse("2026-10-05T12:00:00Z");
	private static final Instant FIN = Instant.parse("2026-10-05T13:00:00Z");

	@Mock private ClaseProgramadaRepositoryPort clases;
	@Mock private ClaseEventoRepositoryPort eventos;
	@Mock private AgendaDeSede agenda;
	@Mock private OfertaDirectory ofertas;
	@Mock private DisponibilidadDirectory disponibilidad;
	@Mock private EspacioDirectory espacios;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AuditTrail auditTrail;

	private ClaseService service;

	@BeforeEach
	void setUp() {
		service = new ClaseService(clases, eventos, agenda, ofertas, disponibilidad, espacios,
				consultorios, permissionGuard, auditTrail);

		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(sede()));
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(Optional.of(ofertaGrupal(8)));
		given(ofertas.profesionalesHabilitados(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(List.of());
		given(ofertas.espaciosHabilitados(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(List.of());
		given(espacios.enServicio(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(espacio(20)));
		given(espacios.find(ORG_ID, ESPACIO_ID, INICIO)).willReturn(Optional.of(espacio(20)));
		given(disponibilidad.efectiva(anyLong(), any(), anyLong(), any(), any()))
				.willReturn(List.of(diaConFranjaDe(INICIO, FIN)));
		given(agenda.turnosDeProfesionalQueCruzan(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of());
		given(agenda.turnosDeEspacioQueCruzan(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of());
		given(clases.findVivasDeProfesionalQueCruzan(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of());
		given(clases.findVivasDeEspacioQueCruzan(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of());
		given(clases.save(any())).willAnswer(invocacion -> conId(invocacion.getArgument(0), 77L));
	}

	/**
	 * El orden es lo que hace confiable a todo lo demas.
	 *
	 * <p>{@code asegurar} antes de {@code bloquear} porque crear la fila-lock dentro de la
	 * transaccion que la bloquea produce deadlock entre las primeras N escrituras de una sede. Y
	 * {@code bloquear} antes de leer porque leer y despues bloquear es una escalada S -&gt; X: dos
	 * transacciones se quedan cada una con su lock compartido esperando el exclusivo de la otra.
	 *
	 * <p>Las dos reglas se pierden al refactorizar sin que nada falle en un entorno de un solo
	 * hilo, que es precisamente por que hay un test que las fija.
	 */
	@Test
	@DisplayName("Toma el lock de la sede ANTES de leer un solo turno o una sola clase")
	void bloquea_antes_de_leer() {
		service.programar(actor(), SEDE_ID, OFERTA_ID, comando(8, null));

		InOrder orden = inOrder(agenda, clases);
		orden.verify(agenda).asegurar(ORG_ID, SEDE_ID);
		orden.verify(agenda).bloquear(ORG_ID, SEDE_ID);
		orden.verify(agenda).turnosDeProfesionalQueCruzan(ORG_ID, PROFESIONAL_ID, INICIO, FIN);
		orden.verify(clases).save(any());
	}

	/**
	 * <b>El caso que da sentido a la etapa.</b> Un turno que cruza el horario deja el profesional
	 * ocupado, y la clase no se programa.
	 *
	 * <p>Sin este control se programa una clase encima de un turno ya vendido: ningun unique puede
	 * atraparlo, porque dos intervalos que se cruzan no comparten un valor de columna.
	 */
	@Test
	@DisplayName("Un turno que se cruza impide programar la clase: recurso-ocupado")
	void un_turno_bloquea_la_clase() {
		given(agenda.turnosDeProfesionalQueCruzan(ORG_ID, PROFESIONAL_ID, INICIO, FIN))
				.willReturn(List.of(new OcupacionDeAgenda(
						"TURNO", 5L, PROFESIONAL_ID, ESPACIO_ID,
						INICIO.plusSeconds(1800), FIN.plusSeconds(1800))));

		assertThatThrownBy(() -> service.programar(actor(), SEDE_ID, OFERTA_ID, comando(8, null)))
				.isInstanceOf(RecursoOcupadoException.class)
				.hasMessageContaining("profesional");
	}

	/**
	 * RN-M28-001, y la trampa que lo acompana: <b>GRUPAL no se deduce de la capacidad</b>. V24
	 * obliga a que una oferta GRUPAL tenga capacidad mayor a 1, pero NO la reciproca — una oferta
	 * individual en un box de dos camillas puede tener capacidad 2 y sigue siendo individual.
	 *
	 * <p>Por eso esta oferta tiene capacidad 4 y no es grupal: si el codigo dedujera la modalidad
	 * del cupo, este test pasaria en verde con el defecto adentro.
	 */
	@Test
	@DisplayName("Una oferta INDIVIDUAL no sostiene una clase, aunque su capacidad sea mayor a 1")
	void la_oferta_tiene_que_ser_grupal() {
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA_ID))
				.willReturn(Optional.of(oferta(4, false)));

		assertThatThrownBy(() -> service.programar(actor(), SEDE_ID, OFERTA_ID, comando(4, null)))
				.isInstanceOf(ClaseNoProgramableException.class)
				.hasMessageContaining("no es una oferta grupal");
	}

	/**
	 * RN-M28-002: la capacidad propia queda limitada <b>ademas</b> por la del espacio. El box
	 * admite 5 y la clase pide 8.
	 *
	 * <p>El maximo viaja en la excepcion para que la pantalla corrija el numero sola en vez de solo
	 * mostrar el error.
	 */
	@Test
	@DisplayName("La capacidad del espacio limita la de la clase, y el maximo viaja en el error")
	void el_espacio_limita_la_capacidad() {
		given(espacios.enServicio(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(espacio(5)));
		given(espacios.find(ORG_ID, ESPACIO_ID, INICIO)).willReturn(Optional.of(espacio(5)));

		assertThatThrownBy(() -> service.programar(actor(), SEDE_ID, OFERTA_ID, comando(8, null)))
				.isInstanceOf(CapacidadNoAdmitidaException.class)
				.extracting(error -> ((CapacidadNoAdmitidaException) error).getCapacidadMaxima())
				.isEqualTo(5);
	}

	/**
	 * CA-M28-006-06: "una segunda ejecucion no devuelve creditos ni dinero dos veces".
	 *
	 * <p>Hoy no hay creditos que devolver, y por eso importa fijarlo ahora: cuando 08.02 y 08.07
	 * cuelguen de esta operacion las reversas economicas, la idempotencia deja de ser una comodidad
	 * de pantalla. Lo que se verifica es que la segunda cancelacion <b>no registre un segundo
	 * evento</b>, que es la forma observable de que no repitio el efecto.
	 */
	@Test
	@DisplayName("Cancelar dos veces no registra un segundo evento de historial")
	void cancelar_es_idempotente() {
		ClaseProgramada clase = conId(nuevaClase(8), 77L);
		given(clases.findByIdInScope(ORG_ID, SEDE_ID, 77L)).willReturn(Optional.of(clase));
		given(clases.saveAndFlush(any())).willAnswer(invocacion -> invocacion.getArgument(0));

		service.cancelar(actor(), SEDE_ID, 77L, "El instructor se reporto enfermo");
		var segunda = service.cancelar(actor(), SEDE_ID, 77L, "otro motivo distinto");

		org.mockito.Mockito.verify(eventos, org.mockito.Mockito.times(1)).registrar(any());
		assertThat(segunda.estado()).isEqualTo("CANCELADA");
		// El motivo es el de la PRIMERA cancelacion: la segunda no reescribio la historia.
		assertThat(segunda.motivoCancelacion()).isEqualTo("El instructor se reporto enfermo");
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static OperatingActor actor() {
		return new OperatingActor(CUENTA_ID, false, ORG_ID, SEDE_ID);
	}

	private static ProgramarClaseCommand comando(int capacidad, String idempotencyKey) {
		return new ProgramarClaseCommand(
				INICIO, FIN, PROFESIONAL_ID, capacidad, "Pilates - avanzado", idempotencyKey);
	}

	private static ClaseProgramada nuevaClase(int capacidad) {
		return new ClaseProgramada(ORG_ID, SEDE_ID, OFERTA_ID, PROFESIONAL_ID, ESPACIO_ID,
				"Pilates - avanzado", INICIO, FIN, capacidad, CUENTA_ID,
				Instant.parse("2026-10-01T09:00:00Z"), null, null);
	}

	private static ClaseProgramada conId(ClaseProgramada clase, long id) {
		ReflectionTestUtils.setField(clase, "id", id);
		return clase;
	}

	private static ConsultorioSnapshot sede() {
		return new ConsultorioSnapshot(SEDE_ID, ORG_ID, "Sede Centro", "UTC", true);
	}

	private static OfertaSnapshot ofertaGrupal(int capacidad) {
		return oferta(capacidad, true);
	}

	private static OfertaSnapshot oferta(int capacidad, boolean grupal) {
		return new OfertaSnapshot(OFERTA_ID, ORG_ID, SEDE_ID, 3L, "Pilates", 60, capacidad,
				grupal, true, true, LocalDate.of(2020, 1, 1), null, true);
	}

	private static EspacioSnapshot espacio(int capacidad) {
		return new EspacioSnapshot(ESPACIO_ID, ORG_ID, SEDE_ID, "Salon", "SALON", capacidad,
				Instant.parse("2020-01-01T00:00:00Z"), null, true, true);
	}

	private static DisponibilidadDirectory.DiaDisponible diaConFranjaDe(Instant desde, Instant hasta) {
		return new DisponibilidadDirectory.DiaDisponible(
				LocalDate.ofInstant(desde, java.time.ZoneOffset.UTC),
				null,
				List.of(new DisponibilidadDirectory.Franja(desde, hasta)));
	}
}
