package com.akine.identity.application;

import com.akine.identity.IdentityFixtures;
import com.akine.identity.domain.ColaboradorInvitacion;
import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.EstadoCuenta;
import com.akine.identity.domain.EstadoInvitacion;
import com.akine.identity.domain.TokenDigest;
import com.akine.identity.domain.exception.ColaboradorYaVinculadoException;
import com.akine.identity.domain.exception.InvitacionNotAccessibleException;
import com.akine.identity.domain.exception.InvitacionPendienteDuplicadaException;
import com.akine.identity.domain.exception.InvitacionVencidaException;
import com.akine.identity.domain.port.ColaboradorInvitacionRepositoryPort;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.identity.domain.port.IdentityClock;
import com.akine.identity.domain.port.NotificationOutboxPort;
import com.akine.identity.domain.port.PasswordHasher;
import com.akine.identity.domain.port.TokenGenerator;
import com.akine.identity.domain.port.VerificationLinkBuilder;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.InvitationMembershipCommand;
import com.akine.organization.spi.MembershipProvisioning;
import com.akine.organization.spi.OrganizationDirectory;
import com.akine.organization.spi.OrganizationSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * La invitacion a colaborar, del lado en que se decide (M05, AKINE-02.03).
 *
 * <p>Se prueban aca las decisiones que <b>no se ven en el resultado</b> y que por lo tanto
 * ningun test de la capa HTTP puede detectar:
 *
 * <ol>
 *   <li><b>El permiso se exige ANTES de tocar el email</b>, igual que en el alta directa. Con el
 *       orden invertido el resultado seria identico y el endpoint pasaria a ser un oraculo de
 *       existencia de direcciones para cualquier cuenta con contexto.</li>
 *   <li><b>Consultar no consume la invitacion.</b> Si alguien la marca resuelta al leerla, el
 *       invitado pierde el enlace apenas recargue la pantalla — y ningun assert sobre el cuerpo
 *       de la respuesta lo notaria.</li>
 *   <li><b>La cuenta del invitado nace ACTIVA</b> y sin correo de activacion. Es lo que
 *       convierte la aceptacion en un solo paso, y es una decision de seguridad explicita: el
 *       token ya probo la direccion.</li>
 *   <li><b>El vinculo se crea por {@code createFromInvitation} y no por {@code createDirect}.</b>
 *       El segundo exige {@code colaborador:manage} al actor, y el actor aca es el invitado, que
 *       no pertenece al tenant: si alguien "unifica" los dos caminos, ninguna invitacion se
 *       puede aceptar nunca.</li>
 * </ol>
 */
class ColaboradorInvitacionServiceTest {

	private static final long ORG_ID = 10L;
	private static final long CONSULTORIO_ID = 3L;
	private static final long ADMIN_ID = 99L;
	private static final long CUENTA_ID = 100L;
	private static final long MEMBERSHIP_ID = 77L;
	private static final long INVITACION_ID = 55L;

	private static final String EMAIL_TIPEADO = "Kine@Centro.Test";
	private static final String EMAIL_NORMALIZADO = "kine@centro.test";
	private static final String ROL = "PROFESIONAL";
	private static final String TOKEN_PLANO = "token-de-prueba-largo";
	private static final String PASSWORD = "kinesiologia-2026";

	private static final Instant AHORA = Instant.parse("2026-08-25T12:00:00Z");

	private ColaboradorInvitacionRepositoryPort invitacionRepository;
	private CuentaRepositoryPort cuentaRepository;
	private MembershipProvisioning membershipProvisioning;
	private AccountContextDirectory contextDirectory;
	private OrganizationDirectory organizationDirectory;
	private ConsultorioDirectory consultorioDirectory;
	private PermissionGuard permissionGuard;
	private NotificationOutboxPort notificationOutbox;
	private AuditTrail auditTrail;
	private ColaboradorInvitacionService servicio;

	private final DirectMembershipService.Actor actor =
			new DirectMembershipService.Actor(ADMIN_ID, ORG_ID, false);

