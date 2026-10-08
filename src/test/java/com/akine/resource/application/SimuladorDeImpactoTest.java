package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.MotivoExcepcion;
import com.akine.resource.domain.TipoExcepcion;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.BloqueDisponibilidadRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.DisponibilidadExcepcionRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.FeriadoRepositoryPort;
import com.akine.resource.spi.DisponibilidadImpactProbe;
import com.akine.resource.spi.DisponibilidadImpactProbe.TurnoPendiente;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * El calculo exacto del impacto (A-11): el que reemplaza la cota superior de E-1.
 *
 * <p>Escenario unico: un profesional con dos bloques —martes y jueves de 9 a 12— y tres turnos
 * pendientes: martes 10 h, jueves 10 h y un sobreturno el martes a las 15 h, fuera de todo bloque.
 * Con la cota de E-1, dar de baja el martes informaba tres; el numero correcto es uno.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SimuladorDeImpacto")
class SimuladorDeImpactoTest {

	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PROFESIONAL = 30L;
	private static final String ZONA = "America/Argentina/Cordoba";
	private static final ZoneId Z = ZoneId.of(ZONA);

	@Mock private BloqueDisponibilidadRepositoryPort bloques;
	@Mock private DisponibilidadExcepcionRepositoryPort excepciones;
	@Mock private FeriadoRepositoryPort feriados;
	@Mock private CalendarioSedeRepositoryPort calendarios;
	@Mock private DisponibilidadImpactProbe sonda;

	private final ConsultorioSnapshot sede = new ConsultorioSnapshot(SEDE_ID, ORG_ID, "Sede", ZONA, true);
	private SimuladorDeImpacto simulador;
	private BloqueDisponibilidad martes;
	private TurnoPendiente turnoMartes;
	private TurnoPendiente turnoJueves;

	@BeforeEach
	void setUp() {
		simulador = new SimuladorDeImpacto(bloques, excepciones, feriados, calendarios, sonda);

		LocalDate hoy = LocalDate.now(Z);
		martes = bloque(1L, 2, hoy.minusDays(30));
		BloqueDisponibilidad jueves = bloque(2L, 4, hoy.minusDays(30));
		given(bloques.findVigentesEn(eq(ORG_ID), eq(SEDE_ID), eq(PROFESIONAL), any(), any()))
				.willReturn(List.of(martes, jueves));

		LocalDate proximoMartes = hoy.with(TemporalAdjusters.next(DayOfWeek.TUESDAY));
		LocalDate proximoJueves = hoy.with(TemporalAdjusters.next(DayOfWeek.THURSDAY));
		turnoMartes = turno(101L, proximoMartes, 10);
		turnoJueves = turno(102L, proximoJueves, 10);
		TurnoPendiente sobreturno = turno(103L, proximoMartes, 15);
		given(sonda.pendientesEn(eq(ORG_ID), eq(SEDE_ID), any(), any(), any()))
				.willReturn(List.of(turnoMartes, turnoJueves, sobreturno));
	}

	@Test
	@DisplayName("La baja de un bloque cuenta solo sus turnos: ni los de otro bloque ni el sobreturno")
	void baja_cuenta_solo_lo_que_el_bloque_cubria() {
		ImpactoDeDisponibilidad impacto = simulador.deBaja(sede, martes, Instant.now());

		assertThat(impacto.turnosAfectados()).isEqualTo(1L);
		assertThat(impacto.turnos()).extracting(ImpactoDeDisponibilidad.TurnoAfectado::turnoId)
				.containsExactly(101L);
		assertThat(impacto.primerTurnoAfectado()).isEqualTo(turnoMartes.inicio());
		assertThat(impacto.evaluadoHasta()).isEqualTo(LocalDate.now(Z).plusDays(90));
	}

	@Test
	@DisplayName("Recortar el horario cuenta el turno que queda afuera y no toca la entidad leida")
	void edicion_recorta_sin_mutar() {
		ImpactoDeDisponibilidad impacto = simulador.deEdicion(sede, martes,
				new BloqueEdicionCommand(null, null, LocalTime.of(10, 0), null, null, false, 0L),
				Instant.now());

		assertThat(impacto.turnos()).extracting(ImpactoDeDisponibilidad.TurnoAfectado::turnoId)
				.containsExactly(101L);
		assertThat(martes.getHoraHasta()).as("la simulacion trabaja sobre una copia")
				.isEqualTo(LocalTime.of(12, 0));
	}

	@Test
	@DisplayName("Ampliar el horario no deja ningun turno afuera")
	void edicion_que_amplia_da_cero() {
		ImpactoDeDisponibilidad impacto = simulador.deEdicion(sede, martes,
				new BloqueEdicionCommand(null, LocalTime.of(8, 0), null, null, null, false, 0L),
				Instant.now());

		assertThat(impacto.hayAlgo()).isFalse();
	}

	@Test
	@DisplayName("Un cierre de sede el jueves deja afuera el turno del jueves; una apertura, nada")
	void excepcion_de_sede() {
		LocalDate jueves = LocalDate.ofInstant(turnoJueves.inicio(), Z);
		DisponibilidadExcepcion cierre = new DisponibilidadExcepcion(ORG_ID, SEDE_ID, null,
				TipoExcepcion.CIERRE, MotivoExcepcion.AUSENCIA, jueves, jueves.plusDays(1),
				null, null, null, null);
		DisponibilidadExcepcion apertura = new DisponibilidadExcepcion(ORG_ID, SEDE_ID, null,
				TipoExcepcion.APERTURA, MotivoExcepcion.AUSENCIA, jueves, jueves.plusDays(1),
				null, null, null, null);

		assertThat(simulador.deAltaDeExcepcion(sede, cierre, Instant.now()).turnos())
				.extracting(ImpactoDeDisponibilidad.TurnoAfectado::turnoId).containsExactly(102L);
		assertThat(simulador.deAltaDeExcepcion(sede, apertura, Instant.now()).hayAlgo()).isFalse();
	}

	private static BloqueDisponibilidad bloque(long id, int dia, LocalDate desde) {
		BloqueDisponibilidad bloque = new BloqueDisponibilidad(ORG_ID, SEDE_ID, PROFESIONAL, dia,
				LocalTime.of(9, 0), LocalTime.of(12, 0), desde, null);
		ReflectionTestUtils.setField(bloque, "id", id);
		return bloque;
	}

	private static TurnoPendiente turno(long id, LocalDate fecha, int hora) {
		Instant inicio = fecha.atTime(hora, 0).atZone(Z).toInstant();
		return new TurnoPendiente(id, PROFESIONAL, inicio, inicio.plusSeconds(3600));
	}

}
