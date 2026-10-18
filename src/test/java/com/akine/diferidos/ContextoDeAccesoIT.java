package com.akine.diferidos;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.akine.organization.domain.LecturaDeContexto;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.infrastructure.MembershipRepository;
import com.akine.platform.spi.tenant.MembershipDirectory;
import com.akine.platform.spi.tenant.TenantOperationalStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La sentencia unica que resuelve un contexto de trabajo (docs/carga.md §13), contra MySQL.
 *
 * <p>Reemplazo a cuatro lecturas —memberships, sede por (id, organizacion) activa, organizacion
 * vigente y suscripcion—, y cada condicion de esas cuatro tiene que seguir decidiendo igual: una
 * sede de otra organizacion o dada de baja, una organizacion dada de baja o una membership dada de
 * baja no pueden resolver un contexto, y cuando hay varias memberships tiene que haber una fila por
 * cada una, en orden de id, sin que las uniones las multipliquen.
 */
class ContextoDeAccesoIT extends BaseEscenarioDiferido {

	@Autowired
	private MembershipRepository memberships;

	@Autowired
	private MembershipDirectory directorio;

	@Test
	@DisplayName("Contexto propio: una fila con la sede, la organizacion y el estado de la suscripcion")
	void contexto_propio() {
		Sesion a = altaCompleta("ctx-ok");

		List<LecturaDeContexto> filas = leer(a, a.consultorioId());

		assertThat(filas).hasSize(1);
		assertThat(filas.get(0).consultorioId()).isEqualTo(a.consultorioId());
		assertThat(filas.get(0).organizacionId()).isEqualTo(a.organizationId());
		assertThat(filas.get(0).estadoSuscripcion()).isEqualTo(SubscriptionStatus.ACTIVA);
		assertThat(directorio.resolveMembership(a.cuentaId(), a.organizationId(), a.consultorioId(), Instant.now()))
				.hasValueSatisfying(m -> assertThat(m.operationalStatus()).isEqualTo(TenantOperationalStatus.ACTIVA));
	}

	@Test
	@DisplayName("La sede de OTRA organizacion no se une: el contexto no resuelve")
	void sede_de_otra_organizacion() {
		Sesion a = altaCompleta("ctx-a");
		Sesion b = altaCompleta("ctx-b");

		List<LecturaDeContexto> filas = leer(a, b.consultorioId());

		assertThat(filas).hasSize(1);
		assertThat(filas.get(0).consultorioId()).isNull();
		assertThat(directorio.resolveMembership(a.cuentaId(), a.organizationId(), b.consultorioId(), Instant.now()))
				.isEmpty();
	}

	@Test
	@DisplayName("Sede dada de baja y organizacion dada de baja: no se unen")
	void bajas_logicas() {
		Sesion a = altaCompleta("ctx-baja-sede");
		jdbc.update("UPDATE consultorio SET active = 0, deleted_at = UTC_TIMESTAMP(6) WHERE id = ?",
				a.consultorioId());
		assertThat(leer(a, a.consultorioId()).get(0).consultorioId()).isNull();

		Sesion b = altaCompleta("ctx-baja-org");
		jdbc.update("UPDATE organization SET active = 0, deleted_at = UTC_TIMESTAMP(6) WHERE id = ?",
				b.organizationId());
		LecturaDeContexto fila = leer(b, b.consultorioId()).get(0);
		assertThat(fila.consultorioId()).isEqualTo(b.consultorioId());
		assertThat(fila.organizacionId()).isNull();
		assertThat(directorio.resolveMembership(b.cuentaId(), b.organizationId(), b.consultorioId(), Instant.now()))
				.isEmpty();
	}

	@Test
	@DisplayName("Varias memberships: una fila por cada una, en orden de id; la dada de baja no aparece")
	void varias_memberships() {
		Sesion a = altaCompleta("ctx-varias");
		long fundadora = jdbc.queryForObject(
				"SELECT id FROM membership WHERE organization_id = ? AND account_id = ?",
				Long.class, a.organizationId(), a.cuentaId());
		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'PROFESIONAL', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", a.organizationId(), a.consultorioId(), a.cuentaId());

		List<LecturaDeContexto> filas = leer(a, a.consultorioId());

		assertThat(filas).extracting(f -> f.membership().getId())
				.hasSize(2)
				.startsWith(fundadora)
				.isSorted();
		assertThat(filas).allSatisfy(f -> {
			assertThat(f.consultorioId()).isEqualTo(a.consultorioId());
			assertThat(f.organizacionId()).isEqualTo(a.organizationId());
		});
		// Gana la acotada a la sede (MembershipSelection), igual que con las cuatro lecturas.
		assertThat(directorio.resolveMembership(a.cuentaId(), a.organizationId(), a.consultorioId(), Instant.now()))
				.hasValueSatisfying(m -> assertThat(m.roleCode()).isEqualTo("PROFESIONAL"));

		jdbc.update("UPDATE membership SET active = 0, deleted_at = UTC_TIMESTAMP(6) WHERE id = ?", fundadora);
		assertThat(leer(a, a.consultorioId())).extracting(f -> f.membership().getId())
				.doesNotContain(fundadora)
				.hasSize(1);
	}

	@Test
	@DisplayName("Sin membership en la organizacion: ninguna fila")
	void sin_membership() {
		Sesion a = altaCompleta("ctx-sin-a");
		Sesion b = altaCompleta("ctx-sin-b");

		assertThat(memberships.leerContextoDeAcceso(b.organizationId(), a.cuentaId(), b.consultorioId()))
				.isEmpty();
	}

	private List<LecturaDeContexto> leer(Sesion sesion, long consultorioId) {
		return memberships.leerContextoDeAcceso(sesion.organizationId(), sesion.cuentaId(), consultorioId);
	}
}
