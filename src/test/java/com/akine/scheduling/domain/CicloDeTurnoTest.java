package com.akine.scheduling.domain;

import com.akine.scheduling.domain.exception.TransicionDeTurnoNoPermitidaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La maquina de estados del Turno (AKINE-05.03).
 *
 * <p>Solo reglas de negocio: que libera el lugar y que no, que se puede hacer sobre el pasado y que
 * sobre el futuro, y que la reprogramacion conserva identidad. Nada de codigos HTTP ni de
 * validaciones de forma —eso lo decide la capa web y probarlo aca seria probar Spring—.
 */
class CicloDeTurnoTest {

	private static final Instant AHORA = Instant.parse("2026-09-15T12:00:00Z");
	private static final Instant EN_UNA_HORA = AHORA.plus(Duration.ofHours(1));

	@Nested
	@DisplayName("Cancelacion (RF-M12-004, RN-M12-002)")
	class Cancelacion {

		@Test
		@DisplayName("cancelar conserva la fila y libera el lugar")
		void cancelar_conserva_la_fila_y_libera_el_lugar() {
			Turno turno = turnoQueEmpiezaEn(EN_UNA_HORA);

			turno.cancelar("El paciente aviso", 7L, AHORA);

			assertThat(turno.getEstado()).isEqualTo(EstadoTurno.CANCELADO);
			assertThat(turno.getMotivoCancelacion()).isEqualTo("El paciente aviso");
			assertThat(turno.getCanceladoPorCuentaId()).isEqualTo(7L);
			assertThat(turno.estaVivo())
					.as("un turno cancelado deja de ocupar lugar: es lo que revisan las consultas "
							+ "de solapamiento")
					.isFalse();
			assertThat(turno.getInicio())
					.as("y su horario sigue ahi: la fila conserva lo que fue, no se vacia")
					.isEqualTo(EN_UNA_HORA);
		}

		@Test
		@DisplayName("un turno que ya empezo no se cancela: se marca ausente")
		void el_pasado_es_inalterable() {
			Turno turno = turnoQueEmpiezaEn(AHORA.minus(Duration.ofMinutes(30)));

			assertThatThrownBy(() -> turno.cancelar("tarde", 7L, AHORA))
					.isInstanceOf(TransicionDeTurnoNoPermitidaException.class)
					.hasMessageContaining("ausente");

			assertThat(turno.estaVivo()).isTrue();
			assertThat(turno.getEstado()).isEqualTo(EstadoTurno.RESERVADO);
		}

		@Test
		@DisplayName("cancelar dos veces no es idempotente: el lugar pudo tomarlo otro")
		void cancelar_dos_veces_falla() {
			Turno turno = turnoQueEmpiezaEn(EN_UNA_HORA);
			turno.cancelar("primero", 7L, AHORA);

			assertThatThrownBy(() -> turno.cancelar("segundo", 8L, AHORA))
					.isInstanceOf(TransicionDeTurnoNoPermitidaException.class);

			assertThat(turno.getMotivoCancelacion())
					.as("y el motivo del primero es el que queda")
					.isEqualTo("primero");
		}

		@Test
		@DisplayName("el motivo es obligatorio (DP-04)")
		void sin_motivo_no_se_cancela() {
			Turno turno = turnoQueEmpiezaEn(EN_UNA_HORA);

			assertThatThrownBy(() -> turno.cancelar("   ", 7L, AHORA))
					.isInstanceOf(IllegalArgumentException.class);

			assertThat(turno.estaVivo()).isTrue();
		}
	}

	@Nested
	@DisplayName("Ausencia (RF-M12-007, DP-04)")
	class Ausencia {

		@Test
		@DisplayName("la ausencia NO libera el lugar: la hora se consumio igual")
		void la_ausencia_no_libera_el_lugar() {
			Turno turno = turnoQueEmpiezaEn(AHORA.minus(Duration.ofHours(1)));

			turno.marcarAusente(AHORA);

			assertThat(turno.getEstado()).isEqualTo(EstadoTurno.AUSENTE);
			assertThat(turno.getAusenteEn()).isEqualTo(AHORA);
			assertThat(turno.estaVivo())
					.as("sigue ocupando el slot, a diferencia de un cancelado")
					.isTrue();
			assertThat(turno.getDeletedAt()).isNull();
		}

		@Test
		@DisplayName("no se registra ausencia antes de la hora del turno")
		void no_hay_ausencia_anticipada() {
			Turno turno = turnoQueEmpiezaEn(EN_UNA_HORA);

			assertThatThrownBy(() -> turno.marcarAusente(AHORA))
					.isInstanceOf(TransicionDeTurnoNoPermitidaException.class)
					.hasMessageContaining("todavia no empezo");
		}

