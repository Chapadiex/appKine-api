package com.akine.organization.application;

import com.akine.organization.domain.Membership;
import com.akine.organization.domain.MembershipGrant;
import com.akine.organization.domain.PermissionCode;
import com.akine.organization.domain.PermissionScope;
import com.akine.organization.domain.PlatformRole;
import com.akine.organization.domain.RoleCode;
import com.akine.organization.domain.SupportAccess;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.PermissionDeniedException;
import com.akine.organization.domain.port.MembershipGrantRepositoryPort;
import com.akine.organization.domain.port.MembershipRepositoryPort;
import com.akine.organization.domain.port.PlatformRoleRepositoryPort;
import com.akine.organization.domain.port.SupportAccessRepositoryPort;
import com.akine.organization.spi.DenialKind;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.akine.organization.application.Fixtures.ACCOUNT_ID;
import static com.akine.organization.application.Fixtures.CONSULTORIO_ID;
import static com.akine.organization.application.Fixtures.MEMBERSHIP_ID;
import static com.akine.organization.application.Fixtures.ORG_ID;
import static com.akine.organization.application.Fixtures.OTRO_CONSULTORIO_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * El evaluador de la matriz, con puertos falsos y sin base de datos.
 *
 * <p>Lo que se prueba es el algoritmo: <b>que membership gobierna</b>, <b>que el alcance se
 * verifica aparte del permiso</b>, y <b>que cada forma de no estar vigente deniega por
 * separado</b>. Las cuatro condiciones de vigencia van una por test a proposito: una
 * implementacion que solo mire {@code active} pasa cualquier test que las combine.
 *
 * <p>Que rol tiene que celda de la matriz <b>no se prueba aca</b>: eso es
 * {@code RolePermissionsTest}, que compara contra la matriz usada como fixture.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionEvaluatorServiceTest {

	private static final long OTRA_CUENTA = 31L;
	private static final Instant AHORA = Instant.now();

	@Mock
	private MembershipRepositoryPort membershipRepository;

	@Mock
	private MembershipGrantRepositoryPort grantRepository;

	@Mock
	private PlatformRoleRepositoryPort platformRoleRepository;

	@Mock
	private SupportAccessRepositoryPort supportAccessRepository;

	@Mock
	private PermissionDenialAuditor denialAuditor;

	@InjectMocks
	private PermissionEvaluatorService evaluator;

	// ------------------------------------------------------------------ apoyo

	private void memberships(Membership... memberships) {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(
				ORG_ID, ACCOUNT_ID)).willReturn(List.of(memberships));
	}

	private void objetivoEsMiembro(long cuenta, boolean loEs) {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(
				ORG_ID, cuenta))
				.willReturn(loEs ? List.of(Fixtures.membershipVigente()) : List.of());
	}

	private void sinRolDePlataforma() {
		given(platformRoleRepository.findAllByAccountIdAndActiveTrue(anyLong()))
				.willReturn(List.of());
	}

	private void conRolDePlataforma() {
		given(platformRoleRepository.findAllByAccountIdAndActiveTrue(ACCOUNT_ID))
				.willReturn(List.of(new PlatformRole(
						ACCOUNT_ID, null, "bootstrap", AHORA.minus(1, ChronoUnit.DAYS))));
	}

	private void conSoporteVigente() {
		given(supportAccessRepository.findAllByOrganizationIdAndAccountIdAndActiveTrue(
				ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(new SupportAccess(
						ORG_ID, ACCOUNT_ID, "incidente sintetico", ACCOUNT_ID,
						AHORA.minus(1, ChronoUnit.HOURS), AHORA.plus(3, ChronoUnit.HOURS))));
	}

	private PermissionDecision decidir(PermissionCode permiso, Long consultorioId) {
		return evaluator.evaluate(new PermissionQuery(
				ACCOUNT_ID, permiso.code(), ORG_ID, consultorioId, null, AHORA));
	}

	// =================================================================================
	// Alcance
	// =================================================================================

	@Nested
	@DisplayName("Alcance")
	class Alcance {

		@Test
		@DisplayName("Una membership de organizacion habilita cualquier sede del tenant")
		void la_de_organizacion_habilita_todas_las_sedes() {
			sinRolDePlataforma();
			memberships(Fixtures.membershipVigenteCon(RoleCode.ORG_ADMIN));

			assertThat(decidir(PermissionCode.COLABORADOR_MANAGE, CONSULTORIO_ID).granted()).isTrue();
			assertThat(decidir(PermissionCode.COLABORADOR_MANAGE, OTRO_CONSULTORIO_ID).granted()).isTrue();
			assertThat(decidir(PermissionCode.COLABORADOR_MANAGE, null).granted()).isTrue();
		}

		@Test
		@DisplayName("Una membership de sede NO habilita otra sede")
		void la_de_sede_no_habilita_otra_sede() {
			sinRolDePlataforma();
			memberships(Fixtures.membershipDeSede(CONSULTORIO_ID, RoleCode.CONSULTORIO_ADMIN));

			assertThat(decidir(PermissionCode.COLABORADOR_MANAGE, CONSULTORIO_ID).granted()).isTrue();

			PermissionDecision otraSede =
					decidir(PermissionCode.COLABORADOR_MANAGE, OTRO_CONSULTORIO_ID);
			// Es miembro de la organizacion, asi que el recurso existe para el: 403 y no 404.
			assertThat(otraSede.granted()).isFalse();
			assertThat(otraSede.denial()).isEqualTo(DenialKind.NO_PERMISSION);
		}

		@Test
		@DisplayName("Una membership de sede no puede decidir sobre la organizacion entera")
		void la_de_sede_no_decide_sobre_el_tenant() {
			// Sin consultorio en la consulta, la decision es sobre el tenant. Aceptar una
			// membership de sede seria la escalada de privilegio silenciosa que aparece sola en
			// cuanto existan memberships por sede.
			sinRolDePlataforma();
			memberships(Fixtures.membershipDeSede(CONSULTORIO_ID, RoleCode.CONSULTORIO_ADMIN));

			PermissionDecision decision = decidir(PermissionCode.COLABORADOR_MANAGE, null);

			assertThat(decision.granted()).isFalse();
			assertThat(decision.denial()).isEqualTo(DenialKind.NO_PERMISSION);
		}

		@Test
		@DisplayName("Con dos memberships gana la mas especifica, no la mas privilegiada")
		void gana_la_mas_especifica() {
			// El criterio esta centralizado en MembershipSelection y AKINE-01.03 lo revisó y lo
			// mantuvo: la fila de sede existe para decir algo distinto de la general. Ganando la
			// mas privilegiada, escribirla no tendria ningun efecto.
			sinRolDePlataforma();
			memberships(
					Fixtures.membershipVigenteCon(RoleCode.ORG_ADMIN),
					Fixtures.conId(new Membership(ORG_ID, CONSULTORIO_ID, ACCOUNT_ID,
							RoleCode.PROFESIONAL, false, AHORA.minus(30, ChronoUnit.DAYS)), 61L));

			// En la sede gobierna la de PROFESIONAL: no administra.
			assertThat(decidir(PermissionCode.COLABORADOR_MANAGE, CONSULTORIO_ID).granted()).isFalse();
			// Y sigue leyendo, que es lo que PROFESIONAL si tiene en la matriz.
			assertThat(decidir(PermissionCode.COLABORADOR_READ, CONSULTORIO_ID).granted()).isTrue();
			// Sobre la organizacion entera manda la de alcance organizacion.
			assertThat(decidir(PermissionCode.COLABORADOR_MANAGE, null).granted()).isTrue();
		}

		@Test
		@DisplayName("La decision informa con que alcance se concedio")
		void la_decision_informa_el_alcance() {
			sinRolDePlataforma();
			memberships(Fixtures.membershipDeSede(CONSULTORIO_ID, RoleCode.CONSULTORIO_ADMIN));

			assertThat(decidir(PermissionCode.AUDITORIA_READ, CONSULTORIO_ID).grantedByScope())
					.isEqualTo(PermissionScope.CONSULTORIO.name());

			memberships(Fixtures.membershipVigenteCon(RoleCode.ORG_ADMIN));
			assertThat(decidir(PermissionCode.AUDITORIA_READ, CONSULTORIO_ID).grantedByScope())
					.isEqualTo(PermissionScope.ORGANIZACION.name());
		}

		@Test
		@DisplayName("ACTIVIDAD_PROPIA cubre la sede del profesional, la informa, y no otra sede")
		void la_actividad_propia_cubre_su_sede_y_lo_dice() {
			// G-1 (DP-15). El evaluador decide QUE sede alcanza; QUE filas las recorta quien lee el
			// dato. Por eso lo que importa ademas del granted es el alcance que viaja.
			sinRolDePlataforma();
			memberships(Fixtures.membershipDeSede(CONSULTORIO_ID, RoleCode.PROFESIONAL));

			PermissionDecision suSede = decidir(PermissionCode.REPORTE_READ, CONSULTORIO_ID);
			assertThat(suSede.granted()).isTrue();
			assertThat(suSede.grantedByScope()).isEqualTo(PermissionScope.ACTIVIDAD_PROPIA.name());
			assertThat(suSede.limitadaAActividadPropia()).isTrue();

			PermissionDecision otraSede = decidir(PermissionCode.REPORTE_READ, OTRO_CONSULTORIO_ID);
			assertThat(otraSede.granted()).isFalse();
			assertThat(otraSede.denial()).isEqualTo(DenialKind.NO_PERMISSION);

			// Sin sede en la consulta no cubre nada, igual que CONSULTORIO.
			assertThat(decidir(PermissionCode.REPORTE_READ, null).granted()).isFalse();
		}

		@Test
		@DisplayName("El ADMINISTRATIVO tiene reporte:read de sede, sin recorte por actividad")
		void el_administrativo_no_queda_limitado_a_su_actividad() {
			sinRolDePlataforma();
			memberships(Fixtures.membershipDeSede(CONSULTORIO_ID, RoleCode.ADMINISTRATIVO));

			PermissionDecision decision = decidir(PermissionCode.REPORTE_READ, CONSULTORIO_ID);
			assertThat(decision.granted()).isTrue();
			assertThat(decision.limitadaAActividadPropia()).isFalse();
		}
	}

	// =================================================================================
	// Vigencia: cuatro formas de no estar vigente, cuatro tests
	// =================================================================================

	@Nested
	@DisplayName("Vigencia")
	class Vigencia {

		@Test
		@DisplayName("Sin ninguna membership en el tenant es 404, no 403")
		void sin_membership_es_fuera_de_alcance() {
			sinRolDePlataforma();
			memberships();

			PermissionDecision decision = decidir(PermissionCode.COLABORADOR_READ, CONSULTORIO_ID);

			// Un 403 confirmaria que esa organizacion existe: bastarian ids consecutivos para
			// enumerar los clientes del SaaS.
			assertThat(decision.denial()).isEqualTo(DenialKind.OUT_OF_SCOPE);
		}

		@Test
		@DisplayName("Con la vigencia vencida tampoco: es como no tenerla")
		void una_membership_vencida_no_habilita() {
			sinRolDePlataforma();
			memberships(Fixtures.membershipVencida());

			assertThat(decidir(PermissionCode.COLABORADOR_READ, CONSULTORIO_ID).denial())
					.isEqualTo(DenialKind.OUT_OF_SCOPE);
		}

		@Test
		@DisplayName("Con la vigencia todavia por empezar tampoco")
		void una_membership_futura_no_habilita() {
			sinRolDePlataforma();
			memberships(Fixtures.conId(new Membership(ORG_ID, null, ACCOUNT_ID,
					RoleCode.ORG_ADMIN, false, AHORA.plus(1, ChronoUnit.DAYS)), MEMBERSHIP_ID));

			assertThat(decidir(PermissionCode.COLABORADOR_READ, CONSULTORIO_ID).denial())
					.isEqualTo(DenialKind.OUT_OF_SCOPE);
		}

		@Test
		@DisplayName("Suspendida tampoco: el estado es una tercera condicion")
		void una_membership_suspendida_no_habilita() {
			sinRolDePlataforma();
			Membership suspendida = Fixtures.membershipVigenteCon(RoleCode.ORG_ADMIN);
			suspendida.suspender();
			memberships(suspendida);

			assertThat(decidir(PermissionCode.COLABORADOR_READ, CONSULTORIO_ID).denial())
					.isEqualTo(DenialKind.OUT_OF_SCOPE);
		}

		@Test
		@DisplayName("Revocada tampoco, y es el caso que la ventana cero tiene que cubrir")
		void una_membership_revocada_no_habilita() {
			sinRolDePlataforma();
			Membership revocada = Fixtures.membershipVigenteCon(RoleCode.ORG_ADMIN);
			revocada.revocar(OTRA_CUENTA, "baja", AHORA.minusSeconds(1));
			memberships(revocada);

			assertThat(decidir(PermissionCode.COLABORADOR_READ, CONSULTORIO_ID).denial())
					.isEqualTo(DenialKind.OUT_OF_SCOPE);
		}
	}

	// =================================================================================
	// Contexto y objetivo
	// =================================================================================

	@Test
	@DisplayName("Sin organizacion en la consulta es NO_CONTEXT, que la capa web traduce a 403")
	void sin_contexto_es_no_context() {
		sinRolDePlataforma();

		PermissionDecision decision = evaluator.evaluate(new PermissionQuery(
				ACCOUNT_ID, PermissionCode.COLABORADOR_READ.code(), null, null, null, AHORA));

		// Y nunca 401: el interceptor del frontend borra el token ante cualquier 401 y el
		// usuario entra en un bucle de login del que no sale.
		assertThat(decision.denial()).isEqualTo(DenialKind.NO_CONTEXT);
	}

	@Test
	@DisplayName("Una cuenta objetivo de otra organizacion es 404, no 403")
	void un_objetivo_ajeno_es_fuera_de_alcance() {
		// La matriz decide QUE puede hacer el actor; la membership del objetivo sigue decidiendo
		// SOBRE QUIEN (ADR-0019). Y una cuenta ajena se responde como inexistente.
		sinRolDePlataforma();
		memberships(Fixtures.membershipVigenteCon(RoleCode.ORG_ADMIN));
		objetivoEsMiembro(OTRA_CUENTA, false);

		PermissionDecision decision = evaluator.evaluate(new PermissionQuery(
				ACCOUNT_ID, PermissionCode.COLABORADOR_MANAGE.code(), ORG_ID, null,
				OTRA_CUENTA, AHORA));

		assertThat(decision.denial()).isEqualTo(DenialKind.OUT_OF_SCOPE);
	}

	@Test
	@DisplayName("Una cuenta objetivo de la misma organizacion pasa")
	void un_objetivo_propio_pasa() {
		sinRolDePlataforma();
		memberships(Fixtures.membershipVigenteCon(RoleCode.ORG_ADMIN));
		objetivoEsMiembro(OTRA_CUENTA, true);

		assertThat(evaluator.evaluate(new PermissionQuery(
				ACCOUNT_ID, PermissionCode.COLABORADOR_MANAGE.code(), ORG_ID, null,
				OTRA_CUENTA, AHORA)).granted()).isTrue();
	}

	@Test
	@DisplayName("Operar sobre uno mismo no exige consultar la membership del objetivo")
	void sobre_uno_mismo_no_se_consulta_de_nuevo() {
		sinRolDePlataforma();
		memberships(Fixtures.membershipVigenteCon(RoleCode.ORG_ADMIN));

		assertThat(evaluator.evaluate(new PermissionQuery(
				ACCOUNT_ID, PermissionCode.COLABORADOR_MANAGE.code(), ORG_ID, null,
				ACCOUNT_ID, AHORA)).granted()).isTrue();
	}

	// =================================================================================
	// Grants
	// =================================================================================

	@Nested
	@DisplayName("Permisos adicionales")
	class Grants {

		@Test
		@DisplayName("Un grant vigente habilita lo que el rol no da por defecto")
		void un_grant_vigente_habilita() {
			sinRolDePlataforma();
			memberships(Fixtures.membershipVigenteCon(RoleCode.ORG_ADMIN));
			given(grantRepository.findAllByMembershipIdAndActiveTrue(MEMBERSHIP_ID))
					.willReturn(List.of(new MembershipGrant(ORG_ID, MEMBERSHIP_ID,
							PermissionCode.AUDITORIA_READ_CLINICA, OTRA_CUENTA, "auditoria",
							AHORA.minus(1, ChronoUnit.DAYS), null)));

			assertThat(decidir(PermissionCode.AUDITORIA_READ_CLINICA, null).granted()).isTrue();
		}

		@Test
		@DisplayName("Sin grant, auditoria:read-clinica deniega: la matriz la marca 'No por defecto'")
		void sin_grant_deniega() {
			sinRolDePlataforma();
			memberships(Fixtures.membershipVigenteCon(RoleCode.ORG_ADMIN));
			given(grantRepository.findAllByMembershipIdAndActiveTrue(MEMBERSHIP_ID))
					.willReturn(List.of());

			assertThat(decidir(PermissionCode.AUDITORIA_READ_CLINICA, null).granted()).isFalse();
		}

		@Test
		@DisplayName("Un grant vencido no habilita")
		void un_grant_vencido_no_habilita() {
			sinRolDePlataforma();
			memberships(Fixtures.membershipVigenteCon(RoleCode.ORG_ADMIN));
			given(grantRepository.findAllByMembershipIdAndActiveTrue(MEMBERSHIP_ID))
					.willReturn(List.of(new MembershipGrant(ORG_ID, MEMBERSHIP_ID,
							PermissionCode.AUDITORIA_READ_CLINICA, OTRA_CUENTA, "auditoria",
							AHORA.minus(2, ChronoUnit.DAYS), AHORA.minus(1, ChronoUnit.DAYS))));

			assertThat(decidir(PermissionCode.AUDITORIA_READ_CLINICA, null).granted()).isFalse();
		}

		@Test
		@DisplayName("Un grant sobre una membership de sede no habilita mas alla de esa sede")
		void el_grant_hereda_el_alcance_de_la_membership() {
			sinRolDePlataforma();
			memberships(Fixtures.membershipDeSede(CONSULTORIO_ID, RoleCode.CONSULTORIO_ADMIN));
			given(grantRepository.findAllByMembershipIdAndActiveTrue(MEMBERSHIP_ID))
					.willReturn(List.of(new MembershipGrant(ORG_ID, MEMBERSHIP_ID,
							PermissionCode.AUDITORIA_READ_CLINICA, OTRA_CUENTA, "auditoria",
							AHORA.minus(1, ChronoUnit.DAYS), null)));

			assertThat(decidir(PermissionCode.AUDITORIA_READ_CLINICA, CONSULTORIO_ID).granted())
					.isTrue();
			// Sobre la organizacion entera, no: el grant esta atado a una membership de sede.
			assertThat(decidir(PermissionCode.AUDITORIA_READ_CLINICA, null).granted()).isFalse();
		}

		@Test
		@DisplayName("G-5: un grant que el rol actual no admite no concede nada")
		void el_grant_que_el_rol_no_admite_no_concede() {
			// hc:read sobre un ADMINISTRATIVO: su celda de Ver HC es "Limitado" (solo metadatos),
			// no otorgable. La fila puede existir —otorgada antes de G-5, o a un CONSULTORIO_ADMIN
			// que despues paso a ADMINISTRATIVO— y no tiene que abrir el contenido clinico.
			sinRolDePlataforma();
			memberships(Fixtures.membershipDeSede(CONSULTORIO_ID, RoleCode.ADMINISTRATIVO));
			given(grantRepository.findAllByMembershipIdAndActiveTrue(MEMBERSHIP_ID))
					.willReturn(List.of(new MembershipGrant(ORG_ID, MEMBERSHIP_ID,
							PermissionCode.HC_READ, OTRA_CUENTA, "previo a G-5",
							AHORA.minus(1, ChronoUnit.DAYS), null)));

			assertThat(decidir(PermissionCode.HC_READ, CONSULTORIO_ID).granted()).isFalse();
		}
	}

	// =================================================================================
	// PLATFORM_ADMIN y acceso de soporte
	// =================================================================================

	@Nested
	@DisplayName("Administrador de plataforma")
	class Plataforma {

		@Test
		@DisplayName("Administra el contrato del tenant sin necesitar membership")
		void administra_sin_membership() {
			conRolDePlataforma();

			assertThat(decidir(PermissionCode.TENANT_MANAGE, null).granted()).isTrue();
			assertThat(decidir(PermissionCode.COLABORADOR_MANAGE, null).grantedByScope())
					.isEqualTo(PermissionScope.GLOBAL.name());
			verify(membershipRepository, never())
					.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(anyLong(), anyLong());
		}

		@Test
		@DisplayName("Sin acceso de soporte no toca los datos de personas del tenant")
		void sin_soporte_no_toca_datos_del_tenant() {
			// La matriz §6 le da "Soporte" a gestionar paciente: no es un permiso implicito.
			// paciente:manage es de F3 y por lo tanto ni siquiera tiene fila; el que si esta
			// declarado como restringido es la auditoria clinica.
			conRolDePlataforma();
			given(supportAccessRepository.findAllByOrganizationIdAndAccountIdAndActiveTrue(
					ORG_ID, ACCOUNT_ID)).willReturn(List.of());

			assertThat(decidir(PermissionCode.AUDITORIA_READ_CLINICA, null).granted()).isFalse();
		}

		@Test
		@DisplayName("La auditoria clinica deniega SIEMPRE en F1, incluso con soporte vigente")
		void la_auditoria_clinica_deniega_en_f1() {
			// "Restringido" = soporte MAS un permiso clinico explicito, y ese permiso no existe
			// hasta F4. Que denuegue esta probado a proposito: documenta que falta por diseño.
			conRolDePlataforma();
			conSoporteVigente();

			PermissionDecision decision = decidir(PermissionCode.AUDITORIA_READ_CLINICA, null);

			assertThat(decision.granted()).isFalse();
			assertThat(decision.denial()).isEqualTo(DenialKind.NO_PERMISSION);
		}

		@Test
		@DisplayName("Con soporte vigente, la decision lo informa para que se audite el USO")
		void el_uso_del_soporte_se_informa() {
			// La matriz §7 exige auditar CADA operacion amparada por soporte, no solo el
			// otorgamiento. El evaluador no puede escribir ese evento —no sabe cual fue la
			// operacion— asi que lo informa y el llamador lo registra.
			conRolDePlataforma();
			conSoporteVigente();

			PermissionDecision decision = decidir(PermissionCode.COLABORADOR_MANAGE, null);

			assertThat(decision.granted()).isTrue();
			assertThat(decision.viaSupportAccess()).isTrue();
		}

		@Test
		@DisplayName("Sin soporte vigente la decision no marca uso de soporte")
		void sin_soporte_no_se_marca_uso() {
			conRolDePlataforma();
			given(supportAccessRepository.findAllByOrganizationIdAndAccountIdAndActiveTrue(
					ORG_ID, ACCOUNT_ID)).willReturn(List.of());

			assertThat(decidir(PermissionCode.COLABORADOR_MANAGE, null).viaSupportAccess()).isFalse();
		}

		@Test
		@DisplayName("Un rol de plataforma revocado no habilita nada: la ventana es cero")
		void un_rol_revocado_no_habilita() {
			PlatformRole revocado = new PlatformRole(
					ACCOUNT_ID, null, "alta", AHORA.minus(2, ChronoUnit.DAYS));
			revocado.revoke(OTRA_CUENTA, "baja", AHORA.minusSeconds(1));
			given(platformRoleRepository.findAllByAccountIdAndActiveTrue(ACCOUNT_ID))
					.willReturn(List.of(revocado));
			memberships();

			assertThat(evaluator.isPlatformAdmin(ACCOUNT_ID, AHORA)).isFalse();
			assertThat(decidir(PermissionCode.TENANT_MANAGE, null).granted()).isFalse();
		}

		@Test
		@DisplayName("hasSupportAccess responde por la vigencia, no por la existencia de la fila")
		void has_support_access_mira_la_vigencia() {
			SupportAccess vencido = new SupportAccess(ORG_ID, ACCOUNT_ID, "viejo", ACCOUNT_ID,
					AHORA.minus(2, ChronoUnit.DAYS), AHORA.minus(1, ChronoUnit.DAYS));
			given(supportAccessRepository.findAllByOrganizationIdAndAccountIdAndActiveTrue(
					ORG_ID, ACCOUNT_ID)).willReturn(List.of(vencido));

			assertThat(evaluator.hasSupportAccess(ACCOUNT_ID, ORG_ID, AHORA)).isFalse();

			conSoporteVigente();
			assertThat(evaluator.hasSupportAccess(ACCOUNT_ID, ORG_ID, AHORA)).isTrue();
		}
	}

	// =================================================================================
	// Permisos efectivos (insumo de UX)
	// =================================================================================

	@Nested
	@DisplayName("Permisos efectivos")
	class Efectivos {

		@Test
		@DisplayName("Un ORG_ADMIN ve sus permisos base de la matriz")
		void los_de_un_org_admin() {
			sinRolDePlataforma();
			memberships(Fixtures.membershipVigenteCon(RoleCode.ORG_ADMIN));
			given(grantRepository.findAllByMembershipIdAndActiveTrue(MEMBERSHIP_ID))
					.willReturn(List.of());

			// `paciente:manage` se sumo en AKINE-03.01, cuando nacio el modulo que lo evalua: la
			// matriz §4 se lo da al ORG_ADMIN con alcance organizacion y hasta esa etapa el codigo
			// existia sin que lo tuviera nadie. `turno:read` se sumo en AKINE-05.01 por el mismo
			// motivo: la matriz literal de §32 no tiene fila de turnos, y la enmienda §13 le da
			// alcance ORGANIZACION al ORG_ADMIN.
			assertThat(evaluator.effectivePermissions(ACCOUNT_ID, ORG_ID, CONSULTORIO_ID))
					.containsExactlyInAnyOrder(
							"tenant:read", "consultorio:manage", "espacio:read",
							"colaborador:manage", "colaborador:read", "auditoria:read",
							"paciente:manage", "turno:read", "turno:manage", "cobro:register",
							// `paciente:read` lo sumo AKINE-DU-6 (DP-22): todo el personal lee el padron.
							"paciente:read",
							// `convenio:manage` se sumo en AKINE-03.03, cuando nacio `contracting`,
							// el modulo que lo evalua. La matriz §2 se lo da al ORG_ADMIN en la fila
							// Administrar Convenios y la enmienda esta en la matriz §13.
							"convenio:manage",
							// `caja:operate` se sumo en AKINE-07.03, la etapa que crea la caja. La
							// matriz §2 se lo da al ORG_ADMIN en la fila Operar Caja, con alcance
							// ORGANIZACION. Es un permiso DISTINTO de `cobro:register` y no un
							// sinonimo: cobrar es un acto comercial, arquear y cerrar una caja es
							// responsabilidad sobre dinero fisico. Por eso aparecen los dos.
							"caja:operate",
							// `clase:read` y `clase:manage` se suman en AKINE-08.01, la etapa
							// que crea la clase. Mismo reparto que los turnos: M28 §2 nombra al
							// administrador entre los actores de una clase.
							"clase:read", "clase:manage",
							// `inscripcion:read` e `inscripcion:manage` se suman en AKINE-08.02.
							// Mismo alcance que la clase: quien puede programarla puede anotar
							// gente en ella.
							"inscripcion:read", "inscripcion:manage",
							// `asistencia:manage` se suma en AKINE-08.03, con codigo propio:
							// anotar a alguien y decir que vino son decisiones distintas, y un
							// instructor podria marcarla sin poder inscribir ni dar de baja.
							"asistencia:manage",
							// AKINE-G-1 (DP-15): "Si" en Ver Reportes.
							"reporte:read");
		}

		@Test
		@DisplayName("Los grants vigentes se suman a la lista")
		void los_grants_se_suman() {
			sinRolDePlataforma();
			memberships(Fixtures.membershipVigenteCon(RoleCode.ORG_ADMIN));
			given(grantRepository.findAllByMembershipIdAndActiveTrue(MEMBERSHIP_ID))
					.willReturn(List.of(new MembershipGrant(ORG_ID, MEMBERSHIP_ID,
							PermissionCode.AUDITORIA_READ_CLINICA, OTRA_CUENTA, "auditoria",
							AHORA.minus(1, ChronoUnit.DAYS), null)));

			assertThat(evaluator.effectivePermissions(ACCOUNT_ID, ORG_ID, CONSULTORIO_ID))
					.contains("auditoria:read-clinica");
		}

		@Test
		@DisplayName("Sin membership vigente la lista es vacia, no un error")
		void sin_membership_la_lista_es_vacia() {
			// El selector del frontend lo interpreta como "elegi otro contexto".
			sinRolDePlataforma();
			memberships();

			assertThat(evaluator.effectivePermissions(ACCOUNT_ID, ORG_ID, CONSULTORIO_ID)).isEmpty();
		}

		@Test
		@DisplayName("Un administrador de plataforma ve los globales y nunca la auditoria clinica")
		void los_de_un_admin_de_plataforma() {
			conRolDePlataforma();
			given(supportAccessRepository.findAllByOrganizationIdAndAccountIdAndActiveTrue(
					ORG_ID, ACCOUNT_ID)).willReturn(List.of());

			assertThat(evaluator.effectivePermissions(ACCOUNT_ID, ORG_ID, CONSULTORIO_ID))
					.contains("tenant:manage", "colaborador:manage")
					// RESTRINGIDO nunca entra: exige un permiso clinico que no existe en F1, y
					// mostrarlo en la lista le prometeria al frontend algo que el backend niega.
					.doesNotContain("auditoria:read-clinica")
					// Y SOPORTE tampoco entra sin un support_access vigente. tenant:read y
					// auditoria:read son de alcance SOPORTE desde que se cerro el agujero de
					// AuthorizationGuard: sin soporte, el administrador de plataforma no lee
					// los datos ni el rastro del tenant, y la lista tiene que decir eso.
					.doesNotContain("tenant:read", "auditoria:read");
		}

		@Test
		@DisplayName("Con soporte vigente, la lista del admin de plataforma suma las de SOPORTE")
		void los_de_un_admin_de_plataforma_con_soporte() {
			conRolDePlataforma();
			conSoporteVigente();

			assertThat(evaluator.effectivePermissions(ACCOUNT_ID, ORG_ID, CONSULTORIO_ID))
					.contains("tenant:read", "auditoria:read")
					.doesNotContain("auditoria:read-clinica");
		}

		@Test
		@DisplayName("Un PROFESIONAL ve las lecturas de su sede y su historia clinica, nada mas")
		void los_de_un_profesional() {
			// `hc:read` y `hc:write` entraron en AKINE-04.01: la matriz seccion 2 le dice "Si" en
			// las dos filas de Historia Clinica y es el unico rol al que se las dice.
			sinRolDePlataforma();
			memberships(Fixtures.membershipDeSede(CONSULTORIO_ID, RoleCode.PROFESIONAL));
			given(grantRepository.findAllByMembershipIdAndActiveTrue(MEMBERSHIP_ID))
					.willReturn(List.of());

			assertThat(evaluator.effectivePermissions(ACCOUNT_ID, ORG_ID, CONSULTORIO_ID))
					.containsExactlyInAnyOrder(
							"colaborador:read", "espacio:read", "turno:read", "turno:manage", "sesion:register",
							"hc:read", "hc:write",
							// AKINE-DU-6 (DP-22): por base, no por grant.
							"paciente:read",
							// `clase:read` y `clase:manage` entran en AKINE-08.01: M28 §2 lo nombra
							// como "Profesional / Instructor", y es quien dicta la clase.
							"clase:read", "clase:manage",
							// AKINE-08.02: el instructor necesita saber a quien tiene enfrente y
							// dar de baja a quien avisa que no viene.
							"inscripcion:read", "inscripcion:manage",
							// AKINE-08.03: es quien toma lista.
							"asistencia:manage",
							// AKINE-G-1 (DP-15): aparece aunque sea ACTIVIDAD_PROPIA; el recorte
							// lo hace el reporte, no la lista que oculta botones.
							"reporte:read");
		}
	}

	// =================================================================================
	// El guard: traduccion del rechazo
	// =================================================================================

	@Nested
	@DisplayName("requirePermission")
	class Guard {

		@Test
		@DisplayName("Fuera de alcance lanza la excepcion que la capa web traduce a 404")
		void fuera_de_alcance_es_404() {
			sinRolDePlataforma();
			memberships();

			assertThatThrownBy(() -> evaluator.requirePermission(new PermissionQuery(
					ACCOUNT_ID, PermissionCode.COLABORADOR_READ.code(), ORG_ID, null, null, AHORA)))
					.isInstanceOf(OrganizationNotFoundException.class);

			// Y NO se audita: registrar los rechazos por alcance construiria dentro de
			// audit_event el mismo padron de existencia de tenants ajenos que el 404 evita.
			verify(denialAuditor, never()).recordDenial(any(), any());
		}

		@Test
		@DisplayName("Sin contexto lanza AccessDenied, que la capa web traduce a 403")
		void sin_contexto_es_403() {
			sinRolDePlataforma();

			assertThatThrownBy(() -> evaluator.requirePermission(new PermissionQuery(
					ACCOUNT_ID, PermissionCode.COLABORADOR_READ.code(), null, null, null, AHORA)))
					.isInstanceOf(AccessDeniedException.class);

			verify(denialAuditor, never()).recordDenial(any(), any());
		}

		@Test
		@DisplayName("Sin permiso sobre un recurso propio lanza 403 Y deja rastro en la auditoria")
		void sin_permiso_es_403_auditado() {
			sinRolDePlataforma();
			memberships(Fixtures.membershipDeSede(CONSULTORIO_ID, RoleCode.PROFESIONAL));
			given(grantRepository.findAllByMembershipIdAndActiveTrue(MEMBERSHIP_ID))
					.willReturn(List.of());

			assertThatThrownBy(() -> evaluator.requirePermission(new PermissionQuery(
					ACCOUNT_ID, PermissionCode.COLABORADOR_MANAGE.code(), ORG_ID,
					CONSULTORIO_ID, null, AHORA)))
					.isInstanceOf(PermissionDeniedException.class);

			// AGENT.md §10: auditoria de toda excepcion de permiso.
			verify(denialAuditor).recordDenial(any(), any());
		}

		@Test
		@DisplayName("Concedido devuelve la decision, con su alcance")
		void concedido_devuelve_la_decision() {
			sinRolDePlataforma();
			memberships(Fixtures.membershipVigenteCon(RoleCode.ORG_ADMIN));

			PermissionDecision decision = evaluator.requirePermission(new PermissionQuery(
					ACCOUNT_ID, PermissionCode.COLABORADOR_MANAGE.code(), ORG_ID, null, null, AHORA));

			assertThat(decision.granted()).isTrue();
			assertThat(decision.grantedByScope()).isEqualTo(PermissionScope.ORGANIZACION.name());
		}
	}

	// =================================================================================
	// Contrato del spi
	// =================================================================================

	@Test
	@DisplayName("Un codigo de permiso desconocido deniega en vez de explotar")
	void un_codigo_desconocido_deniega() {
		// Fail-closed. Un evaluador que explota ante un codigo mal escrito convierte un error de
		// tipeo en un 500; uno que ante la duda concede es un agujero.
		PermissionDecision decision = evaluator.evaluate(new PermissionQuery(
				ACCOUNT_ID, "no:existe", ORG_ID, null, null, AHORA));

		assertThat(decision.granted()).isFalse();
		assertThat(decision.denial()).isEqualTo(DenialKind.NO_PERMISSION);
	}

	@ParameterizedTest
	@CsvSource({"'', tenant:read", "colaborador:read, ''"})
	@DisplayName("Una consulta mal formada se rechaza al construirla, no al evaluarla")
	void la_consulta_se_valida_al_construirla(String vacio, String otro) {
		assertThatThrownBy(() -> new PermissionQuery(ACCOUNT_ID, "  ", ORG_ID, null, null, AHORA))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PermissionQuery(
				ACCOUNT_ID, PermissionCode.TENANT_READ.code(), ORG_ID, null, null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("instante explicito");
	}

	@Test
	@DisplayName("El atajo of() arma una consulta sin sede ni objetivo")
	void el_atajo_of() {
		PermissionQuery consulta = PermissionQuery.of(
				ACCOUNT_ID, PermissionCode.TENANT_READ.code(), ORG_ID, AHORA);

		assertThat(consulta.consultorioId()).isNull();
		assertThat(consulta.targetAccountId()).isNull();
		assertThat(consulta.organizationId()).isEqualTo(ORG_ID);
	}

	@Test
	@DisplayName("Una decision no puede decir que concede y traer un motivo de rechazo")
	void la_decision_es_coherente() {
		assertThatThrownBy(() -> new PermissionDecision(true, DenialKind.NO_PERMISSION, null, false))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PermissionDecision(false, DenialKind.NONE, null, false))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PermissionDecision(true, null, null, false))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
