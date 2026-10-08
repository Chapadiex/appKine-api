package com.akine.scheduling.api;

import com.akine.platform.spi.problem.ProblemType;
import com.akine.scheduling.application.IdempotencyKeyConflictException;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.OcurrenciaSinLugarException;
import com.akine.scheduling.domain.exception.SerieNotAccessibleException;
import com.akine.scheduling.domain.exception.OfertaNoAgendableException;
import com.akine.scheduling.domain.exception.OfertaNotAccessibleException;
import com.akine.scheduling.domain.exception.PersonaNotAccessibleException;
import com.akine.scheduling.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.SlotCompletoException;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;
import com.akine.scheduling.domain.exception.TransicionDeTurnoNoPermitidaException;
import com.akine.scheduling.domain.exception.TurnoConAtencionException;
import com.akine.scheduling.domain.exception.TurnoNotAccessibleException;
import com.akine.scheduling.domain.exception.VentanaDeAgendaDemasiadoAmpliaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El contrato de errores de la agenda (M12).
 *
 * <h2>Los dos 409 que la pantalla NO puede confundir</h2>
 *
 * <p><b>{@code slot-completo} y {@code recurso-ocupado} son cosas distintas</b> y llevan a acciones
 * distintas: el primero dice que ese horario se lleno —y la pantalla ofrece otro horario—; el
 * segundo dice que el profesional o el box ya estan tomados por otra cosa, y ahi lo que corresponde
 * es revisar la agenda, no reintentar. Colapsarlos en un 409 generico deja al recepcionista
 * probando horarios a ciegas.
 *
 * <p>Los dos aparecen <b>despues</b> de que el motor de slots dijo que habia lugar, porque entre la
 * lectura y la reserva puede entrar cualquiera: el motor no promete que un slot se pueda reservar,
 * la exclusion real la da la escritura bajo lock.
 */
@DisplayName("SchedulingProblemHandler")
class SchedulingProblemHandlerTest {

	private final SchedulingProblemHandler handler = new SchedulingProblemHandler();

	@Nested
	@DisplayName("Fuera del alcance")
	class FueraDelAlcance {

		@Test
		@DisplayName("Sede, oferta, turno y persona: todas 404 con el type generico")
		void lo_inalcanzable_es_404() {
			List<ProblemDetail> respuestas = List.of(
					handler.handleConsultorioNoAccesible(new ConsultorioNoAccesibleException(7L)),
					handler.handleOfertaNoAccesible(new OfertaNotAccessibleException(42L)),
					handler.handleTurnoNoAccesible(new TurnoNotAccessibleException(301L)),
					handler.handlePersonaNoAccesible(new PersonaNotAccessibleException(128L)));

			assertThat(respuestas).allSatisfy(problem -> {
				assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
				assertThat(problem.getType()).isEqualTo(ProblemType.NOT_FOUND.uri());
				assertThat(problem.getTitle()).isNotBlank();
			});
		}
	}

	@Nested
	@DisplayName("Los dos conflictos de la reserva")
	class ConflictosDeReserva {

		@Test
		@DisplayName("El slot completo dice cuantos lugares tenia")
		void slot_completo() {
			// La pantalla necesita poder decir "ese horario se lleno" y ofrecer otro, no un 409
			// generico que deja al recepcionista probando a ciegas.
			ProblemDetail problem = handler.handleSlotCompleto(new SlotCompletoException(2));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.SLOT_COMPLETO.uri());
		}

