package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.PresentacionItFixture.Tenant;
import com.akine.billing.application.FinanciadorPagoService;
import com.akine.billing.application.FinanciadorPagoView;
import com.akine.billing.application.IdempotencyKeyConflictException;
import com.akine.billing.application.PresentacionCommands;
import com.akine.billing.application.PresentacionService;
import com.akine.billing.application.PresentacionView;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.exception.CajaNoAbiertaException;
import com.akine.billing.domain.exception.ConsultorioNoAccesibleException;
import com.akine.billing.domain.exception.PresentacionNotAccessibleException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Escenario 40 de {@code docs/tests-diferidos.md}: el pago que genera caja, y el que no la toca.
 *
 * <p>RN-M21-002: el pago del financiador es el <b>unico</b> punto donde M21 toca la caja, y lo hace
 * en la misma transaccion que mueve el saldo del lote y registra el pago. Casi siempre llega por
 * transferencia —plata que fue a un banco, no al cajon—, y entonces el movimiento se asienta sin
 * jornada y sin afectar el arqueo. Si llega en efectivo rige la otra mitad de la regla de 07.03:
 * hace falta jornada abierta.
 *
 * <p>La atomicidad del conjunto pago + saldo + movimiento solo se observa cuando algo falla en el
 * medio, y eso exige una transaccion real: el saldo del lote se mueve <b>primero</b>, el pago se
 * inserta despues, y recien al asentar en caja aparece el {@code caja-no-abierta}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PagoDeFinanciadorCajaIT {

	@Autowired private PresentacionService presentacionService;
	@Autowired private FinanciadorPagoService pagoService;
	@Autowired private JdbcTemplate jdbc;

	private PresentacionItFixture fixture;

	@BeforeEach
	void armar() {
		fixture = new PresentacionItFixture(jdbc, presentacionService);
	}

	@Test
	@DisplayName("TRANSFERENCIA sin jornada abierta: asienta el movimiento sin jornada y sin arqueo")
	void transferencia_sin_jornada() {
		Tenant tenant = fixture.crearTenant();
		PresentacionView lote = fixture.loteConfirmado(tenant, "100000.00");

		FinanciadorPagoView pago = pagar(tenant, lote.id(), "40000.00", MedioDePago.TRANSFERENCIA, null);

		Map<String, Object> movimiento = movimientoDelPago(pago.id());
		assertThat(movimiento.get("id")).isEqualTo(pago.movimientoCajaId());
		assertThat(movimiento.get("jornada_caja_id"))
				.as("no hay jornada abierta y no hace falta: la plata fue a un banco")
				.isNull();
		assertThat(((Number) movimiento.get("afecta_arqueo")).intValue()).isZero();
		assertThat(movimiento.get("tipo")).isEqualTo("INGRESO");
		assertThat(movimiento.get("medio")).isEqualTo("TRANSFERENCIA");
		assertThat((BigDecimal) movimiento.get("importe")).isEqualByComparingTo("40000.00");

		assertThat(saldoDelLote(lote.id())).isEqualByComparingTo("60000.00");
		assertThat(cobradoDelLote(lote.id())).isEqualByComparingTo("40000.00");
	}

	@Test
	@DisplayName("TRANSFERENCIA con la caja abierta: el arqueo no se mueve")
	void transferencia_con_jornada_no_toca_el_arqueo() {
		Tenant tenant = fixture.crearTenant();
		long jornadaId = fixture.abrirJornada(tenant, "5000.00");
		PresentacionView lote = fixture.loteConfirmado(tenant, "100000.00");

		FinanciadorPagoView pago = pagar(tenant, lote.id(), "100000.00", MedioDePago.TRANSFERENCIA, null);

		assertThat(((Number) movimientoDelPago(pago.id()).get("afecta_arqueo")).intValue()).isZero();
		assertThat(saldoArqueo(jornadaId))
				.as("meter una transferencia en el arqueo garantizaria que el conteo nunca cuadre")
				.isEqualByComparingTo("5000.00");
		assertThat(saldoDelLote(lote.id())).isEqualByComparingTo("0.00");
	}

	@Test
	@DisplayName("EFECTIVO con la caja abierta: el movimiento cae en la jornada y suma al arqueo")
	void efectivo_con_jornada_mueve_el_arqueo() {
		Tenant tenant = fixture.crearTenant();
		long jornadaId = fixture.abrirJornada(tenant, "5000.00");
		PresentacionView lote = fixture.loteConfirmado(tenant, "30000.00");

		FinanciadorPagoView pago = pagar(tenant, lote.id(), "12000.00", MedioDePago.EFECTIVO, null);

		Map<String, Object> movimiento = movimientoDelPago(pago.id());
		assertThat(((Number) movimiento.get("jornada_caja_id")).longValue()).isEqualTo(jornadaId);
		assertThat(((Number) movimiento.get("afecta_arqueo")).intValue()).isEqualTo(1);
		assertThat(saldoArqueo(jornadaId)).isEqualByComparingTo("17000.00");
		assertThat(saldoDelLote(lote.id())).isEqualByComparingTo("18000.00");
	}

	@Test
	@DisplayName("EFECTIVO sin caja abierta: caja-no-abierta, y no queda pago, ni movimiento, ni saldo movido")
	void efectivo_sin_jornada_no_deja_nada_a_medias() {
		// El orden del servicio es: mover el saldo del lote, insertar el pago, asentar en caja. El
		// rechazo llega en el TERCER paso, con los dos primeros ya escritos en la transaccion. Lo
		// que se mide es que el rollback se lleve los dos.
		Tenant tenant = fixture.crearTenant();
		PresentacionView lote = fixture.loteConfirmado(tenant, "30000.00");

		assertThatThrownBy(() -> pagar(tenant, lote.id(), "12000.00", MedioDePago.EFECTIVO, null))
				.isInstanceOf(CajaNoAbiertaException.class);

		assertThat(saldoDelLote(lote.id())).as("el saldo del lote no se movio").isEqualByComparingTo("30000.00");
		assertThat(cobradoDelLote(lote.id())).isEqualByComparingTo("0.00");
		assertThat(contar("SELECT COUNT(*) FROM financiador_pago WHERE presentacion_id = ?", lote.id()))
				.as("no quedo un pago sin movimiento")
				.isZero();
		assertThat(contar("""
				SELECT COUNT(*) FROM movimiento_caja
				 WHERE organization_id = ? AND tipo_origen = 'PAGO_FINANCIADOR'
				""", tenant.organizationId()))
				.isZero();
	}

	@Test
	@DisplayName("el reintento con la misma idempotency-key no duplica ni el pago ni el movimiento")
	void el_reintento_no_duplica() {
		Tenant tenant = fixture.crearTenant();
		PresentacionView lote = fixture.loteConfirmado(tenant, "100000.00");
		String clave = "pago-fin-" + UUID.randomUUID();

		FinanciadorPagoView primero = pagar(tenant, lote.id(), "40000.00", MedioDePago.TRANSFERENCIA, clave);
		FinanciadorPagoView reintento = pagar(tenant, lote.id(), "40000", MedioDePago.TRANSFERENCIA, clave);

		assertThat(reintento.id()).isEqualTo(primero.id());
		assertThat(contar("SELECT COUNT(*) FROM financiador_pago WHERE presentacion_id = ?", lote.id()))
				.isEqualTo(1);
		assertThat(contar("""
				SELECT COUNT(*) FROM movimiento_caja
				 WHERE tipo_origen = 'PAGO_FINANCIADOR' AND referencia_origen = ?
				""", primero.id()))
				.isEqualTo(1);
		assertThat(saldoDelLote(lote.id()))
				.as("el saldo se movio una sola vez")
				.isEqualByComparingTo("60000.00");

		assertThatThrownBy(() -> pagar(tenant, lote.id(), "50000.00", MedioDePago.TRANSFERENCIA, clave))
				.as("la misma clave con otro contenido no es un reintento")
				.isInstanceOf(IdempotencyKeyConflictException.class);
		assertThat(saldoDelLote(lote.id())).isEqualByComparingTo("60000.00");
	}

	@Test
	@DisplayName("un actor del tenant B no ve, no paga y no concilia un lote de A: 404, nunca 403")
	void aislamiento_de_tenant() {
		Tenant a = fixture.crearTenant();
		Tenant b = fixture.crearTenant();
		PresentacionView lote = fixture.loteConfirmado(a, "30000.00");

		// Por la sede propia de B: el lote de A no existe ahi.
		esNoEncontrado(() -> presentacionService.detalle(b.actor(), b.consultorioId(), lote.id()),
				PresentacionNotAccessibleException.class);
		esNoEncontrado(() -> pagoService.registrar(b.actor(), b.consultorioId(), lote.id(),
						pago("30000.00", MedioDePago.TRANSFERENCIA, null)),
				PresentacionNotAccessibleException.class);
		esNoEncontrado(() -> pagoService.deLaPresentacion(b.actor(), b.consultorioId(), lote.id()),
				PresentacionNotAccessibleException.class);
		esNoEncontrado(() -> presentacionService.conciliar(b.actor(), b.consultorioId(), lote.id()),
				PresentacionNotAccessibleException.class);

		// Por la sede de A: la sede misma no existe para B.
		esNoEncontrado(() -> presentacionService.detalle(b.actor(), a.consultorioId(), lote.id()),
				ConsultorioNoAccesibleException.class);
		esNoEncontrado(() -> pagoService.registrar(b.actor(), a.consultorioId(), lote.id(),
						pago("30000.00", MedioDePago.TRANSFERENCIA, null)),
				ConsultorioNoAccesibleException.class);
		esNoEncontrado(() -> presentacionService.conciliar(b.actor(), a.consultorioId(), lote.id()),
				ConsultorioNoAccesibleException.class);

		assertThat(saldoDelLote(lote.id())).as("nada de A se movio").isEqualByComparingTo("30000.00");
		assertThat(contar("SELECT COUNT(*) FROM financiador_pago WHERE presentacion_id = ?", lote.id()))
				.isZero();
		assertThat(jdbc.queryForObject("SELECT estado FROM presentacion WHERE id = ?", String.class, lote.id()))
				.isEqualTo("PRESENTADA");
	}

	// =================================================================================

	private FinanciadorPagoView pagar(
			Tenant tenant, long presentacionId, String importe, MedioDePago medio, String clave) {
		return pagoService.registrar(tenant.actor(), tenant.consultorioId(), presentacionId,
				pago(importe, medio, clave));
	}

	private static PresentacionCommands.Pago pago(String importe, MedioDePago medio, String clave) {
		return new PresentacionCommands.Pago(
				new BigDecimal(importe), medio, LocalDate.now(), "TRF-0001", clave);
	}

	/**
	 * Las 404 son excepciones de "no accesible" y ninguna de ellas es un AccessDeniedException: un
	 * 403 confirmaria que el recurso existe en otro tenant.
	 */
	private static void esNoEncontrado(
			ThrowingCallable intento, Class<? extends RuntimeException> esperado) {
		assertThatThrownBy(intento)
				.isInstanceOf(esperado)
				.isNotInstanceOf(org.springframework.security.access.AccessDeniedException.class);
	}

	private Map<String, Object> movimientoDelPago(long pagoId) {
		return jdbc.queryForMap("""
				SELECT id, jornada_caja_id, afecta_arqueo, tipo, medio, importe
				  FROM movimiento_caja
				 WHERE tipo_origen = 'PAGO_FINANCIADOR' AND referencia_origen = ?
				""", pagoId);
	}

	private BigDecimal saldoDelLote(long presentacionId) {
		return jdbc.queryForObject(
				"SELECT saldo FROM presentacion WHERE id = ?", BigDecimal.class, presentacionId);
	}

	private BigDecimal cobradoDelLote(long presentacionId) {
		return jdbc.queryForObject(
				"SELECT total_cobrado FROM presentacion WHERE id = ?", BigDecimal.class, presentacionId);
	}

	private BigDecimal saldoArqueo(long jornadaId) {
		return jdbc.queryForObject(
				"SELECT saldo_arqueo FROM jornada_caja WHERE id = ?", BigDecimal.class, jornadaId);
	}

	private int contar(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}
}
