package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Desenlace;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.CarreraDirigida.Retencion;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.ClaseView;
import com.akine.activity.application.InscribirCommand;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.ReprogramarClaseCommand;
import com.akine.activity.domain.exception.CapacidadNoAdmitidaException;
import com.akine.activity.domain.exception.ClaseCompletaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenario 46 de {@code docs/tests-diferidos.md} (AKINE-08.02): <b>bajar la capacidad mientras
 * alguien se inscribe</b>.
 *
 * <p>La clase tiene capacidad 5 y 3 ocupados. Una reprogramacion la baja a 3 —la ocupacion
 * actual, que es lo minimo que RF-M12-012 admite— y a la vez una cuarta persona se inscribe. Las
 * dos ejecuciones en serie posibles son: o la capacidad baja primero y la inscripcion pierde contra
 * la capacidad NUEVA ({@code clase-completa}), o la inscripcion entra primero y la reprogramacion
 * pierde porque ya no puede bajar por debajo de 4 ({@code capacidad-no-admitida}). Lo que no puede
 * pasar es que ganen las dos: eso deja {@code cupo_ocupado > capacidad} y lo frena
 * {@code ck_clase_cupo_ocupado} con un 500.
 *
 * <p>Lo que lo impide es el {@code lockByIdInScope} —{@code SELECT ... FOR UPDATE}— que
 * {@code ClaseService#reprogramar} toma antes de leer la ocupacion. Las dos ordenes se fuerzan con
 * {@link CarreraDirigida}.
 *
 * <p><b>Verificacion por mutacion</b> (corrida el 17/10/2026): con {@code findByIdInScope} en lugar
 * de {@code lockByIdInScope}, la reprogramacion lee la ocupacion vieja, la inscripcion toma el
 * cuarto lugar y el {@code UPDATE} de la reprogramacion muere contra {@code ck_clase_cupo_ocupado}:
 * este test falla.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CapacidadContraInscripcionIT {

	private static final String TOMAR_CUPO = "%cupo_ocupado = cupo_ocupado + 1%";
	/**
	 * Cualquier sentencia sobre la clase, y no solo el {@code FOR UPDATE}: asi la carrera tambien se
	 * arma cuando la reprogramacion lee sin lock y espera recien en su {@code UPDATE}, que es el
	 * caso que la mutacion del escenario produce.
	 */
	private static final String SENTENCIA_SOBRE_LA_CLASE = "%clase_programada%";

	@Autowired private InscripcionService inscripcionService;
	@Autowired private ClaseService claseService;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DataSource dataSource;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	@ParameterizedTest(name = "la reprogramacion llega primero: {0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("Bajar la capacidad a la ocupacion contra una inscripcion: gana una sola y el CHECK no se dispara")
	void bajar_la_capacidad_contra_una_inscripcion(boolean reprogramacionPrimero) {
		Tenant tenant = fx.crearTenant(20, 4);
		long clase = claseConTresOcupados(tenant);
		ClaseView leida = claseService.ver(tenant.actor(), tenant.consultorioId(), clase);

		Callable<Object> reprogramar = () -> claseService.reprogramar(tenant.actor(),
				tenant.consultorioId(), clase,
				new ReprogramarClaseCommand(leida.inicio(), leida.fin(), null, 3, leida.version(), null));
		Callable<Object> inscribir = () -> inscripcionService.inscribir(tenant.actor(),
				tenant.consultorioId(), clase, new InscribirCommand(tenant.persona(3), false, null));

		Desenlace<Object> reprogramacion;
		Desenlace<Object> inscripcion;
		try (CarreraDirigida carrera = new CarreraDirigida(jdbc);
				Retencion fila = Retencion.de(dataSource, "clase_programada", clase)) {
			Future<Desenlace<Object>> fReprogramar;
			Future<Desenlace<Object>> fInscribir;
			if (reprogramacionPrimero) {
				fReprogramar = carrera.largarHastaBloquearse(reprogramar, SENTENCIA_SOBRE_LA_CLASE);
				fInscribir = carrera.largarHastaBloquearse(inscribir, TOMAR_CUPO);
			} else {
				fInscribir = carrera.largarHastaBloquearse(inscribir, TOMAR_CUPO);
				fReprogramar = carrera.largarHastaBloquearse(reprogramar, SENTENCIA_SOBRE_LA_CLASE);
			}
			fila.soltar();
			reprogramacion = CarreraDirigida.resultado(fReprogramar);
			inscripcion = CarreraDirigida.resultado(fInscribir);
		}

		List<Desenlace<Object>> desenlaces = List.of(reprogramacion, inscripcion);
		assertThat(desenlaces)
				.as("ninguna llega al CHECK de la base. Desenlaces: %s", desenlaces)
				.noneMatch(d -> d.fallo()
						&& ActividadItFixture.causaEs(d.error(), DataIntegrityViolationException.class));
		assertThat(desenlaces.stream().filter(d -> !d.fallo()))
				.as("gana exactamente una. Desenlaces: %s", desenlaces)
				.hasSize(1);

		int capacidad = capacidad(clase);
		if (!reprogramacion.fallo()) {
			assertThat(capacidad).isEqualTo(3);
			assertThat(ActividadItFixture.causaEs(inscripcion.error(), ClaseCompletaException.class))
					.as("la inscripcion pierde contra la capacidad NUEVA: %s", inscripcion).isTrue();
			assertThat(fx.cupoOcupado(clase)).isEqualTo(3);
		} else {
			assertThat(ActividadItFixture.causaEs(reprogramacion.error(), CapacidadNoAdmitidaException.class))
					.as("la reprogramacion ve el cuarto ocupado y no baja: %s", reprogramacion).isTrue();
			assertThat(capacidad).isEqualTo(5);
			assertThat(fx.cupoOcupado(clase)).isEqualTo(4);
		}
		assertThat(fx.cupoOcupado(clase)).isLessThanOrEqualTo(capacidad);
	}

	@Test
	@DisplayName("Control negativo: si la reprogramacion no baja la capacidad, la inscripcion simultanea entra")
	void sin_bajar_la_capacidad_entran_las_dos() {
		Tenant tenant = fx.crearTenant(20, 4);
		long clase = claseConTresOcupados(tenant);
		ClaseView leida = claseService.ver(tenant.actor(), tenant.consultorioId(), clase);

		List<Desenlace<Object>> desenlaces = ActividadItFixture.enParalelo(List.of(
				() -> claseService.reprogramar(tenant.actor(), tenant.consultorioId(), clase,
						new ReprogramarClaseCommand(leida.inicio(), leida.fin(), null, 5, leida.version(), null)),
				() -> inscripcionService.inscribir(tenant.actor(), tenant.consultorioId(), clase,
						new InscribirCommand(tenant.persona(3), false, null))));

		assertThat(desenlaces).as("Desenlaces: %s", desenlaces).noneMatch(Desenlace::fallo);
		assertThat(fx.cupoOcupado(clase)).isEqualTo(4);
		assertThat(capacidad(clase)).isEqualTo(5);
	}

	private long claseConTresOcupados(Tenant tenant) {
		long clase = fx.programarClase(tenant, 5, 7);
		for (int i = 0; i < 3; i++) {
			fx.inscribir(tenant, clase, tenant.persona(i), false);
		}
		assertThat(fx.cupoOcupado(clase)).isEqualTo(3);
		return clase;
	}

	private int capacidad(long clase) {
		return jdbc.queryForObject("SELECT capacidad FROM clase_programada WHERE id = ?", Integer.class, clase);
	}
}
