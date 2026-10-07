package com.akine.offering;

import com.akine.TestcontainersConfiguration;
import com.akine.offering.application.HabilitacionesView;
import com.akine.offering.application.OfertaHabilitacionService;
import com.akine.offering.application.OfertaPracticaService;
import com.akine.offering.application.OperatingActor;
import com.akine.offering.application.PracticasDeOfertaView;
import com.akine.offering.domain.exception.OfertaNotAccessibleException;
import com.akine.offering.domain.exception.PracticaNoElegibleException;
import com.akine.offering.spi.PracticaDeOferta;
import com.akine.offering.spi.PracticasDeOfertaDirectory;
import com.akine.scheduling.AgendaFixtures;
import com.akine.scheduling.AgendaFixtures.Desenlace;
import com.akine.scheduling.AgendaFixtures.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El puente Oferta-Practica contra MySQL real (A-9, DP-11, {@code V75}).
 *
 * <p>Lo que un unitario no puede contestar: que {@code V75} ejecute, que la base sostenga "una sola
 * principal" por su cuenta, que la baja logica no choque con el alta de la misma practica, que el
 * orden de escritura del servicio sobreviva al orden INSERT-antes-que-UPDATE de Hibernate, y que
 * dos reemplazos simultaneos terminen en un ganador y un 409 —nunca en un 500 ni en una oferta con
 * dos principales—.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class OfertaPracticaIT {

	@Autowired private OfertaPracticaService practicas;
	@Autowired private OfertaHabilitacionService habilitaciones;
	@Autowired private PracticasDeOfertaDirectory directorio;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("V75 ejecuta y crea la tabla con sus dos uniques, sus CHECK y sus FK")
	void v75_ejecuta() {
		List<String> constraints = jdbc.queryForList("""
				SELECT constraint_name FROM information_schema.table_constraints
				WHERE table_schema = DATABASE() AND table_name = 'oferta_practica'
				""", String.class);

		assertThat(constraints).contains(
				"uk_oferta_practica_vigente", "uk_oferta_practica_principal",
				"ck_oferta_practica_baja_coherente", "ck_oferta_practica_principal_vigente",
				"fk_oferta_practica_organization", "fk_oferta_practica_consultorio",
				"fk_oferta_practica_oferta", "fk_oferta_practica_practica");
		assertThat(jdbc.queryForObject(
				"SELECT success FROM flyway_schema_history WHERE version = '75'", Boolean.class))
				.isTrue();
	}

	@Test
	@DisplayName("una sola principal vigente por oferta: la segunda la rechaza la BASE, sin pasar "
			+ "por la aplicacion")
	void una_sola_principal_la_sostiene_la_base() {
		Escenario e = escenario();
		insertarFila(e, e.practicaA(), true, false);
		insertarFila(e, e.practicaB(), false, false);

		assertThatThrownBy(() -> insertarFila(e, e.practicaC(), true, false))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uk_oferta_practica_principal");

		// Una dada de baja no puede conservar la marca (CHECK)...
		// (el traductor de JdbcTemplate no categoriza el 3819 de MySQL: llega como DataAccessException)
		assertThatThrownBy(() -> insertarFila(e, e.practicaC(), true, true))
				.isInstanceOf(org.springframework.dao.DataAccessException.class)
				.hasMessageContaining("ck_oferta_practica_principal_vigente");
		// ...y una dada de baja que FUE principal no estorba a la vigente: su principal_key es NULL.
		insertarFila(e, e.practicaC(), false, true);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM oferta_practica WHERE oferta_id = ? AND principal_key = 1",
				Integer.class, e.f().ofertaId())).isEqualTo(1);
	}

	@Test
	@DisplayName("baja logica: quitar una practica y volver a agregarla no choca, y el historico queda")
	void baja_logica_no_choca_con_el_alta() {
		Escenario e = escenario();
		long v = version(e);

		PracticasDeOfertaView alta = practicas.reemplazar(e.admin(), org(e), sede(e), oferta(e),
				List.of(e.practicaA()), e.practicaA(), v);
		PracticasDeOfertaView baja = practicas.reemplazar(e.admin(), org(e), sede(e), oferta(e),
				List.of(), null, alta.ofertaVersion());
		PracticasDeOfertaView realta = practicas.reemplazar(e.admin(), org(e), sede(e), oferta(e),
				List.of(e.practicaA()), e.practicaA(), baja.ofertaVersion());

		assertThat(baja.practicaPrincipalId()).isNull();
		assertThat(realta.practicaPrincipalId()).isEqualTo(e.practicaA());
		assertThat(realta.practicas()).hasSize(2);
		assertThat(realta.practicas()).extracting(PracticasDeOfertaView.PracticaDeOfertaView::estado)
				.containsExactly("INACTIVO", "ACTIVO");
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM oferta_practica
				WHERE oferta_id = ? AND practica_id = ? AND active = 0
				  AND deactivation_reason IS NOT NULL
				""", Integer.class, oferta(e), e.practicaA())).isEqualTo(1);
		assertThat(version(e)).as("tres reemplazos, tres avances").isEqualTo(v + 3);
	}

	@Test
	@DisplayName("pasar la principal a una practica NUEVA entra: el desmarcado se vuelca antes del "
			+ "INSERT")
	void principal_a_una_practica_nueva() {
		Escenario e = escenario();
		PracticasDeOfertaView primera = practicas.reemplazar(e.admin(), org(e), sede(e), oferta(e),
				List.of(e.practicaA()), e.practicaA(), version(e));

		// Sin las dos fases, Hibernate inserta B con principal=1 antes de desmarcar A y
		// uk_oferta_practica_principal rechaza un reemplazo legitimo.
		PracticasDeOfertaView segunda = practicas.reemplazar(e.admin(), org(e), sede(e), oferta(e),
				List.of(e.practicaA(), e.practicaB()), e.practicaB(), primera.ofertaVersion());

		assertThat(segunda.practicaPrincipalId()).isEqualTo(e.practicaB());
		assertThat(segunda.ofertaVersion()).isEqualTo(version(e));
		assertThat(directorio.practicaPrincipal(org(e), sede(e), oferta(e))).contains(e.practicaB());
	}

	@Test
	@DisplayName("version vieja: el segundo en guardar recibe 409, y las practicas comparten la "
			+ "version con las habilitaciones")
	void version_vieja_y_compartida() {
		Escenario e = escenario();
		long leida = version(e);

		PracticasDeOfertaView tras = practicas.reemplazar(e.admin(), org(e), sede(e), oferta(e),
				List.of(e.practicaA()), e.practicaA(), leida);
		assertThat(version(e)).as("solo tablas hijas, y aun asi avanza una vez").isEqualTo(leida + 1);

		assertThatThrownBy(() -> practicas.reemplazar(e.admin(), org(e), sede(e), oferta(e),
				List.of(e.practicaB()), e.practicaB(), leida))
				.isInstanceOf(OptimisticLockingFailureException.class);
		// La pantalla de habilitaciones con la version de antes tambien choca: es la misma oferta.
		assertThatThrownBy(() -> habilitaciones.reemplazarProfesionales(e.admin(), org(e), sede(e),
				oferta(e), Set.of(), leida))
				.isInstanceOf(OptimisticLockingFailureException.class);

		// Y con la que devolvio el reemplazo de practicas, entra sin releer.
		HabilitacionesView encadenada = habilitaciones.reemplazarProfesionales(e.admin(), org(e),
				sede(e), oferta(e), Set.of(), tras.ofertaVersion());
		assertThat(encadenada.ofertaVersion()).isEqualTo(version(e)).isEqualTo(leida + 2);
	}

	@Test
	@DisplayName("dos reemplazos simultaneos con la misma version: uno gana, el otro 409, nunca 500 "
			+ "ni dos principales")
	void reemplazo_concurrente() {
		Escenario e = escenario();
		long v = version(e);

		List<Callable<PracticasDeOfertaView>> tareas = List.of(
				() -> practicas.reemplazar(e.admin(), org(e), sede(e), oferta(e),
						List.of(e.practicaA(), e.practicaB()), e.practicaA(), v),
				() -> practicas.reemplazar(e.admin(), org(e), sede(e), oferta(e),
						List.of(e.practicaB(), e.practicaA()), e.practicaB(), v));
		List<Desenlace<PracticasDeOfertaView>> desenlaces = AgendaFixtures.enParalelo(tareas);

		assertThat(desenlaces).as(desenlaces.toString()).filteredOn(d -> !d.fallo()).hasSize(1);
		Desenlace<PracticasDeOfertaView> perdedor =
				desenlaces.stream().filter(Desenlace::fallo).findFirst().orElseThrow();
		assertThat(perdedor.error())
				.as("el perdedor recibe un 409 de version, no un deadlock que la API responde con 500")
				.isInstanceOf(OptimisticLockingFailureException.class);

		Desenlace<PracticasDeOfertaView> ganador =
				desenlaces.stream().filter(d -> !d.fallo()).findFirst().orElseThrow();
		assertThat(version(e)).isEqualTo(v + 1);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM oferta_practica WHERE oferta_id = ? AND principal_key = 1",
				Integer.class, oferta(e))).isEqualTo(1);
		assertThat(directorio.practicaPrincipal(org(e), sede(e), oferta(e)))
				.contains(ganador.valor().practicaPrincipalId());
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM oferta_practica WHERE oferta_id = ?", Integer.class, oferta(e)))
				.as("solo las filas del ganador").isEqualTo(2);
	}

	@Test
	@DisplayName("habilitaciones: dos reemplazos simultaneos con la misma version terminan en 409, "
			+ "no en un deadlock")
	void habilitaciones_concurrentes() {
		Escenario e = escenario();
		// El fixture ya habilita al profesional: se lo quita primero para que los dos reemplazos
		// concurrentes INSERTEN, que es lo que deja el lock compartido de la FK sobre la oferta.
		long v = habilitaciones.reemplazarProfesionales(e.admin(), org(e), sede(e), oferta(e),
				Set.of(), version(e)).ofertaVersion();
		Set<Long> profesional = Set.of(e.f().profesionalMembershipId());

		List<Callable<HabilitacionesView>> tareas = List.of(
				() -> habilitaciones.reemplazarProfesionales(e.admin(), org(e), sede(e), oferta(e),
						profesional, v),
				() -> habilitaciones.reemplazarProfesionales(e.admin(), org(e), sede(e), oferta(e),
						profesional, v));
		List<Desenlace<HabilitacionesView>> desenlaces = AgendaFixtures.enParalelo(tareas);

		assertThat(desenlaces).as(desenlaces.toString()).filteredOn(d -> !d.fallo()).hasSize(1);
		assertThat(desenlaces.stream().filter(Desenlace::fallo).findFirst().orElseThrow().error())
				.isInstanceOf(OptimisticLockingFailureException.class);
		assertThat(version(e)).isEqualTo(v + 1);
	}

	@Test
	@DisplayName("tenant ajeno: su oferta es 404 y su practica propia tambien")
	void tenant_ajeno() {
		Escenario a = escenario();
		Escenario b = escenario();

		// B, en su propio contexto, pidiendo la oferta de A.
		assertThatThrownBy(() -> practicas.leer(b.admin(), org(b), sede(b), oferta(a)))
				.isInstanceOf(OfertaNotAccessibleException.class);
		assertThatThrownBy(() -> practicas.reemplazar(b.admin(), org(b), sede(b), oferta(a),
				List.of(b.practicaA()), b.practicaA(), version(a)))
				.isInstanceOf(OfertaNotAccessibleException.class);

		// A queriendo usar una practica propia de B.
		assertThatThrownBy(() -> practicas.reemplazar(a.admin(), org(a), sede(a), oferta(a),
				List.of(b.practicaA()), b.practicaA(), version(a)))
				.isInstanceOfSatisfying(PracticaNoElegibleException.class, ex -> assertThat(
						ex.getMotivo()).isEqualTo(PracticaNoElegibleException.Motivo.INEXISTENTE));

		// El spi tampoco cruza: otro tenant u otra sede responden vacio.
		practicas.reemplazar(a.admin(), org(a), sede(a), oferta(a),
				List.of(a.practicaA()), a.practicaA(), version(a));
		assertThat(directorio.practicasHabilitadas(org(b), sede(a), oferta(a))).isEmpty();
		assertThat(directorio.practicasHabilitadas(org(a), otraSede(a), oferta(a))).isEmpty();
		assertThat(directorio.practicasHabilitadas(org(a), sede(a), oferta(a)))
				.containsExactly(new PracticaDeOferta(a.practicaA(), true));
	}

	@Test
	@DisplayName("una practica GLOBAL de plataforma se puede usar; una dada de baja no se puede agregar")
	void practica_global_y_no_vigente() {
		Escenario e = escenario();
		long global = insertarPractica(null, "GLB");
		jdbc.update("""
				UPDATE practica SET active = 0, deleted_at = UTC_TIMESTAMP(6),
				       deactivation_reason = 'Baja sintetica' WHERE id = ?
				""", e.practicaC());

		PracticasDeOfertaView vista = practicas.reemplazar(e.admin(), org(e), sede(e), oferta(e),
				List.of(global, e.practicaA()), global, version(e));
		assertThat(vista.practicaPrincipalId()).isEqualTo(global);
		assertThat(vista.practicas()).allMatch(PracticasDeOfertaView.PracticaDeOfertaView::vigenteEnCatalogo);

		assertThatThrownBy(() -> practicas.reemplazar(e.admin(), org(e), sede(e), oferta(e),
				List.of(global, e.practicaC()), global, vista.ofertaVersion()))
				.isInstanceOfSatisfying(PracticaNoElegibleException.class, ex -> assertThat(
						ex.getMotivo()).isEqualTo(PracticaNoElegibleException.Motivo.NO_VIGENTE));
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private record Escenario(Fixture f, OperatingActor admin, long practicaA, long practicaB,
			long practicaC) {
	}

	private Escenario escenario() {
		Fixture f = new AgendaFixtures(jdbc).crear(1);
		// La lectura exige pertenencia, y la pertenencia exige una suscripcion vigente.
		jdbc.update("""
				INSERT INTO subscription (organization_id, plan_id, status, started_at, active,
				                          version, created_at, updated_at)
				SELECT ?, id, 'ACTIVA', UTC_TIMESTAMP(6), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
				FROM plan WHERE code = 'BASICO'
				""", f.organizationId());
		OperatingActor admin = new OperatingActor(
				f.actor().accountId(), false, f.organizationId(), f.consultorioId());
		return new Escenario(f, admin,
				insertarPractica(f.organizationId(), "A"),
				insertarPractica(f.organizationId(), "B"),
				insertarPractica(f.organizationId(), "C"));
	}

	private long insertarPractica(Long organizationId, String prefijo) {
		String sufijo = prefijo + "-" + UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
				INSERT INTO especialidad (organization_id, codigo, name, valid_from, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, "ESP-" + sufijo, "Especialidad " + sufijo);
		long especialidadId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("""
				INSERT INTO practica (organization_id, especialidad_id, codigo, name, valid_from,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, especialidadId, "PRA-" + sufijo, "Practica " + sufijo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private void insertarFila(Escenario e, long practicaId, boolean principal, boolean dadaDeBaja) {
		jdbc.update("""
				INSERT INTO oferta_practica (organization_id, consultorio_id, oferta_id, practica_id,
				                             principal, active, deleted_at, deactivation_reason,
				                             version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org(e), sede(e), oferta(e), practicaId, principal, !dadaDeBaja,
				dadaDeBaja ? java.sql.Timestamp.valueOf("2026-01-01 00:00:00") : null,
				dadaDeBaja ? "Baja sintetica" : null);
	}

	private long otraSede(Escenario e) {
		String nombre = "Otra sede " + UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org(e), nombre, AgendaFixtures.ZONA);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long version(Escenario e) {
		return jdbc.queryForObject(
				"SELECT version FROM oferta_servicio_consultorio WHERE id = ?", Long.class, oferta(e));
	}

	private static long org(Escenario e) {
		return e.f().organizationId();
	}

	private static long sede(Escenario e) {
		return e.f().consultorioId();
	}

	private static long oferta(Escenario e) {
		return e.f().ofertaId();
	}
}
