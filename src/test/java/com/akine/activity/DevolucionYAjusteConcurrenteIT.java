package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Desenlace;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.AjustarSaldoCommand;
import com.akine.activity.application.AjusteDePaseService;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.ConsumoDeCredito.Efecto;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.ProductoService;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.application.ResultadoDeAsistencia;
import com.akine.activity.application.VenderPaseCommand;
import com.akine.activity.application.VentaDePaseService;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.activity.domain.exception.TransicionDeClaseNoPermitidaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>El ledger de creditos con transacciones que se pelean de verdad</b> (AKINE-08.07, casos borde
 * del plan: "asistencia revertida", "ajuste y consumo simultaneos").
 *
 * <p>Lo que se afirma no es quien gana —eso lo decide InnoDB— sino que, gane quien gane, ningun
 * credito se pierde ni se devuelve dos veces y el ledger explica cada saldo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class DevolucionYAjusteConcurrenteIT {

	private static final int PERSONAS = 6;

	@Autowired private ProductoService productoService;
	@Autowired private VentaDePaseService ventaService;
	@Autowired private AjusteDePaseService ajusteService;
	@Autowired private InscripcionService inscripcionService;
	@Autowired private AsistenciaService asistenciaService;
	@Autowired private ClaseService claseService;
	@Autowired private JdbcTemplate jdbc;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	@Test
	@DisplayName("Cancelar la clase mientras se toma lista: cada credito consumido vuelve, ninguno dos veces, y nada consume despues de cancelar")
	void cancelacion_contra_asistencias() {
		Tenant tenant = fx.crearTenant(10, PERSONAS);
		PaseVentaYConsumoIT.declararPorPack(jdbc, tenant.ofertaId());
		long productoId = productoService.crear(tenant.actor(), tenant.consultorioId(),
				PaseVentaYConsumoIT.comando(tenant.ofertaId(), "Pack", 8)).id();
		List<Long> pases = new ArrayList<>();
		for (Long persona : tenant.personas()) {
			pases.add(ventaService.vender(tenant.actor(), tenant.consultorioId(),
					new VenderPaseCommand(productoId, persona, null)).pase().id());
		}
		long clase = fx.programarClase(tenant, 10, 1);
		List<Long> inscripciones = new ArrayList<>();
		for (Long persona : tenant.personas()) {
			fx.inscribir(tenant, clase, persona, false);
			inscripciones.add(fx.inscripcionDe(clase, persona));
		}
		asistenciaService.iniciar(tenant.actor(), tenant.consultorioId(), clase);

		List<Callable<Object>> tareas = new ArrayList<>();
		for (Long inscripcion : inscripciones) {
			tareas.add(() -> asistenciaService.registrar(tenant.actor(), tenant.consultorioId(), clase,
					new RegistrarAsistenciaCommand(
							inscripcion, ResultadoAsistencia.PRESENTE, OrigenAsistencia.MOSTRADOR, null, null)));
		}
		tareas.add(() -> claseService.cancelar(tenant.actor(), tenant.consultorioId(), clase, "Corte de luz"));

		List<Desenlace<Object>> desenlaces = ActividadItFixture.enParalelo(tareas);

		assertThat(desenlaces.get(PERSONAS).fallo()).as("la cancelacion no falla. %s", desenlaces).isFalse();
		for (int i = 0; i < PERSONAS; i++) {
			Desenlace<Object> d = desenlaces.get(i);
			if (d.fallo()) {
				assertThat(ActividadItFixture.causaEs(d.error(), TransicionDeClaseNoPermitidaException.class))
						.as("una asistencia solo pierde contra la clase ya cancelada, nunca por deadlock. %s", d)
						.isTrue();
			} else {
				assertThat(((ResultadoDeAsistencia) d.valor()).consumo().efecto()).isEqualTo(Efecto.CONSUMIDO);
			}
		}
		for (Long pase : pases) {
			assertThat(saldo(pase)).as("el pase %s recupero su credito", pase).isEqualTo(8);
			assertThat(sumaDelLedger(pase)).as("el ledger del pase %s explica el saldo", pase).isEqualTo(8);
			assertThat(contar("SELECT COUNT(*) FROM movimiento_pase WHERE pase_id = ? AND tipo = 'CONSUMO'", pase))
					.as("una devolucion por cada consumo, ni mas ni menos")
					.isEqualTo(contar("SELECT COUNT(*) FROM movimiento_pase WHERE pase_id = ? "
							+ "AND origen_tipo = 'CANCELACION'", pase));
		}
	}

	@Test
	@DisplayName("Ajuste manual contra consumo del mismo pase: gana uno, el otro no pisa el saldo, y el ledger cuadra")
	void ajuste_contra_consumo() {
		Tenant tenant = fx.crearTenant(10, 1);
		PaseVentaYConsumoIT.declararPorPack(jdbc, tenant.ofertaId());
		long productoId = productoService.crear(tenant.actor(), tenant.consultorioId(),
				PaseVentaYConsumoIT.comando(tenant.ofertaId(), "Pack de dos", 2)).id();
		long persona = tenant.persona(0);
		long pase = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoId, persona, null)).pase().id();
		long clase = fx.programarClase(tenant, 10, 1);
		fx.inscribir(tenant, clase, persona, false);
		asistenciaService.iniciar(tenant.actor(), tenant.consultorioId(), clase);
		long inscripcion = fx.inscripcionDe(clase, persona);

		List<Callable<Object>> carrera = List.of(
				() -> asistenciaService.registrar(tenant.actor(), tenant.consultorioId(), clase,
						new RegistrarAsistenciaCommand(
								inscripcion, ResultadoAsistencia.PRESENTE, OrigenAsistencia.MOSTRADOR, null, null)),
				() -> ajusteService.ajustar(tenant.actor(), tenant.consultorioId(), pase,
						new AjustarSaldoCommand(-2, "Baja del taller", 0), true));
		List<Desenlace<Object>> desenlaces = ActividadItFixture.enParalelo(carrera);

		Desenlace<Object> asistencia = desenlaces.get(0);
		Desenlace<Object> ajuste = desenlaces.get(1);
		assertThat(asistencia.fallo()).as("la asistencia se registra siempre. %s", desenlaces).isFalse();
		Efecto efecto = ((ResultadoDeAsistencia) asistencia.valor()).consumo().efecto();
		if (ajuste.fallo()) {
			// El consumo llego primero y movio la version: el ajuste con la vieja no pisa nada.
			assertThat(ActividadItFixture.causaEs(ajuste.error(), OptimisticLockingFailureException.class))
					.as("%s", desenlaces).isTrue();
			assertThat(efecto).isEqualTo(Efecto.CONSUMIDO);
			assertThat(saldo(pase)).isEqualTo(1);
		} else {
			// El ajuste llego primero y dejo el pase AGOTADO: la asistencia queda sin pase.
			assertThat(efecto).isEqualTo(Efecto.SIN_PASE_ELEGIBLE);
			assertThat(saldo(pase)).isZero();
		}
		assertThat(sumaDelLedger(pase)).isEqualTo(saldo(pase));
	}

	private int saldo(long paseId) {
		return jdbc.queryForObject("SELECT creditos_disponibles FROM pase_servicio WHERE id = ?", Integer.class, paseId);
	}

	private int sumaDelLedger(long paseId) {
		return jdbc.queryForObject("SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_pase WHERE pase_id = ?",
				Integer.class, paseId);
	}

	private int contar(String sql, Object arg) {
		return jdbc.queryForObject(sql, Integer.class, arg);
	}
}
