package com.akine.encounter.application;

import com.akine.clinical.spi.CasoDirectory;
import com.akine.clinical.spi.CasoSnapshot;
import com.akine.clinical.spi.HistoriaClinicaDirectory;
import com.akine.clinical.spi.HistoriaClinicaSnapshot;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.ContenidoDeSesion;
import com.akine.encounter.domain.EvaluacionBase;
import com.akine.encounter.domain.Evolucion;
import com.akine.encounter.domain.Lateralidad;
import com.akine.encounter.domain.ModoSesion;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.FotoClinica;
import com.akine.encounter.domain.MedicionEnmendada;
import com.akine.encounter.domain.TratamientoAplicado;
import com.akine.encounter.domain.TratamientoEnmendado;
import com.akine.encounter.domain.SesionVersion;
import com.akine.encounter.domain.exception.CasoNoAsignableException;
import com.akine.encounter.domain.exception.CierreIncompletoException;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.SesionNoCerradaException;
import com.akine.encounter.spi.SesionCerrada;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.platform.spi.audit.AuditEntry;
import org.springframework.security.access.AccessDeniedException;
import java.math.BigDecimal;
import com.akine.encounter.domain.exception.EnmiendaSinMotivoException;
import com.akine.encounter.domain.exception.EvaluacionIncoherenteException;
import com.akine.encounter.domain.exception.SesionAjenaException;
import com.akine.encounter.domain.exception.SesionCerradaException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.domain.exception.TurnoNoAtendibleException;
import com.akine.encounter.domain.port.SesionNumeradorPort;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.encounter.domain.port.SesionVersionRepositoryPort;
import com.akine.encounter.spi.CierreDeSesionObserver;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.scheduling.spi.TurnoDirectory;
import com.akine.scheduling.spi.TurnoSnapshot;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Las cuatro reglas de la atencion, y nada mas.
 *
 * <p>Sin tests de codigos HTTP, de validacion de forma ni del comportamiento de Spring: eso lo
 * garantiza el framework y probarlo agrega tests que hay que mantener sin comprar nada.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SesionService")
class SesionServiceTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long TURNO_ID = 301L;
	private static final long OFERTA_ID = 42L;
	private static final long CASO_ID = 55L;
	private static final long PERSONA_ID = 128L;
	private static final long HISTORIA_ID = 88L;

	private static final long CUENTA_PROPIA = 99L;
	private static final long MEMBERSHIP_PROPIA = 31L;
	private static final long MEMBERSHIP_AJENA = 32L;

	@Mock private SesionRepositoryPort sesiones;
	@Mock private SesionVersionRepositoryPort versiones;
	@Mock private AuditTrail auditTrail;
	@Mock private TurnoDirectory turnos;
	@Mock private HistoriaClinicaDirectory historias;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private ConsultorioMembershipDirectory memberships;
	@Mock private PermissionGuard permissionGuard;
	@Mock private SesionNumeradorPort numerador;
	@Mock private NumeradorIniciador numeradorIniciador;
	@Mock private OfertaDirectory ofertas;

	/**
	 * 06.04: solo se consulta al notificar el cierre, para saber que practicas se aplicaron. Por
	 * defecto devuelve vacio, que es el comportamiento de toda sesion anterior a esa etapa.
	 */
	@Mock private com.akine.encounter.domain.port.TratamientoRepositoryPorts
			.TratamientoRepositoryPort tratamientos;

	/** 04.03: solo se consulta cuando la sesion declara un caso. Estos tests no declaran. */
	@Mock private CasoDirectory casos;

	/** C-6: la foto de tratamientos y mediciones de cada version, y su enmienda. */
	@Mock private com.akine.encounter.domain.port.TratamientoRepositoryPorts
			.TratamientoParametroRepositoryPort parametros;
	@Mock private com.akine.encounter.domain.port.SesionMedicionRepositoryPort mediciones;
	@Mock private TratamientoService tratamientoService;
	@Mock private MedicionService medicionService;

	/**
	 * 06.06: existe para poder probar que la ENMIENDA no lo llama.
	 *
	 * <p>Con la lista vacia ese test no probaria nada: un observador que no esta no se dispara
	 * igual, y el test pasaria aunque el servicio volviera a notificar el cierre.
	 */
	@Mock private CierreDeSesionObserver observador;

	private SesionService service;

	private final OperatingActor actor =
			new OperatingActor(CUENTA_PROPIA, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		service = new SesionService(
				sesiones, versiones, auditTrail, turnos, historias, casos, consultorios,
				memberships, permissionGuard, numerador, numeradorIniciador, ofertas,
				tratamientos, parametros, mediciones, tratamientoService, medicionService,
				List.of(observador));

		given(consultorios.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(CONSULTORIO_ID, ORG_ID, "Sede", "America/Argentina/Cordoba", true)));
		given(memberships.findByAccount(ORG_ID, CUENTA_PROPIA)).willReturn(List.of(
				new ConsultorioMembershipSnapshot(MEMBERSHIP_PROPIA, CUENTA_PROPIA, ORG_ID,
						CONSULTORIO_ID, "PROFESIONAL", "ACTIVA", Instant.EPOCH, null, true, true)));
		given(historias.asegurar(anyLong(), anyLong(), anyLong())).willReturn(
				new HistoriaClinicaSnapshot(HISTORIA_ID, ORG_ID, PERSONA_ID, Instant.EPOCH, true, 0));
		given(historias.findPorId(anyLong(), anyLong())).willReturn(Optional.of(
				new HistoriaClinicaSnapshot(HISTORIA_ID, ORG_ID, PERSONA_ID, Instant.EPOCH, true, 0)));
		// JPA asigna el id al persistir; el doble tiene que hacer lo mismo o el fixture
		// representaria una sesion guardada sin id, que en produccion no ocurre.
		given(sesiones.save(any())).willAnswer(SesionServiceTest::conIdComoJpa);
		// `saveAndFlush` se comporta igual que `save` en el doble: lo que agrega en produccion es
		// el flush, que existe para que la respuesta lleve la version YA avanzada. Eso no se puede
		// simular con un mock —la version la mueve Hibernate— y queda declarado como no verificado.
		given(sesiones.saveAndFlush(any())).willAnswer(SesionServiceTest::conIdComoJpa);
	}

	/** JPA asigna el id al persistir; el doble tiene que hacer lo mismo o el fixture mentiria. */
	private static Sesion conIdComoJpa(org.mockito.invocation.InvocationOnMock invocacion) {
		Sesion guardada = invocacion.getArgument(0);
		if (guardada.getId() == null) {
			ReflectionTestUtils.setField(guardada, "id", 1L);
		}
		return guardada;
	}

	private static TurnoSnapshot turno(Long profesionalId, boolean vivo) {
		return new TurnoSnapshot(TURNO_ID, ORG_ID, CONSULTORIO_ID, OFERTA_ID, PERSONA_ID,
				profesionalId, null, Instant.EPOCH, Instant.EPOCH.plusSeconds(3600),
				"CONFIRMADO", vivo);
	}

	/**
	 * Una sesion como la devuelve la base: CON id.
	 *
	 * <p>El id se pone por reflexion porque la entidad no lo expone —lo asigna JPA al persistir— y
	 * {@code SesionView} lo desempaqueta a un {@code long}. Sin esto el fixture representaria un
	 * estado que en produccion no existe: una sesion guardada sin id.
	 */
	private static Sesion sesionExistente(long profesionalMembershipId) {
		Sesion sesion = new Sesion(ORG_ID, CONSULTORIO_ID, HISTORIA_ID, null, TURNO_ID, OFERTA_ID,
				profesionalMembershipId, Instant.EPOCH, CUENTA_PROPIA);
		ReflectionTestUtils.setField(sesion, "id", 1L);
		return sesion;
	}

	@Test
	@DisplayName("El doble inicio devuelve la sesion que ya existe, no una segunda ni un 409")
	void doble_inicio_es_idempotente() {
		// RN-M14-001: un turno produce como mucho una sesion. Apretar dos veces o recargar la
		// pantalla es el caso normal, no el raro, y un 409 obligaria a la pantalla a distinguir
		// dos situaciones que para el usuario son la misma.
		given(sesiones.findVivaPorTurno(ORG_ID, TURNO_ID))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));

		SesionView vista = service.iniciar(actor, CONSULTORIO_ID, TURNO_ID, null);

		assertThat(vista.turnoId()).isEqualTo(TURNO_ID);
		verify(sesiones, never()).save(any());
		// Y no toca la Historia Clinica: `asegurar` es idempotente pero llamarla igual dejaria un
		// evento de acceso clinico por cada doble click.
		verify(historias, never()).asegurar(anyLong(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("Un turno de otro profesional no se puede atender")
	void turno_de_otro_profesional() {
		// Dejar que otro abra la sesion de un turno ajeno rompe la propiedad ANTES de que la
		// sesion exista, y ahi el control de Sesion#exigirPropiedadDe ya no puede salvarla:
		// quedaria registrada a nombre de quien la abrio.
		given(sesiones.findVivaPorTurno(ORG_ID, TURNO_ID)).willReturn(Optional.empty());
		given(turnos.find(ORG_ID, CONSULTORIO_ID, TURNO_ID))
				.willReturn(Optional.of(turno(MEMBERSHIP_AJENA, true)));

		assertThatThrownBy(() -> service.iniciar(actor, CONSULTORIO_ID, TURNO_ID, null))
				.isInstanceOf(TurnoNoAtendibleException.class);
	}

	@Test
	@DisplayName("Un turno sin profesional asignado lo atiende quien inicia")
	void turno_sin_profesional() {
		// Una oferta que no requiere profesional produce turnos sin uno —M27 lo permite— pero una
		// ATENCION siempre la da alguien.
		given(sesiones.findVivaPorTurno(ORG_ID, TURNO_ID)).willReturn(Optional.empty());
		given(turnos.find(ORG_ID, CONSULTORIO_ID, TURNO_ID))
				.willReturn(Optional.of(turno(null, true)));

		assertThat(service.iniciar(actor, CONSULTORIO_ID, TURNO_ID, null).profesionalId())
				.isEqualTo(MEMBERSHIP_PROPIA);
	}

	@Test
	@DisplayName("Un turno dado de baja no habilita atencion")
	void turno_dado_de_baja() {
		given(sesiones.findVivaPorTurno(ORG_ID, TURNO_ID)).willReturn(Optional.empty());
		given(turnos.find(ORG_ID, CONSULTORIO_ID, TURNO_ID))
				.willReturn(Optional.of(turno(MEMBERSHIP_PROPIA, false)));

		assertThatThrownBy(() -> service.iniciar(actor, CONSULTORIO_ID, TURNO_ID, null))
				.isInstanceOf(TurnoNoAtendibleException.class);
	}

	@Test
	@DisplayName("Nadie guarda en la sesion de otro profesional, aunque tenga el permiso")
	void no_se_edita_la_sesion_ajena() {
		// Es una regla de PROPIEDAD, no de autorizacion: los dos profesionales de la sede tienen
		// el mismo `sesion:register`. Por eso vive en la entidad y no en el evaluador, y por eso
		// el rechazo es 409 y no 403.
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_AJENA)));

		assertThatThrownBy(() ->
				service.guardarBorrador(actor, CONSULTORIO_ID, 1L, "{}", 0L))
				.isInstanceOf(SesionAjenaException.class);
	}

	@Test
	@DisplayName("Una version vieja no pisa lo que otro guardo")
	void el_autosave_no_pisa() {
		// El caso real: dos pestanas del mismo profesional. Sin este control la segunda pisa a la
		// primera en silencio y el profesional pierde lo que escribio sin enterarse.
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));

		assertThatThrownBy(() ->
				service.guardarBorrador(actor, CONSULTORIO_ID, 1L, "{}", 7L))
				.isInstanceOf(OptimisticLockingFailureException.class);

		verify(sesiones, never()).save(any());
	}

	@Test
	@DisplayName("Con la version correcta el borrador se guarda")
	void el_autosave_guarda() {
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));

		SesionView vista = service.guardarBorrador(
				actor, CONSULTORIO_ID, 1L, "{\"motivoConsulta\":\"dolor lumbar\"}", 0L);

		assertThat(vista.borrador()).contains("dolor lumbar");
		assertThat(vista.borradorGuardadoEn()).isNotNull();
	}

	// =================================================================================
	// Evaluacion base (AKINE-06.02)
	// =================================================================================

	private static EvaluacionBase evaluacion(Integer dolorEva, String zona, Lateralidad lado) {
		return new EvaluacionBase(ModoSesion.RAPIDA, "dolor lumbar", dolorEva, zona, lado,
				Evolucion.MEJOR, null, null);
	}

	@Test
	@DisplayName("Una evaluacion de seguimiento con solo dolor y evolucion es valida")
	void el_seguimiento_no_exige_examen_completo() {
		// Es la regla de negocio de la etapa, no una comodidad: exigir campos obligaria al
		// profesional a inventar datos clinicos para poder guardar. Y el seguimiento es la mayoria
		// de las sesiones de un tratamiento.
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));

		SesionView vista = service.evaluar(actor, CONSULTORIO_ID, 1L,
				new EvaluacionBase(null, null, 6, null, null, Evolucion.MEJOR, null, null), 0L);

		assertThat(vista.evaluacion().dolorEva()).isEqualTo(6);
		assertThat(vista.evaluadaEn()).isNotNull();
	}

	@Test
	@DisplayName("Un dolor fuera de la escala 0-10 se rechaza: es un dato que despues se promedia")
	void el_dolor_fuera_de_escala() {
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));

		assertThatThrownBy(() ->
				service.evaluar(actor, CONSULTORIO_ID, 1L, evaluacion(12, "Lumbar", null), 0L))
				.isInstanceOf(EvaluacionIncoherenteException.class);
	}

	@Test
	@DisplayName("La lateralidad sin zona no dice nada y se rechaza")
	void la_lateralidad_sin_zona() {
		// "Derecha" de que. Guardado ocupa el lugar de un dato real.
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));

		assertThatThrownBy(() ->
				service.evaluar(actor, CONSULTORIO_ID, 1L, evaluacion(5, null, Lateralidad.DERECHA), 0L))
				.isInstanceOf(EvaluacionIncoherenteException.class);
	}

	@Test
	@DisplayName("Una zona sin lateralidad SI es valida: una zona central no tiene lado")
	void la_zona_sin_lateralidad() {
		// La implicacion va en un solo sentido a proposito. Rechazar esto obligaria a declarar un
		// lado para la lumbar, que no lo tiene.
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));

		assertThat(service.evaluar(actor, CONSULTORIO_ID, 1L,
				evaluacion(5, "Lumbar", null), 0L).evaluacion().dolorZona())
				.isEqualTo("Lumbar");
	}

	@Test
	@DisplayName("La evaluacion previa viaja con la sesion, para poder comparar")
	void la_previa_viaja_con_la_sesion() {
		// Sin esto la pantalla no puede mostrar "la vez pasada tenia 7" al lado del campo de dolor,
		// que es lo que hace que el profesional cargue una evolucion real y no la que recuerda.
		Sesion anterior = sesionExistente(MEMBERSHIP_PROPIA);
		anterior.evaluar(evaluacion(7, "Lumbar", null), Instant.EPOCH);
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));
		given(sesiones.findPreviaEvaluada(anyLong(), anyLong(), any()))
				.willReturn(Optional.of(anterior));

		SesionView vista = service.ver(actor, CONSULTORIO_ID, 1L);

		assertThat(vista.previa()).isNotNull();
		assertThat(vista.previa().dolorEva()).isEqualTo(7);
	}

	@Test
	@DisplayName("Nadie evalua la sesion de otro profesional")
	void no_se_evalua_la_sesion_ajena() {
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_AJENA)));

		assertThatThrownBy(() ->
				service.evaluar(actor, CONSULTORIO_ID, 1L, evaluacion(5, "Lumbar", null), 0L))
				.isInstanceOf(SesionAjenaException.class);
	}

	// =================================================================================
	// Cierre (AKINE-06.05)
	// =================================================================================

	private static CierreDeSesion cierre(Asistencia asistencia, String nota) {
		return new CierreDeSesion(asistencia, nota, null, null, null, null);
	}

	@Test
	@DisplayName("Sin declarar asistencia no se puede cerrar: sin eso no se sabe si hubo prestacion")
	void el_cierre_exige_asistencia() {
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));

		assertThatThrownBy(() ->
				service.cerrar(actor, CONSULTORIO_ID, 1L, cierre(null, "Terapia manual"), 0L))
				.isInstanceOf(CierreIncompletoException.class);
	}

	@Test
	@DisplayName("Con el paciente presente hay que decir que se hizo")
	void el_cierre_presente_exige_nota() {
		// La etapa valida "tratamiento o nota equivalente". El detalle estructurado es 06.04, que
		// quedo cortada, asi que la nota es lo UNICO que registra que se hizo: no es un campo de
		// descarte. Y de este cierre se deriva una obligacion economica.
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));

		assertThatThrownBy(() ->
				service.cerrar(actor, CONSULTORIO_ID, 1L, cierre(Asistencia.PRESENTE, null), 0L))
				.isInstanceOf(CierreIncompletoException.class);
	}

	@Test
	@DisplayName("Con el paciente AUSENTE se cierra sin nota: no hubo atencion que describir")
	void el_ausente_cierra_sin_nota() {
		// La ausencia tambien es un hecho clinico y economico, y no registrarla dejaria el turno
		// abierto para siempre. Pedir resultado de una atencion que no ocurrio seria pedir que se
		// invente.
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));
		given(numerador.leerUltimo(anyLong(), anyLong())).willReturn(1);

		assertThat(service.cerrar(actor, CONSULTORIO_ID, 1L, cierre(Asistencia.AUSENTE, null), 0L)
				.numeroSesion())
				.isEqualTo(1);
	}

	@Test
	@DisplayName("Cerrar dos veces no renumera ni pide un correlativo nuevo")
	void el_cierre_es_idempotente() {
		// Si la idempotencia se evaluara DESPUES de pedir el numero, cada reintento consumiria un
		// correlativo que nadie usa y la numeracion del paciente quedaria con huecos que parecen
		// sesiones borradas.
		Sesion yaCerrada = sesionExistente(MEMBERSHIP_PROPIA);
		yaCerrada.cerrar(cierre(Asistencia.PRESENTE, "Terapia manual"), 3, null, Instant.EPOCH, CUENTA_PROPIA);
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L)).willReturn(Optional.of(yaCerrada));

		SesionView vista = service.cerrar(
				actor, CONSULTORIO_ID, 1L, cierre(Asistencia.PRESENTE, "Otra cosa"), 99L);

		assertThat(vista.numeroSesion()).isEqualTo(3);
		assertThat(vista.cierre().notaDeCierre())
				.as("y no se sobreescribe: corregir una sesion cerrada es una enmienda, no un segundo cierre")
				.isEqualTo("Terapia manual");
		verify(numerador, never()).incrementar(anyLong(), anyLong());
	}

	@Test
	@DisplayName("Una sesion cerrada no admite mas borrador ni evaluacion")
	void la_sesion_cerrada_no_se_edita() {
		// Fail-closed hasta que exista la enmienda de 06.06: es preferible no poder corregir a
		// corregir sin dejar rastro, que es historia clinica reescrita en silencio.
		Sesion yaCerrada = sesionExistente(MEMBERSHIP_PROPIA);
		yaCerrada.cerrar(cierre(Asistencia.PRESENTE, "Terapia manual"), 1, null, Instant.EPOCH, CUENTA_PROPIA);
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L)).willReturn(Optional.of(yaCerrada));

		assertThatThrownBy(() -> service.guardarBorrador(actor, CONSULTORIO_ID, 1L, "{}", 0L))
				.isInstanceOf(SesionCerradaException.class);
	}

	// =================================================================================
	// El Caso Clinico (AKINE-04.03)
	// =================================================================================

	@Test
	@DisplayName("Un caso cerrado no admite sesiones nuevas: 409, no 404")
	void caso_cerrado_no_admite_sesion() {
		// El caso existe y es del paciente; lo que no admite es su estado. La accion correcta es
		// reabrirlo con motivo, no buscar otro caso.
		given(sesiones.findVivaPorTurno(ORG_ID, TURNO_ID)).willReturn(Optional.empty());
		given(turnos.find(ORG_ID, CONSULTORIO_ID, TURNO_ID))
				.willReturn(Optional.of(turno(MEMBERSHIP_PROPIA, true)));
		given(casos.find(ORG_ID, CASO_ID))
				.willReturn(Optional.of(new CasoSnapshot(CASO_ID, ORG_ID, HISTORIA_ID, 2, false)));

		assertThatThrownBy(() -> service.iniciar(actor, CONSULTORIO_ID, TURNO_ID, CASO_ID))
				.isInstanceOf(CasoNoAsignableException.class)
				.extracting(e -> ((CasoNoAsignableException) e).getMotivo())
				.isEqualTo(CasoNoAsignableException.Motivo.CERRADO);
	}

	@Test
	@DisplayName("Un caso de otra historia clinica no se puede colgar de esta atencion")
	void caso_de_otra_historia() {
		// Indistinguible de "no existe" desde afuera, a proposito: distinguirlos permitiria censar
		// por ids los casos de otro paciente.
		given(sesiones.findVivaPorTurno(ORG_ID, TURNO_ID)).willReturn(Optional.empty());
		given(turnos.find(ORG_ID, CONSULTORIO_ID, TURNO_ID))
				.willReturn(Optional.of(turno(MEMBERSHIP_PROPIA, true)));
		given(casos.find(ORG_ID, CASO_ID)).willReturn(Optional.of(
				new CasoSnapshot(CASO_ID, ORG_ID, HISTORIA_ID + 1, 2, true)));

		assertThatThrownBy(() -> service.iniciar(actor, CONSULTORIO_ID, TURNO_ID, CASO_ID))
				.isInstanceOf(CasoNoAsignableException.class)
				.extracting(e -> ((CasoNoAsignableException) e).getMotivo())
				.isEqualTo(CasoNoAsignableException.Motivo.DE_OTRA_HISTORIA);
	}

	@Test
	@DisplayName("El cierre toma los dos numeradores, y SIEMPRE la historia antes que el caso")
	void el_cierre_toma_los_dos_numeradores_en_orden() {
		// Es la primera transaccion de este sistema que toma dos numeradores. Cada uno es un lock
		// exclusivo de fila: si dos cierres concurrentes los tomaran en orden distinto, cada uno
		// esperaria el lock que el otro ya tiene. Un orden total fijo es lo unico que lo evita.
		Sesion conCaso = sesionConCaso();
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L)).willReturn(Optional.of(conCaso));
		given(numerador.leerUltimo(anyLong(), anyLong())).willReturn(8);
		given(casos.siguienteNumeroDeSesion(ORG_ID, CASO_ID)).willReturn(3);

		SesionView vista = service.cerrar(
				actor, CONSULTORIO_ID, 1L, cierre(Asistencia.PRESENTE, "Terapia manual"), 0L);

		InOrder orden = inOrder(numerador, casos);
		orden.verify(numerador).incrementar(ORG_ID, HISTORIA_ID);
		orden.verify(casos).siguienteNumeroDeSesion(ORG_ID, CASO_ID);

		assertThat(vista.numeroSesion()).as("el correlativo por historia no cambia").isEqualTo(8);
		assertThat(vista.numeroEnCaso()).as("y el del caso se agrega al lado").isEqualTo(3);
	}

	@Test
	@DisplayName("Una sesion sin caso no pide el segundo numero: no hay caso dentro del cual contar")
	void sin_caso_no_hay_segundo_numerador() {
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));
		given(numerador.leerUltimo(anyLong(), anyLong())).willReturn(8);

		SesionView vista = service.cerrar(
				actor, CONSULTORIO_ID, 1L, cierre(Asistencia.PRESENTE, "Terapia manual"), 0L);

		assertThat(vista.numeroEnCaso()).isNull();
		verify(casos, never()).siguienteNumeroDeSesion(anyLong(), anyLong());
	}

	/** Una sesion ya persistida que pertenece a un caso. */
	private static Sesion sesionConCaso() {
		Sesion sesion = new Sesion(ORG_ID, CONSULTORIO_ID, HISTORIA_ID, CASO_ID, TURNO_ID,
				OFERTA_ID, MEMBERSHIP_PROPIA, Instant.EPOCH, CUENTA_PROPIA);
		ReflectionTestUtils.setField(sesion, "id", 1L);
		return sesion;
	}


	// =================================================================================
	// Enmienda de una sesion cerrada (AKINE-06.06)
	// =================================================================================
	//
	// Tres reglas y nada mas: que la correccion se VERSIONE en vez de pisar el original, que exija
	// motivo, y que una sesion de otro tenant sea 404. Lo demas de la etapa —el mapeo HTTP de cada
	// excepcion, el permiso, la auditoria, el largo del motivo— queda deliberadamente sin test.

	private static final String MOTIVO = "Se corrigio la lateralidad: el dolor era del lado derecho";

	/** Una sesion cerrada con contenido, que es lo unico que se puede enmendar. */
	private static Sesion sesionCerrada() {
		Sesion sesion = sesionExistente(MEMBERSHIP_PROPIA);
		sesion.cerrar(cierre(Asistencia.PRESENTE, "Terapia manual"), 8, null,
				Instant.EPOCH.plusSeconds(3600), CUENTA_PROPIA);
		return sesion;
	}

	private static ContenidoDeSesion contenido(String notaDeCierre) {
		return new ContenidoDeSesion("dolor lumbar", 4, "Lumbar", Lateralidad.DERECHA,
				Evolucion.MEJOR, null, null, notaDeCierre, null, null, null, null);
	}

	@Test
	@DisplayName("Enmendar escribe la version siguiente en vez de pisar el original")
	void enmendar_agrega_version() {
		// Es toda la etapa: una sesion cerrada es historia clinica y ADR-0011 prohibe reescribirla.
		// Enmendar no es un UPDATE aunque la cabecera se actualice —el original vive en la version
		// 1 que escribio el cierre— y la fila nueva va al lado, con su motivo.
		//
		// Y no vuelve a disparar lo economico: re-disparar los observadores solo puede AGREGAR
		// deuda o consumo de autorizacion y nunca sacarlos, y ninguno de los campos enmendables los
		// afecta. Por eso se verifica aca mismo y no en un test aparte.
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionCerrada()));

		SesionView vista = service.enmendar(
				actor, CONSULTORIO_ID, 1L, contenido("Nota corregida"), MOTIVO, 0L);

		ArgumentCaptor<SesionVersion> escrita = ArgumentCaptor.forClass(SesionVersion.class);
		verify(versiones).save(escrita.capture());
		assertThat(escrita.getValue().getNumeroVersion()).isEqualTo(2);
		assertThat(escrita.getValue().getMotivoEnmienda()).isEqualTo(MOTIVO);
		assertThat(escrita.getValue().getNotaDeCierre())
				.as("la version copia el contenido YA enmendado: al reves seria un historial que miente")
				.isEqualTo("Nota corregida");

		assertThat(vista.ultimoNumeroVersion()).isEqualTo(2);
		assertThat(vista.fueEnmendada()).isTrue();
		verify(observador, never()).alCerrar(any());
	}

	@Test
	@DisplayName("C-6: tratamientos y mediciones se corrigen DESPUES del flush de la cabecera y "
			+ "ANTES de la foto")
	void la_enmienda_clinica_va_entre_la_cabecera_y_la_foto() {
		// Despues del flush: la cabecera emite su UPDATE versionado primero, asi una segunda
		// enmienda concurrente espera el lock y termina en 409 sin haber leido un tratamiento.
		// Antes de la foto: la version tiene que decir lo que quedo.
		Sesion cerrada = sesionCerrada();
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L)).willReturn(Optional.of(cerrada));
		List<TratamientoEnmendado> tratamientosEnmendados = List.of(new TratamientoEnmendado(5L,
				new TratamientoAplicado(41L, null, null, null, null, null, null, null, List.of())));
		List<MedicionEnmendada> medicionesEnmendadas = List.of();

		service.enmendar(actor, CONSULTORIO_ID, 1L, contenido("Nota"), tratamientosEnmendados,
				medicionesEnmendadas, "  " + MOTIVO + "  ", 0L);

		InOrder orden = inOrder(sesiones, tratamientoService, medicionService, tratamientos, versiones);
		orden.verify(sesiones).saveAndFlush(cerrada);
		orden.verify(tratamientoService).aplicarEnmienda(
				any(), any(), org.mockito.ArgumentMatchers.eq(tratamientosEnmendados),
				org.mockito.ArgumentMatchers.eq(MOTIVO), any());
		orden.verify(medicionService).aplicarEnmienda(
				any(), any(), org.mockito.ArgumentMatchers.eq(medicionesEnmendadas), any());
		orden.verify(tratamientos).listarVigentes(ORG_ID, 1L);
		orden.verify(versiones).save(any());
		verify(observador, never()).alCerrar(any());
	}

	@Test
	@DisplayName("C-6: sin listas, la enmienda no toca tratamientos ni mediciones")
	void sin_listas_no_se_tocan() {
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionCerrada()));

		service.enmendar(actor, CONSULTORIO_ID, 1L, contenido("Nota"), MOTIVO, 0L);

		verify(tratamientoService, never()).aplicarEnmienda(any(), any(), any(), any(), any());
		verify(medicionService, never()).aplicarEnmienda(any(), any(), any(), any());
	}

	@Test
	@DisplayName("C-6: sin motivo no se escribe nada, tampoco tratamientos: el motivo es su baja")
	void sin_motivo_no_se_corrigen_tratamientos() {
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionCerrada()));

		assertThatThrownBy(() -> service.enmendar(actor, CONSULTORIO_ID, 1L, contenido("Nota"),
				List.of(), List.of(), "   ", 0L))
				.isInstanceOf(EnmiendaSinMotivoException.class);

		verify(sesiones, never()).saveAndFlush(any());
		verify(tratamientoService, never()).aplicarEnmienda(any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("La enmienda sin motivo se rechaza y no escribe ninguna version")
	void enmendar_sin_motivo_se_rechaza() {
		// RN-M14-006 no prohibe corregir una sesion cerrada: prohibe corregirla SILENCIOSAMENTE.
		// Sin motivo una enmienda es indistinguible de una correccion de tipeo y el historial deja
		// de servir para lo unico que sirve. Lo hace cumplir la entidad y no el DTO, asi que un
		// camino futuro que no pase por el controller falla igual.
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionCerrada()));

		assertThatThrownBy(() ->
				service.enmendar(actor, CONSULTORIO_ID, 1L, contenido("Nota"), "   ", 0L))
				.isInstanceOf(EnmiendaSinMotivoException.class);

		verify(versiones, never()).save(any());
	}

	@Test
	@DisplayName("Una sesion de otro tenant es 404 al enmendar y al leer el historial, nunca 403")
	void la_sesion_de_otro_tenant_no_existe() {
		// Indistinguible de "no existe" a proposito: un 403 confirmaria que ese id existe en otra
		// organizacion, que es censar el padron ajeno de a un id por vez. Es la regla desde 01.01, y
		// vale igual para el historial: protegerlo con menos que el original seria una puerta
		// lateral a lo mismo.
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L)).willReturn(Optional.empty());

		assertThatThrownBy(() ->
				service.enmendar(actor, CONSULTORIO_ID, 1L, contenido("Nota"), MOTIVO, 0L))
				.isInstanceOf(SesionNotAccessibleException.class);

		assertThatThrownBy(() -> service.versiones(actor, CONSULTORIO_ID, 1L))
				.isInstanceOf(SesionNotAccessibleException.class);

		verify(versiones, never()).save(any());
		verify(versiones, never()).buscarPorSesion(anyLong(), anyLong());
	}

	@Test
	@DisplayName("La enmienda se audita como transicion de version, sin el motivo en el evento")
	void la_enmienda_se_audita_sin_el_motivo() {
		// El motivo es prosa sobre un paciente, y audit_event se lee con auditoria:read, que no es
		// un permiso clinico. Va la transicion; el detalle vive en la version.
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionCerrada()));

		service.enmendar(actor, CONSULTORIO_ID, 1L, contenido("Nota corregida"), MOTIVO, 0L);

		ArgumentCaptor<AuditEntry> evento = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(evento.capture());
		assertThat(evento.getValue().eventType()).isEqualTo(AuditEvents.SESION_AMENDED);
		assertThat(evento.getValue().previousState()).isEqualTo("VERSION_1");
		assertThat(evento.getValue().newState()).isEqualTo("VERSION_2");
		assertThat(evento.getValue().reason()).isNull();
		assertThat(evento.getValue().details().values()).doesNotContain(MOTIVO);
	}

	@Test
	@DisplayName("Enmendar una sesion abierta es 409: eso se guarda, no se enmienda")
	void la_abierta_no_se_enmienda() {
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));

		assertThatThrownBy(() ->
				service.enmendar(actor, CONSULTORIO_ID, 1L, contenido("Nota"), MOTIVO, 0L))
				.isInstanceOf(SesionNoCerradaException.class);
		verify(versiones, never()).save(any());
		verify(auditTrail, never()).record(any());
	}

	@Test
	@DisplayName("El historial devuelve las versiones del puerto, de la 1 a la ultima")
	void el_historial_se_lee_del_puerto() {
		Sesion cerrada = sesionCerrada();
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L)).willReturn(Optional.of(cerrada));
		given(versiones.buscarPorSesion(ORG_ID, 1L))
				.willReturn(List.of(SesionVersion.original(cerrada, new FotoClinica(null, null))));

		List<SesionVersionView> historial = service.versiones(actor, CONSULTORIO_ID, 1L);

		assertThat(historial).singleElement().satisfies(version -> {
			assertThat(version.numeroVersion()).isEqualTo(1);
			assertThat(version.motivoEnmienda()).isNull();
			assertThat(version.notaDeCierre()).isEqualTo("Terapia manual");
			assertThat(version.registradaPor()).isEqualTo(CUENTA_PROPIA);
		});
	}

	// =================================================================================
	// Lo que el cierre le avisa a billing y contracting (07.01, 04.05, 06.04)
	// =================================================================================

	@Test
	@DisplayName("El aviso de cierre lleva la persona de la historia, el precio de la oferta y las "
			+ "practicas realizadas")
	void el_aviso_de_cierre_lleva_lo_que_el_observador_necesita() {
		// Se lee todo ACA y no en cada observador: si cada uno leyera el precio por su cuenta, dos
		// de ellos podrian devengar contra precios distintos si alguien edita la oferta en el medio.
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));
		given(numerador.leerUltimo(anyLong(), anyLong())).willReturn(8);
		given(ofertas.precioEn(eq(ORG_ID), eq(CONSULTORIO_ID), eq(OFERTA_ID), any())).willReturn(Optional.of(
				new PrecioDeOferta(OFERTA_ID, new BigDecimal("8500.00"), "ARS", true)));
		given(tratamientos.practicasVigentesDe(ORG_ID, 1L)).willReturn(List.of(610L, 611L, 610L));

		service.cerrar(actor, CONSULTORIO_ID, 1L, cierre(Asistencia.PRESENTE, "Terapia manual"), 0L);

		ArgumentCaptor<SesionCerrada> aviso = ArgumentCaptor.forClass(SesionCerrada.class);
		verify(observador).alCerrar(aviso.capture());
		assertThat(aviso.getValue().personaId()).isEqualTo(PERSONA_ID);
		assertThat(aviso.getValue().numeroSesion()).isEqualTo(8);
		assertThat(aviso.getValue().asistio()).isTrue();
		assertThat(aviso.getValue().precioDeLaOferta()).isEqualByComparingTo("8500");
		assertThat(aviso.getValue().moneda()).isEqualTo("ARS");
		assertThat(aviso.getValue().practicasRealizadas()).containsExactlyInAnyOrder(610L, 611L);
		// AKINE F-4: si la oferta admite obra social se lee en el mismo punto que el precio.
		assertThat(aviso.getValue().ofertaAdmiteObraSocial()).isTrue();
	}

	@Test
	@DisplayName("Una oferta sin precio no impide cerrar: el aviso viaja sin importe")
	void la_oferta_sin_precio_cierra_igual() {
		// DP-06: cerrar no cobra. Que la oferta no este tarifada es asunto de billing; negar el
		// cierre clinico por eso haria que un problema de facturacion bloquee una historia clinica.
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));
		given(numerador.leerUltimo(anyLong(), anyLong())).willReturn(1);
		given(ofertas.precioEn(anyLong(), anyLong(), anyLong(), any())).willReturn(Optional.empty());

		service.cerrar(actor, CONSULTORIO_ID, 1L, cierre(Asistencia.AUSENTE, null), 0L);

		ArgumentCaptor<SesionCerrada> aviso = ArgumentCaptor.forClass(SesionCerrada.class);
		verify(observador).alCerrar(aviso.capture());
		assertThat(aviso.getValue().asistio()).isFalse();
		assertThat(aviso.getValue().precioDeLaOferta()).isNull();
		assertThat(aviso.getValue().moneda()).isNull();
		assertThat(aviso.getValue().ofertaAdmiteObraSocial()).isFalse();
	}

	@Test
	@DisplayName("Una sesion cuya historia clinica no existe no se cierra: falla antes de avisar")
	void historia_inexistente_falla_cerrado() {
		// Sin persona no hay a quien devengarle la deuda. Seguir con un id inventado seria peor
		// que fallar, y el fallo deshace el cierre entero.
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));
		given(numerador.leerUltimo(anyLong(), anyLong())).willReturn(1);
		given(historias.findPorId(anyLong(), anyLong())).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.cerrar(
				actor, CONSULTORIO_ID, 1L, cierre(Asistencia.PRESENTE, "Terapia manual"), 0L))
				.isInstanceOf(IllegalStateException.class);
		verify(observador, never()).alCerrar(any());
	}

	// =================================================================================
	// Precondiciones de acceso
	// =================================================================================

	@Test
	@DisplayName("Sin contexto de trabajo es 403 y nunca 401: un 401 deja al frontend en bucle")
	void sin_contexto_es_403() {
		OperatingActor sinContexto = new OperatingActor(CUENTA_PROPIA, false, null, null);

		assertThatThrownBy(() -> service.ver(sinContexto, CONSULTORIO_ID, 1L))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("Una sede de otro tenant es 404 antes de evaluar ningun permiso")
	void la_sede_ajena_es_404() {
		given(consultorios.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.iniciar(actor, CONSULTORIO_ID, TURNO_ID, null))
				.isInstanceOf(ConsultorioNoAccesibleException.class);
		verify(permissionGuard, never()).requirePermission(any());
	}

	@Test
	@DisplayName("Sin un vinculo activo con la sede no se escribe en ninguna sesion")
	void sin_vinculo_activo_con_la_sede() {
		// La membership es lo que identifica al profesional, no la cuenta: sin una activa en esta
		// sede no hay a nombre de quien comprobar la propiedad.
		given(memberships.findByAccount(ORG_ID, CUENTA_PROPIA)).willReturn(List.of(
				new ConsultorioMembershipSnapshot(MEMBERSHIP_PROPIA, CUENTA_PROPIA, ORG_ID,
						CONSULTORIO_ID, "PROFESIONAL", "ACTIVA", Instant.EPOCH, null, false, true)));
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, 1L))
				.willReturn(Optional.of(sesionExistente(MEMBERSHIP_PROPIA)));

		assertThatThrownBy(() -> service.guardarBorrador(actor, CONSULTORIO_ID, 1L, "{}", 0L))
				.isInstanceOf(AccessDeniedException.class);
		verify(sesiones, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Un caso que no existe en el tenant es 404, igual que uno de otra historia")
	void caso_inexistente() {
		given(sesiones.findVivaPorTurno(ORG_ID, TURNO_ID)).willReturn(Optional.empty());
		given(turnos.find(ORG_ID, CONSULTORIO_ID, TURNO_ID))
				.willReturn(Optional.of(turno(MEMBERSHIP_PROPIA, true)));
		given(casos.find(ORG_ID, CASO_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.iniciar(actor, CONSULTORIO_ID, TURNO_ID, CASO_ID))
				.isInstanceOf(CasoNoAsignableException.class)
				.extracting(e -> ((CasoNoAsignableException) e).getMotivo())
				.isEqualTo(CasoNoAsignableException.Motivo.NO_ACCESIBLE);
		verify(sesiones, never()).save(any());
	}
}
