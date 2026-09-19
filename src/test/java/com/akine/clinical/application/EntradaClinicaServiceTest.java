package com.akine.clinical.application;

import com.akine.clinical.domain.EntradaClinica;
import com.akine.clinical.domain.EntradaClinicaVersion;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.OrigenEntradaClinica;
import com.akine.clinical.domain.TipoEntradaClinica;
import com.akine.clinical.domain.exception.EnmiendaSinMotivoException;
import com.akine.clinical.domain.exception.EntradaClinicaInactivaException;
import com.akine.clinical.domain.exception.EntradaClinicaNotAccessibleException;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.EntradaClinicaRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.EntradaClinicaVersionRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Entradas clinicas: se enmiendan, no se editan; se dan de baja, no se borran.
 *
 * <p>Lo que estos tests fijan es RF-M09-006 en las dos mitades que cuestan caro si se rompen: que
 * la enmienda <b>agregue</b> una version en vez de pisar la anterior, y que la numeracion salga
 * del contador de la cabecera y no de un MAX.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EntradaClinicaService")
class EntradaClinicaServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;
	private static final long HC_ID = 700L;
	private static final long ENTRADA_ID = 900L;

	@Mock
	private HistoriaClinicaRepositoryPort historias;

	@Mock
	private EntradaClinicaRepositoryPort entradas;

	@Mock
	private EntradaClinicaVersionRepositoryPort versiones;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private RelacionAsistencialProbe relaciones;

	@Mock
	private AuditTrail auditTrail;

	@Mock
	private ClinicalSupportAccessAuditor supportAccessAuditor;

	private EntradaClinicaService service;

	private final OperatingActor profesional = new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		service = new EntradaClinicaService(historias, entradas, versiones, permissionGuard,
				relaciones, auditTrail, supportAccessAuditor);

		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
		given(relaciones.tieneRelacionAsistencial(anyLong(), anyLong(), anyLong(), anyLong()))
				.willReturn(true);
		given(historias.findByIdAndOrganizationId(HC_ID, ORG_ID))
				.willReturn(Optional.of(historia()));
		given(entradas.saveAndFlush(any())).willAnswer(i -> conId(i.getArgument(0), ENTRADA_ID));
		given(entradas.save(any())).willAnswer(i -> i.getArgument(0));
		given(versiones.save(any())).willAnswer(i -> i.getArgument(0));
	}

	@Test
	@DisplayName("registrar escribe la cabecera y su version 1 como original sin motivo")
	void registrar_crea_cabecera_y_version_uno() {
		EntradaClinicaView vista = service.registrar(profesional, HC_ID,
				TipoEntradaClinica.EVOLUCION, "  Refiere dolor lumbar  ", null, null);

		assertThat(vista.numeroVersion()).isEqualTo(1);
		assertThat(vista.cuerpo()).isEqualTo("Refiere dolor lumbar");
		assertThat(vista.motivoEnmienda()).isNull();
		assertThat(vista.enmendada()).isFalse();
		assertThat(vista.origen()).isEqualTo(OrigenEntradaClinica.MANUAL.name());
		assertThat(vista.referenciaOrigen()).isNull();
	}

	@Test
	@DisplayName("el cuerpo de la entrada nunca llega a la auditoria")
	void registrar_no_copia_contenido_clinico_a_la_auditoria() {
		service.registrar(profesional, HC_ID, TipoEntradaClinica.EVOLUCION,
				"Hernia L4-L5 confirmada", null, null);

		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		AuditEntry evento = captor.getValue();

		assertThat(evento.eventType()).isEqualTo("ENTRADA_CLINICA_REGISTERED");
		assertThat(evento.details().values()).noneMatch(v -> v.contains("Hernia"));
	}

	@Test
	@DisplayName("una entrada que declara haber ocurrido en el futuro se rechaza")
	void registrar_rechaza_el_futuro() {
		assertThatThrownBy(() -> service.registrar(profesional, HC_ID,
				TipoEntradaClinica.EVOLUCION, "Algo", Instant.now().plusSeconds(3600), null))
				.isInstanceOf(IllegalArgumentException.class);

		verify(entradas, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("enmendar agrega la version siguiente y deja intacta la anterior")
	void enmendar_agrega_version() {
		EntradaClinica entrada = entradaVigente();
		given(entradas.findWithLockByIdAndOrganizationId(ENTRADA_ID, ORG_ID))
				.willReturn(Optional.of(entrada));

		EntradaClinicaView vista = service.enmendar(profesional, ENTRADA_ID,
				"Texto corregido", "Se cargo en la historia equivocada", 0L, null);

		assertThat(vista.numeroVersion()).isEqualTo(2);
		assertThat(vista.motivoEnmienda()).isEqualTo("Se cargo en la historia equivocada");
		assertThat(vista.enmendada()).isTrue();
		// El contador vive en la cabecera: es lo que numera, no un MAX sobre las versiones.
		assertThat(entrada.getUltimoNumeroVersion()).isEqualTo(2);
		// saveAndFlush y no save: ver enmendar_devuelve_la_version_fresca.
		verify(entradas).saveAndFlush(entrada);
	}

	@Test
	@DisplayName("la enmienda sin motivo se rechaza y no escribe ninguna version")
	void enmendar_sin_motivo_se_rechaza() {
		given(entradas.findWithLockByIdAndOrganizationId(ENTRADA_ID, ORG_ID))
				.willReturn(Optional.of(entradaVigente()));

		assertThatThrownBy(() -> service.enmendar(profesional, ENTRADA_ID, "Texto", "  ", 0L, null))
				.isInstanceOf(EnmiendaSinMotivoException.class);

		verify(versiones, never()).save(any());
	}

	@Test
	@DisplayName("enmendar con una version vieja de la cabecera es conflicto")
	void enmendar_con_version_vieja_es_conflicto() {
		EntradaClinica entrada = entradaVigente();
		ReflectionTestUtils.setField(entrada, "version", 3L);
		given(entradas.findWithLockByIdAndOrganizationId(ENTRADA_ID, ORG_ID))
				.willReturn(Optional.of(entrada));

		assertThatThrownBy(() -> service.enmendar(profesional, ENTRADA_ID, "Texto", "Motivo", 1L, null))
				.isInstanceOf(OptimisticLockingFailureException.class);

		verify(versiones, never()).save(any());
	}

	@Test
	@DisplayName("una entrada dada de baja no admite enmiendas")
	void enmendar_entrada_de_baja_es_conflicto() {
		EntradaClinica entrada = entradaVigente();
		entrada.deactivate(Instant.now(), "Cargada en la historia equivocada");
		given(entradas.findWithLockByIdAndOrganizationId(ENTRADA_ID, ORG_ID))
				.willReturn(Optional.of(entrada));

		assertThatThrownBy(() -> service.enmendar(profesional, ENTRADA_ID, "Texto", "Motivo", 0L, null))
				.isInstanceOf(EntradaClinicaInactivaException.class);

		verify(versiones, never()).save(any());
	}

	@Test
	@DisplayName("repetir la baja no pisa el motivo original")
	void baja_repetida_es_idempotente() {
		EntradaClinica entrada = entradaVigente();
		entrada.deactivate(Instant.now(), "Motivo original");
		given(entradas.findByIdAndOrganizationId(ENTRADA_ID, ORG_ID))
				.willReturn(Optional.of(entrada));
		given(versiones.buscarDeEntrada(ORG_ID, ENTRADA_ID)).willReturn(List.of(version(1, null)));

		EntradaClinicaView vista =
				service.darDeBaja(profesional, ENTRADA_ID, "Otro motivo", 0L, null);

		assertThat(vista.vigente()).isFalse();
		assertThat(vista.deactivationReason()).isEqualTo("Motivo original");
		verify(entradas, never()).save(any());
		verify(entradas, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("la baja saca la entrada del timeline sin tocar sus versiones")
	void baja_no_borra_versiones() {
		EntradaClinica entrada = entradaVigente();
		given(entradas.findByIdAndOrganizationId(ENTRADA_ID, ORG_ID))
				.willReturn(Optional.of(entrada));
		given(versiones.buscarDeEntrada(ORG_ID, ENTRADA_ID))
				.willReturn(List.of(version(2, "Motivo"), version(1, null)));

		EntradaClinicaView vista =
				service.darDeBaja(profesional, ENTRADA_ID, "Cargada por error", 0L, null);

		assertThat(vista.vigente()).isFalse();
		assertThat(vista.numeroVersion()).isEqualTo(2);
	}

	@Test
	@DisplayName("una entrada de otro tenant es indistinguible de una que no existe")
	void entrada_de_otro_tenant_no_se_distingue_de_inexistente() {
		given(entradas.findByIdAndOrganizationId(ENTRADA_ID, ORG_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.ver(profesional, ENTRADA_ID, null))
				.isInstanceOf(EntradaClinicaNotAccessibleException.class);
	}

	@Test
	@DisplayName("leer las versiones deja su propio evento de acceso clinico")
	void leer_versiones_se_audita() {
		given(entradas.findByIdAndOrganizationId(ENTRADA_ID, ORG_ID))
				.willReturn(Optional.of(entradaVigente()));
		given(versiones.buscarDeEntrada(ORG_ID, ENTRADA_ID))
				.willReturn(List.of(version(2, "Motivo"), version(1, null)));

		List<EntradaClinicaVersionView> historico =
				service.versiones(profesional, ENTRADA_ID, null);

		assertThat(historico).extracting(EntradaClinicaVersionView::numeroVersion)
				.containsExactly(2, 1);

		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		assertThat(captor.getValue().details()).containsEntry("alcance", "ENTRADA_VERSIONES");
	}

	@Test
	@DisplayName("listar resuelve la version vigente de cada entrada en una sola consulta")
	void listar_arma_la_vigente_de_cada_entrada() {
		EntradaClinica entrada = entradaVigente();
		given(historias.findByIdAndOrganizationId(HC_ID, ORG_ID)).willReturn(Optional.of(historia()));
		given(entradas.buscarDeHistoria(ORG_ID, HC_ID, true)).willReturn(List.of(entrada));
		given(versiones.buscarVigentesDe(ORG_ID, List.of(ENTRADA_ID)))
				.willReturn(List.of(version(2, "Motivo")));

		List<EntradaClinicaView> vistas = service.listar(profesional, HC_ID, true, null);

		assertThat(vistas).hasSize(1);
		assertThat(vistas.getFirst().numeroVersion()).isEqualTo(2);
		verify(versiones, never()).buscarDeEntrada(anyLong(), anyLong());
	}

	@Test
	@DisplayName("enmendar devuelve la version YA avanzada de la cabecera, no la que leyo")
	void enmendar_devuelve_la_version_fresca() {
		// El defecto que esto fija: `save` es un merge, no un flush. Con
		// OPTIMISTIC_FORCE_INCREMENT la @Version avanza en el flush, que ocurre al commit,
		// DESPUES de que esta vista ya leyo getVersion(). El cliente se llevaba la version vieja,
		// la mandaba como expectedVersion en la enmienda siguiente y comia un 409 del que no podia
		// salir salvo releyendo la entrada.
		EntradaClinica entrada = entradaVigente();
		given(entradas.findWithLockByIdAndOrganizationId(ENTRADA_ID, ORG_ID))
				.willReturn(Optional.of(entrada));
		org.mockito.BDDMockito.willAnswer(i -> conVersion(i.getArgument(0), 3L))
				.given(entradas).save(any());
		org.mockito.BDDMockito.willAnswer(i -> conVersion(i.getArgument(0), 4L))
				.given(entradas).saveAndFlush(any());

		EntradaClinicaView vista = service.enmendar(profesional, ENTRADA_ID,
				"Mejora la flexion", "Se aclara el rango", 0L, null);

		verify(entradas).saveAndFlush(entrada);
		verify(entradas, never()).save(any());
		assertThat(vista.version()).isEqualTo(4L);
	}

	@Test
	@DisplayName("la baja tambien devuelve la version ya avanzada")
	void la_baja_devuelve_la_version_fresca() {
		// Este camino se salvaba POR ACCIDENTE: vigenteDe es una consulta JPQL y una consulta
		// dispara el flush AUTO, que emitia el UPDATE justo antes de que se leyera la version.
		// Depender de eso es depender de que nadie reordene dos lineas.
		EntradaClinica entrada = entradaVigente();
		given(entradas.findByIdAndOrganizationId(ENTRADA_ID, ORG_ID))
				.willReturn(Optional.of(entrada));
		given(versiones.buscarDeEntrada(ORG_ID, ENTRADA_ID)).willReturn(List.of(version(1, null)));
		org.mockito.BDDMockito.willAnswer(i -> conVersion(i.getArgument(0), 3L))
				.given(entradas).save(any());
		org.mockito.BDDMockito.willAnswer(i -> conVersion(i.getArgument(0), 4L))
				.given(entradas).saveAndFlush(any());

		EntradaClinicaView vista =
				service.darDeBaja(profesional, ENTRADA_ID, "Cargada por error", 0L, null);

		verify(entradas).saveAndFlush(entrada);
		verify(entradas, never()).save(any());
		assertThat(vista.version()).isEqualTo(4L);
	}

	// =================================================================================

	private HistoriaClinica historia() {
		HistoriaClinica historia =
				new HistoriaClinica(ORG_ID, PERSONA_ID, Instant.now(), ACCOUNT_ID);
		ReflectionTestUtils.setField(historia, "id", HC_ID);
		return historia;
	}

	private EntradaClinica entradaVigente() {
		EntradaClinica entrada = new EntradaClinica(ORG_ID, HC_ID, TipoEntradaClinica.EVOLUCION,
				Instant.now(), OrigenEntradaClinica.MANUAL, null, Instant.now(), ACCOUNT_ID);
		ReflectionTestUtils.setField(entrada, "id", ENTRADA_ID);
		return entrada;
	}

	private EntradaClinicaVersion version(int numero, String motivo) {
		return new EntradaClinicaVersion(
				ORG_ID, ENTRADA_ID, numero, "Cuerpo " + numero, motivo, Instant.now(), ACCOUNT_ID);
	}

	private static EntradaClinica conVersion(EntradaClinica entrada, long version) {
		ReflectionTestUtils.setField(entrada, "version", version);
		return entrada;
	}

	private static EntradaClinica conId(EntradaClinica entrada, long id) {
		ReflectionTestUtils.setField(entrada, "id", id);
		return entrada;
	}
}
