package com.akine.contracting.application;

import com.akine.contracting.domain.Convenio;
import com.akine.contracting.domain.ConvenioLock;
import com.akine.contracting.domain.Financiador;
import com.akine.contracting.domain.ModalidadConvenio;
import com.akine.contracting.domain.PlanCobertura;
import com.akine.contracting.domain.TipoFinanciador;
import com.akine.contracting.domain.exception.ConvenioCodigoTakenException;
import com.akine.contracting.domain.exception.ConvenioNotAccessibleException;
import com.akine.contracting.domain.exception.ConvenioSolapadoException;
import com.akine.contracting.domain.exception.ConvenioYaInactivoException;
import com.akine.contracting.domain.exception.FinanciadorInactivoException;
import com.akine.contracting.domain.exception.FinanciadorNotAccessibleException;
import com.akine.contracting.domain.exception.PlanNotAccessibleException;
import com.akine.contracting.domain.exception.PlanYaInactivoException;
import com.akine.contracting.domain.exception.SedeNoAccesibleException;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.FinanciadorRepositoryPort;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.PlanCoberturaRepositoryPort;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioArancelRepositoryPort;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioLockRepositoryPort;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Los invariantes de los convenios, sin base de datos.
 *
 * <p><b>Lo que este test PUEDE probar</b>: que el solapamiento se rechaza, que el orden
 * asegurar-bloquear-leer es el que es, que el permiso se evalua sobre la sede de la ruta, y que un
 * cross-tenant es 404 y no 403.
 *
 * <p><b>Lo que NO puede probar, y conviene tenerlo claro</b>: que la regla resista dos escrituras
 * concurrentes. Eso lo decide el {@code FOR UPDATE} sobre {@code convenio_lock} y el aislamiento de
 * InnoDB, y no hay mock que reproduzca el gestor de locks de MySQL. Lo prueba
 * {@code ConvenioConcurrenteIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConvenioService")
class ConvenioServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 7L;
	private static final long SEDE_ID = 20L;
	private static final long OTRA_SEDE_ID = 21L;
	private static final long FINANCIADOR_ID = 31L;
	private static final long PLAN_ID = 88L;
	private static final long CONVENIO_ID = 140L;
	private static final LocalDate ENERO = LocalDate.of(2026, 1, 1);
	private static final LocalDate JUNIO_30 = LocalDate.of(2026, 6, 30);
	private static final LocalDate JULIO = LocalDate.of(2026, 7, 1);
	private static final LocalDate DICIEMBRE = LocalDate.of(2026, 12, 31);

	@Mock private ConvenioRepositoryPort convenios;
	@Mock private ConvenioArancelRepositoryPort aranceles;
	@Mock private ConvenioLockRepositoryPort locks;
	@Mock private ConvenioLockIniciador lockIniciador;
	@Mock private FinanciadorRepositoryPort financiadores;
	@Mock private PlanCoberturaRepositoryPort planes;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AuditTrail auditTrail;

	private ConvenioService service;

	private final OperatingActor delMostrador =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	private final OperatingActor sinContexto = new OperatingActor(ACCOUNT_ID, false, null, null);

	@BeforeEach
	void setUp() {
		service = new ConvenioService(convenios, aranceles, locks, lockIniciador, financiadores,
				planes, consultorios, permissionGuard, auditTrail);

		given(consultorios.find(ORG_ID, SEDE_ID))
				.willReturn(Optional.of(new ConsultorioSnapshot(
						SEDE_ID, ORG_ID, "Sede Centro", "America/Argentina/Cordoba", true)));
		given(consultorios.find(ORG_ID, OTRA_SEDE_ID)).willReturn(Optional.empty());
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("ORGANIZACION", false));
		given(financiadores.findByIdAndOrganizationId(FINANCIADOR_ID, ORG_ID))
				.willReturn(Optional.of(financiador(true)));
		given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID))
				.willReturn(Optional.of(plan(true, FINANCIADOR_ID)));
		// El lock no guarda estado: lo unico que importa es que la fila EXISTA, asi que un doble
		// alcanza. Que la fila serialice de verdad lo prueba ConvenioConcurrenteIT contra MySQL.
		given(locks.lockByScope(anyLong(), anyLong()))
				.willReturn(Optional.of(org.mockito.Mockito.mock(ConvenioLock.class)));
		given(convenios.saveAndFlush(any())).willAnswer(i -> conId(i.getArgument(0)));
		given(convenios.save(any())).willAnswer(i -> conId(i.getArgument(0)));
		given(convenios.findActivosPorAlcance(anyLong(), anyLong(), anyLong(), anyLong()))
				.willReturn(List.of());
	}

	// =================================================================================
	// La regla de la etapa
	// =================================================================================

	@Test
	@DisplayName("un convenio que se pisa con otro del mismo alcance se rechaza con su choque")
	void solapamiento_rechazado() {
		given(convenios.findActivosPorAlcance(ORG_ID, SEDE_ID, FINANCIADOR_ID, PLAN_ID))
				.willReturn(List.of(convenio(999L, ENERO, DICIEMBRE)));

		assertThatThrownBy(() -> service.crear(delMostrador, SEDE_ID, alta(JUNIO_30, null)))
				.isInstanceOf(ConvenioSolapadoException.class)
				.satisfies(error -> {
					ConvenioSolapadoException solapado = (ConvenioSolapadoException) error;
					assertThat(solapado.getConvenioExistenteId()).isEqualTo(999L);
					assertThat(solapado.getPeriodoExistente()).contains("2026-01-01");
				});

		verify(convenios, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("dos periodos CONSECUTIVOS conviven: renovar un convenio es el caso normal")
	void consecutivos_conviven() {
		given(convenios.findActivosPorAlcance(ORG_ID, SEDE_ID, FINANCIADOR_ID, PLAN_ID))
				.willReturn(List.of(convenio(999L, ENERO, JUNIO_30)));

		assertThatCode(() -> service.crear(delMostrador, SEDE_ID, alta(JULIO, DICIEMBRE)))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("un convenio de OTRO plan no compite por el periodo")
	void otro_plan_no_compite() {
		// El conjunto candidato lo acota la consulta, que filtra por (sede, financiador, plan).
		// Este test fija que el servicio no valide contra un conjunto mas ancho.
		service.crear(delMostrador, SEDE_ID, alta(ENERO, DICIEMBRE));

		verify(convenios).findActivosPorAlcance(ORG_ID, SEDE_ID, FINANCIADOR_ID, PLAN_ID);
	}

	@Test
	@DisplayName("EL ORDEN ES LA GARANTIA: asegurar la fila-lock, bloquear, y RECIEN AHI leer")
	void el_orden_de_la_concurrencia() {
		// Crear la fila dentro de la transaccion que la bloquea produce deadlock entre las primeras
		// N escrituras de una sede; leer antes de bloquear es una escalada S->X, tambien deadlock.
		// Este test fija el orden; que funcione contra InnoDB lo prueba ConvenioConcurrenteIT.
		service.crear(delMostrador, SEDE_ID, alta(ENERO, DICIEMBRE));

		InOrder orden = inOrder(lockIniciador, locks, convenios);
		orden.verify(lockIniciador).asegurar(ORG_ID, SEDE_ID);
		orden.verify(locks).lockByScope(ORG_ID, SEDE_ID);
		orden.verify(convenios).findActivosPorAlcance(ORG_ID, SEDE_ID, FINANCIADOR_ID, PLAN_ID);
		orden.verify(convenios).saveAndFlush(any());
	}

	@Test
	@DisplayName("la EDICION tambien toma el lock y tambien valida el solapamiento")
	void la_edicion_tambien_valida() {
		// Estirar el fin de un convenio hasta pisar al siguiente es exactamente lo que RN-M16-002
		// prohibe. Validar solo al crear dejaria abierta la puerta mas ancha.
		Convenio propio = convenio(CONVENIO_ID, ENERO, JUNIO_30);
		given(convenios.findByIdAndScope(CONVENIO_ID, ORG_ID, SEDE_ID))
				.willReturn(Optional.of(propio));
		given(convenios.findActivosPorAlcance(ORG_ID, SEDE_ID, FINANCIADOR_ID, PLAN_ID))
				.willReturn(List.of(propio, convenio(999L, JULIO, DICIEMBRE)));

		assertThatThrownBy(() -> service.editar(delMostrador, SEDE_ID, CONVENIO_ID,
				edicion(null, DICIEMBRE, 0L)))
				.isInstanceOf(ConvenioSolapadoException.class);

		verify(lockIniciador).asegurar(ORG_ID, SEDE_ID);
		verify(locks).lockByScope(ORG_ID, SEDE_ID);
	}

	@Test
	@DisplayName("un convenio NO se solapa consigo mismo al editarse")
	void no_se_solapa_consigo_mismo() {
		Convenio propio = convenio(CONVENIO_ID, ENERO, DICIEMBRE);
		given(convenios.findByIdAndScope(CONVENIO_ID, ORG_ID, SEDE_ID))
				.willReturn(Optional.of(propio));
		given(convenios.findActivosPorAlcance(ORG_ID, SEDE_ID, FINANCIADOR_ID, PLAN_ID))
				.willReturn(List.of(propio));

		assertThatCode(() -> service.editar(delMostrador, SEDE_ID, CONVENIO_ID,
				edicion("Renegociado", JUNIO_30, 0L)))
				.doesNotThrowAnyException();
	}

	// =================================================================================
	// Autorizacion y tenant
	// =================================================================================

	@Test
	@DisplayName("sin contexto de trabajo es 403, nunca 401")
	void sin_contexto_403() {
		assertThatThrownBy(() -> service.crear(sinContexto, SEDE_ID, alta(ENERO, null)))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("una sede de otra organizacion es 404, y se comprueba ANTES que el permiso")
	void sede_ajena_404_antes_del_permiso() {
		// Si se evaluara el permiso primero, una sede ajena devolveria 403 y eso confirmaria que
		// ese id existe en otro tenant.
		assertThatThrownBy(() -> service.crear(delMostrador, OTRA_SEDE_ID, alta(ENERO, null)))
				.isInstanceOf(SedeNoAccesibleException.class);

		verify(permissionGuard, never()).requirePermission(any());
	}

	@Test
	@DisplayName("el permiso se evalua sobre la sede de la RUTA, no sobre la del contexto")
	void permiso_sobre_la_sede_de_la_ruta() {
		// Es lo que impide que alguien con convenio:manage en la sede A escriba en la B. Aca el
		// actor tiene contexto en SEDE_ID y la ruta tambien, pero lo que se verifica es de donde
		// sale el consultorioId de la consulta.
		service.crear(delMostrador, SEDE_ID, alta(ENERO, DICIEMBRE));

		var query = ArgumentCaptor.forClass(com.akine.organization.spi.PermissionQuery.class);
		verify(permissionGuard).requirePermission(query.capture());
		assertThat(query.getValue().consultorioId()).isEqualTo(SEDE_ID);
		assertThat(query.getValue().permissionCode()).isEqualTo("convenio:manage");
	}

	@Test
	@DisplayName("un convenio de otra sede del mismo tenant tambien es 404")
	void convenio_de_otra_sede_404() {
		given(convenios.findByIdAndScope(CONVENIO_ID, ORG_ID, SEDE_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.ver(delMostrador, SEDE_ID, CONVENIO_ID, null))
				.isInstanceOf(ConvenioNotAccessibleException.class);
	}

	// =================================================================================
	// Referencias
	// =================================================================================

	@Test
	@DisplayName("un financiador dado de baja no admite convenios nuevos")
	void financiador_inactivo() {
		given(financiadores.findByIdAndOrganizationId(FINANCIADOR_ID, ORG_ID))
				.willReturn(Optional.of(financiador(false)));

		assertThatThrownBy(() -> service.crear(delMostrador, SEDE_ID, alta(ENERO, null)))
				.isInstanceOf(FinanciadorInactivoException.class);
	}

	@Test
	@DisplayName("un financiador de otro tenant es 404")
	void financiador_ajeno_404() {
		given(financiadores.findByIdAndOrganizationId(FINANCIADOR_ID, ORG_ID))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.crear(delMostrador, SEDE_ID, alta(ENERO, null)))
				.isInstanceOf(FinanciadorNotAccessibleException.class);
	}

	@Test
	@DisplayName("un plan dado de baja no admite convenios nuevos")
	void plan_inactivo() {
		given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID))
				.willReturn(Optional.of(plan(false, FINANCIADOR_ID)));

		assertThatThrownBy(() -> service.crear(delMostrador, SEDE_ID, alta(ENERO, null)))
				.isInstanceOf(PlanYaInactivoException.class);
	}

	@Test
	@DisplayName("un plan que NO es del financiador declarado es 404, no 400")
	void plan_de_otro_financiador() {
		// Los dos ids llegan en el mismo cuerpo y nada obliga a que sean coherentes. Un convenio
		// "con OSDE, plan de Swiss Medical" es un dato que despues nadie sabe interpretar.
		given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID))
				.willReturn(Optional.of(plan(true, 999L)));

		assertThatThrownBy(() -> service.crear(delMostrador, SEDE_ID, alta(ENERO, null)))
				.isInstanceOf(PlanNotAccessibleException.class);
	}

	@Test
	@DisplayName("el codigo repetido llega como 409 propio y no como un 500")
	void codigo_repetido() {
		willThrow(new DataIntegrityViolationException("uk_convenio_codigo_vigente"))
				.given(convenios).saveAndFlush(any());

		assertThatThrownBy(() -> service.crear(delMostrador, SEDE_ID, alta(ENERO, null)))
				.isInstanceOf(ConvenioCodigoTakenException.class);
	}

	// =================================================================================
	// Ciclo de vida
	// =================================================================================

	@Test
	@DisplayName("la baja audita cuantos aranceles activos arrastraba, y no los cascadea")
	void la_baja_audita_los_aranceles() {
		given(convenios.findByIdAndScope(CONVENIO_ID, ORG_ID, SEDE_ID))
				.willReturn(Optional.of(convenio(CONVENIO_ID, ENERO, DICIEMBRE)));
		given(aranceles.countActivosDeConvenio(ORG_ID, CONVENIO_ID)).willReturn(4L);

		ConvenioView baja = service.darDeBaja(
				delMostrador, SEDE_ID, CONVENIO_ID, "El centro dejo de trabajar con este plan");

		assertThat(baja.estado()).isEqualTo("INACTIVO");

		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(entrada.capture());
		assertThat(entrada.getValue().details()).containsEntry("arancelesActivos", "4");
		assertThat(entrada.getValue().consultorioId()).isEqualTo(SEDE_ID);
	}

	@Test
	@DisplayName("la baja exige motivo")
	void la_baja_exige_motivo() {
		given(convenios.findByIdAndScope(CONVENIO_ID, ORG_ID, SEDE_ID))
				.willReturn(Optional.of(convenio(CONVENIO_ID, ENERO, DICIEMBRE)));

		assertThatThrownBy(() -> service.darDeBaja(delMostrador, SEDE_ID, CONVENIO_ID, "  "))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("un convenio ya dado de baja no se edita ni se vuelve a dar de baja")
	void ya_inactivo() {
		Convenio dadoDeBaja = convenio(CONVENIO_ID, ENERO, DICIEMBRE);
		dadoDeBaja.deactivate(Instant.now(), "Renegociado");
		given(convenios.findByIdAndScope(CONVENIO_ID, ORG_ID, SEDE_ID))
				.willReturn(Optional.of(dadoDeBaja));

		assertThatThrownBy(() -> service.darDeBaja(delMostrador, SEDE_ID, CONVENIO_ID, "otra vez"))
				.isInstanceOf(ConvenioYaInactivoException.class);
		assertThatThrownBy(() -> service.editar(delMostrador, SEDE_ID, CONVENIO_ID,
				edicion("Otro", null, 0L)))
				.isInstanceOf(ConvenioYaInactivoException.class);
	}

	@Test
	@DisplayName("una version vieja es 409 y no pisa el cambio ajeno")
	void version_vieja() {
		Convenio propio = convenio(CONVENIO_ID, ENERO, DICIEMBRE);
		ReflectionTestUtils.setField(propio, "version", 3L);
		given(convenios.findByIdAndScope(CONVENIO_ID, ORG_ID, SEDE_ID))
				.willReturn(Optional.of(propio));

		assertThatThrownBy(() -> service.editar(delMostrador, SEDE_ID, CONVENIO_ID,
				edicion("Otro", null, 0L)))
				.isInstanceOf(OptimisticLockingFailureException.class);
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	@Test
	@DisplayName("el listado filtra por CICLO DE VIDA y por defecto trae solo los activos")
	void listado_filtra_por_estado() {
		Convenio activo = convenio(1L, ENERO, DICIEMBRE);
		Convenio dadoDeBaja = convenio(2L, ENERO, DICIEMBRE);
		dadoDeBaja.deactivate(Instant.now(), "Renegociado");
		given(convenios.findAllByScopeOrderByNombreAsc(ORG_ID, SEDE_ID))
				.willReturn(List.of(activo, dadoDeBaja));

		assertThat(service.listar(delMostrador, SEDE_ID, null, ENERO)).hasSize(1);
		assertThat(service.listar(delMostrador, SEDE_ID, EstadoFiltro.INACTIVO, ENERO)).hasSize(1);
		assertThat(service.listar(delMostrador, SEDE_ID, EstadoFiltro.TODOS, ENERO)).hasSize(2);
	}

	@Test
	@DisplayName("el listado NO filtra por vigencia: un convenio vencido sigue siendo ACTIVO")
	void listado_no_filtra_por_vigencia() {
		// Son dos cosas distintas y la grilla necesita las dos para explicar por que un convenio no
		// resuelve.
		given(convenios.findAllByScopeOrderByNombreAsc(ORG_ID, SEDE_ID))
				.willReturn(List.of(convenio(1L, ENERO, JUNIO_30)));

		List<ConvenioView> vistas = service.listar(delMostrador, SEDE_ID, null, JULIO);

		assertThat(vistas).singleElement().satisfies(vista -> {
			assertThat(vista.estado()).isEqualTo("ACTIVO");
			assertThat(vista.vigente()).isFalse();
		});
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static ConvenioAltaCommand alta(LocalDate desde, LocalDate hasta) {
		return new ConvenioAltaCommand(FINANCIADOR_ID, PLAN_ID, "OSDE-210", "OSDE 210",
				ModalidadConvenio.POR_PRESTACION, desde, hasta, "ARS",
				null, null, null, null, null, null);
	}

	private static ConvenioEdicionCommand edicion(String nombre, LocalDate hasta, long version) {
		return new ConvenioEdicionCommand(nombre, null, null, hasta, null, null, null, null, null,
				null, version);
	}

	private static Convenio convenio(long id, LocalDate desde, LocalDate hasta) {
		Convenio convenio = new Convenio(ORG_ID, SEDE_ID, FINANCIADOR_ID, PLAN_ID,
				"OSDE-210", "OSDE 210", ModalidadConvenio.POR_PRESTACION, desde, hasta, "ARS",
				false, false, true, null, null, null);
		ReflectionTestUtils.setField(convenio, "id", id);
		return convenio;
	}

	private static Financiador financiador(boolean operable) {
		Financiador financiador = new Financiador(ORG_ID, "OSDE", "OSDE", TipoFinanciador.PREPAGA,
				null, null, null, null);
		ReflectionTestUtils.setField(financiador, "id", FINANCIADOR_ID);
		if (!operable) {
			financiador.deactivate(Instant.now(), "Baja");
		}
		return financiador;
	}

	private static PlanCobertura plan(boolean operable, long financiadorId) {
		PlanCobertura plan = new PlanCobertura(ORG_ID, financiadorId, "210", "Plan 210", null,
				ENERO, null, false, true, null, null);
		ReflectionTestUtils.setField(plan, "id", PLAN_ID);
		if (!operable) {
			plan.deactivate(Instant.now(), "Baja");
		}
		return plan;
	}

	private static Convenio conId(Convenio convenio) {
		if (convenio.getId() == null) {
			ReflectionTestUtils.setField(convenio, "id", CONVENIO_ID);
		}
		return convenio;
	}
}
