package com.akine.clinical.application;

import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.domain.exception.PacienteSinPerfilVigenteException;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.AntecedenteClinicoRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
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
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * La Historia Clinica organizacional: apertura idempotente, precondicion de paciente y auditoria
 * de la lectura.
 *
 * <p>Los cuatro invariantes que este test fija son los que hacen que 04.01 sea un cimiento y no
 * una tabla: una sola historia por paciente y organizacion, ninguna historia sobre alguien que no
 * es paciente, ninguna lectura sin rastro, y ninguna escritura que pise otra en silencio.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("HistoriaClinicaService")
class HistoriaClinicaServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;
	private static final long HC_ID = 700L;

	@Mock
	private HistoriaClinicaRepositoryPort historias;

	@Mock
	private AntecedenteClinicoRepositoryPort antecedentes;

	@Mock
	private PacienteDirectory pacientes;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private RelacionAsistencialProbe relaciones;

	@Mock
	private AuditTrail auditTrail;

	@Mock
	private ClinicalSupportAccessAuditor supportAccessAuditor;

	@Mock
	private HistoriaClinicaEscrituraAparte escrituraAparte;

	private HistoriaClinicaService service;

	private final OperatingActor profesional = new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		service = new HistoriaClinicaService(historias, antecedentes, pacientes, permissionGuard,
				relaciones, List.of(), auditTrail, supportAccessAuditor, escrituraAparte);

		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
		given(relaciones.tieneRelacionAsistencial(anyLong(), anyLong(), anyLong(), anyLong()))
				.willReturn(true);
		given(pacientes.find(ORG_ID, PERSONA_ID)).willReturn(Optional.of(paciente(true, true)));
		given(historias.buscarVigentePorPersona(ORG_ID, PERSONA_ID)).willReturn(Optional.empty());
		// El INSERT ya no lo hace el servicio: lo hace el colaborador que corre en su propia
		// transaccion, porque un choque contra el unique marca rollbackOnly la transaccion en la
		// que ocurre y atraparlo ahi no la des-marca.
		given(escrituraAparte.insertar(any())).willAnswer(i -> conId(i.getArgument(0)));
		given(historias.save(any())).willAnswer(i -> i.getArgument(0));
		given(antecedentes.buscarDeHistoria(anyLong(), anyLong(), any(), anyBoolean()))
				.willReturn(List.of());
	}

	// =================================================================================
	// Apertura
	// =================================================================================

	@Test
	@DisplayName("Abrir crea la historia y la audita")
	void la_apertura_deja_su_rastro() {
		HistoriaClinicaView vista = service.abrirOObtener(profesional, PERSONA_ID, null);

		assertThat(vista.id()).isEqualTo(HC_ID);
		assertThat(vista.personaId()).isEqualTo(PERSONA_ID);

		ArgumentCaptor<AuditEntry> fila = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(fila.capture());
		assertThat(fila.getValue().eventType()).isEqualTo("HISTORIA_CLINICA_OPENED");
		assertThat(fila.getValue().newState()).isEqualTo("ABIERTA");
		assertThat(fila.getValue().details()).containsEntry("viaDeAcceso", "RELACION_ASISTENCIAL");
	}

	@Test
	@DisplayName("Abrir dos veces devuelve la misma historia y NO audita de nuevo")
	void la_apertura_es_idempotente() {
		// Un boton tocado dos veces o un request reintentado tras un timeout son el mismo pedido.
		// Auditar la segunda llenaria el registro de aperturas que nunca ocurrieron.
		given(historias.buscarVigentePorPersona(ORG_ID, PERSONA_ID))
				.willReturn(Optional.of(historiaExistente()));

		HistoriaClinicaView vista = service.abrirOObtener(profesional, PERSONA_ID, null);

		assertThat(vista.id()).isEqualTo(HC_ID);
		verify(escrituraAparte, never()).insertar(any());
		verify(auditTrail, never()).record(any());
	}

	@Test
	@DisplayName("Dos aperturas simultaneas no producen dos historias: gana el unique")
	void la_carrera_la_resuelve_el_unique() {
		// La segunda capa de idempotencia, la unica que de verdad protege: el pre-chequeo tiene
		// ventana de carrera y el unique de V32 no.
		given(historias.buscarVigentePorPersona(ORG_ID, PERSONA_ID))
				.willReturn(Optional.empty())
				.willReturn(Optional.of(historiaExistente()));
		willThrow(new DataIntegrityViolationException("uk_historia_clinica_persona_vigente"))
				.given(escrituraAparte).insertar(any());

		HistoriaClinicaView vista = service.abrirOObtener(profesional, PERSONA_ID, null);

		assertThat(vista.id()).isEqualTo(HC_ID);
		verify(auditTrail, never()).record(any());
		// Y el INSERT NO sale de la transaccion de negocio: si saliera de ahi, el choque contra el
		// unique la marcaria rollbackOnly y la relectura de la ganadora correria sobre una sesion
		// muerta — 500 en vez de idempotencia. Ver HistoriaClinicaEscrituraAparte.
		verify(escrituraAparte).insertar(any());
		verify(historias, never()).saveAndFlush(any());
	}

	// =================================================================================
	// La precondicion que fija el recableado de DP-10
	// =================================================================================

	@Test
	@DisplayName("Una persona sin perfil de paciente vigente NO recibe historia clinica")
	void sin_perfil_de_paciente_no_hay_historia() {
		// Es RF-M07-010 sostenido desde el otro lado: alguien que solo se inscribe a una clase no
		// puede terminar con historia clinica porque un camino se olvido de mirar el perfil.
		given(pacientes.find(ORG_ID, PERSONA_ID)).willReturn(Optional.of(paciente(true, false)));

		assertThatThrownBy(() -> service.abrirOObtener(profesional, PERSONA_ID, null))
				.isInstanceOf(PacienteSinPerfilVigenteException.class);

		verify(escrituraAparte, never()).insertar(any());
	}

	@Test
	@DisplayName("Una ficha del padron dada de baja tampoco admite abrir historia")
	void una_persona_inactiva_no_admite_apertura() {
		given(pacientes.find(ORG_ID, PERSONA_ID)).willReturn(Optional.of(paciente(false, true)));

		assertThatThrownBy(() -> service.abrirOObtener(profesional, PERSONA_ID, null))
				.isInstanceOf(PacienteSinPerfilVigenteException.class);
	}

	@Test
	@DisplayName("Una persona de otra organizacion se trata como inexistente, no como prohibida")
	void cross_tenant_es_inexistente() {
		// Un 403 confirmaria que existe: bastaria probar ids consecutivos para censar el padron
		// clinico de otro centro.
		given(pacientes.find(ORG_ID, PERSONA_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.abrirOObtener(profesional, PERSONA_ID, null))
				.isInstanceOf(HistoriaClinicaNotAccessibleException.class);
	}

	// =================================================================================
	// Lectura
	// =================================================================================

	@Test
	@DisplayName("Cada lectura de la historia deja un evento de auditoria con su via de acceso")
	void la_lectura_se_audita() {
		// En el resto del sistema solo se auditan las mutaciones. Aca no alcanza: DP-03 exige
		// auditar todo acceso clinico sensible, y el riesgo de una historia esta mas en quien la
		// lee sin motivo que en quien la modifica.
		given(historias.buscarVigentePorPersona(ORG_ID, PERSONA_ID))
				.willReturn(Optional.of(historiaExistente()));
		given(relaciones.tieneRelacionAsistencial(anyLong(), anyLong(), anyLong(), anyLong()))
				.willReturn(false);

		service.ver(profesional, PERSONA_ID, "interconsulta autorizada");

		ArgumentCaptor<AuditEntry> fila = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(fila.capture());
		assertThat(fila.getValue().eventType()).isEqualTo("HISTORIA_CLINICA_ACCESSED");
		assertThat(fila.getValue().details()).containsEntry("viaDeAcceso", "JUSTIFICACION");
		assertThat(fila.getValue().reason()).isEqualTo("interconsulta autorizada");
	}

	@Test
	@DisplayName("El timeline viene vacio mientras no haya contribuyentes, y la lista existe igual")
	void el_timeline_esta_cableado_y_vacio() {
		// Es la costura hacia 04.02, que DP-10 corto. Que la lista exista desde ahora es lo que
		// evita que el consumidor cambie de forma cuando el timeline llegue.
		given(historias.buscarVigentePorPersona(ORG_ID, PERSONA_ID))
				.willReturn(Optional.of(historiaExistente()));

		assertThat(service.ver(profesional, PERSONA_ID, null).eventos()).isEmpty();
	}

	// =================================================================================
	// Resumen
	// =================================================================================

	@Test
	@DisplayName("Guardar con una version vieja no pisa el cambio ajeno")
	void el_control_optimista_protege_el_resumen() {
		HistoriaClinica historia = historiaExistente();
		ReflectionTestUtils.setField(historia, "version", 3L);
		given(historias.buscarVigentePorPersona(ORG_ID, PERSONA_ID)).willReturn(Optional.of(historia));

		assertThatThrownBy(() ->
				service.actualizarResumen(profesional, PERSONA_ID, "texto", 2L, null))
				.isInstanceOf(OptimisticLockingFailureException.class);

		verify(historias, never()).save(any());
	}

	@Test
	@DisplayName("El texto del resumen NO se copia a la auditoria")
	void la_auditoria_no_filtra_contenido_clinico() {
		// `audit_event` se consulta con auditoria:read, que no es un permiso clinico. Copiar el
		// resumen ahi convertiria la auditoria en una via de lectura clinica sin permiso clinico.
		given(historias.buscarVigentePorPersona(ORG_ID, PERSONA_ID))
				.willReturn(Optional.of(historiaExistente()));

		service.actualizarResumen(profesional, PERSONA_ID, "paciente con dolor lumbar", 0L, null);

		ArgumentCaptor<AuditEntry> fila = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(fila.capture());
		assertThat(fila.getValue().details().values()).doesNotContain("paciente con dolor lumbar");
		assertThat(fila.getValue().previousState()).isEqualTo("SIN_RESUMEN");
		assertThat(fila.getValue().newState()).isEqualTo("CON_RESUMEN");
	}

	@Test
	@DisplayName("La proyeccion de solo metadatos no deja pasar una linea de contenido clinico")
	void la_proyeccion_limitada_recorta_lo_clinico() {
		// Es el "Limitado" del ADMINISTRATIVO en la matriz §4, dejado representado sin llamador:
		// hoy ese rol no recibe hc:read porque no hay codigo de permiso que exprese el recorte.
		// El dia que exista, esta es la proyeccion que tiene que devolver.
		given(historias.buscarVigentePorPersona(ORG_ID, PERSONA_ID))
				.willReturn(Optional.of(historiaExistente()));
		service.actualizarResumen(profesional, PERSONA_ID, "dolor lumbar", 0L, null);

		HistoriaClinicaView completa = service.ver(profesional, PERSONA_ID, null);
		HistoriaClinicaView limitada = completa.soloMetadatos();

		assertThat(limitada.resumen()).isNull();
		assertThat(limitada.antecedentes()).isEmpty();
		assertThat(limitada.personaNombreCompleto())
				.as("los metadatos administrativos si viajan: es lo que la celda permite")
				.isEqualTo("Perez, Ana");
	}

	// =================================================================================
	// El camino del spi
	// =================================================================================

	@Test
	@DisplayName("asegurar() saltea el permiso pero NO la precondicion de paciente")
	void el_camino_de_otro_modulo_no_puede_crear_pacientes_de_hecho() {
		// Es el agujero que el spi podria abrir: si `asegurar` no mirara el perfil, el primer
		// modulo que registre una prestacion sobre alguien que solo es "persona" le crearia
		// historia clinica sin que nadie lo decidiera.
		given(pacientes.find(ORG_ID, PERSONA_ID)).willReturn(Optional.of(paciente(true, false)));

		assertThatThrownBy(() -> service.asegurar(ORG_ID, PERSONA_ID, ACCOUNT_ID))
				.isInstanceOf(PacienteSinPerfilVigenteException.class);
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static PacienteSnapshot paciente(boolean activa, boolean esPaciente) {
		return new PacienteSnapshot(PERSONA_ID, ORG_ID, "Perez", "Ana", "DNI", "30111222",
				LocalDate.of(1990, 1, 1), activa, esPaciente, esPaciente ? 900L : null,
				esPaciente ? Instant.now() : null);
	}

	private static HistoriaClinica historiaExistente() {
		return conId(new HistoriaClinica(ORG_ID, PERSONA_ID, Instant.now(), ACCOUNT_ID));
	}

	private static HistoriaClinica conId(HistoriaClinica historia) {
		ReflectionTestUtils.setField(historia, "id", HC_ID);
		return historia;
	}
}
