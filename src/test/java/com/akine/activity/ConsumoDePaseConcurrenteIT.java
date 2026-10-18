package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Desenlace;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.ConsumoDeCredito.Efecto;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.ProductoService;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.application.ResultadoDeAsistencia;
import com.akine.activity.application.VenderPaseCommand;
import com.akine.activity.application.VentaDePaseService;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>El ultimo credito disputado por transacciones de verdad</b> (AKINE-08.07, caso borde "ultimo
 * credito concurrente" del plan).
 *
 * <p>Una persona con un pase de UN credito asiste a la vez a varias clases de la misma oferta. Lo
 * unico que impide el doble consumo es el {@code FOR UPDATE} sobre los pases elegibles y el
 * {@code UPDATE ... WHERE creditos_disponibles >= 1} evaluado con lectura actual bajo el lock de la
 * fila. Un repositorio falso que devuelve cero filas contesta lo que se le dijo; esto lo contesta
 * InnoDB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ConsumoDePaseConcurrenteIT {

	private static final int CLASES = 4;

	@Autowired private ProductoService productoService;
	@Autowired private VentaDePaseService ventaService;
	@Autowired private InscripcionService inscripcionService;
	@Autowired private AsistenciaService asistenciaService;
	@Autowired private JdbcTemplate jdbc;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	@Test
	@DisplayName("N asistencias simultaneas contra el ultimo credito: un CONSUMIDO, N-1 SIN_PASE_ELEGIBLE, saldo 0 y ledger que cuadra")
	void ultimo_credito_concurrente() {
		Tenant tenant = fx.crearTenant(10, 1);
		PaseVentaYConsumoIT.declararPorPack(jdbc, tenant.ofertaId());
		long productoId = productoService.crear(tenant.actor(), tenant.consultorioId(),
				PaseVentaYConsumoIT.comando(tenant.ofertaId(), "Pase de una clase", 1)).id();
		long persona = tenant.persona(0);
		long paseId = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoId, persona, null)).pase().id();

		List<Callable<ResultadoDeAsistencia>> tareas = new ArrayList<>();
		for (int i = 0; i < CLASES; i++) {
			long clase = fx.programarClase(tenant, 10, 1 + i);
			fx.inscribir(tenant, clase, persona, false);
			asistenciaService.iniciar(tenant.actor(), tenant.consultorioId(), clase);
			long inscripcion = fx.inscripcionDe(clase, persona);
			tareas.add(() -> asistenciaService.registrar(tenant.actor(), tenant.consultorioId(), clase,
					new RegistrarAsistenciaCommand(
							inscripcion, ResultadoAsistencia.PRESENTE, OrigenAsistencia.MOSTRADOR, null, null)));
		}

		List<Desenlace<ResultadoDeAsistencia>> desenlaces = ActividadItFixture.enParalelo(tareas);

		assertThat(desenlaces).as("ninguna asistencia falla: la que pierde queda sin pase. %s", desenlaces)
				.noneMatch(Desenlace::fallo);
		assertThat(desenlaces.stream().filter(d -> d.valor().consumo().efecto() == Efecto.CONSUMIDO))
				.as("exactamente una toma el credito. %s", desenlaces)
				.hasSize(1);
		assertThat(desenlaces.stream().filter(d -> d.valor().consumo().efecto() == Efecto.SIN_PASE_ELEGIBLE))
				.hasSize(CLASES - 1);

		assertThat(jdbc.queryForObject(
				"SELECT creditos_disponibles FROM pase_servicio WHERE id = ?", Integer.class, paseId)).isZero();
		assertThat(jdbc.queryForObject(
				"SELECT estado FROM pase_servicio WHERE id = ?", String.class, paseId)).isEqualTo("AGOTADO");
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM movimiento_pase WHERE pase_id = ? AND tipo = 'CONSUMO'",
				Integer.class, paseId)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT SUM(cantidad) FROM movimiento_pase WHERE pase_id = ?", Integer.class, paseId))
				.as("el ledger explica el saldo")
				.isZero();
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM asistencia_actividad WHERE persona_id = ?", Integer.class, persona))
				.as("las cuatro asistencias quedaron registradas: asistencia y economia son independientes")
				.isEqualTo(CLASES);
	}
}
