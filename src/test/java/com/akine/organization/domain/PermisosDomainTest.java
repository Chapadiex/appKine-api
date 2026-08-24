package com.akine.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El modelo que AKINE-01.03 agrega al dominio de {@code organization}.
 *
 * <p>Lo que se prueba en todos los casos es lo mismo, porque es la regla que se rompe sola:
 * <b>vigencia, baja logica y estado son condiciones independientes y todas tienen que
 * cumplirse</b>. Un test por cada una, por separado, porque una implementacion que solo mire
 * {@code active} pasa cualquier test que las combine.
 */
class PermisosDomainTest {

	private static final long ORG_ID = 10L;
	private static final long CONSULTORIO_ID = 20L;
	private static final long ACCOUNT_ID = 30L;
	private static final long MEMBERSHIP_ID = 60L;
	private static final long ACTOR_ID = 31L;

	private static final Instant AYER = Instant.now().minus(1, ChronoUnit.DAYS);
	private static final Instant AHORA = Instant.now();
	private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);

	private static Membership membershipActiva() {
		return new Membership(ORG_ID, null, ACCOUNT_ID, RoleCode.ORG_ADMIN, false, AYER);
	}

	// =================================================================================
	// Estado de la membership
	// =================================================================================

	@Nested
	@DisplayName("Maquina de estados de la membership")
	class Estados {

		@Test
		@DisplayName("Solo ACTIVA habilita: suspendida y revocada no habilitan nada")
		void solo_activa_habilita() {
			assertThat(MembershipEstado.ACTIVA.habilita()).isTrue();
			assertThat(MembershipEstado.SUSPENDIDA.habilita()).isFalse();
			assertThat(MembershipEstado.REVOCADA.habilita()).isFalse();
		}

		@Test
		@DisplayName("REVOCADA es terminal: no hay transicion de salida")
		void revocada_es_terminal() {
			for (MembershipEstado destino : MembershipEstado.values()) {
				assertThat(MembershipEstado.REVOCADA.puedePasarA(destino))
						.as("REVOCADA no puede pasar a %s: la fila queda, el vinculo no vuelve", destino)
						.isFalse();
			}
		}

		@Test
		@DisplayName("Suspender es reversible; revocar no")
		void suspender_es_reversible() {
			assertThat(MembershipEstado.ACTIVA.puedePasarA(MembershipEstado.SUSPENDIDA)).isTrue();
			assertThat(MembershipEstado.SUSPENDIDA.puedePasarA(MembershipEstado.ACTIVA)).isTrue();
			assertThat(MembershipEstado.ACTIVA.puedePasarA(MembershipEstado.REVOCADA)).isTrue();
			assertThat(MembershipEstado.SUSPENDIDA.puedePasarA(MembershipEstado.REVOCADA)).isTrue();
		}

		@Test
		@DisplayName("Un estado no admite pasar a si mismo: repetir no es un no-op silencioso")
		void repetir_el_estado_no_es_valido() {
			// Dos administradores suspendiendo a la vez tienen que enterarse de que el otro
			// llego primero. Un no-op "exitoso" ademas escribiria una fila de auditoria que
			// dice algo que no paso.
			assertThat(MembershipEstado.ACTIVA.puedePasarA(MembershipEstado.ACTIVA)).isFalse();
			assertThat(MembershipEstado.SUSPENDIDA.puedePasarA(MembershipEstado.SUSPENDIDA)).isFalse();
		}
	}

	// =================================================================================
	// Membership: las tres condiciones
	// =================================================================================

	@Nested
	@DisplayName("Vigencia de la membership")
	class VigenciaDeMembership {

		@Test
		@DisplayName("Una membership suspendida NO habilita, aunque este activa y vigente")
		void suspendida_no_habilita() {
			Membership membership = membershipActiva();
			assertThat(membership.isValidAt(AHORA)).isTrue();

			membership.suspender();

			// Sin esta condicion, suspender no significaria nada: la fila seguiria activa y con
			// la vigencia abierta, y el contexto se resolveria igual.
			assertThat(membership.isValidAt(AHORA)).isFalse();
		}

		@Test
		@DisplayName("Reactivar devuelve la vigencia")
		void reactivar_devuelve_la_vigencia() {
			Membership membership = membershipActiva();
			membership.suspender();
			membership.reactivar();

			assertThat(membership.getEstado()).isEqualTo(MembershipEstado.ACTIVA);
			assertThat(membership.isValidAt(AHORA)).isTrue();
		}

		@Test
		@DisplayName("Revocar cierra vigencia, marca baja logica y deja quien y por que")
		void revocar_deja_rastro() {
			Membership membership = membershipActiva();

			membership.revocar(ACTOR_ID, "cambio de centro", AHORA);

			assertThat(membership.getEstado()).isEqualTo(MembershipEstado.REVOCADA);
			assertThat(membership.getRevokedByAccountId()).isEqualTo(ACTOR_ID);
			assertThat(membership.getRevokedReason()).isEqualTo("cambio de centro");
			assertThat(membership.getValidUntil()).isEqualTo(AHORA);
			assertThat(membership.isActive()).isFalse();
			assertThat(membership.getDeletedAt()).isEqualTo(AHORA);
			assertThat(membership.isValidAt(AHORA)).isFalse();
		}

		@Test
		@DisplayName("Revocar sin motivo se rechaza: sin motivo no hay auditoria util")
		void revocar_exige_motivo() {
			assertThatThrownBy(() -> membershipActiva().revocar(ACTOR_ID, "  ", AHORA))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("motivo");
		}

		@Test
		@DisplayName("Una membership revocada no vuelve: la transicion se rechaza")
		void una_revocada_no_vuelve() {
			Membership membership = membershipActiva();
			membership.revocar(ACTOR_ID, "motivo", AHORA);

			assertThatThrownBy(membership::reactivar).isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(membership::suspender).isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> membership.revocar(ACTOR_ID, "otra vez", AHORA))
					.isInstanceOf(IllegalStateException.class);
		}
	}

	@Nested
	@DisplayName("Cambio de rol y de alcance")
	class Cambios {

		@Test
		@DisplayName("PLATFORM_ADMIN no es un rol de membership")
		void platform_admin_no_es_rol_de_membership() {
			// La matriz §1.3 dice que ese rol no tiene membership en ninguna organizacion, y
			// ADR-0020 le dio su propia tabla. La base lo impide con un CHECK; esto lo impide
			// antes, para que el error sea comprensible y no una violacion de constraint.
			assertThatThrownBy(() -> membershipActiva().changeRole(RoleCode.PLATFORM_ADMIN))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("platform_role");
		}

		@ParameterizedTest
		@EnumSource(value = RoleCode.class, mode = EnumSource.Mode.EXCLUDE, names = "PLATFORM_ADMIN")
		@DisplayName("Cualquier otro rol de la matriz se puede asignar")
		void los_demas_roles_se_asignan(RoleCode rol) {
			Membership membership = membershipActiva();
			membership.changeRole(rol);
			assertThat(membership.getRoleCode()).isEqualTo(rol);
		}

		@Test
		@DisplayName("No se le cambia el rol ni el alcance a un vinculo que ya no esta activo")
		void no_se_muta_un_vinculo_inactivo() {
			Membership membership = membershipActiva();
			membership.suspender();

			assertThatThrownBy(() -> membership.changeRole(RoleCode.PROFESIONAL))
					.isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> membership.changeScope(CONSULTORIO_ID))
					.isInstanceOf(IllegalStateException.class);
		}

		@Test
		@DisplayName("Cambiar el alcance a una sede deja de cubrir el resto de la organizacion")
		void cambiar_el_alcance_acota() {
			Membership membership = membershipActiva();
			assertThat(membership.isOrganizationScoped()).isTrue();
			assertThat(membership.covers(999L)).isTrue();

			membership.changeScope(CONSULTORIO_ID);

			assertThat(membership.isOrganizationScoped()).isFalse();
			assertThat(membership.covers(CONSULTORIO_ID)).isTrue();
			assertThat(membership.covers(999L)).isFalse();
		}
	}

	// =================================================================================
	// MembershipGrant
	// =================================================================================

	@Nested
	@DisplayName("Permiso adicional")
	class Grants {

		private MembershipGrant grant(Instant validUntil) {
			return new MembershipGrant(ORG_ID, MEMBERSHIP_ID,
					PermissionCode.AUDITORIA_READ_CLINICA, ACTOR_ID, "auditoria interna",
					AYER, validUntil);
		}

		@Test
		@DisplayName("Un grant sin motivo no se puede crear")
		void el_motivo_es_obligatorio() {
			assertThatThrownBy(() -> new MembershipGrant(ORG_ID, MEMBERSHIP_ID,
					PermissionCode.AUDITORIA_READ_CLINICA, ACTOR_ID, null, AYER, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("motivo");
		}

		@Test
		@DisplayName("Sin fin declarado, el grant vale mientras este activo")
		void sin_fin_vale_mientras_este_activo() {
			assertThat(grant(null).isValidAt(AHORA)).isTrue();
		}

		@Test
		@DisplayName("Un grant vencido no habilita aunque siga activo")
		void un_grant_vencido_no_habilita() {
			assertThat(grant(AYER).isValidAt(AHORA)).isFalse();
		}

		@Test
		@DisplayName("Un grant futuro no habilita todavia")
		void un_grant_futuro_no_habilita() {
			MembershipGrant futuro = new MembershipGrant(ORG_ID, MEMBERSHIP_ID,
					PermissionCode.AUDITORIA_READ_CLINICA, ACTOR_ID, "programado",
					MANIANA, null);
			assertThat(futuro.isValidAt(AHORA)).isFalse();
		}

		@Test
		@DisplayName("Revocar es baja logica: cierra vigencia y deja quien y por que")
		void revocar_es_baja_logica() {
			MembershipGrant grant = grant(null);

			grant.revoke(ACTOR_ID, "ya no lo necesita", AHORA);

			assertThat(grant.isActive()).isFalse();
			assertThat(grant.getDeletedAt()).isEqualTo(AHORA);
			assertThat(grant.getValidUntil()).isEqualTo(AHORA);
			assertThat(grant.getRevokedByAccountId()).isEqualTo(ACTOR_ID);
			assertThat(grant.getRevokedReason()).isEqualTo("ya no lo necesita");
			assertThat(grant.isValidAt(AHORA)).isFalse();
		}

		@Test
		@DisplayName("Los datos del grant quedan disponibles para explicarlo")
		void los_datos_quedan_disponibles() {
			MembershipGrant grant = grant(MANIANA);
			assertThat(grant.getOrganizationId()).isEqualTo(ORG_ID);
			assertThat(grant.getMembershipId()).isEqualTo(MEMBERSHIP_ID);
			assertThat(grant.getPermissionCode()).isEqualTo(PermissionCode.AUDITORIA_READ_CLINICA);
			assertThat(grant.getGrantedByAccountId()).isEqualTo(ACTOR_ID);
			assertThat(grant.getReason()).isEqualTo("auditoria interna");
			assertThat(grant.getValidFrom()).isEqualTo(AYER);
			assertThat(grant.getValidUntil()).isEqualTo(MANIANA);
			assertThat(grant.getVersion()).isZero();
			assertThat(grant.getId()).isNull();
		}
	}

	// =================================================================================
	// PlatformRole
	// =================================================================================

	@Nested
	@DisplayName("Rol de plataforma")
	class RolDePlataforma {

		@Test
		@DisplayName("Siempre nace como PLATFORM_ADMIN: no es una tabla de roles generica")
		void siempre_es_platform_admin() {
			PlatformRole rol = new PlatformRole(ACCOUNT_ID, null, "bootstrap", AYER);
			assertThat(rol.getRoleCode()).isEqualTo(RoleCode.PLATFORM_ADMIN);
			assertThat(rol.getGrantedByAccountId()).isNull();
			assertThat(rol.isValidAt(AHORA)).isTrue();
		}

		@Test
		@DisplayName("El permiso mas alto del sistema no se otorga sin motivo")
		void el_motivo_es_obligatorio() {
			assertThatThrownBy(() -> new PlatformRole(ACCOUNT_ID, ACTOR_ID, "", AYER))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("Revocar corta la vigencia y conserva la fila")
		void revocar_conserva_la_fila() {
			PlatformRole rol = new PlatformRole(ACCOUNT_ID, ACTOR_ID, "alta", AYER);

			rol.revoke(ACTOR_ID, "baja del equipo", AHORA);

			assertThat(rol.isValidAt(AHORA)).isFalse();
			assertThat(rol.isActive()).isFalse();
			assertThat(rol.getValidUntil()).isEqualTo(AHORA);
			assertThat(rol.getRevokedByAccountId()).isEqualTo(ACTOR_ID);
			assertThat(rol.getRevokedReason()).isEqualTo("baja del equipo");
			assertThat(rol.getDeletedAt()).isEqualTo(AHORA);
			// Quien tuvo el rol y desde cuando sigue siendo legible: la fila no se borra.
			assertThat(rol.getAccountId()).isEqualTo(ACCOUNT_ID);
			assertThat(rol.getReason()).isEqualTo("alta");
			assertThat(rol.getValidFrom()).isEqualTo(AYER);
		}

		@Test
		@DisplayName("Un rol con vigencia futura todavia no habilita")
		void un_rol_futuro_no_habilita() {
			assertThat(new PlatformRole(ACCOUNT_ID, ACTOR_ID, "programado", MANIANA)
					.isValidAt(AHORA)).isFalse();
		}
	}

	// =================================================================================
	// SupportAccess
	// =================================================================================

	@Nested
	@DisplayName("Acceso de soporte")
	class AccesoDeSoporte {

		private SupportAccess acceso(Instant hasta) {
			return new SupportAccess(ORG_ID, ACCOUNT_ID, "incidente 123", ACCOUNT_ID, AYER, hasta);
		}

		@Test
		@DisplayName("Sin motivo no hay acceso de soporte")
		void el_motivo_es_obligatorio() {
			assertThatThrownBy(() -> new SupportAccess(
					ORG_ID, ACCOUNT_ID, null, ACCOUNT_ID, AYER, MANIANA))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("motivo");
		}

		@Test
		@DisplayName("Un acceso de soporte sin fin no se puede crear: eso no es soporte")
		void el_vencimiento_es_obligatorio() {
			// Es la diferencia con un grant comun, y esta en la matriz §3: "acotado en tiempo".
			assertThatThrownBy(() -> new SupportAccess(
					ORG_ID, ACCOUNT_ID, "incidente", ACCOUNT_ID, AYER, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("vence");
		}

		@Test
		@DisplayName("Un fin anterior al inicio tampoco: seria un acceso ya vencido al nacer")
		void el_fin_es_posterior_al_inicio() {
			assertThatThrownBy(() -> new SupportAccess(
					ORG_ID, ACCOUNT_ID, "incidente", ACCOUNT_ID, AHORA, AYER))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("Vigente mientras no venza ni se revoque")
		void vigente_hasta_que_venza() {
			assertThat(acceso(MANIANA).isValidAt(AHORA)).isTrue();
			assertThat(acceso(AHORA.minusMillis(1)).isValidAt(AHORA)).isFalse();
		}

		@Test
		@DisplayName("Revocar corta el acceso antes del vencimiento, sin borrar la fila")
		void revocar_corta_antes_del_vencimiento() {
			SupportAccess acceso = acceso(MANIANA);

			acceso.revoke(ACTOR_ID, AHORA);

			assertThat(acceso.isValidAt(AHORA)).isFalse();
			assertThat(acceso.getRevokedAt()).isEqualTo(AHORA);
			assertThat(acceso.getRevokedByAccountId()).isEqualTo(ACTOR_ID);
			assertThat(acceso.isActive()).isFalse();
			assertThat(acceso.getDeletedAt()).isEqualTo(AHORA);
			// Que alguien entro y por que sigue siendo legible.
			assertThat(acceso.getReason()).isEqualTo("incidente 123");
			assertThat(acceso.getOrganizationId()).isEqualTo(ORG_ID);
			assertThat(acceso.getAccountId()).isEqualTo(ACCOUNT_ID);
			assertThat(acceso.getGrantedByAccountId()).isEqualTo(ACCOUNT_ID);
			assertThat(acceso.getValidFrom()).isEqualTo(AYER);
			assertThat(acceso.getValidUntil()).isEqualTo(MANIANA);
			assertThat(acceso.getVersion()).isZero();
		}

		@Test
		@DisplayName("La vigencia por defecto es de cuatro horas (decision D-3 del usuario)")
		void la_vigencia_por_defecto() {
			assertThat(SupportAccess.VIGENCIA_POR_DEFECTO.toHours()).isEqualTo(4);
		}

		@Test
		@DisplayName("Un acceso que todavia no empezo no habilita")
		void un_acceso_futuro_no_habilita() {
			SupportAccess futuro = new SupportAccess(
					ORG_ID, ACCOUNT_ID, "programado", ACCOUNT_ID, MANIANA,
					MANIANA.plus(4, ChronoUnit.HOURS));
			assertThat(futuro.isValidAt(AHORA)).isFalse();
		}
	}

	// =================================================================================
	// Excepciones
	// =================================================================================

	@Test
	@DisplayName("Las excepciones de permisos llevan lo necesario para el log, no para el cuerpo")
	void las_excepciones_llevan_contexto() {
		var denegado = new com.akine.organization.domain.exception.PermissionDeniedException(
				"colaborador:manage", ACCOUNT_ID, ORG_ID);
		assertThat(denegado.getPermissionCode()).isEqualTo("colaborador:manage");
		assertThat(denegado.getAccountId()).isEqualTo(ACCOUNT_ID);
		assertThat(denegado.getOrganizationId()).isEqualTo(ORG_ID);

		var noAccesible = new com.akine.organization.domain.exception
				.MembershipNotAccessibleException(MEMBERSHIP_ID);
		assertThat(noAccesible.getMembershipId()).isEqualTo(MEMBERSHIP_ID);
		// El mensaje no distingue "no existe" de "es de otro tenant": esa indistincion es la
		// proteccion.
		assertThat(noAccesible.getMessage()).doesNotContain("otro", "ajena");

		var ultimoAdmin = new com.akine.organization.domain.exception.LastAdminException(ORG_ID);
		assertThat(ultimoAdmin.getOrganizationId()).isEqualTo(ORG_ID);

		var selfRevoke = new com.akine.organization.domain.exception
				.SelfRevokeNotAllowedException(ACCOUNT_ID);
		assertThat(selfRevoke.getAccountId()).isEqualTo(ACCOUNT_ID);

		var fundador = new com.akine.organization.domain.exception
				.FounderRevocationNotAllowedException(MEMBERSHIP_ID);
		assertThat(fundador.getMembershipId()).isEqualTo(MEMBERSHIP_ID);

		var noActiva = new com.akine.organization.domain.exception.MembershipNotActiveException(
				MEMBERSHIP_ID, MembershipEstado.REVOCADA, MembershipEstado.ACTIVA);
		assertThat(noActiva.getMembershipId()).isEqualTo(MEMBERSHIP_ID);
		assertThat(noActiva.getEstadoActual()).isEqualTo(MembershipEstado.REVOCADA);
		assertThat(noActiva.getEstadoPedido()).isEqualTo(MembershipEstado.ACTIVA);

		var grantDuplicado = new com.akine.organization.domain.exception
				.GrantAlreadyActiveException("auditoria:read-clinica", MEMBERSHIP_ID);
		assertThat(grantDuplicado.getPermissionCode()).isEqualTo("auditoria:read-clinica");
		assertThat(grantDuplicado.getMembershipId()).isEqualTo(MEMBERSHIP_ID);

		var yaExiste = new com.akine.organization.domain.exception
				.MembershipAlreadyExistsException(ORG_ID, CONSULTORIO_ID);
		assertThat(yaExiste.getOrganizationId()).isEqualTo(ORG_ID);
		assertThat(yaExiste.getConsultorioId()).isEqualTo(CONSULTORIO_ID);
		// El mensaje nombra el vinculo revocado: es el caso que mas desconcierta (D-13).
		assertThat(yaExiste.getMessage()).contains("revocado");

		var codigoDesconocido = new com.akine.organization.domain.exception
				.UnknownPermissionCodeException("no:existe", "detalle");
		assertThat(codigoDesconocido.getPermissionCode()).isEqualTo("no:existe");
		assertThat(codigoDesconocido.getMessage()).isEqualTo("detalle");

		var soporte = new com.akine.organization.domain.exception
				.SupportAccessNotFoundException(7L);
		assertThat(soporte.getSupportAccessId()).isEqualTo(7L);

		var rolPlataforma = new com.akine.organization.domain.exception
				.PlatformRoleNotFoundException(8L);
		assertThat(rolPlataforma.getPlatformRoleId()).isEqualTo(8L);
	}

	@Test
	@DisplayName("Los constructores protegidos de JPA existen y no rompen")
	void los_constructores_de_jpa() {
		// JPA los necesita. Se ejercitan por reflexion, que es lo que hace el propio Hibernate.
		assertThatCode(() -> {
			org.springframework.beans.BeanUtils.instantiateClass(
					MembershipGrant.class.getDeclaredConstructor());
			org.springframework.beans.BeanUtils.instantiateClass(
					PlatformRole.class.getDeclaredConstructor());
			org.springframework.beans.BeanUtils.instantiateClass(
					SupportAccess.class.getDeclaredConstructor());
		}).doesNotThrowAnyException();
	}
}
