package com.akine.activity.domain;

import com.akine.activity.domain.exception.TransicionDeClaseNoPermitidaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La maquina de estados de una clase programada (M28, AKINE-08.01).
 *
 * <h2>Las dos invariantes que esta clase defiende</h2>
 *
 * <p><b>Una clase de capacidad uno es un turno individual mal rotulado.</b> Se rechaza en el
 * constructor y en la reprogramacion, no porque el numero moleste, sino porque todo lo que sigue
 * —cupo, lista de espera, asistencia por persona— deja de tener sentido y el centro termina con dos
 * modelos para la misma cosa.
 *
 * <p><b>Lo que ocurrio no se deshace.</b> Una clase realizada no se cancela: lo que corresponde es
 * corregir las asistencias, no borrar el hecho de que la gente fue.
 *
 * <h2>Por que las transiciones devuelven boolean</h2>
 *
 * <p>Repetir una transicion que ya ocurrio devuelve {@code false} en vez de fallar: el mostrador
 * toca "iniciar" dos veces y la segunda no puede ser un error. Pero intentar una transicion
 * IMPOSIBLE si lanza, porque ahi el operador esta pidiendo algo que no se puede hacer.
 */
@DisplayName("ClaseProgramada")
class ClaseProgramadaTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long OFERTA_ID = 42L;
	private static final long CUENTA = 99L;
	private static final Instant AHORA = Instant.parse("2026-10-01T12:00:00Z");
	private static final Instant EN_DOS_HORAS = AHORA.plus(Duration.ofHours(2));

	@Nested
	@DisplayName("El alta")
	class Alta {

		@Test
		@DisplayName("Una clase de capacidad uno no existe: es un turno individual mal rotulado")
		void capacidad_uno() {
			// Con capacidad uno, el cupo, la lista de espera y la asistencia por persona dejan de
			// tener sentido, y el centro termina con dos modelos para la misma cosa.
			assertThatThrownBy(() -> clase(1))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("Una clase que termina antes de empezar no se construye")
		void fin_antes_del_inicio() {
			assertThatThrownBy(() -> new ClaseProgramada(ORG_ID, CONSULTORIO_ID, OFERTA_ID, 31L,
					12L, "Pilates", EN_DOS_HORAS, AHORA, 10, CUENTA, AHORA, null, null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("La clave de idempotencia y el hash viajan juntos o no viajan")
		void idempotencia_a_medias() {
			// Una clave sin hash no puede distinguir un reintento de un pedido distinto, que es
			// justamente para lo que existe.
			assertThatThrownBy(() -> new ClaseProgramada(ORG_ID, CONSULTORIO_ID, OFERTA_ID, 31L,
					12L, "Pilates", AHORA, EN_DOS_HORAS, 10, CUENTA, AHORA, "clave-1", null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("Una clase nace PROGRAMADA, viva y sin nadie anotado")
		void nace_programada() {
			ClaseProgramada clase = clase(10);

			assertThat(clase.getEstado()).isEqualTo(EstadoClase.PROGRAMADA);
			assertThat(clase.estaViva()).isTrue();
			assertThat(clase.getCupoOcupado()).isZero();
		}
	}

	@Nested
	@DisplayName("Las transiciones")
	class Transiciones {

		@Test
		@DisplayName("Iniciar dos veces devuelve false la segunda: no es un error")
		void iniciar_dos_veces() {
			// El mostrador toca el boton dos veces y la segunda no puede romper nada.
			ClaseProgramada clase = clase(10);

			assertThat(clase.iniciar(CUENTA, AHORA)).isTrue();
			assertThat(clase.iniciar(CUENTA, AHORA)).isFalse();
			assertThat(clase.getEstado()).isEqualTo(EstadoClase.EN_CURSO);
		}

		@Test
		@DisplayName("Cerrar dos veces tambien es idempotente")
		void cerrar_dos_veces() {
			ClaseProgramada clase = clase(10);
			clase.iniciar(CUENTA, AHORA);

			assertThat(clase.cerrar(CUENTA, AHORA)).isTrue();
			assertThat(clase.cerrar(CUENTA, AHORA)).isFalse();
			assertThat(clase.getEstado()).isEqualTo(EstadoClase.REALIZADA);
		}

		@Test
		@DisplayName("Una clase cancelada no tiene operacion que cerrar")
		void cerrar_una_cancelada() {
			ClaseProgramada clase = clase(10);
			clase.cancelar("El profesional se enfermo", CUENTA, AHORA);

			assertThatThrownBy(() -> clase.cerrar(CUENTA, AHORA))
					.isInstanceOf(TransicionDeClaseNoPermitidaException.class);
		}

		@Test
		@DisplayName("Una clase realizada NO se cancela: lo que ocurrio no se deshace")
		void cancelar_una_realizada() {
			// Lo que corresponde es corregir las asistencias, no borrar el hecho de que la gente
			// fue.
			ClaseProgramada clase = clase(10);
			clase.iniciar(CUENTA, AHORA);
			clase.cerrar(CUENTA, AHORA);

			assertThatThrownBy(() -> clase.cancelar("Me equivoque", CUENTA, AHORA))
					.isInstanceOf(TransicionDeClaseNoPermitidaException.class);
		}

		@Test
		@DisplayName("Cancelar SIN motivo no entra (RN-M28-009)")
		void cancelar_sin_motivo() {
			// Sin motivo, -se cancelo- es indistinguible de -alguien se equivoco-, y el alumno que
			// pregunta por que no tiene respuesta.
			ClaseProgramada clase = clase(10);

			assertThatThrownBy(() -> clase.cancelar("   ", CUENTA, AHORA))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("Cancelar deja la fila con su motivo y la saca de las consultas vivas")
		void cancelar_es_baja_logica() {
			ClaseProgramada clase = clase(10);

			assertThat(clase.cancelar("El profesional se enfermo", CUENTA, AHORA)).isTrue();
			assertThat(clase.getEstado()).isEqualTo(EstadoClase.CANCELADA);
			assertThat(clase.estaViva())
					.as("baja logica: la fila sigue, con su motivo")
					.isFalse();
			assertThat(clase.cancelar("De nuevo", CUENTA, AHORA))
					.as("cancelar lo cancelado es idempotente, no un error")
					.isFalse();
		}

		@Test
		@DisplayName("Solo una PROGRAMADA se inicia")
		void iniciar_una_cancelada() {
			ClaseProgramada clase = clase(10);
			clase.cancelar("El profesional se enfermo", CUENTA, AHORA);

			assertThatThrownBy(() -> clase.iniciar(CUENTA, AHORA))
					.isInstanceOf(TransicionDeClaseNoPermitidaException.class);
		}
	}

	@Nested
	@DisplayName("La reprogramacion")
	class Reprogramacion {

		@Test
		@DisplayName("Mueve horario, recursos y capacidad de una sola vez")
		void reprograma_todo_junto() {
			ClaseProgramada clase = claseQueEmpiezaEn(EN_DOS_HORAS);

			clase.reprogramar(AHORA.plus(Duration.ofDays(1)),
					AHORA.plus(Duration.ofDays(1)).plus(Duration.ofHours(1)), 32L, 13L, 15, AHORA);

			assertThat(clase.getCapacidad()).isEqualTo(15);
			assertThat(clase.getEspacioId()).isEqualTo(13L);
		}

		@Test
		@DisplayName("Una clase que YA empezo no se mueve")
		void ya_empezo() {
			// Mover una clase en curso dejaria a la gente que ya esta adentro en un horario que no
			// existe.
			ClaseProgramada clase = claseQueEmpiezaEn(AHORA.minus(Duration.ofMinutes(30)));

			assertThatThrownBy(() -> clase.reprogramar(AHORA.plus(Duration.ofDays(1)),
					AHORA.plus(Duration.ofDays(1)).plus(Duration.ofHours(1)), null, null, 10,
					AHORA))
					.isInstanceOf(TransicionDeClaseNoPermitidaException.class);
		}

		@Test
		@DisplayName("El horario nuevo no puede estar en el pasado")
		void destino_en_el_pasado() {
			ClaseProgramada clase = claseQueEmpiezaEn(EN_DOS_HORAS);

			assertThatThrownBy(() -> clase.reprogramar(AHORA.minus(Duration.ofHours(1)),
					AHORA, null, null, 10, AHORA))
					.isInstanceOf(TransicionDeClaseNoPermitidaException.class);
		}

		@Test
		@DisplayName("Reprogramar a capacidad uno tampoco entra")
		void reprogramar_a_capacidad_uno() {
			// La invariante vale en toda la vida de la clase, no solo al crearla.
			ClaseProgramada clase = claseQueEmpiezaEn(EN_DOS_HORAS);

			assertThatThrownBy(() -> clase.reprogramar(AHORA.plus(Duration.ofDays(1)),
					AHORA.plus(Duration.ofDays(1)).plus(Duration.ofHours(1)), null, null, 1, AHORA))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("Una clase cancelada no se reprograma")
		void reprogramar_una_cancelada() {
			ClaseProgramada clase = claseQueEmpiezaEn(EN_DOS_HORAS);
			clase.cancelar("El profesional se enfermo", CUENTA, AHORA);

			assertThatThrownBy(() -> clase.reprogramar(AHORA.plus(Duration.ofDays(1)),
					AHORA.plus(Duration.ofDays(1)).plus(Duration.ofHours(1)), null, null, 10,
					AHORA))
					.isInstanceOf(TransicionDeClaseNoPermitidaException.class);
		}
	}

	@Nested
	@DisplayName("El cruce de horarios")
	class Cruce {

		@Test
		@DisplayName("Dos clases que comparten un minuto se cruzan")
		void se_cruzan() {
			ClaseProgramada clase = claseQueEmpiezaEn(AHORA);

			assertThat(clase.seCruzaCon(AHORA.plus(Duration.ofMinutes(30)),
					AHORA.plus(Duration.ofHours(3)))).isTrue();
		}

		@Test
		@DisplayName("El fin de una y el inicio de la otra NO se cruzan: el borde es exclusivo")
		void el_borde_no_cruza() {
			// Sin esto, dos clases consecutivas en el mismo box se rechazarian entre si y la grilla
			// de la tarde quedaria con un hueco por cada clase.
			ClaseProgramada clase = claseQueEmpiezaEn(AHORA);

			assertThat(clase.seCruzaCon(EN_DOS_HORAS, EN_DOS_HORAS.plus(Duration.ofHours(1))))
					.isFalse();
		}

		@Test
		@DisplayName("Una clase entera antes de la otra no se cruza")
		void sin_cruce() {
			ClaseProgramada clase = claseQueEmpiezaEn(AHORA);

			assertThatCode(() -> assertThat(clase.seCruzaCon(
					AHORA.minus(Duration.ofHours(3)), AHORA.minus(Duration.ofHours(1)))).isFalse())
					.doesNotThrowAnyException();
		}
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static ClaseProgramada clase(int capacidad) {
		return conId(new ClaseProgramada(ORG_ID, CONSULTORIO_ID, OFERTA_ID, 31L, 12L, "Pilates",
				AHORA, EN_DOS_HORAS, capacidad, CUENTA, AHORA, null, null));
	}

	private static ClaseProgramada claseQueEmpiezaEn(Instant inicio) {
		return conId(new ClaseProgramada(ORG_ID, CONSULTORIO_ID, OFERTA_ID, 31L, 12L, "Pilates",
				inicio, inicio.plus(Duration.ofHours(2)), 10, CUENTA, AHORA, null, null));
	}

	private static ClaseProgramada conId(ClaseProgramada clase) {
		ReflectionTestUtils.setField(clase, "id", 80L);
		return clase;
	}
}
