package com.akine.diferidos;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import com.akine.organization.application.AuditQueryService;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.ConsultorioService;
import com.akine.organization.application.MembershipService;
import com.akine.organization.application.MembershipView;
import com.akine.organization.application.OperatingActor;
import com.akine.organization.domain.exception.ConsultorioNotAccessibleException;
import com.akine.organization.domain.exception.MembershipNotAccessibleException;
import com.akine.organization.domain.exception.PermissionDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El acceso de soporte sobre las LECTURAS de colaboradores, contra MySQL real.
 *
 * <h2>Por que este test no puede ser unitario</h2>
 *
 * <p>El bug que cierra es puramente transaccional: {@code MembershipService.list},
 * {@code find} y {@code grants} corren con {@code @Transactional(readOnly = true)}, y en una
 * transaccion de solo lectura Hibernate deja el flush en MANUAL. La entrada
 * {@code SUPPORT_ACCESS_USED} se escribia ahi adentro y <b>nunca llegaba a la base</b>: sin
 * error, sin log, sin fila. Un test con mocks ve el {@code auditTrail.record(...)} y pasa en
 * verde con la auditoria perdida — que es exactamente como esto sobrevivio hasta ahora, y el
 * mismo modo de falla que se llevo puesta una revocacion de familia de refresh en 01.02.
 *
 * <p>Por eso las dos aserciones que importan son {@code SELECT ... FROM audit_event}, y no
 * "el metodo no lanzo".
 *
 * <p>Reusa el harness de {@link BaseEscenarioDiferido}: cuenta, organizacion y sede se crean
 * por el camino real. Lo unico que se siembra es el rol de plataforma y el acceso de soporte,
 * que en produccion entran por endpoints que exigen ser ya administrador de plataforma.
 */
class SoporteEnLecturasIT extends BaseEscenarioDiferido {

	@Autowired
	private MembershipService membershipService;

	@Autowired
	private AuthorizationGuard authorizationGuard;

	@Autowired
	private ConsultorioService consultorioService;

	@Autowired
	private AuditQueryService auditQueryService;

	@Test
	@DisplayName("Leer los colaboradores bajo acceso de soporte deja la fila en audit_event")
	void la_lectura_amparada_por_soporte_queda_registrada() {
		Sesion tenant = altaCompleta("soporte-lectura");
		long soporte = administradorDePlataformaConSoporte(tenant.organizationId());

		List<MembershipView> colaboradores = membershipService
				.list(actorDeSoporte(soporte), tenant.organizationId(), PageRequest.of(0, 20))
				.getContent();

		// La matriz seccion 7 pide auditar CADA operacion amparada por soporte, lecturas
		// incluidas. La fila TIENE que estar en la base, no en el log.
		assertThat(usosDeSoporte(soporte, tenant.organizationId()))
				.as("la lectura bajo acceso de soporte tiene que dejar SUPPORT_ACCESS_USED")
				.hasSize(1)
				.first()
				.satisfies(fila -> {
					assertThat(fila.get("entity_type")).isEqualTo("SupportAccess");
					assertThat(String.valueOf(fila.get("details")))
							.contains("colaborador:read");
				});

		// Y de paso: la fila del listado dice quien es la persona, no solo su numero de cuenta.
		assertThat(colaboradores)
				.singleElement()
				.satisfies(fundador -> {
					assertThat(fundador.accountId()).isEqualTo(tenant.cuentaId());
					assertThat(fundador.accountName()).isEqualTo("Sintetica DePrueba");
					assertThat(fundador.accountEmail()).isEqualTo(tenant.email());
				});
	}

	@Test
	@DisplayName("El rastro sobrevive aunque la lectura termine en 404")
	void el_rastro_sobrevive_al_404() {
		// Es el caso que sacarle el readOnly a la lectura NO arregla: `find` evalua el permiso
		// primero y recien despues carga la fila, asi que un id ajeno o inexistente lanza y
		// hace rollback. Con la auditoria en la transaccion de la lectura, un administrador de
		// plataforma podria recorrer ids sin dejar rastro — justo lo contrario de para lo que
		// existe el acceso de soporte.
		Sesion tenant = altaCompleta("soporte-404");
		long soporte = administradorDePlataformaConSoporte(tenant.organizationId());

		assertThatThrownBy(() -> membershipService.find(
				actorDeSoporte(soporte), tenant.organizationId(), 999_999_999L))
				.isInstanceOf(MembershipNotAccessibleException.class);

		assertThat(usosDeSoporte(soporte, tenant.organizationId()))
				.as("el intento amparado por soporte queda registrado aunque responda 404")
				.hasSize(1);
	}

	// =================================================================================
	// El agujero de AuthorizationGuard: el PLATFORM_ADMIN que entraba sin soporte y sin rastro
	// =================================================================================

	@Test
	@DisplayName("Sin soporte vigente, un admin de plataforma no lee el perfil ni las sedes")
	void sin_soporte_el_admin_de_plataforma_no_entra() {
		// requireMember y requireOrgAdmin tenian un `if (platformAdmin) return;` ANTES de
		// cualquier evaluacion: el perfil de la organizacion y su listado de sedes se leian sin
		// support_access y sin dejar una sola fila. Eso hacia opcional el acceso de soporte
		// justo donde mas se nota, y el control posterior que el modelo se juega —motivo
		// declarado, cuatro horas, auditado— no tenia nada que controlar.
		Sesion tenant = altaCompleta("guard-sin-soporte");
		long admin = administradorDePlataformaSinSoporte();

		assertThatThrownBy(() ->
				authorizationGuard.requireMember(admin, tenant.organizationId(), null, true))
				.isInstanceOf(PermissionDeniedException.class);
		assertThatThrownBy(() ->
				authorizationGuard.requireOrgAdmin(admin, tenant.organizationId(), null, true))
				.isInstanceOf(PermissionDeniedException.class);

		assertThat(usosDeSoporte(admin, tenant.organizationId())).isEmpty();
	}

