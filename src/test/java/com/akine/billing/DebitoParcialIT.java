package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.PresentacionItFixture.Tenant;
import com.akine.billing.application.FinanciadorPagoService;
import com.akine.billing.application.HallazgoDeValidacion;
import com.akine.billing.application.ObligacionView;
import com.akine.billing.application.PresentacionCommands;
import com.akine.billing.application.PresentacionService;
import com.akine.billing.application.PresentacionView;
import com.akine.billing.application.ValidacionDePresentacion;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.exception.ObligacionYaPresentadaException;
import com.akine.billing.domain.exception.PresentacionConHallazgosException;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AKINE-07.04b contra MySQL: las dos decisiones recomendadas (U-11 y U-28) de punta a punta, por
 * los servicios, con los UPDATE nativos, la columna generada de V95 y la lectura de {@code person}.
 *
 * <p>Diseno: {@code docs/diseno/AKINE-07.04b-reparos-y-debito-parcial.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class DebitoParcialIT {

	@Autowired private PresentacionService presentacionService;
	@Autowired private FinanciadorPagoService pagoService;
	@Autowired private JdbcTemplate jdbc;

	private PresentacionItFixture fixture;
	private Tenant tenant;

	@BeforeEach
	void armar() {
		fixture = new PresentacionItFixture(jdbc, presentacionService);
		tenant = fixture.crearTenant();
	}

	// =================================================================================
	// U-11 — debito parcial
	// =================================================================================

	@Test
	@DisplayName("6000 presentados, 1000 debitados, 5000 pagados: conciliar salda 5000 y deja 1000 re-presentables")
	void conciliar_salda_lo_cobrado_y_deja_pendiente_lo_debitado() {
		PresentacionView lote = fixture.loteConfirmado(tenant, "6000.00");
		long itemId = lote.items().getFirst().id();
		long obligacionId = lote.items().getFirst().obligacionId();
		long versionAntes = ((Number) obligacion(obligacionId).get("version")).longValue();

		debitar(lote.id(), itemId, "1000.00");

		// Mientras el lote no concilia, la obligacion sigue tomada: otro lote no la puede reclamar
		// por 6000 mientras este va a cobrar 5000 de ella.
		PresentacionView otro = fixture.borrador(tenant, tenant.financiadorId());
		assertThatThrownBy(() -> presentacionService.agregarItem(
				tenant.actor(), tenant.consultorioId(), otro.id(), obligacionId))
				.isInstanceOf(ObligacionYaPresentadaException.class);
		assertThat(elegibles()).extracting(ObligacionView::id).doesNotContain(obligacionId);

		pagar(lote.id(), "5000.00");
		presentacionService.conciliar(tenant.actor(), tenant.consultorioId(), lote.id());

		Map<String, Object> item = item(itemId);
		assertThat(item.get("estado")).isEqualTo("ACEPTADO_PARCIAL");
		assertThat(item.get("ocupa_marca")).as("ACEPTADO_PARCIAL libera la obligacion").isNull();

		Map<String, Object> deuda = obligacion(obligacionId);
		assertThat((BigDecimal) deuda.get("saldo")).isEqualByComparingTo("1000.00");
		assertThat(deuda.get("estado")).isEqualTo("PARCIAL");
		assertThat((BigDecimal) deuda.get("importe_original")).isEqualByComparingTo("6000.00");
		assertThat(((Number) deuda.get("version")).longValue())
				.as("el descuento es un UPDATE nativo que mueve version")
				.isGreaterThan(versionAntes);

		// Invariante de la conciliacion: lo aceptado por item suma lo cobrado del lote.
		Map<String, Object> fila = lote(lote.id());
		assertThat((BigDecimal) fila.get("saldo")).isEqualByComparingTo("0.00");
		assertThat((BigDecimal) fila.get("total_cobrado")).isEqualByComparingTo(
				((BigDecimal) item.get("importe_presentado"))
						.subtract((BigDecimal) item.get("importe_debitado")));

		// Lo debitado es re-presentable, y un lote nuevo lo reclama por el saldo, no por el total.
		assertThat(elegibles()).extracting(ObligacionView::id).contains(obligacionId);
		presentacionService.agregarItem(tenant.actor(), tenant.consultorioId(), otro.id(), obligacionId);
		PresentacionView represento =
				presentacionService.detalle(tenant.actor(), tenant.consultorioId(), otro.id());
		assertThat(represento.items()).singleElement()
				.satisfies(nuevo -> assertThat(nuevo.importePresentado()).isEqualByComparingTo("1000.00"));
		assertThat(represento.totalPresentado()).isEqualByComparingTo("1000.00");
	}

	@Test
	@DisplayName("un debito por el total sigue siendo DEBITADO: libera en el acto y conciliar no salda nada")
	void el_debito_total_no_cambia() {
		PresentacionView lote = fixture.loteConfirmado(tenant, "6000.00");
		long itemId = lote.items().getFirst().id();
		long obligacionId = lote.items().getFirst().obligacionId();

		debitar(lote.id(), itemId, "6000.00");
		assertThat(item(itemId).get("estado")).isEqualTo("DEBITADO");
		assertThat(item(itemId).get("ocupa_marca")).isNull();

		presentacionService.conciliar(tenant.actor(), tenant.consultorioId(), lote.id());

		Map<String, Object> deuda = obligacion(obligacionId);
		assertThat((BigDecimal) deuda.get("saldo")).isEqualByComparingTo("6000.00");
		assertThat(deuda.get("estado")).isEqualTo("PENDIENTE");
		assertThat(item(itemId).get("estado")).isEqualTo("DEBITADO");
	}

	// =================================================================================
	// U-28 — RF-M21-003
	// =================================================================================

	@Test
	@DisplayName("sin orden ni autorizacion exigidas el lote no se confirma; cargada la orden queda solo la autorizacion")
	void orden_y_autorizacion_son_reparos() {
		long obligacionId = fixture.obligacionDeFinanciador(tenant, tenant.financiadorId(), "8500.00");
		exigir(obligacionId, true, true, false, false);
		PresentacionView borrador = fixture.borradorCon(tenant, tenant.financiadorId(), List.of(obligacionId));

		assertThat(hallazgos(validar(borrador.id())))
				.containsExactly(HallazgoDeValidacion.SIN_ORDEN, HallazgoDeValidacion.SIN_AUTORIZACION);
		assertThatThrownBy(() -> presentacionService.confirmar(
				tenant.actor(), tenant.consultorioId(), borrador.id()))
				.isInstanceOf(PresentacionConHallazgosException.class);
		assertThat(jdbc.queryForObject("SELECT numero FROM presentacion WHERE id = ?",
				Integer.class, borrador.id()))
				.as("un lote con reparos no consume numero de la serie").isNull();

		insertarOrden(tenant.personaId());

		ValidacionDePresentacion despues = validar(borrador.id());
		assertThat(hallazgos(despues)).containsExactly(HallazgoDeValidacion.SIN_AUTORIZACION);
		assertThat(despues.confirmable()).isFalse();
	}

	@Test
	@DisplayName("la credencial vencida el dia de la prestacion avisa y el lote se confirma igual")
	void la_credencial_es_aviso() {
		long obligacionId = fixture.obligacionDeFinanciador(tenant, tenant.financiadorId(), "8500.00");
		exigir(obligacionId, false, false, true, true);
		PresentacionView borrador = fixture.borradorCon(tenant, tenant.financiadorId(), List.of(obligacionId));

		ValidacionDePresentacion validacion = validar(borrador.id());
		assertThat(validacion.confirmable()).isTrue();
		assertThat(validacion.hallazgos()).isEmpty();
		assertThat(validacion.avisos()).extracting(ValidacionDePresentacion.Reparo::hallazgo)
				.containsExactly(HallazgoDeValidacion.CREDENCIAL_FALTANTE);

		PresentacionView confirmada = presentacionService.confirmar(
				tenant.actor(), tenant.consultorioId(), borrador.id());
		assertThat(confirmada.numero()).isNotNull();
	}

	// =================================================================================

	private void debitar(long presentacionId, long itemId, String importe) {
		presentacionService.debitar(tenant.actor(), tenant.consultorioId(), presentacionId, itemId,
				new PresentacionCommands.Debito(new BigDecimal(importe), "Valor por encima del nomenclador"));
	}

	private void pagar(long presentacionId, String importe) {
		pagoService.registrar(tenant.actor(), tenant.consultorioId(), presentacionId,
				new PresentacionCommands.Pago(new BigDecimal(importe), MedioDePago.TRANSFERENCIA,
						LocalDate.now(), "TRF-PARCIAL", null));
	}

	private ValidacionDePresentacion validar(long presentacionId) {
		return presentacionService.validar(tenant.actor(), tenant.consultorioId(), presentacionId);
	}

	private static List<HallazgoDeValidacion> hallazgos(ValidacionDePresentacion validacion) {
		return validacion.hallazgos().stream().map(ValidacionDePresentacion.Reparo::hallazgo).toList();
	}

	private List<ObligacionView> elegibles() {
		LocalDate hoy = LocalDate.now(ZoneId.of(PresentacionItFixture.ZONA));
		return presentacionService.elegibles(tenant.actor(), tenant.consultorioId(),
				tenant.financiadorId(), hoy.minusDays(30), hoy.plusDays(30), 200, 0);
	}

	/** Lo que el convenio exigia, congelado. Las columnas de snapshot son copia: se ajustan por SQL. */
	private void exigir(long obligacionId, boolean orden, boolean autorizacion, boolean credencial,
			boolean credencialVencida) {
		jdbc.update("""
				UPDATE obligacion
				   SET snapshot_requeria_orden = ?, snapshot_requeria_autorizacion = ?,
				       snapshot_requeria_credencial = ?, snapshot_credencial_vencida = ?
				 WHERE id = ?
				""", orden, autorizacion, credencial, credencialVencida, obligacionId);
	}

	/** Una orden sin cobertura declarada —vale para cualquiera—, vigente desde hace cinco dias. */
	private void insertarOrden(long personaId) {
		LocalDate desde = LocalDate.now(ZoneId.of(PresentacionItFixture.ZONA)).minusDays(5);
		jdbc.update("""
				INSERT INTO orden_medica (organization_id, persona_id, consultorio_id,
				                          profesional_emisor, fecha_emision, vigencia_desde,
				                          active, version, created_at, updated_at)
				VALUES (?, ?, ?, 'Dra. Sintetica', ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", tenant.organizationId(), personaId, tenant.consultorioId(),
				java.sql.Date.valueOf(desde), java.sql.Date.valueOf(desde));
	}

	private Map<String, Object> obligacion(long obligacionId) {
		return jdbc.queryForMap(
				"SELECT saldo, estado, importe_original, version FROM obligacion WHERE id = ?",
				obligacionId);
	}

	private Map<String, Object> lote(long presentacionId) {
		return jdbc.queryForMap(
				"SELECT saldo, total_presentado, total_debitado, total_cobrado FROM presentacion WHERE id = ?",
				presentacionId);
	}

	private Map<String, Object> item(long itemId) {
		return jdbc.queryForMap("""
				SELECT estado, importe_presentado, importe_debitado, ocupa_marca
				  FROM presentacion_item WHERE id = ?
				""", itemId);
	}
}
