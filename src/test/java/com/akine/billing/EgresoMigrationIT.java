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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.akine.billing.MigracionItSoporte.ahora;
import static com.akine.billing.MigracionItSoporte.con;
import static com.akine.billing.MigracionItSoporte.entra;
import static com.akine.billing.MigracionItSoporte.fila;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenario 44 de {@code docs/tests-diferidos.md}: {@code V57} contra el motor.
 *
 * <ul>
 *   <li>Los CHECK de {@code egreso}, con {@code ck_egreso_borrador_sin_pagos},
 *       {@code ck_egreso_beneficiario_coherente} y {@code ck_egreso_periodo_coherente} en sus dos
 *       direcciones.</li>
 *   <li>{@code anulado_key} generada STORED con el centinela {@code '1970-01-01'}, y el unique del
 *       comprobante con y sin anulacion de por medio.</li>
 *   <li>Los CHECK de {@code pago_egreso}.</li>
 *   <li>El {@code ALTER} que agrega {@code PAGO_EGRESO} a {@code ck_movimiento_caja_tipo_origen},
 *       con los valores viejos todavia validos (lo afirma {@link PresentacionesMigrationIT}, que
 *       recorre la lista entera).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class EgresoMigrationIT {

	@Autowired private PresentacionService presentacionService;
	@Autowired private JdbcTemplate jdbc;

	private MigracionItSoporte db;
	private Tenant tenant;
	private long membershipId;

	@BeforeEach
	void armar() {
		db = new MigracionItSoporte(jdbc);
		tenant = new PresentacionItFixture(jdbc, presentacionService).crearTenant();
		membershipId = jdbc.queryForObject("SELECT id FROM membership WHERE account_id = ?",
				Long.class, tenant.cuentaId());
	}

	// =================================================================================
	// egreso — los tres CHECK que van en dos direcciones
	// =================================================================================

	@Test
	@DisplayName("un BORRADOR no puede tener pagos; un CONFIRMADO si")
	void el_borrador_no_tiene_pagos() {
		db.violaCheck("ck_egreso_borrador_sin_pagos", () -> db.insertar("egreso",
				con(borrador(), "saldo_pendiente", new BigDecimal("400.00"))));
		entra(() -> db.insertar("egreso", borrador()));
		entra(() -> db.insertar("egreso", con(confirmado(), "saldo_pendiente", new BigDecimal("400.00"))));
	}

	@Test
	@DisplayName("COLABORADOR exige membership y EXTERNO la prohibe")
	void el_beneficiario_es_coherente() {
		db.violaCheck("ck_egreso_beneficiario_coherente", () -> db.insertar("egreso",
				con(borrador(), "tipo_beneficiario", "COLABORADOR")));
		db.violaCheck("ck_egreso_beneficiario_coherente", () -> db.insertar("egreso",
				con(borrador(), "beneficiario_membership_id", membershipId)));

		entra(() -> db.insertar("egreso", con(borrador(),
				"tipo_beneficiario", "COLABORADOR", "beneficiario_membership_id", membershipId)));
		entra(() -> db.insertar("egreso", borrador()));
	}

	@Test
	@DisplayName("el periodo va entero o no va, y desde no supera a hasta")
	void el_periodo_es_coherente() {
		LocalDate hoy = LocalDate.now();
		db.violaCheck("ck_egreso_periodo_coherente", () -> db.insertar("egreso",
				con(borrador(), "periodo_desde", hoy)));
		db.violaCheck("ck_egreso_periodo_coherente", () -> db.insertar("egreso",
				con(borrador(), "periodo_hasta", hoy)));
		db.violaCheck("ck_egreso_periodo_coherente", () -> db.insertar("egreso",
				con(borrador(), "periodo_desde", hoy, "periodo_hasta", hoy.minusDays(1))));

		entra(() -> db.insertar("egreso", borrador()));
		entra(() -> db.insertar("egreso", con(borrador(), "periodo_desde", hoy, "periodo_hasta", hoy)));
	}

	// =================================================================================
	// egreso — el resto de los CHECK
	// =================================================================================

	@Test
	@DisplayName("estado, categoria y tipo de beneficiario son listas cerradas")
	void los_catalogos_son_cerrados() {
		db.violaCheck("ck_egreso_estado",
				// Desde un CONFIRMADO: con las marcas de confirmacion presentes, el unico CHECK que
				// un estado fuera de catalogo rompe es el de catalogo.
				() -> db.insertar("egreso", con(confirmado(), "estado", "PARCIAL")));
		db.violaCheck("ck_egreso_categoria",
				() -> db.insertar("egreso", con(borrador(), "categoria", "VIATICOS")));
		db.violaCheck("ck_egreso_tipo_beneficiario",
				() -> db.insertar("egreso", con(borrador(), "tipo_beneficiario", "PROVEEDOR")));
	}

	@Test
	@DisplayName("importe positivo, saldo entre cero y el total")
	void los_importes_estan_acotados() {
		db.violaCheck("ck_egreso_importe_positivo", () -> db.insertar("egreso", con(borrador(),
				"importe_total", BigDecimal.ZERO, "saldo_pendiente", BigDecimal.ZERO)));
		db.violaCheck("ck_egreso_saldo_no_negativo", () -> db.insertar("egreso",
				con(confirmado(), "saldo_pendiente", new BigDecimal("-1.00"))));
		db.violaCheck("ck_egreso_saldo_acotado", () -> db.insertar("egreso",
				con(confirmado(), "saldo_pendiente", new BigDecimal("1000.01"))));
	}

	@Test
	@DisplayName("confirmacion, anulacion e idempotencia van completas")
	void las_marcas_van_completas() {
		db.violaCheck("ck_egreso_confirmacion_completa", () -> db.insertar("egreso",
				con(confirmado(), "confirmado_en", null, "confirmado_por_cuenta_id", null)));
		db.violaCheck("ck_egreso_confirmacion_completa", () -> db.insertar("egreso",
				con(confirmado(), "confirmado_por_cuenta_id", null)));
		db.violaCheck("ck_egreso_anulacion_completa", () -> db.insertar("egreso",
				con(anulado(ahora()), "anulado_en", null)));
		db.violaCheck("ck_egreso_anulacion_completa", () -> db.insertar("egreso",
				con(anulado(ahora()), "motivo_anulacion", null)));
		db.violaCheck("ck_egreso_anulacion_completa", () -> db.insertar("egreso",
				con(borrador(), "anulado_en", ahora(), "anulado_por_cuenta_id", tenant.cuentaId(),
						"motivo_anulacion", "Un borrador no esta anulado")));
		db.violaCheck("ck_egreso_idempotencia_completa", () -> db.insertar("egreso",
				con(borrador(), "idempotency_key", "clave-sin-hash")));

		entra(() -> db.insertar("egreso", anulado(ahora())));
	}

	// =================================================================================
	// anulado_key y el unique del comprobante
	// =================================================================================

	@Test
	@DisplayName("anulado_key es generada STORED: el centinela 1970-01-01 si vive, anulado_en si no")
	void anulado_key_es_generada() {
		db.esGeneradaStored("egreso", "anulado_key", "1970-01-01");

		long vivo = db.insertar("egreso", borrador());
		LocalDateTime cuando = ahora();
		long anulado = db.insertar("egreso", anulado(cuando));

		assertThat(anuladoKey(vivo)).isEqualTo(LocalDateTime.of(1970, 1, 1, 0, 0));
		assertThat(anuladoKey(anulado)).isEqualTo(cuando);
	}

	@Test
	@DisplayName("el mismo comprobante del mismo beneficiario no entra dos veces vivo")
	void el_comprobante_vivo_es_unico() {
		db.insertar("egreso", conComprobante(borrador(), "0001-00000123"));
		db.violaUnique("uk_egreso_comprobante",
				() -> db.insertar("egreso", conComprobante(confirmado(), "0001-00000123")));

		// Controles negativos: otro numero, u otro beneficiario con el mismo numero, entran.
		entra(() -> db.insertar("egreso", conComprobante(borrador(), "0001-00000124")));
		entra(() -> db.insertar("egreso", con(conComprobante(borrador(), "0001-00000123"),
				"beneficiario_clave", "EXT-OTRO")));
	}

	@Test
	@DisplayName("anulado el primero, el mismo comprobante vuelve a entrar; y dos anulados conviven")
	void la_anulacion_libera_el_comprobante() {
		db.insertar("egreso", conComprobante(anulado(ahora()), "0001-00000200"));
		entra(() -> db.insertar("egreso", conComprobante(borrador(), "0001-00000200")));
		entra(() -> db.insertar("egreso",
				conComprobante(anulado(ahora().plusSeconds(1)), "0001-00000200")));
		db.violaUnique("uk_egreso_comprobante",
				() -> db.insertar("egreso", conComprobante(confirmado(), "0001-00000200")));
	}

	@Test
	@DisplayName("sin comprobante, dos egresos del mismo beneficiario conviven")
	void sin_comprobante_no_hay_choque() {
		db.insertar("egreso", borrador());
		entra(() -> db.insertar("egreso", borrador()));
	}

	// =================================================================================
	// pago_egreso
	// =================================================================================

	@Test
	@DisplayName("pago_egreso: estado, medio, importe positivo, anulacion e idempotencia completas")
	void los_checks_del_pago() {
		long egreso = db.insertar("egreso", confirmado());

		db.violaCheck("ck_pago_egreso_estado",
				() -> db.insertar("pago_egreso", con(pago(egreso), "estado", "PENDIENTE")));
		db.violaCheck("ck_pago_egreso_medio",
				() -> db.insertar("pago_egreso", con(pago(egreso), "medio", "CHEQUE")));
		db.violaCheck("ck_pago_egreso_importe_positivo",
				() -> db.insertar("pago_egreso", con(pago(egreso), "importe", BigDecimal.ZERO)));
		db.violaCheck("ck_pago_egreso_anulacion_completa",
				() -> db.insertar("pago_egreso", con(pago(egreso), "estado", "ANULADO")));
		db.violaCheck("ck_pago_egreso_anulacion_completa", () -> db.insertar("pago_egreso",
				con(pago(egreso), "estado", "ANULADO", "anulado_en", ahora(),
						"anulado_por_cuenta_id", tenant.cuentaId())));
		db.violaCheck("ck_pago_egreso_anulacion_completa",
				() -> db.insertar("pago_egreso", con(pago(egreso), "anulado_en", ahora())));
		db.violaCheck("ck_pago_egreso_idempotencia_completa",
				() -> db.insertar("pago_egreso", con(pago(egreso), "request_hash", "a".repeat(64))));

		entra(() -> db.insertar("pago_egreso", pago(egreso)));
		entra(() -> db.insertar("pago_egreso", con(pago(egreso), "estado", "ANULADO",
				"anulado_en", ahora(), "anulado_por_cuenta_id", tenant.cuentaId(),
				"motivo_anulacion", "Transferencia rebotada")));
	}

	@Test
	@DisplayName("PAGO_EGRESO es un tipo_origen valido de movimiento_caja, junto a los tres viejos")
	void el_alter_de_movimiento_caja() {
		assertThat(db.clausulaDe("ck_movimiento_caja_tipo_origen")).contains("PAGO_EGRESO");
		for (String origen : List.of("COBRO", "MANUAL", "PAGO_EGRESO")) {
			entra(() -> db.insertar("movimiento_caja", fila(
					"organization_id", tenant.organizationId(),
					"consultorio_id", tenant.consultorioId(),
					"fecha_negocio", LocalDate.now(),
					"tipo", "EGRESO",
					"medio", "TRANSFERENCIA",
					"importe", new BigDecimal("100.00"),
					"moneda", "ARS",
					"concepto", "Pago sintetico",
					"tipo_origen", origen,
					"referencia_origen", System.nanoTime(),
					"registrado_en", ahora(),
					"registrado_por_cuenta_id", tenant.cuentaId())));
		}
	}

	// =================================================================================
	// Filas sinteticas
	// =================================================================================

	private Map<String, Object> borrador() {
		return fila(
				"organization_id", tenant.organizationId(),
				"consultorio_id", tenant.consultorioId(),
				"categoria", "ALQUILER",
				"tipo_beneficiario", "EXTERNO",
				"beneficiario_membership_id", null,
				"beneficiario_clave", "EXT-INMOBILIARIA",
				"beneficiario_nombre", "Inmobiliaria Sintetica",
				"concepto", "Alquiler sintetico",
				"importe_total", new BigDecimal("1000.00"),
				"saldo_pendiente", new BigDecimal("1000.00"),
				"moneda", "ARS",
				"estado", "BORRADOR",
				"registrado_en", ahora(),
				"registrado_por_cuenta_id", tenant.cuentaId());
	}

	private Map<String, Object> confirmado() {
		return con(borrador(),
				"estado", "CONFIRMADO",
				"confirmado_en", ahora(),
				"confirmado_por_cuenta_id", tenant.cuentaId());
	}

	private Map<String, Object> anulado(LocalDateTime cuando) {
		return con(borrador(),
				"estado", "ANULADO",
				"anulado_en", cuando,
				"anulado_por_cuenta_id", tenant.cuentaId(),
				"motivo_anulacion", "Cargado por error");
	}

	private static Map<String, Object> conComprobante(Map<String, Object> egreso, String numero) {
		return con(egreso,
				"comprobante_tipo", "FACTURA_C",
				"comprobante_numero", numero,
				"comprobante_fecha", LocalDate.now());
	}

	private Map<String, Object> pago(long egresoId) {
		return fila(
				"organization_id", tenant.organizationId(),
				"consultorio_id", tenant.consultorioId(),
				"egreso_id", egresoId,
				"importe", new BigDecimal("400.00"),
				"moneda", "ARS",
				"medio", "TRANSFERENCIA",
				"fecha_negocio", LocalDate.now(),
				"estado", "CONFIRMADO",
				"pagado_en", ahora(),
				"pagado_por_cuenta_id", tenant.cuentaId());
	}

	private LocalDateTime anuladoKey(long egresoId) {
		return jdbc.queryForObject("SELECT anulado_key FROM egreso WHERE id = ?",
				LocalDateTime.class, egresoId);
	}
}
