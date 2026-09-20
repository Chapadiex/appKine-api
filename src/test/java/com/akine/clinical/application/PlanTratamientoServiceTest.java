package com.akine.clinical.application;

import com.akine.clinical.domain.CasoClinico;
import com.akine.clinical.domain.EstadoPlan;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.PlanEvento;
import com.akine.clinical.domain.PlanItem;
import com.akine.clinical.domain.PlanTratamiento;
import com.akine.clinical.domain.PlanTratamientoVersion;
import com.akine.clinical.domain.TipoEventoPlan;
import com.akine.clinical.domain.exception.CasoNoActivoException;
import com.akine.clinical.domain.exception.OfertaNoHabilitadaException;
import com.akine.clinical.domain.exception.PlanNoEditableException;
import com.akine.clinical.domain.exception.PlanTratamientoNotAccessibleException;
import com.akine.clinical.domain.exception.TransicionDePlanInvalidaException;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoClinicoRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanEventoRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanItemRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanNumeradorPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanTratamientoRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanTratamientoVersionRepositoryPort;
import com.akine.clinical.spi.RealizadoEnElCasoProbe;
import com.akine.clinical.spi.RealizadoPorOferta;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.AutorizacionDirectory;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * El Plan de Tratamiento: lo planificado se escribe, lo realizado se deriva.
 *
 * <p>Lo que estos tests fijan son las cosas que cuestan caro si se rompen, y casi todas son la
 * misma: <b>planificado no es realizado</b>. Que el avance salga de la sonda y no de una columna,
 * que completar la cantidad no finalice nada, que los items cuelguen de la version y que la version
 * anterior quede intacta, que el correlativo salga del numerador con su fila asegurada afuera, y
 * que activar un plan finalice el anterior antes de activarse.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PlanTratamientoService")
class PlanTratamientoServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;
	private static final long HC_ID = 700L;
	private static final long CASO_ID = 900L;
	private static final long PLAN_ID = 77L;
	private static final long OTRO_PLAN_ID = 78L;
	private static final long VERSION_ID = 210L;
	private static final long OFERTA_ID = 42L;
	private static final long OTRA_OFERTA_ID = 43L;

	@Mock
	private HistoriaClinicaRepositoryPort historias;

	@Mock
	private CasoClinicoRepositoryPort casos;

	@Mock
	private PlanTratamientoRepositoryPort planes;

	@Mock
	private PlanTratamientoVersionRepositoryPort versiones;

	@Mock
	private PlanItemRepositoryPort items;

	@Mock
	private PlanEventoRepositoryPort eventos;

	@Mock
	private PlanNumeradorPort numerador;

	@Mock
	private PlanNumeradorIniciador numeradorIniciador;

	@Mock
	private OfertaDirectory ofertas;

	@Mock
	private RealizadoEnElCasoProbe realizado;

	/** AKINE-04.05: el servicio gano RF-M11-007 y con el la lectura de autorizaciones de M17. */
	@Mock
	private AutorizacionDirectory autorizaciones;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private RelacionAsistencialProbe relaciones;

	@Mock
	private AuditTrail auditTrail;

	@Mock
	private ClinicalSupportAccessAuditor supportAccessAuditor;

	private PlanTratamientoService service;

	private final OperatingActor profesional =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		service = new PlanTratamientoService(historias, casos, planes, versiones, items, eventos,
				numerador, numeradorIniciador, ofertas, realizado, autorizaciones,
				permissionGuard, relaciones, auditTrail, supportAccessAuditor);

		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
		given(relaciones.tieneRelacionAsistencial(anyLong(), anyLong(), anyLong(), anyLong()))
				.willReturn(true);
		given(historias.findByIdAndOrganizationId(HC_ID, ORG_ID))
				.willReturn(Optional.of(historia()));
		given(casos.findByIdAndOrganizationId(CASO_ID, ORG_ID))
				.willReturn(Optional.of(caso(true)));
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(Optional.of(oferta(true)));
		given(ofertas.find(ORG_ID, SEDE_ID, OTRA_OFERTA_ID))
				.willReturn(Optional.of(otraOferta()));
		given(numerador.leerUltimo(ORG_ID, CASO_ID)).willReturn(2);

		// JPA asigna el id al persistir; los dobles tienen que hacer lo mismo o los fixtures
		// representarian filas guardadas sin id, que en produccion no ocurre.
		// Solo cuando falta: pisar el id de una fila que ya lo tiene haria que el plan anterior y
		// el nuevo compartan identidad en el test de activacion, que es justamente lo que ese
		// test distingue.
		given(planes.save(any())).willAnswer(i -> idSiFalta(i.getArgument(0), PLAN_ID));
		given(planes.saveAndFlush(any())).willAnswer(i -> idSiFalta(i.getArgument(0), PLAN_ID));
		given(versiones.save(any())).willAnswer(i -> i.getArgument(0));
		given(versiones.saveAndFlush(any())).willAnswer(i -> idSiFalta(i.getArgument(0), VERSION_ID));
		given(items.saveAll(any())).willAnswer(i -> {
			List<PlanItem> guardados = i.getArgument(0);
			guardados.forEach(item -> {
				if (item.getId() == null) {
					ReflectionTestUtils.setField(item, "id", 1L);
				}
			});
			return guardados;
		});
		given(items.buscarDeVersion(anyLong(), anyLong())).willReturn(List.of());
		given(eventos.save(any())).willAnswer(i -> i.getArgument(0));
		given(planes.buscarActivoDelCaso(ORG_ID, CASO_ID)).willReturn(Optional.empty());
		given(realizado.contarPorOfertaEnElCaso(anyLong(), anyLong())).willReturn(List.of());
	}

	// =================================================================================
	// Alta
	// =================================================================================

	@Nested
	@DisplayName("Crear")
	class Crear {

		@Test
		@DisplayName("el correlativo sale del numerador, y su fila se asegura ANTES de bloquearla")
		void el_correlativo_sale_del_numerador() {
			// La trampa que este repositorio ya pago cuatro veces: crear la fila del numerador
			// DENTRO de la transaccion que despues la bloquea produce deadlock, y el try/catch no
			// salva porque atrapar una excepcion de persistencia no des-marca la transaccion.
			PlanTratamientoView vista = service.crear(profesional, CASO_ID, contenido(), null);

			InOrder orden = inOrder(numeradorIniciador, numerador);
			orden.verify(numeradorIniciador).asegurarPlanes(ORG_ID, CASO_ID);
			orden.verify(numerador).incrementar(ORG_ID, CASO_ID);
			orden.verify(numerador).leerUltimo(ORG_ID, CASO_ID);

			assertThat(vista.numeroPlan())
					.as("el numero es el que entrego el numerador, no un MAX+1")
					.isEqualTo(2);
		}

		@Test
		@DisplayName("una oferta no habilitada detiene el alta ANTES de consumir un correlativo")
		void la_oferta_se_valida_antes_del_numerador() {
			// Regla 4 del Paquete B: si el numero se pidiera primero, cada alta rechazada dejaria
			// un hueco en la numeracion del caso que parece un plan borrado.
			given(ofertas.find(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(Optional.of(oferta(false)));

			assertThatThrownBy(() -> service.crear(profesional, CASO_ID, contenido(), null))
					.isInstanceOf(OfertaNoHabilitadaException.class)
					.extracting(e -> ((OfertaNoHabilitadaException) e).getOfertaId())
					.isEqualTo(OFERTA_ID);

			verify(numerador, never()).incrementar(anyLong(), anyLong());
		}

		@Test
		@DisplayName("el plan nace en BORRADOR aunque venga completo")
		void nace_en_borrador() {
			// Activar tiene un efecto que no se deshace —finaliza el plan vigente del caso— asi
			// que es una decision explicita y otra operacion.
			assertThat(service.crear(profesional, CASO_ID, contenido(), null).estado())
					.isEqualTo("BORRADOR");
		}

		@Test
		@DisplayName("un caso cerrado no admite planes nuevos")
		void caso_cerrado_no_admite_planes() {
			given(casos.findByIdAndOrganizationId(CASO_ID, ORG_ID))
					.willReturn(Optional.of(caso(false)));

			assertThatThrownBy(() -> service.crear(profesional, CASO_ID, contenido(), null))
					.isInstanceOf(CasoNoActivoException.class);
		}

		@Test
		@DisplayName("la misma oferta dos veces se rechaza aca y no contra el unique de la base")
		void oferta_repetida() {
			// Un 409 de integridad no le dice al profesional CUAL de las cinco practicas repitio.
			// Y ademas, dos items de la misma oferta harian que el avance contara las mismas
			// sesiones dos veces.
			ContenidoDelPlan repetida = new ContenidoDelPlan("Objetivos", null, 3, 8, List.of(
					new PlanItemPlanificado(OFERTA_ID, 20, null),
					new PlanItemPlanificado(OFERTA_ID, 5, null)));

			assertThatThrownBy(() -> service.crear(profesional, CASO_ID, repetida, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining(String.valueOf(OFERTA_ID));
		}

		@Test
		@DisplayName("el item congela el nombre y el servicio de la oferta")
		void el_item_congela_el_snapshot() {
			// Un plan de seis meses se lee con los nombres que tenia al planificarse. Sin el
			// snapshot, el plan de marzo se lee en septiembre con los nombres de septiembre.
			service.crear(profesional, CASO_ID, contenido(), null);

			@SuppressWarnings("unchecked")
			ArgumentCaptor<List<PlanItem>> escritos = ArgumentCaptor.forClass(List.class);
			verify(items).saveAll(escritos.capture());

			PlanItem item = escritos.getValue().get(0);
			assertThat(item.getOfertaNombre()).isEqualTo("Kinesiologia");
			assertThat(item.getServicioId()).isEqualTo(1L);
			assertThat(item.getPlanTratamientoVersionId())
					.as("el item cuelga de la VERSION, no del plan")
					.isEqualTo(VERSION_ID);
		}

		@Test
		@DisplayName("la creacion queda en el historial y es el unico evento sin estado anterior")
		void la_creacion_se_asienta() {
			service.crear(profesional, CASO_ID, contenido(), null);

			ArgumentCaptor<PlanEvento> evento = ArgumentCaptor.forClass(PlanEvento.class);
			verify(eventos).save(evento.capture());
			assertThat(evento.getValue().getTipo()).isEqualTo(TipoEventoPlan.CREACION);
			assertThat(evento.getValue().getEstadoAnterior()).isNull();
		}
	}

	// =================================================================================
	// Modificar
	// =================================================================================

	@Nested
	@DisplayName("Modificar")
	class Modificar {

		@Test
		@DisplayName("un BORRADOR se edita en el lugar: no crea version y reemplaza sus items")
		void el_borrador_no_versiona() {
			PlanTratamiento plan = plan(EstadoPlan.BORRADOR);
			given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.of(plan));
			given(versiones.buscarVigente(ORG_ID, PLAN_ID))
					.willReturn(Optional.of(version(plan, 1, null)));

			service.modificar(profesional, PLAN_ID, contenido(), null, 0L, null);

			verify(versiones, never()).saveAndFlush(any());
			verify(items).borrarDeVersionEnBorrador(ORG_ID, VERSION_ID);
			assertThat(plan.getUltimoNumeroVersion())
					.as("un plan que nunca se activo no tiene historia que preservar")
					.isEqualTo(1);
		}

		@Test
		@DisplayName("un plan ACTIVO versiona, y la version anterior no se toca")
		void el_plan_activo_versiona() {
			// RN-M11-003: modificar el plan no cambia tratamientos historicos. Los items cuelgan
			// de la version, asi que la nueva trae su propio juego y los viejos quedan intactos.
			PlanTratamiento plan = plan(EstadoPlan.ACTIVO);
			given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.of(plan));

			service.modificar(profesional, PLAN_ID, contenido(), "Bajamos la carga", 0L, null);

			ArgumentCaptor<PlanTratamientoVersion> escrita =
					ArgumentCaptor.forClass(PlanTratamientoVersion.class);
			verify(versiones).saveAndFlush(escrita.capture());
			assertThat(escrita.getValue().getNumeroVersion()).isEqualTo(2);
			assertThat(escrita.getValue().getMotivoModificacion()).isEqualTo("Bajamos la carga");

			verify(items, never()).borrarDeVersionEnBorrador(anyLong(), anyLong());
			assertThat(plan.getUltimoNumeroVersion())
					.as("el numero sale del contador de la cabecera, nunca de un MAX+1")
					.isEqualTo(2);
		}

		@Test
		@DisplayName("modificar un plan vigente sin motivo es 400, no 409")
		void modificar_sin_motivo() {
			// No hay conflicto de estado: falta un dato del pedido, y reintentar sin motivo
			// vuelve a fallar exactamente igual.
			PlanTratamiento plan = plan(EstadoPlan.ACTIVO);
			given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.of(plan));

			assertThatThrownBy(
					() -> service.modificar(profesional, PLAN_ID, contenido(), "  ", 0L, null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("un plan FINALIZADO no admite cambios: 409 y no 404")
		void finalizado_no_editable() {
			PlanTratamiento plan = plan(EstadoPlan.FINALIZADO);
			given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.of(plan));

			assertThatThrownBy(
					() -> service.modificar(profesional, PLAN_ID, contenido(), "x", 0L, null))
					.isInstanceOf(PlanNoEditableException.class);
		}

		@Test
		@DisplayName("una version vieja es 409 antes de escribir nada")
		void version_vieja() {
			PlanTratamiento plan = plan(EstadoPlan.ACTIVO);
			ReflectionTestUtils.setField(plan, "version", 4L);
			given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.of(plan));

			assertThatThrownBy(
					() -> service.modificar(profesional, PLAN_ID, contenido(), "x", 1L, null))
					.isInstanceOf(OptimisticLockingFailureException.class);

			verify(versiones, never()).saveAndFlush(any());
		}
	}

	// =================================================================================
	// Transiciones
	// =================================================================================

	@Nested
	@DisplayName("Transiciones")
	class Transiciones {

		@Test
		@DisplayName("activar finaliza el plan vigente ANTES de activar el nuevo")
		void activar_finaliza_el_anterior() {
			// El unique de plan activo se evalua fila por fila: si el UPDATE del plan nuevo
			// saliera primero, chocaria contra el anterior dentro de la misma transaccion. El
			// orden no se le deja al ORM.
			PlanTratamiento nuevo = plan(EstadoPlan.BORRADOR);
			PlanTratamiento anterior = otroPlanActivo();
			given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.of(nuevo));
			given(planes.buscarActivoDelCaso(ORG_ID, CASO_ID)).willReturn(Optional.of(anterior));

			service.activar(profesional, PLAN_ID, 0L, null);

			ArgumentCaptor<PlanTratamiento> guardados =
					ArgumentCaptor.forClass(PlanTratamiento.class);
			verify(planes, org.mockito.Mockito.times(2)).saveAndFlush(guardados.capture());

			assertThat(guardados.getAllValues().get(0))
					.as("el anterior se guarda y se flushea primero")
					.isSameAs(anterior);
			assertThat(anterior.getEstado()).isEqualTo(EstadoPlan.FINALIZADO);
			assertThat(anterior.getMotivoFinalizacion())
					.as("la finalizacion automatica deja su motivo, no un hueco")
					.contains("activar el plan");
			assertThat(nuevo.getEstado()).isEqualTo(EstadoPlan.ACTIVO);
		}

		@Test
		@DisplayName("activar lo ya activo es el mismo pedido, no un conflicto")
		void activar_lo_activo_es_idempotente() {
			PlanTratamiento plan = plan(EstadoPlan.ACTIVO);
			given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.of(plan));

			// expectedVersion desactualizada a proposito: un reintento no puede fallar por una
			// version que avanzo justamente por la transicion que ya ocurrio.
			assertThat(service.activar(profesional, PLAN_ID, 99L, null).estado())
					.isEqualTo("ACTIVO");
			verify(planes, never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("un FINALIZADO no se reabre: activar es 409 con el estado adentro")
		void finalizado_no_se_reabre() {
			PlanTratamiento plan = plan(EstadoPlan.FINALIZADO);
			given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.of(plan));

			assertThatThrownBy(() -> service.activar(profesional, PLAN_ID, 0L, null))
					.isInstanceOf(TransicionDePlanInvalidaException.class)
					.extracting(e -> ((TransicionDePlanInvalidaException) e).getEstadoActual())
					.isEqualTo("FINALIZADO");
		}

		@Test
		@DisplayName("suspender exige motivo y no libera el lugar del plan activo")
		void suspender_exige_motivo() {
			PlanTratamiento plan = plan(EstadoPlan.ACTIVO);
			given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.of(plan));

			assertThatThrownBy(() -> service.suspender(profesional, PLAN_ID, "  ", 0L, null))
					.isInstanceOf(IllegalArgumentException.class);

			service.suspender(profesional, PLAN_ID, "El paciente viaja", 0L, null);
			assertThat(plan.getEstado()).isEqualTo(EstadoPlan.SUSPENDIDO);
			assertThat(plan.getMotivoSuspension()).isEqualTo("El paciente viaja");
		}

		@Test
		@DisplayName("reanudar limpia la suspension; el motivo viejo vive en el historial")
		void reanudar_limpia_la_suspension() {
			PlanTratamiento plan = plan(EstadoPlan.SUSPENDIDO);
			given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.of(plan));

			service.reanudar(profesional, PLAN_ID, 0L, null);

			assertThat(plan.getEstado()).isEqualTo(EstadoPlan.ACTIVO);
			assertThat(plan.getSuspendidoEn()).isNull();
			assertThat(plan.getMotivoSuspension()).isNull();

			ArgumentCaptor<PlanEvento> evento = ArgumentCaptor.forClass(PlanEvento.class);
			verify(eventos).save(evento.capture());
			assertThat(evento.getValue().getTipo()).isEqualTo(TipoEventoPlan.REANUDACION);
		}

		@Test
		@DisplayName("reanudar un BORRADOR es 409: lo que corresponde ahi es activarlo")
		void reanudar_un_borrador() {
			PlanTratamiento plan = plan(EstadoPlan.BORRADOR);
			given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.of(plan));

			assertThatThrownBy(() -> service.reanudar(profesional, PLAN_ID, 0L, null))
					.isInstanceOf(TransicionDePlanInvalidaException.class);
		}

		@Test
		@DisplayName("finalizar dos veces conserva el motivo original")
		void finalizar_dos_veces() {
			PlanTratamiento plan = plan(EstadoPlan.ACTIVO);
			given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.of(plan));

			service.finalizar(profesional, PLAN_ID, "Alta por objetivos cumplidos", 0L, null);
			service.finalizar(profesional, PLAN_ID, "Otro motivo", 0L, null);

			assertThat(plan.getMotivoFinalizacion())
					.as("pisarlo con el nuevo perderia el que explica el final")
					.isEqualTo("Alta por objetivos cumplidos");
		}
	}

	// =================================================================================
	// Avance — la etapa entera
	// =================================================================================

	@Nested
	@DisplayName("Avance")
	class Avance {

		@Test
		@DisplayName("lo realizado sale de la sonda y no de ninguna columna")
		void el_avance_se_deriva() {
			prepararAvance(version(plan(EstadoPlan.ACTIVO), 1, null));
			given(realizado.contarPorOfertaEnElCaso(ORG_ID, CASO_ID))
					.willReturn(List.of(new RealizadoPorOferta(OFERTA_ID, 8, 2)));

			AvanceDelPlanView avance = service.avance(profesional, PLAN_ID, null, null);

			assertThat(avance.items()).hasSize(1);
			assertThat(avance.items().get(0).realizadas()).isEqualTo(8);
			assertThat(avance.items().get(0).canceladas()).isEqualTo(2);
			assertThat(avance.items().get(0).restantes()).isEqualTo(12);
			assertThat(avance.numeroVersion())
					.as("la respuesta dice de que version habla: 8 de 20 y 8 de 24 son los dos "
							+ "correctos, pero de versiones distintas")
					.isEqualTo(1);
		}

		@Test
		@DisplayName("una practica sin sesiones resuelve en cero, no desaparece")
		void sin_sesiones_es_cero() {
			prepararAvance(version(plan(EstadoPlan.ACTIVO), 1, null));
			given(realizado.contarPorOfertaEnElCaso(ORG_ID, CASO_ID)).willReturn(List.of());

			assertThat(service.avance(profesional, PLAN_ID, null, null).items().get(0).realizadas())
					.isZero();
		}

		@Test
		@DisplayName("completar la cantidad AVISA y no finaliza el plan ni toca su estado")
		void completo_no_finaliza() {
			// RN-M11-004. Es la tentacion que mas se parece a una mejora de producto, y por eso
			// es la mas peligrosa: un cierre automatico por contador es confundir planificado con
			// realizado en la direccion contraria.
			PlanTratamiento plan = plan(EstadoPlan.ACTIVO);
			prepararAvance(version(plan, 1, null));
			given(realizado.contarPorOfertaEnElCaso(ORG_ID, CASO_ID))
					.willReturn(List.of(new RealizadoPorOferta(OFERTA_ID, 25, 0)));

			AvanceDelPlanView avance = service.avance(profesional, PLAN_ID, null, null);

			assertThat(avance.completo()).isTrue();
			assertThat(avance.items().get(0).restantes())
					.as("realizar de mas no es un error: es un tratamiento que se extendio")
					.isZero();
			assertThat(plan.getEstado()).isEqualTo(EstadoPlan.ACTIVO);
			verify(planes, never()).saveAndFlush(any());
			verify(planes, never()).save(any());
		}

		@Test
		@DisplayName("un plan sin practicas nunca esta completo")
		void sin_practicas_no_esta_completo() {
			PlanTratamiento plan = plan(EstadoPlan.ACTIVO);
			PlanTratamientoVersion version = version(plan, 1, null);
			given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.of(plan));
			given(versiones.buscarVigente(ORG_ID, PLAN_ID)).willReturn(Optional.of(version));
			given(items.buscarDeVersion(ORG_ID, VERSION_ID)).willReturn(List.of());

			assertThat(service.avance(profesional, PLAN_ID, null, null).completo()).isFalse();
		}

		@Test
		@DisplayName("pedir una version que no existe es 400 y no 404: el plan si existe")
		void version_inexistente() {
			given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID))
					.willReturn(Optional.of(plan(EstadoPlan.ACTIVO)));
			given(versiones.buscarPorNumero(ORG_ID, PLAN_ID, 9)).willReturn(Optional.empty());

			assertThatThrownBy(() -> service.avance(profesional, PLAN_ID, 9, null))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	// =================================================================================
	// Alcance
	// =================================================================================

	@Test
	@DisplayName("un plan de otro tenant es 404, indistinguible de uno que no existe")
	void plan_de_otro_tenant() {
		given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.ver(profesional, PLAN_ID, null))
				.isInstanceOf(PlanTratamientoNotAccessibleException.class);
	}

	@Test
	@DisplayName("toda lectura de un plan deja evento de auditoria (DP-03)")
	void la_lectura_se_audita() {
		PlanTratamiento plan = plan(EstadoPlan.ACTIVO);
		given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.of(plan));
		given(versiones.buscarVigente(ORG_ID, PLAN_ID))
				.willReturn(Optional.of(version(plan, 1, null)));

		service.ver(profesional, PLAN_ID, "El paciente pregunto por su tratamiento");

		verify(auditTrail).record(any());
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private void prepararAvance(PlanTratamientoVersion version) {
		PlanTratamiento plan = plan(EstadoPlan.ACTIVO);
		given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.of(plan));
		given(versiones.buscarVigente(ORG_ID, PLAN_ID)).willReturn(Optional.of(version));
		given(items.buscarDeVersion(ORG_ID, VERSION_ID)).willReturn(List.of(item()));
	}

	private static ContenidoDelPlan contenido() {
		return new ContenidoDelPlan("Recuperar flexion completa", "Frio local", 3, 8,
				List.of(new PlanItemPlanificado(OFERTA_ID, 20, 10)));
	}

	private static PlanTratamiento plan(EstadoPlan estado) {
		PlanTratamiento plan = new PlanTratamiento(ORG_ID, CASO_ID, 2, Instant.EPOCH, ACCOUNT_ID);
		ReflectionTestUtils.setField(plan, "id", PLAN_ID);
		ReflectionTestUtils.setField(plan, "estado", estado);
		if (estado != EstadoPlan.BORRADOR) {
			ReflectionTestUtils.setField(plan, "activadoEn", Instant.EPOCH);
			ReflectionTestUtils.setField(plan, "activadoPor", ACCOUNT_ID);
		}
		if (estado == EstadoPlan.SUSPENDIDO) {
			ReflectionTestUtils.setField(plan, "suspendidoEn", Instant.EPOCH);
			ReflectionTestUtils.setField(plan, "motivoSuspension", "Motivo previo");
		}
		return plan;
	}

	private static PlanTratamiento otroPlanActivo() {
		PlanTratamiento plan = new PlanTratamiento(ORG_ID, CASO_ID, 1, Instant.EPOCH, ACCOUNT_ID);
		ReflectionTestUtils.setField(plan, "id", OTRO_PLAN_ID);
		ReflectionTestUtils.setField(plan, "estado", EstadoPlan.ACTIVO);
		ReflectionTestUtils.setField(plan, "activadoEn", Instant.EPOCH);
		ReflectionTestUtils.setField(plan, "activadoPor", ACCOUNT_ID);
		return plan;
	}

	private static PlanTratamientoVersion version(
			PlanTratamiento plan, int numero, String motivo) {

		PlanTratamientoVersion version = new PlanTratamientoVersion(ORG_ID, plan.getId(), numero,
				"Recuperar flexion completa", null, 3, 8, motivo, Instant.EPOCH, ACCOUNT_ID);
		ReflectionTestUtils.setField(version, "id", VERSION_ID);
		return version;
	}

	private static PlanItem item() {
		PlanItem item = new PlanItem(ORG_ID, VERSION_ID, OFERTA_ID, SEDE_ID, "Kinesiologia", 1L,
				20, 10);
		ReflectionTestUtils.setField(item, "id", 301L);
		return item;
	}

	private static HistoriaClinica historia() {
		HistoriaClinica historia =
				new HistoriaClinica(ORG_ID, PERSONA_ID, Instant.EPOCH, ACCOUNT_ID);
		ReflectionTestUtils.setField(historia, "id", HC_ID);
		return historia;
	}

	private static CasoClinico caso(boolean activo) {
		CasoClinico caso = new CasoClinico(ORG_ID, HC_ID, 3, OFERTA_ID, SEDE_ID,
				"Gonalgia derecha", null, Instant.EPOCH, ACCOUNT_ID);
		ReflectionTestUtils.setField(caso, "id", CASO_ID);
		if (!activo) {
			caso.cerrar("Alta", Instant.EPOCH, ACCOUNT_ID);
		}
		return caso;
	}

	private static OfertaSnapshot oferta(boolean vigente) {
		return new OfertaSnapshot(OFERTA_ID, ORG_ID, SEDE_ID, 1L, "Kinesiologia", 45, 1, false,
				true, false, true, true, LocalDate.of(2020, 1, 1),
				vigente ? null : LocalDate.of(2021, 1, 1), true);
	}

	private static OfertaSnapshot otraOferta() {
		return new OfertaSnapshot(OTRA_OFERTA_ID, ORG_ID, SEDE_ID, 2L, "Fisioterapia", 30, 1, false,
				true, false, true, true, LocalDate.of(2020, 1, 1), null, true);
	}

	private static <T> T idSiFalta(T entidad, long id) {
		if (ReflectionTestUtils.getField(entidad, "id") == null) {
			ReflectionTestUtils.setField(entidad, "id", id);
		}
		return entidad;
	}
}