		@Test
		@DisplayName("un turno cancelado ya no admite ausencia")
		void cancelado_no_admite_ausencia() {
			Turno turno = turnoQueEmpiezaEn(EN_UNA_HORA);
			turno.cancelar("se dio de baja", 7L, AHORA);

			assertThatThrownBy(() -> turno.marcarAusente(EN_UNA_HORA.plusSeconds(60)))
					.isInstanceOf(TransicionDeTurnoNoPermitidaException.class);
		}
	}

	@Nested
	@DisplayName("Reprogramacion (RF-M12-005, RN-M12-003)")
	class Reprogramacion {

		@Test
		@DisplayName("mover el turno conserva su identidad y su paciente")
		void mover_conserva_identidad() {
			Turno turno = turnoQueEmpiezaEn(EN_UNA_HORA);
			Instant destino = EN_UNA_HORA.plus(Duration.ofDays(7));

			turno.reprogramar(destino, destino.plus(Duration.ofMinutes(45)), 99L, 5L, AHORA);

			assertThat(turno.getPersonaId()).isEqualTo(128L);
			assertThat(turno.getInicio()).isEqualTo(destino);
			assertThat(turno.getProfesionalMembershipId())
					.as("el profesional puede cambiar: mover un turno porque el profesional "
							+ "se ausento es el caso mas frecuente")
					.isEqualTo(99L);
			assertThat(turno.getReprogramadoEn()).isEqualTo(AHORA);
			assertThat(turno.estaVivo())
					.as("no se cancela y se crea otro: es el MISMO turno (DP-04)")
					.isTrue();
		}

		@Test
		@DisplayName("un turno confirmado vuelve a RESERVADO: lo confirmado era otro horario")
		void mover_un_confirmado_lo_devuelve_a_reservado() {
			Turno turno = turnoQueEmpiezaEn(EN_UNA_HORA);
			turno.confirmar(AHORA);
			Instant destino = EN_UNA_HORA.plus(Duration.ofDays(1));

			turno.reprogramar(destino, destino.plus(Duration.ofMinutes(45)), 31L, null, AHORA);

			assertThat(turno.getEstado()).isEqualTo(EstadoTurno.RESERVADO);
			assertThat(turno.getConfirmadoEn()).isNull();
		}

		@Test
		@DisplayName("no se mueve un turno hacia el pasado")
		void no_se_mueve_al_pasado() {
			Turno turno = turnoQueEmpiezaEn(EN_UNA_HORA);
			Instant ayer = AHORA.minus(Duration.ofDays(1));

			assertThatThrownBy(() ->
					turno.reprogramar(ayer, ayer.plus(Duration.ofMinutes(45)), 31L, null, AHORA))
					.isInstanceOf(TransicionDeTurnoNoPermitidaException.class)
					.hasMessageContaining("pasado");
		}

		@Test
		@DisplayName("un turno ausente no se mueve: su ciclo termino")
		void un_ausente_no_se_mueve() {
			Turno turno = turnoQueEmpiezaEn(AHORA.minus(Duration.ofHours(2)));
			turno.marcarAusente(AHORA);
			Instant destino = AHORA.plus(Duration.ofDays(1));

			assertThatThrownBy(() ->
					turno.reprogramar(destino, destino.plus(Duration.ofMinutes(45)), 31L, null, AHORA))
					.isInstanceOf(TransicionDeTurnoNoPermitidaException.class);
		}
	}

	@Nested
	@DisplayName("Lo que un turno no admite")
	class Invariantes {