	@Test
	@DisplayName("Con soporte vigente entra, y las dos entradas dejan su fila en audit_event")
	void con_soporte_el_admin_de_plataforma_deja_rastro() {
		Sesion tenant = altaCompleta("guard-con-soporte");
		long soporte = administradorDePlataformaConSoporte(tenant.organizationId());

		authorizationGuard.requireMember(soporte, tenant.organizationId(), null, true);
		authorizationGuard.requireOrgAdmin(soporte, tenant.organizationId(), null, true);

		assertThat(usosDeSoporte(soporte, tenant.organizationId()))
				.as("las dos puertas de OrganizationService y SubscriptionService dejan fila")
				.hasSize(2)
				.allSatisfy(fila -> assertThat(String.valueOf(fila.get("details")))
						.contains("tenant:read"));
	}

	// =================================================================================
	// El rastro que se llevaba el rollback en ConsultorioService.find
	// =================================================================================

	@Test
	@DisplayName("Una sede inexistente bajo soporte deja la fila igual, pese al 404")
	void la_sede_inexistente_deja_rastro() {
		// find renuncio al readOnly para que la auditoria se escribiera, y no alcanzaba: cargar
		// lanza ConsultorioNotAccessibleException DESPUES de evaluar el permiso, asi que en el
		// caso que importa —un id ajeno o inexistente recorrido por un administrador de
		// plataforma— el rollback se llevaba la fila igual. Ahora la escribe
		// SupportAccessReadAuditor en transaccion propia y find recupero su readOnly.
		Sesion tenant = altaCompleta("soporte-sede-404");
		long soporte = administradorDePlataformaConSoporte(tenant.organizationId());

		assertThatThrownBy(() -> consultorioService.find(
				actorDeSoporte(soporte), tenant.organizationId(), 999_999_999L))
				.isInstanceOf(ConsultorioNotAccessibleException.class);

		assertThat(usosDeSoporte(soporte, tenant.organizationId()))
				.as("el recorrido de ids ajenos bajo soporte tiene que quedar registrado")
				.hasSize(1)
				.first()
				.satisfies(fila -> assertThat(String.valueOf(fila.get("details")))
						.contains("consultorio:manage"));
	}

	// =================================================================================
	// Leer el rastro sin dejar rastro de haberlo leido
	// =================================================================================

	@Test
	@DisplayName("Consultar la auditoria del tenant bajo soporte tambien deja su fila")
	void la_consulta_de_auditoria_deja_rastro() {
		// AuditQueryService descartaba viaSupportAccess y solo miraba grantedByScope: alguien
		// podia leer el rastro entero de un tenant —el mapa de lo que hace un cliente— sin
		// dejar rastro de haberlo leido.
		Sesion tenant = altaCompleta("soporte-auditoria");
		long soporte = administradorDePlataformaConSoporte(tenant.organizationId());

		auditQueryService.porPeriodo(
				actorDeSoporte(soporte), tenant.organizationId(),
				Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plusSeconds(60),
				PageRequest.of(0, 20));

		assertThat(usosDeSoporte(soporte, tenant.organizationId()))
				.hasSize(1)
				.first()
				.satisfies(fila -> assertThat(String.valueOf(fila.get("details")))
						.contains("auditoria:read"));
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	/** {@code PLATFORM_ADMIN} sin ningun acceso de soporte sobre ningun tenant. */
	private long administradorDePlataformaSinSoporte() {
		long cuenta = altaCompleta("soporte-admin-pelado").cuentaId();
		sembrarRolDePlataforma(cuenta);
		return cuenta;
	}

	/** Un actor de plataforma no tiene sede propia: opera con alcance organizacion. */
	private static OperatingActor actorDeSoporte(long accountId) {
		return new OperatingActor(accountId, true, null);
	}

	/**
	 * Cuenta con {@code PLATFORM_ADMIN} y un acceso de soporte vigente sobre el tenant.
	 *
	 * <p>{@code valid_from} un minuto en el pasado por el mismo motivo que
	 * {@link #sembrarRolDePlataforma(long)}: los relojes del contenedor y de la JVM no estan
	 * sincronizados al microsegundo y la vigencia puede quedar en el futuro.
	 */
	private long administradorDePlataformaConSoporte(long organizationId) {
		long cuenta = altaCompleta("soporte-admin").cuentaId();
		sembrarRolDePlataforma(cuenta);
		jdbc.update("""
				INSERT INTO support_access (organization_id, account_id, reason,
				                            granted_by_account_id, valid_from, valid_until,
				                            active, version, created_at, updated_at)
				VALUES (?, ?, 'Incidente sintetico de test de integracion', ?,
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE),
				        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, cuenta, cuenta);
		return cuenta;
	}

	private List<Map<String, Object>> usosDeSoporte(long accountId, long organizationId) {
		return jdbc.queryForList("""
				SELECT entity_type, details FROM audit_event
				WHERE event_type = 'SUPPORT_ACCESS_USED'
				  AND actor_account_id = ? AND organization_id = ?
				""", accountId, organizationId);
	}
}
