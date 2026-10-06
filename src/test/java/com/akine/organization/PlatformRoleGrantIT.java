package com.akine.organization;

import com.akine.TestcontainersConfiguration;
import com.akine.organization.application.PlatformRoleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Otorgar el rol de plataforma exige que la cuenta destino exista.
 *
 * <p>{@code platform_role.account_id} es una referencia LOGICA a {@code identity.cuenta}, sin FK
 * fisica (ADR-0001), y el controller recibe el id crudo. Sin una verificacion explicita, dar el
 * rol a un id que no existe entraba con 201 y dejaba una fila huerfana: el permiso mas alto del
 * sistema reservado para una cuenta que el dia de manana puede crearse con ese id.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PlatformRoleGrantIT {

	@Autowired private PlatformRoleService platformRoles;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("dar el rol de plataforma a una cuenta inexistente es 400 y no deja fila")
	void cuenta_inexistente_no_recibe_el_rol() {
		long admin = insertarCuentaConRolDePlataforma();
		long inexistente = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1000 FROM cuenta", Long.class);

		assertThatThrownBy(() -> platformRoles.grant(admin, inexistente, "Prueba sintetica"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("no existe");

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_role WHERE account_id = ?", Integer.class, inexistente))
				.as("ninguna fila huerfana")
				.isZero();
	}

	@Test
	@DisplayName("a una cuenta que existe se le otorga, y otorgarlo de nuevo es 409")
	void cuenta_existente_recibe_el_rol_una_vez() {
		long admin = insertarCuentaConRolDePlataforma();
		long destino = insertarCuenta();

		assertThat(platformRoles.grant(admin, destino, "Soporte de guardia")).isNotNull();
		assertThatThrownBy(() -> platformRoles.grant(admin, destino, "Otra vez"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("ya tiene el rol");
	}

	// =================================================================================

	private long insertarCuentaConRolDePlataforma() {
		long cuenta = insertarCuenta();
		jdbc.update("""
				INSERT INTO platform_role (account_id, role_code, granted_by_account_id, reason,
				                           valid_from, active, version, created_at, updated_at)
				VALUES (?, 'PLATFORM_ADMIN', NULL, 'Fixture sintetico de test de integracion',
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", cuenta);
		return cuenta;
	}

	private long insertarCuenta() {
		String email = "plat-it-" + UUID.randomUUID().toString().substring(0, 8) + "@ejemplo.test";
		jdbc.update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		return jdbc.queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, email);
	}
}
