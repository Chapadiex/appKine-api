package com.akine.organization.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Asignacion BASE de permisos por rol: la tabla §6 de la matriz aprobada, hecha codigo.
 *
 * <h2>Por que vive en codigo y no en una tabla</h2>
 *
 * <p>La asignacion base <b>es la especificacion</b> (§32). Cambiarla tiene que ser un diff
 * revisable contra {@code docs/seguridad/matriz-permisos-minima.md}, no un {@code UPDATE}. Una
 * tabla de asignacion base invita a que alguien se otorgue {@code tenant:manage} con SQL, sin
 * autor, sin motivo, sin auditoria y sin revision.
 *
 * <p>Los <b>grants</b> si son datos ({@code membership_grant}), porque son decisiones
 * operativas de cada organizacion y por eso tienen {@code granted_by}, {@code reason},
 * vigencia y auditoria. La diferencia no es de comodidad: es de quien decide.
 *
 * <p>Un test de arquitectura compara esta tabla fila por fila contra la §6 de la matriz usada
 * como fixture. Si alguien toca el mapa sin tocar la matriz —o al reves— el build falla.
 *
 * <h2>Como se lee</h2>
 *
 * <p>Cada celda de la matriz §6 se traduce al {@link PermissionScope} que le corresponde segun
 * la semantica de la §3:
 * <ul>
 *   <li>"Global" &rarr; {@link PermissionScope#GLOBAL}</li>
 *   <li>"Org" &rarr; {@link PermissionScope#ORGANIZACION}</li>
 *   <li>"Consultorio" &rarr; {@link PermissionScope#CONSULTORIO}</li>
 *   <li>"Restringido (grant + soporte)" &rarr; {@link PermissionScope#RESTRINGIDO}</li>
 *   <li>"No por defecto (grant)" &rarr; <b>ausente</b>: no es base, se otorga por
 *       {@code membership_grant}. Ponerlo aca con algun alcance seria convertir en implicito
 *       justo lo que la matriz define como explicito</li>
 *   <li>"—" &rarr; ausente: denegado</li>
 * </ul>
 *
 * <p><b>Lo que no esta, deniega.</b> No hay valor por defecto ni fallback: un permiso que no
 * figura en la fila de un rol es un permiso que ese rol no tiene. Los codigos de las fases F3 a
 * F8 estan declarados en {@link PermissionCode} y no aparecen en ninguna fila de aca, asi que
 * deniegan siempre — es exactamente lo que se quiere hasta que su modulo exista.
 */
public final class RolePermissions {

	private static final Map<RoleCode, Map<PermissionCode, PermissionScope>> BASE = base();

	private RolePermissions() {
		// Tabla estatica sin estado.
	}

	/**
	 * Alcance con el que un rol tiene un permiso de forma implicita, si lo tiene.
	 *
	 * @return vacio cuando el rol no tiene ese permiso en la asignacion base. Vacio significa
	 *         DENEGADO por base; puede seguir otorgandose por {@code membership_grant}
	 */
	public static Optional<PermissionScope> baseScope(RoleCode role, PermissionCode permission) {
		if (role == null || permission == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(BASE.getOrDefault(role, Map.of()).get(permission));
	}

	/** Permisos que un rol tiene de forma implicita, con su alcance. Nunca {@code null}. */
	public static Map<PermissionCode, PermissionScope> baseOf(RoleCode role) {
		return BASE.getOrDefault(role, Map.of());
	}

	/** Permisos que pueden otorgarse como grant adicional a una membership. */
	public static Set<PermissionCode> otorgablesComoGrant() {
		// El codigo valida contra este conjunto y rechaza cualquier otro con 400, en vez de
		// escribir una fila invalida que nadie va a poder explicar despues.
		//
		// `auditoria:read-clinica` es el "No por defecto (grant)" de la matriz §6, y fue el unico
		// hasta AKINE-03.01. `paciente:manage` entra ahi porque la matriz §4 le dice al
		// PROFESIONAL "Segun permiso" en la fila "Gestionar paciente": no lo tiene por base —no
		// esta en su fila de BASE— y se le concede por grant explicito. Sin esta linea esa celda
		// de la matriz no tendria ninguna forma de cumplirse: el grant se rechazaria con 400.
		return Set.of(PermissionCode.AUDITORIA_READ_CLINICA, PermissionCode.PACIENTE_MANAGE);
	}

	private static Map<RoleCode, Map<PermissionCode, PermissionScope>> base() {
		Map<RoleCode, Map<PermissionCode, PermissionScope>> tabla = new EnumMap<>(RoleCode.class);

		// PLATFORM_ADMIN — el rol mas alto del sistema es el que tiene el acceso MAS restringido
		// a los datos de un tenant (matriz §1.3), no el mas amplio. Que esto parezca al reves es
		// la intencion.
		//
		// LA CONTRADICCION DE LA MATRIZ, RESUELTA POR EL LADO QUE FALLA CERRADO. La §6 le pone
		// "Global" a toda la columna salvo la auditoria clinica; la §7 le pone el invariante
		// contrario: "PLATFORM_ADMIN accede a datos de un tenant SOLO por acceso de soporte
		// justificado y auditado" (§32, DP-03). Los dos parrafos son vinculantes y no coinciden.
		// Con "Global" en las lecturas, el acceso de soporte era decorativo: nada lo exigia y su
		// unico efecto era encender un flag que el llamador podia ignorar —y cuatro servicios lo
		// ignoraban—.
		//
		//   - tenant:read y auditoria:read pasan a SOPORTE. Sin un support_access vigente, un
		//     administrador de plataforma no lee el perfil del tenant, sus sedes, su suscripcion
		//     ni su auditoria; con soporte, la decision vuelve con viaSupportAccess = true y el
		//     llamador deja la fila SUPPORT_ACCESS_USED. Las dos mitades del invariante
		//     —justificado y auditado— dejan de ser opcionales.
		//   - tenant:manage sigue GLOBAL, y es deliberado: es el CONTRATO del tenant —alta,
		//     cambio de plan, suspension—, no sus datos. Es la capacidad de intervenir en un
		//     incidente, ya deja su propia fila de auditoria nominal, y exigirle soporte dejaria
		//     a la plataforma sin poder suspender un tenant abusivo.
		//
		// La linea que separa GLOBAL de SOPORTE aca es una sola pregunta: ¿la operacion deja por
		// si misma una fila que diga quien la hizo y por que?
		//
		// Las MUTACIONES la dejan —`SUBSCRIPTION_TRANSITIONED`, `MEMBERSHIP_*`,
		// `CONSULTORIO_*`, todas con actor y motivo obligatorio—, asi que el hecho queda trazado
		// aunque no se registre ademas como uso de soporte. Las LECTURAS no dejan nada: sin
		// `SUPPORT_ACCESS_USED` no hay ninguna evidencia de que ocurrieron.
		//
		// Por eso `colaborador:read` es SOPORTE desde el 24/08/2026 (decision del usuario) y
		// `tenant:read` y `auditoria:read` ya lo eran: las tres son lectura de datos de un
		// tenant ajeno, que es literalmente lo que la matriz §7 protege. Leer el padron de
		// personas de cualquier centro sin dejar rastro era el hueco mas grande que quedaba.
		//
		// `consultorio:manage` y `colaborador:manage` quedan GLOBAL a proposito: son mutaciones,
		// dejan su propia fila nominal, y exigirles soporte sumaria un paso a operaciones de
		// rescate sin agregar trazabilidad que no exista ya. Escrito en la matriz §9.7.
		// `espacio:read` entra con SOPORTE por la misma pregunta: leer el catalogo fisico de un
		// centro ajeno no deja por si mismo ninguna fila que diga quien lo hizo ni por que.
		// Aprobado el 25/08/2026, matriz §5, §6 y §10.1.
		tabla.put(RoleCode.PLATFORM_ADMIN, Map.of(
				PermissionCode.TENANT_MANAGE, PermissionScope.GLOBAL,
				PermissionCode.TENANT_READ, PermissionScope.SOPORTE,
				PermissionCode.CONSULTORIO_MANAGE, PermissionScope.GLOBAL,
				PermissionCode.ESPACIO_READ, PermissionScope.SOPORTE,
				PermissionCode.COLABORADOR_MANAGE, PermissionScope.GLOBAL,
				PermissionCode.COLABORADOR_READ, PermissionScope.SOPORTE,
				PermissionCode.AUDITORIA_READ, PermissionScope.SOPORTE,
				PermissionCode.AUDITORIA_READ_CLINICA, PermissionScope.RESTRINGIDO,
				// `paciente:manage` entra con SOPORTE en AKINE-03.01: la matriz §4 le da
				// literalmente "Soporte" a esta columna en la fila "Gestionar paciente". Es una
				// MUTACION y las mutaciones de esta tabla suelen quedar GLOBAL porque dejan su
				// propia fila nominal; aca no, y la diferencia es el dato: el padron de personas
				// es exactamente lo que §7 protege, y la matriz ya lo habia decidido asi. El
				// llamador deja `SUPPORT_ACCESS_USED` cuando la decision vuelve con
				// viaSupportAccess — ver `person.application.PersonaService`.
				PermissionCode.PACIENTE_MANAGE, PermissionScope.SOPORTE));

		// ORG_ADMIN — "tenant:manage" NO esta: la matriz §4 acota su "Limitado" a editar su
		// organizacion y ver su suscripcion, y deja el cambio de plan y la suspension para
		// PLATFORM_ADMIN. auditoria:read-clinica tampoco: es "No por defecto (grant)".
		tabla.put(RoleCode.ORG_ADMIN, Map.of(
				PermissionCode.TENANT_READ, PermissionScope.ORGANIZACION,
				PermissionCode.CONSULTORIO_MANAGE, PermissionScope.ORGANIZACION,
				PermissionCode.ESPACIO_READ, PermissionScope.ORGANIZACION,
				PermissionCode.COLABORADOR_MANAGE, PermissionScope.ORGANIZACION,
				PermissionCode.COLABORADOR_READ, PermissionScope.ORGANIZACION,
				PermissionCode.AUDITORIA_READ, PermissionScope.ORGANIZACION,
				PermissionCode.PACIENTE_MANAGE, PermissionScope.ORGANIZACION));

		// CONSULTORIO_ADMIN — todo acotado a SU sede. Sin tenant:read: la matriz no se lo da.
		tabla.put(RoleCode.CONSULTORIO_ADMIN, Map.of(
				PermissionCode.CONSULTORIO_MANAGE, PermissionScope.CONSULTORIO,
				PermissionCode.ESPACIO_READ, PermissionScope.CONSULTORIO,
				PermissionCode.COLABORADOR_MANAGE, PermissionScope.CONSULTORIO,
				PermissionCode.COLABORADOR_READ, PermissionScope.CONSULTORIO,
				PermissionCode.AUDITORIA_READ, PermissionScope.CONSULTORIO,
				PermissionCode.PACIENTE_MANAGE, PermissionScope.CONSULTORIO));

		// PROFESIONAL y ADMINISTRATIVO — ven la lista de colaboradores de su sede y, desde la
		// aprobacion del 25/08/2026, el catalogo fisico de esa misma sede: sin `espacio:read` un
		// profesional no puede saber en que box atiende. Todo lo clinico y economico de sus
		// columnas sigue siendo de F4 en adelante.
		//
		// `paciente:manage` los separa, y es la unica fila de la matriz §4 donde estos dos roles
		// difieren: al ADMINISTRATIVO le dice "Si" —dar de alta y editar fichas es literalmente
		// su trabajo— y al PROFESIONAL le dice "Segun permiso", o sea NO por defecto y si por
		// grant explicito. Por eso el profesional no lo tiene aca y si figura en
		// `otorgablesComoGrant()`, que hasta AKINE-03.01 tenia un solo elemento.
		tabla.put(RoleCode.PROFESIONAL, Map.of(
				PermissionCode.COLABORADOR_READ, PermissionScope.CONSULTORIO,
				PermissionCode.ESPACIO_READ, PermissionScope.CONSULTORIO));
		tabla.put(RoleCode.ADMINISTRATIVO, Map.of(
				PermissionCode.COLABORADOR_READ, PermissionScope.CONSULTORIO,
				PermissionCode.ESPACIO_READ, PermissionScope.CONSULTORIO,
				PermissionCode.PACIENTE_MANAGE, PermissionScope.CONSULTORIO));

		// PACIENTE — ninguna fila de la matriz §6 le da nada en F1. Sus celdas ("Propio",
		// "Propia autorizada") viven en acciones de F3 y F4.
		tabla.put(RoleCode.PACIENTE, Map.of());

		// Se copia a EnumMap para que el acceso sea por indice de ordinal y no por hash: esta
		// tabla se consulta en cada evaluacion de permiso, que es el camino mas caliente del
		// sistema despues de la resolucion de contexto.
		//
		// El mapa vacio se maneja aparte y no es un detalle: `new EnumMap<>(unMapaVacio)` lanza
		// IllegalArgumentException porque no puede deducir el tipo del enum sin ninguna clave.
		// PACIENTE no tiene ninguna fila en la matriz §6, asi que su mapa esta vacio, y sin esta
		// rama la clase entera falla al inicializarse — un ExceptionInInitializerError que se
		// manifiesta como "no se pudo crear el bean" a tres niveles de distancia de la causa.
		tabla.replaceAll((rol, permisos) -> permisos.isEmpty()
				? Map.of()
				: Collections.unmodifiableMap(new EnumMap<>(permisos)));
		return Collections.unmodifiableMap(tabla);
	}
}
