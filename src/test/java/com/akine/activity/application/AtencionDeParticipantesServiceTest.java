package com.akine.activity.application;

import com.akine.activity.domain.AsistenciaActividad;
import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.InscripcionClase;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.activity.domain.exception.OfertaNoClinicaException;
import com.akine.activity.domain.exception.ParticipacionSinAsistenciaException;
import com.akine.activity.domain.port.ActivityRepositoryPorts.AsistenciaActividadRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseProgramadaRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.InscripcionClaseRepositoryPort;
import com.akine.encounter.spi.AtencionDeParticipante;
import com.akine.encounter.spi.AtencionGrupal;
import com.akine.encounter.spi.PedidoDeAtencion;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.problem.ConTipoDeProblema;
import com.akine.platform.spi.problem.ProblemType;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * El lado grupal de la atencion clinica de participantes (AKINE-08.05). Lo clinico es de
 * {@code encounter} y {@code clinical} y aca es un mock: lo que se prueba es que {@code activity}
 * corte lo que le toca antes de pedir nada, que pida lo correcto, y que el lote no esconda a nadie.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtencionDeParticipantesServiceTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 7L;
	private static final long CLASE_ID = 640L;
	private static final long OFERTA_ID = 42L;
	private static final long CUENTA_ID = 91L;
	private static final long PERSONA_ID = 77L;
	private static final long OTRA_PERSONA_ID = 78L;
	private static final Instant INICIO = Instant.parse("2026-10-16T13:00:00Z");

	@Mock private ClaseProgramadaRepositoryPort clases;
	@Mock private InscripcionClaseRepositoryPort inscripciones;
	@Mock private AsistenciaActividadRepositoryPort asistencias;
	@Mock private OfertaDirectory ofertas;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AtencionGrupal atenciones;
	@Mock private PlatformTransactionManager transactionManager;

	private AtencionDeParticipantesService service;

	@BeforeEach
	void setUp() {
		service = new AtencionDeParticipantesService(clases, inscripciones, asistencias, ofertas,
				consultorios, permissionGuard, atenciones, transactionManager);
		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(SEDE_ID, ORG_ID, "Sede Centro", "UTC", true)));
		given(clases.findByIdInScope(ORG_ID, SEDE_ID, CLASE_ID)).willReturn(Optional.of(clase()));
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(Optional.of(oferta(true)));
	}

	@Test
	@DisplayName("una clase no clinica corta con oferta-no-clinica y encounter no se entera (RN-M14-008)")
	void clase_no_clinica() {
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(Optional.of(oferta(false)));

		assertThatThrownBy(() -> service.iniciar(actor(), SEDE_ID, CLASE_ID, 1L, null, null))
				.isInstanceOf(OfertaNoClinicaException.class);
		assertThatThrownBy(() -> service.iniciarLote(actor(), SEDE_ID, CLASE_ID,
				List.of(new PedidoDeAtencionDeParticipante(1L, null)), null))
				.isInstanceOf(OfertaNoClinicaException.class);

		PanelDeAtencionGrupalView panel = service.panel(actor(), SEDE_ID, CLASE_ID);
		assertThat(panel.clinica()).isFalse();
		verifyNoInteractions(atenciones);
	}

	@Test
	@DisplayName("no se atiende a quien no vino: ausente es participacion-sin-asistencia")
	void ausente() {
		participante(1L, PERSONA_ID, 931L, ResultadoAsistencia.AUSENTE);

		assertThatThrownBy(() -> service.iniciar(actor(), SEDE_ID, CLASE_ID, 1L, null, null))
				.isInstanceOf(ParticipacionSinAsistenciaException.class);
		verifyNoInteractions(atenciones);
	}

	@Test
	@DisplayName("pide la atencion con la asistencia como ancla, la persona de la inscripcion y la justificacion")
	void pide_lo_correcto() {
		participante(1L, PERSONA_ID, 931L, ResultadoAsistencia.PRESENTE_TARDE);
		given(atenciones.iniciar(any())).willReturn(atencion(501L, 931L, false));

		AtencionDeParticipanteView vista =
				service.iniciar(actor(), SEDE_ID, CLASE_ID, 1L, 88L, "Indicacion sintetica");

		ArgumentCaptor<PedidoDeAtencion> pedido = ArgumentCaptor.forClass(PedidoDeAtencion.class);
		verify(atenciones).iniciar(pedido.capture());
		assertThat(pedido.getValue().participacionId()).isEqualTo(931L);
		assertThat(pedido.getValue().personaId()).isEqualTo(PERSONA_ID);
		assertThat(pedido.getValue().claseId()).isEqualTo(CLASE_ID);
		assertThat(pedido.getValue().derivacionId()).isEqualTo(88L);
		assertThat(pedido.getValue().actor().consultorioId()).isEqualTo(SEDE_ID);
		assertThat(pedido.getValue().actor().justificacion()).isEqualTo("Indicacion sintetica");
		assertThat(vista.inscripcionId()).isEqualTo(1L);
		assertThat(vista.atencion().sesionId()).isEqualTo(501L);
	}

	@Test
	@DisplayName("el lote no esconde a nadie: un exito, un error tipado de otro modulo y uno no previsto")
	void lote_con_exito_parcial() {
		participante(1L, PERSONA_ID, 931L, ResultadoAsistencia.PRESENTE);
		participante(2L, OTRA_PERSONA_ID, 932L, ResultadoAsistencia.PRESENTE);
		participante(3L, 79L, 933L, ResultadoAsistencia.PRESENTE);
		given(atenciones.iniciar(any())).willAnswer(invocacion -> {
			PedidoDeAtencion pedido = invocacion.getArgument(0);
			if (pedido.participacionId() == 932L) {
				throw new SinDerivacion();
			}
			if (pedido.participacionId() == 933L) {
				throw new IllegalStateException("detalle interno que no tiene que viajar");
			}
			return atencion(501L, 931L, false);
		});

		ResultadoDeAtencionGrupal resultado = service.iniciarLote(actor(), SEDE_ID, CLASE_ID, List.of(
				new PedidoDeAtencionDeParticipante(1L, null),
				new PedidoDeAtencionDeParticipante(2L, null),
				new PedidoDeAtencionDeParticipante(3L, null)), null);

		assertThat(resultado.exitosos()).isEqualTo(1);
		assertThat(resultado.fallidos()).isEqualTo(2);
		assertThat(resultado.items().get(0).atencion().sesionId()).isEqualTo(501L);
		assertThat(resultado.items().get(1).problemType())
				.isEqualTo(ProblemType.PARTICIPACION_SIN_DERIVACION.value());
		assertThat(resultado.items().get(2).problemType()).isEqualTo(ProblemType.INTERNAL_ERROR.value());
		assertThat(resultado.items().get(2).detalle()).doesNotContain("detalle interno");
	}

	@Test
	@DisplayName("el panel agrupa las atenciones por asistencia y deja vacio a quien no tiene")
	void panel() {
		AsistenciaActividad conAtencion = asistencia(inscripcion(1L, PERSONA_ID), 931L,
				ResultadoAsistencia.PRESENTE);
		AsistenciaActividad sinAtencion = asistencia(inscripcion(2L, OTRA_PERSONA_ID), 932L,
				ResultadoAsistencia.AUSENTE);
		given(asistencias.findDeLaClase(ORG_ID, CLASE_ID)).willReturn(List.of(conAtencion, sinAtencion));
		given(atenciones.deLaClase(any(), anyLong())).willReturn(List.of(atencion(501L, 931L, false)));

		PanelDeAtencionGrupalView panel = service.panel(actor(), SEDE_ID, CLASE_ID);

		assertThat(panel.clinica()).isTrue();
		assertThat(panel.participantes()).hasSize(2);
		assertThat(panel.participantes().get(0).atenciones()).extracting(AtencionDeParticipante::sesionId)
				.containsExactly(501L);
		assertThat(panel.participantes().get(1).estuvo()).isFalse();
		assertThat(panel.participantes().get(1).atenciones()).isEmpty();
	}

	// =================================================================================

	/** Una excepcion de otro modulo que el lote no puede importar: se reconoce por su tipo. */
	private static final class SinDerivacion extends RuntimeException implements ConTipoDeProblema {
		SinDerivacion() {
			super("sin derivacion vigente");
		}

		@Override
		public ProblemType tipoDeProblema() {
			return ProblemType.PARTICIPACION_SIN_DERIVACION;
		}
	}

	private static OperatingActor actor() {
		return new OperatingActor(CUENTA_ID, false, ORG_ID, SEDE_ID);
	}

	private void participante(long inscripcionId, long personaId, long asistenciaId,
			ResultadoAsistencia resultado) {
		InscripcionClase inscripcion = inscripcion(inscripcionId, personaId);
		given(inscripciones.findByIdInScope(ORG_ID, CLASE_ID, inscripcionId))
				.willReturn(Optional.of(inscripcion));
		given(asistencias.findDeInscripcionParaDerivar(ORG_ID, inscripcionId))
				.willReturn(Optional.of(asistencia(inscripcion, asistenciaId, resultado)));
	}

	private static ClaseProgramada clase() {
		ClaseProgramada clase = new ClaseProgramada(
				ORG_ID, SEDE_ID, OFERTA_ID, 31L, null, "Rehabilitacion grupal",
				INICIO, INICIO.plus(1, ChronoUnit.HOURS), 8, CUENTA_ID, Instant.now(), null, null);
		ReflectionTestUtils.setField(clase, "id", CLASE_ID);
		return clase;
	}

	private static OfertaSnapshot oferta(boolean clinica) {
		return new OfertaSnapshot(OFERTA_ID, ORG_ID, SEDE_ID, 5L, "Grupal", 60, 8, true, false,
				false, clinica, clinica, LocalDate.of(2020, 1, 1), null, true);
	}

	private static InscripcionClase inscripcion(long id, long personaId) {
		InscripcionClase inscripcion = InscripcionClase.conLugar(
				ORG_ID, SEDE_ID, CLASE_ID, personaId, CUENTA_ID, Instant.now(), null, null);
		ReflectionTestUtils.setField(inscripcion, "id", id);
		return inscripcion;
	}

	private static AsistenciaActividad asistencia(
			InscripcionClase inscripcion, long id, ResultadoAsistencia resultado) {
		AsistenciaActividad asistencia = AsistenciaActividad.registrar(
				inscripcion, resultado, OrigenAsistencia.MOSTRADOR, 31L, null, CUENTA_ID, Instant.now());
		ReflectionTestUtils.setField(asistencia, "id", id);
		return asistencia;
	}

	private static AtencionDeParticipante atencion(long sesionId, long participacionId, boolean yaExistia) {
		return new AtencionDeParticipante(sesionId, SEDE_ID, CLASE_ID, participacionId, 88L, 301L, 55L,
				31L, "BORRADOR", null, null, Instant.now(), null, 0L, yaExistia);
	}
}
