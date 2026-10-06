package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Desenlace;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.ResultadoDeInscripcion;
import com.akine.activity.domain.exception.ClaseCompletaException;
import com.akine.activity.infrastructure.InscripcionClaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenario 43 de {@code docs/tests-diferidos.md}: <b>la ultima vacante disputada por N
 * transacciones de verdad</b>. Es el escenario de AKINE-08.02.
 *
 * <h2>Por que no puede ser un test unitario</h2>
 *
 * <p>Lo unico que impide la sobreventa es que
 * {@code UPDATE clase_programada SET cupo_ocupado = cupo_ocupado + 1 WHERE cupo_ocupado < LEAST(...)}
 * evalue la condicion con <i>current read</i> bajo el lock exclusivo de la fila. Eso lo contesta
 * InnoDB; un repositorio falso que devuelve cero filas contesta lo que se le dijo.
 *
 * <h2>Verificacion por mutacion</h2>
 *
 * <p>Corrido el 06/10/2026 con {@code tomarCupo} reemplazado por un {@code SELECT} del contador
 * seguido de un {@code UPDATE} sin condicion: este archivo <b>falla</b>. El detalle —y por que la
 * mitad de los casos falla por el {@code CHECK} y la otra mitad por sobreventa limpia— esta en la
 * fila 43 del documento y en el PR.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class UltimaVacanteConcurrenteIT {

	/** Cuantas inscripciones salen juntas contra la ultima vacante. Ver el tope en el fixture. */
	private static final int N = 8;

	@Autowired private InscripcionService inscripcionService;
	@Autowired private InscripcionClaseRepository inscripcionRepository;
	@Autowired private JdbcTemplate jdbc;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	@Test
	@DisplayName("N altas sobre la ultima vacante aceptando espera: una RESERVADA y N-1 en la cola, sin sobreventa")
	void ultima_vacante_con_lista_de_espera() {
		Tenant tenant = fx.crearTenant(5, 5 + N);
		long clase = fx.programarClase(tenant, 5, 7);
		llenarHasta(tenant, clase, 4);

		List<Desenlace<ResultadoDeInscripcion>> desenlaces =
				ActividadItFixture.enParalelo(altas(tenant, clase, 4, N, true));

		assertThat(desenlaces).as("ninguna alta falla: la que pierde va a la cola. Desenlaces: %s", desenlaces)
				.noneMatch(Desenlace::fallo);
		assertThat(desenlaces.stream().filter(d -> "RESERVADA".equals(d.valor().inscripcion().estado())))
				.as("exactamente una se lleva el lugar. Desenlaces: %s", desenlaces)
				.hasSize(1);
		assertThat(fx.posicionesEnEspera(clase))
				.as("las otras N-1 quedan en la cola con posiciones distintas y consecutivas")
				.containsExactlyElementsOf(IntStream.rangeClosed(1, N - 1).boxed().toList());

		assertThat(fx.cupoOcupado(clase)).as("el contador termina en la capacidad efectiva").isEqualTo(5);
		assertThat(inscripcionRepository.contarQueConsumenCupo(tenant.organizationId(), clase))
				.as("y los recibos dicen lo mismo que el contador").isEqualTo(5);
	}

	@Test
	@DisplayName("N altas sobre la ultima vacante sin aceptar espera: una entra y N-1 reciben clase-completa")
	void ultima_vacante_sin_lista_de_espera() {
		Tenant tenant = fx.crearTenant(5, 5 + N);
		long clase = fx.programarClase(tenant, 5, 7);
		llenarHasta(tenant, clase, 4);

		List<Desenlace<ResultadoDeInscripcion>> desenlaces =
				ActividadItFixture.enParalelo(altas(tenant, clase, 4, N, false));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()))
				.as("exactamente una entra. Desenlaces: %s", desenlaces)
				.hasSize(1);
		assertThat(desenlaces.stream().filter(Desenlace::fallo)
				.filter(d -> ActividadItFixture.causaEs(d.error(), ClaseCompletaException.class)))
				.as("y las otras N-1 reciben clase-completa (CA-M28-002-06), no otro error. Desenlaces: %s",
						desenlaces)
				.hasSize(N - 1);

		assertThat(fx.cupoOcupado(clase)).isEqualTo(5);
		assertThat(fx.contarPorEstado(clase, "LISTA_ESPERA")).as("nadie quedo encolado sin pedirlo").isZero();
		assertThat(inscripcionRepository.contarQueConsumenCupo(tenant.organizationId(), clase)).isEqualTo(5);
	}

	@Test
	@DisplayName("la capacidad efectiva la pone la oferta: el CHECK de la columna no ayuda y el UPDATE condicional igual no sobrevende")
	void ultima_vacante_con_la_oferta_como_limite() {
		// El caso que distingue al UPDATE condicional de cualquier otra defensa. Con la clase en 10 y
		// la oferta en 3 —la oferta se achico despues de programar—, `ck_clase_cupo_ocupado`
		// permite llegar hasta 10: si el WHERE no serializara, la sobreventa pasaria limpia, sin un
		// solo error. El unico freno es `LEAST(capacidad, :capacidadEfectiva)` evaluado bajo lock.
		Tenant tenant = fx.crearTenant(3, 3 + N);
		long clase = fx.programarClase(tenant, 10, 7);
		llenarHasta(tenant, clase, 2);

		List<Desenlace<ResultadoDeInscripcion>> desenlaces =
				ActividadItFixture.enParalelo(altas(tenant, clase, 2, N, true));

		assertThat(desenlaces).as("Desenlaces: %s", desenlaces).noneMatch(Desenlace::fallo);
		assertThat(desenlaces.stream().filter(d -> "RESERVADA".equals(d.valor().inscripcion().estado())))
				.as("exactamente una se lleva el lugar. Desenlaces: %s", desenlaces)
				.hasSize(1);
		assertThat(fx.cupoOcupado(clase))
				.as("el contador termina en la efectiva (3), nunca en la propia (10)")
				.isEqualTo(3);
		assertThat(inscripcionRepository.contarQueConsumenCupo(tenant.organizationId(), clase)).isEqualTo(3);
		assertThat(fx.contarPorEstado(clase, "LISTA_ESPERA")).isEqualTo(N - 1);
	}

	@Test
	@DisplayName("control negativo: dos clases distintas con un lugar cada una, las dos altas entran")
	void dos_clases_distintas_no_se_bloquean_entre_si() {
		// Lo que destaparia un lock demasiado grueso —por sede, por oferta, por tenant—: dos altas
		// que no compiten por nada tienen que entrar las dos.
		Tenant tenant = fx.crearTenant(5, 10);
		long claseA = fx.programarClase(tenant, 5, 7);
		long claseB = fx.programarClase(tenant, 5, 8);
		for (int i = 0; i < 4; i++) {
			fx.inscribir(tenant, claseA, tenant.persona(i), false);
			fx.inscribir(tenant, claseB, tenant.persona(i), false);
		}

		List<Desenlace<ResultadoDeInscripcion>> desenlaces = ActividadItFixture.enParalelo(List.of(
				() -> fx.inscribir(tenant, claseA, tenant.persona(4), false),
				() -> fx.inscribir(tenant, claseB, tenant.persona(5), false)));

		assertThat(desenlaces).as("Desenlaces: %s", desenlaces)
				.allMatch(d -> !d.fallo() && "RESERVADA".equals(d.valor().inscripcion().estado()));
		assertThat(fx.cupoOcupado(claseA)).isEqualTo(5);
		assertThat(fx.cupoOcupado(claseB)).isEqualTo(5);
	}

	@Test
	@DisplayName("rafaga sobre una clase llena: todas pierden y el contador no se mueve")
	void clase_llena_rechaza_toda_la_rafaga() {
		Tenant tenant = fx.crearTenant(5, 5 + N);
		long clase = fx.programarClase(tenant, 5, 7);
		llenarHasta(tenant, clase, 5);

		List<Desenlace<ResultadoDeInscripcion>> desenlaces =
				ActividadItFixture.enParalelo(altas(tenant, clase, 5, N, false));

		assertThat(desenlaces).as("Desenlaces: %s", desenlaces)
				.allMatch(d -> ActividadItFixture.causaEs(d.error(), ClaseCompletaException.class));
		assertThat(fx.cupoOcupado(clase)).isEqualTo(5);
		assertThat(inscripcionRepository.contarQueConsumenCupo(tenant.organizationId(), clase)).isEqualTo(5);
	}

	// =================================================================================

	/** Llena la clase de a una, por el servicio, con las primeras {@code cuantas} personas. */
	private void llenarHasta(Tenant tenant, long clase, int cuantas) {
		for (int i = 0; i < cuantas; i++) {
			ResultadoDeInscripcion r = fx.inscribir(tenant, clase, tenant.persona(i), false);
			assertThat(r.inscripcion().estado()).isEqualTo("RESERVADA");
		}
		assertThat(fx.cupoOcupado(clase)).isEqualTo(cuantas);
	}

	/** {@code n} altas de personas distintas, empezando por la de indice {@code desde}. */
	private List<Callable<ResultadoDeInscripcion>> altas(
			Tenant tenant, long clase, int desde, int n, boolean aceptaEspera) {

		List<Callable<ResultadoDeInscripcion>> tareas = new ArrayList<>();
		for (int i = desde; i < desde + n; i++) {
			long persona = tenant.persona(i);
			tareas.add(() -> fx.inscribir(tenant, clase, persona, aceptaEspera));
		}
		return tareas;
	}
}
