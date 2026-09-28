package com.akine.resource.application;

import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.resource.domain.CatalogoAlcance;
import com.akine.resource.domain.CatalogoAlcanceFiltro;
import com.akine.resource.domain.PermissionCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Quien ve y quien muta un catalogo <b>global + por tenant</b>, en un solo lugar.
 *
 * <p>Es la politica que AKINE-02.05 fijo para M06, extraida de {@code CatalogoService} para que
 * {@code MedicionDefinicionService} —el sexto catalogo del modulo— no la vuelva a escribir. Una
 * segunda copia de estas reglas es una segunda definicion de "que puede ver este tenant", y la
 * primera en divergir seria la que decide si un centro lee el catalogo propio de otro.
 *
 * <p><b>{@code CatalogoService} no se migro a esta clase.</b> Aquella tiene 2.086 pruebas encima y
 * la etapa no puede correr integracion para respaldar el cambio; reescribir su autorizacion para
 * ahorrar duplicacion seria pagar un riesgo real por una ganancia estetica. Queda declarado: si
 * alguna vez hay que tocar la politica, se toca en los dos lados o se migra aquella clase aqui.
 *
 * <h2>La regla, en tres filas</h2>
 *
 * <pre>
 *   QUIEN LO VE      global: todos los tenants        contextual: solo su duenio
 *   QUIEN LO MUTA    global: PLATFORM_ADMIN           contextual: consultorio:manage
 *   DE QUE DEPENDE   global: solo de otros globales   contextual: de globales o propios
 * </pre>
 *
 * <p>Y lo que deliberadamente <b>no</b> se concede: un {@code PLATFORM_ADMIN} no ve ni muta
 * conceptos contextuales de un tenant. El catalogo propio de un centro es informacion comercial
 * suya, no hay ninguna operacion de rescate que exija tocarlo, y concederlo obligaria ademas a
 * exigir {@code support_access} y a auditar cada lectura.
 *
 * <h2>{@code owner_key}, y por que las lecturas no filtran por {@code organizationId}</h2>
 *
 * <p>La lista de duenios visibles se expresa sobre la columna generada {@code owner_key} —el id
 * del tenant, o el centinela {@code 0} para lo global—. Filtrar por {@code organization_id}
 * dejaria fuera todo el catalogo de plataforma, y usar {@code IS NULL OR = :org} no sirve en un
 * unique porque varios {@code NULL} no colisionan en MySQL (ADR-0021). Es el error que 02.05
 * pago.
 *
 * <p>La lista la arma <b>el servidor</b> despues de validar el contexto, nunca el cliente: un id
 * de otro tenant no aparece en ella y por lo tanto no resuelve nunca.
 */
@Component
public class AlcanceDelCatalogo {

	/**
	 * Centinela del duenio "plataforma" en {@code owner_key}.
	 *
	 * <p>0 no es ni puede ser el id de ninguna organizacion: {@code organization.id} es
	 * AUTO_INCREMENT y arranca en 1.
	 */
	public static final long OWNER_PLATAFORMA = 0L;

	/** Duenio que no existe. Hace que una consulta imposible devuelva vacio sin romper el IN. */
	private static final long OWNER_NINGUNO = -1L;

	private static final Logger log = LoggerFactory.getLogger(AlcanceDelCatalogo.class);

	private final PermissionGuard permissionGuard;

	public AlcanceDelCatalogo(PermissionGuard permissionGuard) {
		this.permissionGuard = permissionGuard;
	}

	/** Los duenios cuyos conceptos ve el actor: lo global, mas lo propio si tiene contexto. */
	public List<Long> ownersVisibles(OperatingActor actor) {
		if (actor.platformAdmin()) {
			// Ve el catalogo comun y NADA de ningun tenant. Ver el javadoc de la clase.
			return List.of(OWNER_PLATAFORMA);
		}
		return List.of(OWNER_PLATAFORMA, exigirContexto(actor));
	}

	/** Los duenios visibles, recortados por el filtro de alcance que pidio el cliente. */
	public List<Long> ownersSegunFiltro(OperatingActor actor, CatalogoAlcanceFiltro filtro) {
		List<Long> visibles = ownersVisibles(actor);
		return switch (filtro) {
			case TODOS -> visibles;
			case GLOBAL -> List.of(OWNER_PLATAFORMA);
			// Para un administrador de plataforma esto es la lista vacia, y una lista vacia en un
			// IN es un error de sintaxis: se usa un duenio imposible para que devuelva cero filas.
			case ORGANIZACION -> visibles.size() > 1
					? List.of(visibles.get(1))
					: List.of(OWNER_NINGUNO);
		};
	}

	/**
	 * Autoriza crear o mutar un concepto con ese alcance y devuelve su duenio.
	 *
	 * @return {@code null} para un concepto global, el id del tenant para uno contextual
	 * @throws AccessDeniedException 403 y jamas 401: el interceptor del frontend borra el token
	 *                               ante cualquier 401 y deja al usuario en un bucle de login
	 */
	public Long exigirGestionDe(OperatingActor actor, CatalogoAlcance alcance) {
		if (alcance == CatalogoAlcance.GLOBAL) {
			if (!actor.platformAdmin()) {
				log.info("Mutacion del catalogo global rechazada: accountId={}", actor.accountId());
				throw new AccessDeniedException(
						"El catalogo global lo administra unicamente la plataforma");
			}
			return null;
		}
		if (actor.platformAdmin()) {
			throw new AccessDeniedException(
					"La administracion de plataforma no crea conceptos de un tenant");
		}
		long organizationId = exigirContexto(actor);
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.CONSULTORIO_MANAGE,
				organizationId,
				actor.consultorioId(),
				null,
				Instant.now()));
		return organizationId;
	}

	/** La misma autorizacion, cuando el concepto ya existe y su duenio decide quien lo toca. */
	public void exigirGestionSobre(OperatingActor actor, Long organizationIdDelConcepto) {
		exigirGestionDe(actor, CatalogoAlcance.de(organizationIdDelConcepto));
	}

	/** Exige un contexto de trabajo elegido. <b>403 y jamas 401.</b> */
	private static long exigirContexto(OperatingActor actor) {
		Long organizationId = actor.contextOrganizationId();
		if (organizationId == null) {
			log.info("Operacion de catalogo sin contexto validado: accountId={}",
					actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		return organizationId;
	}
}
