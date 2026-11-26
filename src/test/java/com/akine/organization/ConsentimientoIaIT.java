package com.akine.organization;

import com.akine.TestcontainersConfiguration;
import com.akine.organization.application.FuncionesIaService;
import com.akine.organization.application.FuncionesIaView;
import com.akine.organization.spi.FuncionIa;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El consentimiento de IA del centro contra PostgreSQL (Ley 25.326): sin aceptar la version vigente
 * ninguna funcion corre, aceptar deja una sola fila aunque lleguen dos a la vez, y una version vieja
 * no se acepta.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "akine.ia.consentimiento.version=2026-11-26")
class ConsentimientoIaIT {

	private static final String VIGENTE = "2026-11-26";

	@Autowired private FuncionesIaService funciones;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("sin consentimiento ninguna funcion esta prendida; aceptado, vuelven")
	void sin_consentimiento_nada_prendido() {
		long org = insertarOrganizacion();

		assertThat(funciones.consentimientoVigente(org)).isFalse();
		for (FuncionIa f : FuncionIa.values()) {
			assertThat(funciones.prendida(org, f)).as(f.name()).isFalse();
		}
		FuncionesIaView antes = funciones.ver(org);
		assertThat(antes.consentimiento().exigido()).isTrue();
		assertThat(antes.consentimiento().aceptado()).isFalse();
		assertThat(antes.consentimiento().versionVigente()).isEqualTo(VIGENTE);

		FuncionesIaView despues = funciones.aceptarConsentimiento(org, VIGENTE, 42L);

		assertThat(despues.consentimiento().aceptado()).isTrue();
		assertThat(despues.consentimiento().aceptadoAt()).isNotNull();
		assertThat(funciones.prendida(org, FuncionIa.ASISTENTE)).isTrue();
		assertThat(jdbc.queryForObject(
				"SELECT details ->> 'version' FROM audit_event WHERE organization_id = ? AND event_type = 'ORGANIZATION_IA_CONSENTIMIENTO_ACEPTADO'",
				String.class, org)).isEqualTo(VIGENTE);
	}

	@Test
	@DisplayName("dos aceptaciones a la vez dejan una sola fila y una sola auditoria")
	void aceptar_concurrente_una_fila() {
		long org = insertarOrganizacion();
		CountDownLatch largada = new CountDownLatch(1);

		CompletableFuture<?>[] dos = new CompletableFuture<?>[2];
		for (int i = 0; i < 2; i++) {
			long cuenta = 40L + i;
			dos[i] = CompletableFuture.runAsync(() -> {
				try {
					largada.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				funciones.aceptarConsentimiento(org, VIGENTE, cuenta);
			});
		}
		largada.countDown();
		CompletableFuture.allOf(dos).join();

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM organization_ia_consentimiento WHERE organization_id = ?", Integer.class, org))
				.isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE organization_id = ? AND event_type = 'ORGANIZATION_IA_CONSENTIMIENTO_ACEPTADO'",
				Integer.class, org)).isEqualTo(1);
	}

	@Test
	@DisplayName("una version que no es la vigente no se acepta, y la de otra version no vale")
	void version_vieja_no_vale() {
		long org = insertarOrganizacion();

		assertThatThrownBy(() -> funciones.aceptarConsentimiento(org, "2026-01-01", 42L))
				.isInstanceOf(OptimisticLockingFailureException.class);

		jdbc.update("""
				INSERT INTO organization_ia_consentimiento (organization_id, version, account_id, nombre, aceptado_at)
				VALUES (?, '2026-01-01', 42, null, CURRENT_TIMESTAMP)""", org);
		assertThat(funciones.consentimientoVigente(org)).as("aceptar el texto viejo no cubre el nuevo").isFalse();
	}

	private long insertarOrganizacion() {
		String slug = "consent-it-" + UUID.randomUUID().toString().substring(0, 8);
		return jdbc.queryForObject("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				RETURNING id""", Long.class, "Centro sintetico " + slug, slug);
	}
}
