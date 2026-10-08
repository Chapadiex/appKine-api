package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionQuery;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.exception.BloqueNotAccessibleException;
import com.akine.resource.domain.exception.BloqueSolapadoException;
import com.akine.resource.domain.exception.ConsultorioNotAccessibleException;
import com.akine.resource.domain.exception.ProfesionalNoVinculadoException;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.BloqueDisponibilidadRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.DisponibilidadExcepcionRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.FeriadoRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.HorarioGeneralRepositoryPort;
import com.akine.resource.spi.DisponibilidadImpactProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Los invariantes de la disponibilidad semanal, sin base de datos.
 *
 * <h2>Que puede y que no puede probar este test</h2>
 *
 * <p><b>Puede</b> probar el ORDEN del protocolo —que el lock de la sede es anterior a la primera
 * lectura de bloques, que la pertenencia se comprueba antes que el permiso—, los dos predicados
 * que la etapa confunde con facilidad (coincidencia exacta contra solapamiento, y contiguo
 * contra solapado) y que la auditoria se escribe en la transaccion del negocio.
 *
 * <p><b>No puede</b> probar que el invariante se sostenga bajo concurrencia real: eso necesita
 * dos transacciones contra MySQL y es un IT. Lo que este test fija es que el codigo pida el lock
 * en el momento correcto; que el lock funcione es del motor.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisponibilidadServiceTest {

	private static final long ORG_ID = 10L;
	private static final long CONSULTORIO_ID = 20L;
	private static final long OTRA_SEDE_ID = 21L;
	private static final long MEMBERSHIP_ID = 30L;
	private static final long ACCOUNT_ID = 40L;
	private static final long BLOQUE_ID = 50L;
	private static final long OTRA_MEMBERSHIP_ID = 31L;
	private static final String ZONA = "America/Argentina/Buenos_Aires";

	private static final int MARTES = 2;
	private static final LocalDate DESDE_MARZO = LocalDate.of(2026, 3, 1);

	@Mock
	private BloqueDisponibilidadRepositoryPort bloques;

	@Mock
	private CalendarioSedeRepositoryPort calendarios;

	@Mock
	private HorarioGeneralRepositoryPort horarios;

	@Mock
	private ConsultorioDirectory consultorioDirectory;

	@Mock
	private ConsultorioMembershipDirectory membershipDirectory;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private AuditTrail auditTrail;

	@Mock
	private DisponibilidadImpactProbe impactProbe;

	@Mock
	private DisponibilidadExcepcionRepositoryPort excepciones;

	@Mock
	private FeriadoRepositoryPort feriados;

	private DisponibilidadService service;

	private final OperatingActor actor =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		// El iniciador real sobre el puerto mockeado: su REQUIRES_NEW es un no-op sin proxy de
		// Spring, y asi el InOrder de concurrencia ve el crearSiFalta sobre el mismo mock.
		service = new DisponibilidadService(
				bloques, calendarios, new CalendarioSedeIniciador(calendarios),
				consultorioDirectory, membershipDirectory,
				permissionGuard, auditTrail,
				new SimuladorDeImpacto(
						bloques, excepciones, feriados, calendarios, impactProbe, horarios));

		given(consultorioDirectory.find(ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(new ConsultorioSnapshot(
						CONSULTORIO_ID, ORG_ID, "Sede Centro", ZONA, true)));
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID))
				.willReturn(Optional.of(profesionalDeLaSede(CONSULTORIO_ID)));
		given(calendarios.lockByScope(ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(new CalendarioSede(ORG_ID, CONSULTORIO_ID)));
		given(bloques.save(any())).willAnswer(invocacion -> {
			BloqueDisponibilidad guardado = invocacion.getArgument(0);
			if (guardado.getId() == null) {
				ReflectionTestUtils.setField(guardado, "id", BLOQUE_ID);
			}
			return guardado;
		});
		given(impactProbe.pendientesEn(anyLong(), anyLong(), any(), any(), any()))
				.willReturn(List.of());
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	@Test
	@DisplayName("Una sede de otro tenant sale por 404 y ni siquiera llega al evaluador de permisos")
	void una_sede_de_otro_tenant_da_404_y_no_403() {
		given(consultorioDirectory.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes()))
				.isInstanceOf(ConsultorioNotAccessibleException.class);

		// Lo que fija este verify es el ORDEN: si el permiso se evaluara primero, un tenant ajeno
		// recibiria 403 y eso confirmaria que la sede existe.
		verifyNoInteractions(permissionGuard);
		verifyNoInteractions(bloques);
	}

	@Test
	@DisplayName("Sin consultorio:manage el alta responde 403 y no escribe nada")
	void sin_consultorio_manage_el_alta_da_403() {
		given(permissionGuard.requirePermission(any()))
				.willThrow(new AccessDeniedException("sin permiso"));

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes()))
				.isInstanceOf(AccessDeniedException.class);

		verify(bloques, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Un profesional no puede cargar su propio bloque: el codigo exigido es consultorio:manage")
	void un_profesional_no_puede_crear_su_propio_bloque() {
		// La matriz seccion 6 le NIEGA consultorio:manage a PROFESIONAL, asi que el evaluador
		// rechaza. Lo que este test fija es que el servicio pida ESE codigo y no uno de lectura:
		// si alguien lo cambiara por colaborador:read, el profesional pasaria.
		given(permissionGuard.requirePermission(any()))
				.willThrow(new AccessDeniedException("PROFESIONAL no gestiona la sede"));

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes()))
				.isInstanceOf(AccessDeniedException.class);

		ArgumentCaptor<PermissionQuery> consulta = ArgumentCaptor.forClass(PermissionQuery.class);
		verify(permissionGuard).requirePermission(consulta.capture());
		assertThat(consulta.getValue().permissionCode()).isEqualTo("consultorio:manage");
		assertThat(consulta.getValue().consultorioId()).isEqualTo(CONSULTORIO_ID);
	}

	@Test
	@DisplayName("La lectura exige colaborador:read con la sede como alcance, no espacio:read")
	void con_colaborador_read_puede_listar() {
		given(bloques.findActivosDe(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID))
				.willReturn(List.of(bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0))));

		List<BloqueView> horario = service.listar(actor, CONSULTORIO_ID, MEMBERSHIP_ID);

		assertThat(horario).hasSize(1);
		assertThat(horario.getFirst().estado()).isEqualTo("ACTIVO");

		ArgumentCaptor<PermissionQuery> consulta = ArgumentCaptor.forClass(PermissionQuery.class);
		verify(permissionGuard).requirePermission(consulta.capture());
		assertThat(consulta.getValue().permissionCode()).isEqualTo("colaborador:read");
		assertThat(consulta.getValue().consultorioId()).isEqualTo(CONSULTORIO_ID);
	}

	@Test
	@DisplayName("Un bloque de OTRO profesional no se edita: 404, aunque el actor administre la sede")
	void un_bloque_de_otro_profesional_no_se_puede_editar() {
		// PUT /consultorios/20/profesionales/31/disponibilidad/50 donde el bloque 50 es del
		// profesional 30. El actor es un CONSULTORIO_ADMIN legitimo y el permiso alcanza: lo unico
		// que separa un 404 de una edicion exitosa auditada contra el profesional equivocado es
		// que la carga compare la membership de la ruta con la de la fila.
		given(membershipDirectory.find(ORG_ID, OTRA_MEMBERSHIP_ID))
				.willReturn(Optional.of(new ConsultorioMembershipSnapshot(
						OTRA_MEMBERSHIP_ID, 41L, ORG_ID, CONSULTORIO_ID, "PROFESIONAL", "ACTIVA",
						Instant.parse("2026-01-01T00:00:00Z"), null, true, true)));
		given(bloques.findByIdScoped(BLOQUE_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0))));

		assertThatThrownBy(() -> service.editar(
				actor, CONSULTORIO_ID, OTRA_MEMBERSHIP_ID, BLOQUE_ID,
				new BloqueEdicionCommand(null, LocalTime.of(10, 0), null, null, null, false, 0L)))
				.isInstanceOf(BloqueNotAccessibleException.class);

		verify(bloques, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Una membership que no cubre la sede se rechaza con 409, no con 404")
	void una_membership_que_no_cubre_la_sede_es_rechazada() {
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID))
				.willReturn(Optional.of(profesionalDeLaSede(OTRA_SEDE_ID)));

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes()))
				.isInstanceOf(ProfesionalNoVinculadoException.class);

		verify(bloques, never()).save(any());
	}

	// =================================================================================
	// Idempotencia y solapamiento
	// =================================================================================

	@Test
	@DisplayName("Un alta identica devuelve el bloque que ya existe: no duplica y no da 409")
	void un_alta_exactamente_igual_devuelve_el_bloque_existente() {
		BloqueDisponibilidad yaCargado =
				bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0));
		given(bloques.findActivosDe(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID))
				.willReturn(List.of(yaCargado));

		BloqueView vista = service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes());

		assertThat(vista.id()).isEqualTo(BLOQUE_ID);
		verify(bloques, never()).save(any());
		// Tampoco se audita de nuevo: el reintento de red tiene que dejar el historial igual que
		// lo dejo el primer intento.
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Un alta que se pisa sin coincidir responde 409")
	void un_alta_que_solapa_sin_coincidir_da_409() {
		given(bloques.findActivosDe(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID))
				.willReturn(List.of(bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0))));

		BloqueAltaCommand pisando = new BloqueAltaCommand(
				MARTES, LocalTime.of(11, 0), LocalTime.of(13, 0), DESDE_MARZO, null);

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, pisando))
				.isInstanceOf(BloqueSolapadoException.class)
				.extracting(conflicto -> ((BloqueSolapadoException) conflicto).getBloqueEnConflictoId())
				.isEqualTo(BLOQUE_ID);

		verify(bloques, never()).save(any());
	}

	@Test
	@DisplayName("Manana y tarde del mismo dia son contiguas, no solapadas: 09-12 y 12-15 conviven")
	void dos_bloques_contiguos_del_mismo_dia_son_validos() {
		given(bloques.findActivosDe(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID))
				.willReturn(List.of(bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0))));

		BloqueAltaCommand tarde = new BloqueAltaCommand(
				MARTES, LocalTime.of(12, 0), LocalTime.of(15, 0), DESDE_MARZO, null);

		BloqueView vista = service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, tarde);

		assertThat(vista.horaDesde()).isEqualTo(LocalTime.of(12, 0));
		assertThat(vista.horaHasta()).isEqualTo(LocalTime.of(15, 0));
		verify(bloques).save(any());
	}

	@Test
	@DisplayName("Dos temporadas del mismo horario no se solapan: marzo-junio y septiembre conviven")
	void dos_temporadas_del_mismo_horario_no_se_solapan() {
		// Horas que SI se pisan (11-12 esta en los dos) pero vigencias disjuntas. Si alguien saca
		// la comparacion de vigencia de exigirSinSolapamiento, esto se convierte en un 409 y el
		// horario de temporada deja de poder cargarse.
		given(bloques.findActivosDe(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID))
				.willReturn(List.of(bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0),
						DESDE_MARZO, LocalDate.of(2026, 6, 1))));

		BloqueAltaCommand temporadaDePrimavera = new BloqueAltaCommand(
				MARTES, LocalTime.of(11, 0), LocalTime.of(13, 0),
				LocalDate.of(2026, 9, 1), null);

		service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, temporadaDePrimavera);

		verify(bloques).save(any());
	}

	@Test
	@DisplayName("Mismo dia y mismas horas con OTRA vigencia no es el mismo bloque: se crea, no se reusa")
	void un_alta_con_otra_vigencia_no_es_el_mismo_bloque() {
		// Mismo dia y mismas horas que el existente, pero otra temporada. Si alguien saca la
		// vigencia de coincideExacto, esto devuelve el bloque de MARZO como si fuera un reintento
		// y el horario de septiembre no se crea nunca.
		given(bloques.findActivosDe(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID))
				.willReturn(List.of(bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0),
						DESDE_MARZO, LocalDate.of(2026, 6, 1))));

		BloqueAltaCommand mismoHorarioOtraTemporada = new BloqueAltaCommand(
				MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0), LocalDate.of(2026, 9, 1), null);

		service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, mismoHorarioOtraTemporada);

		ArgumentCaptor<BloqueDisponibilidad> creado =
				ArgumentCaptor.forClass(BloqueDisponibilidad.class);
		verify(bloques).save(creado.capture());
		assertThat(creado.getValue().getVigenciaDesde()).isEqualTo(LocalDate.of(2026, 9, 1));
	}

	@Test
	@DisplayName("El reintento que cruza la medianoche sigue siendo idempotente (CA-M05-003-05)")
	void el_reintento_que_cruza_la_medianoche_sigue_siendo_idempotente() {
		// Primer intento a las 23:59:58 con vigenciaDesde nula: la fila quedo con la fecha de AYER.
		// El reintento llega despues de medianoche y resolveria HOY. Exigir igualdad de fechas le
		// daria 409 justo al caso que la idempotencia existe para cubrir.
		LocalDate ayer = LocalDate.now(ZoneId.of(ZONA)).minusDays(1);
		given(bloques.findActivosDe(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID))
				.willReturn(List.of(bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0),
						ayer, null)));

		BloqueAltaCommand desdeAhora = new BloqueAltaCommand(
				MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0), null, null);

		BloqueView vista = service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, desdeAhora);

		assertThat(vista.id()).isEqualTo(BLOQUE_ID);
		verify(bloques, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Un bloque de vigencia FUTURA no satisface un pedido de 'que rija desde ahora'")
	void un_bloque_que_todavia_no_rige_no_satisface_un_alta_desde_ahora() {
		LocalDate elMesQueViene = LocalDate.now(ZoneId.of(ZONA)).plusMonths(1);
		given(bloques.findActivosDe(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID))
				.willReturn(List.of(bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0),
						elMesQueViene, null)));

		BloqueAltaCommand desdeAhora = new BloqueAltaCommand(
				MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0), null, null);

		// No es el mismo pedido: el existente no cubre HOY. Y como las dos vigencias se pisan de
		// aca en adelante, el alta es un conflicto real.
		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, desdeAhora))
				.isInstanceOf(BloqueSolapadoException.class);
	}

	// =================================================================================
	// Concurrencia
	// =================================================================================

	@Test
	@DisplayName("El lock de la sede se toma ANTES de leer los bloques: leer primero es una escalada S->X")
	void el_lock_se_toma_antes_de_leer_los_bloques() {
		service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes());

		InOrder protocolo = inOrder(calendarios, bloques);
		protocolo.verify(calendarios).lockByScope(ORG_ID, CONSULTORIO_ID);
		protocolo.verify(bloques).findActivosDe(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID);
	}

	@Test
	@DisplayName("La edicion tambien bloquea antes de leer el bloque, no despues")
	void la_edicion_toma_el_lock_antes_de_leer_el_bloque() {
		given(bloques.findByIdScoped(BLOQUE_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0))));

		service.editar(actor, CONSULTORIO_ID, MEMBERSHIP_ID, BLOQUE_ID,
				new BloqueEdicionCommand(null, LocalTime.of(10, 0), null, null, null, false, 0L));

		InOrder protocolo = inOrder(calendarios, bloques);
		protocolo.verify(calendarios).lockByScope(ORG_ID, CONSULTORIO_ID);
		protocolo.verify(bloques).findByIdScoped(BLOQUE_ID, ORG_ID, CONSULTORIO_ID);
	}

	@Test
	@DisplayName("El calendario de la sede se asegura ANTES del lock, y nunca se inserta bajo el")
	void el_calendario_de_la_sede_se_asegura_antes_del_lock() {
		service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes());

		// El orden importa y es el que corrige el deadlock: asegurar la fila —en su propia
		// transaccion, con un INSERT que no puede fallar— y recien despues bloquearla. El camino
		// viejo bloqueaba, veia el vacio e insertaba DENTRO de esta transaccion, y N primeras
		// escrituras concurrentes de una sede se mataban entre si.
		InOrder protocolo = inOrder(calendarios, bloques);
		protocolo.verify(calendarios).crearSiFalta(ORG_ID, CONSULTORIO_ID);
		protocolo.verify(calendarios).lockByScope(ORG_ID, CONSULTORIO_ID);
		protocolo.verify(bloques).findActivosDe(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID);

		verify(calendarios, never()).save(any(CalendarioSede.class));
	}

	@Test
	@DisplayName("Si el calendario no esta ni despues de asegurarlo, falla ruidosamente")
	void si_el_calendario_no_se_puede_bloquear_despues_de_crearlo_falla() {
		given(calendarios.lockByScope(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.empty());

		// Nunca deberia pasar. Seguir sin el lock si podria: seria escribir disponibilidad sin el
		// unico mecanismo que impide que dos altas concurrentes se pisen.
		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes()))
				.isInstanceOf(IllegalStateException.class);

		verify(bloques, never()).save(any());
	}

	@Test
	@DisplayName("La sonda de impacto se pregunta por el tramo que la edicion RECORTA, no por el que deja")
	void la_edicion_pregunta_por_el_tramo_que_recorta() {
		// Bloque sin fin de vigencia con turnos hacia adelante. El administrador le pone fin en
		// diez dias: los turnos que esa edicion deja huerfanos son los POSTERIORES a ese fin. Una
		// ventana derivada del estado nuevo terminaria justo ahi y responderia cero.
		given(bloques.findByIdScoped(BLOQUE_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0))));

		LocalDate nuevoFin = LocalDate.now(ZoneId.of(ZONA)).plusDays(10);
		service.editar(actor, CONSULTORIO_ID, MEMBERSHIP_ID, BLOQUE_ID,
				new BloqueEdicionCommand(null, null, null, null, nuevoFin, false, 0L));

		ArgumentCaptor<Instant> hasta = ArgumentCaptor.forClass(Instant.class);
		verify(impactProbe).pendientesEn(
				org.mockito.ArgumentMatchers.eq(ORG_ID),
				org.mockito.ArgumentMatchers.eq(CONSULTORIO_ID),
				org.mockito.ArgumentMatchers.eq(MEMBERSHIP_ID),
				any(), hasta.capture());

		Instant finDeLaVentanaNueva = nuevoFin.atStartOfDay(ZoneId.of(ZONA)).toInstant();
		assertThat(hasta.getValue()).isAfter(finDeLaVentanaNueva);
	}

	@Test
	@DisplayName("Editar con una version vieja responde 409 concurrent-modification, no pisa el cambio ajeno")
	void la_edicion_con_version_vieja_da_409_concurrent_modification() {
		BloqueDisponibilidad enBase =
				bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0));
		ReflectionTestUtils.setField(enBase, "version", 3L);
		given(bloques.findByIdScoped(BLOQUE_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(enBase));

		BloqueEdicionCommand conVersionVieja = new BloqueEdicionCommand(
				null, LocalTime.of(10, 0), null, null, null, false, 1L);

		assertThatThrownBy(() -> service.editar(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, BLOQUE_ID, conVersionVieja))
				.isInstanceOf(OptimisticLockingFailureException.class);

		verify(bloques, never()).save(any());
		assertThat(enBase.getHoraDesde()).isEqualTo(LocalTime.of(9, 0));
	}

	// =================================================================================
	// Baja logica
	// =================================================================================

	@Test
	@DisplayName("La baja sin motivo se rechaza: sin el, la auditoria no responde por que")
	void la_baja_exige_motivo() {
		given(bloques.findByIdScoped(BLOQUE_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0))));

		assertThatThrownBy(() -> service.darDeBaja(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, BLOQUE_ID, "   "))
				.isInstanceOf(IllegalArgumentException.class);

		verify(bloques, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("La baja es logica: conserva la fila, el motivo y la historia (RN-M05-003)")
	void la_baja_es_logica_y_conserva_la_fila() {
		BloqueDisponibilidad enBase =
				bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0));
		given(bloques.findByIdScoped(BLOQUE_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(enBase));

		BloqueView vista = service.darDeBaja(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, BLOQUE_ID, "El profesional cambio de turno");

		ArgumentCaptor<BloqueDisponibilidad> guardado =
				ArgumentCaptor.forClass(BloqueDisponibilidad.class);
		verify(bloques).save(guardado.capture());

		// La baja tambien lee un bloque, y tambien tiene que bloquear primero.
		InOrder protocolo = inOrder(calendarios, bloques);
		protocolo.verify(calendarios).lockByScope(ORG_ID, CONSULTORIO_ID);
		protocolo.verify(bloques).findByIdScoped(BLOQUE_ID, ORG_ID, CONSULTORIO_ID);

		assertThat(guardado.getValue().isActive()).isFalse();
		assertThat(guardado.getValue().getDeletedAt()).isNotNull();
		assertThat(guardado.getValue().getDeactivationReason())
				.isEqualTo("El profesional cambio de turno");
		// La fila sigue ahi con todos sus datos: el dia y las horas no se tocan.
		assertThat(guardado.getValue().getDiaSemana()).isEqualTo(MARTES);
		assertThat(guardado.getValue().getHoraDesde()).isEqualTo(LocalTime.of(9, 0));
		assertThat(vista.estado()).isEqualTo("INACTIVO");
		assertThat(vista.deactivationReason()).isEqualTo("El profesional cambio de turno");
	}

	// =================================================================================
	// Auditoria
	// =================================================================================

	@Test
	@DisplayName("La auditoria se escribe en la transaccion del negocio, no en un listener post-commit")
	void la_auditoria_se_escribe_en_la_misma_transaccion() throws NoSuchMethodException {
		service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes());

		ArgumentCaptor<AuditEntry> fila = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(fila.capture());
		assertThat(fila.getValue().eventType()).isEqualTo("DISPONIBILIDAD_BLOQUE_CREATED");
		assertThat(fila.getValue().organizationId()).isEqualTo(ORG_ID);
		assertThat(fila.getValue().consultorioId()).isEqualTo(CONSULTORIO_ID);
		assertThat(fila.getValue().entityId()).isEqualTo(BLOQUE_ID);
		assertThat(fila.getValue().actorAccountId()).isEqualTo(ACCOUNT_ID);

		// Que la escritura ocurra DENTRO de la transaccion no lo prueba el verify de arriba por si
		// solo: lo prueba que el metodo que la produjo sea transaccional. Un listener post-commit
		// que falla dejaria la mutacion sin rastro, y es lo que este assert impide reintroducir.
		Transactional demarcacion = DisponibilidadService.class
				.getMethod("crear", OperatingActor.class, long.class, long.class, BloqueAltaCommand.class)
				.getAnnotation(Transactional.class);
		assertThat(demarcacion).isNotNull();
		assertThat(demarcacion.readOnly()).isFalse();
	}

	// =================================================================================
	// Quien puede ser SUJETO de disponibilidad (ruling R18)
	// =================================================================================

	@ParameterizedTest(name = "un vinculo {0} SI puede tener disponibilidad")
	@ValueSource(strings = {"PROFESIONAL", "CONSULTORIO_ADMIN", "ORG_ADMIN"})
	@DisplayName("Los tres roles que atienden pacientes pueden ser sujeto de un bloque")
	void los_roles_que_atienden_pueden_tener_disponibilidad(String roleCode) {
		// CONSULTORIO_ADMIN y ORG_ADMIN NO son un caso raro que haya que tolerar: en un centro
		// chico el duenio atiende, y la matriz seccion 1.2 dice explicitamente que el rol de
		// seguridad no es la profesion. Filtrar por roleCode == PROFESIONAL —que es el fix
		// "obvio"— le impediria cargarse el horario a la persona que abrio el centro.
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID))
				.willReturn(Optional.of(conRol(roleCode)));

		BloqueView vista = service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes());

		assertThat(vista.nuevo()).isTrue();
		verify(bloques).save(any());
	}

	@ParameterizedTest(name = "un vinculo {0} NO puede tener disponibilidad")
	@ValueSource(strings = {"ADMINISTRATIVO", "PACIENTE"})
	@DisplayName("Los dos roles que por definicion no atienden reciben 409 y no se escribe nada")
	void los_roles_que_no_atienden_no_pueden_tener_disponibilidad(String roleCode) {
		// El agujero que este control cierra: antes del ruling R18, un POST con curl contra la
		// membership de la recepcionista devolvia 201 y /efectiva empezaba a servir franjas
		// reales contra ella. La unica regla que existia era un filtro de TypeScript.
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID))
				.willReturn(Optional.of(conRol(roleCode)));

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes()))
				.isInstanceOf(ProfesionalNoVinculadoException.class);

		verify(bloques, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("La EDICION aplica el mismo control de rol que el alta")
	void la_edicion_tambien_rechaza_a_quien_no_atiende() {
		// Mover un bloque tambien es poner disponibilidad en efecto hacia adelante. Sin el
		// control aca, bastaria crear con un rol valido y despues cambiar el rol del vinculo.
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID))
				.willReturn(Optional.of(conRol("ADMINISTRATIVO")));

		assertThatThrownBy(() -> service.editar(actor, CONSULTORIO_ID, MEMBERSHIP_ID, BLOQUE_ID,
				new BloqueEdicionCommand(null, LocalTime.of(10, 0), null, null, null, false, 0L)))
				.isInstanceOf(ProfesionalNoVinculadoException.class);

		verify(bloques, never()).save(any());
	}

	@Test
	@DisplayName("La BAJA no mira el rol: RN-M05-003, lo que ya se cargo se tiene que poder ordenar")
	void la_baja_no_mira_el_rol() {
		// Un vinculo que cambio de rol —o que nunca debio tener horario— deja bloques cargados.
		// Si la baja exigiera rol, el administrador no podria limpiarlos y quedarian computando.
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID))
				.willReturn(Optional.of(conRol("ADMINISTRATIVO")));
		given(bloques.findByIdScoped(BLOQUE_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0))));

		BloqueView vista = service.darDeBaja(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, BLOQUE_ID, "cargado por error");

		assertThat(vista.estado()).isEqualTo("INACTIVO");
	}

	// =================================================================================
	// La ventana de la sonda de impacto
	// =================================================================================

	@Test
	@DisplayName("Con los dos fines de vigencia presentes, la ventana llega al MAS LEJANO de los dos")
	void la_ventana_de_impacto_es_la_union_de_los_dos_fines() {
		// Es la rama "los dos no nulos" de impactoDe, que es la union que justifico el fix T7 y
		// que hasta ahora no estaba afirmada por ningun test. Un bloque que termina en 40 dias al
		// que la edicion le adelanta el fin a 10: los turnos que quedan huerfanos son los que
		// caen ENTRE el dia 10 y el dia 40. Una ventana derivada del estado nuevo terminaria en
		// el dia 10 y responderia cero exactamente en el caso que la pregunta existe para
		// detectar.
		LocalDate finPrevio = LocalDate.now(ZoneId.of(ZONA)).plusDays(40);
		LocalDate finNuevo = LocalDate.now(ZoneId.of(ZONA)).plusDays(10);

		given(bloques.findByIdScoped(BLOQUE_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(bloque(
						BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0),
						DESDE_MARZO, finPrevio)));

		service.editar(actor, CONSULTORIO_ID, MEMBERSHIP_ID, BLOQUE_ID,
				new BloqueEdicionCommand(null, null, null, null, finNuevo, false, 0L));

		ArgumentCaptor<Instant> hasta = ArgumentCaptor.forClass(Instant.class);
		verify(impactProbe).pendientesEn(anyLong(), anyLong(), any(), any(), hasta.capture());

		assertThat(hasta.getValue())
				.as("el fin MAS LEJANO de los dos, no el nuevo y tampoco el horizonte de 90 dias")
				.isEqualTo(finPrevio.atStartOfDay(ZoneId.of(ZONA)).toInstant());
	}

	// =================================================================================
	// Fixtures sinteticas
	// =================================================================================

	private static ConsultorioMembershipSnapshot conRol(String roleCode) {
		return new ConsultorioMembershipSnapshot(
				MEMBERSHIP_ID, ACCOUNT_ID, ORG_ID, CONSULTORIO_ID, roleCode, "ACTIVA",
				Instant.parse("2026-01-01T00:00:00Z"), null, true, true);
	}

	private static BloqueAltaCommand altaMartes() {
		return new BloqueAltaCommand(MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0), DESDE_MARZO, null);
	}

	private static BloqueDisponibilidad bloque(long id, int dia, LocalTime desde, LocalTime hasta) {
		return bloque(id, dia, desde, hasta, DESDE_MARZO, null);
	}

	private static BloqueDisponibilidad bloque(
			long id, int dia, LocalTime desde, LocalTime hasta,
			LocalDate vigenciaDesde, LocalDate vigenciaHasta) {

		BloqueDisponibilidad bloque = new BloqueDisponibilidad(
				ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID, dia, desde, hasta,
				vigenciaDesde, vigenciaHasta);
		ReflectionTestUtils.setField(bloque, "id", id);
		return bloque;
	}

	private static ConsultorioMembershipSnapshot profesionalDeLaSede(Long consultorioId) {
		return new ConsultorioMembershipSnapshot(
				MEMBERSHIP_ID, ACCOUNT_ID, ORG_ID, consultorioId, "PROFESIONAL", "ACTIVA",
				Instant.parse("2026-01-01T00:00:00Z"), null, true, true);
	}
}