		@Test
		@DisplayName("no se crea con el fin antes del inicio, ni con clave de idempotencia sin hash")
		void al_crearlo() {
			assertThatThrownBy(() -> new Turno(1L, 7L, 42L, 128L, 31L, null, EN_UNA_HORA, EN_UNA_HORA,
					7L, AHORA, null, null)).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new Turno(1L, 7L, 42L, 128L, 31L, null, EN_UNA_HORA, EN_UNA_HORA.plusSeconds(60),
					7L, AHORA, "clave", null)).isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("un turno que ya empezo no se mueve, y el horario nuevo tiene que terminar despues de empezar")
		void al_reprogramar() {
			Turno empezado = turnoQueEmpiezaEn(AHORA.minus(Duration.ofMinutes(30)));
			assertThatThrownBy(() -> empezado.reprogramar(EN_UNA_HORA, EN_UNA_HORA.plusSeconds(60), 31L, null, AHORA))
					.isInstanceOf(TransicionDeTurnoNoPermitidaException.class)
					.hasMessageContaining("ya empezo");

			Turno futuro = turnoQueEmpiezaEn(EN_UNA_HORA);
			assertThatThrownBy(() -> futuro.reprogramar(EN_UNA_HORA, EN_UNA_HORA, 31L, null, AHORA))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("reprogramar limpia la marca de sobreturno: en el horario nuevo no hay nada que autorizar")
		void reprogramar_limpia_el_sobreturno() {
			Turno turno = turnoQueEmpiezaEn(EN_UNA_HORA);
			turno.autorizarSobreturno(" Urgencia ", 7L);
			assertThat(turno.getSobreturnoMotivo()).isEqualTo("Urgencia");

			turno.reprogramar(EN_UNA_HORA.plusSeconds(3600), EN_UNA_HORA.plusSeconds(7200), 31L, null, AHORA);

			assertThat(turno.isSobreturno()).isFalse();
			assertThat(turno.getSobreturnoMotivo()).isNull();
		}

		@Test
		@DisplayName("serie, cobertura, origen y sobreturno se fijan al reservar y no despues")
		void lo_que_se_fija_al_reservar() {
			Turno persistido = turnoQueEmpiezaEn(EN_UNA_HORA);
			ReflectionTestUtils.setField(persistido, "id", 301L);

			assertThatThrownBy(() -> persistido.vincularASerie(5L)).isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> persistido.elegirCobertura(5L)).isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> persistido.declararOrigen(OrigenDeTurno.PLAN)).isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> persistido.autorizarSobreturno("Urgencia", 7L)).isInstanceOf(IllegalStateException.class);

			Turno nuevo = turnoQueEmpiezaEn(EN_UNA_HORA);
			nuevo.vincularASerie(5L);
			nuevo.elegirCobertura(6L);
			assertThatThrownBy(() -> nuevo.vincularASerie(5L)).isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> nuevo.elegirCobertura(6L)).isInstanceOf(IllegalStateException.class);
		}

		@Test
		@DisplayName("un sobreturno sin motivo no se autoriza")
		void sobreturno_sin_motivo() {
			assertThatThrownBy(() -> turnoQueEmpiezaEn(EN_UNA_HORA).autorizarSobreturno("  ", 7L))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> turnoQueEmpiezaEn(EN_UNA_HORA).autorizarSobreturno(null, 7L))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("un turno cerrado no cambia de caso ni de plan, y confirmar uno cerrado no lo revive")
		void un_turno_cerrado() {
			Turno cancelado = turnoQueEmpiezaEn(EN_UNA_HORA);
			cancelado.cancelar("No puede venir", OrigenDeCancelacion.PACIENTE, 7L, AHORA);

			assertThat(cancelado.getOrigenCancelacion()).isEqualTo(OrigenDeCancelacion.PACIENTE);
			assertThatThrownBy(() -> cancelado.asociarContexto(9L, 57L)).isInstanceOf(TransicionDeTurnoNoPermitidaException.class);
			assertThatThrownBy(() -> cancelado.confirmar(AHORA)).isInstanceOf(TransicionDeTurnoNoPermitidaException.class);
			assertThat(cancelado.getEstado()).isEqualTo(EstadoTurno.CANCELADO);
		}

		@Test
		@DisplayName("una ausencia consume sesion solo si el turno tiene plan: sin plan no hay nada que consumir")
		void ausencia_que_consume() {
			Turno sinPlan = turnoQueEmpiezaEn(AHORA.minus(Duration.ofMinutes(5)));
			sinPlan.marcarAusente(true, AHORA);
			assertThat(sinPlan.getAusenciaConsumeSesion()).isNull();

			Turno conPlan = turnoQueEmpiezaEn(AHORA.minus(Duration.ofMinutes(5)));
			conPlan.asociarContexto(9L, 57L);
			conPlan.marcarAusente(true, AHORA);
			assertThat(conPlan.getAusenciaConsumeSesion()).isTrue();
		}

		@Test
		@DisplayName("se cruza con un intervalo solo si se solapan: los extremos superiores son exclusivos")
		void solapamiento() {
			Turno turno = turnoQueEmpiezaEn(EN_UNA_HORA);

			assertThat(turno.seCruzaCon(EN_UNA_HORA, EN_UNA_HORA.plusSeconds(1))).isTrue();
			assertThat(turno.seCruzaCon(EN_UNA_HORA.minusSeconds(60), EN_UNA_HORA)).isFalse();
			assertThat(turno.seCruzaCon(turno.getFin(), turno.getFin().plusSeconds(60))).isFalse();
		}
	}

	private static Turno turnoQueEmpiezaEn(Instant inicio) {
		return new Turno(
				1L, 7L, 42L, 128L, 31L, null,
				inicio, inicio.plus(Duration.ofMinutes(45)),
				7L, AHORA.minus(Duration.ofDays(1)), null, null);
	}
}
