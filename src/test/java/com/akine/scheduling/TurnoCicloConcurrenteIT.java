package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.scheduling.AgendaFixtures.Desenlace;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.CicloDeTurnoService;
import com.akine.scheduling.application.EventoDeTurnoView;
import com.akine.scheduling.application.ReprogramacionCommand;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.SlotCompletoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static com.akine.scheduling.AgendaFixtures.causaEs;
import static com.akine.scheduling.AgendaFixtures.enParalelo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * El criterio de aceptacion de AKINE-05.03: <b>no hay borrado ni doble ocupacion</b>.
 *
 * <h2>Por que estos cuatro escenarios y no otros</h2>
 *
 * <p>Los tres primeros son los que <b>ningun test unitario puede contestar</b>: si dos
 * reprogramaciones al mismo hueco se serializan de verdad depende del {@code FOR UPDATE} sobre
 * {@code agenda_sede} y del aislamiento de InnoDB, no del codigo Java. Con mocks se verificaria el
 * orden de las llamadas, que es exactamente lo que ya funcionaba en 05.02 antes de que MySQL real
 * mostrara los tres defectos que no se veian.
 *
 * <p>El cuarto —cancelar libera, ausente no— no es concurrente pero si es de base: lo que decide si
 * un turno sigue ocupando lugar es la interaccion entre {@code deleted_at}, el {@code deleted_key}
 * generado y las consultas de solapamiento. Probarlo con un doble del repositorio probaria el doble.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class TurnoCicloConcurrenteIT {

	/** Ver el javadoc de la constante homonima en {@code TurnoConcurrenteIT}. */
	private static final LocalDate LUNES = LocalDate.of(2027, 3, 8);

	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);

	@Autowired private TurnoService turnoService;
	@Autowired private CicloDeTurnoService cicloService;
	@Autowired private JdbcTemplate jdbc;

	private AgendaFixtures fixtures;

	@BeforeEach
	void prepararFixtures() {
		fixtures = new AgendaFixtures(jdbc);
	}

	@Test
	@DisplayName("dos reprogramaciones concurrentes al mismo hueco: una entra y la otra recibe 409")
	void dos_reprogramaciones_al_mismo_hueco_no_pasan_las_dos() {
		Fixture fixture = fixtures.crear(1);
		// Dos turnos del MISMO profesional, en horas distintas. Los dos quieren mudarse a las 12.
		TurnoView deLasNueve = reservar(fixture, fixture.personaA(), hora(9));
		TurnoView deLasDiez = reservar(fixture, fixture.personaB(), hora(10));

		Callable<TurnoView> mueveA = () -> reprogramar(fixture, deLasNueve, hora(12));
		Callable<TurnoView> mueveB = () -> reprogramar(fixture, deLasDiez, hora(12));

		List<Desenlace<TurnoView>> desenlaces = enParalelo(List.of(mueveA, mueveB));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente un turno se mueve. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), SlotCompletoException.class)
						|| causaEs(d.error(), RecursoOcupadoException.class))
				.count())
				.as("el otro recibe un 409 de agenda, no un error generico. Desenlaces: %s", desenlaces)
				.isEqualTo(1);

		assertThat(turnosVivosQueEmpiezanEn(fixture, hora(12)))
				.as("y en la base hay UNA sola fila viva a las 12: si las dos hubieran pasado su "
						+ "validacion, habria dos turnos encima")
				.isEqualTo(1);
		assertThat(contarVivos(fixture))
				.as("los dos turnos siguen vivos: mover no borra ni crea filas")
				.isEqualTo(2);
	}

	@Test
	@DisplayName("una reprogramacion y una reserva compiten por el mismo hueco: gana una sola")
	void reprogramar_compite_con_reservar() {
		Fixture fixture = fixtures.crear(1);
		TurnoView aMover = reservar(fixture, fixture.personaA(), hora(9));

		Callable<TurnoView> mueve = () -> reprogramar(fixture, aMover, hora(11));
		Callable<TurnoView> reserva = () -> reservar(fixture, fixture.personaB(), hora(11));

		List<Desenlace<TurnoView>> desenlaces = enParalelo(List.of(mueve, reserva));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("mover es una escritura de agenda y compite con reservar: una sola gana. "
						+ "Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(turnosVivosQueEmpiezanEn(fixture, hora(11))).isEqualTo(1);
	}

	@Test
	@DisplayName("cancelar libera el lugar, y la fila cancelada queda con su motivo")
	void cancelar_libera_el_lugar_sin_borrar() {
		Fixture fixture = fixtures.crear(1);
		TurnoView original = reservar(fixture, fixture.personaA(), hora(9));

		TurnoView cancelado = cicloService.cancelar(
				fixture.actor(), fixture.consultorioId(), original.id(),
				"El paciente aviso que no puede venir", original.version());

		assertThat(cancelado.estado()).isEqualTo("CANCELADO");

		// La prueba de que el lugar quedo libre: el mismo hueco vuelve a aceptar una reserva.
		TurnoView reemplazo = reservar(fixture, fixture.personaB(), hora(9));
		assertThat(reemplazo.id()).isNotEqualTo(original.id());

		Map<String, Object> fila = jdbc.queryForMap(
				"SELECT estado, motivo_cancelacion, deleted_at FROM turno WHERE id = ?",
				original.id());
		assertThat(fila.get("estado")).isEqualTo("CANCELADO");
		assertThat(fila.get("motivo_cancelacion"))
				.as("el motivo queda en la fila: DP-04 exige motivo y auditoria")
				.isEqualTo("El paciente aviso que no puede venir");
		assertThat(fila.get("deleted_at"))
				.as("baja logica, no borrado: la fila sigue ahi con su historia")
				.isNotNull();

		List<EventoDeTurnoView> historial = cicloService.historial(
				fixture.actor(), fixture.consultorioId(), original.id());
		assertThat(historial).extracting(EventoDeTurnoView::tipo)
				.as("el historial cuenta el ciclo completo, de la reserva a la cancelacion")
				.containsExactly("RESERVA", "CANCELACION");
		assertThat(historial.get(1).motivo()).isEqualTo("El paciente aviso que no puede venir");
	}

	@Test
	@DisplayName("una ausencia NO libera el lugar y no toca ningun otro turno")
	void la_ausencia_no_libera_el_lugar() {
		Fixture fixture = fixtures.crear(1);
		// Un turno que ya paso. No se puede llegar a el reservando —la reserva exige futuro— asi
		// que se inserta como lo dejaria una reserva de la semana pasada.
		long turnoId = insertarTurnoPasado(fixture);

		TurnoView ausente = cicloService.marcarAusente(
				fixture.actor(), fixture.consultorioId(), turnoId, "No aviso", 0L);

		assertThat(ausente.estado()).isEqualTo("AUSENTE");

		Map<String, Object> fila = jdbc.queryForMap(
				"SELECT estado, deleted_at, ausente_en FROM turno WHERE id = ?", turnoId);
		assertThat(fila.get("deleted_at"))
				.as("la hora se consumio igual: un ausente sigue ocupando su lugar, a diferencia "
						+ "de un cancelado")
				.isNull();
		assertThat(fila.get("ausente_en")).isNotNull();

		assertThat(contarVivos(fixture))
				.as("y no desaparecio ninguna fila: DP-04 deroga el borrado del documento de 2019")
				.isEqualTo(1);
		assertThat(cicloService.historial(fixture.actor(), fixture.consultorioId(), turnoId))
				.extracting(EventoDeTurnoView::tipo)
				.contains("AUSENCIA");
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	private Instant hora(int horaLocal) {
		return LUNES.atTime(horaLocal, 0).atZone(ZONA).toInstant();
	}

	private TurnoView reservar(Fixture fixture, long personaId, Instant inicio) {
		return turnoService.reservar(
				fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(personaId, inicio, fixture.profesionalMembershipId(), null))
				.turno();
	}

	private TurnoView reprogramar(Fixture fixture, TurnoView turno, Instant destino) {
		return cicloService.reprogramar(
				fixture.actor(), fixture.consultorioId(), turno.id(),
				new ReprogramacionCommand(
						destino, fixture.profesionalMembershipId(),
						"El profesional pidio el dia", turno.version()));
	}

	/**
	 * Un turno de la semana pasada, insertado directo.
	 *
	 * <p>El servicio no puede crearlo —reservar exige futuro, y con razon— y maquillar el reloj del
	 * test seria peor: lo que se quiere probar es que la ausencia no libera el lugar, no como se
	 * llego al turno.
	 */
	private long insertarTurnoPasado(Fixture fixture) {
		jdbc.update("""
				INSERT INTO turno (organization_id, consultorio_id, oferta_id, persona_id,
				                   profesional_membership_id, inicio, fin, estado,
				                   reservado_por_cuenta_id, reservado_en, version,
				                   created_at, updated_at)
				VALUES (?, ?, ?, ?, ?,
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 7 DAY),
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 7 DAY) + INTERVAL 60 MINUTE,
				        'RESERVADO', ?, UTC_TIMESTAMP(6), 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId(), fixture.ofertaId(),
				fixture.personaA(), fixture.profesionalMembershipId(),
				fixture.actor().accountId());
		return jdbc.queryForObject("""
				SELECT MAX(id) FROM turno WHERE organization_id = ? AND oferta_id = ?
				""", Long.class, fixture.organizationId(), fixture.ofertaId());
	}

	private long contarVivos(Fixture fixture) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM turno
				 WHERE organization_id = ? AND oferta_id = ? AND deleted_at IS NULL
				""", Long.class, fixture.organizationId(), fixture.ofertaId());
	}

	/**
	 * <p>El instante se compara <b>convertido a texto UTC</b> y no enlazado como parametro: enlazar
	 * un {@code Instant} en JDBC lo convierte con la zona de la sesion, que no es necesariamente la
	 * misma conversion que hace Hibernate al guardar. Es la trampa que ya documenta
	 * {@code TurnoConcurrenteIT}, y aca no se puede esquivar contando todo porque el escenario
	 * necesita justamente el predicado por hora.
	 */
	private long turnosVivosQueEmpiezanEn(Fixture fixture, Instant inicio) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM turno
				 WHERE organization_id = ? AND oferta_id = ? AND deleted_at IS NULL
				   AND inicio = ?
				""", Long.class, fixture.organizationId(), fixture.ofertaId(),
				inicio.atZone(ZoneId.of("UTC")).toLocalDateTime());
	}
}
