package com.akine.person.application;

import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.domain.AdjuntoAdministrativo;
import com.akine.person.domain.CategoriaAdjunto;
import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoDocumento;
import com.akine.person.domain.exception.AdjuntoInactivoException;
import com.akine.person.domain.exception.AdjuntoNoDisponibleException;
import com.akine.person.domain.exception.AdjuntoNotAccessibleException;
import com.akine.person.domain.exception.ArchivoNoAceptadoException;
import com.akine.person.domain.exception.PersonaInactivaException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.port.AdjuntoStoragePort;
import com.akine.person.domain.port.PersonRepositoryPorts.AdjuntoRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Los adjuntos administrativos: validacion real de contenido, idempotencia y baja logica.
 *
 * <p>Lo que estos casos fijan, en orden de importancia:
 *
 * <ol>
 *   <li>El tipo lo decide el CONTENIDO. Un cliente que declara {@code application/pdf} sobre un
 *       HTML no consigue nada.</li>
 *   <li>La subida es idempotente y el reintento NO escribe un segundo binario.</li>
 *   <li>La fila se escribe ANTES que el binario. Si el orden se invierte alguna vez, este test
 *       lo agarra por el efecto observable: un fallo de almacenamiento no deja fila.</li>
 *   <li>La baja es logica y no borra nada.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdjuntoService")
class AdjuntoServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;
	private static final long ADJUNTO_ID = 700L;

	private static final byte[] UN_PDF = "%PDF-1.7\ncontenido sintetico"
			.getBytes(StandardCharsets.US_ASCII);

	@Mock
	private PersonaRepositoryPort personas;

	@Mock
	private AdjuntoRepositoryPort adjuntos;

	@Mock
	private AdjuntoStoragePort storage;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private AuditTrail auditTrail;

	@Mock
	private PersonSupportAccessAuditor supportAccessAuditor;

	@Mock
	private AdjuntoEscrituraAparte escrituraAparte;

	private AdjuntoService service;

	private final OperatingActor delMostrador =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		service = new AdjuntoService(personas, adjuntos, storage, permissionGuard,
				auditTrail, supportAccessAuditor, escrituraAparte);

		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("ORGANIZACION", false));
		given(personas.findByIdAndOrganizationId(PERSONA_ID, ORG_ID))
				.willReturn(Optional.of(personaVigente()));
		given(storage.tamanoMaximo()).willReturn(10L * 1024 * 1024);
		given(adjuntos.buscarVigentePorChecksum(any(), any(), anyString()))
				.willReturn(Optional.empty());
		// El INSERT ya no lo hace el servicio: corre en su propia transaccion, porque un choque
		// contra el unique marca rollbackOnly la transaccion en la que ocurre y atraparlo ahi no
		// la des-marca.
		given(escrituraAparte.insertar(any())).willAnswer(i -> conId(i.getArgument(0)));
		given(adjuntos.save(any())).willAnswer(i -> i.getArgument(0));
	}

	@Test
	@DisplayName("sube el archivo, guarda el tipo DETECTADO y no el declarado")
	void guarda_el_tipo_real() {
		AdjuntoService.AdjuntoAlta alta = service.subir(delMostrador, PERSONA_ID,
				new AdjuntoAltaCommand(CategoriaAdjunto.DOCUMENTO_IDENTIDAD, "DNI frente",
						"dni.pdf", "application/octet-stream", UN_PDF));

		assertThat(alta.creado()).isTrue();
		assertThat(alta.adjunto().contentType()).isEqualTo("application/pdf");
		assertThat(alta.adjunto().tamanoBytes()).isEqualTo(UN_PDF.length);
		verify(storage).guardar(anyString(), any());
	}

	@Test
	@DisplayName("un HTML disfrazado de PDF se rechaza con 400 y no toca el almacenamiento")
	void archivo_malicioso_no_entra() {
		byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> service.subir(delMostrador, PERSONA_ID,
				new AdjuntoAltaCommand(CategoriaAdjunto.OTRO, null, "informe.pdf",
						"application/pdf", html)))
				.isInstanceOf(ArchivoNoAceptadoException.class)
				.extracting(e -> ((ArchivoNoAceptadoException) e).getMotivo())
				.isEqualTo("TIPO_NO_PERMITIDO");

		verifyNoInteractions(auditTrail);
		verify(storage, never()).guardar(anyString(), any());
	}

	@Test
	@DisplayName("un archivo mas grande que el tope se rechaza antes de hashearlo")
	void archivo_demasiado_grande() {
		given(storage.tamanoMaximo()).willReturn(4L);

		assertThatThrownBy(() -> service.subir(delMostrador, PERSONA_ID,
				new AdjuntoAltaCommand(CategoriaAdjunto.OTRO, null, "x.pdf", null, UN_PDF)))
				.isInstanceOf(ArchivoNoAceptadoException.class)
				.extracting(e -> ((ArchivoNoAceptadoException) e).getMotivo())
				.isEqualTo("DEMASIADO_GRANDE");

		verify(escrituraAparte, never()).insertar(any());
	}

	@Test
	@DisplayName("subir dos veces el mismo archivo devuelve el existente y NO reescribe el binario")
	void la_subida_es_idempotente() {
		given(adjuntos.buscarVigentePorChecksum(any(), any(), anyString()))
				.willReturn(Optional.of(unAdjunto()));

		AdjuntoService.AdjuntoAlta alta = service.subir(delMostrador, PERSONA_ID,
				new AdjuntoAltaCommand(CategoriaAdjunto.OTRO, null, "x.pdf", null, UN_PDF));

		assertThat(alta.creado()).isFalse();
		assertThat(alta.adjunto().id()).isEqualTo(ADJUNTO_ID);
		verify(escrituraAparte, never()).insertar(any());
		verify(storage, never()).guardar(anyString(), any());
	}

	@Test
	@DisplayName("si el almacenamiento falla, la fila queda NO_DISPONIBLE y la excepcion sube")
	void el_fallo_de_almacenamiento_propaga() {
		willThrow(new UncheckedIOException(new IOException("disco lleno")))
				.given(storage).guardar(anyString(), any());

		assertThatThrownBy(() -> service.subir(delMostrador, PERSONA_ID,
				new AdjuntoAltaCommand(CategoriaAdjunto.OTRO, null, "x.pdf", null, UN_PDF)))
				.isInstanceOf(UncheckedIOException.class);

		// La fila ya esta commiteada —el INSERT corre en su propia transaccion— asi que el
		// rollback de esta no se la lleva. Dejarla DISPONIBLE seria que el listado afirme tener un
		// documento que no se puede descargar: se la marca NO_DISPONIBLE, que es la verdad.
		verify(escrituraAparte).marcarNoDisponible(ORG_ID, PERSONA_ID, ADJUNTO_ID);
		// La auditoria se escribe DESPUES del blob: si el blob falla, no hay rastro de una carga
		// que no ocurrio. Es la contrapartida de auditar dentro de la transaccion del negocio.
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("dos subidas simultaneas del mismo contenido: la perdedora responde idempotente")
	void la_carrera_del_unique_no_es_un_500() {
		// El defecto que esto fija: con el INSERT dentro de la transaccion de negocio, el choque
		// contra uk_adjunto_contenido_vigente la marcaba rollbackOnly, el catch corria sobre una
		// sesion inutilizable y el commit terminaba en UnexpectedRollbackException. O sea 500,
		// justo donde el contrato promete idempotencia. El INSERT va en transaccion propia.
		willThrow(new DataIntegrityViolationException("uk_adjunto_contenido_vigente"))
				.given(escrituraAparte).insertar(any());
		given(adjuntos.buscarVigentePorChecksum(any(), any(), anyString()))
				.willReturn(Optional.empty())
				.willReturn(Optional.of(unAdjunto()));

		AdjuntoService.AdjuntoAlta alta = service.subir(delMostrador, PERSONA_ID,
				new AdjuntoAltaCommand(CategoriaAdjunto.OTRO, null, "x.pdf", null, UN_PDF));

		assertThat(alta.creado()).isFalse();
		assertThat(alta.adjunto().id()).isEqualTo(ADJUNTO_ID);
		verify(storage, never()).guardar(anyString(), any());
	}

	@Test
	@DisplayName("una persona dada de baja no admite documentos nuevos")
	void persona_inactiva_no_admite_carga() {
		given(personas.findByIdAndOrganizationId(PERSONA_ID, ORG_ID))
				.willReturn(Optional.of(personaDeBaja()));

		assertThatThrownBy(() -> service.subir(delMostrador, PERSONA_ID,
				new AdjuntoAltaCommand(CategoriaAdjunto.OTRO, null, "x.pdf", null, UN_PDF)))
				.isInstanceOf(PersonaInactivaException.class);
	}

	@Test
	@DisplayName("sin contexto de sede no se sube nada: 403 y ni siquiera se lee la persona")
	void sin_sede_no_se_muta() {
		OperatingActor sinSede = new OperatingActor(ACCOUNT_ID, false, ORG_ID, null);

		assertThatThrownBy(() -> service.subir(sinSede, PERSONA_ID,
				new AdjuntoAltaCommand(CategoriaAdjunto.OTRO, null, "x.pdf", null, UN_PDF)))
				.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(personas);
	}

	@Test
	@DisplayName("descarga el contenido y AUDITA la lectura")
	void la_descarga_se_audita() {
		given(adjuntos.buscarDeLaPersona(ORG_ID, PERSONA_ID, ADJUNTO_ID))
				.willReturn(Optional.of(unAdjunto()));
		given(storage.leer(anyString())).willReturn(Optional.of(UN_PDF));

		ContenidoDeAdjunto contenido = service.contenido(delMostrador, PERSONA_ID, ADJUNTO_ID);

		assertThat(contenido.contenido()).isEqualTo(UN_PDF);
		assertThat(contenido.contentType()).isEqualTo("application/pdf");

		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(entrada.capture());
		assertThat(entrada.getValue().eventType()).isEqualTo("ADJUNTO_DOWNLOADED");
	}

	@Test
	@DisplayName("la descarga NO exige paciente:manage: hereda el acceso de la ficha")
	void la_descarga_se_autoriza_por_pertenencia() {
		given(adjuntos.buscarDeLaPersona(ORG_ID, PERSONA_ID, ADJUNTO_ID))
				.willReturn(Optional.of(unAdjunto()));
		given(storage.leer(anyString())).willReturn(Optional.of(UN_PDF));

		service.contenido(new OperatingActor(ACCOUNT_ID, false, ORG_ID, null),
				PERSONA_ID, ADJUNTO_ID);

		verifyNoInteractions(permissionGuard);
	}

	@Test
	@DisplayName("si el almacenamiento perdio el binario, es 409 y no 404")
	void contenido_ausente_es_conflicto() {
		given(adjuntos.buscarDeLaPersona(ORG_ID, PERSONA_ID, ADJUNTO_ID))
				.willReturn(Optional.of(unAdjunto()));
		given(storage.leer(anyString())).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.contenido(delMostrador, PERSONA_ID, ADJUNTO_ID))
				.isInstanceOf(AdjuntoNoDisponibleException.class);
	}

	@Test
	@DisplayName("un adjunto de otra persona o de otro tenant es 404")
	void adjunto_ajeno_es_404() {
		given(adjuntos.buscarDeLaPersona(ORG_ID, PERSONA_ID, ADJUNTO_ID))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.contenido(delMostrador, PERSONA_ID, ADJUNTO_ID))
				.isInstanceOf(AdjuntoNotAccessibleException.class);
	}

	@Test
	@DisplayName("una persona de otro tenant es 404 antes de mirar ningun adjunto")
	void persona_ajena_es_404() {
		given(personas.findByIdAndOrganizationId(PERSONA_ID, ORG_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.contenido(delMostrador, PERSONA_ID, ADJUNTO_ID))
				.isInstanceOf(PersonaNotAccessibleException.class);

		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("lista solo los vigentes por defecto, y todos si se lo piden")
	void el_listado_filtra_por_estado() {
		given(adjuntos.listar(ORG_ID, PERSONA_ID, null, 1, 0, 20))
				.willReturn(List.of(unAdjunto()));
		given(adjuntos.contar(ORG_ID, PERSONA_ID, null, 1)).willReturn(1L);

		assertThat(service.listar(delMostrador, PERSONA_ID, null, false, 0, 20).total())
				.isEqualTo(1L);

		verify(adjuntos).listar(ORG_ID, PERSONA_ID, null, 1, 0, 20);
		service.listar(delMostrador, PERSONA_ID, CategoriaAdjunto.OTRO, true, 0, 20);
		verify(adjuntos).listar(ORG_ID, PERSONA_ID, "OTRO", -1, 0, 20);
	}

	@Test
	@DisplayName("reclasificar cambia la categoria y deja el contenido intacto")
	void reclasifica() {
		AdjuntoAdministrativo existente = unAdjunto();
		given(adjuntos.buscarDeLaPersona(ORG_ID, PERSONA_ID, ADJUNTO_ID))
				.willReturn(Optional.of(existente));

		AdjuntoView vista = service.reclasificar(delMostrador, PERSONA_ID, ADJUNTO_ID,
				CategoriaAdjunto.CREDENCIAL_COBERTURA, "Credencial 2026");

		assertThat(vista.categoria()).isEqualTo("CREDENCIAL_COBERTURA");
		assertThat(vista.titulo()).isEqualTo("Credencial 2026");
		assertThat(vista.checksumSha256()).isEqualTo(existente.getChecksumSha256());
	}

	@Test
	@DisplayName("dar de baja es logico: conserva el contenido y exige motivo")
	void la_baja_es_logica() {
		given(adjuntos.buscarDeLaPersona(ORG_ID, PERSONA_ID, ADJUNTO_ID))
				.willReturn(Optional.of(unAdjunto()));

		AdjuntoView vista = service.darDeBaja(
				delMostrador, PERSONA_ID, ADJUNTO_ID, "Credencial vencida");

		assertThat(vista.estadoCicloDeVida()).isEqualTo("INACTIVO");
		assertThat(vista.deactivationReason()).isEqualTo("Credencial vencida");
		assertThat(vista.estado()).isEqualTo("DISPONIBLE");
	}

	@Test
	@DisplayName("un adjunto ya dado de baja no se reclasifica ni se vuelve a dar de baja")
	void adjunto_de_baja_es_conflicto() {
		AdjuntoAdministrativo deBaja = unAdjunto();
		deBaja.deactivate(Instant.now(), "ya estaba");
		given(adjuntos.buscarDeLaPersona(ORG_ID, PERSONA_ID, ADJUNTO_ID))
				.willReturn(Optional.of(deBaja));

		assertThatThrownBy(() -> service.darDeBaja(delMostrador, PERSONA_ID, ADJUNTO_ID, "otra vez"))
				.isInstanceOf(AdjuntoInactivoException.class);
		assertThatThrownBy(() -> service.reclasificar(
				delMostrador, PERSONA_ID, ADJUNTO_ID, CategoriaAdjunto.OTRO, null))
				.isInstanceOf(AdjuntoInactivoException.class);
	}

	@Test
	@DisplayName("el conteo por categoria traduce las filas crudas del repositorio")
	void cuenta_por_categoria() {
		given(adjuntos.contarVigentesPorCategoria(ORG_ID, PERSONA_ID))
				.willReturn(List.of(new Object[] {"OTRO", 2L}, new Object[] {"CONSENTIMIENTO", 1L}));

		assertThat(service.conteoPorCategoria(ORG_ID, PERSONA_ID))
				.containsEntry("OTRO", 2L)
				.containsEntry("CONSENTIMIENTO", 1L);
	}

	private static Persona personaVigente() {
		Persona persona = new Persona(ORG_ID, TipoDocumento.DNI, "27888999", "Perez", "Ana",
				null, null, null, null);
		ReflectionTestUtils.setField(persona, "id", PERSONA_ID);
		return persona;
	}

	private static Persona personaDeBaja() {
		Persona persona = personaVigente();
		persona.deactivate(Instant.now(), "ficha duplicada");
		return persona;
	}

	private static AdjuntoAdministrativo unAdjunto() {
		AdjuntoAdministrativo adjunto = new AdjuntoAdministrativo(
				ORG_ID, PERSONA_ID, SEDE_ID, CategoriaAdjunto.OTRO, null, "dni.pdf",
				"application/pdf", UN_PDF.length, "a".repeat(64),
				"0123456789abcdef0123456789abcdef", ACCOUNT_ID, Instant.now());
		ReflectionTestUtils.setField(adjunto, "id", ADJUNTO_ID);
		return adjunto;
	}

	private static AdjuntoAdministrativo conId(AdjuntoAdministrativo adjunto) {
		ReflectionTestUtils.setField(adjunto, "id", ADJUNTO_ID);
		return adjunto;
	}
}
