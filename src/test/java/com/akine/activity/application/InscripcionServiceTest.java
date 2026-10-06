package com.akine.activity.application;

import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.EstadoClase;
import com.akine.activity.domain.EstadoInscripcion;
import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.InscripcionClase;
import com.akine.activity.domain.exception.ClaseCompletaException;
import com.akine.activity.domain.exception.ConsultorioNoAccesibleException;
import com.akine.activity.domain.exception.InscripcionDuplicadaException;
import com.akine.activity.domain.exception.InscripcionNotAccessibleException;
import com.akine.activity.domain.exception.PersonaNoAccesibleException;
import com.akine.activity.domain.exception.TransicionDeClaseNoPermitidaException;
import com.akine.activity.domain.exception.TransicionDeInscripcionNoPermitidaException;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseProgramadaRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.InscripcionClaseRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
 * Lo poco que un unitario puede decir de verdad sobre esta etapa.
 *
 * <h2>Lo que NO se prueba aca, y no por pereza</h2>
 *
 * <p><b>La ultima vacante disputada y la promocion doble no se simulan.</b> Son el caso que define
 * si la etapa sirve y son <b>exactamente</b> lo que un mock no puede contestar: un repositorio
 * falso que devuelve {@code 0} no reproduce ni el gestor de locks de InnoDB ni la semantica de
 * current read del {@code UPDATE}. Un test que "prueba" la concurrencia con mocks prueba que el
 * mock devuelve lo que se le dijo, y despues alguien lo lee como evidencia. Quedan anotados en
 * {@code docs/tests-diferidos.md} con etapa destino.
 *
 * <p>Lo que si se prueba es lo que un unitario decide sin ambiguedad: <b>el orden de las dos
 * escrituras de cupo</b> —liberar antes de leer la cola, que es lo que hace correcto a todo lo
 * demas— y las ramas de cero filas, que son las decisiones que el codigo toma cuando la base dice
 * que no.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InscripcionService")
class InscripcionServiceTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 10L;
	private static final long CLASE_ID = 77L;
	private static final long OFERTA_ID = 45L;
	private static final long PERSONA_ID = 512L;
	private static final long CUENTA_ID = 99L;

	private static final Instant INICIO = Instant.now().plus(7, ChronoUnit.DAYS);

	@Mock private InscripcionClaseRepositoryPort inscripciones;
	@Mock private ClaseProgramadaRepositoryPort clases;
	@Mock private CapacidadDeClase capacidad;
	@Mock private AvisosDeClase avisos;
	@Mock private PacienteDirectory personas;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AuditTrail auditTrail;

	private InscripcionService service;

	@BeforeEach
	void setUp() {
		service = new InscripcionService(inscripciones, clases, capacidad, avisos, personas,
				consultorios, permissionGuard, auditTrail);

		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(sede()));
		given(clases.findByIdInScope(ORG_ID, SEDE_ID, CLASE_ID))
				.willReturn(Optional.of(claseConOcupados(8, 6)));
		given(capacidad.efectiva(anyLong(), anyLong(), any())).willReturn(8);
		given(personas.find(ORG_ID, PERSONA_ID)).willReturn(Optional.of(persona()));
		given(inscripciones.findVivaDePersona(ORG_ID, CLASE_ID, PERSONA_ID))
				.willReturn(Optional.empty());
		// El id lo pone la base al insertar: el mock lo simula porque la vista lo lleva, y sin el
		// la proyeccion revienta antes de poder decir nada de la regla que se esta probando.
		given(inscripciones.saveAndFlush(any())).willAnswer(llamada -> {
			InscripcionClase guardada = llamada.getArgument(0);
			if (guardada.getId() == null) {
				ReflectionTestUtils.setField(guardada, "id", 301L);
			}
			return guardada;
		});
		given(inscripciones.contarEnEspera(ORG_ID, CLASE_ID)).willReturn(0);
	}

	/**
	 * Una fila afectada es "tenes el lugar", y de ahi sale el estado. <b>El servicio no cuenta
	 * nada</b>: no hay ningun {@code COUNT} previo del que dependa la decision, y por eso no hay
	 * ventana entre leer y escribir.
	 */
	@Test
	@DisplayName("Con lugar disponible la inscripcion queda RESERVADA y no pide posicion de cola")
	void con_lugar_queda_reservada() {
		given(clases.tomarCupo(ORG_ID, CLASE_ID, 8)).willReturn(1);

		var resultado = service.inscribir(actor(), SEDE_ID, CLASE_ID, comando(false));

		assertThat(resultado.inscripcion().estado()).isEqualTo("RESERVADA");
		assertThat(resultado.inscripcion().posicionEspera()).isNull();
		assertThat(resultado.creada()).isTrue();
		// Quien entro con lugar no consume una posicion de la cola: gastarla dejaria un hueco por
		// cada persona que se anoto normalmente.
		verify(clases, never()).siguientePosicionDeEspera(anyLong(), anyLong());
	}

	/**
	 * RN-M28-005: la espera no consume cupo, y por eso esta rama es la del cero.
	 *
	 * <p>La posicion sale del contador de la clase —{@code UPDATE ... + 1}— y <b>nunca de un
	 * {@code MAX + 1}</b>, que repite numeros en cuanto hay dos altas a la vez.
	 */
	@Test
	@DisplayName("Sin lugar y aceptando la espera, entra a la cola con la posicion del contador")
	void sin_lugar_entra_a_la_cola() {
		given(clases.tomarCupo(ORG_ID, CLASE_ID, 8)).willReturn(0);
		given(clases.siguientePosicionDeEspera(ORG_ID, CLASE_ID)).willReturn(3);

		var resultado = service.inscribir(actor(), SEDE_ID, CLASE_ID, comando(true));

		assertThat(resultado.inscripcion().estado()).isEqualTo("LISTA_ESPERA");
		assertThat(resultado.inscripcion().posicionEspera()).isEqualTo(3);
	}

	/**
	 * El error especifico que pide el plan, con los dos numeros que le permiten a la pantalla
	 * ofrecer la lista de espera sin otra vuelta al servidor.
	 */
	@Test
	@DisplayName("Sin lugar y sin aceptar la espera, es clase-completa con capacidad y ocupados")
	void sin_lugar_y_sin_espera_es_clase_completa() {
		given(clases.tomarCupo(ORG_ID, CLASE_ID, 8)).willReturn(0);

		assertThatThrownBy(() -> service.inscribir(actor(), SEDE_ID, CLASE_ID, comando(false)))
				.isInstanceOf(ClaseCompletaException.class)
				.satisfies(error -> {
					ClaseCompletaException completa = (ClaseCompletaException) error;
					assertThat(completa.getCapacidadEfectiva()).isEqualTo(8);
					assertThat(completa.getOcupados()).isEqualTo(6);
				});
		verify(inscripciones, never()).saveAndFlush(any());
	}

	/**
	 * <b>El orden es lo que hace correcta a toda la lista de espera.</b>
	 *
	 * <p>{@code liberarCupo} es el {@code UPDATE} que toma el lock exclusivo de la fila de la clase
	 * y lo retiene hasta el commit. Leer la cola DESPUES es lo que hace que dos bajas simultaneas
	 * se serialicen y que la segunda no vuelva a promover a quien la primera ya promovio. Leerla
	 * antes seria leer una foto vieja: el mismo error que leer antes de bloquear, que ya se pago en
	 * 05.02 y en 02.04.
	 */
	@Test
	@DisplayName("Cancelar libera el lugar ANTES de leer la lista de espera")
	void cancelar_libera_antes_de_leer_la_cola() {
		given(inscripciones.findByIdInScope(ORG_ID, CLASE_ID, 301L))
				.willReturn(Optional.of(reservada(301L)));
		given(inscripciones.siguienteEnEspera(ORG_ID, CLASE_ID)).willReturn(Optional.empty());

		service.cancelar(actor(), SEDE_ID, CLASE_ID, 301L, "La paciente aviso que no viene");

		InOrder orden = inOrder(clases, inscripciones);
		orden.verify(clases).liberarCupo(ORG_ID, CLASE_ID);
		orden.verify(inscripciones).siguienteEnEspera(ORG_ID, CLASE_ID);
	}

	/**
	 * La segunda mitad de la promocion: el {@code UPDATE} condicional cuyo cero significa "ya la
	 * promovio otro".
	 *
	 * <p>Lo que importa no es que no promueva —eso es obvio— sino que <b>devuelva el lugar que
	 * habia tomado</b>. Quedarselo dejaria el contador contando un recibo que nadie emitio, y la
	 * clase mostraria un lugar menos del que tiene para siempre.
	 */
	@Test
	@DisplayName("Si otra operacion ya promovio a la cabeza de la cola, se devuelve el lugar tomado")
	void promocion_perdida_devuelve_el_lugar() {
		given(inscripciones.findByIdInScope(ORG_ID, CLASE_ID, 301L))
				.willReturn(Optional.of(reservada(301L)));
		given(inscripciones.siguienteEnEspera(ORG_ID, CLASE_ID))
				.willReturn(Optional.of(enEspera(302L, 1)));
		given(clases.tomarCupo(ORG_ID, CLASE_ID, 8)).willReturn(1);
		given(inscripciones.promover(anyLong(), anyLong(), any())).willReturn(0);

		var resultado = service.cancelar(
				actor(), SEDE_ID, CLASE_ID, 301L, "La paciente aviso que no viene");

		assertThat(resultado.promovida()).isNull();
		// Dos veces: la del lugar que la baja libero, y la del que se habia tomado para una
		// promocion que no ocurrio.
		verify(clases, org.mockito.Mockito.times(2)).liberarCupo(ORG_ID, CLASE_ID);
		verify(avisos, never()).avisarCupoLiberado(any(), any(), any(), any());
	}

	// =================================================================================
	// Idempotencia
	// =================================================================================

	/**
	 * El reintento con la misma clave y el mismo pedido devuelve lo ya creado <b>sin pedir
	 * lugar</b>: si pasara por {@code tomarCupo} consumiria una vacante que nunca vuelve.
	 */
	@Test
	@DisplayName("El reintento con la misma clave devuelve la inscripcion existente sin tocar el cupo")
	void reintento_idempotente_no_toma_cupo() {
		InscribirCommand comando = new InscribirCommand(PERSONA_ID, false, "clave-1");
		InscripcionClase existente = InscripcionClase.conLugar(ORG_ID, SEDE_ID, CLASE_ID,
				PERSONA_ID, CUENTA_ID, Instant.now(), "clave-1", comando.huella(CLASE_ID));
		ReflectionTestUtils.setField(existente, "id", 301L);
		given(inscripciones.findByIdempotencyKey(ORG_ID, "clave-1"))
				.willReturn(Optional.of(existente));

		var resultado = service.inscribir(actor(), SEDE_ID, CLASE_ID, comando);

		assertThat(resultado.creada()).isFalse();
		assertThat(resultado.inscripcion().id()).isEqualTo(301L);
		verify(clases, never()).tomarCupo(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
		verify(inscripciones, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("La misma clave con otro pedido es conflicto de idempotencia, no un reintento")
	void misma_clave_otro_pedido() {
		InscripcionClase existente = InscripcionClase.conLugar(ORG_ID, SEDE_ID, CLASE_ID,
				PERSONA_ID + 1, CUENTA_ID, Instant.now(), "clave-1",
				new InscribirCommand(PERSONA_ID + 1, false, "clave-1").huella(CLASE_ID));
		given(inscripciones.findByIdempotencyKey(ORG_ID, "clave-1"))
				.willReturn(Optional.of(existente));

		assertThatThrownBy(() -> service.inscribir(actor(), SEDE_ID, CLASE_ID,
				new InscribirCommand(PERSONA_ID, false, "clave-1")))
				.isInstanceOf(IdempotencyKeyConflictException.class);
	}

	@Test
	@DisplayName("Con clave nueva la inscripcion guarda la clave y la huella del pedido")
	void clave_nueva_se_persiste_con_su_huella() {
		given(inscripciones.findByIdempotencyKey(ORG_ID, "clave-2")).willReturn(Optional.empty());
		given(clases.tomarCupo(ORG_ID, CLASE_ID, 8)).willReturn(1);
		InscribirCommand comando = new InscribirCommand(PERSONA_ID, false, "clave-2");

		service.inscribir(actor(), SEDE_ID, CLASE_ID, comando);

		org.mockito.ArgumentCaptor<InscripcionClase> guardada =
				org.mockito.ArgumentCaptor.forClass(InscripcionClase.class);
		verify(inscripciones).saveAndFlush(guardada.capture());
		assertThat(guardada.getValue().getIdempotencyKey()).isEqualTo("clave-2");
		assertThat(guardada.getValue().getRequestHash()).isEqualTo(comando.huella(CLASE_ID));
	}

	// =================================================================================
	// Reglas del alta
	// =================================================================================

	@Test
	@DisplayName("Una clase cancelada no admite inscripciones")
	void clase_cancelada_no_inscribe() {
		ClaseProgramada cancelada = claseConOcupados(8, 0);
		cancelada.cancelar("Se suspendio la actividad", CUENTA_ID, Instant.now());
		given(clases.findByIdInScope(ORG_ID, SEDE_ID, CLASE_ID)).willReturn(Optional.of(cancelada));

		assertThatThrownBy(() -> service.inscribir(actor(), SEDE_ID, CLASE_ID, comando(true)))
				.isInstanceOf(TransicionDeClaseNoPermitidaException.class)
				.hasMessageContaining("cancelada");
	}

	@Test
	@DisplayName("Una clase que ya empezo no se sigue llenando desde la API")
	void clase_ya_empezada_no_inscribe() {
		ClaseProgramada empezada = claseConOcupados(8, 0);
		ReflectionTestUtils.setField(empezada, "inicio", Instant.now().minus(5, ChronoUnit.MINUTES));
		given(clases.findByIdInScope(ORG_ID, SEDE_ID, CLASE_ID)).willReturn(Optional.of(empezada));

		assertThatThrownBy(() -> service.inscribir(actor(), SEDE_ID, CLASE_ID, comando(true)))
				.isInstanceOf(TransicionDeClaseNoPermitidaException.class)
				.hasMessageContaining("ya empezo");
		verify(clases, never()).tomarCupo(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	@DisplayName("Una persona fuera del padron del tenant es 404; una dada de baja es 409")
	void persona_fuera_del_padron_o_de_baja() {
		given(personas.find(ORG_ID, PERSONA_ID)).willReturn(Optional.empty());
		assertThatThrownBy(() -> service.inscribir(actor(), SEDE_ID, CLASE_ID, comando(true)))
				.isInstanceOf(PersonaNoAccesibleException.class);

		given(personas.find(ORG_ID, PERSONA_ID)).willReturn(Optional.of(new PacienteSnapshot(
				PERSONA_ID, ORG_ID, "Perez", "Ana", "DNI", "30111222", null, false, false,
				null, null)));
		assertThatThrownBy(() -> service.inscribir(actor(), SEDE_ID, CLASE_ID, comando(true)))
				.isInstanceOf(TransicionDeInscripcionNoPermitidaException.class);
		verify(clases, never()).tomarCupo(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	@DisplayName("Quien ya esta anotado no se anota de nuevo, ni siquiera en la cola")
	void inscripcion_duplicada() {
		given(inscripciones.findVivaDePersona(ORG_ID, CLASE_ID, PERSONA_ID))
				.willReturn(Optional.of(reservada(300L)));

		assertThatThrownBy(() -> service.inscribir(actor(), SEDE_ID, CLASE_ID, comando(true)))
				.isInstanceOf(InscripcionDuplicadaException.class);
		verify(clases, never()).tomarCupo(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	@DisplayName("Sede de otro tenant es 404, y sin contexto de trabajo es 403 antes de leer nada")
	void sede_ajena_y_sin_contexto() {
		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.empty());
		assertThatThrownBy(() -> service.inscribir(actor(), SEDE_ID, CLASE_ID, comando(true)))
				.isInstanceOf(ConsultorioNoAccesibleException.class);

		assertThatThrownBy(() -> service.inscribir(
				new OperatingActor(CUENTA_ID, false, null, null), SEDE_ID, CLASE_ID, comando(true)))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("El alta exige inscripcion:manage en ESA sede")
	void el_alta_exige_inscripcion_manage() {
		given(clases.tomarCupo(ORG_ID, CLASE_ID, 8)).willReturn(1);

		service.inscribir(actor(), SEDE_ID, CLASE_ID, comando(false));

		org.mockito.ArgumentCaptor<PermissionQuery> consulta =
				org.mockito.ArgumentCaptor.forClass(PermissionQuery.class);
		verify(permissionGuard).requirePermission(consulta.capture());
		assertThat(consulta.getValue().permissionCode()).isEqualTo(PermissionCodes.INSCRIPCION_MANAGE);
		assertThat(consulta.getValue().consultorioId()).isEqualTo(SEDE_ID);
	}

	// =================================================================================
	// Confirmar
	// =================================================================================

	@Test
	@DisplayName("Confirmar una reservada audita; confirmar una confirmada no audita otra vez")
	void confirmar_es_idempotente() {
		InscripcionClase inscripcion = reservada(301L);
		given(inscripciones.findByIdInScope(ORG_ID, CLASE_ID, 301L))
				.willReturn(Optional.of(inscripcion));

		assertThat(service.confirmar(actor(), SEDE_ID, CLASE_ID, 301L).estado())
				.isEqualTo("CONFIRMADA");
		assertThat(service.confirmar(actor(), SEDE_ID, CLASE_ID, 301L).estado())
				.isEqualTo("CONFIRMADA");

		verify(auditTrail, org.mockito.Mockito.times(1)).record(any());
	}

	@Test
	@DisplayName("Quien espera no se confirma: nunca tuvo lugar")
	void confirmar_en_espera() {
		given(inscripciones.findByIdInScope(ORG_ID, CLASE_ID, 302L))
				.willReturn(Optional.of(enEspera(302L, 1)));

		assertThatThrownBy(() -> service.confirmar(actor(), SEDE_ID, CLASE_ID, 302L))
				.isInstanceOf(TransicionDeInscripcionNoPermitidaException.class);
	}

	@Test
	@DisplayName("Una inscripcion de otra clase o de otro tenant es 404")
	void confirmar_inexistente() {
		given(inscripciones.findByIdInScope(ORG_ID, CLASE_ID, 303L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.confirmar(actor(), SEDE_ID, CLASE_ID, 303L))
				.isInstanceOf(InscripcionNotAccessibleException.class);
	}

	// =================================================================================
	// Cancelar y promover
	// =================================================================================

	/**
	 * Cancelar dos veces no libera dos lugares. Cuando 08.07 cuelgue la devolucion de creditos de
	 * aca, esta es la propiedad que impide devolver dos veces.
	 */
	@Test
	@DisplayName("Cancelar una cancelada no libera un segundo lugar ni audita")
	void cancelar_es_idempotente() {
		InscripcionClase cancelada = reservada(301L);
		cancelada.cancelar("Aviso", CUENTA_ID, Instant.now());
		given(inscripciones.findByIdInScope(ORG_ID, CLASE_ID, 301L))
				.willReturn(Optional.of(cancelada));

		var resultado = service.cancelar(actor(), SEDE_ID, CLASE_ID, 301L, "Otra vez");

		assertThat(resultado.inscripcion().estado()).isEqualTo("CANCELADA");
		assertThat(resultado.inscripcion().motivoCancelacion()).isEqualTo("Aviso");
		verify(clases, never()).liberarCupo(anyLong(), anyLong());
		verify(auditTrail, never()).record(any());
	}

	@Test
	@DisplayName("Dar de baja a quien esperaba no libera ningun lugar: nunca lo tuvo")
	void cancelar_en_espera_no_libera() {
		given(inscripciones.findByIdInScope(ORG_ID, CLASE_ID, 302L))
				.willReturn(Optional.of(enEspera(302L, 2)));

		var resultado = service.cancelar(actor(), SEDE_ID, CLASE_ID, 302L, "Ya no puede");

		assertThat(resultado.promovida()).isNull();
		verify(clases, never()).liberarCupo(anyLong(), anyLong());
		verify(inscripciones, never()).siguienteEnEspera(anyLong(), anyLong());
	}

	@Test
	@DisplayName("La promocion exitosa toma el lugar, audita y avisa a la persona promovida")
	void promocion_exitosa() {
		InscripcionClase cabeza = enEspera(302L, 1);
		given(inscripciones.findByIdInScope(ORG_ID, CLASE_ID, 301L))
				.willReturn(Optional.of(reservada(301L)));
		given(inscripciones.findByIdInScope(ORG_ID, CLASE_ID, 302L)).willReturn(Optional.of(cabeza));
		given(inscripciones.siguienteEnEspera(ORG_ID, CLASE_ID)).willReturn(Optional.of(cabeza));
		given(clases.tomarCupo(ORG_ID, CLASE_ID, 8)).willReturn(1);
		given(inscripciones.promover(eq(ORG_ID), eq(302L), any())).willReturn(1);

		var resultado = service.cancelar(actor(), SEDE_ID, CLASE_ID, 301L, "No viene");

		assertThat(resultado.promovida()).isNotNull();
		assertThat(resultado.promovida().id()).isEqualTo(302L);
		verify(avisos).avisarCupoLiberado(any(), eq("Sede Centro"), eq("UTC"), eq(cabeza));
		// La baja y la promocion: dos registros de auditoria.
		verify(auditTrail, org.mockito.Mockito.times(2)).record(any());
		verify(clases, org.mockito.Mockito.times(1)).liberarCupo(ORG_ID, CLASE_ID);
	}

	/**
	 * Si el box se cambio por uno mas chico, la capacidad efectiva quedo por debajo de lo ocupado
	 * y el lugar que se libero no existe. Prometerselo a alguien seria peor que no promover.
	 */
	@Test
	@DisplayName("Si la capacidad efectiva bajo, el lugar liberado no se promueve")
	void sin_capacidad_no_promueve() {
		given(inscripciones.findByIdInScope(ORG_ID, CLASE_ID, 301L))
				.willReturn(Optional.of(reservada(301L)));
		given(inscripciones.siguienteEnEspera(ORG_ID, CLASE_ID))
				.willReturn(Optional.of(enEspera(302L, 1)));
		given(clases.tomarCupo(ORG_ID, CLASE_ID, 8)).willReturn(0);

		var resultado = service.cancelar(actor(), SEDE_ID, CLASE_ID, 301L, "No viene");

		assertThat(resultado.promovida()).isNull();
		verify(inscripciones, never()).promover(anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("Una clase que ya no esta PROGRAMADA no promueve a nadie de la cola")
	void clase_no_programada_no_promueve() {
		ClaseProgramada enCurso = claseConOcupados(8, 8);
		ReflectionTestUtils.setField(enCurso, "estado", EstadoClase.EN_CURSO);
		given(clases.findByIdInScope(ORG_ID, SEDE_ID, CLASE_ID)).willReturn(Optional.of(enCurso));
		given(inscripciones.findByIdInScope(ORG_ID, CLASE_ID, 301L))
				.willReturn(Optional.of(reservada(301L)));
		given(inscripciones.siguienteEnEspera(ORG_ID, CLASE_ID))
				.willReturn(Optional.of(enEspera(302L, 1)));

		var resultado = service.cancelar(actor(), SEDE_ID, CLASE_ID, 301L, "No viene");

		assertThat(resultado.promovida()).isNull();
		verify(clases, never()).tomarCupo(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	@Test
	@DisplayName("Los participantes resuelven nombres en un batch y la persona faltante no rompe la lista")
	void participantes_con_persona_faltante() {
		InscripcionClase conNombre = reservada(301L);
		InscripcionClase sinNombre = enEspera(302L, 1);
		given(inscripciones.findDeLaClase(ORG_ID, CLASE_ID)).willReturn(List.of(conNombre, sinNombre));
		given(personas.findAll(eq(ORG_ID), any())).willReturn(Map.of(PERSONA_ID, persona()));

		List<ParticipanteView> lista = service.participantes(actor(), SEDE_ID, CLASE_ID);

		assertThat(lista).hasSize(2);
		assertThat(lista.get(0).apellido()).isEqualTo("Perez");
		assertThat(lista.get(1).apellido()).isNull();
		assertThat(lista.get(1).inscripcion().estado()).isEqualTo("LISTA_ESPERA");
		verify(personas, org.mockito.Mockito.times(1)).findAll(eq(ORG_ID), any());
	}

	@Test
	@DisplayName("Los cupos publican disponibles sobre la capacidad EFECTIVA, nunca negativos")
	void cupos_sobre_capacidad_efectiva() {
		given(clases.findByIdInScope(ORG_ID, SEDE_ID, CLASE_ID))
				.willReturn(Optional.of(claseConOcupados(10, 7)));
		given(capacidad.efectiva(anyLong(), anyLong(), any())).willReturn(6);
		given(inscripciones.contarEnEspera(ORG_ID, CLASE_ID)).willReturn(2);

		CuposView cupos = service.cupos(actor(), SEDE_ID, CLASE_ID);

		assertThat(cupos.capacidad()).isEqualTo(10);
		assertThat(cupos.capacidadEfectiva()).isEqualTo(6);
		assertThat(cupos.ocupados()).isEqualTo(7);
		assertThat(cupos.disponibles()).isZero();
		assertThat(cupos.enEspera()).isEqualTo(2);
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static OperatingActor actor() {
		return new OperatingActor(CUENTA_ID, false, ORG_ID, SEDE_ID);
	}

	private static InscribirCommand comando(boolean aceptaListaEspera) {
		return new InscribirCommand(PERSONA_ID, aceptaListaEspera, null);
	}

	private static ConsultorioSnapshot sede() {
		return new ConsultorioSnapshot(SEDE_ID, ORG_ID, "Sede Centro", "UTC", true);
	}

	private static PacienteSnapshot persona() {
		return new PacienteSnapshot(PERSONA_ID, ORG_ID, "Perez", "Ana", "DNI", "30111222",
				null, true, false, null, null);
	}

	private static ClaseProgramada claseConOcupados(int capacidad, int ocupados) {
		ClaseProgramada clase = new ClaseProgramada(
				ORG_ID, SEDE_ID, OFERTA_ID, 31L, 8L, "Pilates - avanzado",
				INICIO, INICIO.plus(1, ChronoUnit.HOURS), capacidad, CUENTA_ID,
				Instant.now(), null, null);
		ReflectionTestUtils.setField(clase, "id", CLASE_ID);
		// La columna es de solo lectura desde JPA —la escribe el UPDATE condicional y nadie mas—,
		// asi que el fixture la pone por reflexion igual que lo haria la base.
		ReflectionTestUtils.setField(clase, "cupoOcupado", ocupados);
		return clase;
	}

	private static InscripcionClase reservada(long id) {
		InscripcionClase inscripcion = InscripcionClase.conLugar(
				ORG_ID, SEDE_ID, CLASE_ID, PERSONA_ID, CUENTA_ID, Instant.now(), null, null);
		ReflectionTestUtils.setField(inscripcion, "id", id);
		assertThat(inscripcion.getEstado()).isEqualTo(EstadoInscripcion.RESERVADA);
		return inscripcion;
	}

	private static InscripcionClase enEspera(long id, int posicion) {
		InscripcionClase inscripcion = InscripcionClase.enEspera(
				ORG_ID, SEDE_ID, CLASE_ID, PERSONA_ID + 1, posicion, CUENTA_ID, Instant.now(),
				null, null);
		ReflectionTestUtils.setField(inscripcion, "id", id);
		return inscripcion;
	}
}
