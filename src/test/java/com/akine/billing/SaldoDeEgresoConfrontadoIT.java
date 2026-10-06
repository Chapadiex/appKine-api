package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.application.EgresoCommand;
import com.akine.billing.application.EgresoService;
import com.akine.billing.application.EgresoView;
import com.akine.billing.application.OperatingActor;
import com.akine.billing.application.PagoEgresoCommand;
import com.akine.billing.application.PagoEgresoService;
import com.akine.billing.application.PagoEgresoView;
import com.akine.billing.domain.CategoriaEgreso;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.TipoBeneficiario;
import com.akine.billing.domain.port.PagoEgresoRepositoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El saldo materializado de un egreso cuadra con la suma de sus pagos vigentes (M22, 07.05).
 *
 * <p>{@code egreso.saldo_pendiente} lo mueven dos UPDATE nativos —descontar al pagar, devolver al
 * anular— y es lo que gobierna. {@code PagoEgresoRepositoryPort.totalPagadoVigente} existe para
 * confrontarlo, y hasta este test no lo usaba nadie: si la columna y los pagos divergian, no lo
 * detectaba nada. Es la misma invariante que el ledger de autorizaciones dejo pendiente en 04.05.
 *
 * <p>Por los servicios y no por insercion directa: lo que se prueba es que pagar y anular dejen la
 * columna donde los pagos dicen. TRANSFERENCIA para no depender de una jornada de caja abierta.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class SaldoDeEgresoConfrontadoIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	@Autowired private EgresoService egresoService;
	@Autowired private PagoEgresoService pagoService;
	@Autowired private PagoEgresoRepositoryPort pagos;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("pagar, pagar y anular un pago deja saldo_pendiente = total - pagos vigentes")
	void el_saldo_materializado_cuadra_con_los_pagos() {
		Fixture fixture = crearFixture();
		OperatingActor actor = fixture.actor();
		long sede = fixture.consultorioId();

		EgresoView egreso = egresoService.registrar(actor, sede, new EgresoCommand(
				CategoriaEgreso.ALQUILER, TipoBeneficiario.EXTERNO, null,
				"Inmobiliaria Sintetica", "30-00000000-0",
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
				"Alquiler septiembre", new BigDecimal("10000.00"), "ARS",
				"FACTURA_B", "0001-" + fixture.sufijo(), LocalDate.of(2026, 9, 1), null));
		egresoService.confirmar(actor, sede, egreso.id());

		PagoEgresoView primero = pagoService.pagar(actor, sede, egreso.id(),
				new PagoEgresoCommand(new BigDecimal("4000.00"), MedioDePago.TRANSFERENCIA, "TRF-1", null));
		pagoService.pagar(actor, sede, egreso.id(),
				new PagoEgresoCommand(new BigDecimal("3000.00"), MedioDePago.TRANSFERENCIA, "TRF-2", null));
		pagoService.anularPago(actor, sede, egreso.id(), primero.id(), "Transferencia rechazada");

		BigDecimal saldoColumna = jdbc.queryForObject(
				"SELECT saldo_pendiente FROM egreso WHERE id = ?", BigDecimal.class, egreso.id());
		BigDecimal pagadoVigente = pagos.totalPagadoVigente(fixture.organizationId(), egreso.id());

		assertThat(pagadoVigente)
				.as("solo cuenta el pago que sigue vigente")
				.isEqualByComparingTo("3000.00");
		assertThat(saldoColumna)
				.as("la columna que gobierna cuadra con los pagos que la explican")
				.isEqualByComparingTo(new BigDecimal("10000.00").subtract(pagadoVigente));
	}

	// =================================================================================
	// Fixture — mismo criterio que CobroConcurrenteIT
	// =================================================================================

	private record Fixture(long organizationId, long consultorioId, String sufijo, OperatingActor actor) {
	}

	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM organization WHERE slug = ?",
				new Object[]{"Centro " + sufijo, "egreso-it-" + sufijo, ZONA},
				new Object[]{"egreso-it-" + sufijo});

		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				new Object[]{organizationId, "Sede " + sufijo, ZONA},
				new Object[]{organizationId, "Sede " + sufijo});

		String email = "egreso-it-" + sufijo + "@ejemplo.test";
		long cuentaId = insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM cuenta WHERE email_normalizado = ?",
				new Object[]{email, email}, new Object[]{email});

		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'ADMINISTRATIVO', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, cuentaId);

		return new Fixture(organizationId, consultorioId, sufijo,
				new OperatingActor(cuentaId, false, organizationId, consultorioId));
	}

	private long insertar(String insert, String select, Object[] insertArgs, Object[] selectArgs) {
		jdbc.update(insert, insertArgs);
		return jdbc.queryForObject(select, Long.class, selectArgs);
	}
}
