package com.akine.identity.application;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.EmailNormalizado;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.organization.spi.DirectMembershipCommand;
import com.akine.organization.spi.MembershipProvisioning;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Alta directa de un colaborador a partir de su email.
 *
 * <h2>Por que el alta vive en {@code identity} y no en {@code organization}</h2>
 *
 * <p>Lo dice el javadoc de {@link MembershipProvisioning}: un administrador tipea un
 * <b>email</b>, y {@code cuenta} es propiedad de {@code identity}. {@code organization} no
 * compila contra este modulo —ArchUnit rechaza la flecha—, asi que la traduccion email &rarr;
 * {@code accountId} tiene que ocurrir de este lado. Recibir el {@code accountId} desde el
 * cliente seria dejar que el cliente elija a quien vincular por id.
 *
 * <h2>El orden de los pasos ES la seguridad de este servicio</h2>
 *
 * <ol>
 *   <li><b>Permiso primero.</b> {@code colaborador:manage} se exige ANTES de mirar el email. Al
 *       reves, cualquier cuenta autenticada con contexto podria preguntar por direcciones
 *       ajenas y leer la respuesta antes de que el permiso la frenara.</li>
 *   <li><b>Email despues.</b> Recien con el permiso concedido se resuelve la direccion.</li>
 *   <li><b>El vinculo lo crea {@code organization}</b>, que vuelve a autorizar bajo el bloqueo
 *       del tenant y audita {@code MEMBERSHIP_CREATED} dentro de esa misma transaccion.</li>
 * </ol>
 *
 * <h2>El email no encontrado es 404, y eso tiene contrapartidas</h2>
 *
 * <p>Decision del usuario del 24/08/2026. Responder distinto segun exista la cuenta convierte
 * este endpoint en un <b>oraculo de enumeracion autenticado</b>: es exactamente lo que
 * {@code AccountAdminController} documenta al explicar por que no existe ninguna busqueda por
 * email. Se acepta el 404 porque la alternativa —una respuesta uniforme— dejaria al
 * administrador sin saber si el alta ocurrio, sobre la unica via que la etapa tiene para crear
 * colaboradores.
 *
 * <p>Las dos mitigaciones <b>no son opcionales</b>:
 * <ul>
 *   <li><b>Rate limit</b> propio sobre la ruta, en {@code RateLimitFilter}: acota el barrido a
 *       diez intentos por minuto y por IP.</li>
 *   <li><b>Auditoria de cada intento fallido</b>, aca: un barrido de la base deja una racha de
 *       {@code MEMBERSHIP_ALTA_RECHAZADA} del mismo actor, imposible de disimular en la
 *       auditoria del tenant, que ademas es consultable por RF-M24-003.</li>
 * </ul>
 *
 * <p>Notar que esa auditoria <b>no construye ningun padron</b>: solo registra direcciones que
 * NO tienen cuenta. Las que si la tienen salen por el camino de exito, donde el evento apunta a
 * la membership creada y no al texto tipeado.
 *
 * <h2>Por que el evento del intento fallido va en su propia transaccion</h2>
 *
 * <p>La regla heredada es escribir la auditoria dentro de la transaccion del negocio, y por eso
 * el alta exitosa se audita donde ocurre —dentro de {@code createDirect}—. El intento fallido no
 * tiene transaccion de negocio: no muta nada y termina en una excepcion. Escribirlo en la misma
 * transaccion que despues lanza el 404 lo borraria en el rollback, que es justo el rastro que la
 * decision exige conservar. Por eso se confirma en una transaccion propia
 * ({@code REQUIRES_NEW}) antes de lanzar.
 */
@Service
public class DirectMembershipService {

	private static final Logger log = LoggerFactory.getLogger(DirectMembershipService.class);

	/**
	 * Permiso de la matriz que habilita administrar colaboradores.
	 *
	 * <p>Viaja como texto y no como el enum de {@code organization.domain}: ese paquete es
	 * privado de su modulo y ArchUnit rechaza importarlo.
	 */
	private static final String PERMISO_GESTION_DE_COLABORADORES = "colaborador:manage";

	private final CuentaRepositoryPort cuentaRepository;
	private final MembershipProvisioning membershipProvisioning;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;
	private final TransactionTemplate auditoriaDelIntento;

	public DirectMembershipService(
			CuentaRepositoryPort cuentaRepository,
			MembershipProvisioning membershipProvisioning,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			PlatformTransactionManager transactionManager) {

		this.cuentaRepository = cuentaRepository;
		this.membershipProvisioning = membershipProvisioning;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.auditoriaDelIntento = auditoriaDelIntento(transactionManager);
	}

