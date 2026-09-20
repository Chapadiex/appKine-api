package com.akine.activity.application;

import com.akine.activity.domain.AsistenciaActividad;
import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.EstadoClase;
import com.akine.activity.domain.InscripcionClase;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.activity.domain.exception.ClaseCompletaException;
import com.akine.activity.domain.exception.TransicionDeClaseNoPermitidaException;
import com.akine.activity.domain.exception.TransicionDeInscripcionNoPermitidaException;
import com.akine.activity.domain.port.ActivityRepositoryPorts.AsistenciaActividadRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.AsistenciaEventoRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseEventoRepositoryPort;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Lo poco que un unitario puede decir de verdad sobre esta etapa.
 *
 * <h2>Lo que NO se prueba aca, y no por pereza</h2>
 *
 * <p><b>El ingreso sin inscripcion sobre la ultima vacante no se simula.</b> Es el caso que rompe
 * el diseno y es <b>exactamente</b> lo que un mock no puede contestar: un repositorio falso que
 * devuelve {@code 0} no reproduce el gestor de locks de InnoDB ni la semantica de current read del
 * {@code UPDATE}. Lo que si se prueba aca es la <b>decision</b> del servicio cuando la base dice
 * que no hay lugar — que es otra cosa, y conviene no confundirlas.
 *
 * <p><b>Tampoco se prueba que el lote sea transaccionalmente independiente</b>: con mocks se
 * verifica el ruteo, no la propagacion. Ni el unique que sostiene la idempotencia del cierre.
 * Los tres quedan en {@code docs/tests-diferidos.md} con etapa destino.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AsistenciaService")
class AsistenciaServiceTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 10L;
	private static final long CLASE_ID = 77L;
	private static final long OFERTA_ID = 45L;
	private static final long PERSONA_ID = 512L;
	private static final long CUENTA_ID = 99L;
	private static final long INSCRIPCION_ID = 412L;
	private static final long PROFESIONAL_ID = 31L;

	private static final Instant INICIO = Instant.now().minus(1, ChronoUnit.HOURS);

	@Mock private AsistenciaActividadRepositoryPort asistencias;
	@Mock private AsistenciaEventoRepositoryPort eventos;
	@Mock private InscripcionClaseRepositoryPort inscripciones;
	@Mock private ClaseProgramadaRepositoryPort clases;
	@Mock private ClaseEventoRepositoryPort eventosDeClase;
	@Mock private CapacidadDeClase capacidad;
	@Mock private PacienteDirectory personas;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AuditTrail auditTrail;

	private AsistenciaService service;

	@BeforeEach
	void setUp() {
		service = new AsistenciaService(asistencias, eventos, inscripciones, clases, eventosDeClase,
				capacidad, personas, consultorios, permissionGuard, auditTrail);

		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(sede()));
		given(clases.findByIdInScope(ORG_ID, SEDE_ID, CLASE_ID))
				.willReturn(Optional.of(clase(EstadoClase.EN_CURSO, 8, 6)));
		given(capacidad.efectiva(anyLong(), anyLong(), any())).willReturn(8);
		given(inscripciones.contarEnEspera(ORG_ID, CLASE_ID)).willReturn(0);
		given(inscripciones.findByIdInScope(ORG_ID, CLASE_ID, INSCRIPCION_ID))
				.willReturn(Optional.of(reservada(INSCRIPCION_ID)));
		given(asistencias.findDeInscripcion(ORG_ID, INSCRIPCION_ID)).willReturn(Optional.empty());
		given(inscripciones.saveAndFlush(any())).willAnswer(llamada -> llamada.getArgument(0));
		// El id lo pone la base al insertar: sin el, la proyeccion revienta antes de poder decir
		// nada de la regla que se esta probando.
		given(asistencias.saveAndFlush(any())).willAnswer(llamada -> {
			AsistenciaActividad guardada = llamada.getArgument(0);
			ReflectionTestUtils.setField(guardada, "id", 981L);
			return guardada;
		});
	}

	/**
	 * La regla maestra de la etapa, hecha aserto: el hecho es una fila propia y la inscripcion solo
	 * <b>proyecta</b> como quedo resuelta la reserva. Y <b>el cupo no se toca</b>: los dos estados
	 * involucrados lo consumen, asi que no hay nada que otorgar ni que liberar.
	 */
	@Test
	@DisplayName("Marcar presente crea el hecho, proyecta la inscripcion y no mueve el cupo")
	void marcar_presente_no_mueve_el_cupo() {
		var resultado = service.registrar(actor(), SEDE_ID, CLASE_ID, comando(
				ResultadoAsistencia.PRESENTE, null));

		assertThat(resultado.registrada()).isTrue();
		assertThat(resultado.asistencia().resultado()).isEqualTo("PRESENTE");
		assertThat(resultado.inscripcion().estado()).isEqualTo("ASISTIO");
		// NI UN LLAMADO AL ASIGNADOR. Si esta linea alguna vez falla, alguien reintrodujo una
		// escritura de cupo donde no hay ningun lugar que otorgar.
		verify(clases, never()).tomarCupo(anyLong(), anyLong(), anyInt());
		verify(clases, never()).liberarCupo(anyLong(), anyLong());
	}

	/**
	 * La idempotencia de la etapa <b>sale del hecho, no de una clave</b>: no hay
	 * {@code Idempotency-Key} y no hace falta, porque la clave natural es (clase, persona) y ya
	 * vive en un unique.
	 */
	@Test
	@DisplayName("Repetir el mismo resultado no escribe nada")
	void repetir_el_mismo_resultado_es_idempotente() {
		given(asistencias.findDeInscripcion(ORG_ID, INSCRIPCION_ID))
				.willReturn(Optional.of(yaMarcada(ResultadoAsistencia.PRESENTE)));

		var resultado = service.registrar(actor(), SEDE_ID, CLASE_ID, comando(
				ResultadoAsistencia.PRESENTE, null));

		assertThat(resultado.sinCambios()).isTrue();
		verify(asistencias, never()).saveAndFlush(any());
		verify(eventos, never()).registrar(any());
	}

	/**
	 * Corregir cambia un hecho ya afirmado, y el motivo es lo unico que lo hace auditable. El valor
	 * anterior <b>no se borra</b>: se apendea en el historial.
	 */
	@Test
	@DisplayName("Corregir sin motivo se rechaza; con motivo apendea el evento")
	void corregir_exige_motivo_y_apendea() {
		given(asistencias.findDeInscripcion(ORG_ID, INSCRIPCION_ID))
				.willReturn(Optional.of(yaMarcada(ResultadoAsistencia.AUSENTE)));

		assertThatThrownBy(() -> service.registrar(actor(), SEDE_ID, CLASE_ID, comando(
				ResultadoAsistencia.PRESENTE, null)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("motivo");

		var resultado = service.registrar(actor(), SEDE_ID, CLASE_ID, comando(
				ResultadoAsistencia.PRESENTE, "Se habia marcado ausente por error"));

		assertThat(resultado.corregida()).isTrue();
		assertThat(resultado.asistencia().correcciones()).isEqualTo(1);
		verify(eventos).registrar(any());
	}

	/**
	 * Quien espera <b>nunca tuvo lugar</b> (RN-M28-005), asi que no puede haber asistido a algo que
	 * no tenia reservado. Es 409 y no 404: la inscripcion existe y el mostrador la esta viendo.
	 */
	@Test
	@DisplayName("No se marca asistencia de quien esta en lista de espera")
	void lista_de_espera_no_asiste() {
		given(inscripciones.findByIdInScope(ORG_ID, CLASE_ID, INSCRIPCION_ID))
				.willReturn(Optional.of(enEspera(INSCRIPCION_ID)));

		assertThatThrownBy(() -> service.registrar(actor(), SEDE_ID, CLASE_ID, comando(
				ResultadoAsistencia.PRESENTE, null)))
				.isInstanceOf(TransicionDeInscripcionNoPermitidaException.class)
				.hasMessageContaining("lista de espera");
	}

	/**
	 * El caso que rompe el diseno, en la parte que un unitario si puede contestar: <b>el servicio
	 * no deja entrar a nadie si el asignador dice que no hay lugar</b>. Que el asignador acierte
	 * bajo concurrencia es otra cosa, y no se simula: ver la cabecera.
	 */
	@Test
	@DisplayName("El ingreso sin inscripcion pide lugar de verdad y sin lugar no entra")
	void ingreso_sin_inscripcion_respeta_el_cupo() {
		given(personas.find(ORG_ID, PERSONA_ID)).willReturn(Optional.of(persona()));
		given(inscripciones.findVivaDePersona(ORG_ID, CLASE_ID, PERSONA_ID))
				.willReturn(Optional.empty());
		given(clases.tomarCupo(ORG_ID, CLASE_ID, 8)).willReturn(0);

		assertThatThrownBy(() -> service.registrarIngresoSinInscripcion(
				actor(), SEDE_ID, CLASE_ID,
				new IngresoSinInscripcionCommand(
						PERSONA_ID, ResultadoAsistencia.PRESENTE_TARDE, null)))
				.isInstanceOf(ClaseCompletaException.class);

		// Y no se creo ninguna inscripcion huerfana: una asistencia sin recibo es una persona
		// adentro de la clase que el contador no ve.
		verify(inscripciones, never()).saveAndFlush(any());
	}

	/**
	 * El cierre resuelve a los que quedaron sin marcar y <b>no devenga nada</b>. Su idempotencia no
	 * descansa en una bandera sino en que la consulta de pendientes vuelva vacia, que es lo que el
	 * unique garantiza contra una base real.
	 */
	@Test
	@DisplayName("Cerrar ausenta a los pendientes, cancela la cola y no toca el cupo")
	void cerrar_resuelve_pendientes() {
		ClaseProgramada enCurso = clase(EstadoClase.EN_CURSO, 8, 6);
		given(clases.findByIdInScope(ORG_ID, SEDE_ID, CLASE_ID)).willReturn(Optional.of(enCurso));
		given(clases.saveAndFlush(any())).willAnswer(llamada -> llamada.getArgument(0));
		given(inscripciones.findConLugarSinAsistencia(ORG_ID, CLASE_ID))
				.willReturn(List.of(reservada(INSCRIPCION_ID)));
		given(asistencias.saveAll(any())).willAnswer(llamada -> {
			List<AsistenciaActividad> nuevas = llamada.getArgument(0);
			nuevas.forEach(nueva -> ReflectionTestUtils.setField(nueva, "id", 982L));
			return nuevas;
		});
		given(inscripciones.cancelarEsperaPorClaseCerrada(
				anyLong(), anyLong(), any(), anyLong(), any())).willReturn(2);

		var resultado = service.cerrar(actor(), SEDE_ID, CLASE_ID);

		assertThat(resultado.cerroAhora()).isTrue();
		assertThat(resultado.clase().estado()).isEqualTo("REALIZADA");
		assertThat(resultado.ausentados()).hasSize(1);
		assertThat(resultado.ausentados().getFirst().resultado()).isEqualTo("AUSENTE");
		assertThat(resultado.ausentados().getFirst().origen())
				.isEqualTo(OrigenAsistencia.CIERRE.name());
		assertThat(resultado.esperaCancelada()).isEqualTo(2);
		// Cancelar la cola NO libera cupo: quien espera nunca lo tuvo.
		verify(clases, never()).liberarCupo(anyLong(), anyLong());
	}

	/** Sin iniciar la clase no se toma lista: si no, {@code EN_CURSO} seria un valor decorativo. */
	@Test
	@DisplayName("Una clase que no se inicio no admite asistencia")
	void clase_no_iniciada_no_admite_asistencia() {
		given(clases.findByIdInScope(ORG_ID, SEDE_ID, CLASE_ID))
				.willReturn(Optional.of(clase(EstadoClase.PROGRAMADA, 8, 6)));

		assertThatThrownBy(() -> service.registrar(actor(), SEDE_ID, CLASE_ID, comando(
				ResultadoAsistencia.PRESENTE, null)))
				.isInstanceOf(TransicionDeClaseNoPermitidaException.class)
				.hasMessageContaining("no se inicio");
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static OperatingActor actor() {
		return new OperatingActor(CUENTA_ID, false, ORG_ID, SEDE_ID);
	}

	private static RegistrarAsistenciaCommand comando(
			ResultadoAsistencia resultado, String motivo) {

		return new RegistrarAsistenciaCommand(
				INSCRIPCION_ID, resultado, OrigenAsistencia.MOSTRADOR, null, motivo);
	}

	private static ConsultorioSnapshot sede() {
		return new ConsultorioSnapshot(SEDE_ID, ORG_ID, "Sede Centro", "UTC", true);
	}

	private static PacienteSnapshot persona() {
		return new PacienteSnapshot(PERSONA_ID, ORG_ID, "Perez", "Ana", "DNI", "30111222",
				null, true, false, null, null);
	}

	private static ClaseProgramada clase(EstadoClase estado, int capacidad, int ocupados) {
		ClaseProgramada clase = new ClaseProgramada(
				ORG_ID, SEDE_ID, OFERTA_ID, PROFESIONAL_ID, 8L, "Pilates - avanzado",
				INICIO, INICIO.plus(1, ChronoUnit.HOURS), capacidad, CUENTA_ID,
				Instant.now(), null, null);
		ReflectionTestUtils.setField(clase, "id", CLASE_ID);
		// Solo lectura desde JPA —la escribe el UPDATE condicional y nadie mas—, asi que el fixture
		// la pone por reflexion igual que lo haria la base.
		ReflectionTestUtils.setField(clase, "cupoOcupado", ocupados);
		ReflectionTestUtils.setField(clase, "estado", estado);
		return clase;
	}

	private static InscripcionClase reservada(long id) {
		InscripcionClase inscripcion = InscripcionClase.conLugar(
				ORG_ID, SEDE_ID, CLASE_ID, PERSONA_ID, CUENTA_ID, Instant.now(), null, null);
		ReflectionTestUtils.setField(inscripcion, "id", id);
		return inscripcion;
	}

	private static InscripcionClase enEspera(long id) {
		InscripcionClase inscripcion = InscripcionClase.enEspera(
				ORG_ID, SEDE_ID, CLASE_ID, PERSONA_ID, 3, CUENTA_ID, Instant.now(), null, null);
		ReflectionTestUtils.setField(inscripcion, "id", id);
		return inscripcion;
	}

	private static AsistenciaActividad yaMarcada(ResultadoAsistencia resultado) {
		AsistenciaActividad asistencia = AsistenciaActividad.registrar(
				reservada(INSCRIPCION_ID), resultado, OrigenAsistencia.MOSTRADOR, PROFESIONAL_ID,
				null, CUENTA_ID, Instant.now());
		ReflectionTestUtils.setField(asistencia, "id", 981L);
		return asistencia;
	}
}