		@Test
		@DisplayName("El recurso ocupado dice CUAL recurso, y es otro problema que el cupo")
		void recurso_ocupado() {
			// Aca no se ofrece otro horario del mismo profesional: lo que corresponde es revisar la
			// agenda, porque el que esta tomado es el recurso.
			ProblemDetail problem = handler.handleRecursoOcupado(
					new RecursoOcupadoException("PROFESIONAL"));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.RECURSO_OCUPADO.uri());
			assertThat(problem.getType())
					.as("si fuera el mismo type que slot-completo, la pantalla no podria "
							+ "distinguir dos situaciones con acciones opuestas")
					.isNotEqualTo(ProblemType.SLOT_COMPLETO.uri());
		}

		@Test
		@DisplayName("El slot que ya no existe lleva el motivo que lo explica")
		void slot_no_disponible() {
			// Entre que la pantalla dibujo la grilla y el usuario confirmo, pudo entrar un feriado,
			// cambiar un horario o desvincularse el profesional.
			ProblemDetail problem = handler.handleSlotNoDisponible(
					new SlotNoDisponibleException("el profesional ya no atiende ese dia"));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.SLOT_NO_DISPONIBLE.uri());
		}
	}

	@Nested
	@DisplayName("La agenda")
	class Agenda {

		@Test
		@DisplayName("Una oferta no agendable es 409 y no 404: existe y el usuario la esta viendo")
		void oferta_no_agendable() {
			// Un 404 mandaria a la pantalla a decir "no encontrada" sobre algo que el usuario tiene
			// delante de los ojos.
			ProblemDetail problem = handler.handleOfertaNoAgendable(
					new OfertaNoAgendableException(42L, "su vigencia no toca la ventana pedida"));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.OFERTA_NO_AGENDABLE.uri());
		}

		@Test
		@DisplayName("La ventana demasiado amplia es 400 y viaja con el maximo de dias")
		void ventana_demasiado_amplia() {
			// Sin el maximo, el cliente tiene que descubrir el tope probando.
			ProblemDetail problem = handler.handleVentanaDemasiadoAmplia(
					new VentanaDeAgendaDemasiadoAmpliaException(
							LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 31));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.VENTANA_DEMASIADO_AMPLIA.uri());
			// OJO CON EL NOMBRE: aca la propiedad se llama `maxDays`, en ingles, mientras que
			// `rango-de-reporte-invalido` de 07.06 publica la misma idea como `maximoDias`. Son dos
			// nombres para el mismo concepto en el mismo contrato, y el cliente generado los expone
			// como dos campos distintos. No se corrige aca porque cambiar cualquiera de los dos es
			// un cambio incompatible de contrato; queda fijado cual publica cada uno.
			assertThat(problem.getProperties()).containsKey("maxDays");
		}
	}

	@Nested
	@DisplayName("El ciclo del turno")
	class CicloDelTurno {

		@Test
		@DisplayName("Un turno con sesion registrada no se cancela ni se mueve")
		void turno_con_atencion() {
			// Cancelar en silencio dejaria un registro clinico —y desde 07.01 una obligacion
			// economica— colgando de una reserva que segun la agenda nunca existio.
			ProblemDetail problem = handler.handleTurnoConAtencion(
					new TurnoConAtencionException(301L));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.TURNO_CON_ATENCION.uri());
		}

		@Test
		@DisplayName("La transicion no permitida lleva el motivo, no solo el estado")
		void transicion_no_permitida() {
			ProblemDetail problem = handler.handleTransicionNoPermitida(
					new TransicionDeTurnoNoPermitidaException(301L, "ya empezo: marcalo ausente"));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType())
					.isEqualTo(ProblemType.TURNO_TRANSICION_NO_PERMITIDA.uri());
			assertThat(problem.getDetail())
					.as("el motivo es lo que le dice al recepcionista que hacer en su lugar")
					.isNotBlank();
		}

		@Test
		@DisplayName("Una persona sin perfil de paciente no reserva, y es su propio type")
		void persona_sin_perfil() {
			// Una Persona NO es un Paciente: la pantalla tiene que ofrecer crear el perfil, no
			// decir que la persona no existe.
			ProblemDetail problem = handler.handlePersonaSinPerfil(
					new PersonaSinPerfilPacienteException(128L));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType())
					.isEqualTo(ProblemType.PERSONA_SIN_PERFIL_PACIENTE.uri());
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

	@Nested
	@DisplayName("Series de turnos (E-3)")
	class Series {

		@Test
		@DisplayName("DP-21: la cantidad confirmada desactualizada sigue siendo conflict, no concurrent-modification")
		void cantidad_confirmada_desactualizada_es_conflict() {
			// No hay version vieja que recargar: hay que volver a previsualizar y decidir sobre otra
			// lista. Es el 409 de negocio que DP-21 deja afuera a proposito.
			ProblemDetail problem = handler.handleCantidadConfirmadaDesactualizada(
					new com.akine.scheduling.domain.exception.CantidadConfirmadaDesactualizadaException(
							12L, "TODA_LA_SERIE", 3, 2));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.CONFLICT.uri());
			assertThat(problem.getProperties())
					.containsEntry("afectados", 3)
					.containsEntry("confirmados", 2);
			assertThat(problem.getDetail()).contains("previsualizar");
		}

		@Test
		@DisplayName("Una serie de otro tenant es 404 con el type generico")
		void serie_inalcanzable() {
			ProblemDetail problem = handler.handleSerieNoAccesible(new SerieNotAccessibleException(12L));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.NOT_FOUND.uri());
		}

		@Test
		@DisplayName("Una ocurrencia sin lugar conserva el type de la causa y nombra el dia")
		void ocurrencia_sin_lugar() {
			Instant lunes = Instant.parse("2027-03-15T12:00:00Z");

			ProblemDetail problem = handler.handleOcurrenciaSinLugar(
					new OcurrenciaSinLugarException(lunes, new RecursoOcupadoException("profesional")));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.RECURSO_OCUPADO.uri());
			assertThat(problem.getProperties())
					.containsEntry("recurso", "profesional")
					.containsEntry("ocurrenciaInicio", "2027-03-15T12:00:00Z");
		}
	}
}
