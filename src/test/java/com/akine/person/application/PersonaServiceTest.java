package com.akine.person.application;

import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.domain.PerfilPaciente;
import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoDocumento;
import com.akine.person.domain.exception.PersonaDocumentoTakenException;
import com.akine.person.domain.exception.PersonaInactivaException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.exception.PersonaPosibleDuplicadoException;
import com.akine.person.domain.port.PersonRepositoryPorts.PerfilPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Los invariantes del padron, sin base de datos.
 *
 * <h2>Que puede y que no puede probar este test</h2>
 *
 * <p><b>Puede</b> probar quien esta autorizado, que las dos capas de proteccion contra duplicados
 * son distintas entre si, que un choque de unique llega como 409 y no como 500, que una version
 * vieja no pisa cambios ajenos, y —el que importa— que <b>ningun camino de alta ni de edicion
 * escribe un perfil de paciente</b>.
 *
 * <p><b>No puede</b> probar que el unique realmente impida dos personas vigentes con el mismo
 * documento: eso lo garantiza {@code uk_persona_documento_vigente} de V27 y solo se comprueba
 * contra MySQL real. Lo que este test fija es que el servicio <b>traduzca</b> esa violacion en
 * vez de dejarla escapar, y que <b>no</b> la anticipe con un SELECT previo — un pre-chequeo
 * tendria ventana de carrera y romperia el reuso de un documento liberado por una baja.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PersonaService")
class PersonaServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;

	@Mock
	private PersonaRepositoryPort personas;

	@Mock
	private PerfilPacienteRepositoryPort perfiles;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private AuditTrail auditTrail;

	@Mock
	private PersonSupportAccessAuditor supportAccessAuditor;

	private PersonaService service;

	/** Con contexto completo: organizacion y sede. Es el actor normal del mostrador. */
	private final OperatingActor delMostrador =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	/** Autenticado y sin elegir con que centro trabaja. Estado legitimo, no un error. */
	private final OperatingActor sinContexto = new OperatingActor(ACCOUNT_ID, false, null, null);

	@BeforeEach
	void setUp() {
		service = new PersonaService(
				personas, perfiles, permissionGuard, auditTrail, supportAccessAuditor);
		given(personas.saveAndFlush(any())).willAnswer(i -> conId(i.getArgument(0)));
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("ORGANIZACION", false));
		given(personas.buscarCoincidencias(anyLong(), anyString(), anyString(), any()))
				.willReturn(List.of());
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	@Nested
	@DisplayName("Autorizacion")
	class Autorizacion {

		@Test
		@DisplayName("Sin contexto de trabajo no se lee ni se muta el padron: 403 y nada tocado")
		void sin_contexto_todo_da_403() {
			assertThatThrownBy(() -> service.buscar(sinContexto, busquedaVacia(), 0, 20))
					.isInstanceOf(AccessDeniedException.class);
			assertThatThrownBy(() -> service.crear(sinContexto, altaValida()))
					.isInstanceOf(AccessDeniedException.class);

			// 403 y nunca 401: el interceptor del frontend borra el token ante cualquier 401 y
			// deja al usuario en un bucle de login del que no sale.
			verifyNoInteractions(personas, auditTrail);
		}

		@Test
		@DisplayName("Mutar exige ademas sede en el contexto, y se evalua paciente:manage sobre ella")
		void mutar_evalua_el_permiso_con_la_sede_del_contexto() {
			service.crear(delMostrador, altaValida());

			ArgumentCaptor<PermissionQuery> consulta = ArgumentCaptor.forClass(PermissionQuery.class);
			verify(permissionGuard).requirePermission(consulta.capture());

			// Con consultorioId en null, alcanceCubre deja afuera a CONSULTORIO_ADMIN y a
			// ADMINISTRATIVO: el recepcionista no podria dar de alta a nadie. Ver PermissionCodes.
			assertThat(consulta.getValue().permissionCode()).isEqualTo("paciente:manage");
			assertThat(consulta.getValue().organizationId()).isEqualTo(ORG_ID);
			assertThat(consulta.getValue().consultorioId()).isEqualTo(SEDE_ID);
		}

		@Test
		@DisplayName("Leer NO evalua ningun permiso: se autoriza por pertenencia")
		void leer_no_evalua_permisos() {
			// La matriz no declara paciente:read y una etapa no amplia la matriz. El hueco que
			// esto deja —una membership PACIENTE lee el padron entero— esta escrito en
			// PermissionCodes y no se tapa con un permiso inventado.
			given(personas.buscar(anyLong(), anyString(), anyString(), anyInt(), anyInt(),
					anyInt(), anyInt())).willReturn(List.of());

			service.buscar(delMostrador, busquedaVacia(), 0, 20);

			verifyNoInteractions(permissionGuard);
		}

		@Test
		@DisplayName("Un permiso concedido por soporte deja su fila SUPPORT_ACCESS_USED")
		void el_acceso_de_soporte_queda_auditado() {
			// La matriz §4 le da "Soporte" a PLATFORM_ADMIN en la fila "Gestionar paciente", y §7
			// exige que ese acceso sea justificado Y auditado. Sin esta fila, la segunda mitad
			// del invariante no se cumple.
			given(permissionGuard.requirePermission(any()))
					.willReturn(PermissionDecision.concedida("SOPORTE", true));

			service.crear(delMostrador, altaValida());

			verify(supportAccessAuditor).record(any(AuditEntry.class));
		}
	}

	// =================================================================================
	// Duplicados: las dos capas, y son distintas
	// =================================================================================

	@Nested
	@DisplayName("Duplicados")
	class Duplicados {

		@Test
		@DisplayName("Coincidencia de nombre sin confirmar: 409 con los candidatos, y no se inserta")
		void una_coincidencia_sin_confirmar_detiene_el_alta() {
			// Es RN-M07-001 —busqueda previa a la creacion— hecho cumplir por el BACKEND. Con la
			// pantalla como unica defensa, un alta por API entraria sin ninguna comprobacion.
			given(personas.buscarCoincidencias(anyLong(), anyString(), anyString(), any()))
					.willReturn(List.of(personaConId(77L)));

			assertThatThrownBy(() -> service.crear(delMostrador, altaValida()))
					.isInstanceOf(PersonaPosibleDuplicadoException.class)
					.extracting(e -> ((PersonaPosibleDuplicadoException) e).getCandidatos())
					.isEqualTo(List.of(77L));

			verify(personas, never()).saveAndFlush(any());
			verifyNoInteractions(auditTrail);
		}

		@Test
		@DisplayName("Con la confirmacion del operador, la misma alta entra")
		void confirmada_la_coincidencia_el_alta_entra() {
			given(personas.buscarCoincidencias(anyLong(), anyString(), anyString(), any()))
					.willReturn(List.of(personaConId(77L)));

			PersonaView creada = service.crear(delMostrador, altaConfirmada());

			assertThat(creada.id()).isEqualTo(PERSONA_ID);
			// Ni siquiera se pregunta: confirmar significa que el operador ya vio la lista.
			verify(personas, never()).buscarCoincidencias(anyLong(), anyString(), anyString(), any());
		}

		@Test
		@DisplayName("El documento repetido es DURO: la confirmacion no lo saltea")
		void el_documento_repetido_no_se_puede_confirmar() {
			// Dos personas con el mismo documento en la misma organizacion son un error de carga,
			// siempre. Por eso este 409 sale del unique y no de una rama que alguien pueda evitar.
			willThrow(choqueDeUnique()).given(personas).saveAndFlush(any());

			assertThatThrownBy(() -> service.crear(delMostrador, altaConfirmada()))
					.isInstanceOf(PersonaDocumentoTakenException.class);
		}

		@Test
		@DisplayName("Despues de un flush fallido no se vuelve a tocar la sesion JPA")
		void tras_el_choque_no_se_consulta_de_nuevo() {
			// Buscar ahi mismo quien tiene ese documento seria comodo y produce un 500 en vez del
			// 409 legitimo: la especificacion prohibe usar la sesion despues de un flush fallido.
			willThrow(choqueDeUnique()).given(personas).saveAndFlush(any());

			assertThatThrownBy(() -> service.crear(delMostrador, altaValida()))
					.isInstanceOf(PersonaDocumentoTakenException.class);

			verify(personas, never()).buscarVigentePorDocumento(anyLong(), any(), anyString());
			verifyNoInteractions(auditTrail);
		}
	}

	// =================================================================================
	// Edicion
	// =================================================================================

	@Nested
	@DisplayName("Edicion")
	class Edicion {

		@Test
		@DisplayName("Una persona de otra organizacion no resuelve: 404, nunca 403")
		void una_persona_ajena_da_404() {
			// Un 403 confirmaria que ese id existe y bastaria recorrer numeros para medir el
			// padron de pacientes de otro centro.
			given(personas.findByIdAndOrganizationId(PERSONA_ID, ORG_ID))
					.willReturn(Optional.empty());

			assertThatThrownBy(() -> service.editar(delMostrador, PERSONA_ID, edicionDeApellido()))
					.isInstanceOf(PersonaNotAccessibleException.class);
		}

		@Test
		@DisplayName("Una persona dada de baja no se edita: 409, y su ficha se sigue leyendo")
		void una_persona_inactiva_da_409() {
			Persona baja = personaConId(PERSONA_ID);
			baja.deactivate(Instant.now(), "duplicada");
			given(personas.findByIdAndOrganizationId(PERSONA_ID, ORG_ID)).willReturn(Optional.of(baja));

			assertThatThrownBy(() -> service.editar(delMostrador, PERSONA_ID, edicionDeApellido()))
					.isInstanceOf(PersonaInactivaException.class);
		}

		@Test
		@DisplayName("Una version vieja no pisa el cambio ajeno")
		void una_version_vieja_da_409() {
			Persona persona = personaConId(PERSONA_ID);
			ReflectionTestUtils.setField(persona, "version", 3L);
			given(personas.findByIdAndOrganizationId(PERSONA_ID, ORG_ID))
					.willReturn(Optional.of(persona));

			assertThatThrownBy(() -> service.editar(delMostrador, PERSONA_ID, edicionDeApellido()))
					.isInstanceOf(OptimisticLockingFailureException.class);
		}

		@Test
		@DisplayName("La auditoria dice QUE campo cambio, nunca a que valor")
		void la_auditoria_no_publica_pii() {
			// Los detalles se leen con auditoria:read, que no es paciente:manage. Una fila que
			// reproduce el documento o el telefono se los entrega a quien no deberia verlos.
			Persona persona = personaConId(PERSONA_ID);
			given(personas.findByIdAndOrganizationId(PERSONA_ID, ORG_ID))
					.willReturn(Optional.of(persona));

			service.editar(delMostrador, PERSONA_ID, edicionDeApellido());

			ArgumentCaptor<AuditEntry> fila = ArgumentCaptor.forClass(AuditEntry.class);
			verify(auditTrail).record(fila.capture());
			assertThat(fila.getValue().details()).containsEntry("apellido", "modificado");
			assertThat(fila.getValue().details().values()).noneMatch(v -> v.contains("Gomez"));
			// consultorioId NULL: la sede autorizo la operacion, el hecho es de la organizacion.
			assertThat(fila.getValue().consultorioId()).isNull();
			assertThat(fila.getValue().organizationId()).isEqualTo(ORG_ID);
		}
	}

	// =================================================================================
	// RF-M07-010: ningun camino de este servicio crea nada clinico
	// =================================================================================

	@Test
	@DisplayName("Ni el alta ni la edicion escriben un perfil de paciente")
	void ninguna_mutacion_crea_un_paciente() {
		// Es el invariante mas importante del modulo. El puerto de perfiles esta inyectado SOLO
		// para leer: si algun dia una de estas dos operaciones escribe ahi, alguien convirtio en
		// paciente a quien vino a una clase de pilates.
		Persona persona = personaConId(PERSONA_ID);
		given(personas.findByIdAndOrganizationId(PERSONA_ID, ORG_ID)).willReturn(Optional.of(persona));

		service.crear(delMostrador, altaValida());
		service.editar(delMostrador, PERSONA_ID, edicionDeApellido());

		verify(perfiles, never()).save(any());
		verify(perfiles, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Una persona recien creada no es paciente, y su vista lo dice")
	void una_persona_nueva_no_es_paciente() {
		PersonaView creada = service.crear(delMostrador, altaValida());

		assertThat(creada.esPaciente()).isFalse();
		assertThat(creada.perfilPacienteId()).isNull();
	}

	// =================================================================================
	// Busqueda
	// =================================================================================

	@Test
	@DisplayName("El listado resuelve los perfiles de toda la pagina en una sola consulta")
	void el_listado_no_pregunta_fila_por_fila() {
		// Preguntarlo por persona son veinte consultas por pantalla: es la forma en que una
		// busqueda que anda bien con cien personas se cae con cincuenta mil.
		Persona una = personaConId(1L);
		Persona otra = personaConId(2L);
		given(personas.buscar(anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(),
				anyInt())).willReturn(List.of(una, otra));
		given(perfiles.buscarVigentesDePersonas(ORG_ID, List.of(1L, 2L)))
				.willReturn(List.of(new PerfilPaciente(ORG_ID, 2L, Instant.now(), ACCOUNT_ID, null)));
		given(personas.contar(anyLong(), anyString(), anyString(), anyInt(), anyInt())).willReturn(2L);

		PersonaPagina pagina = service.buscar(delMostrador, busquedaVacia(), 0, 20);

		assertThat(pagina.contenido()).extracting(PersonaView::esPaciente)
				.containsExactly(false, true);
		verify(perfiles, never()).buscarVigente(anyLong(), anyLong());
	}

	@Test
	@DisplayName("Una pagina vacia no consulta perfiles: IN () es sintaxis invalida en MySQL")
	void una_pagina_vacia_no_consulta_perfiles() {
		given(personas.buscar(anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(),
				anyInt())).willReturn(List.of());

		service.buscar(delMostrador, busquedaVacia(), 0, 20);

		verify(perfiles, never()).buscarVigentesDePersonas(anyLong(), any());
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static PersonaBusqueda busquedaVacia() {
		return new PersonaBusqueda(null, null, null);
	}

	private static PersonaAltaCommand altaValida() {
		return new PersonaAltaCommand(
				TipoDocumento.DNI, "12345678", "Perez", "Ana", null, null, "1155550000", null, false);
	}

	private static PersonaAltaCommand altaConfirmada() {
		return new PersonaAltaCommand(
				TipoDocumento.DNI, "12345678", "Perez", "Ana", null, null, "1155550000", null, true);
	}

	private static PersonaEdicionCommand edicionDeApellido() {
		return new PersonaEdicionCommand(
				null, null, "Gomez", null, null, null, null, null, 0L);
	}

	private static Persona personaConId(long id) {
		Persona persona = new Persona(
				ORG_ID, TipoDocumento.DNI, "12345678", "Perez", "Ana", null, null, "1155550000", null);
		ReflectionTestUtils.setField(persona, "id", id);
		return persona;
	}

	private static Persona conId(Persona persona) {
		if (persona.getId() == null) {
			ReflectionTestUtils.setField(persona, "id", PERSONA_ID);
		}
		return persona;
	}

	private static DataIntegrityViolationException choqueDeUnique() {
		return new DataIntegrityViolationException(
				"Duplicate entry for key 'uk_persona_documento_vigente'");
	}
}
