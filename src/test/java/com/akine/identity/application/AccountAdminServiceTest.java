package com.akine.identity.application;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.EstadoCuenta;
import com.akine.identity.domain.MotivoRevocacion;
import com.akine.identity.domain.RefreshToken;
import com.akine.identity.domain.exception.AccountNotFoundException;
import com.akine.identity.domain.exception.InvalidAccountTransitionException;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.identity.domain.port.RefreshTokenRepositoryPort;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.MembershipSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static com.akine.identity.IdentityFixtures.ACTOR_ID;
import static com.akine.identity.IdentityFixtures.CUENTA_ID;
import static com.akine.identity.IdentityFixtures.ORG_ID;
import static com.akine.identity.IdentityFixtures.cuentaActiva;
import static com.akine.identity.IdentityFixtures.cuentaBloqueada;
import static com.akine.identity.IdentityFixtures.refreshVivo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;


/**
 * Bloqueo, desbloqueo y desactivacion (RF-M02-005).
 *
 * <p>Tres tests sostienen las decisiones que importan:
 * {@link #bloquear_revoca_todas_las_sesiones_vivas()} —sin eso el bloqueo no bloquea nada,
 * porque el refresh sigue renovando—,
 * {@link #una_transicion_invalida_no_persiste_nada()} —un 409 explicito en vez de un 200 que
 * oculta la carrera entre dos administradores— y
 * {@link #una_cuenta_de_otra_organizacion_es_inexistente()} —404 y no 403, para que nadie
 * enumere cuentas ajenas recorriendo ids—.
 */
@ExtendWith(MockitoExtension.class)
class AccountAdminServiceTest {

	private static final AccountAdminService.Actor ADMIN =
			new AccountAdminService.Actor(ACTOR_ID, ORG_ID, false);

	private static final AccountAdminService.Actor ADMIN_PLATAFORMA =
			new AccountAdminService.Actor(ACTOR_ID, null, true);

	private static final String MOTIVO = "sospecha de compromiso de la cuenta";

	@Mock
	private CuentaRepositoryPort cuentaRepository;

	@Mock
	private RefreshTokenRepositoryPort refreshTokenRepository;

	@Mock
	private AccountContextDirectory accountContextDirectory;

	@Mock
	private AuditTrail auditTrail;

	@InjectMocks
	private AccountAdminService service;

	private void actorAdministraLaOrganizacion() {
		given(accountContextDirectory.membership(ACTOR_ID, ORG_ID))
				.willReturn(Optional.of(new MembershipSnapshot(
						60L, "ORG_ADMIN", true,
						Instant.now().minus(30, ChronoUnit.DAYS), null, true)));
	}

	private void cuentaObjetivoEsDeLaOrganizacion() {
		given(accountContextDirectory.hasActiveMembership(CUENTA_ID, ORG_ID)).willReturn(true);
	}

	private AuditEntry capturarAuditoria() {
		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		return captor.getValue();
	}

	// =================================================================================
	// Bloqueo
	// =================================================================================

	@Test
	@DisplayName("bloquear revoca todas las sesiones vivas, en la misma transaccion")
	void bloquear_revoca_todas_las_sesiones_vivas() {
		actorAdministraLaOrganizacion();
		cuentaObjetivoEsDeLaOrganizacion();
		Cuenta cuenta = cuentaActiva();
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(cuenta));
		List<RefreshToken> vivos = List.of(refreshVivo(1L), refreshVivo(2L));
		given(refreshTokenRepository.findByCuentaIdAndRevocadoEnIsNull(CUENTA_ID))
				.willReturn(vivos);

		AccountView resultado = service.bloquear(ADMIN, CUENTA_ID, MOTIVO);