	@BeforeEach
	void setUp() {
		invitacionRepository = mock(ColaboradorInvitacionRepositoryPort.class);
		cuentaRepository = mock(CuentaRepositoryPort.class);
		membershipProvisioning = mock(MembershipProvisioning.class);
		contextDirectory = mock(AccountContextDirectory.class);
		organizationDirectory = mock(OrganizationDirectory.class);
		consultorioDirectory = mock(ConsultorioDirectory.class);
		permissionGuard = mock(PermissionGuard.class);
		notificationOutbox = mock(NotificationOutboxPort.class);
		auditTrail = mock(AuditTrail.class);

		TokenGenerator tokenGenerator = mock(TokenGenerator.class);
		given(tokenGenerator.nuevoToken()).willReturn(TOKEN_PLANO);
		VerificationLinkBuilder linkBuilder = (tipo, token) -> "https://akine.test/invitacion?token=" + token;
		PasswordHasher passwordHasher = mock(PasswordHasher.class);
		given(passwordHasher.hash(anyString())).willReturn("$argon2id$fake");

		IdentityClock clock = () -> AHORA;

		given(organizationDirectory.find(ORG_ID))
				.willReturn(Optional.of(new OrganizationSnapshot(ORG_ID, "Centro Belgrano", true)));
		given(invitacionRepository.saveAndFlush(any()))
				.willAnswer(llamada -> conId(llamada.getArgument(0)));
		given(invitacionRepository.save(any())).willAnswer(llamada -> llamada.getArgument(0));
		given(cuentaRepository.save(any()))
				.willAnswer(llamada -> IdentityFixtures.conId(llamada.getArgument(0), CUENTA_ID));

		servicio = new ColaboradorInvitacionService(
				invitacionRepository, cuentaRepository, membershipProvisioning, contextDirectory,
				organizationDirectory, consultorioDirectory, permissionGuard, tokenGenerator,
				linkBuilder, notificationOutbox, passwordHasher, new PasswordPolicy(), auditTrail,
				clock, new TerminosService(
						com.akine.identity.domain.TerminosSettings.sinTerminos(), aceptacion -> aceptacion),
				mock(com.akine.offering.spi.OfertaDirectory.class));
	}

	@Test
	@DisplayName("el alta manual guarda los datos y crea un vinculo por cada sede al aceptar")
	void alta_manual_multisede() {
		long otraSede = 4L;
		given(consultorioDirectory.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(CONSULTORIO_ID, ORG_ID, "Centro", "America/Argentina/Buenos_Aires", true)));
		given(consultorioDirectory.find(ORG_ID, otraSede)).willReturn(Optional.of(
				new ConsultorioSnapshot(otraSede, ORG_ID, "Norte", "America/Argentina/Buenos_Aires", true)));
		DatosAltaManual datos = new DatosAltaManual("Juan", "Perez", "DNI", "12345678", null,
				"Kinesiologia", "MP 4521", "Cordoba", "Traumatologia", List.of("Deportiva"),
				List.of(CONSULTORIO_ID, otraSede), List.of(), null, true, false, false);
		servicio.invitarManual(actor, new InvitacionAltaCommand(EMAIL_TIPEADO, ROL, CONSULTORIO_ID), datos);

		ArgumentCaptor<ColaboradorInvitacion> guardada = ArgumentCaptor.forClass(ColaboradorInvitacion.class);
		verify(invitacionRepository).saveAndFlush(guardada.capture());
		assertThat(guardada.getValue().getDatosAltaManual()).contains("Kinesiologia", "\"sedeIds\":[3,4]").doesNotContain(PASSWORD);
		given(invitacionRepository.findByTokenHash(TokenDigest.of(TOKEN_PLANO)))
				.willReturn(Optional.of(guardada.getValue()));
		given(cuentaRepository.findByEmailNormalizado(EMAIL_NORMALIZADO)).willReturn(Optional.empty());
		given(membershipProvisioning.createFromInvitation(eq(ORG_ID), any()))
				.willReturn(MEMBERSHIP_ID, MEMBERSHIP_ID + 1);

