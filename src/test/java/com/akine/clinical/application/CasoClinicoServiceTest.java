package com.akine.clinical.application;

import com.akine.clinical.domain.CasoClinico;
import com.akine.clinical.domain.CasoEvento;
import com.akine.clinical.domain.CasoProfesional;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.RolEnCaso;
import com.akine.clinical.domain.TipoEventoCaso;
import com.akine.clinical.domain.exception.CasoClinicoCerradoException;
import com.akine.clinical.domain.exception.CasoClinicoNotAccessibleException;
import com.akine.clinical.domain.exception.CasoClinicoPosibleDuplicadoException;
import com.akine.clinical.domain.exception.CierreDeCasoSinMotivoException;
import com.akine.clinical.domain.exception.OfertaNoVigenteException;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoClinicoRepositoryPort;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoEventoRepositoryPort;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoNumeradorPort;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoProfesionalRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * El Caso Clinico: se abre con correlativo atomico, se cierra con motivo y no se borra.
 *
 * <p>Lo que estos tests fijan son las cuatro cosas que cuestan caro si se rompen: que el
 * correlativo salga del numerador y que su fila se asegure <b>antes</b> de bloquearla, que el
 * duplicado razonable se pueda confirmar en vez de rechazarse, que cerrar exija motivo y no borre,
 * y que el que sale del equipo conserve su fila.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CasoClinicoService")
class CasoClinicoServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;
	private static final long HC_ID = 700L;
	private static final long CASO_ID = 900L;
	private static final long OFERTA_ID = 42L;

	@Mock
	private HistoriaClinicaRepositoryPort historias;

	@Mock
	private CasoClinicoRepositoryPort casos;

	@Mock
	private CasoProfesionalRepositoryPort equipos;

	@Mock
	private CasoEventoRepositoryPort eventos;

	@Mock
	private CasoNumeradorPort numerador;

	@Mock
	private CasoNumeradorIniciador numeradorIniciador;

	@Mock
	private OfertaDirectory ofertas;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private RelacionAsistencialProbe relaciones;

	@Mock
	private AuditTrail auditTrail;

	@Mock
	private ClinicalSupportAccessAuditor supportAccessAuditor;

	private CasoClinicoService service;

	private final OperatingActor profesional =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		service = new CasoClinicoService(historias, casos, equipos, eventos, numerador,
				numeradorIniciador, ofertas, permissionGuard, relaciones, auditTrail,
				supportAccessAuditor);

		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
		given(relaciones.tieneRelacionAsistencial(anyLong(), anyLong(), anyLong(), anyLong()))
				.willReturn(true);
		given(historias.findByIdAndOrganizationId(HC_ID, ORG_ID))
				.willReturn(Optional.of(historia()));
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(Optional.of(oferta(true)));
		given(numerador.leerUltimo(ORG_ID, HC_ID)).willReturn(3);
		given(casos.save(any())).willAnswer(i -> conId(i.getArgument(0), CASO_ID));
		// JPA asigna el id al persistir; el doble tiene que hacer lo mismo o el fixture
		// representaria una participacion guardada sin id, que en produccion no ocurre.
		given(equipos.saveAll(any())).willAnswer(i -> {
			List<CasoProfesional> guardadas = i.getArgument(0);
			guardadas.forEach(participacion -> {
				if (participacion.getId() == null) {
					ReflectionTestUtils.setField(participacion, "id", 1L);
				}
			});
			return guardadas;
		});
		given(equipos.buscarDeCaso(anyLong(), anyLong(), anyBoolean())).willReturn(List.of());
		given(eventos.save(any())).willAnswer(i -> i.getArgument(0));
	}

	// =================================================================================
	// Alta
	// =================================================================================

	@Test
	@DisplayName("el correlativo sale del numerador, y su fila se asegura ANTES de bloquearla")
	void el_correlativo_sale_del_numerador() {
		// Es la trampa que este repositorio ya pago cuatro veces: crear la fila del numerador
		// DENTRO de la transaccion que despues la bloquea produce deadlock, y el try/catch no
		// salva porque atrapar una excepcion de persistencia no des-marca la transaccion.
		CasoClinicoView vista = service.abrir(profesional, alta(List.of(), false), null);

		InOrder orden = inOrder(numeradorIniciador, numerador);
		orden.verify(numeradorIniciador).asegurarCasos(ORG_ID, HC_ID);
		orden.verify(numerador).incrementar(ORG_ID, HC_ID);
		orden.verify(numerador).leerUltimo(ORG_ID, HC_ID);

		assertThat(vista.numeroCaso())
				.as("el numero es el que entrego el numerador, no un MAX+1")
				.isEqualTo(3);
	}

	@Test
	@DisplayName("un caso activo de la misma oferta detiene el alta con los candidatos")
	void posible_duplicado_devuelve_candidatos() {
		// No se rechaza de plano: RN-M10-002 admite varios casos activos y el segundo puede ser
		// correcto. Lo que hace falta es que alguien mire, y para mirar necesita los ids.
		given(casos.buscarActivosPorOferta(ORG_ID, HC_ID, OFERTA_ID))
				.willReturn(List.of(casoExistente()));

		assertThatThrownBy(() -> service.abrir(profesional, alta(List.of(), false), null))
				.isInstanceOf(CasoClinicoPosibleDuplicadoException.class)
				.extracting(e -> ((CasoClinicoPosibleDuplicadoException) e).getCandidatos())
				.isEqualTo(List.of(CASO_ID));

		verify(numerador, never()).incrementar(anyLong(), anyLong());
	}

	@Test
	@DisplayName("el duplicado confirmado entra, y no consume un correlativo antes de decidirlo")
	void duplicado_confirmado_entra() {
		given(casos.buscarActivosPorOferta(ORG_ID, HC_ID, OFERTA_ID))
				.willReturn(List.of(casoExistente()));

		assertThat(service.abrir(profesional, alta(List.of(), true), null).numeroCaso())
				.isEqualTo(3);
	}

	@Test
	@DisplayName("una oferta vencida no puede motivar un caso")
	void oferta_no_vigente() {
		// RN-M10-006: la necesidad de Caso la determina la Oferta efectiva. Un caso apoyado en una
		// oferta que no se puede prestar no tiene sobre que apoyarse.
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(Optional.of(oferta(false)));

		assertThatThrownBy(() -> service.abrir(profesional, alta(List.of(), false), null))
				.isInstanceOf(OfertaNoVigenteException.class);
	}

	@Test
	@DisplayName("el equipo inicial se escribe y la apertura queda en el historial")
	void el_alta_asienta_la_apertura() {
		service.abrir(profesional, alta(
				List.of(new IntegranteDelEquipo(31L, RolEnCaso.RESPONSABLE)), false), null);

		ArgumentCaptor<CasoEvento> evento = ArgumentCaptor.forClass(CasoEvento.class);
		verify(eventos).save(evento.capture());
		assertThat(evento.getValue().getTipo()).isEqualTo(TipoEventoCaso.APERTURA);
		assertThat(evento.getValue().getEstadoAnterior())
				.as("la apertura es el unico evento sin estado anterior: antes no habia estado")
				.isNull();
	}

	// =================================================================================
	// Cierre y reapertura
	// =================================================================================

	@Test
	@DisplayName("cerrar sin motivo es 400 y no 409: falta un dato, no hay conflicto")
	void cerrar_sin_motivo() {
		given(casos.findByIdAndOrganizationId(CASO_ID, ORG_ID))
				.willReturn(Optional.of(casoExistente()));

		assertThatThrownBy(() -> service.cerrar(profesional, CASO_ID, "   ", 0L, null))
				.isInstanceOf(CierreDeCasoSinMotivoException.class);
	}

	@Test
	@DisplayName("cerrar dos veces conserva el motivo original y no es un conflicto")
	void cerrar_es_idempotente() {
		CasoClinico caso = casoExistente();
		caso.cerrar("Alta por objetivos cumplidos", Instant.EPOCH, ACCOUNT_ID);
		given(casos.findByIdAndOrganizationId(CASO_ID, ORG_ID)).willReturn(Optional.of(caso));

		CasoClinicoView vista = service.cerrar(profesional, CASO_ID, "Otra cosa", 99L, null);

		assertThat(vista.motivoCierre())
				.as("pisarlo con el nuevo perderia el que explica el cierre")
				.isEqualTo("Alta por objetivos cumplidos");
		verify(casos, never()).save(any());
	}

	@Test
	@DisplayName("reabrir limpia el cierre pero el cierre anterior queda en el historial")
	void reabrir_deja_evento() {
		CasoClinico caso = casoExistente();
		caso.cerrar("Alta", Instant.EPOCH, ACCOUNT_ID);
		given(casos.findByIdAndOrganizationId(CASO_ID, ORG_ID)).willReturn(Optional.of(caso));

		CasoClinicoView vista = service.reabrir(profesional, CASO_ID, "Recidiva", 0L, null);

		assertThat(vista.estado()).isEqualTo("ACTIVO");
		assertThat(vista.cerradoEn()).isNull();
		assertThat(vista.motivoCierre()).isNull();

		ArgumentCaptor<CasoEvento> evento = ArgumentCaptor.forClass(CasoEvento.class);
		verify(eventos).save(evento.capture());
		assertThat(evento.getValue().getTipo()).isEqualTo(TipoEventoCaso.REAPERTURA);
		assertThat(evento.getValue().getMotivo()).isEqualTo("Recidiva");
	}

	@Test
	@DisplayName("un caso cerrado no se edita: 409, y la salida es reabrirlo")
	void editar_caso_cerrado() {
		CasoClinico caso = casoExistente();
		caso.cerrar("Alta", Instant.EPOCH, ACCOUNT_ID);
		given(casos.findByIdAndOrganizationId(CASO_ID, ORG_ID)).willReturn(Optional.of(caso));

		assertThatThrownBy(() ->
				service.editar(profesional, CASO_ID, "Otro diagnostico", null, 0L, null))
				.isInstanceOf(CasoClinicoCerradoException.class);
	}

	@Test
	@DisplayName("editar con una version vieja es conflicto, no una escritura silenciosa")
	void editar_con_version_vieja() {
		given(casos.findByIdAndOrganizationId(CASO_ID, ORG_ID))
				.willReturn(Optional.of(casoExistente()));

		assertThatThrownBy(() ->
				service.editar(profesional, CASO_ID, "Otro diagnostico", null, 7L, null))
				.isInstanceOf(OptimisticLockingFailureException.class);
	}

	// =================================================================================
	// Equipo
	// =================================================================================

	@Test
	@DisplayName("quien sale del equipo conserva su fila con fecha de salida")
	void el_que_sale_no_se_borra() {
		// Un profesional desvinculado sigue figurando en el caso que trato, porque lo trato
		// (regla maestra 10). Lo que cambia es que deja de poder escribir.
		CasoProfesional saliente = new CasoProfesional(ORG_ID, CASO_ID, 31L, RolEnCaso.TRATANTE,
				Instant.EPOCH);
		ReflectionTestUtils.setField(saliente, "id", 904L);
		given(casos.findWithLockByIdAndOrganizationId(CASO_ID, ORG_ID))
				.willReturn(Optional.of(casoExistente()));
		given(equipos.buscarDeCaso(ORG_ID, CASO_ID, true)).willReturn(List.of(saliente));

		service.cambiarEquipo(profesional, CASO_ID,
				List.of(new IntegranteDelEquipo(77L, RolEnCaso.TRATANTE)), 0L, null);

		assertThat(saliente.getHasta()).as("se le marca la salida, no se borra").isNotNull();
		verify(equipos).saveAll(List.of(saliente));
	}

	@Test
	@DisplayName("el cambio de equipo lee el caso con lock forzado, no con la lectura comun")
	void el_equipo_fuerza_la_version() {
		// Es la leccion de 02.07: un @Version sobre el padre NO protege una escritura que solo
		// toca tablas hijas. Sin OPTIMISTIC_FORCE_INCREMENT, dos cambios de equipo concurrentes
		// commitean los dos y el resultado no es ninguno de los dos.
		given(casos.findWithLockByIdAndOrganizationId(CASO_ID, ORG_ID))
				.willReturn(Optional.of(casoExistente()));

		service.cambiarEquipo(profesional, CASO_ID, List.of(), 0L, null);

		verify(casos).findWithLockByIdAndOrganizationId(CASO_ID, ORG_ID);
		verify(casos, never()).findByIdAndOrganizationId(anyLong(), anyLong());
		verify(casos).save(any());
	}

	// =================================================================================
	// Alcance
	// =================================================================================

	@Test
	@DisplayName("un caso de otro tenant es 404 y no 403")
	void caso_de_otro_tenant() {
		// Un 403 confirmaria que la fila existe, y probar ids consecutivos alcanzaria para censar
		// cuantos casos clinicos tiene otro centro del SaaS.
		given(casos.findByIdAndOrganizationId(CASO_ID, ORG_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.ver(profesional, CASO_ID, null))
				.isInstanceOf(CasoClinicoNotAccessibleException.class);
	}

	// =================================================================================
	// Andamiaje
	// =================================================================================

	private static CasoClinicoAltaCommand alta(
			List<IntegranteDelEquipo> equipo, boolean confirma) {

		return new CasoClinicoAltaCommand(HC_ID, OFERTA_ID, "Gonalgia derecha",
				"Recuperar flexion", equipo, confirma);
	}

	private static CasoClinico casoExistente() {
		CasoClinico caso = new CasoClinico(ORG_ID, HC_ID, 3, OFERTA_ID, SEDE_ID,
				"Gonalgia derecha", null, Instant.EPOCH, ACCOUNT_ID);
		ReflectionTestUtils.setField(caso, "id", CASO_ID);
		return caso;
	}

	private static CasoClinico conId(CasoClinico caso, long id) {
		ReflectionTestUtils.setField(caso, "id", id);
		return caso;
	}

	private static HistoriaClinica historia() {
		HistoriaClinica historia =
				new HistoriaClinica(ORG_ID, PERSONA_ID, Instant.EPOCH, ACCOUNT_ID);
		ReflectionTestUtils.setField(historia, "id", HC_ID);
		return historia;
	}

	private static OfertaSnapshot oferta(boolean vigente) {
		return new OfertaSnapshot(OFERTA_ID, ORG_ID, SEDE_ID, 1L, "Kinesiologia", 45, 1, false,
				true, false, true, true, LocalDate.of(2020, 1, 1),
				vigente ? null : LocalDate.of(2021, 1, 1), true);
	}
}
