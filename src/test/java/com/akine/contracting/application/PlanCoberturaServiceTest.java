package com.akine.contracting.application;

import com.akine.contracting.domain.Financiador;
import com.akine.contracting.domain.PlanCobertura;
import com.akine.contracting.domain.TipoFinanciador;
import com.akine.contracting.domain.exception.FinanciadorInactivoException;
import com.akine.contracting.domain.exception.FinanciadorNotAccessibleException;
import com.akine.contracting.domain.exception.PlanCodigoTakenException;
import com.akine.contracting.domain.exception.PlanNombreTakenException;
import com.akine.contracting.domain.exception.PlanNotAccessibleException;
import com.akine.contracting.domain.exception.PlanYaInactivoException;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.FinanciadorRepositoryPort;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.PlanCoberturaRepositoryPort;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Los invariantes de los planes de cobertura, sin base de datos.
 *
 * <p>Las dos afirmaciones que este test fija: que <b>RN-M15-001 se hace cumplir resolviendo el
 * financiador contra la base y acotado al tenant</b> —la FK garantiza que el id exista, no que sea
 * del tenant del request— y que <b>cerrar la vigencia no es dar de baja</b>.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PlanCoberturaService")
class PlanCoberturaServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 7L;
	private static final long SEDE_ID = 20L;
	private static final long FINANCIADOR_ID = 31L;
	private static final long PLAN_ID = 88L;
	private static final LocalDate DESDE = LocalDate.of(2026, 1, 1);

	@Mock
	private PlanCoberturaRepositoryPort planes;

	@Mock
	private FinanciadorRepositoryPort financiadores;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private AuditTrail auditTrail;

	private PlanCoberturaService service;

	private final OperatingActor delMostrador =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	private final OperatingActor sinContexto = new OperatingActor(ACCOUNT_ID, false, null, null);

	@BeforeEach
	void setUp() {
		service = new PlanCoberturaService(planes, financiadores, permissionGuard, auditTrail);
		given(planes.saveAndFlush(any())).willAnswer(i -> conId(i.getArgument(0)));
		given(planes.save(any())).willAnswer(i -> conId(i.getArgument(0)));
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("ORGANIZACION", false));
		given(financiadores.findByIdAndOrganizationId(FINANCIADOR_ID, ORG_ID))
				.willReturn(Optional.of(financiador(true)));
	}

	// =================================================================================
	// RN-M15-001 y aislamiento
	// =================================================================================

	@Test
	@DisplayName("El financiador se resuelve contra la base y ACOTADO al tenant, antes de escribir")
	void el_financiador_se_resuelve_acotado_al_tenant() {
		// La FK garantiza que el id exista, NO que sea del tenant del request. Sin esta consulta,
		// un financiadorId ajeno crearia un plan con el organization_id del atacante y el
		// financiador_id de la victima, y la FK lo aceptaria sin objetar nada.
		given(financiadores.findByIdAndOrganizationId(anyLong(), anyLong()))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.crear(delMostrador, FINANCIADOR_ID, alta()))
				.isInstanceOf(FinanciadorNotAccessibleException.class);

		verify(planes, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Un plan de OTRO financiador es 404 bajo esta ruta, aunque sea del mismo tenant")
	void un_plan_de_otro_financiador_es_404() {
		// La ruta declara a que financiador pertenece: resolverlo bajo uno que no es el suyo seria
		// una respuesta que miente. Por eso la consulta lleva las tres columnas.
		given(planes.findByIdAndOrganizationIdAndFinanciadorId(PLAN_ID, ORG_ID, FINANCIADOR_ID))
				.willReturn(Optional.empty());

		assertThatThrownBy(
				() -> service.editar(delMostrador, FINANCIADOR_ID, PLAN_ID, edicion(0L, null)))
				.isInstanceOf(PlanNotAccessibleException.class);
	}

	@Test
	@DisplayName("Sin contexto de trabajo no se lista ni se crea: 403")
	void sin_contexto_da_403() {
		assertThatThrownBy(() -> service.listar(sinContexto, FINANCIADOR_ID, null, null))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.crear(sinContexto, FINANCIADOR_ID, alta()))
				.isInstanceOf(AccessDeniedException.class);
	}

	// =================================================================================
	// Alta
	// =================================================================================

	@Test
	@DisplayName("Un financiador dado de baja no admite planes nuevos: 409")
	void financiador_inactivo_no_admite_planes() {
		// Es la contracara de que la baja no cascadee: lo que se impide es lo NUEVO, no lo que ya
		// existe.
		given(financiadores.findByIdAndOrganizationId(FINANCIADOR_ID, ORG_ID))
				.willReturn(Optional.of(financiador(false)));

		assertThatThrownBy(() -> service.crear(delMostrador, FINANCIADOR_ID, alta()))
				.isInstanceOf(FinanciadorInactivoException.class);

		verify(planes, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("requiereCredencial ausente vale TRUE; requiereAutorizacion ausente vale FALSE")
	void los_dos_defaults_son_opuestos_a_proposito() {
		// Pedir la credencial es lo normal en una cobertura financiada, asi que el default menos
		// invasivo es el que hace que M08 pida el dato en vez de omitirlo en silencio. La
		// autorizacion previa, al reves: no se le impone a nadie un tramite que no declaro.
		PlanCoberturaView creado = service.crear(delMostrador, FINANCIADOR_ID, alta());

		assertThat(creado.requiereCredencial()).isTrue();
		assertThat(creado.requiereAutorizacion()).isFalse();
	}

	@Test
	@DisplayName("El choque de unique se traduce al 409 que nombra el campo correcto")
	void traduce_los_uniques() {
		given(financiadores.findByIdAndOrganizationId(FINANCIADOR_ID, ORG_ID))
				.willReturn(Optional.of(financiador(true)));

		// willThrow(...).given(mock) y no given(mock.metodo(any())): la forma clasica invoca el
		// metodo sobre el doble con null y el Answer ya stubbeado explota antes de redefinirse.
		willThrow(new DataIntegrityViolationException("c",
				new RuntimeException("Duplicate entry for key 'uk_plan_nombre_vigente'")))
				.given(planes).saveAndFlush(any());
		assertThatThrownBy(() -> service.crear(delMostrador, FINANCIADOR_ID, alta()))
				.isInstanceOf(PlanNombreTakenException.class);

		willThrow(new DataIntegrityViolationException("c",
				new RuntimeException("Duplicate entry for key 'uk_plan_codigo_vigente'")))
				.given(planes).saveAndFlush(any());
		assertThatThrownBy(() -> service.crear(delMostrador, FINANCIADOR_ID, alta()))
				.isInstanceOf(PlanCodigoTakenException.class);
	}

	// =================================================================================
	// Edicion y cierre de vigencia
	// =================================================================================

	@Test
	@DisplayName("Cerrar la vigencia deja el plan ACTIVO y queda registrado en la auditoria")
	void cerrar_la_vigencia_no_da_de_baja() {
		given(planes.findByIdAndOrganizationIdAndFinanciadorId(PLAN_ID, ORG_ID, FINANCIADOR_ID))
				.willReturn(Optional.of(plan()));
		LocalDate ultimoDia = LocalDate.of(2026, 6, 30);

		PlanCoberturaView actualizado =
				service.editar(delMostrador, FINANCIADOR_ID, PLAN_ID, edicion(0L, ultimoDia));

		assertThat(actualizado.estado()).isEqualTo("ACTIVO");
		assertThat(actualizado.vigenciaHasta()).isEqualTo(ultimoDia);

		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		assertThat(captor.getValue().eventType()).isEqualTo("PLAN_COBERTURA_UPDATED");
		// Sin esta linea nadie podria responder desde cuando un plan dejo de ofrecerse.
		assertThat(captor.getValue().details()).containsKey("vigenciaHasta");
	}

	@Test
	@DisplayName("Una version vieja no pisa el cambio ajeno: 409 conflict y nada se guarda")
	void la_version_vieja_choca() {
		given(planes.findByIdAndOrganizationIdAndFinanciadorId(PLAN_ID, ORG_ID, FINANCIADOR_ID))
				.willReturn(Optional.of(plan()));

		assertThatThrownBy(
				() -> service.editar(delMostrador, FINANCIADOR_ID, PLAN_ID, edicion(99L, null)))
				.isInstanceOf(OptimisticLockingFailureException.class);

		verify(planes, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Un plan dado de baja no se edita ni se vuelve a dar de baja")
	void inactivo_no_se_edita() {
		PlanCobertura inactivo = plan();
		inactivo.deactivate(Instant.now(), "motivo");
		given(planes.findByIdAndOrganizationIdAndFinanciadorId(PLAN_ID, ORG_ID, FINANCIADOR_ID))
				.willReturn(Optional.of(inactivo));

		assertThatThrownBy(
				() -> service.editar(delMostrador, FINANCIADOR_ID, PLAN_ID, edicion(0L, null)))
				.isInstanceOf(PlanYaInactivoException.class);
		assertThatThrownBy(
				() -> service.darDeBaja(delMostrador, FINANCIADOR_ID, PLAN_ID, "otro"))
				.isInstanceOf(PlanYaInactivoException.class);
	}

	// =================================================================================
	// Listado
	// =================================================================================

	@Test
	@DisplayName("El listado filtra por CICLO DE VIDA, no por vigencia")
	void el_listado_no_filtra_por_vigencia() {
		// Un plan activo con la vigencia vencida sigue siendo ACTIVO y se devuelve, con
		// vigente = false. Son dos cosas distintas y la pantalla necesita las dos para explicar
		// por que ese plan no se ofrece: es el caso borde de la etapa.
		PlanCobertura vencido = new PlanCobertura(
				ORG_ID, FINANCIADOR_ID, "210", "Plan 210", null,
				DESDE, LocalDate.of(2026, 3, 31), false, true, null, null);
		ReflectionTestUtils.setField(vencido, "id", PLAN_ID);

		given(planes.findAllByOrganizationIdAndFinanciadorIdOrderByNombreAsc(ORG_ID, FINANCIADOR_ID))
				.willReturn(List.of(vencido));

		List<PlanCoberturaView> resultado = service.listar(
				delMostrador, FINANCIADOR_ID, EstadoFiltro.ACTIVO, LocalDate.of(2026, 6, 1));

		assertThat(resultado).hasSize(1);
		assertThat(resultado.getFirst().estado()).isEqualTo("ACTIVO");
		assertThat(resultado.getFirst().vigente()).isFalse();
	}

	@Test
	@DisplayName("El filtro INACTIVO deja fuera a los activos, y TODOS trae los dos")
	void el_filtro_de_estado() {
		PlanCobertura activo = plan();
		PlanCobertura dadoDeBaja = plan();
		dadoDeBaja.deactivate(Instant.now(), "motivo");

		given(planes.findAllByOrganizationIdAndFinanciadorIdOrderByNombreAsc(ORG_ID, FINANCIADOR_ID))
				.willReturn(List.of(activo, dadoDeBaja));

		assertThat(service.listar(delMostrador, FINANCIADOR_ID, EstadoFiltro.ACTIVO, DESDE))
				.hasSize(1);
		assertThat(service.listar(delMostrador, FINANCIADOR_ID, EstadoFiltro.INACTIVO, DESDE))
				.hasSize(1);
		assertThat(service.listar(delMostrador, FINANCIADOR_ID, EstadoFiltro.TODOS, DESDE))
				.hasSize(2);
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private static PlanAltaCommand alta() {
		return new PlanAltaCommand(
				"210", "Plan 210", null, DESDE, null, null, null, null, null);
	}

	private static PlanEdicionCommand edicion(long expectedVersion, LocalDate vigenciaHasta) {
		return new PlanEdicionCommand(
				null, null, null, vigenciaHasta, null, null, null, null, expectedVersion);
	}

	private static Financiador financiador(boolean operable) {
		Financiador financiador = new Financiador(
				ORG_ID, "OSDE", "OSDE Binario", TipoFinanciador.PREPAGA, null, null, null, null);
		ReflectionTestUtils.setField(financiador, "id", FINANCIADOR_ID);
		if (!operable) {
			financiador.deactivate(Instant.now(), "motivo");
		}
		return financiador;
	}

	private static PlanCobertura plan() {
		PlanCobertura plan = new PlanCobertura(
				ORG_ID, FINANCIADOR_ID, "210", "Plan 210", null, DESDE, null, false, true, null, null);
		ReflectionTestUtils.setField(plan, "id", PLAN_ID);
		return plan;
	}

	private static PlanCobertura conId(PlanCobertura plan) {
		if (plan.getId() == null) {
			ReflectionTestUtils.setField(plan, "id", PLAN_ID);
		}
		return plan;
	}
}
