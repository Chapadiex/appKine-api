package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Desenlace;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.InscripcionView;
import com.akine.activity.application.ResultadoDeCancelacion;
import com.akine.activity.infrastructure.InscripcionClaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenario 44 de {@code docs/tests-diferidos.md}: <b>la promocion doble desde la lista de
 * espera</b> (CA-M28-004-06, CA-M26-007-06).
 *
 * <p>Lo que se pone a prueba es el orden que {@code InscripcionService#cancelar} declara en su
 * cabecera: la baja <b>libera el lugar antes de leer la cola</b>, y ese {@code UPDATE} toma el lock
 * exclusivo de la fila de la clase hasta el commit. La segunda baja espera ahi y, en
 * {@code READ_COMMITTED}, lee una cola de la que ya salio el promovido por la primera. El unitario
 * cubre la rama del cero de {@code promover}; lo que no puede cubrir es que el lock la haga
 * innecesaria.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PromocionDesdeEsperaConcurrenteIT {

	private static final String MOTIVO = "Baja sintetica de prueba";

	@Autowired private InscripcionService inscripcionService;
	@Autowired private InscripcionClaseRepository inscripcionRepository;
	@Autowired private JdbcTemplate jdbc;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	@Test
	@DisplayName("dos bajas simultaneas con una sola persona en la cola: una promueve, la otra devuelve el lugar")
	void una_en_la_cola_dos_bajas() {
		Tenant tenant = fx.crearTenant(3, 4);
		long clase = fx.programarClase(tenant, 3, 7);
		long a = idDe(fx.inscribir(tenant, clase, tenant.persona(0), false).inscripcion());
		long b = idDe(fx.inscribir(tenant, clase, tenant.persona(1), false).inscripcion());
		fx.inscribir(tenant, clase, tenant.persona(2), false);
		long w1 = idDe(fx.inscribir(tenant, clase, tenant.persona(3), true).inscripcion());
		assertThat(fx.estadoDe(w1)).isEqualTo("LISTA_ESPERA");

		List<Desenlace<ResultadoDeCancelacion>> desenlaces = ActividadItFixture.enParalelo(List.of(
				() -> cancelar(tenant, clase, a),
				() -> cancelar(tenant, clase, b)));

		assertThat(desenlaces).as("las dos bajas entran. Desenlaces: %s", desenlaces)
				.noneMatch(Desenlace::fallo);
		assertThat(promovidas(desenlaces))
				.as("exactamente una baja promueve, y a la unica que esperaba. Desenlaces: %s", desenlaces)
				.containsExactly(w1);

		assertThat(fx.estadoDe(w1)).isEqualTo("RESERVADA");
		assertThat(fx.cupoOcupado(clase))
				.as("3 - 2 bajas + 1 promocion = 2: la baja que no promovio devolvio el lugar")
				.isEqualTo(2);
		assertThat(inscripcionRepository.contarQueConsumenCupo(tenant.organizationId(), clase))
				.isEqualTo(2);
	}

	@Test
	@DisplayName("dos bajas simultaneas con tres en la cola: dos promociones distintas, en orden de posicion")
	void tres_en_la_cola_dos_bajas() {
		Tenant tenant = fx.crearTenant(3, 6);
		long clase = fx.programarClase(tenant, 3, 7);
		long a = idDe(fx.inscribir(tenant, clase, tenant.persona(0), false).inscripcion());
		long b = idDe(fx.inscribir(tenant, clase, tenant.persona(1), false).inscripcion());
		fx.inscribir(tenant, clase, tenant.persona(2), false);
		long w1 = idDe(fx.inscribir(tenant, clase, tenant.persona(3), true).inscripcion());
		long w2 = idDe(fx.inscribir(tenant, clase, tenant.persona(4), true).inscripcion());
		long w3 = idDe(fx.inscribir(tenant, clase, tenant.persona(5), true).inscripcion());
		assertThat(fx.posicionesEnEspera(clase)).containsExactly(1, 2, 3);

		List<Desenlace<ResultadoDeCancelacion>> desenlaces = ActividadItFixture.enParalelo(List.of(
				() -> cancelar(tenant, clase, a),
				() -> cancelar(tenant, clase, b)));

		assertThat(desenlaces).as("Desenlaces: %s", desenlaces).noneMatch(Desenlace::fallo);
		assertThat(promovidas(desenlaces))
				.as("cada baja promueve a una persona distinta, y son las dos primeras de la cola."
						+ " Desenlaces: %s", desenlaces)
				.containsExactlyInAnyOrder(w1, w2);

		assertThat(fx.estadoDe(w1)).isEqualTo("RESERVADA");
		assertThat(fx.estadoDe(w2)).isEqualTo("RESERVADA");
		assertThat(fx.estadoDe(w3))
				.as("la tercera sigue esperando: el orden de la cola se respeto")
				.isEqualTo("LISTA_ESPERA");
		assertThat(fx.cupoOcupado(clase)).as("3 - 2 + 2").isEqualTo(3);
		assertThat(inscripcionRepository.contarQueConsumenCupo(tenant.organizationId(), clase))
				.isEqualTo(3);
	}

	// =================================================================================

	private ResultadoDeCancelacion cancelar(Tenant tenant, long clase, long inscripcion) {
		return inscripcionService.cancelar(
				tenant.actor(), tenant.consultorioId(), clase, inscripcion, MOTIVO);
	}

	private static List<Long> promovidas(List<Desenlace<ResultadoDeCancelacion>> desenlaces) {
		return desenlaces.stream()
				.map(Desenlace::valor)
				.map(ResultadoDeCancelacion::promovida)
				.filter(Objects::nonNull)
				.map(InscripcionView::id)
				.toList();
	}

	private static long idDe(InscripcionView vista) {
		return vista.id();
	}
}
