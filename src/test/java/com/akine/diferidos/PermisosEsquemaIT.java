package com.akine.diferidos;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;

import com.akine.organization.application.MembershipService;
import com.akine.organization.application.OperatingActor;
import com.akine.organization.spi.DirectMembershipCommand;
import com.akine.organization.spi.PermissionEvaluator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las garantias que viven en la BASE, disparadas contra MySQL real.
 *
 * <h2>Por que no alcanza con leer el DDL</h2>
 *
 * <p>Los uniques con columna generada son la parte del esquema con mas chance de estar mal, y
 * un {@code CREATE TABLE} correcto a la vista puede aceptar exactamente lo que queria impedir:
 * en MySQL varios {@code NULL} no colisionan en un indice unico, asi que un discriminador mal
 * elegido convierte la restriccion en decoracion. La unica forma de saberlo es <b>insertar la
 * colision esperada y la no-colision esperada</b>.
 *
 * <p>Lo mismo con los triggers: la unica forma de probar un trigger es dispararlo.
 */
class PermisosEsquemaIT extends BaseEscenarioDiferido {

	private static final String MOTIVO = "prueba sintetica de esquema";

	@Autowired
	private MembershipService membershipService;

	@Autowired
	private PermissionEvaluator permissionEvaluator;

	// =================================================================================
	// Los uniques con columna generada
	// =================================================================================

	@Test
	@DisplayName("Un grant revocado sale del unique: se puede re-otorgar lo que alguna vez se quito")
	void un_grant_revocado_sale_del_unique() {
		// Es el efecto que la columna generada `grant_activo` existe para producir: vale el
		// codigo mientras el grant esta vigente y NULL cuando se dio de baja. Varios NULL no
		// colisionan, asi que las filas historicas quedan fuera del unique. Un unique sobre
		// permission_code a secas impediria esta operacion, que es legitima y frecuente.
		Sesion tenant = altaCompleta("grants-reotorgar");
		long membership = membershipDelFundador(tenant);
		OperatingActor actor = actorDe(tenant);

		membershipService.assignGrant(actor, tenant.organizationId(), membership,
				"auditoria:read-clinica", MOTIVO, null);
		membershipService.revokeGrant(actor, tenant.organizationId(), membership,
				"auditoria:read-clinica", MOTIVO);

		assertThatCode(() -> membershipService.assignGrant(actor, tenant.organizationId(),
				membership, "auditoria:read-clinica", MOTIVO, null))
				.doesNotThrowAnyException();

		// Y el historial se conserva: dos filas, una activa.
		assertThat(contarGrants(membership, null)).isEqualTo(2L);
		assertThat(contarGrants(membership, true)).isEqualTo(1L);
	}

