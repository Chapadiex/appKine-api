package com.akine.resource.application;

import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.CatalogoAlcance;
import com.akine.resource.domain.CatalogoAlcanceFiltro;
import com.akine.resource.domain.CatalogoConcepto;
import com.akine.resource.domain.CatalogoTipo;
import com.akine.resource.domain.Especialidad;
import com.akine.resource.domain.Nomenclador;
import com.akine.resource.domain.NomencladorItem;
import com.akine.resource.domain.PermissionCodes;
import com.akine.resource.domain.Practica;
import com.akine.resource.domain.exception.CatalogoCodeTakenException;
import com.akine.resource.domain.exception.CatalogoHasActiveReferencesException;
import com.akine.resource.domain.exception.CatalogoInactiveException;
import com.akine.resource.domain.exception.CatalogoNameTakenException;
import com.akine.resource.domain.exception.CatalogoNotAccessibleException;
import com.akine.resource.domain.exception.CatalogoScopeMismatchException;
import com.akine.resource.domain.exception.NomencladorVigenciaOverlapException;
import com.akine.resource.domain.port.CatalogoRepositoryPorts;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Las reglas de {@link CatalogoService} que no necesitan MySQL para decidirse.
 *
 * <p>{@code CatalogoIT} cubre el {@code owner_key} real y los uniques. Aca se prueba lo que el
 * servicio resuelve antes de llegar a la base: quien puede mutar que alcance, que duenios ve cada
 * actor, la compatibilidad de alcance entre un concepto y su referencia, el solapamiento de
 * vigencias, la traduccion de los choques de unique y la auditoria dentro de la operacion.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogoServiceTest {

	private static final long ORG_ID = 10L;
	private static final long CONSULTORIO_ID = 20L;
	private static final long ACCOUNT_ID = 40L;
	private static final long CONCEPTO_ID = 301L;
	private static final long NOMENCLADOR_ID = 401L;
	private static final long PRACTICA_ID = 402L;
	private static final long ITEM_ID = 403L;

	private static final Instant DESDE = Instant.parse("2026-01-01T00:00:00Z");

	/** Los duenios que ve un actor de tenant: el catalogo comun (0) y el suyo. */
	private static final List<Long> OWNERS_TENANT = List.of(0L, ORG_ID);
	private static final List<Long> OWNERS_PLATAFORMA = List.of(0L);

	@Mock
	private CatalogoRepositoryPorts.EspecialidadPort especialidades;

	@Mock
	private CatalogoRepositoryPorts.PracticaPort practicas;

	@Mock
	private CatalogoRepositoryPorts.NomencladorPort nomencladores;

	@Mock
	private CatalogoRepositoryPorts.NomencladorItemPort vigencias;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private AuditTrail auditTrail;

	private CatalogoService service;

	private final OperatingActor tenant =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, CONSULTORIO_ID);
	private final OperatingActor plataforma =
			new OperatingActor(ACCOUNT_ID, true, null, null);

	@BeforeEach
	void setUp() {
		service = new CatalogoService(
				especialidades, practicas, nomencladores, vigencias, permissionGuard, auditTrail);

		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("ORGANIZACION", false));
		given(especialidades.saveAndFlush(any())).willAnswer(i -> conId(i.getArgument(0), CONCEPTO_ID));
		given(especialidades.save(any())).willAnswer(i -> i.getArgument(0));
		given(practicas.saveAndFlush(any())).willAnswer(i -> conId(i.getArgument(0), CONCEPTO_ID));
		given(vigencias.saveAndFlush(any())).willAnswer(i -> conId(i.getArgument(0), ITEM_ID));
		given(vigencias.save(any())).willAnswer(i -> i.getArgument(0));
		given(vigencias.vigenciasDelCodigo(any(), anyString())).willReturn(List.of());
	}

	// =================================================================================
	// Quien muta que alcance
	// =================================================================================

	@Test
	@DisplayName("Un actor de tenant no crea conceptos GLOBALES, aunque tenga consultorio:manage")
	void un_tenant_no_crea_conceptos_globales() {
		assertThatThrownBy(() -> service.crear(tenant, CatalogoTipo.ESPECIALIDAD,
				alta(CatalogoAlcance.GLOBAL, null)))
				.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(especialidades, auditTrail);
	}

	@Test
	@DisplayName("La plataforma no crea conceptos de un tenant: el catalogo propio es del centro")
	void la_plataforma_no_crea_conceptos_de_tenant() {
		assertThatThrownBy(() -> service.crear(plataforma, CatalogoTipo.ESPECIALIDAD,
				alta(CatalogoAlcance.ORGANIZACION, null)))
				.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(especialidades, auditTrail);
	}

	@Test
	@DisplayName("Sin contexto de trabajo, ni siquiera se puede leer el catalogo: 403")
	void sin_contexto_es_403() {
		OperatingActor sinContexto = new OperatingActor(ACCOUNT_ID, false, null, null);

		assertThatThrownBy(() -> service.find(sinContexto, CatalogoTipo.ESPECIALIDAD, CONCEPTO_ID))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("El alta por default es del tenant del contexto, exige consultorio:manage y se audita")
	void el_alta_de_tenant_es_del_contexto_y_se_audita() {
		CatalogoConceptoView vista = service.crear(tenant, CatalogoTipo.ESPECIALIDAD,
				new CatalogoAltaCommand(null, " KINE   01 ", "Kinesiologia", null, null, null, null, null));

		assertThat(vista.organizationId()).isEqualTo(ORG_ID);
		assertThat(vista.alcance()).isEqualTo(CatalogoAlcance.ORGANIZACION.name());
		assertThat(vista.codigo()).isEqualTo("KINE 01");

		ArgumentCaptor<PermissionQuery> consulta = ArgumentCaptor.forClass(PermissionQuery.class);
		verify(permissionGuard).requirePermission(consulta.capture());
		assertThat(consulta.getValue().permissionCode()).isEqualTo(PermissionCodes.CONSULTORIO_MANAGE);
		assertThat(consulta.getValue().organizationId()).isEqualTo(ORG_ID);

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().eventType()).isEqualTo(AuditEvents.CATALOGO_CREATED);
		assertThat(auditoria.getValue().organizationId()).isEqualTo(ORG_ID);
		assertThat(auditoria.getValue().details())
				.containsEntry("alcance", "ORGANIZACION")
				.containsEntry("tipo", "ESPECIALIDAD");
	}

	@Test
	@DisplayName("Un concepto global lo crea la plataforma sin pasar por el evaluador de tenant, y se audita sin tenant")
	void la_plataforma_crea_globales_y_se_auditan_sin_tenant() {
		CatalogoConceptoView vista = service.crear(plataforma, CatalogoTipo.ESPECIALIDAD,
				alta(CatalogoAlcance.GLOBAL, null));

		assertThat(vista.alcance()).isEqualTo(CatalogoAlcance.GLOBAL.name());
		verifyNoInteractions(permissionGuard);
		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().organizationId()).isNull();
	}

	@Test
	@DisplayName("Un tenant no edita un concepto global aunque lo vea")
	void un_tenant_no_edita_un_concepto_global() {
		given(especialidades.findVisible(CONCEPTO_ID, OWNERS_TENANT))
				.willReturn(Optional.of(especialidad(null)));

		assertThatThrownBy(() -> service.editar(tenant, CatalogoTipo.ESPECIALIDAD, CONCEPTO_ID,
				edicion("Otro nombre", 0L)))
				.isInstanceOf(AccessDeniedException.class);

		verify(especialidades, never()).saveAndFlush(any());
	}

	// =================================================================================
	// Visibilidad
	// =================================================================================

	@Test
	@DisplayName("Un tenant ve el catalogo comun y el suyo; un concepto fuera de eso es 404")
	void un_concepto_fuera_de_los_duenios_visibles_es_404() {
		given(especialidades.findVisible(CONCEPTO_ID, OWNERS_TENANT)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.find(tenant, CatalogoTipo.ESPECIALIDAD, CONCEPTO_ID))
				.isInstanceOf(CatalogoNotAccessibleException.class);
		verify(especialidades).findVisible(CONCEPTO_ID, OWNERS_TENANT);
	}

	@Test
	@DisplayName("La plataforma ve solo el catalogo comun, nada de ningun tenant")
	void la_plataforma_ve_solo_el_catalogo_comun() {
		given(especialidades.findVisible(CONCEPTO_ID, OWNERS_PLATAFORMA))
				.willReturn(Optional.of(especialidad(null)));

		service.find(plataforma, CatalogoTipo.ESPECIALIDAD, CONCEPTO_ID);

		verify(especialidades).findVisible(CONCEPTO_ID, OWNERS_PLATAFORMA);
	}

	@Test
	@DisplayName("El filtro ORGANIZACION de la plataforma consulta un duenio imposible, nunca un IN vacio")
	void el_filtro_organizacion_de_la_plataforma_no_devuelve_nada() {
		service.buscar(plataforma, CatalogoTipo.ESPECIALIDAD,
				new CatalogoBusqueda(null, null, CatalogoAlcanceFiltro.ORGANIZACION, null));

		verify(especialidades).buscar(eq(List.of(-1L)), anyString(), anyInt());
	}

	// =================================================================================
	// Choques de unique
	// =================================================================================

	@Test
	@DisplayName("El choque sobre el unique de nombre es name-taken; cualquier otro es code-taken")
	void los_choques_de_unique_se_distinguen_por_la_constraint() {
		willThrow(new DataIntegrityViolationException("dup",
				new RuntimeException("Duplicate entry for key 'uk_especialidad_owner_name_vigente'")))
				.given(especialidades).saveAndFlush(any());
		assertThatThrownBy(() -> service.crear(tenant, CatalogoTipo.ESPECIALIDAD,
				alta(CatalogoAlcance.ORGANIZACION, null)))
				.isInstanceOf(CatalogoNameTakenException.class);

		willThrow(new DataIntegrityViolationException("dup",
				new RuntimeException("Duplicate entry for key 'uk_especialidad_owner_codigo_vigente'")))
				.given(especialidades).saveAndFlush(any());
		assertThatThrownBy(() -> service.crear(tenant, CatalogoTipo.ESPECIALIDAD,
				alta(CatalogoAlcance.ORGANIZACION, null)))
				.isInstanceOf(CatalogoCodeTakenException.class);

		verifyNoInteractions(auditTrail);
	}

	// =================================================================================
	// Referencias entre conceptos
	// =================================================================================

	@Test
	@DisplayName("Una practica global no puede colgar de una especialidad de tenant")
	void una_practica_global_no_cuelga_de_una_especialidad_de_tenant() {
		given(especialidades.findVisible(CONCEPTO_ID, OWNERS_PLATAFORMA))
				.willReturn(Optional.of(especialidad(ORG_ID)));

		assertThatThrownBy(() -> service.crear(plataforma, CatalogoTipo.PRACTICA,
				alta(CatalogoAlcance.GLOBAL, CONCEPTO_ID)))
				.isInstanceOf(CatalogoScopeMismatchException.class);
		verify(practicas, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Una practica no puede colgar de una especialidad dada de baja")
	void una_practica_no_cuelga_de_una_especialidad_inactiva() {
		Especialidad dadaDeBaja = especialidad(ORG_ID);
		dadaDeBaja.deactivate(DESDE, "Fuera de uso");
		given(especialidades.findVisible(CONCEPTO_ID, OWNERS_TENANT)).willReturn(Optional.of(dadaDeBaja));

		assertThatThrownBy(() -> service.crear(tenant, CatalogoTipo.PRACTICA,
				alta(CatalogoAlcance.ORGANIZACION, CONCEPTO_ID)))
				.isInstanceOf(CatalogoInactiveException.class)
				.satisfies(e -> assertThat(((CatalogoInactiveException) e).getOperacion())
						.isEqualTo(CatalogoInactiveException.Operacion.REFERENCIA));
	}

	// =================================================================================
	// Vigencias de nomenclador
	// =================================================================================

	@Test
	@DisplayName("Una vigencia que se solapa con otra del mismo codigo es 409 y no se escribe")
	void una_vigencia_solapada_es_409() {
		given(nomencladores.findVisibleForUpdate(NOMENCLADOR_ID, OWNERS_TENANT))
				.willReturn(Optional.of(nomenclador(ORG_ID)));
		given(practicas.findVisible(PRACTICA_ID, OWNERS_TENANT))
				.willReturn(Optional.of(practica(ORG_ID)));
		NomencladorItem existente = item(NOMENCLADOR_ID, null);
		given(vigencias.vigenciasDelCodigo(NOMENCLADOR_ID, "25.01.01")).willReturn(List.of(existente));

		assertThatThrownBy(() -> service.crearVigencia(tenant, NOMENCLADOR_ID,
				vigencia(Instant.parse("2026-06-01T00:00:00Z"))))
				.isInstanceOf(NomencladorVigenciaOverlapException.class);

		verify(vigencias, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Un nomenclador global no codifica una practica de tenant")
	void un_nomenclador_global_no_codifica_practicas_de_tenant() {
		given(nomencladores.findVisibleForUpdate(NOMENCLADOR_ID, OWNERS_PLATAFORMA))
				.willReturn(Optional.of(nomenclador(null)));
		given(practicas.findVisible(PRACTICA_ID, OWNERS_PLATAFORMA))
				.willReturn(Optional.of(practica(ORG_ID)));

		assertThatThrownBy(() -> service.crearVigencia(plataforma, NOMENCLADOR_ID, vigencia(DESDE)))
				.isInstanceOf(CatalogoScopeMismatchException.class);
	}

	@Test
	@DisplayName("La vigencia hereda el duenio del nomenclador y se audita CATALOGO_VIGENCIA_CREATED")
	void la_vigencia_valida_hereda_el_duenio_y_se_audita() {
		given(nomencladores.findVisibleForUpdate(NOMENCLADOR_ID, OWNERS_TENANT))
				.willReturn(Optional.of(nomenclador(ORG_ID)));
		given(practicas.findVisible(PRACTICA_ID, OWNERS_TENANT))
				.willReturn(Optional.of(practica(null)));

		CatalogoConceptoView vista = service.crearVigencia(tenant, NOMENCLADOR_ID, vigencia(DESDE));

		assertThat(vista.organizationId()).isEqualTo(ORG_ID);
		assertThat(vista.nomencladorId()).isEqualTo(NOMENCLADOR_ID);
		assertThat(vista.practicaId()).isEqualTo(PRACTICA_ID);
		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().eventType()).isEqualTo(AuditEvents.CATALOGO_VIGENCIA_CREATED);
	}

	@Test
	@DisplayName("Dar de baja una vigencia por la ruta de OTRO nomenclador es 404")
	void la_baja_de_una_vigencia_de_otro_nomenclador_es_404() {
		given(nomencladores.findVisible(NOMENCLADOR_ID, OWNERS_TENANT))
				.willReturn(Optional.of(nomenclador(ORG_ID)));
		given(vigencias.findVisible(ITEM_ID, OWNERS_TENANT))
				.willReturn(Optional.of(item(999L, ORG_ID)));

		assertThatThrownBy(() -> service.darDeBajaVigencia(tenant, NOMENCLADOR_ID, ITEM_ID, "Error de carga"))
				.isInstanceOf(CatalogoNotAccessibleException.class);
		verify(vigencias, never()).save(any());
	}

	// =================================================================================
	// Edicion y baja
	// =================================================================================

	@Test
	@DisplayName("Editar con una version vieja es 409 y no escribe")
	void editar_con_version_vieja_es_409() {
		given(especialidades.findVisible(CONCEPTO_ID, OWNERS_TENANT))
				.willReturn(Optional.of(especialidad(ORG_ID)));

		assertThatThrownBy(() -> service.editar(tenant, CatalogoTipo.ESPECIALIDAD, CONCEPTO_ID,
				edicion("Otro nombre", 3L)))
				.isInstanceOf(OptimisticLockingFailureException.class);
		verify(especialidades, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Editar un concepto dado de baja es 409")
	void editar_un_concepto_inactivo_es_409() {
		Especialidad dadaDeBaja = especialidad(ORG_ID);
		dadaDeBaja.deactivate(DESDE, "Fuera de uso");
		given(especialidades.findVisible(CONCEPTO_ID, OWNERS_TENANT)).willReturn(Optional.of(dadaDeBaja));

		assertThatThrownBy(() -> service.editar(tenant, CatalogoTipo.ESPECIALIDAD, CONCEPTO_ID,
				edicion("Otro nombre", 0L)))
				.isInstanceOf(CatalogoInactiveException.class);
	}

	@Test
	@DisplayName("Una especialidad con practicas vigentes no se da de baja: 409")
	void una_especialidad_con_practicas_vigentes_no_se_da_de_baja() {
		Especialidad especialidad = especialidad(ORG_ID);
		given(especialidades.findVisible(CONCEPTO_ID, OWNERS_TENANT)).willReturn(Optional.of(especialidad));
		given(practicas.contarVigentesPorEspecialidad(CONCEPTO_ID)).willReturn(2L);

		assertThatThrownBy(() -> service.darDeBaja(tenant, CatalogoTipo.ESPECIALIDAD, CONCEPTO_ID, "Fuera de uso"))
				.isInstanceOf(CatalogoHasActiveReferencesException.class);
		assertThat(especialidad.isOperable()).isTrue();
	}

	@Test
	@DisplayName("La baja es logica, exige motivo y se audita ACTIVO -> INACTIVO")
	void la_baja_es_logica_y_se_audita() {
		given(especialidades.findVisible(CONCEPTO_ID, OWNERS_TENANT))
				.willReturn(Optional.of(especialidad(ORG_ID)));

		assertThatThrownBy(() -> service.darDeBaja(tenant, CatalogoTipo.ESPECIALIDAD, CONCEPTO_ID, " "))
				.isInstanceOf(IllegalArgumentException.class);

		CatalogoConceptoView vista = service.darDeBaja(
				tenant, CatalogoTipo.ESPECIALIDAD, CONCEPTO_ID, "Fuera de uso");

		assertThat(vista.estado()).isEqualTo("INACTIVO");
		assertThat(vista.deactivationReason()).isEqualTo("Fuera de uso");
		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().eventType()).isEqualTo(AuditEvents.CATALOGO_DEACTIVATED);
		assertThat(auditoria.getValue().previousState()).isEqualTo("ACTIVO");
		assertThat(auditoria.getValue().newState()).isEqualTo("INACTIVO");
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static CatalogoAltaCommand alta(CatalogoAlcance alcance, Long especialidadId) {
		return new CatalogoAltaCommand(
				alcance, "KINE", "Kinesiologia", null, especialidadId, null, DESDE, null);
	}

	private static CatalogoEdicionCommand edicion(String nombre, long version) {
		return new CatalogoEdicionCommand(nombre, null, null, null, false, null, version);
	}

	private static VigenciaAltaCommand vigencia(Instant desde) {
		return new VigenciaAltaCommand(
				PRACTICA_ID, "25.01.01", "Sesion de kinesiologia", null,
				new BigDecimal("1500.00"), desde, null);
	}

	private static Especialidad especialidad(Long organizationId) {
		return conId(new Especialidad(organizationId, "KINE", "Kinesiologia", null, DESDE, null),
				CONCEPTO_ID);
	}

	private static Practica practica(Long organizationId) {
		return conId(new Practica(organizationId, CONCEPTO_ID, "P01", "Sesion", null, DESDE, null),
				PRACTICA_ID);
	}

	private static Nomenclador nomenclador(Long organizationId) {
		return conId(new Nomenclador(organizationId, "NN", "Nomenclador Nacional", null, DESDE, null),
				NOMENCLADOR_ID);
	}

	private static NomencladorItem item(long nomencladorId, Long organizationId) {
		return conId(new NomencladorItem(organizationId, nomencladorId, PRACTICA_ID, "25.01.01",
				"Sesion de kinesiologia", null, null, DESDE, null), ITEM_ID);
	}

	private static <T extends CatalogoConcepto> T conId(T concepto, long id) {
		if (concepto.getId() == null) {
			ReflectionTestUtils.setField(concepto, "id", id);
		}
		return concepto;
	}
}