		assertThat(resultado.estado()).isEqualTo(EstadoCuenta.BLOQUEADA.name());
		assertThat(resultado.bloqueadaEn()).isNotNull();
		assertThat(cuenta.getBloqueadaMotivo()).isEqualTo(MOTIVO);
		assertThat(vivos).allSatisfy(sesion -> {
			assertThat(sesion.getRevocadoEn()).isNotNull();
			assertThat(sesion.getMotivoRevocacion()).isEqualTo(MotivoRevocacion.BLOQUEO);
		});
		verify(refreshTokenRepository).saveAll(vivos);
		verify(cuentaRepository).save(cuenta);
	}

	@Test
	@DisplayName("la auditoria lleva actor, motivo y los dos estados")
	void la_auditoria_lleva_actor_motivo_y_estados() {
		actorAdministraLaOrganizacion();
		cuentaObjetivoEsDeLaOrganizacion();
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(cuentaActiva()));
		given(refreshTokenRepository.findByCuentaIdAndRevocadoEnIsNull(CUENTA_ID))
				.willReturn(List.of());

		service.bloquear(ADMIN, CUENTA_ID, MOTIVO);

		AuditEntry entrada = capturarAuditoria();
		assertThat(entrada.eventType()).isEqualTo("CUENTA_BLOQUEADA");
		assertThat(entrada.actorAccountId()).isEqualTo(ACTOR_ID);
		assertThat(entrada.entityId()).isEqualTo(CUENTA_ID);
		assertThat(entrada.organizationId()).isEqualTo(ORG_ID);
		assertThat(entrada.previousState()).isEqualTo("ACTIVA");
		assertThat(entrada.newState()).isEqualTo("BLOQUEADA");
		assertThat(entrada.reason()).isEqualTo(MOTIVO);
		assertThat(entrada.details()).containsEntry("sesionesRevocadas", "0");
	}

	@Test
	@DisplayName("una transicion invalida no persiste nada: 409, no un 200 silencioso")
	void una_transicion_invalida_no_persiste_nada() {
		actorAdministraLaOrganizacion();
		cuentaObjetivoEsDeLaOrganizacion();
		Cuenta yaBloqueada = cuentaBloqueada();
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(yaBloqueada));

		assertThatThrownBy(() -> service.bloquear(ADMIN, CUENTA_ID, MOTIVO))
				.isInstanceOf(InvalidAccountTransitionException.class);

		// Dos administradores a la vez: el segundo tiene que enterarse, y no puede quedar ni
		// una escritura ni una fila de auditoria diciendo que bloqueo algo.
		verify(cuentaRepository, never()).save(any());
		verify(refreshTokenRepository, never()).saveAll(any());
		verifyNoInteractions(auditTrail);
	}

	// =================================================================================
	// Desbloqueo y desactivacion
	// =================================================================================

	@Test
	@DisplayName("desbloquear devuelve el acceso y no toca las sesiones")
	void desbloquear_devuelve_el_acceso() {
		actorAdministraLaOrganizacion();
		cuentaObjetivoEsDeLaOrganizacion();
		Cuenta bloqueada = cuentaBloqueada();
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(bloqueada));

		AccountView resultado = service.desbloquear(ADMIN, CUENTA_ID, "el incidente se cerro");

		assertThat(resultado.estado()).isEqualTo(EstadoCuenta.ACTIVA.name());
		assertThat(resultado.bloqueadaEn()).isNull();
		assertThat(bloqueada.getBloqueadaMotivo()).isNull();
		// No hay sesiones que cortar: el bloqueo ya las corto. Revocar de nuevo escribiria
		// sobre filas ya revocadas y lo contaria como si algo hubiera pasado.
		verify(refreshTokenRepository, never()).findByCuentaIdAndRevocadoEnIsNull(CUENTA_ID);
		assertThat(capturarAuditoria().eventType()).isEqualTo("CUENTA_DESBLOQUEADA");
	}

	@Test
	@DisplayName("desactivar es baja logica: la fila se conserva y las sesiones se cortan")
	void desactivar_es_baja_logica() {
		actorAdministraLaOrganizacion();
		cuentaObjetivoEsDeLaOrganizacion();
		Cuenta cuenta = cuentaActiva();
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(cuenta));
		List<RefreshToken> vivos = List.of(refreshVivo(1L));
		given(refreshTokenRepository.findByCuentaIdAndRevocadoEnIsNull(CUENTA_ID))
				.willReturn(vivos);

		AccountView resultado = service.desactivar(ADMIN, CUENTA_ID, "baja del profesional");

		assertThat(resultado.estado()).isEqualTo(EstadoCuenta.DESACTIVADA.name());
		assertThat(cuenta.isActive()).isFalse();
		assertThat(cuenta.getDeletedAt()).isNotNull();
		assertThat(vivos.get(0).getMotivoRevocacion()).isEqualTo(MotivoRevocacion.DESACTIVACION);
		assertThat(capturarAuditoria().eventType()).isEqualTo("CUENTA_DESACTIVADA");
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	@Test
	@DisplayName("una cuenta de otra organizacion es inexistente: 404, jamas 403")
	void una_cuenta_de_otra_organizacion_es_inexistente() {
		actorAdministraLaOrganizacion();
		given(accountContextDirectory.hasActiveMembership(CUENTA_ID, ORG_ID)).willReturn(false);

		assertThatThrownBy(() -> service.bloquear(ADMIN, CUENTA_ID, MOTIVO))
				.isInstanceOf(AccountNotFoundException.class);

		verifyNoInteractions(cuentaRepository, auditTrail);
	}

	@Test
	@DisplayName("sin rol de administrador no se opera ninguna cuenta")
	void sin_rol_de_administrador_no_se_opera() {
		given(accountContextDirectory.membership(ACTOR_ID, ORG_ID))
				.willReturn(Optional.of(new MembershipSnapshot(
						60L, "PROFESIONAL", false,
						Instant.now().minus(30, ChronoUnit.DAYS), null, true)));

		assertThatThrownBy(() -> service.bloquear(ADMIN, CUENTA_ID, MOTIVO))
				.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(cuentaRepository, auditTrail);
	}

	@Test
	@DisplayName("una membership vencida no habilita: el rol se revalida, no se cree")
	void una_membership_vencida_no_habilita() {
		given(accountContextDirectory.membership(ACTOR_ID, ORG_ID))
				.willReturn(Optional.of(new MembershipSnapshot(
						60L, "ORG_ADMIN", true,
						Instant.now().minus(30, ChronoUnit.DAYS),
						Instant.now().minus(1, ChronoUnit.DAYS), true)));

		assertThatThrownBy(() -> service.bloquear(ADMIN, CUENTA_ID, MOTIVO))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("sin contexto de organizacion no hay operacion administrativa")
	void sin_contexto_no_hay_operacion() {
		AccountAdminService.Actor sinContexto =
				new AccountAdminService.Actor(ACTOR_ID, null, false);

		assertThatThrownBy(() -> service.bloquear(sinContexto, CUENTA_ID, MOTIVO))
				.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(accountContextDirectory, cuentaRepository);
	}

	@Test
	@DisplayName("el administrador de plataforma opera sin membership")
	void el_administrador_de_plataforma_opera_sin_membership() {
		Cuenta cuenta = cuentaActiva();
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(cuenta));
		given(refreshTokenRepository.findByCuentaIdAndRevocadoEnIsNull(CUENTA_ID))
				.willReturn(List.of());

		service.bloquear(ADMIN_PLATAFORMA, CUENTA_ID, MOTIVO);

		assertThat(cuenta.getEstado()).isEqualTo(EstadoCuenta.BLOQUEADA);
		verifyNoInteractions(accountContextDirectory);
	}

	@Test
	@DisplayName("una cuenta que no existe se responde igual que una ajena")
	void una_cuenta_inexistente_se_responde_igual() {
		actorAdministraLaOrganizacion();
		cuentaObjetivoEsDeLaOrganizacion();
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.bloquear(ADMIN, CUENTA_ID, MOTIVO))
				.isInstanceOf(AccountNotFoundException.class);
	}

	// =================================================================================
	// Motivo
	// =================================================================================

	@Test
	@DisplayName("las tres operaciones exigen motivo, y se comprueba antes de autorizar")
	void las_tres_operaciones_exigen_motivo() {
		assertThatThrownBy(() -> service.bloquear(ADMIN, CUENTA_ID, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.desbloquear(ADMIN, CUENTA_ID, "  "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.desactivar(ADMIN, CUENTA_ID, null))
				.isInstanceOf(IllegalArgumentException.class);

		verifyNoInteractions(cuentaRepository, refreshTokenRepository, auditTrail);
	}
}