	/**
	 * Vincula una cuenta existente con la organizacion del contexto del actor.
	 *
	 * <p><b>Sin {@code @Transactional} a proposito.</b> El unico paso que muta el negocio es
	 * {@code createDirect}, que abre y cierra la suya con el orden de bloqueo del sistema; y el
	 * evento del intento fallido tiene que sobrevivir a la excepcion que lo sigue. Una
	 * transaccion que abarcara el metodo entero romperia las dos cosas.
	 *
	 * @param actor         quien opera, con la organizacion de su contexto ya validado
	 * @param email         direccion tipeada por el administrador
	 * @param consultorioId sede del vinculo, o {@code null} para alcance organizacion
	 * @param roleCode      rol de la matriz
	 * @param reason        motivo declarado. Obligatorio
	 * @return el id de la membership creada
	 * @throws AccessDeniedException si el actor opera sin contexto de organizacion (403)
	 * @throws EmailSinCuentaException si ninguna cuenta tiene ese email (404)
	 */
	public long vincular(
			Actor actor, String email, Long consultorioId, String roleCode, String reason) {

		Long organizationId = actor.organizationId();
		if (organizationId == null) {
			// Un PLATFORM_ADMIN llega hasta aca sin contexto: TenantContextFilter lo deja pasar
			// a proposito. El alta necesita un tenant, y elegirlo por el seria inventarlo.
			throw new AccessDeniedException(
					"El alta de un colaborador requiere un contexto de organizacion activo");
		}

		Instant ahora = Instant.now();

		// PASO 1 - Permiso, antes que nada. La sede que se evalua es la del VINCULO pedido: quien
		// administra la sede A no puede dar de alta en la sede B. El tipo de la excepcion que
		// esto puede lanzar no se nombra: ArchUnit prohibe que identity importe
		// organization.domain, y una excepcion que se deja propagar no genera un import.
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PERMISO_GESTION_DE_COLABORADORES,
				organizationId,
				consultorioId,
				null,
				ahora));

		// PASO 2 - Recien ahora el email. Antes del paso 1 seria un oraculo abierto.
		String emailNormalizado = EmailNormalizado.of(email);
		Optional<Cuenta> cuenta = cuentaRepository.findByEmailNormalizado(emailNormalizado);
		if (cuenta.isEmpty()) {
			auditarIntentoFallido(
					actor, organizationId, consultorioId, emailNormalizado, roleCode, reason, ahora);
			throw new EmailSinCuentaException(emailNormalizado);
		}

		// PASO 3 - El vinculo. Autoriza de nuevo bajo el bloqueo del tenant, valida invariantes y
		// audita MEMBERSHIP_CREATED dentro de su propia transaccion.
		return membershipProvisioning.createDirect(
				actor.accountId(),
				actor.platformAdmin(),
				organizationId,
				new DirectMembershipCommand(cuenta.get().getId(), consultorioId, roleCode, reason));
	}

	/**
	 * Deja el rastro del intento sobre un email sin cuenta.
	 *
	 * <p>Se confirma en su propia transaccion porque el llamador lanza inmediatamente despues:
	 * dentro de la transaccion del request, el rollback del 404 se llevaria puesto justamente el
	 * rastro que esta decision exige conservar.
	 *
	 * <p>El email va en {@code details} y eso es deliberado: sin la direccion tipeada, una racha
	 * de estos eventos dice "alguien probo cien veces" y no "alguien barrio la base". No hay
	 * fuga: por construccion, aca solo entran direcciones que NO tienen cuenta.
	 */
	private void auditarIntentoFallido(
			Actor actor,
			long organizationId,
			Long consultorioId,
			String emailNormalizado,
			String roleCode,
			String reason,
			Instant ahora) {

		Map<String, String> details = new LinkedHashMap<>();
		details.put("email", emailNormalizado);
		details.put("roleCode", String.valueOf(roleCode));
		details.put("consultorioId", String.valueOf(consultorioId));
		details.put("motivoDelRechazo", "EMAIL_SIN_CUENTA");

		auditoriaDelIntento.executeWithoutResult(estado ->
				IdentityAuditEvents.registrarIntentoDeAlta(
						auditTrail, organizationId, consultorioId, actor.accountId(),
						details, reason, ahora));

		// La direccion NO se loguea: el log estructurado no tiene la retencion ni el control de
		// acceso de la auditoria del tenant, y el evento ya la lleva.
		log.info("Alta directa rechazada: organizationId={} actorAccountId={} motivo=EMAIL_SIN_CUENTA",
				organizationId, actor.accountId());
	}

	/**
	 * La transaccion del rastro: propia y siempre nueva.
	 *
	 * <p>{@code REQUIRES_NEW} y no {@code REQUIRED} aunque hoy no haya ninguna transaccion
	 * abierta cuando se lo invoca. Es la unica forma de que la garantia siga en pie si alguien
	 * envuelve este servicio en un {@code @Transactional} mas adelante: con {@code REQUIRED} el
	 * evento se uniria a esa transaccion y el 404 lo borraria en el rollback, en silencio.
	 */
	private static TransactionTemplate auditoriaDelIntento(
			PlatformTransactionManager transactionManager) {

		TransactionTemplate plantilla = new TransactionTemplate(transactionManager);
		plantilla.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return plantilla;
	}

	/**
	 * Quien ejecuta el alta.
	 *
	 * <p>Primitivos y no un principal: {@code application} no puede conocer HTTP ni la forma del
	 * token. Mismo criterio que {@code AccountAdminService.Actor}.
	 *
	 * @param accountId      cuenta autenticada que opera
	 * @param organizationId organizacion del contexto YA VALIDADO del request, o {@code null}.
	 *                       Nunca un id que mando el cliente
	 * @param platformAdmin  administra la plataforma, por encima de cualquier tenant
	 */
	public record Actor(long accountId, Long organizationId, boolean platformAdmin) {
	}
}
