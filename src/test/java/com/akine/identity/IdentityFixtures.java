package com.akine.identity;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.EstadoCuenta;
import com.akine.identity.domain.MotivoRevocacion;
import com.akine.identity.domain.RefreshToken;
import com.akine.identity.domain.TipoTokenVerificacion;
import com.akine.identity.domain.TokenDigest;
import com.akine.identity.domain.TokenVerificacion;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Entidades de identidad con id asignado, para tests sin base.
 *
 * <p>El id lo pone la base y las entities no exponen setter, asi que en un test hay que
 * asignarlo por reflexion. Agregarles un setter "solo para los tests" es como termina
 * existiendo en produccion.
 *
 * <p>Datos sinteticos sin excepcion. Ninguna direccion real, ningun nombre real, y ninguna
 * contrasena que alguien pueda estar usando de verdad.
 */
public final class IdentityFixtures {

	public static final long CUENTA_ID = 100L;
	public static final long OTRA_CUENTA_ID = 101L;
	public static final long ORG_ID = 10L;
	public static final long ACTOR_ID = 99L;
	public static final long TOKEN_ID = 500L;

	public static final String EMAIL = "ana.gomez@ejemplo.test";
	public static final String PASSWORD_VALIDA = "kinesiologia-2026";

	private IdentityFixtures() {
	}

	public static <T> T conId(T entidad, long id) {
		ReflectionTestUtils.setField(entidad, "id", id);
		return entidad;
	}

	/** Cuenta recien creada: PENDIENTE_ACTIVACION, con credencial ya fijada. */
	public static Cuenta cuentaPendiente() {
		return conId(new Cuenta(EMAIL, "Ana", "Gomez", "{hash}fake"), CUENTA_ID);
	}

	/** Cuenta que puede autenticarse. */
	public static Cuenta cuentaActiva() {
		Cuenta cuenta = cuentaPendiente();
		cuenta.transicionarA(EstadoCuenta.ACTIVA, null, Instant.now());
		return cuenta;
	}

	/** Cuenta suspendida administrativamente. */
	public static Cuenta cuentaBloqueada() {
		Cuenta cuenta = cuentaActiva();
		cuenta.transicionarA(EstadoCuenta.BLOQUEADA, "sospecha de compromiso", Instant.now());
		return cuenta;
	}

	/** Token de verificacion vivo del tipo pedido, con el digest del valor plano dado. */
	public static TokenVerificacion token(TipoTokenVerificacion tipo, String tokenPlano) {
		return conId(new TokenVerificacion(
				CUENTA_ID, tipo, TokenDigest.of(tokenPlano), Instant.now()), TOKEN_ID);
	}

	/** Refresh vivo de la cuenta. */
	public static RefreshToken refreshVivo(long id) {
		Instant ahora = Instant.now();
		return conId(new RefreshToken(
				CUENTA_ID, "familia-1", "hash-" + id, ahora, ahora.plus(12, ChronoUnit.HOURS),
				ORG_ID, 20L, "10.0.0.1", "test-agent"), id);
	}

	/** Refresh que ya fue revocado. */
	public static RefreshToken refreshRevocado(long id) {
		RefreshToken token = refreshVivo(id);
		token.revocar(MotivoRevocacion.LOGOUT, Instant.now());
		return token;
	}
}
