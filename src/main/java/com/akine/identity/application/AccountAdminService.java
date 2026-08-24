package com.akine.identity.application;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.EstadoCuenta;
import com.akine.identity.domain.AccountStateMachine;
import com.akine.identity.domain.MotivoRevocacion;
import com.akine.identity.domain.RefreshToken;
import com.akine.identity.domain.exception.AccountNotFoundException;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.identity.domain.port.RefreshTokenRepositoryPort;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bloqueo, desbloqueo y desactivacion de cuentas (RF-M02-005, diseño §4.8).
 *
 * <p>Las tres operaciones son la misma con distinto destino, y por eso comparten un solo
 * camino: autorizar, transicionar, revocar sesiones, auditar. Tenerlas separadas invitaria a
 * que una se olvidara de revocar, que es el paso que hace que el bloqueo signifique algo.
 *
 * <h2>Una transicion invalida es un 409, no un 200 silencioso</h2>
 *
 * <p>Bloquear una cuenta ya bloqueada falla contra {@link AccountStateMachine} y sale por
 * {@code InvalidAccountTransitionException}, que la capa HTTP traduce a conflicto. Tratarlo
 * como idempotente seria comodo y estaria mal: dos administradores operando a la vez sobre la
 * misma cuenta tienen que enterarse de que el otro llego primero, y una segunda transicion
 * "exitosa" ademas escribiria una fila de auditoria que dice algo que no paso.
 *
 * <h2>Revocar es parte de la transicion, no un efecto posterior</h2>
 *
 * <p>Los refresh de la cuenta se revocan <b>en esta misma transaccion</b>. Si fuera un listener
 * post-commit, existiria una ventana —corta, pero real— en la que la cuenta figura bloqueada y
 * sus sesiones siguen renovandose. El access token ya emitido si sobrevive hasta su TTL
 * (&le; 10 min): es una ventana aceptada y documentada, no un olvido.
 *
 * <h2>Autorizacion</h2>
 *
 * <p>Toda la autorizacion de esta clase pasa por un solo metodo privado —{@link #autorizar}— y
 * desde AKINE-01.03 ese metodo delega en el evaluador de la matriz de permisos de
 * {@code organization}. El servicio ya no compara roles ni consulta memberships.
 *
 * <p>La condicion que evita la fuga cross-tenant <b>no se relajo</b>: la cuenta objetivo tiene
 * que tener membership en la organizacion del actor. Viaja como {@code targetAccountId} y la
 * absorbe el evaluador, tal como ADR-0019 lo exige —la matriz decide QUE puede hacer el actor,
 * la membership sigue decidiendo SOBRE QUIEN—. Una cuenta ajena se responde como inexistente
 * (404, jamas 403): un "prohibido" confirmaria que ese id existe y alcanzaria con recorrer
 * numeros para enumerar las cuentas de los demas centros.
 */
@Service
public class AccountAdminService {

	private static final Logger log = LoggerFactory.getLogger(AccountAdminService.class);

	/**
	 * Permiso de la matriz que habilita administrar colaboradores.
	 *
	 * <p>Viaja como texto y no como el enum de {@code organization.domain}: ese paquete es
	 * privado de su modulo y ArchUnit rechaza importarlo.
	 */
	private static final String PERMISO_GESTION_DE_COLABORADORES = "colaborador:manage";

	private final CuentaRepositoryPort cuentaRepository;
	private final RefreshTokenRepositoryPort refreshTokenRepository;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public AccountAdminService(
			CuentaRepositoryPort cuentaRepository,
			RefreshTokenRepositoryPort refreshTokenRepository,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {
		this.cuentaRepository = cuentaRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	/**
	 * Suspende el acceso de la cuenta. Reversible con {@link #desbloquear}.
	 *
	 * @throws AccessDeniedException si el actor no administra la organizacion desde la que opera
	 * @throws AccountNotFoundException si la cuenta no existe o no es de su organizacion
	 * @throws com.akine.identity.domain.exception.InvalidAccountTransitionException si la cuenta
	 *         no esta en un estado desde el que se pueda bloquear
	 */
	@Transactional
	public AccountView bloquear(Actor actor, long cuentaId, String motivo) {
		return AccountView.de(transicionar(actor, cuentaId, EstadoCuenta.BLOQUEADA, motivo,
				IdentityAuditEvents.CUENTA_BLOQUEADA, MotivoRevocacion.BLOQUEO));
	}

	/**
	 * Devuelve el acceso a una cuenta bloqueada.
	 *
	 * <p>No revoca nada: la cuenta llego aca sin sesiones vivas, porque el bloqueo ya las corto.
	 * Volver a revocar seria escribir sobre filas que ya estan revocadas y contarlo en la
	 * auditoria como si algo hubiera pasado.
	 *
	 * <p>El motivo se exige igual, aunque la maquina de estados no lo pida para este destino:
	 * devolverle el acceso a alguien es tan auditable como quitarselo, y "por que me
	 * desbloquearon" es una pregunta que aparece en las mismas revisiones que la otra.
	 */
	@Transactional
	public AccountView desbloquear(Actor actor, long cuentaId, String motivo) {
		return AccountView.de(transicionar(actor, cuentaId, EstadoCuenta.ACTIVA, motivo,
				IdentityAuditEvents.CUENTA_DESBLOQUEADA, MotivoRevocacion.BLOQUEO));
	}

	/**
	 * Baja logica de la cuenta. Estado TERMINAL: no hay vuelta desde la aplicacion.
	 *
	 * <p>La fila no se borra —los historicos que la referencian tienen que seguir siendo
	 * legibles (RN-M02-004, ADR-0004)—: queda {@code active = 0} con {@code deleted_at}.
	 */
	@Transactional
	public AccountView desactivar(Actor actor, long cuentaId, String motivo) {
		return AccountView.de(transicionar(actor, cuentaId, EstadoCuenta.DESACTIVADA, motivo,
				IdentityAuditEvents.CUENTA_DESACTIVADA, MotivoRevocacion.DESACTIVACION));
	}

	/**
	 * El camino unico de las tres operaciones.
	 *
	 * <p>El orden no es casual: se autoriza antes de leer la cuenta, la transicion se valida
	 * antes de tocar nada mas, y la revocacion y la auditoria ocurren despues de que la
	 * transicion se aplico. Si la transicion falla, no se escribio ni una fila.
	 */
	private Cuenta transicionar(
			Actor actor,
			long cuentaId,
			EstadoCuenta destino,
			String motivo,
			String evento,
			MotivoRevocacion motivoRevocacion) {

		exigirMotivo(motivo, destino);
		autorizar(actor, cuentaId);

		Cuenta cuenta = cuentaRepository.findById(cuentaId)
				.orElseThrow(() -> new AccountNotFoundException(cuentaId));

		EstadoCuenta anterior = cuenta.getEstado();
		Instant ahora = Instant.now();

		// Valida contra AccountStateMachine y lanza si el estado actual no lo permite. Va antes
		// de cualquier escritura para que un conflicto no deje efectos a medias.
		cuenta.transicionarA(destino, motivo, ahora);

		int sesiones = AccountStateMachine.revokesSessions(destino)
				? revocarSesiones(cuenta, motivoRevocacion, ahora)
				: 0;

		cuentaRepository.save(cuenta);

		Map<String, String> details = new LinkedHashMap<>();
		details.put("sesionesRevocadas", String.valueOf(sesiones));
		details.put("actorOrganizationId", String.valueOf(actor.organizationId()));
		IdentityAuditEvents.registrar(auditTrail, evento,
				actor.organizationId(), cuenta.getId(), actor.accountId(),
				anterior.name(), destino.name(), details, motivo, ahora);

		log.info("Cuenta {}: cuentaId={} actorAccountId={} estado={}->{} sesionesRevocadas={}",
				evento, cuenta.getId(), actor.accountId(), anterior, destino, sesiones);
		return cuenta;
	}

	/**
	 * Exige el motivo.
	 *
	 * <p>Se comprueba aca ademas de en la entity porque el desbloqueo tambien lo exige y la
	 * maquina de estados no se lo pide: sin motivo, la auditoria no responde "por que me quede
	 * afuera" —ni "quien me dejo entrar de vuelta"— seis meses despues, que es exactamente
	 * cuando se pregunta.
	 */
	private static void exigirMotivo(String motivo, EstadoCuenta destino) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"La transicion a " + destino + " exige un motivo declarado por el actor");
		}
	}

	/**
	 * Exige el permiso de la matriz para operar sobre la cuenta objetivo.
	 *
	 * <p>Desde AKINE-01.03 lo decide el evaluador de {@code organization}, no este servicio. Lo
	 * que sigue sin distinguirse es bloquear de desactivar: la matriz no tiene todavia dos
	 * permisos separados para eso, y las tres operaciones piden {@code colaborador:manage}.
	 *
	 * @throws AccessDeniedException    si el actor no administra la organizacion (403)
	 * @throws AccountNotFoundException si la cuenta objetivo no es de esa organizacion (404)
	 */
	private void autorizar(Actor actor, long cuentaId) {
		if (actor.platformAdmin()) {
			// Administra la plataforma entera: esta por encima de cualquier tenant, asi que
			// buscarle una membership en una organizacion seria contradictorio.
			return;
		}

		Long organizationId = actor.organizationId();
		if (organizationId == null) {
			throw new AccessDeniedException(
					"La operacion administrativa requiere un contexto de organizacion activo");
		}

		// El permiso lo decide el evaluador de la matriz, no este servicio. La verificacion "la
		// cuenta objetivo tiene membership en esta organizacion" NO se relajo: viaja como
		// targetAccountId y la absorbe el evaluador, tal como ADR-0019 lo exige —la matriz decide
		// QUE puede hacer el actor, la membership sigue decidiendo SOBRE QUIEN—. Un objetivo de
		// otra organizacion sale por el camino de alcance, que es 404 y nunca 403.
		//
		// consultorioId va en null a proposito: bloquear o desactivar una cuenta afecta a la
		// persona en TODA la organizacion, no en una sede. Con null, el evaluador exige una
		// membership de alcance organizacion, que es lo mismo que exigia el codigo anterior.
		//
		// El tipo de las excepciones que esto puede lanzar NO se nombra aca, y eso es parte del
		// diseño: ArchUnit prohibe que identity importe organization.domain, y una excepcion que
		// se deja propagar no genera un import. Las traduce el advice de organization, que es
		// @RestControllerAdvice y por lo tanto global. Es el mismo mecanismo, ya probado, de
		// ContextNotAuthorizedException.
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PERMISO_GESTION_DE_COLABORADORES,
				organizationId,
				null,
				cuentaId,
				Instant.now()));
	}

	/** Corta todas las sesiones vivas de la cuenta, dentro de esta transaccion. */
	private int revocarSesiones(Cuenta cuenta, MotivoRevocacion motivo, Instant ahora) {
		List<RefreshToken> vivos =
				refreshTokenRepository.findByCuentaIdAndRevocadoEnIsNull(cuenta.getId());
		if (vivos.isEmpty()) {
			return 0;
		}
		vivos.forEach(sesion -> sesion.revocar(motivo, ahora));
		refreshTokenRepository.saveAll(vivos);
		return vivos.size();
	}

	/**
	 * Quien ejecuta la operacion administrativa.
	 *
	 * <p>Primitivos y no un principal: {@code application} no puede conocer HTTP ni la forma del
	 * token. La capa {@code api} traduce el request a estos tres datos y los pasa; cuando el
	 * principal exista, este servicio no cambia. Es el mismo criterio que
	 * {@code AuthorizationGuard} de {@code organization}.
	 *
	 * @param accountId      cuenta autenticada que opera
	 * @param organizationId organizacion del contexto YA VALIDADO del request, o {@code null} si
	 *                       el request no trae contexto. Nunca un id que mando el cliente
	 * @param platformAdmin  administra la plataforma, por encima de cualquier tenant
	 */
	public record Actor(long accountId, Long organizationId, boolean platformAdmin) {
	}
}
