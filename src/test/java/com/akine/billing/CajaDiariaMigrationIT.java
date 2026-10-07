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
import java.util.Map;

import static com.akine.billing.MigracionItSoporte.ahora;
import static com.akine.billing.MigracionItSoporte.con;
import static com.akine.billing.MigracionItSoporte.entra;
import static com.akine.billing.MigracionItSoporte.fila;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenario 35 de {@code docs/tests-diferidos.md}: {@code V54} contra el motor.
 *
 * <p>Que la migracion ejecute lo prueba que el contexto arranque. Lo que esta clase prueba es que
 * cada constraint haga lo que su comentario dice, insertando DIRECTO y sin servicio: un invariante
 * que solo la aplicacion respeta deja de proteger en cuanto alguien escribe por otro camino.
 *
 * <ul>
 *   <li>{@code abierta_marca} generada y {@code uk_jornada_caja_abierta}: <b>a lo sumo una jornada
 *       abierta por sede</b>, y varias cerradas —marca NULL— que no colisionan.</li>
 *   <li>{@code afecta_arqueo = (medio = 'EFECTIVO')} generada.</li>
 *   <li>{@code ck_movimiento_caja_efectivo_con_jornada}: el efectivo vive en una jornada; el resto
 *       puede no tenerla.</li>
 *   <li>El motivo de diferencia: obligatorio con diferencia distinta de cero <b>y prohibido</b>
 *       con diferencia cero.</li>
 *   <li>Importe estrictamente positivo, los dos CHECK de reversion y el unique que impide revertir
 *       dos veces el mismo movimiento.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CajaDiariaMigrationIT {

	@Autowired private PresentacionService presentacionService;
	@Autowired private JdbcTemplate jdbc;

	private MigracionItSoporte db;
	private Tenant tenant;

	@BeforeEach
	void armar() {
		db = new MigracionItSoporte(jdbc);
		tenant = new PresentacionItFixture(jdbc, presentacionService).crearTenant();
	}

	// =================================================================================
	// jornada_caja
	// =================================================================================

	@Test
	@DisplayName("abierta_marca es generada STORED: 1 si ABIERTA, NULL si CERRADA")
	void la_marca_de_abierta_es_generada() {
		db.esGeneradaStored("jornada_caja", "abierta_marca", "ABIERTA");

		long abierta = db.insertar("jornada_caja", jornadaAbierta(tenant.consultorioId()));
		long cerrada = db.insertar("jornada_caja", jornadaCerrada("0.00", null));

		assertThat(marca(abierta)).isEqualTo(1);
		assertThat(marca(cerrada)).isNull();
	}

	@Test
	@DisplayName("una segunda jornada ABIERTA en la misma sede choca contra uk_jornada_caja_abierta")
	void a_lo_sumo_una_abierta_por_sede() {
		db.insertar("jornada_caja", jornadaAbierta(tenant.consultorioId()));

		db.violaUnique("uk_jornada_caja_abierta",
				() -> db.insertar("jornada_caja", jornadaAbierta(tenant.consultorioId())));
	}

	@Test
	@DisplayName("varias CERRADAS conviven con una ABIERTA, y otra sede abre la suya")
	void las_cerradas_no_colisionan() {
		// Los NULL de la marca no colisionan en un unique de MySQL: es lo que permite el historial
		// de jornadas cerradas de la sede. Y el unique arranca por sede: otra sede abre aparte.
		db.insertar("jornada_caja", jornadaCerrada("0.00", null));
		db.insertar("jornada_caja", jornadaCerrada("0.00", null));
		entra(() -> db.insertar("jornada_caja", jornadaAbierta(tenant.consultorioId())));

		long otraSede = otraSede();
		entra(() -> db.insertar("jornada_caja", jornadaAbierta(otraSede)));
	}

	@Test
	@DisplayName("cerrar la abierta libera el lugar: la sede puede abrir otra")
	void cerrar_libera_la_marca() {
		long abierta = db.insertar("jornada_caja", jornadaAbierta(tenant.consultorioId()));
		jdbc.update("""
				UPDATE jornada_caja
				   SET estado = 'CERRADA', cerrada_en = UTC_TIMESTAMP(6), cerrada_por_cuenta_id = ?,
				       saldo_teorico_cierre = 1000.00, saldo_declarado = 1000.00, diferencia = 0.00
				 WHERE id = ?
				""", tenant.cuentaId(), abierta);

		assertThat(marca(abierta)).isNull();
		entra(() -> db.insertar("jornada_caja", jornadaAbierta(tenant.consultorioId())));
	}

	@Test
	@DisplayName("diferencia distinta de cero exige motivo; diferencia cero lo prohibe")
	void el_motivo_de_diferencia_va_en_las_dos_direcciones() {
		db.violaCheck("ck_jornada_caja_motivo_de_diferencia",
				() -> db.insertar("jornada_caja", jornadaCerrada("-100.00", null)));
		db.violaCheck("ck_jornada_caja_motivo_de_diferencia",
				() -> db.insertar("jornada_caja", jornadaCerrada("0.00", "Sobraba un billete")));

		entra(() -> db.insertar("jornada_caja", jornadaCerrada("-100.00", "Vuelto mal dado")));
		entra(() -> db.insertar("jornada_caja", jornadaCerrada("0.00", null)));
	}

	@Test
	@DisplayName("una CERRADA a medias, un arqueo negativo o un estado fuera de catalogo no entran")
	void los_demas_checks_de_la_jornada() {
		db.violaCheck("ck_jornada_caja_cierre_completo", () -> db.insertar("jornada_caja",
				con(jornadaCerrada("0.00", null), "cerrada_en", null)));
		db.violaCheck("ck_jornada_caja_cierre_completo", () -> db.insertar("jornada_caja",
				con(jornadaAbierta(tenant.consultorioId()), "saldo_declarado", new BigDecimal("1.00"))));
		db.violaCheck("ck_jornada_caja_saldo_no_negativo", () -> db.insertar("jornada_caja",
				con(jornadaAbierta(tenant.consultorioId()), "saldo_arqueo", new BigDecimal("-0.01"))));
		db.violaCheck("ck_jornada_caja_saldo_inicial", () -> db.insertar("jornada_caja",
				con(jornadaAbierta(tenant.consultorioId()), "saldo_inicial", new BigDecimal("-1.00"))));
		// Un estado fuera de catalogo no puede satisfacer ck_jornada_caja_cierre_completo —que solo
		// admite ABIERTA o CERRADA— y MySQL informa ese primero. Se afirma el rechazo por esa via y
		// la clausula del CHECK de catalogo por information_schema.
		db.violaCheck("ck_jornada_caja_cierre_completo", () -> db.insertar("jornada_caja",
				con(jornadaAbierta(tenant.consultorioId()), "estado", "REABIERTA")));
		assertThat(db.clausulaDe("ck_jornada_caja_estado")).contains("ABIERTA", "CERRADA");
	}

	// =================================================================================
	// movimiento_caja
	// =================================================================================

	@Test
	@DisplayName("afecta_arqueo es generada STORED: 1 solo para EFECTIVO")
	void afecta_arqueo_es_generada() {
		db.esGeneradaStored("movimiento_caja", "afecta_arqueo", "EFECTIVO");
		long jornada = db.insertar("jornada_caja", jornadaAbierta(tenant.consultorioId()));

		long efectivo = db.insertar("movimiento_caja", movimiento(jornada, "EFECTIVO"));
		long transferencia = db.insertar("movimiento_caja", movimiento(jornada, "TRANSFERENCIA"));
		long tarjeta = db.insertar("movimiento_caja", movimiento(null, "TARJETA_DEBITO"));

		assertThat(afectaArqueo(efectivo)).isEqualTo(1);
		assertThat(afectaArqueo(transferencia)).isZero();
		assertThat(afectaArqueo(tarjeta)).isZero();
	}

	@Test
	@DisplayName("EFECTIVO sin jornada no entra; TRANSFERENCIA sin jornada si")
	void el_efectivo_vive_en_una_jornada() {
		db.violaCheck("ck_movimiento_caja_efectivo_con_jornada",
				() -> db.insertar("movimiento_caja", movimiento(null, "EFECTIVO")));
		entra(() -> db.insertar("movimiento_caja", movimiento(null, "TRANSFERENCIA")));
	}

	@Test
	@DisplayName("importe cero o negativo no entra: el signo lo da el tipo")
	void el_importe_es_estrictamente_positivo() {
		db.violaCheck("ck_movimiento_caja_importe_positivo", () -> db.insertar("movimiento_caja",
				con(movimiento(null, "TRANSFERENCIA"), "importe", BigDecimal.ZERO)));
		db.violaCheck("ck_movimiento_caja_importe_positivo", () -> db.insertar("movimiento_caja",
				con(movimiento(null, "TRANSFERENCIA"), "importe", new BigDecimal("-10.00"))));
	}

	@Test
	@DisplayName("una reversion exige motivo, movimiento de origen y tipo_origen REVERSION")
	void la_reversion_va_completa() {
		long jornada = db.insertar("jornada_caja", jornadaAbierta(tenant.consultorioId()));
		long original = db.insertar("movimiento_caja", movimiento(jornada, "EFECTIVO"));

		db.violaCheck("ck_movimiento_caja_reversion_completa",
				() -> db.insertar("movimiento_caja", con(reversion(jornada, original), "motivo", null)));
		db.violaCheck("ck_movimiento_caja_reversion_completa", () -> db.insertar("movimiento_caja",
				con(reversion(jornada, original), "movimiento_origen_id", null)));
		db.violaCheck("ck_movimiento_caja_reversion_completa", () -> db.insertar("movimiento_caja",
				con(reversion(jornada, original), "tipo_origen", "MANUAL")));

		entra(() -> db.insertar("movimiento_caja", reversion(jornada, original)));
	}

	@Test
	@DisplayName("solo una reversion puede apuntar a otro movimiento")
	void el_origen_es_exclusivo_de_la_reversion() {
		long jornada = db.insertar("jornada_caja", jornadaAbierta(tenant.consultorioId()));
		long original = db.insertar("movimiento_caja", movimiento(jornada, "EFECTIVO"));

		db.violaCheck("ck_movimiento_caja_origen_solo_en_reversion", () -> db.insertar(
				"movimiento_caja", con(movimiento(jornada, "EFECTIVO"), "movimiento_origen_id", original)));
	}

	@Test
	@DisplayName("el mismo movimiento no se revierte dos veces: uk_movimiento_caja_origen")
	void no_se_revierte_dos_veces() {
		long jornada = db.insertar("jornada_caja", jornadaAbierta(tenant.consultorioId()));
		long original = db.insertar("movimiento_caja", movimiento(jornada, "EFECTIVO"));
		long otro = db.insertar("movimiento_caja", movimiento(jornada, "EFECTIVO"));

		db.insertar("movimiento_caja", reversion(jornada, original));
		db.violaUnique("uk_movimiento_caja_origen",
				() -> db.insertar("movimiento_caja", reversion(jornada, original)));

		// Control negativo: revertir OTRO movimiento entra, y los manuales —referencia NULL— no
		// colisionan entre si, que es lo que permite mas de un egreso manual por dia.
		entra(() -> db.insertar("movimiento_caja", reversion(jornada, otro)));
		entra(() -> db.insertar("movimiento_caja", movimiento(jornada, "EFECTIVO")));
	}

	@Test
	@DisplayName("idempotency_key y request_hash van juntos")
	void la_idempotencia_va_completa() {
		db.violaCheck("ck_movimiento_caja_idempotencia_completa", () -> db.insertar("movimiento_caja",
				con(movimiento(null, "TRANSFERENCIA"), "idempotency_key", "clave-sin-hash")));
	}

	// =================================================================================
	// Filas sinteticas
	// =================================================================================

	private Map<String, Object> jornadaAbierta(long consultorioId) {
		return fila(
				"organization_id", tenant.organizationId(),
				"consultorio_id", consultorioId,
				"fecha_negocio", LocalDate.now(),
				"moneda", "ARS",
				"estado", "ABIERTA",
				"saldo_inicial", new BigDecimal("1000.00"),
				"saldo_arqueo", new BigDecimal("1000.00"),
				"abierta_en", ahora(),
				"abierta_por_cuenta_id", tenant.cuentaId());
	}

	private Map<String, Object> jornadaCerrada(String diferencia, String motivo) {
		BigDecimal dif = new BigDecimal(diferencia);
		return con(jornadaAbierta(tenant.consultorioId()),
				"estado", "CERRADA",
				"cerrada_en", ahora(),
				"cerrada_por_cuenta_id", tenant.cuentaId(),
				"saldo_teorico_cierre", new BigDecimal("1000.00"),
				"saldo_declarado", new BigDecimal("1000.00").add(dif),
				"diferencia", dif,
				"motivo_diferencia", motivo);
	}

	private Map<String, Object> movimiento(Long jornadaId, String medio) {
		return fila(
				"organization_id", tenant.organizationId(),
				"consultorio_id", tenant.consultorioId(),
				"jornada_caja_id", jornadaId,
				"fecha_negocio", LocalDate.now(),
				"tipo", "EGRESO",
				"medio", medio,
				"importe", new BigDecimal("100.00"),
				"moneda", "ARS",
				"concepto", "Movimiento sintetico",
				"tipo_origen", "MANUAL",
				"registrado_en", ahora(),
				"registrado_por_cuenta_id", tenant.cuentaId());
	}

	private Map<String, Object> reversion(long jornadaId, long originalId) {
		return con(movimiento(jornadaId, "EFECTIVO"),
				"tipo", "REVERSION_DE_EGRESO",
				"tipo_origen", "REVERSION",
				"referencia_origen", originalId,
				"movimiento_origen_id", originalId,
				"motivo", "Se cargo dos veces");
	}

	private long otraSede() {
		return db.insertar("consultorio", fila(
				"organization_id", tenant.organizationId(),
				"name", "Otra sede " + System.nanoTime(),
				"timezone", PresentacionItFixture.ZONA,
				"active", 1,
				"version", 0,
				"created_at", ahora(),
				"updated_at", ahora()));
	}

	private Integer marca(long jornadaId) {
		Number valor = (Number) db.valor("SELECT abierta_marca FROM jornada_caja WHERE id = ?", jornadaId);
		return valor == null ? null : valor.intValue();
	}

	private int afectaArqueo(long movimientoId) {
		return ((Number) db.valor("SELECT afecta_arqueo FROM movimiento_caja WHERE id = ?", movimientoId))
				.intValue();
	}
}
