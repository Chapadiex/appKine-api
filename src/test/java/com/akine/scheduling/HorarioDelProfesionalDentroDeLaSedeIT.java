package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.resource.application.BloqueAltaCommand;
import com.akine.resource.application.BloquesAltaCommand;
import com.akine.resource.application.CalendarioService;
import com.akine.resource.application.CalendarioService.FranjasFuera;
import com.akine.resource.application.CalendarioView;
import com.akine.resource.application.DisponibilidadService;
import com.akine.resource.application.FranjaFueraDeHorario;
import com.akine.resource.application.FranjaFueraDeHorario.AccionSobreFranja;
import com.akine.resource.application.FranjasFueraDelHorarioException;
import com.akine.resource.application.OperatingActor;
import com.akine.resource.domain.FranjaHorarioGeneral.Franja;
import com.akine.resource.domain.IntervaloLocal;
import com.akine.resource.domain.exception.FranjaFueraDelHorarioDeSedeException;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Regla del 08/11/2026, contra MySQL real: el horario de un profesional en una sede queda DENTRO del
 * horario general de esa sede.
 *
 * <ul>
 *   <li>Cargar una franja que se sale se rechaza, y en un lote no se guarda ninguna.</li>
 *   <li>Una sede sin horario no limita nada.</li>
 *   <li>Achicar el horario de la sede avisa (409 sin guardar nada) y, con {@code RECORTAR}, deja cada
 *       franja en su parte comun y da de baja las que quedan vacias, en la misma transaccion.</li>
 * </ul>
 *
 * <p>El profesional de {@link AgendaFixtures} atiende los lunes de 09:00 a 13:00.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class HorarioDelProfesionalDentroDeLaSedeIT {

	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);
	/** El lunes de la semana que viene que no es feriado (un feriado cierra la sede: el 23/11/2026). */
	private LocalDate lunes;

	@Autowired private CalendarioService calendarioService;
	@Autowired private DisponibilidadService disponibilidadService;
	@Autowired private TurnoService turnoService;
	@Autowired private JdbcTemplate jdbc;

	private AgendaFixtures fixtures;

	@BeforeEach
	void setUp() {
		lunes = LocalDate.now(ZONA).plusDays(7).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
		while (jdbc.queryForObject("SELECT COUNT(*) FROM feriado WHERE fecha = ?", Integer.class, lunes) > 0) {
			lunes = lunes.plusWeeks(1);
		}
		fixtures = new AgendaFixtures(jdbc);
	}

	// =================================================================================
	// Carga del profesional: rechazar
	// =================================================================================

	@Test
	@DisplayName("Una franja que se sale del horario de la sede se rechaza con el horario de la sede de ese dia")
	void rechaza_la_franja_fuera_de_horario() {
		Fixture fixture = fixtures.crear(1);
		fijarHorario(fixture, List.of(franja(DayOfWeek.TUESDAY, 9, 13)), FranjasFuera.CONSERVAR);

		assertThatThrownBy(() -> disponibilidadService.crear(actor(fixture), fixture.consultorioId(),
				fixture.profesionalMembershipId(),
				new BloqueAltaCommand(DayOfWeek.TUESDAY.getValue(), LocalTime.of(8, 0), LocalTime.of(12, 0),
						null, null)))
				.isInstanceOfSatisfying(FranjaFueraDelHorarioDeSedeException.class, rechazo ->
						assertThat(rechazo.getFranjas()).singleElement().satisfies(fuera -> {
							assertThat(fuera.indice()).isZero();
							assertThat(fuera.diaSemana()).isEqualTo(DayOfWeek.TUESDAY.getValue());
							assertThat(fuera.horarioDeSede())
									.containsExactly(new IntervaloLocal(LocalTime.of(9, 0), LocalTime.of(13, 0)));
						}));

		assertThat(disponibilidadService.crear(actor(fixture), fixture.consultorioId(),
				fixture.profesionalMembershipId(),
				new BloqueAltaCommand(DayOfWeek.TUESDAY.getValue(), LocalTime.of(9, 0), LocalTime.of(12, 0),
						null, null)).nuevo())
				.as("dentro del horario se guarda")
				.isTrue();
	}

	@Test
	@DisplayName("Un lote con un dia que la sede no abre no guarda ninguno y nombra ese dia")
	void el_lote_con_un_dia_afuera_no_guarda_ninguno() {
		Fixture fixture = fixtures.crear(1);
		fijarHorario(fixture, List.of(
				franja(DayOfWeek.TUESDAY, 8, 20), franja(DayOfWeek.WEDNESDAY, 8, 20)), FranjasFuera.CONSERVAR);
		long antes = bloquesActivos(fixture);

		assertThatThrownBy(() -> disponibilidadService.crearVarios(actor(fixture), fixture.consultorioId(),
				fixture.profesionalMembershipId(),
				new BloquesAltaCommand(Set.of(2, 3, 6), LocalTime.of(9, 0), LocalTime.of(12, 0), null, null)))
				.isInstanceOfSatisfying(FranjaFueraDelHorarioDeSedeException.class, rechazo ->
						assertThat(rechazo.getFranjas())
								.extracting(f -> f.indice(), f -> f.diaSemana(), f -> f.horarioDeSede().isEmpty())
								.containsExactly(tuple(2, 6, true)));

		assertThat(bloquesActivos(fixture)).as("ni el martes ni el miercoles").isEqualTo(antes);
	}

	@Test
	@DisplayName("Una sede sin horario general no limita la carga")
	void sede_sin_horario_no_limita() {
		Fixture fixture = fixtures.crear(1);

		assertThat(disponibilidadService.crear(actor(fixture), fixture.consultorioId(),
				fixture.profesionalMembershipId(),
				new BloqueAltaCommand(DayOfWeek.SUNDAY.getValue(), LocalTime.of(6, 0), LocalTime.of(23, 0),
						null, null)).nuevo())
				.isTrue();
	}

	// =================================================================================
	// Cambio del horario de la sede: avisar y ofrecer recortar
	// =================================================================================

	@Test
	@DisplayName("Achicar la sede sin decidir avisa con la franja afuera y no guarda nada")
	void achicar_sin_decidir_avisa_y_no_guarda() {
		Fixture fixture = fixtures.crear(1);

		assertThatThrownBy(() -> fijarHorario(fixture, List.of(franja(DayOfWeek.MONDAY, 10, 12)), null))
				.isInstanceOfSatisfying(FranjasFueraDelHorarioException.class, aviso ->
						assertThat(aviso.getFranjas()).singleElement().satisfies(fuera -> {
							assertThat(fuera.membershipId()).isEqualTo(fixture.profesionalMembershipId());
							assertThat(fuera.horaDesde()).isEqualTo(LocalTime.of(9, 0));
							assertThat(fuera.horaHasta()).isEqualTo(LocalTime.of(13, 0));
							assertThat(fuera.resultantes())
									.containsExactly(new IntervaloLocal(LocalTime.of(10, 0), LocalTime.of(12, 0)));
						}));

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM consultorio_horario WHERE consultorio_id = ? AND active = true",
				Long.class, fixture.consultorioId()))
				.as("el horario de la sede no se guardo")
				.isZero();
		assertThat(franjasActivas(fixture)).containsExactly(Map.of("desde", "09:00", "hasta", "13:00"));
	}

	@Test
	@DisplayName("Recortar parte la franja que cruza el cierre del mediodia y sigue informando el turno afuera")
	void recortar_parte_la_franja_e_informa_turnos() {
		Fixture fixture = fixtures.crear(1);
		TurnoView once = turnoService.reservar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(fixture.personaA(), lunes.atTime(11, 0).atZone(ZONA).toInstant(),
						fixture.profesionalMembershipId(), null)).turno();

		CalendarioView vista = fijarHorario(fixture,
				List.of(franja(DayOfWeek.MONDAY, 9, 11), franja(DayOfWeek.MONDAY, 12, 13)), FranjasFuera.RECORTAR);

		assertThat(vista.franjasFueraDelHorario())
				.extracting(FranjaFueraDeHorario::accion)
				.containsExactly(AccionSobreFranja.RECORTADA);
		assertThat(franjasActivas(fixture)).containsExactly(
				Map.of("desde", "09:00", "hasta", "11:00"),
				Map.of("desde", "12:00", "hasta", "13:00"));
		assertThat(vista.impactoDelHorario().turnos())
				.as("el recorte va despues del impacto: el turno de las 11 se sigue informando")
				.singleElement()
				.satisfies(turno -> assertThat(turno.turnoId()).isEqualTo(once.id()));
		assertThat(jdbc.queryForObject("SELECT estado FROM turno WHERE id = ?", String.class, once.id()))
				.as("y no se cancela")
				.isEqualTo(once.estado());
	}

	@Test
	@DisplayName("Recortar da de baja la franja que queda entera afuera, con motivo y auditada")
	void recortar_da_de_baja_la_franja_vacia() {
		Fixture fixture = fixtures.crear(1);

		CalendarioView vista = fijarHorario(fixture, List.of(franja(DayOfWeek.TUESDAY, 9, 18)), FranjasFuera.RECORTAR);

		assertThat(vista.franjasFueraDelHorario())
				.extracting(FranjaFueraDeHorario::accion)
				.containsExactly(AccionSobreFranja.DADA_DE_BAJA);
		assertThat(franjasActivas(fixture)).isEmpty();
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM profesional_disponibilidad
				 WHERE consultorio_id = ? AND active = false AND deactivation_reason = ?
				""", Long.class, fixture.consultorioId(), "Fuera del horario general de la sede"))
				.as("baja logica, no borrado")
				.isEqualTo(1L);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE organization_id = ? AND event_type = 'DISPONIBILIDAD_BLOQUE_DEACTIVATED'
				""", Long.class, fixture.organizationId()))
				.isEqualTo(1L);
	}

	// =================================================================================

	private CalendarioView fijarHorario(Fixture fixture, List<Franja> horario, FranjasFuera franjasFuera) {
		return calendarioService.actualizar(actor(fixture), fixture.consultorioId(), null, null, horario,
				franjasFuera);
	}

	private static OperatingActor actor(Fixture fixture) {
		return new OperatingActor(fixture.actor().accountId(), false, fixture.organizationId(),
				fixture.consultorioId());
	}

	private long bloquesActivos(Fixture fixture) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM profesional_disponibilidad WHERE consultorio_id = ? AND active = true",
				Long.class, fixture.consultorioId());
	}

	private List<Map<String, String>> franjasActivas(Fixture fixture) {
		return jdbc.query("""
				SELECT to_char(hora_desde, 'HH24:MI') AS desde, to_char(hora_hasta, 'HH24:MI') AS hasta
				  FROM profesional_disponibilidad
				 WHERE consultorio_id = ? AND active = true
				 ORDER BY hora_desde
				""", (fila, n) -> Map.of("desde", fila.getString("desde"), "hasta", fila.getString("hasta")),
				fixture.consultorioId());
	}

	private static Franja franja(DayOfWeek dia, int desde, int hasta) {
		return new Franja(dia.getValue(), LocalTime.of(desde, 0), LocalTime.of(hasta, 0));
	}
}