		assertThat(servicio.consultar(TOKEN_PLANO).perfil().profesion()).isEqualTo("Kinesiologia");
		PerfilDeInvitacion corregido = new PerfilDeInvitacion("Juan Carlos", "Perez", "DNI",
				"12345678", "3511234567", "Kinesiologia", "MP 4521", "Cordoba",
				"Deportologia", List.of("Deportiva"));
		servicio.aceptar(new InvitacionAceptacionCommand(
				TOKEN_PLANO, "Otro", "Perez", PASSWORD, null, null, corregido));
		assertThat(guardada.getValue().getDatosAltaManual()).contains("Deportologia", "3511234567");
		ArgumentCaptor<Cuenta> cuenta = ArgumentCaptor.forClass(Cuenta.class);
		verify(cuentaRepository).save(cuenta.capture());
		assertThat(cuenta.getValue().getNombre()).isEqualTo("Juan Carlos");
		ArgumentCaptor<InvitationMembershipCommand> vinculos = ArgumentCaptor.forClass(InvitationMembershipCommand.class);
		verify(membershipProvisioning, org.mockito.Mockito.times(2))
				.createFromInvitation(eq(ORG_ID), vinculos.capture());
		assertThat(vinculos.getAllValues()).extracting(InvitationMembershipCommand::consultorioId)
				.containsExactly(CONSULTORIO_ID, otraSede);
		assertThat(vinculos.getAllValues()).allMatch(vinculo -> !vinculo.activo());
	}

	@Test
	@DisplayName("un administrador de organizacion recibe un unico vinculo de alcance total")
	void alta_manual_de_admin_de_organizacion() {
		DatosAltaManual datos = new DatosAltaManual("Ana", "Perez", "DNI", "12345678", null,
				null, null, null, null, List.of(), List.of(), List.of(), null, false, false, true);
		servicio.invitarManual(actor,
				new InvitacionAltaCommand(EMAIL_TIPEADO, "ORG_ADMIN", null), datos);
		ArgumentCaptor<ColaboradorInvitacion> guardada = ArgumentCaptor.forClass(ColaboradorInvitacion.class);
		verify(invitacionRepository).saveAndFlush(guardada.capture());
		given(invitacionRepository.findByTokenHash(TokenDigest.of(TOKEN_PLANO)))
				.willReturn(Optional.of(guardada.getValue()));
		given(cuentaRepository.findByEmailNormalizado(EMAIL_NORMALIZADO)).willReturn(Optional.empty());
		given(membershipProvisioning.createFromInvitation(eq(ORG_ID), any())).willReturn(MEMBERSHIP_ID);
		servicio.aceptar(new InvitacionAceptacionCommand(TOKEN_PLANO, "Ana", "Perez", PASSWORD));
		ArgumentCaptor<InvitationMembershipCommand> vinculo = ArgumentCaptor.forClass(InvitationMembershipCommand.class);
		verify(membershipProvisioning).createFromInvitation(eq(ORG_ID), vinculo.capture());
		assertThat(vinculo.getValue().consultorioId()).isNull();
		assertThat(vinculo.getValue().roleCode()).isEqualTo("ORG_ADMIN");
	}

	@Test
	@DisplayName("el permiso se exige antes de mirar el email")
	void permiso_antes_que_email() {
		given(cuentaRepository.findByEmailNormalizado(EMAIL_NORMALIZADO))
				.willReturn(Optional.empty());

		servicio.invitar(actor, new InvitacionAltaCommand(EMAIL_TIPEADO, ROL, CONSULTORIO_ID));

		// El orden, y no el resultado: invertido, el resultado seria el mismo y cualquier cuenta
		// con contexto podria preguntar por direcciones ajenas antes de que el permiso la frene.
		InOrder orden = inOrder(permissionGuard, cuentaRepository);
		orden.verify(permissionGuard).requirePermission(any(PermissionQuery.class));
		orden.verify(cuentaRepository).findByEmailNormalizado(EMAIL_NORMALIZADO);

		// Y la sede que se evalua es la del VINCULO propuesto: quien administra la sede A no
		// puede invitar a la sede B.
		ArgumentCaptor<PermissionQuery> consulta = ArgumentCaptor.forClass(PermissionQuery.class);
		verify(permissionGuard).requirePermission(consulta.capture());
		assertThat(consulta.getValue().consultorioId()).isEqualTo(CONSULTORIO_ID);
		assertThat(consulta.getValue().permissionCode()).isEqualTo("colaborador:manage");
	}

	@Test
	@DisplayName("el email se normaliza y el correo sale con el enlace, nunca con el token suelto")
	void el_correo_lleva_el_enlace() {
		given(cuentaRepository.findByEmailNormalizado(EMAIL_NORMALIZADO))
				.willReturn(Optional.empty());

		InvitacionView vista = servicio.invitar(
				actor, new InvitacionAltaCommand(EMAIL_TIPEADO, ROL, CONSULTORIO_ID));

		assertThat(vista.email()).isEqualTo(EMAIL_NORMALIZADO);
		assertThat(vista.estado()).isEqualTo(EstadoInvitacion.PENDIENTE);
		assertThat(vista.expiraEn()).isEqualTo(AHORA.plus(Duration.ofDays(14)));

		ArgumentCaptor<NotificationOutboxPort.Notificacion> correo =
				ArgumentCaptor.forClass(NotificationOutboxPort.Notificacion.class);
		verify(notificationOutbox).encolar(correo.capture());

		// El token viaja SOLO dentro del enlace, en el campo de transporte. El payload
		// consultable no lo tiene ni truncado (T-11).
		assertThat(correo.getValue().enlaceSeguro()).contains(TOKEN_PLANO);
		assertThat(correo.getValue().datosPlantilla().values())
				.noneMatch(valor -> valor.contains(TOKEN_PLANO));
		assertThat(correo.getValue().datosPlantilla()).containsEntry(
				"organizacionNombre", "Centro Belgrano");
	}

	@Test
	@DisplayName("invitar a quien ya trabaja ahi es 409, no un correo")
	void ya_vinculado_no_recibe_correo() {
		Cuenta existente = IdentityFixtures.conId(IdentityFixtures.cuentaActiva(), CUENTA_ID);
		given(cuentaRepository.findByEmailNormalizado(EMAIL_NORMALIZADO))
				.willReturn(Optional.of(existente));
		given(contextDirectory.hasActiveMembership(CUENTA_ID, ORG_ID)).willReturn(true);

		assertThatThrownBy(() -> servicio.invitar(
				actor, new InvitacionAltaCommand(EMAIL_TIPEADO, ROL, CONSULTORIO_ID)))
				.isInstanceOf(ColaboradorYaVinculadoException.class);

		verify(notificationOutbox, never()).encolar(any());
		verify(invitacionRepository, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("dos invitaciones pendientes a la misma persona: 409, el camino es reenviar")
	void pendiente_duplicada() {
		given(cuentaRepository.findByEmailNormalizado(EMAIL_NORMALIZADO))
				.willReturn(Optional.empty());
		given(invitacionRepository
				.findByOrganizationIdAndConsultorioIdAndEmailNormalizadoAndEstado(
						ORG_ID, CONSULTORIO_ID, EMAIL_NORMALIZADO, EstadoInvitacion.PENDIENTE))
				.willReturn(Optional.of(pendiente()));

		assertThatThrownBy(() -> servicio.invitar(
				actor, new InvitacionAltaCommand(EMAIL_TIPEADO, ROL, CONSULTORIO_ID)))
				.isInstanceOf(InvitacionPendienteDuplicadaException.class);

		verify(notificationOutbox, never()).encolar(any());
	}

	@Test
	@DisplayName("consultar no consume la invitacion")
	void consultar_no_consume() {
		ColaboradorInvitacion invitacion = pendiente();
		given(invitacionRepository.findByTokenHash(TokenDigest.of(TOKEN_PLANO)))
				.willReturn(Optional.of(invitacion));
		given(cuentaRepository.findByEmailNormalizado(EMAIL_NORMALIZADO))
				.willReturn(Optional.empty());

		InvitacionPreview preview = servicio.consultar(TOKEN_PLANO);

		assertThat(preview.organizacionNombre()).isEqualTo("Centro Belgrano");
		assertThat(preview.requiereRegistro()).isTrue();

		// Lo que importa: no se guardo nada y sigue pendiente. Un enlace que se gasta al mirarlo
		// deja al invitado sin poder aceptar en cuanto recargue la pantalla.
		assertThat(invitacion.getEstado()).isEqualTo(EstadoInvitacion.PENDIENTE);
		verify(invitacionRepository, never()).save(any());
	}

	@Test
	@DisplayName("un token vencido se distingue de uno inexistente")
	void vencido_no_es_lo_mismo_que_inexistente() {
		ColaboradorInvitacion vencida = new ColaboradorInvitacion(
				ORG_ID, CONSULTORIO_ID, EMAIL_NORMALIZADO, ROL,
				TokenDigest.of(TOKEN_PLANO), AHORA.minusSeconds(1), ADMIN_ID);
		given(invitacionRepository.findByTokenHash(TokenDigest.of(TOKEN_PLANO)))
				.willReturn(Optional.of(vencida));

		// 409 y no 404: quien presenta el token ya probo que es el destinatario, asi que no hay
		// nada que enumerar, y la salida —pedir un reenvio— es distinta.
		assertThatThrownBy(() -> servicio.consultar(TOKEN_PLANO))
				.isInstanceOf(InvitacionVencidaException.class);

		// Un token que no resuelve, en cambio, responde lo mismo que uno de otro tenant.
		given(invitacionRepository.findByTokenHash(anyString())).willReturn(Optional.empty());
		assertThatThrownBy(() -> servicio.consultar("otro-token"))
				.isInstanceOf(InvitacionNotAccessibleException.class);
	}

	@Test
	@DisplayName("aceptar sin cuenta la crea ACTIVA y vincula por el camino de invitacion")
	void aceptar_crea_la_cuenta_activa() {
		ColaboradorInvitacion invitacion = pendiente();
		given(invitacionRepository.findByTokenHash(TokenDigest.of(TOKEN_PLANO)))
				.willReturn(Optional.of(invitacion));
		given(cuentaRepository.findByEmailNormalizado(EMAIL_NORMALIZADO))
				.willReturn(Optional.empty());
		given(membershipProvisioning.createFromInvitation(eq(ORG_ID), any()))
				.willReturn(MEMBERSHIP_ID);

		ResultadoAceptacion resultado = servicio.aceptar(new InvitacionAceptacionCommand(
				TOKEN_PLANO, "Ana", "Gomez", PASSWORD));

		assertThat(resultado.cuentaCreada()).isTrue();
		assertThat(resultado.membershipId()).isEqualTo(MEMBERSHIP_ID);

		// La cuenta nace ACTIVA: el token ya probo la direccion, asi que un segundo correo de
		// activacion verificaria dos veces lo mismo y agregaria el paso donde la gente abandona.
		ArgumentCaptor<Cuenta> creada = ArgumentCaptor.forClass(Cuenta.class);
		verify(cuentaRepository).save(creada.capture());
		assertThat(creada.getValue().getEstado()).isEqualTo(EstadoCuenta.ACTIVA);
		assertThat(creada.getValue().getEmailNormalizado()).isEqualTo(EMAIL_NORMALIZADO);

		// Y NO se manda ningun correo de activacion.
		verify(notificationOutbox, never()).encolar(any());

		// El vinculo va por el camino sin permiso: el actor es el invitado, que no pertenece al
		// tenant. Por `createDirect` esto seria imposible de aceptar para siempre.
		ArgumentCaptor<InvitationMembershipCommand> vinculo =
				ArgumentCaptor.forClass(InvitationMembershipCommand.class);
		verify(membershipProvisioning).createFromInvitation(eq(ORG_ID), vinculo.capture());
		verify(membershipProvisioning, never()).createDirect(anyLong(), anyBooleanValue(), anyLong(), any());
		assertThat(vinculo.getValue().accountId()).isEqualTo(CUENTA_ID);
		assertThat(vinculo.getValue().roleCode()).isEqualTo(ROL);
		assertThat(vinculo.getValue().invitadaPorAccountId()).isEqualTo(ADMIN_ID);

		assertThat(invitacion.getEstado()).isEqualTo(EstadoInvitacion.ACEPTADA);
	}

	@Test
	@DisplayName("aceptar con cuenta existente no le reescribe nombre ni contrasena")
	void aceptar_con_cuenta_no_pisa_credenciales() {
		ColaboradorInvitacion invitacion = pendiente();
		Cuenta existente = IdentityFixtures.conId(IdentityFixtures.cuentaActiva(), CUENTA_ID);

		given(invitacionRepository.findByTokenHash(TokenDigest.of(TOKEN_PLANO)))
				.willReturn(Optional.of(invitacion));
		given(cuentaRepository.findByEmailNormalizado(EMAIL_NORMALIZADO))
				.willReturn(Optional.of(existente));
		given(membershipProvisioning.createFromInvitation(eq(ORG_ID), any()))
				.willReturn(MEMBERSHIP_ID);

		ResultadoAceptacion resultado = servicio.aceptar(new InvitacionAceptacionCommand(
				TOKEN_PLANO, "Nombre Falso", "Apellido Falso", "otra-contrasena-larga"));

		assertThat(resultado.cuentaCreada()).isFalse();

		// Nada se guardo sobre la cuenta: reescribirle el nombre o la contrasena a alguien por
		// aceptar una invitacion seria una via para tomarle la cuenta con solo invitarlo.
		verify(cuentaRepository, never()).save(any());
	}

	@Test
	@DisplayName("rechazar no crea ninguna cuenta")
	void rechazar_no_crea_cuenta() {
		ColaboradorInvitacion invitacion = pendiente();
		given(invitacionRepository.findByTokenHash(TokenDigest.of(TOKEN_PLANO)))
				.willReturn(Optional.of(invitacion));

		servicio.rechazar(TOKEN_PLANO, "  Ya no estoy disponible  ");

		assertThat(invitacion.getEstado()).isEqualTo(EstadoInvitacion.RECHAZADA);
		assertThat(invitacion.getResolucionNota()).isEqualTo("Ya no estoy disponible");
		verify(cuentaRepository, never()).save(any());
		verify(membershipProvisioning, never()).createFromInvitation(anyLong(), any());
	}

	@Test
	@DisplayName("dos altas simultaneas chocan contra el unique: 409 de duplicada, no un 500")
	void el_unique_de_la_invitacion_se_traduce() {
		given(cuentaRepository.findByEmailNormalizado(EMAIL_NORMALIZADO))
				.willReturn(Optional.empty());
		willThrow(new DataIntegrityViolationException("uq_invitacion_pendiente"))
				.given(invitacionRepository).saveAndFlush(any());

		assertThatThrownBy(() -> servicio.invitar(
				actor, new InvitacionAltaCommand(EMAIL_TIPEADO, ROL, CONSULTORIO_ID)))
				.isInstanceOf(InvitacionPendienteDuplicadaException.class);

		verify(notificationOutbox, never()).encolar(any());
	}

	@Test
	@DisplayName("si entre medio la dieron de alta por el camino directo, aceptar es 409 de ya vinculada")
	void aceptar_con_vinculo_creado_entre_medio() {
		ColaboradorInvitacion invitacion = pendiente();
		given(invitacionRepository.findByTokenHash(TokenDigest.of(TOKEN_PLANO)))
				.willReturn(Optional.of(invitacion));
		given(cuentaRepository.findByEmailNormalizado(EMAIL_NORMALIZADO))
				.willReturn(Optional.empty());
		given(membershipProvisioning.createFromInvitation(eq(ORG_ID), any()))
				.willThrow(new MembershipAlreadyExistsException());

		assertThatThrownBy(() -> servicio.aceptar(new InvitacionAceptacionCommand(
				TOKEN_PLANO, "Ana", "Gomez", PASSWORD)))
				.isInstanceOf(ColaboradorYaVinculadoException.class);

		assertThat(invitacion.getEstado()).isEqualTo(EstadoInvitacion.PENDIENTE);
	}

	@Test
	@DisplayName("cualquier otro rechazo del vinculo sale tal cual: no se disfraza de ya vinculada")
	void aceptar_con_otro_rechazo_del_vinculo() {
		given(invitacionRepository.findByTokenHash(TokenDigest.of(TOKEN_PLANO)))
				.willReturn(Optional.of(pendiente()));
		given(cuentaRepository.findByEmailNormalizado(EMAIL_NORMALIZADO))
				.willReturn(Optional.empty());
		IllegalStateException topeDePlan = new IllegalStateException("tope de plan");
		given(membershipProvisioning.createFromInvitation(eq(ORG_ID), any())).willThrow(topeDePlan);

		assertThatThrownBy(() -> servicio.aceptar(new InvitacionAceptacionCommand(
				TOKEN_PLANO, "Ana", "Gomez", PASSWORD)))
				.isSameAs(topeDePlan);
	}

	/** El servicio la reconoce por el nombre simple: ArchUnit le prohibe importar la de organization. */
	private static final class MembershipAlreadyExistsException extends RuntimeException {
	}

	private ColaboradorInvitacion pendiente() {
		return conId(new ColaboradorInvitacion(
				ORG_ID, CONSULTORIO_ID, EMAIL_NORMALIZADO, ROL,
				TokenDigest.of(TOKEN_PLANO), AHORA.plus(Duration.ofDays(14)), ADMIN_ID));
	}

	private static ColaboradorInvitacion conId(ColaboradorInvitacion invitacion) {
		return IdentityFixtures.conId(invitacion, INVITACION_ID);
	}

	/** Azucar para el {@code never()} sobre {@code createDirect}, que toma un boolean. */
	private static boolean anyBooleanValue() {
		return org.mockito.ArgumentMatchers.anyBoolean();
	}
}
