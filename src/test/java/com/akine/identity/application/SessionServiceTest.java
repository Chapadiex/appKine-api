package com.akine.identity.application;

import com.akine.identity.IdentityFixtures;
import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.MotivoRevocacion;
import com.akine.identity.domain.RefreshToken;
import com.akine.identity.domain.SessionSettings;
import com.akine.identity.domain.TokenDigest;
import com.akine.identity.domain.exception.ContextNotAvailableException;
import com.akine.identity.domain.exception.InvalidCredentialsException;
import com.akine.identity.domain.exception.InvalidRefreshTokenException;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.identity.domain.port.IdentityClock;
import com.akine.identity.domain.port.RefreshTokenRepositoryPort;
import com.akine.identity.domain.port.TokenGenerator;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.security.AccessTokenIssuer;
import com.akine.platform.spi.security.AccessTokenClaims;
import com.akine.platform.spi.security.AccessTokenScope;
import com.akine.platform.spi.security.IssuedAccessToken;
import com.akine.platform.spi.tenant.MembershipDirectory;
import com.akine.platform.spi.tenant.TenantMembership;
import com.akine.platform.spi.tenant.TenantOperationalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static com.akine.identity.IdentityFixtures.CUENTA_ID;
import static com.akine.identity.IdentityFixtures.EMAIL;
import static com.akine.identity.IdentityFixtures.ORG_ID;
import static com.akine.identity.IdentityFixtures.PASSWORD_VALIDA;
import static com.akine.identity.IdentityFixtures.cuentaActiva;
import static com.akine.identity.IdentityFixtures.cuentaBloqueada;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Ciclo de vida de la sesion (ADR-0017, ADR-0009).
 *
 * <p>Los tests que sostienen las decisiones que importan son cuatro:
 * {@link Refresco#un_refresh_ya_rotado_revoca_la_familia_entera()} —sin eso, robar la cookie es
 * acceso permanente—,
 * {@link Refresco#el_reuso_responde_igual_que_cualquier_token_invalido()} —decirle al atacante
 * que lo detectaron le entrega la unica informacion que necesita—,
 * {@link Refresco#la_rotacion_no_extiende_el_vencimiento()} —si lo extendiera, la sesion robada
 * no venceria nunca— y
 * {@link CambioDeContexto#un_contexto_ajeno_no_existe()} —404 y no 403, para que nadie enumere
 * consultorios de otras organizaciones—.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionServiceTest {

	private static final long CONSULTORIO_ID = 20L;
	private static final String FAMILIA = "familia-de-prueba";
	private static final String REFRESH_PLANO = "refresh-opaco-sintetico-de-prueba";
	private static final String REFRESH_NUEVO = "refresh-opaco-sintetico-sucesor";
	private static final Instant AHORA = Instant.parse("2026-08-23T12:00:00Z");
	private static final Duration TTL = Duration.ofHours(12);

	@Mock
	private AuthenticationService authenticationService;

	@Mock
	private CuentaRepositoryPort cuentaRepository;

	@Mock
	private RefreshTokenRepositoryPort refreshTokenRepository;

	@Mock
	private TokenGenerator tokenGenerator;

	@Mock
	private AccessTokenIssuer accessTokenIssuer;

	@Mock
	private MembershipDirectory membershipDirectory;

	@Mock
	private AuditTrail auditTrail;

	private SessionService service;

	/** Ids que la base asignaria al insertar. */
	private final AtomicLong secuencia = new AtomicLong(900L);

	/** Todo lo que se guardo, en orden, para poder afirmar sobre el estado final. */
	private final List<RefreshToken> guardados = new ArrayList<>();

	@BeforeEach
	void configurar() {
		IdentityClock clock = () -> AHORA;
		service = new SessionService(
				authenticationService, cuentaRepository, refreshTokenRepository, tokenGenerator,
				accessTokenIssuer, membershipDirectory, new SessionSettings(TTL), clock,
				auditTrail);

		given(tokenGenerator.nuevaFamilia()).willReturn(FAMILIA);
		given(tokenGenerator.nuevoToken()).willReturn(REFRESH_NUEVO);

		given(refreshTokenRepository.save(any())).willAnswer(invocacion -> {
			RefreshToken fila = invocacion.getArgument(0);
			if (fila.getId() == null) {
				IdentityFixtures.conId(fila, secuencia.incrementAndGet());
			}
			guardados.add(fila);
			return fila;
		});
		given(refreshTokenRepository.saveAll(any())).willAnswer(invocacion -> {
			List<RefreshToken> filas = new ArrayList<>();
			((Iterable<RefreshToken>) invocacion.getArgument(0)).forEach(filas::add);
			guardados.addAll(filas);
			return filas;
		});

		given(accessTokenIssuer.issue(anyLong(), any(), any(), any(), any(), any()))
				.willAnswer(invocacion -> {
					AccessTokenScope alcance = invocacion.getArgument(1);
					return new IssuedAccessToken("jwt-sintetico", new AccessTokenClaims(
							invocacion.getArgument(0), "jti", alcance,
							invocacion.getArgument(2), invocacion.getArgument(3),
							invocacion.getArgument(4), invocacion.getArgument(5),
							AHORA, AHORA.plusSeconds(600)), 600L);
				});
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	private RefreshToken filaViva(Long organizationId, Long consultorioId) {
		return IdentityFixtures.conId(new RefreshToken(
				CUENTA_ID, FAMILIA, TokenDigest.of(REFRESH_PLANO),
				AHORA.minus(1, ChronoUnit.HOURS), AHORA.plus(11, ChronoUnit.HOURS),
				organizationId, consultorioId, "10.0.0.9", "agente-de-prueba"), 800L);
	}

	private void laFilaEstaEnLaBase(RefreshToken fila) {
		given(refreshTokenRepository.findByTokenHash(TokenDigest.of(REFRESH_PLANO)))
				.willReturn(Optional.of(fila));
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(cuentaActiva()));
	}

	private void laFamiliaTieneVivos(RefreshToken... filas) {
		given(refreshTokenRepository.findByFamiliaIdAndRevocadoEnIsNull(FAMILIA))
				.willReturn(List.of(filas));
	}

	private void elContextoEsAccesible() {
		given(membershipDirectory.resolveMembership(CUENTA_ID, ORG_ID, CONSULTORIO_ID, AHORA))
				.willReturn(Optional.of(new TenantMembership(
						60L, "ORG_ADMIN", TenantOperationalStatus.ACTIVA)));
	}

	private List<AuditEntry> auditoria() {
		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail, org.mockito.Mockito.atLeast(0)).record(captor.capture());
		return captor.getAllValues();
	}

	private boolean seAudito(String evento) {
		return auditoria().stream().anyMatch(entrada -> evento.equals(entrada.eventType()));
	}

	// =================================================================================
	// Apertura
	// =================================================================================

	@Nested
	@DisplayName("Abrir sesion")
	class Apertura {

		@Test
		@DisplayName("emite el par y persiste el refresh HASHEADO, nunca en claro")
		void persiste_el_refresh_hasheado() {
			given(authenticationService.autenticar(EMAIL, PASSWORD_VALIDA))
					.willReturn(cuentaActiva());

			SessionService.SesionEmitida sesion = service.abrirSesion(
					EMAIL, PASSWORD_VALIDA, new SessionService.DatosDeCliente("10.0.0.1", "ua"));

			assertThat(sesion.refreshPlano()).isEqualTo(REFRESH_NUEVO);
			assertThat(guardados).hasSize(1);
			// La regla que sostiene todo ADR-0017: en la base solo hay digest.
			assertThat(guardados.get(0).getTokenHash())
					.isEqualTo(TokenDigest.of(REFRESH_NUEVO))
					.isNotEqualTo(REFRESH_NUEVO);
			assertThat(guardados.get(0).getFamiliaId()).isEqualTo(FAMILIA);
			assertThat(guardados.get(0).getExpiraEn()).isEqualTo(AHORA.plus(TTL));
		}

		@Test
		@DisplayName("el access sale PRE_CONTEXT: el login autentica una identidad, no un rol")
		void el_access_sale_pre_contexto() {
			given(authenticationService.autenticar(EMAIL, PASSWORD_VALIDA))
					.willReturn(cuentaActiva());

			SessionService.SesionEmitida sesion =
					service.abrirSesion(EMAIL, PASSWORD_VALIDA, null);

			assertThat(sesion.acceso().alcance()).isEqualTo(AccessTokenScope.PRE_CONTEXT);
			assertThat(sesion.acceso().organizationId()).isNull();
			verify(accessTokenIssuer).issue(
					CUENTA_ID, AccessTokenScope.PRE_CONTEXT, null, null, null, FAMILIA);
		}

		@Test
		@DisplayName("una credencial invalida no emite ni persiste nada")
		void una_credencial_invalida_no_emite_nada() {
			willThrow(new InvalidCredentialsException())
					.given(authenticationService).autenticar(EMAIL, "no-es-la-contrasena");

			assertThatThrownBy(() -> service.abrirSesion(EMAIL, "no-es-la-contrasena", null))
					.isInstanceOf(InvalidCredentialsException.class);

			assertThat(guardados).isEmpty();
			verify(accessTokenIssuer, never()).issue(anyLong(), any(), any(), any(), any(), any());
		}

		@Test
		@DisplayName("la auditoria registra la sesion sin el token, solo el id de la fila")
		void la_auditoria_no_lleva_el_token() {
			given(authenticationService.autenticar(EMAIL, PASSWORD_VALIDA))
					.willReturn(cuentaActiva());

			service.abrirSesion(EMAIL, PASSWORD_VALIDA, null);

			AuditEntry entrada = auditoria().get(0);
			assertThat(entrada.eventType()).isEqualTo("SESION_ABIERTA");
			assertThat(entrada.details().values())
					.noneMatch(valor -> valor.contains(REFRESH_NUEVO));
			assertThat(entrada.details()).containsEntry("familiaId", FAMILIA);
		}
	}

	// =================================================================================
	// Refresco
	// =================================================================================

	@Nested
	@DisplayName("Refrescar")
	class Refresco {

		@Test
		@DisplayName("rotacion feliz: el presentado queda rotado apuntando a su sucesor")
		void rotacion_feliz() {
			RefreshToken presentado = filaViva(null, null);
			laFilaEstaEnLaBase(presentado);

			SessionService.SesionEmitida sesion = service.refrescar(REFRESH_PLANO, null);

			assertThat(presentado.getUsadoEn()).isEqualTo(AHORA);
			assertThat(presentado.getReemplazadoPorId()).isNotNull();
			assertThat(sesion.refreshPlano()).isEqualTo(REFRESH_NUEVO);
			// El sucesor esta en la MISMA familia: la cadena se puede reconstruir entera.
			RefreshToken sucesor = guardados.get(0);
			assertThat(sucesor.getFamiliaId()).isEqualTo(FAMILIA);
			assertThat(presentado.getReemplazadoPorId()).isEqualTo(sucesor.getId());
			assertThat(seAudito("SESION_REFRESCADA")).isTrue();
		}

		@Test
		@DisplayName("la rotacion NO extiende el vencimiento: se hereda el absoluto")
		void la_rotacion_no_extiende_el_vencimiento() {
			RefreshToken presentado = filaViva(null, null);
			laFilaEstaEnLaBase(presentado);

			SessionService.SesionEmitida sesion = service.refrescar(REFRESH_PLANO, null);

			assertThat(guardados.get(0).getExpiraEn()).isEqualTo(presentado.getExpiraEn());
			assertThat(sesion.refreshExpiraEn()).isEqualTo(presentado.getExpiraEn());
			// Y no es el que le tocaria a una sesion nueva.
			assertThat(sesion.refreshExpiraEn()).isNotEqualTo(AHORA.plus(TTL));
		}

		@Test
		@DisplayName("un refresh ya rotado revoca la familia entera con ROTACION_REUSO")
		void un_refresh_ya_rotado_revoca_la_familia_entera() {
			RefreshToken presentado = filaViva(null, null);
			presentado.marcarRotado(801L, AHORA.minusSeconds(60));
			RefreshToken hermano = IdentityFixtures.conId(new RefreshToken(
					CUENTA_ID, FAMILIA, "otro-hash", AHORA.minusSeconds(60),
					AHORA.plus(11, ChronoUnit.HOURS), null, null, null, null), 801L);
			laFilaEstaEnLaBase(presentado);
			laFamiliaTieneVivos(presentado, hermano);

			assertThatThrownBy(() -> service.refrescar(REFRESH_PLANO, null))
					.isInstanceOf(InvalidRefreshTokenException.class);

			assertThat(presentado.getRevocadoEn()).isEqualTo(AHORA);
			assertThat(presentado.getMotivoRevocacion())
					.isEqualTo(MotivoRevocacion.ROTACION_REUSO);
			// El eslabon sucesor tambien muere: si sobreviviera, el atacante seguiria dentro.
			assertThat(hermano.getRevocadoEn()).isEqualTo(AHORA);
			assertThat(hermano.getMotivoRevocacion()).isEqualTo(MotivoRevocacion.ROTACION_REUSO);
			assertThat(seAudito("REFRESH_REUSO_DETECTADO")).isTrue();
		}

		@Test
		@DisplayName("un refresh ya revocado tambien es reuso y vuelve a barrer la familia")
		void un_refresh_ya_revocado_es_reuso() {
			RefreshToken presentado = filaViva(null, null);
			presentado.revocar(MotivoRevocacion.LOGOUT, AHORA.minusSeconds(30));
			RefreshToken hermano = IdentityFixtures.conId(new RefreshToken(
					CUENTA_ID, FAMILIA, "otro-hash", AHORA.minusSeconds(30),
					AHORA.plus(11, ChronoUnit.HOURS), null, null, null, null), 802L);
			laFilaEstaEnLaBase(presentado);
			laFamiliaTieneVivos(hermano);

			assertThatThrownBy(() -> service.refrescar(REFRESH_PLANO, null))
					.isInstanceOf(InvalidRefreshTokenException.class);

			assertThat(hermano.getMotivoRevocacion()).isEqualTo(MotivoRevocacion.ROTACION_REUSO);
			// La revocacion previa NO se pisa: el motivo original es la informacion valiosa.
			assertThat(presentado.getMotivoRevocacion()).isEqualTo(MotivoRevocacion.LOGOUT);
		}

		@Test
		@DisplayName("el reuso responde igual que cualquier token invalido: nada lo distingue")
		void el_reuso_responde_igual_que_cualquier_token_invalido() {
			RefreshToken rotado = filaViva(null, null);
			rotado.marcarRotado(801L, AHORA.minusSeconds(5));
			laFilaEstaEnLaBase(rotado);
			laFamiliaTieneVivos(rotado);

			Throwable porReuso = org.assertj.core.api.Assertions
					.catchThrowable(() -> service.refrescar(REFRESH_PLANO, null));

			given(refreshTokenRepository.findByTokenHash(any())).willReturn(Optional.empty());
			Throwable porInexistente = org.assertj.core.api.Assertions
					.catchThrowable(() -> service.refrescar("token-que-no-existe", null));

			assertThat(porReuso).isInstanceOf(InvalidRefreshTokenException.class);
			assertThat(porInexistente).isInstanceOf(InvalidRefreshTokenException.class);
			assertThat(porReuso.getMessage()).isEqualTo(porInexistente.getMessage());
			assertThat(porReuso.getClass()).isEqualTo(porInexistente.getClass());
		}

		@Test
		@DisplayName("un refresh vencido se rechaza y NO revoca la familia")
		void un_refresh_vencido_no_revoca_la_familia() {
			RefreshToken vencido = IdentityFixtures.conId(new RefreshToken(
					CUENTA_ID, FAMILIA, TokenDigest.of(REFRESH_PLANO),
					AHORA.minus(13, ChronoUnit.HOURS), AHORA.minus(1, ChronoUnit.HOURS),
					null, null, null, null), 803L);
			laFilaEstaEnLaBase(vencido);

			assertThatThrownBy(() -> service.refrescar(REFRESH_PLANO, null))
					.isInstanceOf(InvalidRefreshTokenException.class);

			assertThat(vencido.getRevocadoEn()).isNull();
			assertThat(seAudito("REFRESH_REUSO_DETECTADO")).isFalse();
			verify(refreshTokenRepository, never()).saveAll(any());
		}

		@Test
		@DisplayName("un token de otra familia solo toca su propia familia")
		void un_token_de_otra_familia_no_toca_la_ajena() {
			RefreshToken deOtraFamilia = IdentityFixtures.conId(new RefreshToken(
					CUENTA_ID, "familia-ajena", TokenDigest.of(REFRESH_PLANO),
					AHORA.minusSeconds(60), AHORA.plus(11, ChronoUnit.HOURS),
					null, null, null, null), 804L);
			deOtraFamilia.marcarRotado(805L, AHORA.minusSeconds(10));
			laFilaEstaEnLaBase(deOtraFamilia);
			given(refreshTokenRepository.findByFamiliaIdAndRevocadoEnIsNull("familia-ajena"))
					.willReturn(List.of(deOtraFamilia));

			assertThatThrownBy(() -> service.refrescar(REFRESH_PLANO, null))
					.isInstanceOf(InvalidRefreshTokenException.class);

			verify(refreshTokenRepository)
					.findByFamiliaIdAndRevocadoEnIsNull("familia-ajena");
			verify(refreshTokenRepository, never())
					.findByFamiliaIdAndRevocadoEnIsNull(FAMILIA);
		}

		@Test
		@DisplayName("un token vacio sale por el mismo camino que uno inexistente")
		void un_token_vacio_es_un_token_invalido() {
			assertThatThrownBy(() -> service.refrescar("   ", null))
					.isInstanceOf(InvalidRefreshTokenException.class);
			assertThatThrownBy(() -> service.refrescar(null, null))
					.isInstanceOf(InvalidRefreshTokenException.class);
		}

		@Test
		@DisplayName("una cuenta bloqueada no puede refrescar, y no se dice por que")
		void una_cuenta_bloqueada_no_refresca() {
			RefreshToken presentado = filaViva(null, null);
			given(refreshTokenRepository.findByTokenHash(TokenDigest.of(REFRESH_PLANO)))
					.willReturn(Optional.of(presentado));
			given(cuentaRepository.findById(CUENTA_ID))
					.willReturn(Optional.of(cuentaBloqueada()));

			assertThatThrownBy(() -> service.refrescar(REFRESH_PLANO, null))
					.isInstanceOf(InvalidRefreshTokenException.class);

			assertThat(presentado.getUsadoEn()).isNull();
		}

		@Test
		@DisplayName("el contexto recordado se revalida y reemite un access con alcance")
		void el_contexto_recordado_se_revalida() {
			RefreshToken presentado = filaViva(ORG_ID, CONSULTORIO_ID);
			laFilaEstaEnLaBase(presentado);
			elContextoEsAccesible();

			SessionService.SesionEmitida sesion = service.refrescar(REFRESH_PLANO, null);

			assertThat(sesion.acceso().alcance()).isEqualTo(AccessTokenScope.CONTEXT);
			assertThat(sesion.acceso().roleCode()).isEqualTo("ORG_ADMIN");
			assertThat(guardados.get(0).getContextOrganizationId()).isEqualTo(ORG_ID);
		}

		@Test
		@DisplayName("si el contexto dejo de ser accesible se degrada a PRE_CONTEXT, no falla")
		void un_contexto_perdido_degrada_a_pre_contexto() {
			RefreshToken presentado = filaViva(ORG_ID, CONSULTORIO_ID);
			laFilaEstaEnLaBase(presentado);
			given(membershipDirectory.resolveMembership(CUENTA_ID, ORG_ID, CONSULTORIO_ID, AHORA))
					.willReturn(Optional.empty());

			SessionService.SesionEmitida sesion = service.refrescar(REFRESH_PLANO, null);

			assertThat(sesion.acceso().alcance()).isEqualTo(AccessTokenScope.PRE_CONTEXT);
			assertThat(guardados.get(0).getContextOrganizationId()).isNull();
		}

		@Test
		@DisplayName("una suscripcion CANCELADA tambien degrada a PRE_CONTEXT")
		void una_suscripcion_cancelada_degrada() {
			RefreshToken presentado = filaViva(ORG_ID, CONSULTORIO_ID);
			laFilaEstaEnLaBase(presentado);
			given(membershipDirectory.resolveMembership(CUENTA_ID, ORG_ID, CONSULTORIO_ID, AHORA))
					.willReturn(Optional.of(new TenantMembership(
							60L, "ORG_ADMIN", TenantOperationalStatus.CANCELADA)));

			SessionService.SesionEmitida sesion = service.refrescar(REFRESH_PLANO, null);

			assertThat(sesion.acceso().alcance()).isEqualTo(AccessTokenScope.PRE_CONTEXT);
		}
	}

	// =================================================================================
	// Cambio de contexto
	// =================================================================================

	@Nested
	@DisplayName("Cambiar de contexto")
	class CambioDeContexto {

		@Test
		@DisplayName("un contexto autorizado emite un access CONTEXT sin pedir login nuevo")
		void un_contexto_autorizado_emite_access_con_alcance() {
			elContextoEsAccesible();
			laFamiliaTieneVivos(filaViva(null, null));

			SessionService.AccesoEmitido acceso =
					service.cambiarContexto(CUENTA_ID, FAMILIA, ORG_ID, CONSULTORIO_ID);

			assertThat(acceso.alcance()).isEqualTo(AccessTokenScope.CONTEXT);
			assertThat(acceso.organizationId()).isEqualTo(ORG_ID);
			assertThat(acceso.consultorioId()).isEqualTo(CONSULTORIO_ID);
			assertThat(acceso.roleCode()).isEqualTo("ORG_ADMIN");
			// Ni se autentico de nuevo ni se roto el refresh: la sesion es la misma.
			verify(authenticationService, never()).autenticar(any(), any());
			assertThat(seAudito("CONTEXTO_SELECCIONADO")).isTrue();
		}

		@Test
		@DisplayName("el contexto queda recordado en la familia viva para el proximo refresh")
		void el_contexto_queda_recordado() {
			elContextoEsAccesible();
			RefreshToken viva = filaViva(null, null);
			laFamiliaTieneVivos(viva);

			service.cambiarContexto(CUENTA_ID, FAMILIA, ORG_ID, CONSULTORIO_ID);

			assertThat(viva.getContextOrganizationId()).isEqualTo(ORG_ID);
			assertThat(viva.getContextConsultorioId()).isEqualTo(CONSULTORIO_ID);
		}

		@Test
		@DisplayName("un contexto no autorizado es 404: no existe, jamas 403")
		void un_contexto_ajeno_no_existe() {
			given(membershipDirectory.resolveMembership(CUENTA_ID, ORG_ID, 999L, AHORA))
					.willReturn(Optional.empty());

			assertThatThrownBy(() ->
					service.cambiarContexto(CUENTA_ID, FAMILIA, ORG_ID, 999L))
					.isInstanceOf(ContextNotAvailableException.class);

			assertThat(seAudito("CONTEXTO_RECHAZADO")).isTrue();
			verify(accessTokenIssuer, never())
					.issue(anyLong(), any(), any(), any(), any(), any());
		}

		@Test
		@DisplayName("una suscripcion CANCELADA es indistinguible de un contexto inexistente")
		void una_suscripcion_cancelada_no_habilita_contexto() {
			given(membershipDirectory.resolveMembership(CUENTA_ID, ORG_ID, CONSULTORIO_ID, AHORA))
					.willReturn(Optional.of(new TenantMembership(
							60L, "ORG_ADMIN", TenantOperationalStatus.BAJA)));

			assertThatThrownBy(() ->
					service.cambiarContexto(CUENTA_ID, FAMILIA, ORG_ID, CONSULTORIO_ID))
					.isInstanceOf(ContextNotAvailableException.class);
		}

		@Test
		@DisplayName("no se escribe sobre la familia de otra cuenta aunque se pase su id")
		void no_se_escribe_sobre_la_familia_ajena() {
			elContextoEsAccesible();
			RefreshToken deOtraPersona = IdentityFixtures.conId(new RefreshToken(
					IdentityFixtures.OTRA_CUENTA_ID, FAMILIA, "hash-ajeno",
					AHORA, AHORA.plus(TTL), null, null, null, null), 806L);
			laFamiliaTieneVivos(deOtraPersona);

			service.cambiarContexto(CUENTA_ID, FAMILIA, ORG_ID, CONSULTORIO_ID);

			assertThat(deOtraPersona.getContextOrganizationId()).isNull();
			verify(refreshTokenRepository, never()).saveAll(any());
		}

		@Test
		@DisplayName("sin familia el access se emite igual, sin recordar nada")
		void sin_familia_el_access_se_emite_igual() {
			elContextoEsAccesible();

			SessionService.AccesoEmitido acceso =
					service.cambiarContexto(CUENTA_ID, null, ORG_ID, CONSULTORIO_ID);

			assertThat(acceso.alcance()).isEqualTo(AccessTokenScope.CONTEXT);
			verify(refreshTokenRepository, never()).findByFamiliaIdAndRevocadoEnIsNull(any());
		}
	}

	// =================================================================================
	// Cierre
	// =================================================================================

	@Nested
	@DisplayName("Cerrar sesion")
	class Cierre {

		@Test
		@DisplayName("cerrar sesion revoca la familia entera con LOGOUT")
		void cerrar_sesion_revoca_la_familia() {
			RefreshToken viva = filaViva(null, null);
			given(refreshTokenRepository.findByTokenHash(TokenDigest.of(REFRESH_PLANO)))
					.willReturn(Optional.of(viva));
			laFamiliaTieneVivos(viva);

			int revocadas = service.cerrarSesion(REFRESH_PLANO);

			assertThat(revocadas).isEqualTo(1);
			assertThat(viva.getMotivoRevocacion()).isEqualTo(MotivoRevocacion.LOGOUT);
			assertThat(seAudito("SESION_CERRADA")).isTrue();
		}

		@Test
		@DisplayName("un logout con un token desconocido no falla ni revela nada")
		void un_logout_desconocido_no_falla() {
			given(refreshTokenRepository.findByTokenHash(any())).willReturn(Optional.empty());

			assertThat(service.cerrarSesion("token-que-no-existe")).isZero();
			assertThat(service.cerrarSesion(null)).isZero();
			verify(auditTrail, never()).record(any());
		}

		@Test
		@DisplayName("cerrar todas revoca por cuenta, no por familia")
		void cerrar_todas_revoca_por_cuenta() {
			RefreshToken unaSesion = filaViva(null, null);
			RefreshToken otraSesion = IdentityFixtures.conId(new RefreshToken(
					CUENTA_ID, "otra-familia", "otro-hash", AHORA, AHORA.plus(TTL),
					null, null, null, null), 807L);
			given(refreshTokenRepository.findByCuentaIdAndRevocadoEnIsNull(CUENTA_ID))
					.willReturn(List.of(unaSesion, otraSesion));

			int revocadas = service.cerrarTodasLasSesiones(CUENTA_ID);

			assertThat(revocadas).isEqualTo(2);
			assertThat(unaSesion.getRevocadoEn()).isEqualTo(AHORA);
			assertThat(otraSesion.getRevocadoEn()).isEqualTo(AHORA);
			assertThat(seAudito("SESIONES_CERRADAS")).isTrue();
		}

		@Test
		@DisplayName("cerrar todas sin sesiones vivas es cero y queda auditado igual")
		void cerrar_todas_sin_sesiones_es_cero() {
			given(refreshTokenRepository.findByCuentaIdAndRevocadoEnIsNull(CUENTA_ID))
					.willReturn(List.of());

			assertThat(service.cerrarTodasLasSesiones(CUENTA_ID)).isZero();

			assertThat(seAudito("SESIONES_CERRADAS")).isTrue();
			verify(refreshTokenRepository, never()).saveAll(any());
		}
	}

	// =================================================================================
	// Reglas de vigencia
	// =================================================================================

	@Nested
	@DisplayName("SessionSettings")
	class Vigencia {

		@Test
		@DisplayName("una vigencia no positiva es una sesion que nace vencida")
		void una_vigencia_no_positiva_se_rechaza() {
			assertThatThrownBy(() -> new SessionSettings(Duration.ZERO))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new SessionSettings(Duration.ofMinutes(-1)))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new SessionSettings(null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("el default es la jornada de un consultorio")
		void el_default_es_doce_horas() {
			assertThat(SessionSettings.porDefecto().refreshTtl())
					.isEqualTo(Duration.ofHours(12));
		}
	}

	// =================================================================================
	// Excepcion de contexto
	// =================================================================================

	@Test
	@DisplayName("ContextNotAvailableException conserva que contexto se pidio, para el log")
	void la_excepcion_de_contexto_conserva_los_ids() {
		ContextNotAvailableException excepcion = new ContextNotAvailableException(7L, 8L);

		assertThat(excepcion.getOrganizationId()).isEqualTo(7L);
		assertThat(excepcion.getConsultorioId()).isEqualTo(8L);
		assertThat(excepcion.getMessage()).isEqualTo("Contexto no disponible");
	}

	@Test
	@DisplayName("el cliente desconocido no rompe la emision del refresh")
	void el_cliente_desconocido_no_rompe_nada() {
		given(authenticationService.autenticar(eq(EMAIL), any())).willReturn(cuentaActiva());

		service.abrirSesion(EMAIL, PASSWORD_VALIDA, SessionService.DatosDeCliente.desconocido());

		assertThat(guardados.get(0).getIp()).isNull();
		assertThat(guardados.get(0).getUserAgent()).isNull();
	}
}
