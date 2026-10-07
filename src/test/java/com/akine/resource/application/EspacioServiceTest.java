package com.akine.resource.application;

import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.Espacio;
import com.akine.resource.domain.EspacioTipo;
import com.akine.resource.domain.PermissionCodes;
import com.akine.resource.domain.exception.ConsultorioNotAccessibleException;
import com.akine.resource.domain.exception.ConsultorioNotOperableException;
import com.akine.resource.domain.exception.EspacioCapacityBelowOccupancyException;
import com.akine.resource.domain.exception.EspacioHasActiveReferencesException;
import com.akine.resource.domain.exception.EspacioInactiveException;
import com.akine.resource.domain.exception.EspacioNameTakenException;
import com.akine.resource.domain.exception.EspacioNotAccessibleException;
import com.akine.resource.domain.port.EspacioRepositoryPort;
import com.akine.resource.spi.EspacioOccupancyProbe;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Las reglas que {@link EspacioService} decide por si mismo, sin base de datos.
 *
 * <p>Hasta aca el servicio solo tenia ITs ({@code EspaciosIT}, {@code EspaciosConcurrenteIT}).
 * Esto no repite lo que ellos prueban contra MySQL —el lock, la concurrencia, el unique real—:
 * cubre el orden de las comprobaciones (pertenencia antes que permiso, contexto antes que
 * evaluador), las ramas de rechazo que no llegan a escribir, y que la auditoria salga con el
 * evento y el estado correctos.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EspacioServiceTest {

	private static final long ORG_ID = 10L;
	private static final long CONSULTORIO_ID = 20L;
	private static final long ACCOUNT_ID = 40L;
	private static final long ESPACIO_ID = 501L;

	@Mock
	private EspacioRepositoryPort espacios;

	@Mock
	private ConsultorioDirectory consultorioDirectory;

	@Mock
	private AccountContextDirectory accountContextDirectory;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private AuditTrail auditTrail;

	@Mock
	private SupportAccessAuditor supportAccessAuditor;

	@Mock
	private EspacioOccupancyProbe turnos;

	@Mock
	private EspacioOccupancyProbe inscripciones;

	private EspacioService service;

	private final OperatingActor actor =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		service = new EspacioService(
				espacios, consultorioDirectory, accountContextDirectory,
				permissionGuard, auditTrail, supportAccessAuditor, List.of(turnos, inscripciones));

		given(consultorioDirectory.find(ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(sede(true)));
		given(accountContextDirectory.hasActiveMembership(ACCOUNT_ID, ORG_ID)).willReturn(true);
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
		given(turnos.peakOccupancyFrom(anyLong(), anyLong(), any()))
				.willReturn(EspacioOccupancyProbe.Occupancy.ninguna());
		given(inscripciones.peakOccupancyFrom(anyLong(), anyLong(), any()))
				.willReturn(EspacioOccupancyProbe.Occupancy.ninguna());
		given(espacios.saveAndFlush(any())).willAnswer(invocacion -> conId(invocacion.getArgument(0)));
		given(espacios.save(any())).willAnswer(invocacion -> invocacion.getArgument(0));
	}

	// =================================================================================
	// Primer box en el alta de la sede (A-8)
	// =================================================================================

	@Test
	@DisplayName("El primer box de una sede nueva es un BOX, se audita y no reevalua permisos ni relee la sede")
	void el_primer_box_de_la_sede_nueva() {
		EspacioView box = service.crearPrimerBoxDeSedeNueva(
				ORG_ID, CONSULTORIO_ID, ACCOUNT_ID, "  Box 1 ", null);

		assertThat(box.tipo()).isEqualTo(EspacioTipo.BOX.name());
		assertThat(box.name()).isEqualTo("Box 1");
		assertThat(box.capacidad()).isEqualTo(1);
		verify(auditTrail).record(any());
		// organization ya exigio consultorio:manage con alcance organizacion en la misma
		// transaccion, y la sede no esta commiteada: ni permiso ni relectura.
		verifyNoInteractions(permissionGuard, consultorioDirectory, supportAccessAuditor);
	}

	@Test
	@DisplayName("Un primer box sin nombre se rechaza: el alta entera revierte")
	void el_primer_box_sin_nombre_se_rechaza() {
		assertThatThrownBy(() -> service.crearPrimerBoxDeSedeNueva(
				ORG_ID, CONSULTORIO_ID, ACCOUNT_ID, " ", null))
				.isInstanceOf(IllegalArgumentException.class);
		verify(espacios, never()).saveAndFlush(any());
	}

	// =================================================================================
	// Lecturas: pertenencia antes que permiso
	// =================================================================================

	@Test
	@DisplayName("Leer espacios de otro tenant es 404 y el evaluador de permisos ni se consulta")
	void la_lectura_de_un_tenant_ajeno_es_404_antes_del_permiso() {
		long otraOrganizacion = 99L;

		assertThatThrownBy(() -> service.find(actor, otraOrganizacion, CONSULTORIO_ID, ESPACIO_ID))
				.isInstanceOf(ConsultorioNotAccessibleException.class);

		// Un 403 del evaluador confirmaria que esa organizacion existe.
		verifyNoInteractions(permissionGuard);
	}

	@Test
	@DisplayName("Sin membership activa en la organizacion del contexto, la lectura tambien es 404")
	void la_lectura_sin_membership_activa_es_404() {
		given(accountContextDirectory.hasActiveMembership(ACCOUNT_ID, ORG_ID)).willReturn(false);

		assertThatThrownBy(() -> service.list(actor, ORG_ID, CONSULTORIO_ID, EspacioEstadoFiltro.ACTIVO))
				.isInstanceOf(ConsultorioNotAccessibleException.class);
		verifyNoInteractions(permissionGuard);
	}

	@Test
	@DisplayName("Sin contexto de trabajo la lectura es 403, no 404 ni 401")
	void la_lectura_sin_contexto_es_403() {
		OperatingActor sinContexto = new OperatingActor(ACCOUNT_ID, false, null, null);

		assertThatThrownBy(() -> service.find(sinContexto, ORG_ID, CONSULTORIO_ID, ESPACIO_ID))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("La lectura exige espacio:read con la sede como alcance")
	void la_lectura_exige_espacio_read_sobre_la_sede() {
		given(espacios.findByIdAndOrganizationIdAndConsultorioId(ESPACIO_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(espacioActivo(4)));

		EspacioView vista = service.find(actor, ORG_ID, CONSULTORIO_ID, ESPACIO_ID);

		assertThat(vista.id()).isEqualTo(ESPACIO_ID);
		ArgumentCaptor<PermissionQuery> consulta = ArgumentCaptor.forClass(PermissionQuery.class);
		verify(permissionGuard).requirePermission(consulta.capture());
		assertThat(consulta.getValue().permissionCode()).isEqualTo(PermissionCodes.ESPACIO_READ);
		assertThat(consulta.getValue().consultorioId()).isEqualTo(CONSULTORIO_ID);
	}

	@Test
	@DisplayName("Un espacio que no resuelve dentro del tenant y la sede es 404")
	void un_espacio_inexistente_es_404() {
		given(espacios.findByIdAndOrganizationIdAndConsultorioId(ESPACIO_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.find(actor, ORG_ID, CONSULTORIO_ID, ESPACIO_ID))
				.isInstanceOf(EspacioNotAccessibleException.class);
	}

	@Test
	@DisplayName("El PLATFORM_ADMIN lee via soporte: no pasa por membership y deja SUPPORT_ACCESS_USED")
	void el_platform_admin_lee_via_soporte_y_lo_registra() {
		OperatingActor plataforma = new OperatingActor(ACCOUNT_ID, true, null, null);
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("SOPORTE", true));
		given(espacios.findAllByOrganizationIdAndConsultorioIdOrderByNameAsc(ORG_ID, CONSULTORIO_ID))
				.willReturn(List.of());

		service.list(plataforma, ORG_ID, CONSULTORIO_ID, EspacioEstadoFiltro.TODOS);

		verifyNoInteractions(accountContextDirectory);
		ArgumentCaptor<AuditEntry> uso = ArgumentCaptor.forClass(AuditEntry.class);
		verify(supportAccessAuditor).record(uso.capture());
		assertThat(uso.getValue().eventType()).isEqualTo("SUPPORT_ACCESS_USED");
		assertThat(uso.getValue().organizationId()).isEqualTo(ORG_ID);
		// En una lectura el uso va por su propia transaccion, nunca por la del negocio.
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("El filtro INACTIVO pide a la base los dados de baja, no los filtra en memoria")
	void el_filtro_inactivo_consulta_active_false() {
		given(espacios.findAllByOrganizationIdAndConsultorioIdAndActiveOrderByNameAsc(
				ORG_ID, CONSULTORIO_ID, false)).willReturn(List.of());

		service.list(actor, ORG_ID, CONSULTORIO_ID, EspacioEstadoFiltro.INACTIVO);

		verify(espacios).findAllByOrganizationIdAndConsultorioIdAndActiveOrderByNameAsc(
				ORG_ID, CONSULTORIO_ID, false);
	}

	// =================================================================================
	// Disponibilidad
	// =================================================================================

	@Test
	@DisplayName("La ventana de disponibilidad no puede superar los 31 dias")
	void la_ventana_de_disponibilidad_esta_acotada() {
		Instant desde = Instant.parse("2026-03-01T00:00:00Z");

		assertThatThrownBy(() -> service.disponibilidad(
				actor, ORG_ID, CONSULTORIO_ID, desde, desde.plus(32, ChronoUnit.DAYS)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.disponibilidad(
				actor, ORG_ID, CONSULTORIO_ID, desde, desde))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("Los lugares comprometidos son el MAXIMO de las sondas, no la suma")
	void la_disponibilidad_toma_el_maximo_de_las_sondas() {
		Instant desde = Instant.parse("2026-03-01T09:00:00Z");
		Instant hasta = desde.plus(1, ChronoUnit.HOURS);
		given(espacios.findEnServicio(ORG_ID, CONSULTORIO_ID, desde, hasta))
				.willReturn(List.of(espacioActivo(5)));
		given(turnos.peakOccupancyFrom(ORG_ID, ESPACIO_ID, desde))
				.willReturn(new EspacioOccupancyProbe.Occupancy("TURNO", 2));
		given(inscripciones.peakOccupancyFrom(ORG_ID, ESPACIO_ID, desde))
				.willReturn(new EspacioOccupancyProbe.Occupancy("INSCRIPCION", 3));

		List<DisponibilidadView> vista = service.disponibilidad(
				actor, ORG_ID, CONSULTORIO_ID, desde, hasta);

		// La misma persona puede ocupar un lugar por dos motivos: sumar contaria doble.
		assertThat(vista).singleElement().satisfies(d -> {
			assertThat(d.lugaresComprometidos()).isEqualTo(3);
			assertThat(d.lugaresDisponibles()).isEqualTo(2);
			assertThat(d.disponible()).isTrue();
		});
	}

	// =================================================================================
	// Alta
	// =================================================================================

	@Test
	@DisplayName("Un alta valida usa los defaults, persiste y audita ESPACIO_CREATED en ACTIVO")
	void el_alta_valida_audita_dentro_de_la_operacion() {
		EspacioView vista = service.create(actor, ORG_ID, CONSULTORIO_ID,
				new EspacioAltaCommand("  Box 1 ", null, null, null, null, null));

		assertThat(vista.name()).isEqualTo("Box 1");
		assertThat(vista.tipo()).isEqualTo(EspacioTipo.BOX.name());
		assertThat(vista.capacidad()).isEqualTo(Espacio.CAPACIDAD_POR_DEFECTO);

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().eventType()).isEqualTo(AuditEvents.ESPACIO_CREATED);
		assertThat(auditoria.getValue().entityId()).isEqualTo(ESPACIO_ID);
		assertThat(auditoria.getValue().newState()).isEqualTo("ACTIVO");
		assertThat(auditoria.getValue().organizationId()).isEqualTo(ORG_ID);
	}

	@Test
	@DisplayName("Mutar una sede distinta de la del contexto es 404 antes de evaluar el permiso")
	void el_alta_fuera_de_la_sede_del_contexto_es_404() {
		long otraSede = 21L;

		assertThatThrownBy(() -> service.create(actor, ORG_ID, otraSede,
				new EspacioAltaCommand("Box 1", null, null, null, null, null)))
				.isInstanceOf(ConsultorioNotAccessibleException.class);

		verifyNoInteractions(permissionGuard);
		verify(espacios, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Sin consultorio:manage el alta no escribe nada")
	void el_alta_sin_permiso_no_escribe() {
		given(permissionGuard.requirePermission(any()))
				.willThrow(new AccessDeniedException("sin consultorio:manage"));

		assertThatThrownBy(() -> service.create(actor, ORG_ID, CONSULTORIO_ID,
				new EspacioAltaCommand("Box 1", null, null, null, null, null)))
				.isInstanceOf(AccessDeniedException.class);

		verify(espacios, never()).saveAndFlush(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Una sede dada de baja no recibe espacios nuevos: 409")
	void el_alta_sobre_una_sede_inactiva_es_409() {
		given(consultorioDirectory.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(sede(false)));

		assertThatThrownBy(() -> service.create(actor, ORG_ID, CONSULTORIO_ID,
				new EspacioAltaCommand("Box 1", null, null, null, null, null)))
				.isInstanceOf(ConsultorioNotOperableException.class);
		verify(espacios, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("La capacidad fuera de 1..1000 se rechaza antes de persistir")
	void la_capacidad_fuera_de_rango_se_rechaza() {
		assertThatThrownBy(() -> service.create(actor, ORG_ID, CONSULTORIO_ID,
				new EspacioAltaCommand("Gimnasio", EspacioTipo.GIMNASIO, 1001, null, null, null)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.create(actor, ORG_ID, CONSULTORIO_ID,
				new EspacioAltaCommand("Box 1", null, 0, null, null, null)))
				.isInstanceOf(IllegalArgumentException.class);

		verify(espacios, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("El unique de nombre vigente se traduce a 409 y despues no se audita nada")
	void el_nombre_repetido_es_409_sin_auditoria() {
		willThrow(new DataIntegrityViolationException("uk")).given(espacios).saveAndFlush(any());

		assertThatThrownBy(() -> service.create(actor, ORG_ID, CONSULTORIO_ID,
				new EspacioAltaCommand("Box 1", null, null, null, null, null)))
				.isInstanceOf(EspacioNameTakenException.class);

		// Tocar la sesion JPA despues de un flush fallido convierte el 409 en un 500.
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Una mutacion amparada en soporte deja el evento del negocio Y el SUPPORT_ACCESS_USED")
	void la_mutacion_via_soporte_audita_el_uso_en_la_misma_transaccion() {
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("SOPORTE", true));

		service.create(actor, ORG_ID, CONSULTORIO_ID,
				new EspacioAltaCommand("Box 1", null, null, null, null, null));

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail, times(2)).record(auditoria.capture());
		assertThat(auditoria.getAllValues()).extracting(AuditEntry::eventType)
				.containsExactly(AuditEvents.ESPACIO_CREATED, "SUPPORT_ACCESS_USED");
		verifyNoInteractions(supportAccessAuditor);
	}

	// =================================================================================
	// Edicion
	// =================================================================================

	@Test
	@DisplayName("Editar un espacio dado de baja es 409")
	void editar_un_espacio_inactivo_es_409() {
		Espacio dadoDeBaja = espacioActivo(4);
		dadoDeBaja.deactivate(Instant.now(), "Refaccion");
		given(espacios.findByIdForUpdate(ESPACIO_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(dadoDeBaja));

		assertThatThrownBy(() -> service.update(actor, ORG_ID, CONSULTORIO_ID, ESPACIO_ID,
				edicion("Box 2", null, 0L)))
				.isInstanceOf(EspacioInactiveException.class)
				.satisfies(e -> assertThat(((EspacioInactiveException) e).getOperacion())
						.isEqualTo(EspacioInactiveException.Operacion.EDICION));
	}

	@Test
	@DisplayName("Una version enviada vieja es 409 y no escribe")
	void editar_con_version_vieja_es_409() {
		given(espacios.findByIdForUpdate(ESPACIO_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(espacioActivo(4)));

		assertThatThrownBy(() -> service.update(actor, ORG_ID, CONSULTORIO_ID, ESPACIO_ID,
				edicion("Box 2", null, 7L)))
				.isInstanceOf(OptimisticLockingFailureException.class);
		verify(espacios, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Bajar la capacidad por debajo del pico de ocupacion es 409 y no escribe")
	void reducir_capacidad_bajo_la_ocupacion_es_409() {
		given(espacios.findByIdForUpdate(ESPACIO_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(espacioActivo(6)));
		given(inscripciones.peakOccupancyFrom(anyLong(), anyLong(), any()))
				.willReturn(new EspacioOccupancyProbe.Occupancy("INSCRIPCION", 5));

		assertThatThrownBy(() -> service.update(actor, ORG_ID, CONSULTORIO_ID, ESPACIO_ID,
				edicion(null, 4, 0L)))
				.isInstanceOf(EspacioCapacityBelowOccupancyException.class)
				.satisfies(e -> {
					EspacioCapacityBelowOccupancyException rechazo =
							(EspacioCapacityBelowOccupancyException) e;
					assertThat(rechazo.getRequestedCapacity()).isEqualTo(4);
					assertThat(rechazo.getCurrentOccupancy()).isEqualTo(5);
				});
		verify(espacios, never()).saveAndFlush(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Subir la capacidad no consulta a las sondas y audita el cambio")
	void aumentar_capacidad_no_consulta_sondas_y_audita() {
		given(espacios.findByIdForUpdate(ESPACIO_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(espacioActivo(4)));

		EspacioView vista = service.update(actor, ORG_ID, CONSULTORIO_ID, ESPACIO_ID,
				edicion("Box Grande", 8, 0L));

		assertThat(vista.capacidad()).isEqualTo(8);
		verifyNoInteractions(turnos, inscripciones);

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().eventType()).isEqualTo(AuditEvents.ESPACIO_UPDATED);
		assertThat(auditoria.getValue().details())
				.containsEntry("capacidad", "4 -> 8")
				.containsEntry("name", "Box 1 -> Box Grande");
	}

	// =================================================================================
	// Baja
	// =================================================================================

	@Test
	@DisplayName("La baja sin motivo se rechaza antes de tomar el lock de la fila")
	void la_baja_sin_motivo_se_rechaza_antes_del_lock() {
		assertThatThrownBy(() -> service.deactivate(actor, ORG_ID, CONSULTORIO_ID, ESPACIO_ID, "  "))
				.isInstanceOf(IllegalArgumentException.class);

		verify(espacios, never()).findByIdForUpdate(anyLong(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("Si alguna sonda declara ocupacion, la baja es 409 y el espacio sigue activo")
	void la_baja_con_ocupacion_es_409() {
		Espacio espacio = espacioActivo(4);
		given(espacios.findByIdForUpdate(ESPACIO_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(espacio));
		given(turnos.peakOccupancyFrom(anyLong(), anyLong(), any()))
				.willReturn(new EspacioOccupancyProbe.Occupancy("TURNO", 1));

		assertThatThrownBy(() -> service.deactivate(actor, ORG_ID, CONSULTORIO_ID, ESPACIO_ID, "Refaccion"))
				.isInstanceOf(EspacioHasActiveReferencesException.class);

		assertThat(espacio.isOperable()).isTrue();
		verify(espacios, never()).save(any());
	}

	@Test
	@DisplayName("Dar de baja dos veces es 409, no una segunda baja silenciosa")
	void la_segunda_baja_es_409() {
		Espacio dadoDeBaja = espacioActivo(4);
		dadoDeBaja.deactivate(Instant.now(), "Refaccion");
		given(espacios.findByIdForUpdate(ESPACIO_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(dadoDeBaja));

		assertThatThrownBy(() -> service.deactivate(actor, ORG_ID, CONSULTORIO_ID, ESPACIO_ID, "Otra vez"))
				.isInstanceOf(EspacioInactiveException.class)
				.satisfies(e -> assertThat(((EspacioInactiveException) e).getOperacion())
						.isEqualTo(EspacioInactiveException.Operacion.BAJA));
	}

	@Test
	@DisplayName("La baja es logica: la fila sobrevive con su motivo y se audita ACTIVO -> INACTIVO")
	void la_baja_es_logica_y_se_audita() {
		given(espacios.findByIdForUpdate(ESPACIO_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(espacioActivo(4)));

		EspacioView vista = service.deactivate(actor, ORG_ID, CONSULTORIO_ID, ESPACIO_ID, "Refaccion");

		assertThat(vista.estado()).isEqualTo("INACTIVO");
		assertThat(vista.deactivationReason()).isEqualTo("Refaccion");
		assertThat(vista.deletedAt()).isNotNull();

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().eventType()).isEqualTo(AuditEvents.ESPACIO_DEACTIVATED);
		assertThat(auditoria.getValue().previousState()).isEqualTo("ACTIVO");
		assertThat(auditoria.getValue().newState()).isEqualTo("INACTIVO");
		assertThat(auditoria.getValue().reason()).isEqualTo("Refaccion");
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static ConsultorioSnapshot sede(boolean activa) {
		return new ConsultorioSnapshot(
				CONSULTORIO_ID, ORG_ID, "Sede Sintetica", "America/Argentina/Cordoba", activa);
	}

	private static Espacio espacioActivo(int capacidad) {
		return conId(new Espacio(ORG_ID, CONSULTORIO_ID, "Box 1", EspacioTipo.BOX, capacidad, null,
				Instant.parse("2020-01-01T00:00:00Z"), null));
	}

	private static Espacio conId(Espacio espacio) {
		if (espacio.getId() == null) {
			ReflectionTestUtils.setField(espacio, "id", ESPACIO_ID);
		}
		return espacio;
	}

	private static EspacioEdicionCommand edicion(String nombre, Integer capacidad, long version) {
		return new EspacioEdicionCommand(nombre, null, capacidad, null, null, null, false, version);
	}
}
