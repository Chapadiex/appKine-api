package com.akine.identity.application;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.MotivoRevocacion;
import com.akine.identity.domain.RefreshToken;
import com.akine.identity.domain.SessionSettings;
import com.akine.identity.domain.TokenDigest;
import com.akine.identity.domain.exception.ContextNotAvailableException;
import com.akine.identity.domain.exception.InvalidRefreshTokenException;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.identity.domain.port.IdentityClock;
import com.akine.identity.domain.port.RefreshTokenRepositoryPort;
import com.akine.identity.domain.port.TokenGenerator;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.security.AccessTokenIssuer;
import com.akine.platform.spi.security.AccessTokenScope;
import com.akine.platform.spi.security.IssuedAccessToken;
import com.akine.platform.spi.tenant.MembershipDirectory;
import com.akine.platform.spi.tenant.TenantMembership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ciclo de vida de la sesion: abrir, refrescar con rotacion, cambiar de contexto y cerrar
 * (RF-M02-002, ADR-0017, ADR-0009).
 *
 * <h2>Reparto de responsabilidades</h2>
 *
 * <p>{@code AuthenticationService} decide <b>quien puede entrar</b>. Esta clase decide
 * <b>cuanto dura y como se renueva</b> lo que se le entrega. La separacion permite recorrer la
 * regla de credenciales sin instanciar nada que firme tokens, y permite cambiar la politica de
 * sesion sin tocar la anti-enumeracion.
 *
 * <h2>El refresh es una fila, no un JWT</h2>
 *
 * <p>Y de esa fila solo se guarda el <b>SHA-256</b> del valor opaco ({@link TokenDigest},
 * RN-M02-003). El valor en claro existe unicamente en la respuesta HTTP y en la cookie del
 * navegador: nunca en la base, nunca en el log, nunca en la auditoria. Un volcado de
 * {@code refresh_token} no entrega ni una sola sesion usable. La busqueda no necesita el valor
 * plano: se hashea lo que llega y se compara contra el unique.
 *
 * <h2>Rotacion estricta y deteccion de reuso</h2>
 *
 * <p>Cada canje marca el token presentado como rotado ({@code usado_en} mas el enlace al
 * sucesor) y emite uno nuevo en la <b>misma familia</b>, heredando el vencimiento absoluto.
 * Presentar un token ya rotado o ya revocado significa que hay dos portadores de la misma
 * cadena y no se sabe cual es el legitimo: la respuesta correcta no es rechazar ese token sino
 * {@link #responderAlReuso revocar la familia completa}. Victima y atacante quedan afuera; la
 * victima vuelve a autenticarse, el atacante no puede.
 *
 * <p><b>Hacia afuera el reuso es indistinguible de cualquier otro token invalido</b>: mismo
 * {@link InvalidRefreshTokenException}, mismo 401, mismo cuerpo. Decirle al atacante que su
 * copia fue detectada le regala la unica informacion que necesita para ajustar el ataque. La
 * deteccion vive en la auditoria ({@code REFRESH_REUSO_DETECTADO}), que es donde se puede
 * investigar sin devolversela a quien pregunta.
 *
 * <h2>Sin ventana de gracia</h2>
 *
 * <p>ADR-0017 preve una gracia de 10 s que devuelve el sucesor ya emitido cuando dos pestanas
 * refrescan a la vez. <b>No es implementable</b> con la custodia que el mismo ADR fija: del
 * sucesor solo queda su SHA-256, y de un digest no se vuelve al valor que habria que devolver.
 * Implementarla exigiria retener refresh en claro en memoria, que es exactamente lo que la
 * regla de custodia evita. Queda registrado como contradiccion del ADR; mientras tanto la
 * carrera entre pestanas se resuelve en el cliente, con la cola single-flight que el mismo ADR
 * ya le exige al frontend.
 */
@Service
public class SessionService {

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);

	private final AuthenticationService authenticationService;
	private final CuentaRepositoryPort cuentaRepository;
	private final RefreshTokenRepositoryPort refreshTokenRepository;
	private final TokenGenerator tokenGenerator;
	private final AccessTokenIssuer accessTokenIssuer;
	private final MembershipDirectory membershipDirectory;
	private final SessionSettings settings;
	private final IdentityClock clock;
	private final AuditTrail auditTrail;

	public SessionService(
			AuthenticationService authenticationService,
			CuentaRepositoryPort cuentaRepository,
			RefreshTokenRepositoryPort refreshTokenRepository,
			TokenGenerator tokenGenerator,
			AccessTokenIssuer accessTokenIssuer,
			MembershipDirectory membershipDirectory,
			SessionSettings settings,
			IdentityClock clock,
			AuditTrail auditTrail) {
		this.authenticationService = authenticationService;
		this.cuentaRepository = cuentaRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.tokenGenerator = tokenGenerator;
		this.accessTokenIssuer = accessTokenIssuer;
		this.membershipDirectory = membershipDirectory;
		this.settings = settings;
		this.clock = clock;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Abrir
	// =================================================================================

	/**
	 * Autentica y entrega el par de tokens de una sesion nueva.
	 *
	 * <p>El access sale con alcance {@link AccessTokenScope#PRE_CONTEXT}: el login autentica
	 * una identidad, no un rol (DP-02, ADR-0009). Ningun endpoint de negocio acepta ese
	 * alcance; para trabajar hay que elegir contexto con {@link #cambiarContexto}.
	 *
	 * @throws com.akine.identity.domain.exception.InvalidCredentialsException en cualquiera de
	 *         las causas de rechazo, sin distinguirlas (ADR-0018)
	 */
	@Transactional
	public SesionEmitida abrirSesion(String email, String password, DatosDeCliente cliente) {
		Cuenta cuenta = authenticationService.autenticar(email, password);

		Instant ahora = clock.now();
		Instant expiraEn = ahora.plus(settings.refreshTtl());
		String familiaId = tokenGenerator.nuevaFamilia();

		RefreshEmitido refresh = emitirRefresh(
				cuenta.getId(), familiaId, ahora, expiraEn, null, null, cliente);
		AccesoEmitido acceso = emitirAcceso(
				cuenta.getId(), AccessTokenScope.PRE_CONTEXT, null, null, null, familiaId);

		Map<String, String> details = new LinkedHashMap<>();
		details.put("familiaId", familiaId);
		// El id de la fila, jamas el token: un id no sirve para autenticarse.
		details.put("refreshTokenId", String.valueOf(refresh.fila().getId()));
		IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.SESION_ABIERTA,
				null, cuenta.getId(), cuenta.getId(), null, null, details, null, ahora);

		log.info("Sesion abierta: cuentaId={} familiaId={}", cuenta.getId(), familiaId);
		return new SesionEmitida(acceso, refresh.plano(), expiraEn);
	}

	// =================================================================================
	// Refrescar
	// =================================================================================

	/**
	 * Canjea un refresh vigente por un par nuevo.
	 *
	 * <p>Tres cosas ocurren en la misma transaccion: se revalida el estado de la cuenta —es la
	 * unica revalidacion que hay, porque el access no consulta la base (ADR-0017 D-3)—, se
	 * marca el token presentado como rotado apuntando a su sucesor, y se emite el sucesor con
	 * el <b>mismo vencimiento absoluto</b>.
	 *
	 * <p>Si el contexto que la sesion recordaba dejo de ser accesible —membership revocada,
	 * suscripcion cancelada— el par nuevo sale <b>degradado a {@code PRE_CONTEXT}</b> en vez de
	 * fallar: la persona sigue autenticada y el frontend la manda a elegir contexto de nuevo,
	 * que es un camino que ya existe. Cortarle la sesion entera por perder una membership seria
	 * castigarla por un cambio administrativo ajeno.
	 *
	 * @throws InvalidRefreshTokenException token inexistente, vencido, revocado, ya rotado
	 *         (reuso) o de una cuenta que ya no puede autenticarse. Las cinco, igual
	 */
	// noRollbackFor NO es un detalle: sin esto, la respuesta al reuso NUNCA se persiste.
	// InvalidRefreshTokenException es una RuntimeException, asi que Spring revertia la
	// transaccion entera al lanzarla — y responderAlReuso corre JUSTO ANTES de ese throw. La
	// revocacion de la familia y la fila REFRESH_REUSO_DETECTADO de la auditoria se escribian y
	// se deshacian en el mismo instante: ADR-0017 promete que "su uso genera reuso y mata la
	// familia entera", y la familia quedaba viva. El atacante conservaba su cadena y la victima
	// no se enteraba de nada. Se descubrio al escribir el test de concurrencia, que afirma sobre
	// el estado final de la base y no sobre el codigo de respuesta: la respuesta ya era la
	// correcta (401), lo que no ocurria era el efecto.
	//
	// La excepcion es una senal de negocio, no una falla: todo lo que este metodo escribe antes
	// de lanzarla es exactamente lo que tiene que quedar. Las otras cuatro causas del mismo
	// rechazo —token inexistente, vencido, revocado, cuenta sin acceso— no escriben nada, asi
	// que para ellas commitear es equivalente a revertir.
	@Transactional(noRollbackFor = InvalidRefreshTokenException.class)
	public SesionEmitida refrescar(String refreshPlano, DatosDeCliente cliente) {
		Instant ahora = clock.now();
		RefreshToken presentado = exigirFila(refreshPlano);

		if (presentado.getUsadoEn() != null || presentado.getRevocadoEn() != null) {
			// Hay una copia de esta cadena dando vueltas. No se sabe cual de los dos
			// portadores es el legitimo, asi que se los expulsa a los dos.
			responderAlReuso(presentado, ahora);
			throw new InvalidRefreshTokenException();
		}

		if (!presentado.esCanjeableEn(ahora)) {
			// Solo queda el vencimiento. No es una senial de nada: la sesion duro lo que tenia
			// que durar y no hay familia que revocar.
			log.debug("Refresh vencido: familiaId={}", presentado.getFamiliaId());
			throw new InvalidRefreshTokenException();
		}

		Cuenta cuenta = cuentaRepository.findById(presentado.getCuentaId())
				.orElseThrow(InvalidRefreshTokenException::new);
		if (!cuenta.puedeAutenticarse()) {
			// Bloqueada o desactivada entre dos refresh. Sus sesiones ya fueron revocadas en la
			// transaccion de la transicion; esto cubre el caso residual sin decir por que.
			log.info("Refresh rechazado por estado de cuenta: cuentaId={}", cuenta.getId());
			throw new InvalidRefreshTokenException();
		}

		ContextoDeSesion contexto = revalidarContexto(cuenta.getId(), presentado, ahora);

		RefreshEmitido sucesor = emitirRefresh(
				cuenta.getId(),
				presentado.getFamiliaId(),
				ahora,
				// Absoluto y heredado: la rotacion NO extiende la sesion.
				presentado.getExpiraEn(),
				contexto.organizationId(),
				contexto.consultorioId(),
				cliente);

		presentado.marcarRotado(sucesor.fila().getId(), ahora);
		refreshTokenRepository.save(presentado);

		AccesoEmitido acceso = emitirAcceso(
				cuenta.getId(),
				contexto.alcance(),
				contexto.organizationId(),
				contexto.consultorioId(),
				contexto.roleCode(),
				presentado.getFamiliaId());

		Map<String, String> details = new LinkedHashMap<>();
		details.put("familiaId", presentado.getFamiliaId());
		details.put("refreshTokenId", String.valueOf(sucesor.fila().getId()));
		details.put("alcance", contexto.alcance().claimValue());
		IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.SESION_REFRESCADA,
				contexto.organizationId(), cuenta.getId(), cuenta.getId(), null, null,
				details, null, ahora);

		log.info("Sesion refrescada: cuentaId={} familiaId={} alcance={}",
				cuenta.getId(), presentado.getFamiliaId(), contexto.alcance());
		return new SesionEmitida(acceso, sucesor.plano(), presentado.getExpiraEn());
	}

	/**
	 * Revoca la familia completa ante un reuso y lo deja auditado.
	 *
	 * <p>No lanza: quien la llama tira {@link InvalidRefreshTokenException} igual que ante
	 * cualquier otro token invalido. La separacion es deliberada, para que se lea que la
	 * respuesta al cliente es la misma haya reuso o no.
	 */
	private void responderAlReuso(RefreshToken presentado, Instant ahora) {
		int revocadas = revocarFamilia(
				presentado.getFamiliaId(), MotivoRevocacion.ROTACION_REUSO, ahora);

		Map<String, String> details = new LinkedHashMap<>();
		details.put("familiaId", presentado.getFamiliaId());
		details.put("sesionesRevocadas", String.valueOf(revocadas));
		details.put("motivoDeteccion",
				presentado.getUsadoEn() != null ? "TOKEN_ROTADO" : "TOKEN_REVOCADO");
		IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.REFRESH_REUSO_DETECTADO,
				null, presentado.getCuentaId(), presentado.getCuentaId(), null, null,
				details, null, ahora);

		log.warn("Reuso de refresh detectado: cuentaId={} familiaId={} sesionesRevocadas={}. "
						+ "Se revoco la familia completa.",
				presentado.getCuentaId(), presentado.getFamiliaId(), revocadas);
	}

	/**
	 * Vuelve a preguntar si el contexto que la sesion recordaba sigue siendo accesible.
	 *
	 * <p>El contexto guardado en la fila es un recuerdo, no una autorizacion: quien decide es
	 * {@link MembershipDirectory} leyendo la base ahora (RN-M01-003). Si ya no da, se degrada a
	 * pre-contexto en vez de fallar.
	 */
	private ContextoDeSesion revalidarContexto(long cuentaId, RefreshToken fila, Instant ahora) {
		Long organizationId = fila.getContextOrganizationId();
		Long consultorioId = fila.getContextConsultorioId();
		if (organizationId == null || consultorioId == null) {
			return ContextoDeSesion.sinContexto();
		}

		Optional<TenantMembership> membership = membershipDirectory.resolveMembership(
				cuentaId, organizationId, consultorioId, ahora);
		if (membership.isEmpty() || !membership.get().operationalStatus().allowsContextUsage()) {
			log.info("El contexto recordado dejo de ser accesible: cuentaId={} org={} "
							+ "consultorio={}. Se emite un token pre-contexto.",
					cuentaId, organizationId, consultorioId);
			return ContextoDeSesion.sinContexto();
		}

		return new ContextoDeSesion(organizationId, consultorioId, membership.get().roleCode());
	}

	// =================================================================================
	// Cambio de contexto
	// =================================================================================

	/**
	 * Emite un access token acotado a Organizacion mas Consultorio, <b>sin exigir login
	 * nuevo</b> (AGENT.md sec. 6, DP-02).
	 *
	 * <p>No rota el refresh ni entrega uno nuevo: la sesion es la misma, lo que cambia es el
	 * alcance del access. Lo que si hace es <b>dejar el contexto recordado en la familia
	 * viva</b>, para que el proximo refresh reemita sobre el mismo lugar donde la persona
	 * estaba trabajando en vez de devolverla a la pantalla de seleccion cada diez minutos.
	 *
	 * <p>La familia se filtra por {@code cuentaId} antes de tocarla: sin ese filtro, pasar el
	 * identificador de familia de otra persona dejaria escribir sobre su sesion.
	 *
	 * @param familiaId familia de la sesion en curso, del claim {@code fam} del access token.
	 *                  Puede ser {@code null}: el token se emite igual, solo que ninguna fila
	 *                  recuerda el contexto
	 * @throws ContextNotAvailableException si el contexto no es accesible para esta cuenta.
	 *         Se traduce a <b>404</b>, nunca 403
	 */
	@Transactional
	public AccesoEmitido cambiarContexto(
			long cuentaId, String familiaId, long organizationId, long consultorioId) {

		Instant ahora = clock.now();
		Optional<TenantMembership> resuelta = membershipDirectory.resolveMembership(
				cuentaId, organizationId, consultorioId, ahora);

		Optional<TenantMembership> accesible = resuelta
				.filter(membership -> membership.operationalStatus().allowsContextUsage());

		if (accesible.isEmpty()) {
			Map<String, String> rechazo = new LinkedHashMap<>();
			rechazo.put("organizationId", String.valueOf(organizationId));
			rechazo.put("consultorioId", String.valueOf(consultorioId));
			IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.CONTEXTO_RECHAZADO,
					null, cuentaId, cuentaId, null, null, rechazo, null, ahora);
			log.info("Cambio de contexto rechazado: cuentaId={} org={} consultorio={}",
					cuentaId, organizationId, consultorioId);
			throw new ContextNotAvailableException(organizationId, consultorioId);
		}

		TenantMembership membership = accesible.get();
		int recordado = recordarContextoEnLaFamilia(
				cuentaId, familiaId, organizationId, consultorioId);

		AccesoEmitido acceso = emitirAcceso(cuentaId, AccessTokenScope.CONTEXT,
				organizationId, consultorioId, membership.roleCode(), familiaId);

		Map<String, String> details = new LinkedHashMap<>();
		details.put("consultorioId", String.valueOf(consultorioId));
		details.put("roleCode", membership.roleCode());
		details.put("sesionesActualizadas", String.valueOf(recordado));
		IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.CONTEXTO_SELECCIONADO,
				organizationId, cuentaId, cuentaId, null, null, details, null, ahora);

		log.info("Contexto seleccionado: cuentaId={} org={} consultorio={} rol={}",
				cuentaId, organizationId, consultorioId, membership.roleCode());
		return acceso;
	}

	private int recordarContextoEnLaFamilia(
			long cuentaId, String familiaId, long organizationId, long consultorioId) {

		if (familiaId == null || familiaId.isBlank()) {
			return 0;
		}
		List<RefreshToken> vivos = refreshTokenRepository
				.findByFamiliaIdAndRevocadoEnIsNull(familiaId).stream()
				// Solo los eslabones de ESTA cuenta y solo los que todavia se pueden canjear:
				// escribir sobre uno ya rotado no cambiaria nada y ensuciaria el rastro.
				.filter(fila -> fila.getCuentaId() != null && fila.getCuentaId() == cuentaId)
				.filter(fila -> fila.getUsadoEn() == null)
				.toList();
		if (vivos.isEmpty()) {
			return 0;
		}
		vivos.forEach(fila -> fila.recordarContexto(organizationId, consultorioId));
		refreshTokenRepository.saveAll(vivos);
		return vivos.size();
	}

	// =================================================================================
	// Cerrar
	// =================================================================================

	/**
	 * Cierra la sesion a la que pertenece el refresh presentado.
	 *
	 * <p><b>No lanza nunca por "no existe".</b> Un logout que fallara ante un token desconocido
	 * seria un oraculo de tokens validos y ademas dejaria al frontend sin poder limpiar su
	 * estado cuando la cookie ya no sirve. Es idempotente por construccion: una revocacion
	 * posterior no pisa la primera.
	 *
	 * @return cuantos eslabones vivos se revocaron. Cero es un resultado valido
	 */
	@Transactional
	public int cerrarSesion(String refreshPlano) {
		Optional<RefreshToken> encontrada = buscarFila(refreshPlano);
		if (encontrada.isEmpty()) {
			log.debug("Logout con un refresh desconocido: no hay nada que revocar");
			return 0;
		}

		RefreshToken fila = encontrada.get();
		Instant ahora = clock.now();
		int revocadas = revocarFamilia(fila.getFamiliaId(), MotivoRevocacion.LOGOUT, ahora);

		Map<String, String> details = new LinkedHashMap<>();
		details.put("familiaId", fila.getFamiliaId());
		details.put("sesionesRevocadas", String.valueOf(revocadas));
		IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.SESION_CERRADA,
				null, fila.getCuentaId(), fila.getCuentaId(), null, null, details, null, ahora);

		log.info("Sesion cerrada: cuentaId={} familiaId={} sesionesRevocadas={}",
				fila.getCuentaId(), fila.getFamiliaId(), revocadas);
		return revocadas;
	}

	/**
	 * Cierra TODAS las sesiones de la cuenta, en todos sus dispositivos.
	 *
	 * <p>Es la misma revocacion en masa que ejecutan {@code AccountAdminService} al bloquear o
	 * desactivar y {@code PasswordResetService} al confirmar un reset. Esos dos ya la hacen
	 * dentro de su propia transaccion de negocio y no pasan por aca a proposito: sacarles la
	 * revocacion los volveria dependientes de este servicio para cumplir una invariante que es
	 * suya. Este metodo cubre el caso del usuario que decide echarse a si mismo.
	 *
	 * @return cuantas sesiones vivas se cortaron
	 */
	@Transactional
	public int cerrarTodasLasSesiones(long cuentaId) {
		Instant ahora = clock.now();
		List<RefreshToken> vivos =
				refreshTokenRepository.findByCuentaIdAndRevocadoEnIsNull(cuentaId);
		if (!vivos.isEmpty()) {
			vivos.forEach(fila -> fila.revocar(MotivoRevocacion.LOGOUT, ahora));
			refreshTokenRepository.saveAll(vivos);
		}

		IdentityAuditEvents.registrar(auditTrail, IdentityAuditEvents.SESIONES_CERRADAS,
				null, cuentaId, cuentaId, null, null,
				Map.of("sesionesRevocadas", String.valueOf(vivos.size())), null, ahora);

		log.info("Sesiones cerradas en masa: cuentaId={} sesionesRevocadas={}",
				cuentaId, vivos.size());
		return vivos.size();
	}

	// =================================================================================
	// Piezas compartidas
	// =================================================================================

	private int revocarFamilia(String familiaId, MotivoRevocacion motivo, Instant ahora) {
		List<RefreshToken> vivos =
				refreshTokenRepository.findByFamiliaIdAndRevocadoEnIsNull(familiaId);
		if (vivos.isEmpty()) {
			return 0;
		}
		vivos.forEach(fila -> fila.revocar(motivo, ahora));
		refreshTokenRepository.saveAll(vivos);
		return vivos.size();
	}

	private RefreshEmitido emitirRefresh(
			long cuentaId,
			String familiaId,
			Instant emitidoEn,
			Instant expiraEn,
			Long organizationId,
			Long consultorioId,
			DatosDeCliente cliente) {

		String plano = tokenGenerator.nuevoToken();
		DatosDeCliente datos = cliente == null ? DatosDeCliente.desconocido() : cliente;

		RefreshToken fila = refreshTokenRepository.save(new RefreshToken(
				cuentaId,
				familiaId,
				// Lo unico que llega a la base. El valor plano vuelve al cliente y se olvida.
				TokenDigest.of(plano),
				emitidoEn,
				expiraEn,
				organizationId,
				consultorioId,
				datos.ip(),
				datos.userAgent()));

		return new RefreshEmitido(plano, fila);
	}

	private AccesoEmitido emitirAcceso(
			long cuentaId,
			AccessTokenScope alcance,
			Long organizationId,
			Long consultorioId,
			String roleCode,
			String familiaId) {

		IssuedAccessToken emitido = accessTokenIssuer.issue(
				cuentaId, alcance, organizationId, consultorioId, roleCode, familiaId);
		return new AccesoEmitido(
				emitido.value(), emitido.expiresInSeconds(), alcance,
				organizationId, consultorioId, roleCode);
	}

	private RefreshToken exigirFila(String refreshPlano) {
		return buscarFila(refreshPlano).orElseThrow(InvalidRefreshTokenException::new);
	}

	/**
	 * Busca la fila tolerando un token vacio o mal formado.
	 *
	 * <p>{@link TokenDigest#of} rechaza el string vacio con {@code IllegalArgumentException},
	 * que la capa HTTP traduciria a 400 y delataria la diferencia entre "no mandaste token" y
	 * "mandaste uno que no sirve". Las dos tienen que salir por el mismo lugar.
	 */
	private Optional<RefreshToken> buscarFila(String refreshPlano) {
		if (refreshPlano == null || refreshPlano.isBlank()) {
			return Optional.empty();
		}
		return refreshTokenRepository.findByTokenHash(TokenDigest.of(refreshPlano));
	}

	// =================================================================================
	// Tipos de entrada y salida
	// =================================================================================

	/**
	 * Lo que se sabe del cliente que abre o renueva la sesion.
	 *
	 * <p>Primitivos y no {@code HttpServletRequest}: {@code application} no puede conocer HTTP
	 * (ArchUnit lo verifica). Los dos campos son best effort y se guardan truncados; sirven para
	 * que la persona reconozca una sesion ajena, no para autorizar nada.
	 */
	public record DatosDeCliente(String ip, String userAgent) {

		public static DatosDeCliente desconocido() {
			return new DatosDeCliente(null, null);
		}
	}

	/**
	 * Access token recien firmado.
	 *
	 * @param valor            el JWT compacto
	 * @param expiraEnSegundos vigencia, para que el cliente programe la renovacion
	 */
	public record AccesoEmitido(
			String valor,
			long expiraEnSegundos,
			AccessTokenScope alcance,
			Long organizationId,
			Long consultorioId,
			String roleCode) {
	}

	/**
	 * Par completo de una sesion abierta o renovada.
	 *
	 * @param refreshPlano    valor opaco. <b>Es una credencial:</b> viaja a la cookie
	 *                        {@code httpOnly} y no se loguea ni se audita en ningun caso
	 * @param refreshExpiraEn vencimiento ABSOLUTO de la sesion, que la rotacion no mueve
	 */
	public record SesionEmitida(
			AccesoEmitido acceso,
			String refreshPlano,
			Instant refreshExpiraEn) {
	}

	/** Fila persistida junto al valor plano que solo existe en esta invocacion. */
	private record RefreshEmitido(String plano, RefreshToken fila) {
	}

	/** Contexto con el que se va a emitir el access token de un refresh. */
	private record ContextoDeSesion(Long organizationId, Long consultorioId, String roleCode) {

		static ContextoDeSesion sinContexto() {
			return new ContextoDeSesion(null, null, null);
		}

		AccessTokenScope alcance() {
			return organizationId == null || consultorioId == null
					? AccessTokenScope.PRE_CONTEXT
					: AccessTokenScope.CONTEXT;
		}
	}
}
