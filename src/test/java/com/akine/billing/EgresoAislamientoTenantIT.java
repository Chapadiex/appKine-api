package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.EgresoItFixture.Tenant;
import com.akine.billing.application.CajaService;
import com.akine.billing.application.EgresoCommand;
import com.akine.billing.application.EgresoService;
import com.akine.billing.application.EgresoView;
import com.akine.billing.application.JornadaCajaView;
import com.akine.billing.application.OperatingActor;
import com.akine.billing.application.PagoEgresoCommand;
import com.akine.billing.application.PagoEgresoService;
import com.akine.billing.application.PagoEgresoView;
import com.akine.billing.domain.CategoriaEgreso;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.TipoBeneficiario;
import com.akine.billing.domain.exception.ConsultorioNoAccesibleException;
import com.akine.billing.domain.exception.EgresoNotAccessibleException;
import com.akine.billing.domain.exception.PagoEgresoNotAccessibleException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Escenario 48 de {@code docs/tests-diferidos.md} (AKINE-07.05): <b>un tenant no toca los egresos
 * de otro, y la respuesta es 404, nunca 403</b>.
 *
 * <h2>Por que necesita MySQL</h2>
 *
 * <p>El aislamiento lo hacen las consultas con {@code organization_id} en el {@code WHERE}: un
 * mock del repositorio devuelve lo que se le diga y no puede mostrar que una consulta se olvido
 * del filtro. Hacen falta dos tenants de verdad en la misma base, con ids que se pisan. Es lo que
 * {@code AGENT.md} §6 exige en cada IT.
 *
 * <p>Se prueban las dos formas del ataque: el actor de B pidiendo el egreso de A <b>por su propia
 * sede</b> (el egreso no existe en su alcance → {@code EgresoNotAccessibleException}) y <b>por la
 * sede de A</b> (la sede no existe en su tenant → {@code ConsultorioNoAccesibleException}). Los
 * dos mapean a 404 en {@code BillingProblemHandler}; ninguno puede ser
 * {@link AccessDeniedException}, que es 403 y confirma que el recurso existe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class EgresoAislamientoTenantIT {

	@Autowired private JdbcTemplate jdbc;
	@Autowired private CajaService cajaService;
	@Autowired private EgresoService egresoService;
	@Autowired private PagoEgresoService pagoService;

	private EgresoItFixture fixture;

	@BeforeEach
	void preparar() {
		fixture = new EgresoItFixture(jdbc, cajaService, egresoService, pagoService);
	}

	@Test
	@DisplayName("48 · un actor de otro tenant no lista, ve, edita, confirma, anula ni paga un egreso ajeno, ni anula su pago: 404")
	void otro_tenant_no_alcanza_los_egresos_ajenos() {
		Tenant a = fixture.crearTenant();
		JornadaCajaView cajaA = fixture.abrirCaja(a, "100000.00");
		EgresoView borradorA = fixture.registrar(a, "15000.00");
		EgresoView confirmadoA = fixture.registrarYConfirmar(a, "40000.00");
		PagoEgresoView pagoA = fixture.pagarEnEfectivo(a, confirmadoA.id(), "10000.00");

		Tenant b = fixture.crearTenant();
		fixture.abrirCaja(b, "100000.00");
		// B tiene su propio egreso y su propio pago: sirven de ruta legitima para pedir, por
		// debajo, el pago de A. Es el ataque mas fino, porque la primera validacion pasa.
		EgresoView propioB = fixture.registrarYConfirmar(b, "5000.00");

		Map<String, Object> egresoAAntes = filaEgreso(confirmadoA.id());
		Map<String, Object> borradorAAntes = filaEgreso(borradorA.id());
		BigDecimal cajaAAntes = fixture.saldoArqueo(cajaA.id());

		OperatingActor actorB = b.actor();
		long sedeB = b.consultorioId();
		long sedeA = a.consultorioId();

		// --- Por la propia sede de B: el egreso de A no existe en su alcance ---
		esNoEncontrado(() -> egresoService.ver(actorB, sedeB, confirmadoA.id()), EgresoNotAccessibleException.class);
		esNoEncontrado(() -> egresoService.editar(actorB, sedeB, borradorA.id(), comando("15000.00")),
				EgresoNotAccessibleException.class);
		esNoEncontrado(() -> egresoService.confirmar(actorB, sedeB, borradorA.id()), EgresoNotAccessibleException.class);
		esNoEncontrado(() -> egresoService.anular(actorB, sedeB, borradorA.id(), "intrusion"),
				EgresoNotAccessibleException.class);
		esNoEncontrado(() -> pagoService.pagar(actorB, sedeB, confirmadoA.id(), pagoEfectivo("1000.00")),
				EgresoNotAccessibleException.class);
		esNoEncontrado(() -> pagoService.anularPago(actorB, sedeB, confirmadoA.id(), pagoA.id(), "intrusion"),
				EgresoNotAccessibleException.class);
		esNoEncontrado(() -> pagoService.anularPago(actorB, sedeB, propioB.id(), pagoA.id(), "intrusion"),
				PagoEgresoNotAccessibleException.class);

		assertThat(egresoService.buscar(actorB, sedeB, null, null, null, null, null, 100, 0))
				.extracting(EgresoView::id)
				.as("el listado de B trae solo lo de B")
				.containsExactly(propioB.id());

		// --- Por la sede de A: la sede no existe en el tenant de B ---
		esNoEncontrado(() -> egresoService.buscar(actorB, sedeA, null, null, null, null, null, 100, 0),
				ConsultorioNoAccesibleException.class);
		esNoEncontrado(() -> egresoService.ver(actorB, sedeA, confirmadoA.id()), ConsultorioNoAccesibleException.class);
		esNoEncontrado(() -> egresoService.editar(actorB, sedeA, borradorA.id(), comando("15000.00")),
				ConsultorioNoAccesibleException.class);
		esNoEncontrado(() -> egresoService.confirmar(actorB, sedeA, borradorA.id()), ConsultorioNoAccesibleException.class);
		esNoEncontrado(() -> egresoService.anular(actorB, sedeA, borradorA.id(), "intrusion"),
				ConsultorioNoAccesibleException.class);
		esNoEncontrado(() -> pagoService.pagar(actorB, sedeA, confirmadoA.id(), pagoEfectivo("1000.00")),
				ConsultorioNoAccesibleException.class);
		esNoEncontrado(() -> pagoService.anularPago(actorB, sedeA, confirmadoA.id(), pagoA.id(), "intrusion"),
				ConsultorioNoAccesibleException.class);

		// Y nada de A se movio: ni el egreso, ni el borrador, ni el pago, ni el cajon.
		assertThat(filaEgreso(confirmadoA.id())).isEqualTo(egresoAAntes);
		assertThat(filaEgreso(borradorA.id())).isEqualTo(borradorAAntes);
		assertThat(jdbc.queryForObject("SELECT estado FROM pago_egreso WHERE id = ?", String.class, pagoA.id()))
				.isEqualTo("CONFIRMADO");
		assertThat(fixture.saldoArqueo(cajaA.id())).isEqualByComparingTo(cajaAAntes);
	}

	// =================================================================================

	private static void esNoEncontrado(ThrowingCallable intento, Class<? extends RuntimeException> esperado) {
		assertThatThrownBy(intento)
				.as("404 (%s), nunca 403", esperado.getSimpleName())
				.isInstanceOf(esperado)
				.isNotInstanceOf(AccessDeniedException.class);
	}

	private Map<String, Object> filaEgreso(long egresoId) {
		return jdbc.queryForMap("""
				SELECT estado, saldo_pendiente, importe_total, concepto, version
				  FROM egreso WHERE id = ?
				""", egresoId);
	}

	private static EgresoCommand comando(String importe) {
		return new EgresoCommand(
				CategoriaEgreso.OTRO, TipoBeneficiario.EXTERNO, null,
				"Intruso", "99-99999999-9", null, null, "Edicion ajena",
				new BigDecimal(importe), EgresoItFixture.MONEDA,
				"FACTURA_C", "9999-00000001", LocalDate.now(), null);
	}

	private static PagoEgresoCommand pagoEfectivo(String importe) {
		return new PagoEgresoCommand(new BigDecimal(importe), MedioDePago.EFECTIVO, null, null);
	}
}
