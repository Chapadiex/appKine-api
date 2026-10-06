package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.domain.port.PresentacionRepositoryPort;
import com.akine.billing.domain.port.TotalesDeCuentaCorriente;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La cuenta corriente de un financiador suma TODOS sus lotes de la organizacion (F-1).
 *
 * <p>Antes la armaba {@code buscar(..., consultorioId, ..., 200, 0)}: contaba solo la sede de la
 * ruta —contra lo que declara el servicio— y desde el lote 201 los totales salian cortos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CuentaCorrienteDeFinanciadorIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	@Autowired private PresentacionRepositoryPort presentaciones;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("suma los lotes de todas las sedes, mas alla de 200, y deja afuera la baja logica")
	void cruza_sedes_y_no_pagina() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);
		long organizationId = insertarOrganizacion(sufijo);
		long sedeA = insertarSede(organizationId, "A " + sufijo);
		long sedeB = insertarSede(organizationId, "B " + sufijo);
		long cuentaId = insertarCuenta(sufijo);
		long financiadorId = insertarFinanciador(organizationId, sufijo);
		long otroFinanciadorId = insertarFinanciador(organizationId, sufijo + "-2");

		insertarLotes(organizationId, sedeA, financiadorId, cuentaId, 1, false);
		insertarLotes(organizationId, sedeB, financiadorId, cuentaId, 201, false);
		insertarLotes(organizationId, sedeB, financiadorId, cuentaId, 1, true);
		insertarLotes(organizationId, sedeA, otroFinanciadorId, cuentaId, 1, false);

		TotalesDeCuentaCorriente totales =
				presentaciones.sumarCuentaCorriente(organizationId, financiadorId);

		assertThat(totales.lotes()).isEqualTo(202);
		assertThat(totales.presentado()).isEqualByComparingTo("20200.00");
		assertThat(totales.debitado()).isEqualByComparingTo("2020.00");
		assertThat(totales.cobrado()).isEqualByComparingTo("4040.00");
		assertThat(totales.saldo()).isEqualByComparingTo("14140.00");
	}

	@Test
	@DisplayName("un financiador sin lotes tiene la cuenta en cero, no en null")
	void sin_lotes_da_cero() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);
		long organizationId = insertarOrganizacion(sufijo);
		long financiadorId = insertarFinanciador(organizationId, sufijo);

		TotalesDeCuentaCorriente totales =
				presentaciones.sumarCuentaCorriente(organizationId, financiadorId);

		assertThat(totales.lotes()).isZero();
		assertThat(totales.presentado()).isEqualByComparingTo("0");
		assertThat(totales.saldo()).isEqualByComparingTo("0");
	}

	// =================================================================================
	// Fixture — insercion directa: lo que se mide es la consulta, no el armado del lote
	// =================================================================================

	/** Cada lote: presentado 100, debitado 10, cobrado 20, saldo 70. En BORRADOR: sin numero. */
	private void insertarLotes(long organizationId, long consultorioId, long financiadorId,
			long cuentaId, int cantidad, boolean dadosDeBaja) {
		List<Object[]> filas = new ArrayList<>();
		for (int i = 0; i < cantidad; i++) {
			filas.add(new Object[]{organizationId, consultorioId, financiadorId, cuentaId, dadosDeBaja});
		}
		jdbc.batchUpdate("""
				INSERT INTO presentacion (organization_id, consultorio_id, financiador_id,
				                          periodo_desde, periodo_hasta, moneda, estado,
				                          total_presentado, total_debitado, total_cobrado, saldo,
				                          creada_en, creada_por_cuenta_id, deleted_at)
				VALUES (?, ?, ?, '2026-09-01', '2026-09-30', 'ARS', 'BORRADOR',
				        100.00, 10.00, 20.00, 70.00, UTC_TIMESTAMP(6), ?,
				        IF(?, UTC_TIMESTAMP(6), NULL))
				""", filas);
	}

	private long insertarOrganizacion(String sufijo) {
		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro " + sufijo, "cc-fin-it-" + sufijo, ZONA);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarSede(long organizationId, String nombre) {
		jdbc.update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, "Sede " + nombre, ZONA);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarCuenta(String sufijo) {
		String email = "cc-fin-it-" + sufijo + "@ejemplo.test";
		jdbc.update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarFinanciador(long organizationId, String sufijo) {
		jdbc.update("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 'PREPAGA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, "FIN-" + sufijo, "Financiador " + sufijo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
