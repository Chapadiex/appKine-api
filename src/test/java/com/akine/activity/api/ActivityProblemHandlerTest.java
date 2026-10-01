package com.akine.activity.api;

import com.akine.activity.application.IdempotencyKeyConflictException;
import com.akine.activity.domain.exception.AsistenciaNotAccessibleException;
import com.akine.activity.domain.exception.CapacidadNoAdmitidaException;
import com.akine.activity.domain.exception.ClaseCompletaException;
import com.akine.activity.domain.exception.ClaseNoProgramableException;
import com.akine.activity.domain.exception.ClaseNotAccessibleException;
import com.akine.activity.domain.exception.ConsultorioNoAccesibleException;
import com.akine.activity.domain.exception.HorarioNoDisponibleException;
import com.akine.activity.domain.exception.InscripcionDuplicadaException;
import com.akine.activity.domain.exception.InscripcionNotAccessibleException;
import com.akine.activity.domain.exception.OfertaNotAccessibleException;
import com.akine.activity.domain.exception.PersonaNoAccesibleException;
import com.akine.activity.domain.exception.RecursoOcupadoException;
import com.akine.activity.domain.exception.TransicionDeClaseNoPermitidaException;
import com.akine.activity.domain.exception.TransicionDeInscripcionNoPermitidaException;
import com.akine.platform.spi.problem.ProblemType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El contrato de errores de {@code activity} (M28/M29, la segunda entrega).
 *
 * <h2>La regla que distingue a este modulo</h2>
 *
 * <p>Una clase grupal <b>no es un turno</b>, y su contrato de errores lo refleja en un lugar
 * concreto: <b>la clase completa lleva la capacidad efectiva y los ocupados</b>. Un turno individual
 * esta o no esta; una clase tiene cupo, y una pantalla que solo diga "no hay lugar" no puede ofrecer
 * la lista de espera ni explicar por que ayer habia dos lugares y hoy ninguno.
 *
 * <p>Y la inscripcion duplicada lleva el id de la que ya existe, por lo mismo que la caja ya abierta
 * lleva el de la jornada: sin el, el mostrador no puede llevar al operador a la inscripcion que
 * tiene delante.
 */
@DisplayName("ActivityProblemHandler")
class ActivityProblemHandlerTest {

	private final ActivityProblemHandler handler = new ActivityProblemHandler();

	@Nested
	@DisplayName("Fuera del alcance")
	class FueraDelAlcance {

		@Test
		@DisplayName("Sede, oferta, clase, inscripcion, persona y asistencia: todas 404")
		void lo_inalcanzable_es_404() {
			// Un 403 confirmaria que ese id existe, y en M28 eso ademas filtraria quien se anoto a
			// que: la inscripcion de una persona a una clase es dato de salud por asociacion.
			List<ProblemDetail> respuestas = List.of(
					handler.handleConsultorioNoAccesible(new ConsultorioNoAccesibleException(7L)),
					handler.handleOfertaNoAccesible(new OfertaNotAccessibleException(42L)),
					handler.handleClaseNoAccesible(new ClaseNotAccessibleException(80L)),
					handler.handleInscripcionNoAccesible(
							new InscripcionNotAccessibleException(90L)),
					handler.handlePersonaNoAccesible(new PersonaNoAccesibleException(128L)),
					handler.handleAsistenciaNoAccesible(new AsistenciaNotAccessibleException(95L)));

			assertThat(respuestas).allSatisfy(problem -> {
				assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
				assertThat(problem.getType()).isEqualTo(ProblemType.NOT_FOUND.uri());
				assertThat(problem.getTitle()).isNotBlank();
			});
		}
	}

	@Nested
	@DisplayName("El cupo")
	class Cupo {

