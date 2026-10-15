package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Desenlace;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.ClaseView;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.ProgramarClaseCommand;
import com.akine.activity.application.ReprogramarClaseCommand;
import com.akine.activity.application.ResultadoDeProgramacion;
import com.akine.activity.domain.exception.CapacidadNoAdmitidaException;
import com.akine.activity.domain.exception.HorarioNoDisponibleException;
import com.akine.activity.domain.exception.RecursoOcupadoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El box elegido por quien programa y los nombres de la clase (pedidos de la pantalla de clases de
 * F9, web #81), contra MySQL real.
 *
 * <p>Lo que un unitario no puede contestar y esto si:
 * <ul>
 *   <li>que el solapamiento del box elegido mire turnos <b>y clases</b> de verdad, con las
 *       consultas reales y el lock de {@code agenda_sede};</li>
 *   <li>que dos programaciones simultaneas sobre el mismo box elegido se serialicen y gane una
 *       sola —con un mock que devuelve cero filas esa carrera no existe—;</li>
 *   <li>que las consultas agrupadas nuevas ({@code findAllByOrganizationIdAndIdIn},
 *       {@code findAllById} de espacios) existan, filtren el tenant y devuelvan los nombres.</li>
 * </ul>
 *
 * <p>La oferta no exige profesional: la disponibilidad del profesional tiene sus propios tests y
 * aca solo agregaria filas. El nombre del profesional se prueba poniendo la membership en la fila
 * por JDBC, que es lo unico que la proyeccion lee.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ClaseEspacioElegidoIT {

	@Autowired private ClaseService claseService;
	@Autowired private InscripcionService inscripcionService;
	@Autowired private JdbcTemplate jdbc;

	private ActividadItFixture fx;
	private Tenant tenant;
	private long salon;
	private long boxChico;
	private Instant inicio;
	private Instant fin;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
		tenant = fx.crearTenant(20, 0);
		jdbc.update("UPDATE oferta_servicio_consultorio SET requiere_espacio = 1 WHERE id = ?",
				tenant.ofertaId());
		salon = crearEspacio("Salon " + UUID.randomUUID().toString().substring(0, 6), 20);
		boxChico = crearEspacio("Box " + UUID.randomUUID().toString().substring(0, 6), 4);
		inicio = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
		fin = inicio.plus(1, ChronoUnit.HOURS);
	}

	@Test
	@DisplayName("Con espacioId la clase toma ESE box, y su nombre y el de la oferta vuelven en la respuesta")
	void programar_en_el_box_elegido() {
		ClaseView clase = programar(inicio, fin, 8, salon).clase();

		assertThat(clase.espacioId()).isEqualTo(salon);
		assertThat(clase.espacioNombre()).startsWith("Salon ");
		assertThat(clase.ofertaNombre()).startsWith("Oferta ");
		assertThat(jdbc.queryForObject(
				"SELECT espacio_id FROM clase_programada WHERE id = ?", Long.class, clase.id()))
				.as("lo que quedo en la base, no lo que dijo la respuesta")
				.isEqualTo(salon);
	}

	@Test
	@DisplayName("El box elegido limita la capacidad: 8 en un box de 4 es capacidad no admitida")
	void el_box_elegido_limita_el_cupo() {
		assertThatThrownBy(() -> programar(inicio, fin, 8, boxChico))
				.isInstanceOf(CapacidadNoAdmitidaException.class);
	}

	@Test
	@DisplayName("Solapamiento: el box elegido ocupado por otra clase es recurso-ocupado; otro box sirve")
	void solapamiento_contra_otra_clase() {
		programar(inicio, fin, 8, salon);

		assertThatThrownBy(() -> programar(inicio.plus(30, ChronoUnit.MINUTES),
				fin.plus(30, ChronoUnit.MINUTES), 8, salon))
				.isInstanceOf(RecursoOcupadoException.class);

		ClaseView enElChico = programar(inicio.plus(30, ChronoUnit.MINUTES),
				fin.plus(30, ChronoUnit.MINUTES), 4, boxChico).clase();
		assertThat(enElChico.espacioId()).isEqualTo(boxChico);

		assertThatThrownBy(() -> programar(inicio, fin, 4, null))
				.as("sin box elegido y los dos tomados, el servidor no encuentra ninguno")
				.isInstanceOf(RecursoOcupadoException.class);
	}

	@Test
	@DisplayName("Un box de otra sede no es apto: slot-no-disponible")
	void box_de_otra_sede() {
		String nombreSede = "Otra sede " + UUID.randomUUID().toString().substring(0, 6);
		jdbc.update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", tenant.organizationId(), nombreSede, ActividadItFixture.ZONA);
		long consultorioAjeno = jdbc.queryForObject(
				"SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				Long.class, tenant.organizationId(), nombreSede);
		long boxAjeno = crearEspacio(consultorioAjeno, "Ajeno " + consultorioAjeno, 20);

		assertThatThrownBy(() -> programar(inicio, fin, 8, boxAjeno))
				.isInstanceOf(HorarioNoDisponibleException.class);
	}

	@Test
	@DisplayName("Reprogramar con espacioId mueve el box; sobre un box tomado es recurso-ocupado")
	void reprogramar_con_box_elegido() {
		ClaseView enSalon = programar(inicio, fin, 4, salon).clase();
		ClaseView enChico = programar(inicio, fin, 4, boxChico).clase();

		assertThatThrownBy(() -> claseService.reprogramar(tenant.actor(), tenant.consultorioId(),
				enChico.id(), new ReprogramarClaseCommand(inicio, fin, null, 4, enChico.version(), salon)))
				.isInstanceOf(RecursoOcupadoException.class);

		// Se excluye a si misma: correrla media hora en su propio box no choca contra ella.
		ClaseView corrida = claseService.reprogramar(tenant.actor(), tenant.consultorioId(),
				enSalon.id(), new ReprogramarClaseCommand(inicio.plus(30, ChronoUnit.MINUTES),
						fin.plus(30, ChronoUnit.MINUTES), null, 4, enSalon.version(), salon));
		assertThat(corrida.espacioId()).isEqualTo(salon);

		ClaseView mudada = claseService.reprogramar(tenant.actor(), tenant.consultorioId(),
				enChico.id(), new ReprogramarClaseCommand(inicio.plus(2, ChronoUnit.HOURS),
						fin.plus(2, ChronoUnit.HOURS), null, 4, enChico.version(), salon));
		assertThat(mudada.espacioId()).isEqualTo(salon);
		assertThat(mudada.espacioNombre()).startsWith("Salon ");
	}

	/**
	 * La carrera que importa: dos operadores eligen el mismo box para la misma franja a la vez. El
	 * lock de {@code agenda_sede} las serializa y la segunda ve la primera.
	 */
	@Test
	@DisplayName("Dos programaciones simultaneas sobre el mismo box elegido: gana una sola")
	void dos_a_la_vez_sobre_el_mismo_box() {
		List<Callable<ResultadoDeProgramacion>> tareas = List.of(
				() -> programar(inicio, fin, 8, salon),
				() -> programar(inicio, fin, 8, salon),
				() -> programar(inicio, fin, 8, salon),
				() -> programar(inicio, fin, 8, salon));

		List<Desenlace<ResultadoDeProgramacion>> desenlaces = ActividadItFixture.enParalelo(tareas);

		assertThat(desenlaces.stream().filter(d -> !d.fallo()))
				.as("exactamente una clase en el box. Desenlaces: %s", desenlaces)
				.hasSize(1);
		assertThat(desenlaces.stream().filter(Desenlace::fallo))
				.allMatch(d -> ActividadItFixture.causaEs(d.error(), RecursoOcupadoException.class));
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM clase_programada WHERE espacio_id = ? AND estado = 'PROGRAMADA'
				""", Integer.class, salon)).isEqualTo(1);
	}

	@Test
	@DisplayName("Listar trae los nombres de oferta, profesional y box, y deja nulo lo que no hay")
	void listar_con_nombres() {
		ClaseView conBox = programar(inicio, fin, 4, salon).clase();
		ClaseView otra = programar(inicio, fin, 4, boxChico).clase();
		long membership = jdbc.queryForObject("""
				SELECT id FROM membership WHERE organization_id = ? AND account_id = ?
				""", Long.class, tenant.organizationId(), tenant.actor().accountId());
		jdbc.update("UPDATE clase_programada SET profesional_membership_id = ? WHERE id = ?",
				membership, conBox.id());

		List<ClaseView> lista = claseService.listar(tenant.actor(), tenant.consultorioId(),
				inicio.minus(1, ChronoUnit.HOURS), fin.plus(1, ChronoUnit.HOURS));

		assertThat(lista).hasSize(2);
		ClaseView leida = lista.stream().filter(c -> c.id() == conBox.id()).findFirst().orElseThrow();
		assertThat(leida.profesionalNombre()).isEqualTo("Sintetico DePrueba");
		assertThat(leida.espacioNombre()).startsWith("Salon ");
		assertThat(leida.ofertaNombre()).startsWith("Oferta ");
		ClaseView sinProfesional = lista.stream().filter(c -> c.id() == otra.id()).findFirst().orElseThrow();
		assertThat(sinProfesional.profesionalNombre()).isNull();
		assertThat(sinProfesional.espacioNombre()).startsWith("Box ");
		assertThat(sinProfesional.capacidadEfectiva()).as("min(clase 4, oferta 20, box 4)").isEqualTo(4);
	}

	// =================================================================================

	private ResultadoDeProgramacion programar(Instant desde, Instant hasta, int capacidad, Long espacioId) {
		return claseService.programar(tenant.actor(), tenant.consultorioId(), tenant.ofertaId(),
				new ProgramarClaseCommand(desde, hasta, null, capacidad, "Clase sintetica", null,
						espacioId));
	}

	private long crearEspacio(String nombre, int capacidad) {
		return crearEspacio(tenant.consultorioId(), nombre, capacidad);
	}

	private long crearEspacio(long consultorioId, String nombre, int capacidad) {
		jdbc.update("""
				INSERT INTO espacio (organization_id, consultorio_id, name, tipo, capacidad, valid_from,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, ?, 'BOX', ?, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", tenant.organizationId(), consultorioId, nombre, capacidad);
		return jdbc.queryForObject("""
				SELECT id FROM espacio WHERE organization_id = ? AND consultorio_id = ? AND name = ?
				""", Long.class, tenant.organizationId(), consultorioId, nombre);
	}
}
