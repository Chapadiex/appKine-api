package com.akine.contracting.application;

import com.akine.contracting.domain.Financiador;
import com.akine.contracting.domain.TipoFinanciador;
import com.akine.contracting.domain.exception.FinanciadorCodigoTakenException;
import com.akine.contracting.domain.exception.FinanciadorCuitTakenException;
import com.akine.contracting.domain.exception.FinanciadorNombreTakenException;
import com.akine.contracting.domain.exception.FinanciadorNotAccessibleException;
import com.akine.contracting.domain.exception.FinanciadorYaInactivoException;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.FinanciadorRepositoryPort;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.PlanCoberturaRepositoryPort;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Los invariantes del catalogo de financiadores, sin base de datos.
 *
 * <h2>Que puede y que no puede probar este test</h2>
 *
 * <p><b>Puede</b> probar quien esta autorizado, que un choque de unique llega como 409 con el
 * {@code type} correcto y no como 500, que una version vieja no pisa cambios ajenos, y —el que
 * importa— que <b>la baja no cascadea</b>: que este servicio no tiene forma de tocar un plan.
 *
 * <p><b>No puede</b> probar que el unique realmente impida dos financiadores vigentes con el mismo
 * codigo: eso lo garantiza {@code uk_financiador_codigo_vigente} de V41 y solo se comprueba contra
 * MySQL real ({@code FinanciadorMigrationIT}). Lo que este test fija es que el servicio
 * <b>traduzca</b> esa violacion en vez de dejarla escapar, y que <b>no</b> la anticipe con un
 * SELECT previo — un pre-chequeo tendria ventana de carrera y romperia el reuso de un codigo
 * liberado por una baja.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FinanciadorService")
class FinanciadorServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 7L;
	private static final long SEDE_ID = 20L;
	private static final long FINANCIADOR_ID = 31L;

	@Mock
	private FinanciadorRepositoryPort financiadores;

	@Mock
	private PlanCoberturaRepositoryPort planes;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private AuditTrail auditTrail;

	private FinanciadorService service;

	/** Con contexto completo: organizacion y sede. Es el actor normal. */
	private final OperatingActor delMostrador =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	/** Autenticado y sin elegir con que centro trabaja. Estado legitimo, no un error. */
	private final OperatingActor sinContexto = new OperatingActor(ACCOUNT_ID, false, null, null);

	/** Con organizacion pero sin sede: el evaluador no puede conceder un alcance de sede. */
	private final OperatingActor sinSede = new OperatingActor(ACCOUNT_ID, false, ORG_ID, null);

	@BeforeEach
	void setUp() {
		service = new FinanciadorService(financiadores, planes, permissionGuard, auditTrail);
		given(financiadores.saveAndFlush(any())).willAnswer(i -> conId(i.getArgument(0)));
		given(financiadores.save(any())).willAnswer(i -> conId(i.getArgument(0)));
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("ORGANIZACION", false));
		given(planes.countByOrganizationIdAndFinanciadorIdAndActive(anyLong(), anyLong(), anyBoolean()))
				.willReturn(0L);
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	@Nested
	@DisplayName("Autorizacion")
	class Autorizacion {

		@Test
		@DisplayName("Sin contexto de trabajo no se lee ni se muta: 403 y nada tocado")
		void sin_contexto_todo_da_403() {
			assertThatThrownBy(() -> service.buscar(sinContexto, busquedaVacia()))
					.isInstanceOf(AccessDeniedException.class);
			assertThatThrownBy(() -> service.crear(sinContexto, alta()))
					.isInstanceOf(AccessDeniedException.class);

			verifyNoInteractions(financiadores);
			verifyNoInteractions(auditTrail);
		}

		@Test
		@DisplayName("Mutar sin sede en el contexto es 403, aunque haya organizacion")
		void mutar_sin_sede_da_403() {
			// No es un capricho: el evaluador concede un alcance de sede SOLO cuando la consulta
			// nombra una sede, asi que sin ella quedaria afuera el CONSULTORIO_ADMIN, a quien la
			// matriz §2 le dice "Si". Matriz §13.3.
			assertThatThrownBy(() -> service.crear(sinSede, alta()))
					.isInstanceOf(AccessDeniedException.class);

			verifyNoInteractions(permissionGuard);
			verify(financiadores, never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("Leer NO evalua ningun permiso: se autoriza por pertenencia al tenant")
		void leer_no_evalua_permiso() {
			// No existe convenio:read y esta etapa no lo inventa. El hueco que eso deja esta
			// declarado en la matriz §13.4.
			given(financiadores.buscar(anyLong(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
					.willReturn(List.of());

			service.buscar(delMostrador, busquedaVacia());

			verifyNoInteractions(permissionGuard);
		}

		@Test
		@DisplayName("Mutar evalua convenio:manage con la organizacion Y la sede del contexto")
		void mutar_evalua_con_sede() {
			service.crear(delMostrador, alta());

			ArgumentCaptor<PermissionQuery> captor = ArgumentCaptor.forClass(PermissionQuery.class);
			verify(permissionGuard).requirePermission(captor.capture());

			assertThat(captor.getValue().permissionCode()).isEqualTo("convenio:manage");
			assertThat(captor.getValue().organizationId()).isEqualTo(ORG_ID);
			assertThat(captor.getValue().consultorioId()).isEqualTo(SEDE_ID);
		}
	}

	// =================================================================================
	// Alta
	// =================================================================================

	@Nested
	@DisplayName("Alta")
	class Alta {

		@Test
		@DisplayName("No consulta si el codigo existe antes de insertar: el unique no tiene ventana")
		void no_hay_prechequeo() {
			// Un SELECT de comprobacion abre una ventana de carrera y ademas romperia el reuso de
			// un codigo liberado por una baja logica.
			service.crear(delMostrador, alta());

			verify(financiadores, never()).buscar(anyLong(), any(),
					org.mockito.ArgumentMatchers.anyInt(), any());
			verify(financiadores).saveAndFlush(any());
		}

		@Test
		@DisplayName("El choque de unique se traduce al 409 que nombra el campo correcto")
		void traduce_los_tres_uniques() {
			assertThatThrownBy(() -> conChoque("uk_financiador_codigo_vigente"))
					.isInstanceOf(FinanciadorCodigoTakenException.class);
			assertThatThrownBy(() -> conChoque("uk_financiador_nombre_vigente"))
					.isInstanceOf(FinanciadorNombreTakenException.class);
			assertThatThrownBy(() -> conChoque("uk_financiador_cuit_vigente"))
					.isInstanceOf(FinanciadorCuitTakenException.class);
		}

		private void conChoque(String indice) {
			// willThrow(...).given(mock) y no given(mock.metodo(any())): la forma clasica invoca el
			// metodo sobre el doble con null, y el Answer que ya esta stubbeado explota antes de
			// llegar a redefinirse.
			willThrow(new DataIntegrityViolationException("choque",
					new RuntimeException("Duplicate entry for key '" + indice + "'")))
					.given(financiadores).saveAndFlush(any());
			service.crear(delMostrador, alta());
		}

		@Test
		@DisplayName("El alta deja una fila de auditoria con el codigo y sin CUIT")
		void audita_el_alta() {
			service.crear(delMostrador, alta());

			ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
			verify(auditTrail).record(captor.capture());
			AuditEntry entry = captor.getValue();

			assertThat(entry.eventType()).isEqualTo("FINANCIADOR_CREATED");
			assertThat(entry.organizationId()).isEqualTo(ORG_ID);
			// Sin consultorio: el financiador es de la ORGANIZACION, y atribuir el cambio a la
			// sede desde la que se hizo sugeriria que otra sede ve algo distinto.
			assertThat(entry.consultorioId()).isNull();
			assertThat(entry.details()).containsEntry("codigo", "OSDE");
			assertThat(entry.details()).doesNotContainKey("cuit");
		}
	}

	// =================================================================================
	// Edicion
	// =================================================================================

	@Nested
	@DisplayName("Edicion")
	class Edicion {

		@Test
		@DisplayName("Una version vieja no pisa el cambio ajeno: 409 conflict, y nada se guarda")
		void la_version_vieja_choca() {
			given(financiadores.findByIdAndOrganizationId(FINANCIADOR_ID, ORG_ID))
					.willReturn(Optional.of(existente()));

			assertThatThrownBy(() -> service.editar(delMostrador, FINANCIADOR_ID, edicion(99L)))
					.isInstanceOf(OptimisticLockingFailureException.class);

			verify(financiadores, never()).saveAndFlush(any());
			verifyNoInteractions(auditTrail);
		}

		@Test
		@DisplayName("Un financiador dado de baja no se edita: reabrirlo reescribiria el historico")
		void inactivo_no_se_edita() {
			Financiador inactivo = existente();
			inactivo.deactivate(Instant.now(), "motivo");
			given(financiadores.findByIdAndOrganizationId(FINANCIADOR_ID, ORG_ID))
					.willReturn(Optional.of(inactivo));

			assertThatThrownBy(() -> service.editar(delMostrador, FINANCIADOR_ID, edicion(0L)))
					.isInstanceOf(FinanciadorYaInactivoException.class);
		}

		@Test
		@DisplayName("Un id de otra organizacion es 404, nunca 403")
		void cross_tenant_da_404() {
			// Un 403 confirmaria que ese id existe, y bastaria recorrer numeros para averiguar con
			// que obras sociales trabaja cada centro del SaaS.
			given(financiadores.findByIdAndOrganizationId(anyLong(), anyLong()))
					.willReturn(Optional.empty());

			assertThatThrownBy(() -> service.editar(delMostrador, FINANCIADOR_ID, edicion(0L)))
					.isInstanceOf(FinanciadorNotAccessibleException.class);
			assertThatThrownBy(() -> service.ver(delMostrador, FINANCIADOR_ID))
					.isInstanceOf(FinanciadorNotAccessibleException.class);
		}

		@Test
		@DisplayName("Lo que no es un choque de nombre ni de CUIT se deja propagar")
		void lo_desconocido_propaga() {
			// Un 500 honesto es mejor que un 409 inventado: DataIntegrityViolationException no la
			// levanta solo un unique — un valor demasiado largo llega por la misma puerta.
			given(financiadores.findByIdAndOrganizationId(FINANCIADOR_ID, ORG_ID))
					.willReturn(Optional.of(existente()));
			willThrow(new DataIntegrityViolationException("largo",
					new RuntimeException("Data too long for column 'observaciones'")))
					.given(financiadores).saveAndFlush(any());

			assertThatThrownBy(() -> service.editar(delMostrador, FINANCIADOR_ID, edicion(0L)))
					.isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	// =================================================================================
	// Baja
	// =================================================================================

	@Nested
	@DisplayName("Baja")
	class Baja {

		@Test
		@DisplayName("La baja NO cascadea: este servicio no puede escribir un plan")
		void la_baja_no_cascadea() {
			// La garantia es estructural: el puerto de planes solo se usa para CONTAR. Si alguien
			// le agrega una escritura y la llama desde aca, este test se cae.
			given(financiadores.findByIdAndOrganizationId(FINANCIADOR_ID, ORG_ID))
					.willReturn(Optional.of(existente()));
			given(planes.countByOrganizationIdAndFinanciadorIdAndActive(ORG_ID, FINANCIADOR_ID, true))
					.willReturn(3L);

			service.darDeBaja(delMostrador, FINANCIADOR_ID, "ya no trabajamos con ellos");

			verify(planes, never()).save(any());
			verify(planes, never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("Tener planes activos no bloquea la baja, pero queda el numero en la auditoria")
		void los_planes_activos_quedan_en_la_auditoria() {
			given(financiadores.findByIdAndOrganizationId(FINANCIADOR_ID, ORG_ID))
					.willReturn(Optional.of(existente()));
			given(planes.countByOrganizationIdAndFinanciadorIdAndActive(ORG_ID, FINANCIADOR_ID, true))
					.willReturn(3L);

			FinanciadorView baja =
					service.darDeBaja(delMostrador, FINANCIADOR_ID, "ya no trabajamos con ellos");

			assertThat(baja.estado()).isEqualTo("INACTIVO");

			ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
			verify(auditTrail).record(captor.capture());
			assertThat(captor.getValue().eventType()).isEqualTo("FINANCIADOR_DEACTIVATED");
			assertThat(captor.getValue().details()).containsEntry("planesActivos", "3");
			assertThat(captor.getValue().previousState()).isEqualTo("ACTIVO");
			assertThat(captor.getValue().newState()).isEqualTo("INACTIVO");
		}

		@Test
		@DisplayName("Dar de baja algo ya dado de baja es 409, no un no-op silencioso")
		void doble_baja_da_409() {
			Financiador inactivo = existente();
			inactivo.deactivate(Instant.now(), "motivo");
			given(financiadores.findByIdAndOrganizationId(FINANCIADOR_ID, ORG_ID))
					.willReturn(Optional.of(inactivo));

			assertThatThrownBy(() -> service.darDeBaja(delMostrador, FINANCIADOR_ID, "otro"))
					.isInstanceOf(FinanciadorYaInactivoException.class);
		}

		@Test
		@DisplayName("La baja sin motivo se rechaza antes de tocar nada")
		void la_baja_exige_motivo() {
			given(financiadores.findByIdAndOrganizationId(FINANCIADOR_ID, ORG_ID))
					.willReturn(Optional.of(existente()));

			assertThatThrownBy(() -> service.darDeBaja(delMostrador, FINANCIADOR_ID, "  "))
					.isInstanceOf(IllegalArgumentException.class);

			verifyNoInteractions(auditTrail);
		}
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private static FinanciadorBusqueda busquedaVacia() {
		return new FinanciadorBusqueda(null, null, null);
	}

	private static FinanciadorAltaCommand alta() {
		return new FinanciadorAltaCommand(
				"OSDE", "OSDE Binario", TipoFinanciador.PREPAGA, "30712345678", null, null, null);
	}

	private static FinanciadorEdicionCommand edicion(long expectedVersion) {
		return new FinanciadorEdicionCommand(
				"Otro nombre", null, null, null, null, null, expectedVersion);
	}

	private static Financiador existente() {
		Financiador financiador = new Financiador(
				ORG_ID, "OSDE", "OSDE Binario", TipoFinanciador.PREPAGA, null, null, null, null);
		ReflectionTestUtils.setField(financiador, "id", FINANCIADOR_ID);
		return financiador;
	}

	private static Financiador conId(Financiador financiador) {
		if (financiador.getId() == null) {
			ReflectionTestUtils.setField(financiador, "id", FINANCIADOR_ID);
		}
		return financiador;
	}
}