		@Test
		@DisplayName("La clase completa dice CUANTOS lugares hay y cuantos estan ocupados")
		void clase_completa() {
			// Es lo que distingue una clase de un turno: sin esos numeros la pantalla no puede
			// ofrecer lista de espera ni explicar por que ayer habia lugar y hoy no.
			ProblemDetail problem = handler.handleClaseCompleta(
					new ClaseCompletaException(80L, 12, 12));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.CLASE_COMPLETA.uri());
			assertThat(problem.getProperties())
					.containsKeys("capacidadEfectiva", "ocupados");
		}

		@Test
		@DisplayName("Una capacidad que el espacio no admite dice cual es el maximo")
		void capacidad_no_admitida() {
			// Programar veinte personas en un box de ocho no es un error de permiso ni de estado:
			// es un dato que no entra, y el maximo es lo unico que permite corregirlo de una vez.
			ProblemDetail problem = handler.handleCapacidad(
					new CapacidadNoAdmitidaException(20, 8, "el espacio admite 8"));

			assertThat(problem.getType()).isEqualTo(ProblemType.CLASE_CAPACIDAD_NO_ADMITIDA.uri());
			assertThat(problem.getProperties()).containsKey("capacidadMaxima");
		}
	}

	@Nested
	@DisplayName("La programacion")
	class Programacion {

		@Test
		@DisplayName("Una oferta que no se puede programar como clase lleva el motivo")
		void no_programable() {
			ProblemDetail problem = handler.handleNoProgramable(
					new ClaseNoProgramableException(42L, "la oferta no es grupal"));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.CLASE_NO_PROGRAMABLE.uri());
		}

		@Test
		@DisplayName("El recurso ocupado dice CUAL: profesional o espacio")
		void recurso_ocupado() {
			// Con "hay un conflicto" a secas, quien arma la grilla tiene que probar combinaciones
			// hasta adivinar cual de los dos recursos es el que choca.
			ProblemDetail problem = handler.handleRecursoOcupado(
					new RecursoOcupadoException("ESPACIO"));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.RECURSO_OCUPADO.uri());
		}

		@Test
		@DisplayName("Un horario fuera de la disponibilidad lleva el motivo que lo explica")
		void horario_no_disponible() {
			ProblemDetail problem = handler.handleHorarioNoDisponible(
					new HorarioNoDisponibleException("el profesional no atiende los domingos"));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.SLOT_NO_DISPONIBLE.uri());
		}
	}

	@Nested
	@DisplayName("Las transiciones y la idempotencia")
	class TransicionesEIdempotencia {

		@Test
		@DisplayName("Las dos maquinas de estados tienen su type propio y no uno compartido")
		void transiciones_separadas() {
			// Clase e inscripcion se mueven por su cuenta: cancelar una clase no es lo mismo que
			// dar de baja a una persona, y la pantalla tiene que poder hablar de la correcta.
			assertThat(handler.handleTransicion(new TransicionDeClaseNoPermitidaException(
					80L, "ya esta dictada")).getType())
					.isEqualTo(ProblemType.CLASE_TRANSICION_NO_PERMITIDA.uri());
			assertThat(handler.handleTransicionDeInscripcion(
					new TransicionDeInscripcionNoPermitidaException(90L, "ya esta de baja"))
					.getType())
					.isEqualTo(ProblemType.INSCRIPCION_TRANSICION_NO_PERMITIDA.uri());
		}

		@Test
		@DisplayName("La inscripcion duplicada lleva el id de la que ya existe")
		void inscripcion_duplicada() {
			// Sin ese id, el mostrador no puede llevar al operador a la inscripcion que ya tiene
			// delante y termina creando otra persona para esquivar el error.
			ProblemDetail problem = handler.handleInscripcionDuplicada(
					new InscripcionDuplicadaException(80L, 128L, 90L));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getProperties()).containsKey("inscripcionExistenteId");
		}

		@Test
		@DisplayName("La clave de idempotencia reusada con otro cuerpo es 409 con type propio")
		void idempotency_conflict() {
			ProblemDetail problem = handler.handleIdempotencyConflict(
					new IdempotencyKeyConflictException("clave-1"));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.IDEMPOTENCY_KEY_CONFLICT.uri());
		}
	}
}
