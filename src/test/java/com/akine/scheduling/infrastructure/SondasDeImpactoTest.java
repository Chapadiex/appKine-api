package com.akine.scheduling.infrastructure;

import com.akine.organization.spi.ColaboradorDesvinculacionProbe;
import com.akine.organization.spi.ConsultorioDeactivationProbe;
import com.akine.resource.spi.DisponibilidadImpactProbe;
import com.akine.resource.spi.EspacioOccupancyProbe;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Las cuatro sondas de impacto de F2 implementadas sobre {@code turno} (paquete E-1).
 *
 * <p>Que un cancelado, un ausente o un turno de otro tenant no cuenten lo decide el predicado de
 * las consultas, no estas clases: eso se verifica contra MySQL real en {@code SondasDeImpactoIT}.
 * Simularlo aca seria un test que pasa porque el mock devolvio lo que se le pidio. Lo que vive aca
 * es lo que las clases calculan: el pico, el primero, la forma de "nada que reportar".
 */
@ExtendWith(MockitoExtension.class)
class SondasDeImpactoTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long ESPACIO_ID = 12L;
	private static final long MEMBERSHIP_ID = 31L;
	private static final Instant AHORA = Instant.parse("2026-10-06T12:00:00Z");
	private static final Instant NUEVE = Instant.parse("2026-10-12T12:00:00Z");
	private static final Instant DIEZ = NUEVE.plus(Duration.ofHours(1));
	private static final Instant ONCE = DIEZ.plus(Duration.ofHours(1));

	@Mock private TurnoRepositoryPort turnos;

	@Nested
	@DisplayName("Sede")
	class Sede {

		@Test
		@DisplayName("Con turnos pendientes bloquea y declara cuantos, con vocabulario estable")
		void con_turnos_bloquea() {
			given(turnos.contarPendientesDeLaSede(ORG_ID, CONSULTORIO_ID, AHORA)).willReturn(3L);

			ConsultorioDeactivationProbe.ActiveReferences referencias =
					new SedeConTurnosPendientes(turnos).activeReferencesOn(ORG_ID, CONSULTORIO_ID, AHORA);

			assertThat(referencias.bloquean()).isTrue();
			assertThat(referencias).isEqualTo(
					new ConsultorioDeactivationProbe.ActiveReferences("turnos-futuros", 3L));
		}

		@Test
		@DisplayName("Sin turnos pendientes no objeta nada")
		void sin_turnos_no_bloquea() {
			given(turnos.contarPendientesDeLaSede(ORG_ID, CONSULTORIO_ID, AHORA)).willReturn(0L);

			assertThat(new SedeConTurnosPendientes(turnos)
					.activeReferencesOn(ORG_ID, CONSULTORIO_ID, AHORA))
					.isEqualTo(ConsultorioDeactivationProbe.ActiveReferences.ninguna());
		}
	}

	@Nested
	@DisplayName("Espacio")
	class Espacio {

		@Test
		@DisplayName("El pico es de turnos SIMULTANEOS: dos a las 9 y uno a las 11 dan 2, no 3")
		void pico_y_no_total() {
			dados(turno(NUEVE), turno(NUEVE), turno(ONCE));

			assertThat(sonda()).isEqualTo(new EspacioOccupancyProbe.Occupancy("turnos-futuros", 2L));
		}

		@Test
		@DisplayName("Dos turnos consecutivos no se cruzan: el fin es exclusivo")
		void consecutivos_ocupan_un_lugar() {
			// 9 a 10 y 10 a 11. Contarlos como dos rechazaria reducir a capacidad 1 un box que
			// nunca tiene mas de una persona adentro.
			dados(turno(DIEZ), turno(NUEVE));

			assertThat(sonda().peak()).isEqualTo(1L);
		}

		@Test
		@DisplayName("Turnos que se pisan parcialmente suman en el tramo comun")
		void solapamiento_parcial() {
			dados(turno(NUEVE), turnoDe(NUEVE.plus(Duration.ofMinutes(30)), Duration.ofMinutes(60)),
					turnoDe(NUEVE.plus(Duration.ofMinutes(45)), Duration.ofMinutes(5)));

			assertThat(sonda().peak()).isEqualTo(3L);
		}

		@Test
		@DisplayName("Sin turnos pendientes, ninguna ocupacion")
		void sin_turnos() {
			dados();

			assertThat(sonda()).isEqualTo(EspacioOccupancyProbe.Occupancy.ninguna());
		}

		private EspacioOccupancyProbe.Occupancy sonda() {
			return new EspacioOcupadoPorTurnos(turnos).peakOccupancyFrom(ORG_ID, ESPACIO_ID, AHORA);
		}

		private void dados(Turno... encontrados) {
			given(turnos.findPendientesDelEspacio(ORG_ID, ESPACIO_ID, AHORA))
					.willReturn(List.of(encontrados));
		}
	}

	@Nested
	@DisplayName("Colaborador")
	class Colaborador {

		@Test
		@DisplayName("Informa cuantos turnos quedan y desde el mas temprano, sin importar el orden")
		void informa_cuantos_y_desde_cuando() {
			given(turnos.findPendientesDelProfesional(ORG_ID, MEMBERSHIP_ID, AHORA))
					.willReturn(List.of(turno(ONCE), turno(NUEVE)));

			assertThat(new ProfesionalConTurnosPendientes(turnos)
					.pendingWorkOn(ORG_ID, MEMBERSHIP_ID, 999L, AHORA))
					.isEqualTo(new ColaboradorDesvinculacionProbe.Impacto("turnos", 2L, NUEVE));
		}

		@Test
		@DisplayName("Sin turnos pendientes responde ninguno, para que la siguiente sonda hable")
		void sin_turnos() {
			given(turnos.findPendientesDelProfesional(ORG_ID, MEMBERSHIP_ID, AHORA))
					.willReturn(List.of());

			assertThat(new ProfesionalConTurnosPendientes(turnos)
					.pendingWorkOn(ORG_ID, MEMBERSHIP_ID, 999L, AHORA).hayAlgo()).isFalse();
		}
	}

	@Nested
	@DisplayName("Disponibilidad")
	class Disponibilidad {

		@Test
		@DisplayName("Cuenta los turnos de la ventana y reporta el primero")
		void cuenta_la_ventana() {
			Instant hasta = AHORA.plus(Duration.ofDays(90));
			given(turnos.findPendientesDelProfesionalEnLaSede(
					ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID, AHORA, hasta))
					.willReturn(List.of(turno(DIEZ), turno(NUEVE)));

			assertThat(new DisponibilidadImpactoSobreTurnos(turnos)
					.turnosEn(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID, AHORA, hasta))
					.isEqualTo(new DisponibilidadImpactProbe.Impacto(2L, NUEVE));
		}

		@Test
		@DisplayName("Una ventana vacia o invertida no consulta la base")
		void ventana_invertida() {
			assertThat(new DisponibilidadImpactoSobreTurnos(turnos)
					.turnosEn(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID, AHORA, AHORA))
					.isEqualTo(DisponibilidadImpactProbe.Impacto.ninguno());
			verifyNoInteractions(turnos);
		}
	}

	private static Turno turno(Instant inicio) {
		return turnoDe(inicio, Duration.ofMinutes(60));
	}

	private static Turno turnoDe(Instant inicio, Duration duracion) {
		return new Turno(ORG_ID, CONSULTORIO_ID, 42L, 128L, MEMBERSHIP_ID, ESPACIO_ID,
				inicio, inicio.plus(duracion), 7L, inicio.minus(Duration.ofDays(1)), null, null);
	}
}