	@Test
	@DisplayName("Dos grants activos del mismo permiso sobre la misma membership: la base lo impide")
	void el_unique_de_grants_impide_el_duplicado() {
		Sesion tenant = altaCompleta("grants-duplicado");
		long membership = membershipDelFundador(tenant);

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO membership_grant (organization_id, membership_id, permission_code,
				                              granted_by_account_id, reason, valid_from, active,
				                              version, created_at, updated_at)
				VALUES (?, ?, 'AUDITORIA_READ_CLINICA', ?, 'sintetico', UTC_TIMESTAMP(6), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
				       (?, ?, 'AUDITORIA_READ_CLINICA', ?, 'sintetico', UTC_TIMESTAMP(6), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", tenant.organizationId(), membership, tenant.cuentaId(),
				tenant.organizationId(), membership, tenant.cuentaId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("Una cuenta no puede tener dos roles de plataforma activos, y si uno revocado y uno nuevo")
	void el_unique_de_platform_role() {
		Sesion cuenta = altaCompleta("rol-plataforma");
		sembrarRolDePlataforma(cuenta.cuentaId());

		assertThatThrownBy(() -> sembrarRolDePlataforma(cuenta.cuentaId()))
				.isInstanceOf(DataIntegrityViolationException.class);

		// Revocado sale del unique: se puede volver a otorgar conservando el historial.
		jdbc.update("UPDATE platform_role SET active = 0, valid_until = UTC_TIMESTAMP(6) "
				+ "WHERE account_id = ?", cuenta.cuentaId());
		assertThatCode(() -> sembrarRolDePlataforma(cuenta.cuentaId()))
				.doesNotThrowAnyException();

		Long filas = jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_role WHERE account_id = ?",
				Long.class, cuenta.cuentaId());
		assertThat(filas).as("el historial se conserva").isEqualTo(2L);
	}

	// =================================================================================
	// El CHECK de role_code
	// =================================================================================

	@Test
	@DisplayName("PLATFORM_ADMIN no es un valor legal de membership.role_code: lo impide la base")
	void el_check_impide_platform_admin_en_membership() {
		// La matriz seccion 1.3 dice que ese rol no tiene membership en ninguna organizacion, y
		// ADR-0020 le dio su propia tabla. V3 lo habia listado como valor valido en un
		// comentario: la migracion V11 cierra la contradiccion con un CHECK.
		Sesion tenant = altaCompleta("check-rol");

		assertThatThrownBy(() -> jdbc.update(
				"UPDATE membership SET role_code = 'PLATFORM_ADMIN' WHERE organization_id = ?",
				tenant.organizationId()))
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("ck_membership_role_code_no_plataforma");
	}

	// =================================================================================
	// Inmutabilidad de la auditoria
	// =================================================================================

	@Test
	@DisplayName("audit_event es append-only: la base rechaza UPDATE y DELETE (RN-M24-001)")
	void la_auditoria_es_inmutable() {
		// Hasta AKINE-01.03 la garantia dependia de que AuditEventRepository no heredara los
		// metodos de borrado: eso protege del descuido y no de un UPDATE por SQL directo, de un
		// script de mantenimiento o de un ORM mal configurado. Con los triggers, "append-only"
		// deja de ser una propiedad del codigo Java y pasa a ser una propiedad de la tabla.
		Sesion tenant = altaCompleta("auditoria-inmutable");
		Long evento = jdbc.queryForObject(
				"SELECT id FROM audit_event WHERE organization_id = ? ORDER BY id DESC LIMIT 1",
				Long.class, tenant.organizationId());
		assertThat(evento).as("el onboarding tuvo que dejar auditoria").isNotNull();

		assertThatThrownBy(() -> jdbc.update(
				"UPDATE audit_event SET reason = 'manipulado' WHERE id = ?", evento))
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("append-only");

		assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_event WHERE id = ?", evento))
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("append-only");

		// Y la fila sigue ahi, intacta.
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE id = ?", Long.class, evento))
				.isEqualTo(1L);
	}

	// =================================================================================
	// El seed del bootstrap
	// =================================================================================

	@Test
	@DisplayName("El seed dejo una cuenta de plataforma con rol vigente y SIN credencial usable")
	void el_seed_del_bootstrap() {
		// Decision D-2/D-14: el seed crea la cuenta Y la fila del rol juntas, porque resolver el
		// email contra `cuenta` sobre una base limpia inserta cero filas sin fallar y deja el
		// despliegue inicial sin ningun administrador, en silencio.
		Map<String, Object> cuenta = jdbc.queryForMap(
				"SELECT id, estado, password_hash FROM cuenta WHERE email_normalizado = ?",
				"plataforma@akine.app");

		assertThat(cuenta.get("estado")).isEqualTo("ACTIVA");
		// NINGUN SECRETO VERSIONADO: la unica via para tomar posesion es la recuperacion de
		// contrasena, que exige acceso a la casilla.
		assertThat(cuenta.get("password_hash")).isNull();

		long cuentaId = ((Number) cuenta.get("id")).longValue();
		assertThat(permissionEvaluator.isPlatformAdmin(cuentaId, java.time.Instant.now()))
				.as("la cuenta del bootstrap tiene el rol vigente")
				.isTrue();

		// Y esa cuenta no puede autenticarse con ninguna contrasena.
		Respuesta login = post("/api/v1/auth/login", null,
				"{\"email\":\"plataforma@akine.app\",\"password\":\"" + PASSWORD + "\"}");
		assertThat(login.status()).isEqualTo(401);
		assertProblemaLimpio(login);
	}

	// =================================================================================
	// El evaluador contra la base
	// =================================================================================

	@Test
	@DisplayName("Una membership de otro tenant no es alcanzable ni para leerla: 404, jamas 403")
	void cross_tenant_es_404() {
		Sesion propio = altaCompleta("cross-propio");
		Sesion ajeno = altaCompleta("cross-ajeno");
		long membershipAjena = membershipDelFundador(ajeno);

		// Se pide una membership que EXISTE, con su id real, desde otro tenant. Un 403
		// confirmaria que ese id existe y bastarian ids consecutivos para enumerar a los
		// colaboradores de los demas centros.
		assertThatThrownBy(() -> membershipService.find(
				actorDe(propio), propio.organizationId(), membershipAjena))
				.isInstanceOf(
						com.akine.organization.domain.exception.MembershipNotAccessibleException.class);
	}

	@Test
	@DisplayName("Los permisos efectivos del fundador salen de la matriz, leidos de la base")
	void los_permisos_efectivos_del_fundador() {
		Sesion tenant = altaCompleta("permisos-efectivos");

		// `paciente:manage` se sumo en AKINE-03.01, cuando nacio el modulo que lo evalua: la
		// matriz §4 se lo da al ORG_ADMIN —y el fundador lo es— con alcance organizacion.
		assertThat(permissionEvaluator.effectivePermissions(
				tenant.cuentaId(), tenant.organizationId(), tenant.consultorioId()))
				.containsExactlyInAnyOrder("tenant:read", "consultorio:manage", "espacio:read",
						"colaborador:manage", "colaborador:read", "auditoria:read",
						"paciente:manage", "turno:read", "turno:manage");
	}

	@Test
	@DisplayName("Una revocacion se ve en el request siguiente: la ventana es cero")
	void la_ventana_de_revocacion_es_cero() {
		Sesion tenant = altaCompleta("ventana-cero");
		Sesion companiero = altaCompleta("ventana-cero-companiero");

		long membership = membershipService.createDirect(
				tenant.cuentaId(), false, tenant.organizationId(),
				new DirectMembershipCommand(companiero.cuentaId(), tenant.consultorioId(),
						"PROFESIONAL", MOTIVO));

		// `hc:read` y `hc:write` se sumaron en AKINE-04.01: la matriz §2 se los da al PROFESIONAL
		// con alcance de su sede.
		assertThat(permissionEvaluator.effectivePermissions(
				companiero.cuentaId(), tenant.organizationId(), tenant.consultorioId()))
				.containsExactlyInAnyOrder(
						"colaborador:read", "espacio:read", "turno:read", "turno:manage", "sesion:register",
						"hc:read", "hc:write");

		membershipService.revoke(actorDe(tenant), tenant.organizationId(), membership, MOTIVO);

		// Sin cache: la revocacion surte efecto de inmediato, no cuando venza un TTL.
		assertThat(permissionEvaluator.effectivePermissions(
				companiero.cuentaId(), tenant.organizationId(), tenant.consultorioId()))
				.isEmpty();
	}

	@Test
	@DisplayName("El alta directa deja auditoria con actor, motivo y rol")
	void el_alta_directa_deja_auditoria() {
		Sesion tenant = altaCompleta("alta-directa");
		Sesion companiero = altaCompleta("alta-directa-companiero");

		long membership = membershipService.createDirect(
				tenant.cuentaId(), false, tenant.organizationId(),
				new DirectMembershipCommand(companiero.cuentaId(), null, "ADMINISTRATIVO", MOTIVO));

		List<Map<String, Object>> eventos = jdbc.queryForList("""
				SELECT event_type, actor_account_id, new_state, reason
				  FROM audit_event
				 WHERE entity_type = 'Membership' AND entity_id = ?
				""", membership);

		assertThat(eventos).singleElement().satisfies(e -> {
			assertThat(e.get("event_type")).isEqualTo("MEMBERSHIP_CREATED");
			assertThat(((Number) e.get("actor_account_id")).longValue())
					.isEqualTo(tenant.cuentaId());
			assertThat(e.get("new_state")).isEqualTo("ADMINISTRATIVO");
			assertThat(e.get("reason")).isEqualTo(MOTIVO);
		});
	}

	@Test
	@DisplayName("Una mutacion que falla no deja NINGUNA fila de auditoria")
	void una_mutacion_fallida_no_audita() {
		// La contracara de "la auditoria se escribe en la transaccion del negocio": si la
		// operacion no se confirma, tampoco su rastro. Una auditoria que dice que algo paso
		// cuando no paso es peor que no tenerla, porque se le cree.
		Sesion tenant = altaCompleta("mutacion-fallida");
		long membership = membershipDelFundador(tenant);
		long eventosAntes = eventosDe(membership);

		assertThatThrownBy(() -> membershipService.revoke(
				actorDe(tenant), tenant.organizationId(), membership, MOTIVO))
				.isInstanceOf(
						com.akine.organization.domain.exception.SelfRevokeNotAllowedException.class);

		assertThat(eventosDe(membership)).isEqualTo(eventosAntes);
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private OperatingActor actorDe(Sesion sesion) {
		return new OperatingActor(sesion.cuentaId(), false, sesion.consultorioId());
	}

	private long membershipDelFundador(Sesion sesion) {
		Long id = jdbc.queryForObject(
				"SELECT id FROM membership WHERE organization_id = ? AND account_id = ?",
				Long.class, sesion.organizationId(), sesion.cuentaId());
		assertThat(id).isNotNull();
		return id;
	}

	private long contarGrants(long membershipId, Boolean activo) {
		String sql = "SELECT COUNT(*) FROM membership_grant WHERE membership_id = ?"
				+ (activo == null ? "" : " AND active = " + (activo ? 1 : 0));
		Long total = jdbc.queryForObject(sql, Long.class, membershipId);
		return total == null ? 0L : total;
	}

	private long eventosDe(long membershipId) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE entity_type = 'Membership' AND entity_id = ?",
				Long.class, membershipId);
		return total == null ? 0L : total;
	}
}
