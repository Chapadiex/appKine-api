package com.akine.organization;

import com.akine.TestcontainersConfiguration;
import com.akine.identity.application.AltaDeCentroPlataformaService;
import com.akine.identity.application.AvisosDeCobro;
import com.akine.organization.application.CobroDeSuscripcionService;
import com.akine.organization.application.CobroDeSuscripcionService.CobroView;
import com.akine.organization.application.ContratacionService;
import com.akine.organization.application.ContratacionView;
import com.akine.organization.domain.SituacionDeCobro;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.domain.exception.CobroNoAdmitidoException;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.AltaDeCentro;
import com.akine.organization.spi.AuthorizedContext;
import com.akine.organization.spi.Servicio;
import com.akine.platform.spi.tenant.MembershipDirectory;
import com.akine.platform.spi.tenant.TenantMembership;
import com.akine.platform.spi.tenant.TenantOperationalStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cobro mensual contra la base real (19/11/2026, docs/diseno/AKINE-cobro-mensual.md): registrar un
 * cobro saca de la prueba y extiende el periodo, y un centro vencido hace mas de 18 dias pierde el
 * contexto hasta que paga.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CobroMensualIT {

	/** El PLATFORM_ADMIN sembrado por las migraciones. */
	private static final long PLATAFORMA = 1L;

	@Autowired private AltaDeCentroPlataformaService alta;
	@Autowired private CobroDeSuscripcionService cobros;
	@Autowired private ContratacionService contratacion;
	@Autowired private MembershipDirectory membresias;
	@Autowired private AccountContextDirectory contextos;
	@Autowired private AvisosDeCobro avisos;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("el primer cobro saca de la prueba, el segundo conserva el ciclo, y una suspendida no admite cobro")
	void registrar_cobros() {
		long org = altaEnPrueba().organizationId();

		CobroView primero = cobros.registrar(org, 1, "  Transferencia  ", PLATAFORMA);
		ContratacionView vista = contratacion.ver(org);
		assertThat(vista.estado()).isEqualTo(SubscriptionStatus.ACTIVA);
		assertThat(vista.pruebaHasta()).isNull();
		assertThat(vista.pagadoHasta()).isEqualTo(primero.periodoHasta());
		assertThat(vista.situacionDeCobro()).isEqualTo(SituacionDeCobro.AL_DIA);
		assertThat(vista.cierreEl()).isEqualTo(SituacionDeCobro.cierreEl(primero.periodoHasta()));
		assertThat(vista.soloLectura()).isFalse();
		assertThat(primero.nota()).isEqualTo("Transferencia");
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM subscription_transition
				 WHERE organization_id = ? AND from_status = 'PRUEBA' AND to_status = 'ACTIVA'""",
				Integer.class, org)).isEqualTo(1);

		CobroView segundo = cobros.registrar(org, 2, null, PLATAFORMA);
		assertThat(segundo.periodoDesde()).as("pagar antes de vencer conserva el ciclo")
				.isEqualTo(primero.periodoHasta());
		assertThat(cobros.historial(org)).extracting(CobroView::id).containsExactly(segundo.id(), primero.id());
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE organization_id = ? AND event_type = 'SUBSCRIPTION_COBRO_REGISTRADO'",
				Integer.class, org)).isEqualTo(2);

		jdbc.update("UPDATE subscription SET status = 'SUSPENDIDA' WHERE organization_id = ?", org);
		assertThatThrownBy(() -> cobros.registrar(org, 1, null, PLATAFORMA))
				.isInstanceOf(CobroNoAdmitidoException.class);
		assertThat(cobros.historial(org)).hasSize(2);
	}

	@Test
	@DisplayName("pasado el cierre el contexto se rechaza, el selector lo muestra sin acceso, se avisa una vez y cobrar lo devuelve")
	void sin_acceso_hasta_pagar() {
		AltaDeCentroPlataformaService.Resultado centro = altaEnPrueba();
		long org = centro.organizationId();
		jdbc.update("""
				INSERT INTO membership (organization_id, account_id, role_code, valid_from, created_at, updated_at)
				VALUES (?, ?, 'ORG_ADMIN', now() - interval '1 day', now(), now())""", org, PLATAFORMA);
		cobros.registrar(org, 1, null, PLATAFORMA);
		jdbc.update("UPDATE subscription SET pagado_hasta = now() - interval '19 days' WHERE organization_id = ?", org);

		TenantMembership cerrada = membresias.resolveMembership(PLATAFORMA, org, centro.consultorioId(), Instant.now())
				.orElseThrow();
		assertThat(cerrada.operationalStatus()).isEqualTo(TenantOperationalStatus.SIN_ACCESO);
		assertThat(cerrada.operationalStatus().allowsContextUsage()).isFalse();
		assertThat(organizaciones(contextos.authorizedContexts(PLATAFORMA))).doesNotContain(org);
		assertThat(organizaciones(contextos.contextosSinAcceso(PLATAFORMA))).contains(org);
		assertThat(contextos.isContextAuthorized(PLATAFORMA, org, centro.consultorioId())).isFalse();
		ContratacionView vista = contratacion.ver(org);
		assertThat(vista.situacionDeCobro()).isEqualTo(SituacionDeCobro.SIN_ACCESO);
		assertThat(vista.soloLectura()).isTrue();

		avisos.avisarVencimientos(Instant.now());
		avisos.avisarVencimientos(Instant.now());
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM notification_outbox WHERE organization_id = ? AND tipo = 'SUSCRIPCION_CERRADA'",
				Integer.class, org)).as("uno por administrador, una sola vez").isEqualTo(1);

		CobroView pago = cobros.registrar(org, 1, null, PLATAFORMA);
		assertThat(pago.periodoDesde()).as("pagar tarde reinicia el ciclo desde hoy").isAfter(Instant.now().minusSeconds(60));
		assertThat(membresias.resolveMembership(PLATAFORMA, org, centro.consultorioId(), Instant.now())
				.orElseThrow().operationalStatus()).isEqualTo(TenantOperationalStatus.ACTIVA);
		assertThat(organizaciones(contextos.authorizedContexts(PLATAFORMA))).contains(org);
		assertThat(organizaciones(contextos.contextosSinAcceso(PLATAFORMA))).doesNotContain(org);
	}

	private static List<Long> organizaciones(List<AuthorizedContext> lista) {
		return lista.stream().map(AuthorizedContext::organizationId).toList();
	}

	private AltaDeCentroPlataformaService.Resultado altaEnPrueba() {
		String email = "cobro-" + UUID.randomUUID().toString().substring(0, 8) + "@centro.test";
		return alta.darDeAlta(new AltaDeCentro.Comando("Centro Sintetico Cobro", "Sede Cobro", null, "BASE",
				EnumSet.of(Servicio.KINESIOLOGIA), EnumSet.noneOf(Adicional.class), true, null, PLATAFORMA), email);
	}
}
