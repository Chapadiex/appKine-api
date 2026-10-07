package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.PresentacionItFixture.Tenant;
import com.akine.billing.application.PresentacionService;
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
import java.util.List;
import java.util.Map;

import static com.akine.billing.MigracionItSoporte.ahora;
import static com.akine.billing.MigracionItSoporte.con;
import static com.akine.billing.MigracionItSoporte.entra;
import static com.akine.billing.MigracionItSoporte.fila;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenario 36 de {@code docs/tests-diferidos.md}: {@code V56} contra el motor.
 *
 * <ul>
 *   <li>Los dos {@code ALTER}: el {@code DROP CHECK} + {@code ADD} de
 *       {@code ck_movimiento_caja_tipo_origen} —la primera vez que el repositorio reemplazo un CHECK
 *       desde una migracion posterior, patron que despues repitieron {@code V57} y {@code V69}— y
 *       los dos CHECK de {@code obligacion.financiador_id}, en sus dos direcciones.</li>
 *   <li>{@code ocupa_marca} generada STORED: 1 para {@code INCLUIDO}/{@code ACEPTADO}, NULL para
 *       {@code DEBITADO}/{@code ANULADO} — y por eso un debito libera la obligacion.</li>
 *   <li>{@code ck_presentacion_saldo_cuadra}, {@code ck_presentacion_confirmacion_completa} y
 *       {@code uk_presentacion_factura} con varios NULL.</li>
 * </ul>
 *
 * <p>Las filas se insertan DIRECTO, sin servicio, salvo la obligacion del financiador valida, que
 * la arma {@link PresentacionItFixture} respetando {@code V77}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PresentacionesMigrationIT {

	@Autowired private PresentacionService presentacionService;
	@Autowired private JdbcTemplate jdbc;

	private MigracionItSoporte db;
	private PresentacionItFixture fixture;
	private Tenant tenant;

	@BeforeEach
	void armar() {
		db = new MigracionItSoporte(jdbc);
		fixture = new PresentacionItFixture(jdbc, presentacionService);
		tenant = fixture.crearTenant();
	}

	// =================================================================================
	// Los dos ALTER
	// =================================================================================

	@Test
	@DisplayName("el CHECK de tipo_origen reemplazado existe una sola vez y sigue admitiendo los valores viejos")
	void el_check_reemplazado_quedo_uno_solo() {
		assertThat(db.checksLlamados("ck_movimiento_caja_tipo_origen")).isEqualTo(1);
		assertThat(db.clausulaDe("ck_movimiento_caja_tipo_origen")).contains("PAGO_FINANCIADOR");

		// Los tres de V54, el que agrego V56 y los que sumaron V57 y V69 sobre el mismo patron.
		for (String origen : List.of("COBRO", "MANUAL", "PAGO_FINANCIADOR", "PAGO_EGRESO", "REINTEGRO")) {
			entra(() -> db.insertar("movimiento_caja", movimiento(origen)));
		}
		db.violaCheck("ck_movimiento_caja_tipo_origen",
				() -> db.insertar("movimiento_caja", movimiento("AJUSTE")));
	}

	@Test
	@DisplayName("FINANCIADOR sin financiador_id no entra; PACIENTE con financiador_id tampoco")
	void el_financiador_de_la_obligacion_va_en_las_dos_direcciones() {
		db.violaCheck("ck_obligacion_financiador_presente",
				() -> obligacionDeFinanciadorSinFinanciador());
		db.violaCheck("ck_obligacion_financiador_solo_de_financiador",
				() -> db.insertar("obligacion", obligacionDelPaciente(tenant.financiadorId())));

		entra(() -> db.insertar("obligacion", obligacionDelPaciente(null)));
		entra(() -> fixture.obligacionDeFinanciador(tenant, tenant.financiadorId(), "8500.00"));
	}

	// =================================================================================
	// presentacion_item.ocupa_marca
	// =================================================================================

	@Test
	@DisplayName("ocupa_marca es generada STORED: 1 en INCLUIDO y ACEPTADO, NULL en DEBITADO y ANULADO")
	void ocupa_marca_es_generada() {
		db.esGeneradaStored("presentacion_item", "ocupa_marca", "INCLUIDO");
		long lote = db.insertar("presentacion", borrador());

		assertThat(ocupa(db.insertar("presentacion_item", item(lote, obligacion(), "INCLUIDO")))).isEqualTo(1);
		assertThat(ocupa(db.insertar("presentacion_item", item(lote, obligacion(), "ACEPTADO")))).isEqualTo(1);
		assertThat(ocupa(db.insertar("presentacion_item", debitado(lote, obligacion())))).isNull();
		assertThat(ocupa(db.insertar("presentacion_item", item(lote, obligacion(), "ANULADO")))).isNull();
	}

	@Test
	@DisplayName("la misma obligacion viva en dos lotes choca; debitada en uno, entra en otro")
	void el_debito_libera_la_obligacion() {
		long loteA = db.insertar("presentacion", borrador());
		long loteB = db.insertar("presentacion", borrador());
		long loteC = db.insertar("presentacion", borrador());
		long obligacion = obligacion();

		db.insertar("presentacion_item", debitado(loteA, obligacion));
		entra(() -> db.insertar("presentacion_item", item(loteB, obligacion, "INCLUIDO")));
		db.violaUnique("uk_presentacion_item_ocupa",
				() -> db.insertar("presentacion_item", item(loteC, obligacion, "INCLUIDO")));
	}

	@Test
	@DisplayName("DEBITADO exige motivo, actor, instante e importe; el debito no supera lo presentado")
	void el_debito_va_completo_y_acotado() {
		long lote = db.insertar("presentacion", borrador());
		db.violaCheck("ck_presentacion_item_debito_completo", () -> db.insertar("presentacion_item",
				con(debitado(lote, obligacion()), "motivo_debito", null)));
		db.violaCheck("ck_presentacion_item_debito_acotado", () -> db.insertar("presentacion_item",
				con(debitado(lote, obligacion()), "importe_debitado", new BigDecimal("8500.01"))));
	}

	// =================================================================================
	// presentacion
	// =================================================================================

	@Test
	@DisplayName("ck_presentacion_saldo_cuadra rechaza un saldo que no es presentado - debitado - cobrado")
	void el_saldo_cuadra() {
		db.violaCheck("ck_presentacion_saldo_cuadra", () -> db.insertar("presentacion",
				importes(borrador(), "100000.00", "15000.00", "40000.00", "50000.00")));
		entra(() -> db.insertar("presentacion",
				importes(borrador(), "100000.00", "15000.00", "40000.00", "45000.00")));
		db.violaCheck("ck_presentacion_saldo", () -> db.insertar("presentacion",
				importes(borrador(), "100000.00", "15000.00", "90000.00", "-5000.00")));
	}

	@Test
	@DisplayName("un BORRADOR con numero no entra, ni una PRESENTADA sin el")
	void la_confirmacion_va_completa() {
		db.violaCheck("ck_presentacion_confirmacion_completa",
				() -> db.insertar("presentacion", con(borrador(), "numero", 1)));
		db.violaCheck("ck_presentacion_confirmacion_completa",
				() -> db.insertar("presentacion", con(presentada(1), "numero", null)));
		db.violaCheck("ck_presentacion_confirmacion_completa",
				() -> db.insertar("presentacion", con(presentada(1), "confirmada_en", null)));

		entra(() -> db.insertar("presentacion", presentada(1)));
	}

	@Test
	@DisplayName("uk_presentacion_factura admite varios NULL y rechaza la misma factura dos veces")
	void la_factura_es_unica_por_financiador() {
		entra(() -> db.insertar("presentacion", presentada(1)));
		entra(() -> db.insertar("presentacion", presentada(2)));

		db.insertar("presentacion", facturada(3, "A-0001-00000042"));
		db.violaUnique("uk_presentacion_factura",
				() -> db.insertar("presentacion", facturada(4, "A-0001-00000042")));
		entra(() -> db.insertar("presentacion", facturada(5, "A-0001-00000043")));
	}

	@Test
	@DisplayName("una factura a medias no entra")
	void la_factura_va_completa() {
		db.violaCheck("ck_presentacion_factura_completa", () -> db.insertar("presentacion",
				con(facturada(1, "A-0001-00000099"), "factura_fecha", null)));
	}

	// =================================================================================
	// Filas sinteticas
	// =================================================================================

	private Map<String, Object> movimiento(String tipoOrigen) {
		return fila(
				"organization_id", tenant.organizationId(),
				"consultorio_id", tenant.consultorioId(),
				"fecha_negocio", LocalDate.now(),
				"tipo", "INGRESO",
				"medio", "TRANSFERENCIA",
				"importe", new BigDecimal("100.00"),
				"moneda", "ARS",
				"concepto", "Movimiento sintetico",
				"tipo_origen", tipoOrigen,
				// Una referencia distinta por fila: uk_movimiento_caja_origen.
				"referencia_origen", System.nanoTime(),
				"registrado_en", ahora(),
				"registrado_por_cuenta_id", tenant.cuentaId());
	}

	private long obligacion() {
		return fixture.obligacionDeFinanciador(tenant, tenant.financiadorId(), "8500.00");
	}

	/** La obligacion valida del fixture con financiador_id NULL: el unico CHECK que rompe es V56. */
	private void obligacionDeFinanciadorSinFinanciador() {
		long valida = obligacion();
		jdbc.update("UPDATE obligacion SET financiador_id = NULL WHERE id = ?", valida);
	}

	/** Una deuda PARTICULAR del paciente, sin snapshot de convenio: la forma de V36 + V64 + V77. */
	private Map<String, Object> obligacionDelPaciente(Long financiadorId) {
		return fila(
				"organization_id", tenant.organizationId(),
				"consultorio_id", tenant.consultorioId(),
				"sesion_id", sesion(),
				"persona_id", tenant.personaId(),
				"responsable", "PACIENTE",
				"financiador_id", financiadorId,
				"importe_original", new BigDecimal("8500.00"),
				"saldo", new BigDecimal("8500.00"),
				"moneda", "ARS",
				"estado", "PENDIENTE",
				"oferta_id", tenant.ofertaId(),
				"snapshot_nombre", "Sesion sintetica",
				"snapshot_precio", new BigDecimal("8500.00"),
				"devengada_en", ahora());
	}

	private long sesion() {
		return db.insertar("sesion", fila(
				"organization_id", tenant.organizationId(),
				"consultorio_id", tenant.consultorioId(),
				"historia_clinica_id", tenant.historiaId(),
				"oferta_id", tenant.ofertaId(),
				"profesional_membership_id", 1L,
				"estado", "BORRADOR",
				"iniciada_en", ahora(),
				"iniciada_por_cuenta_id", tenant.cuentaId(),
				"version", 0,
				"created_at", ahora(),
				"updated_at", ahora()));
	}

	private Map<String, Object> borrador() {
		return fila(
				"organization_id", tenant.organizationId(),
				"consultorio_id", tenant.consultorioId(),
				"financiador_id", tenant.financiadorId(),
				"periodo_desde", LocalDate.now().minusDays(30),
				"periodo_hasta", LocalDate.now(),
				"moneda", "ARS",
				"estado", "BORRADOR",
				"creada_en", ahora(),
				"creada_por_cuenta_id", tenant.cuentaId());
	}

	private Map<String, Object> presentada(int numero) {
		return con(borrador(),
				"estado", "PRESENTADA",
				"numero", numero,
				"confirmada_en", ahora(),
				"confirmada_por_cuenta_id", tenant.cuentaId());
	}

	private Map<String, Object> facturada(int numero, String factura) {
		return con(presentada(numero),
				"estado", "FACTURADA",
				"factura_numero", factura,
				"factura_fecha", LocalDate.now(),
				"factura_registrada_en", ahora(),
				"factura_registrada_por_cuenta_id", tenant.cuentaId());
	}

	private static Map<String, Object> importes(
			Map<String, Object> base, String presentado, String debitado, String cobrado, String saldo) {
		return con(base,
				"total_presentado", new BigDecimal(presentado),
				"total_debitado", new BigDecimal(debitado),
				"total_cobrado", new BigDecimal(cobrado),
				"saldo", new BigDecimal(saldo));
	}

	private Map<String, Object> item(long presentacionId, long obligacionId, String estado) {
		return fila(
				"organization_id", tenant.organizationId(),
				"presentacion_id", presentacionId,
				"obligacion_id", obligacionId,
				"estado", estado,
				"importe_presentado", new BigDecimal("8500.00"),
				"snapshot_persona_id", tenant.personaId(),
				"snapshot_sesion_id", 1L,
				"snapshot_fecha_prestacion", LocalDate.now(),
				"snapshot_concepto", "Sesion sintetica",
				"incluido_en", ahora());
	}

	private Map<String, Object> debitado(long presentacionId, long obligacionId) {
		return con(item(presentacionId, obligacionId, "DEBITADO"),
				"importe_debitado", new BigDecimal("8500.00"),
				"motivo_debito", "Falta autorizacion previa",
				"debitado_en", ahora(),
				"debitado_por_cuenta_id", tenant.cuentaId());
	}

	private Integer ocupa(long itemId) {
		Number valor = (Number) db.valor("SELECT ocupa_marca FROM presentacion_item WHERE id = ?", itemId);
		return valor == null ? null : valor.intValue();
	}
}
