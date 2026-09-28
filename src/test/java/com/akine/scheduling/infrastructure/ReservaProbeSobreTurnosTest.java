package com.akine.scheduling.infrastructure;

import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * La sonda que descuenta el cupo ya vendido (AKINE-05.01, implementada recien despues de 05.03).
 *
 * <p>Que un turno cancelado no cuente lo decide el {@code deleted_at IS NULL} de la consulta, no
 * esta clase: eso se verifica contra MySQL real y simularlo aca seria escribir un test que pasa
 * porque el mock devolvio lo que se le pidio. Lo que si vive aca es el agrupado por instante de
 * inicio y el filtro por recurso.
 */
@ExtendWith(MockitoExtension.class)
class ReservaProbeSobreTurnosTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long OFERTA_ID = 42L;
	private static final long PROFESIONAL_ID = 31L;
	private static final Instant NUEVE = Instant.parse("2026-09-14T12:00:00Z");
	private static final Instant DIEZ = NUEVE.plus(Duration.ofHours(1));
	private static final Instant ONCE = DIEZ.plus(Duration.ofHours(1));

	@Mock private TurnoRepositoryPort turnos;

	@Test
	@DisplayName("Dos turnos a la misma hora cuentan dos, y cada hora cuenta por separado")
	void agrupa_por_instante_de_inicio() {
		dados(turno(NUEVE, PROFESIONAL_ID, null), turno(NUEVE, 32L, null),
				turno(DIEZ, PROFESIONAL_ID, null));

		assertThat(sonda().reservasPorInicio(
				ORG_ID, CONSULTORIO_ID, OFERTA_ID, List.of(), NUEVE, ONCE))
				.isEqualTo(Map.of(NUEVE, 2, DIEZ, 1));
	}

	@Test
	@DisplayName("La lista de recursos vacia significa TODOS, no ninguno")
	void sin_filtro_cuenta_todo() {
		// Invertir esta convencion dejaria al motor sin descontar nada, que es exactamente el
		// defecto que esta clase vino a cerrar.
		dados(turno(NUEVE, PROFESIONAL_ID, null), turno(NUEVE, 32L, null));

		assertThat(sonda().reservasPorInicio(
				ORG_ID, CONSULTORIO_ID, OFERTA_ID, List.of(), NUEVE, DIEZ))
				.containsEntry(NUEVE, 2);
	}

	@Test
	@DisplayName("Con recursos pedidos solo cuentan los turnos de esos recursos")
	void filtra_por_recurso() {
		dados(turno(NUEVE, PROFESIONAL_ID, null), turno(NUEVE, 32L, null), turno(NUEVE, null, 99L));

		assertThat(sonda().reservasPorInicio(
				ORG_ID, CONSULTORIO_ID, OFERTA_ID, List.of(PROFESIONAL_ID, 99L), NUEVE, DIEZ))
				.as("el profesional pedido y el espacio pedido; el otro profesional queda afuera")
				.isEqualTo(Map.of(NUEVE, 2));
	}

	@Test
	@DisplayName("Una ventana sin turnos devuelve el mapa vacio, no null")
	void ventana_vacia() {
		dados();

		assertThat(sonda().reservasPorInicio(
				ORG_ID, CONSULTORIO_ID, OFERTA_ID, List.of(), NUEVE, DIEZ)).isEmpty();
	}

	private ReservaProbeSobreTurnos sonda() {
		return new ReservaProbeSobreTurnos(turnos);
	}

	private void dados(Turno... encontrados) {
		given(turnos.findVivosDeLaOfertaEnVentana(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(encontrados));
	}

	private static Turno turno(Instant inicio, Long profesionalId, Long espacioId) {
		return new Turno(
				ORG_ID, CONSULTORIO_ID, OFERTA_ID, 128L, profesionalId, espacioId,
				inicio, inicio.plus(Duration.ofMinutes(60)),
				7L, inicio.minus(Duration.ofDays(1)), null, null);
	}
}
