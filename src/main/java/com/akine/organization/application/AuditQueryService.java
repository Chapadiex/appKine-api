package com.akine.organization.application;

import com.akine.organization.domain.PermissionCode;
import com.akine.organization.domain.PermissionScope;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEventFilter;
import com.akine.platform.spi.audit.AuditEventSummary;
import com.akine.platform.spi.audit.AuditQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Consulta autorizada de la auditoria (RF-M24-002, RF-M24-003, RF-M24-004).
 *
 * <h2>Por que la autorizacion vive aca y la consulta en {@code platform}</h2>
 *
 * <p>{@code audit_event} es de {@code platform}, y la matriz de permisos es de
 * {@code organization}. {@code platform} es el modulo base y no puede depender de ninguno
 * funcional, asi que no puede evaluar {@code auditoria:read}. La unica division que no cierra un
 * ciclo es esta: {@code organization} decide y delega la lectura por el puerto
 * {@link AuditQuery}.
 *
 * <h2>El {@code organizationId} nunca viene del cliente</h2>
 *
 * <p>Sale del contexto ya revalidado del request, que es lo que el llamador pasa. Si viniera de
 * la URL, cambiar un numero seria leer la auditoria de otro tenant — y la auditoria es
 * exactamente la tabla donde eso duele mas, porque contiene el mapa de lo que hace un cliente.
 *
 * <h2>El alcance por rol, y la decision que sigue abierta</h2>
 *
 * <p>Un {@code ORG_ADMIN} lee la organizacion entera; un {@code CONSULTORIO_ADMIN} lee
 * <b>su sede</b>, y eso se materializa filtrando por {@code consultorio_id} (matriz §6:
 * "Consultorio").
 *
 * <p><b>Consecuencia incomoda que hay que saber, y que no se tapo:</b> casi todos los eventos
 * que el sistema escribe hoy llevan {@code consultorio_id} nulo, porque las operaciones de
 * M01/M02 son de alcance organizacion. Con el filtro estricto, un {@code CONSULTORIO_ADMIN} abre
 * la pantalla de auditoria y <b>no ve nada</b>. Es correcto en minimo privilegio y desconcertante
 * en producto. La alternativa —mostrarle tambien los de alcance organizacion— le expondria
 * transiciones de suscripcion y datos del tenant que no le competen. <b>Es la decision D-7 del
 * diseño y sigue abierta</b>: se implemento la version estricta porque es la unica que no
 * concede de mas, y la otra siempre se puede agregar; al reves no.
 *
 * <h2>Los limites</h2>
 *
 * <p>Sin tope, un {@code from=1970} sobre un tenant grande es un scan y un problema de
 * disponibilidad, no de permisos. Los valores de {@link #RANGO_MAXIMO} y
 * {@link #TAMANIO_MAXIMO_DE_PAGINA} son <b>defaults provisorios</b>: la decision D-8 del diseño
 * no los fijo, y quedan aca —en un solo lugar y con nombre— para que fijarlos sea cambiar dos
 * constantes y no auditar la aplicacion entera.
 */
@Service
public class AuditQueryService {

	/** Ventana maxima por consulta. Default provisorio (D-8 abierta). */
	public static final Duration RANGO_MAXIMO = Duration.ofDays(90);

	/** Tamaño maximo de pagina. Default provisorio (D-8 abierta). */
	public static final int TAMANIO_MAXIMO_DE_PAGINA = 100;

	private final PermissionGuard permissionGuard;
	private final AuditQuery auditQuery;
	private final SupportAccessReadAuditor supportAccessReadAuditor;

	public AuditQueryService(
			PermissionGuard permissionGuard,
			AuditQuery auditQuery,
			SupportAccessReadAuditor supportAccessReadAuditor) {
		this.permissionGuard = permissionGuard;
		this.auditQuery = auditQuery;
		this.supportAccessReadAuditor = supportAccessReadAuditor;
	}

	/**
	 * Historial de una entidad (RF-M24-002).
	 *
	 * @throws com.akine.organization.domain.exception.PermissionDeniedException si falta
	 *         {@code auditoria:read} (403)
	 * @throws com.akine.organization.domain.exception.OrganizationNotFoundException si el tenant
	 *         no esta en el alcance del actor (404)
	 */
	@Transactional(readOnly = true)
	public Page<AuditEventSummary> porEntidad(
			OperatingActor actor,
			long organizationId,
			String entityType,
			long entityId,
			Pageable pageable) {

		Long sede = alcanceDeLectura(actor, organizationId);
		return auditQuery.porEntidad(
				new AuditEventFilter(organizationId, sede, entityType, entityId, null, null, null),
				acotar(pageable));
	}

	/** Actividad de un actor dentro del tenant (RF-M24-003). */
	@Transactional(readOnly = true)
	public Page<AuditEventSummary> porActor(
			OperatingActor actor, long organizationId, long actorAccountId, Pageable pageable) {

		Long sede = alcanceDeLectura(actor, organizationId);
		return auditQuery.porActor(
				new AuditEventFilter(organizationId, sede, null, null, actorAccountId, null, null),
				acotar(pageable));
	}

	/**
	 * Ventana temporal (RF-M24-004).
	 *
	 * @throws IllegalArgumentException si el rango esta invertido o excede {@link #RANGO_MAXIMO}
	 */
	@Transactional(readOnly = true)
	public Page<AuditEventSummary> porPeriodo(
			OperatingActor actor,
			long organizationId,
			Instant desde,
			Instant hasta,
			Pageable pageable) {

		validarRango(desde, hasta);
		Long sede = alcanceDeLectura(actor, organizationId);
		return auditQuery.porPeriodo(
				new AuditEventFilter(organizationId, sede, null, null, null, desde, hasta),
				acotar(pageable));
	}

	/**
	 * Exige {@code auditoria:read} y devuelve la sede a la que hay que acotar la lectura.
	 *
	 * <p>{@code null} significa "toda la organizacion" y solo lo obtiene quien tiene el permiso
	 * con alcance de organizacion o global. Quien lo tiene con alcance de sede lee su sede y solo
	 * su sede: el alcance del permiso y el filtro de la consulta son <b>el mismo dato</b>, y por
	 * eso sale de la decision del evaluador en vez de recalcularse aca. Recalcularlo seria abrir
	 * la puerta a que las dos versiones divergieran.
	 *
	 * <h2>Y ademas registra el uso de soporte, que antes se descartaba</h2>
	 *
	 * <p>Este metodo miraba unicamente {@code grantedByScope} y tiraba {@code viaSupportAccess}
	 * a la basura. En la tabla de auditoria eso es peor que en cualquier otro lado: alguien
	 * podia leer el rastro entero de un tenant —el mapa de lo que hace un cliente— sin dejar
	 * rastro de haberlo leido. La matriz §7 pide auditar CADA operacion amparada por soporte, y
	 * la consulta de la auditoria no es una excepcion; si acaso, es el caso central.
	 *
	 * <p>Va por {@link SupportAccessReadAuditor} y no por el {@code AuditTrail} directo porque
	 * las tres consultas son {@code readOnly = true}, donde el flush de Hibernate queda en
	 * MANUAL y la fila no llegaria nunca a la base — y ademas {@code porPeriodo} valida el rango
	 * y puede lanzar despues, con lo que el rollback se la llevaria igual.
	 *
	 * <p><b>Se registra en la evaluacion y no en cada uno de los tres metodos</b>: son tres
	 * puertas al mismo dato con el mismo permiso, y ponerlo en cada una es la forma de que la
	 * cuarta se olvide.
	 */
	private Long alcanceDeLectura(OperatingActor actor, long organizationId) {
		Instant ahora = Instant.now();
		PermissionDecision decision = permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCode.AUDITORIA_READ.code(),
				organizationId,
				actor.consultorioId(),
				null,
				ahora));

		if (decision.viaSupportAccess()) {
			supportAccessReadAuditor.record(AuditEvents.usoDeSoporte(
					organizationId, actor.consultorioId(), actor.accountId(),
					PermissionCode.AUDITORIA_READ.code(), null, ahora));
		}

		boolean deSede = PermissionScope.CONSULTORIO.name().equals(decision.grantedByScope());
		return deSede ? actor.consultorioId() : null;
	}

	private static void validarRango(Instant desde, Instant hasta) {
		if (desde == null || hasta == null || !hasta.isAfter(desde)) {
			throw new IllegalArgumentException(
					"El periodo consultado exige un inicio y un fin, y el fin es posterior");
		}
		if (Duration.between(desde, hasta).compareTo(RANGO_MAXIMO) > 0) {
			throw new IllegalArgumentException(
					"El periodo consultado no puede exceder " + RANGO_MAXIMO.toDays() + " dias");
		}
	}

	/**
	 * Recorta el tamaño de pagina pedido.
	 *
	 * <p>Se recorta en vez de rechazar: un cliente que pide 10.000 filas no esta atacando, esta
	 * mal configurado, y devolverle 100 le sirve mas que un 400. El tope si es innegociable.
	 */
	private static Pageable acotar(Pageable pageable) {
		if (pageable.isUnpaged()) {
			return PageRequest.of(0, TAMANIO_MAXIMO_DE_PAGINA);
		}
		if (pageable.getPageSize() <= TAMANIO_MAXIMO_DE_PAGINA) {
			return pageable;
		}
		return PageRequest.of(pageable.getPageNumber(), TAMANIO_MAXIMO_DE_PAGINA, pageable.getSort());
	}
}
