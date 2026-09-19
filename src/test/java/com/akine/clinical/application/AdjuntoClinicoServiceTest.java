package com.akine.clinical.application;

import com.akine.clinical.domain.AdjuntoClinico;
import com.akine.clinical.domain.CategoriaAdjuntoClinico;
import com.akine.clinical.domain.EntradaClinica;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.OrigenEntradaClinica;
import com.akine.clinical.domain.TipoEntradaClinica;
import com.akine.clinical.domain.exception.AdjuntoClinicoInactivoException;
import com.akine.clinical.domain.exception.AdjuntoClinicoNoDisponibleException;
import com.akine.clinical.domain.exception.AdjuntoClinicoNotAccessibleException;
import com.akine.clinical.domain.exception.ArchivoClinicoNoAceptadoException;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.AdjuntoClinicoRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.EntradaClinicaRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.domain.port.ContenidoClinicoStoragePort;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Adjuntos clinicos: se autorizan como la Historia Clinica y su descarga queda registrada.
 *
 * <p>Lo que estos tests fijan son las cuatro cosas que cuestan caro si se rompen y que ningun
 * compilador agarra: que la descarga deje {@code ADJUNTO_CLINICO_DOWNLOADED}, que la fila se
 * escriba <b>antes</b> que el binario, que un reintento de subida no deje dos filas, y que una
 * entrada de otra historia no se pueda usar como ancla.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdjuntoClinicoService")
class AdjuntoClinicoServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;
	private static final long HC_ID = 700L;
	private static final long ADJUNTO_ID = 800L;
	private static final long ENTRADA_ID = 900L;
	private static final long OTRA_HC_ID = 701L;

	private static final byte[] PDF = "%PDF-1.7 estudio sintetico".getBytes(StandardCharsets.US_ASCII);

	@Mock
	private HistoriaClinicaRepositoryPort historias;

	@Mock
	private EntradaClinicaRepositoryPort entradas;

	@Mock
	private AdjuntoClinicoRepositoryPort adjuntos;

	@Mock
	private ContenidoClinicoStoragePort storage;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private RelacionAsistencialProbe relaciones;

	@Mock
	private AuditTrail auditTrail;

	@Mock
	private ClinicalSupportAccessAuditor supportAccessAuditor;

	@Mock
	private AdjuntoClinicoEscrituraAparte escrituraAparte;

	private AdjuntoClinicoService service;

	private final OperatingActor profesional =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		service = new AdjuntoClinicoService(historias, entradas, adjuntos, storage,
				permissionGuard, relaciones, auditTrail, supportAccessAuditor, escrituraAparte);

		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
		given(relaciones.tieneRelacionAsistencial(anyLong(), anyLong(), anyLong(), anyLong()))
				.willReturn(true);
		given(historias.findByIdAndOrganizationId(HC_ID, ORG_ID))
				.willReturn(Optional.of(historia()));
		given(storage.tamanoMaximo()).willReturn(1024L * 1024L);
		given(adjuntos.buscarVigentePorChecksum(anyLong(), anyLong(), anyString()))
				.willReturn(Optional.empty());
		given(adjuntos.saveAndFlush(any())).willAnswer(i -> conId(i.getArgument(0)));
		given(adjuntos.save(any())).willAnswer(i -> i.getArgument(0));
		// El INSERT ya no lo hace el servicio: lo hace el colaborador que corre en su propia
		// transaccion, porque un choque contra el unique marca rollbackOnly la transaccion en la
		// que ocurre y atraparlo ahi no la des-marca.
		given(escrituraAparte.insertar(any())).willAnswer(i -> conId(i.getArgument(0)));
	}

	// =================================================================================
	// Subir
	// =================================================================================

	@Test
	@DisplayName("subir escribe la fila ANTES que el binario y guarda el tipo detectado")
	void subir_escribe_fila_y_despues_blob() {
		AdjuntoClinicoService.AdjuntoClinicoAlta alta =
				service.subir(profesional, HC_ID, comando(null, "application/msword"), null);

		assertThat(alta.creado()).isTrue();
		// El cliente declaro msword; se guarda lo que dicen los bytes.
		assertThat(alta.adjunto().contentType()).isEqualTo("application/pdf");
		assertThat(alta.adjunto().historiaClinicaId()).isEqualTo(HC_ID);

		var orden = org.mockito.Mockito.inOrder(escrituraAparte, storage);
		orden.verify(escrituraAparte).insertar(any());
		orden.verify(storage).guardar(anyString(), any());
	}

	@Test
	@DisplayName("subir dos veces el mismo contenido devuelve el existente y no toca el disco")
	void subida_idempotente() {
		given(adjuntos.buscarVigentePorChecksum(anyLong(), anyLong(), anyString()))
				.willReturn(Optional.of(conId(adjunto(null))));

		AdjuntoClinicoService.AdjuntoClinicoAlta alta =
				service.subir(profesional, HC_ID, comando(null, "application/pdf"), null);

		assertThat(alta.creado()).isFalse();
		assertThat(alta.adjunto().id()).isEqualTo(ADJUNTO_ID);
		verify(escrituraAparte, never()).insertar(any());
		verify(storage, never()).guardar(anyString(), any());
	}

	@Test
	@DisplayName("un contenido que no esta en la lista blanca se rechaza por los BYTES")
	void tipo_no_permitido() {
		byte[] htmlDisfrazado = "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> service.subir(profesional, HC_ID,
				new AdjuntoClinicoAltaCommand(CategoriaAdjuntoClinico.ESTUDIO, null, null,
						"estudio.pdf", "application/pdf", htmlDisfrazado),
				null))
				.isInstanceOf(ArchivoClinicoNoAceptadoException.class);

		verify(storage, never()).guardar(anyString(), any());
	}

	@Test
	@DisplayName("un archivo que supera el tope se rechaza antes de hashearlo")
	void demasiado_grande() {
		given(storage.tamanoMaximo()).willReturn(4L);

		assertThatThrownBy(() -> service.subir(
				profesional, HC_ID, comando(null, "application/pdf"), null))
				.isInstanceOf(ArchivoClinicoNoAceptadoException.class)
				.hasMessageContaining("4");

		verify(escrituraAparte, never()).insertar(any());
	}

	@Test
	@DisplayName("una entrada de otra historia no sirve de ancla: 404, no 400")
	void entrada_de_otra_historia() {
		given(entradas.findByIdAndOrganizationId(ENTRADA_ID, ORG_ID))
				.willReturn(Optional.of(entradaDe(OTRA_HC_ID)));

		assertThatThrownBy(() -> service.subir(
				profesional, HC_ID, comando(ENTRADA_ID, "application/pdf"), null))
				.isInstanceOf(AdjuntoClinicoNotAccessibleException.class);

		verify(escrituraAparte, never()).insertar(any());
		verify(storage, never()).guardar(anyString(), any());
	}

	@Test
	@DisplayName("una entrada de esta historia queda como ancla del adjunto")
	void entrada_de_esta_historia() {
		given(entradas.findByIdAndOrganizationId(ENTRADA_ID, ORG_ID))
				.willReturn(Optional.of(entradaDe(HC_ID)));

		AdjuntoClinicoService.AdjuntoClinicoAlta alta = service.subir(
				profesional, HC_ID, comando(ENTRADA_ID, "application/pdf"), null);

		assertThat(alta.adjunto().entradaClinicaId()).isEqualTo(ENTRADA_ID);
	}

	// =================================================================================
	// Descargar — el evento que justifica la etapa
	// =================================================================================

	@Test
	@DisplayName("la descarga deja ADJUNTO_CLINICO_DOWNLOADED con la via de acceso")
	void la_descarga_se_audita() {
		given(adjuntos.buscarDeLaHistoria(ORG_ID, HC_ID, ADJUNTO_ID))
				.willReturn(Optional.of(conId(adjunto(null))));
		given(storage.leer(anyString())).willReturn(Optional.of(PDF));

		ContenidoDeAdjuntoClinico contenido =
				service.contenido(profesional, HC_ID, ADJUNTO_ID, null);

		assertThat(contenido.contenido()).isEqualTo(PDF);
		assertThat(contenido.nombreArchivo()).isEqualTo("estudio.pdf");

		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		AuditEntry evento = captor.getValue();
		assertThat(evento.eventType()).isEqualTo("ADJUNTO_CLINICO_DOWNLOADED");
		assertThat(evento.entityType()).isEqualTo("AdjuntoClinico");
		assertThat(evento.details()).containsEntry("viaDeAcceso", "RELACION_ASISTENCIAL");
	}

	@Test
	@DisplayName("un adjunto dado de baja se sigue descargando")
	void descarga_tras_baja() {
		AdjuntoClinico deBaja = conId(adjunto(null));
		deBaja.deactivate(Instant.now(), "Cargado en la historia equivocada");
		given(adjuntos.buscarDeLaHistoria(ORG_ID, HC_ID, ADJUNTO_ID))
				.willReturn(Optional.of(deBaja));
		given(storage.leer(anyString())).willReturn(Optional.of(PDF));

		assertThat(service.contenido(profesional, HC_ID, ADJUNTO_ID, null).contenido())
				.isEqualTo(PDF);
	}

	@Test
	@DisplayName("si el almacenamiento perdio el binario es 409 y la fila queda NO_DISPONIBLE")
	void binario_perdido() {
		AdjuntoClinico sinBinario = conId(adjunto(null));
		given(adjuntos.buscarDeLaHistoria(ORG_ID, HC_ID, ADJUNTO_ID))
				.willReturn(Optional.of(sinBinario));
		given(storage.leer(anyString())).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.contenido(profesional, HC_ID, ADJUNTO_ID, null))
				.isInstanceOf(AdjuntoClinicoNoDisponibleException.class);

		// La marca NO se escribe en la transaccion de la descarga: la excepcion de arriba la
		// revierte y se la llevaria puesta, dejando la columna en DISPONIBLE para siempre. Va por
		// el colaborador con REQUIRES_NEW, que commitea aparte.
		verify(escrituraAparte).marcarNoDisponible(ORG_ID, HC_ID, ADJUNTO_ID);
		verify(adjuntos, never()).save(any());
	}

	@Test
	@DisplayName("un adjunto de otra historia no se alcanza aunque el id exista")
	void adjunto_de_otra_historia() {
		given(adjuntos.buscarDeLaHistoria(ORG_ID, HC_ID, ADJUNTO_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.contenido(profesional, HC_ID, ADJUNTO_ID, null))
				.isInstanceOf(AdjuntoClinicoNotAccessibleException.class);
	}

	// =================================================================================
	// Listar, reclasificar, dar de baja
	// =================================================================================

	@Test
	@DisplayName("listar deja evento de acceso a la historia y trae solo vigentes por defecto")
	void listar_audita_y_filtra() {
		given(adjuntos.listar(ORG_ID, HC_ID, null, null, 1, 0, 20))
				.willReturn(List.of(conId(adjunto(null))));
		given(adjuntos.contar(ORG_ID, HC_ID, null, null, 1)).willReturn(1L);

		AdjuntoClinicoService.AdjuntoClinicoPagina pagina =
				service.listar(profesional, HC_ID, null, null, false, 0, 20, null);

		assertThat(pagina.total()).isEqualTo(1L);
		assertThat(pagina.contenido()).hasSize(1);

		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		assertThat(captor.getValue().eventType()).isEqualTo("HISTORIA_CLINICA_ACCESSED");
		assertThat(captor.getValue().details()).containsEntry("alcance", "ADJUNTOS");
	}

	@Test
	@DisplayName("reclasificar no toca el contenido y deja el cambio de categoria en la auditoria")
	void reclasificar() {
		given(adjuntos.buscarDeLaHistoria(ORG_ID, HC_ID, ADJUNTO_ID))
				.willReturn(Optional.of(conId(adjunto(null))));

		AdjuntoClinicoView vista = service.reclasificar(profesional, HC_ID, ADJUNTO_ID,
				CategoriaAdjuntoClinico.INFORME, "Informe de resonancia", null);

		assertThat(vista.categoria()).isEqualTo("INFORME");
		assertThat(vista.checksumSha256()).isEqualTo("abc123");

		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		assertThat(captor.getValue().details())
				.containsEntry("categoriaAnterior", "ESTUDIO")
				.containsEntry("categoria", "INFORME")
				// El titulo NO se copia: lo escribe un profesional sobre un documento clinico.
				.containsEntry("titulo", "modificado");
	}

	@Test
	@DisplayName("reclasificar un adjunto dado de baja es conflicto")
	void reclasificar_inactivo() {
		AdjuntoClinico deBaja = conId(adjunto(null));
		deBaja.deactivate(Instant.now(), "Duplicado");
		given(adjuntos.buscarDeLaHistoria(ORG_ID, HC_ID, ADJUNTO_ID))
				.willReturn(Optional.of(deBaja));

		assertThatThrownBy(() -> service.reclasificar(profesional, HC_ID, ADJUNTO_ID,
				CategoriaAdjuntoClinico.INFORME, null, null))
				.isInstanceOf(AdjuntoClinicoInactivoException.class);
	}

	@Test
	@DisplayName("repetir la baja no es conflicto y conserva el motivo original")
	void baja_repetida_es_idempotente() {
		AdjuntoClinico deBaja = conId(adjunto(null));
		deBaja.deactivate(Instant.now(), "Cargado en la historia equivocada");
		given(adjuntos.buscarDeLaHistoria(ORG_ID, HC_ID, ADJUNTO_ID))
				.willReturn(Optional.of(deBaja));

		AdjuntoClinicoView vista =
				service.darDeBaja(profesional, HC_ID, ADJUNTO_ID, "Otro motivo", null);

		assertThat(vista.deactivationReason()).isEqualTo("Cargado en la historia equivocada");
		verify(adjuntos, never()).save(any());
		verify(adjuntos, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("la baja es logica, exige motivo y NO borra el binario")
	void baja_logica() {
		given(adjuntos.buscarDeLaHistoria(ORG_ID, HC_ID, ADJUNTO_ID))
				.willReturn(Optional.of(conId(adjunto(null))));

		AdjuntoClinicoView vista =
				service.darDeBaja(profesional, HC_ID, ADJUNTO_ID, "Duplicado del informe", null);

		assertThat(vista.estadoCicloDeVida()).isEqualTo("INACTIVO");
		assertThat(vista.deactivationReason()).isEqualTo("Duplicado del informe");
		// El puerto de almacenamiento no tiene forma de borrar, y nadie se la pide.
		verify(storage, never()).guardar(anyString(), any());
	}

	// =================================================================================
	// Los defectos que la revision adversarial encontro
	// =================================================================================

	@Test
	@DisplayName("la auditoria de la descarga NO copia el nombre del archivo")
	void la_descarga_no_copia_el_nombre_del_archivo() {
		// `audit_event` se consulta con `auditoria:read`, que no es un permiso clinico, y un
		// nombre como "rmn-rodilla-rotura-menisco.pdf" es el diagnostico.
		given(adjuntos.buscarDeLaHistoria(ORG_ID, HC_ID, ADJUNTO_ID))
				.willReturn(Optional.of(conId(adjunto(null))));
		given(storage.leer(anyString())).willReturn(Optional.of(PDF));

		service.contenido(profesional, HC_ID, ADJUNTO_ID, null);

		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		assertThat(captor.getValue().details())
				.doesNotContainKey("nombreArchivo")
				// El checksum si se queda: es un hash, no dice nada clinico, y es lo que permite
				// identificar despues QUE archivo salio sin tener que nombrarlo.
				.containsEntry("checksumSha256", "abc123");
		assertThat(captor.getValue().details().values())
				.doesNotContain("estudio.pdf");
	}

	@Test
	@DisplayName("la auditoria de la baja NO copia el motivo, que es texto clinico libre")
	void la_baja_no_copia_el_motivo() {
		given(adjuntos.buscarDeLaHistoria(ORG_ID, HC_ID, ADJUNTO_ID))
				.willReturn(Optional.of(conId(adjunto(null))));

		service.darDeBaja(profesional, HC_ID, ADJUNTO_ID,
				"Se da de baja el informe de la biopsia", null);

		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		assertThat(captor.getValue().details()).doesNotContainKey("motivoDeBaja");
		assertThat(captor.getValue().details().values())
				.doesNotContain("Se da de baja el informe de la biopsia");
	}

	@Test
	@DisplayName("reclasificar devuelve la version YA avanzada, no la que leyo")
	void reclasificar_devuelve_la_version_fresca() {
		// El defecto: `save` es un merge y el UPDATE que sube la version sale al commit, despues
		// de que la vista leyo getVersion(). El cliente mandaria esa version vieja en su proxima
		// operacion y comeria un 409 del que no puede salir salvo releyendo.
		AdjuntoClinico leido = conId(adjunto(null));
		given(adjuntos.buscarDeLaHistoria(ORG_ID, HC_ID, ADJUNTO_ID))
				.willReturn(Optional.of(leido));
		org.mockito.BDDMockito.willAnswer(i -> conVersion(i.getArgument(0), 7L))
				.given(adjuntos).save(any());
		org.mockito.BDDMockito.willAnswer(i -> conVersion(i.getArgument(0), 8L))
				.given(adjuntos).saveAndFlush(any());

		AdjuntoClinicoView vista = service.reclasificar(profesional, HC_ID, ADJUNTO_ID,
				CategoriaAdjuntoClinico.INFORME, null, null);

		verify(adjuntos).saveAndFlush(leido);
		verify(adjuntos, never()).save(any());
		assertThat(vista.version()).isEqualTo(8L);
	}

	@Test
	@DisplayName("la baja tambien devuelve la version ya avanzada")
	void la_baja_devuelve_la_version_fresca() {
		AdjuntoClinico leido = conId(adjunto(null));
		given(adjuntos.buscarDeLaHistoria(ORG_ID, HC_ID, ADJUNTO_ID))
				.willReturn(Optional.of(leido));
		org.mockito.BDDMockito.willAnswer(i -> conVersion(i.getArgument(0), 7L))
				.given(adjuntos).save(any());
		org.mockito.BDDMockito.willAnswer(i -> conVersion(i.getArgument(0), 8L))
				.given(adjuntos).saveAndFlush(any());

		AdjuntoClinicoView vista =
				service.darDeBaja(profesional, HC_ID, ADJUNTO_ID, "Duplicado", null);

		verify(adjuntos).saveAndFlush(leido);
		assertThat(vista.version()).isEqualTo(8L);
	}

	@Test
	@DisplayName("un choque de unique en el INSERT se resuelve como idempotente, no como 500")
	void el_choque_concurrente_no_mata_la_transaccion_del_servicio() {
		// El INSERT corre en su propia transaccion; la del servicio sobrevive y puede consultar.
		AdjuntoClinico ganador = conId(adjunto(null));
		org.mockito.BDDMockito.willThrow(new DataIntegrityViolationException(
						"uk_adjunto_clinico_contenido_vigente"))
				.given(escrituraAparte).insertar(any());
		given(adjuntos.buscarVigentePorChecksum(anyLong(), anyLong(), anyString()))
				.willReturn(Optional.empty(), Optional.of(ganador));

		AdjuntoClinicoService.AdjuntoClinicoAlta alta =
				service.subir(profesional, HC_ID, comando(null, "application/pdf"), null);

		assertThat(alta.creado()).isFalse();
		assertThat(alta.adjunto().id()).isEqualTo(ADJUNTO_ID);
		// Y no se escribio el binario: el que gano ya lo escribio.
		verify(storage, never()).guardar(anyString(), any());
	}

	@Test
	@DisplayName("si el blob falla, la fila ya commiteada queda marcada NO_DISPONIBLE")
	void un_blob_que_falla_deja_la_fila_diciendo_la_verdad() {
		org.mockito.BDDMockito.willThrow(new IllegalStateException("disco lleno"))
				.given(storage).guardar(anyString(), any());

		assertThatThrownBy(() -> service.subir(
				profesional, HC_ID, comando(null, "application/pdf"), null))
				.isInstanceOf(IllegalStateException.class);

		verify(escrituraAparte).marcarNoDisponible(ORG_ID, HC_ID, ADJUNTO_ID);
	}

	@Test
	@DisplayName("un titulo mas largo que la columna es 400 y NO llega a escribir el binario")
	void titulo_demasiado_largo() {
		// La subida entra por multipart: el titulo es un @RequestParam suelto, sin @Valid que lo
		// mire. Si la regla no viviera en el dominio, el INSERT moriria con data truncation, el
		// handler global lo devolveria como 409 y el binario ya estaria escrito en disco.
		String largo = "x".repeat(AdjuntoClinico.TITULO_MAXIMO + 1);

		assertThatThrownBy(() -> service.subir(profesional, HC_ID,
				new AdjuntoClinicoAltaCommand(CategoriaAdjuntoClinico.ESTUDIO, null, largo,
						"estudio.pdf", "application/pdf", PDF),
				null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("160");

		verify(escrituraAparte, never()).insertar(any());
		verify(storage, never()).guardar(anyString(), any());
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	@Test
	@DisplayName("sin contexto de trabajo no hay operacion clinica: 403, nunca 401")
	void sin_contexto() {
		OperatingActor sinSede = new OperatingActor(ACCOUNT_ID, false, ORG_ID, null);

		assertThatThrownBy(() -> service.listar(sinSede, HC_ID, null, null, false, 0, 20, null))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("una historia de otro tenant no se distingue de una que no existe")
	void historia_de_otro_tenant() {
		given(historias.findByIdAndOrganizationId(HC_ID, ORG_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.listar(profesional, HC_ID, null, null, false, 0, 20, null))
				.isInstanceOf(HistoriaClinicaNotAccessibleException.class);
	}

	// =================================================================================

	private static AdjuntoClinicoAltaCommand comando(Long entradaId, String declarado) {
		return new AdjuntoClinicoAltaCommand(CategoriaAdjuntoClinico.ESTUDIO, entradaId,
				"Resonancia lumbar", "estudio.pdf", declarado, PDF);
	}

	private HistoriaClinica historia() {
		HistoriaClinica historia =
				new HistoriaClinica(ORG_ID, PERSONA_ID, Instant.now(), ACCOUNT_ID);
		ReflectionTestUtils.setField(historia, "id", HC_ID);
		return historia;
	}

	private EntradaClinica entradaDe(long historiaClinicaId) {
		EntradaClinica entrada = new EntradaClinica(ORG_ID, historiaClinicaId,
				TipoEntradaClinica.EVOLUCION, Instant.now(), OrigenEntradaClinica.MANUAL, null,
				Instant.now(), ACCOUNT_ID);
		ReflectionTestUtils.setField(entrada, "id", ENTRADA_ID);
		return entrada;
	}

	private static AdjuntoClinico adjunto(Long entradaId) {
		return new AdjuntoClinico(ORG_ID, HC_ID, entradaId, SEDE_ID,
				CategoriaAdjuntoClinico.ESTUDIO, "Resonancia lumbar", "estudio.pdf",
				"application/pdf", PDF.length, "abc123",
				"0123456789abcdef0123456789abcdef", ACCOUNT_ID, Instant.now());
	}

	private static AdjuntoClinico conVersion(AdjuntoClinico adjunto, long version) {
		ReflectionTestUtils.setField(adjunto, "version", version);
		return adjunto;
	}

	private static AdjuntoClinico conId(AdjuntoClinico adjunto) {
		ReflectionTestUtils.setField(adjunto, "id", ADJUNTO_ID);
		return adjunto;
	}
}
