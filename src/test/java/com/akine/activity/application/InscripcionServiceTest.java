package com.akine.activity.application;

import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.EstadoInscripcion;
import com.akine.activity.domain.InscripcionClase;
import com.akine.activity.domain.exception.ClaseCompletaException;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseProgramadaRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.InscripcionClaseRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
