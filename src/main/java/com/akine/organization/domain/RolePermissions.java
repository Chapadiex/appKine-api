package com.akine.organization.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Asignacion BASE de permisos por rol: la tabla Â§6 de la matriz aprobada, hecha codigo.
 *
 * <h2>Por que vive en codigo y no en una tabla</h2>
 *
 * <p>La asignacion base <b>es la especificacion</b> (Â§32). Cambiarla tiene que ser un diff
 * revisable contra {@code docs/seguridad/matriz-permisos-minima.md}, no un {@code UPDATE}. Una
 * tabla de asignacion base invita a que alguien se otorgue {@code tenant:manage} con SQL, sin
 * autor, sin motivo, sin auditoria y sin revision.
 *
 * <p>Los <b>grants</b> si son datos ({@code membership_grant}), porque son decisiones
 * operativas de cada organizacion y por eso tienen {@code granted_by}, {@code reason},
 * vigencia y auditoria. La diferencia no es de comodidad: es de quien decide.
 *
 * <p>Un test de arquitectura compara esta tabla fila por fila contra la Â§6 de la matriz usada
 * como fixture. Si alguien toca el mapa sin tocar la matriz âo al revesâ el build falla.
 *
 * <h2>Como se lee</h2>
 *
 * <p>Cada celda de la matriz Â§6 se traduce al {@link PermissionScope} que le corresponde segun
 * la semantica de la Â§3:
 * <ul>
 *   <li>"Global" &rarr; {@link PermissionScope#GLOBAL}</li>
 *   <li>"Org" &rarr; {@link PermissionScope#ORGANIZACION}</li>
 *   <li>"Consultorio" &rarr; {@link PermissionScope#CONSULTORIO}</li>
 *   <li>"Restringido (grant + soporte)" &rarr; {@link PermissionScope#RESTRINGIDO}</li>
 *   <li>"No por defecto (grant)" &rarr; <b>ausente</b>: no es base, se otorga por
 *       {@code membership_grant}. Ponerlo aca con algun alcance seria convertir en implicito
 *       justo lo que la matriz define como explicito</li>
 *   <li>"â" &rarr; ausente: denegado</li>
 * </ul>
 *
 * <p><b>Lo que no esta, deniega.</b> No hay valor por defecto ni fallback: un permiso que no
 * figura en la fila de un rol es un permiso que ese rol no tiene. Los codigos de las fases F3 a
 * F8 estan declarados en {@link PermissionCode} y no aparecen en ninguna fila de aca, asi que
 * deniegan siempre â es exactamente lo que se quiere hasta que su modulo exista.
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
		// `auditoria:read-clinica` es el "No por defecto (grant)" de la matriz Â§6, y fue el unico
		// hasta AKINE-03.01. `paciente:manage` entra ahi porque la matriz Â§4 le dice al
		// PROFESIONAL "Segun permiso" en la fila "Gestionar paciente": no lo tiene por base âno
		// esta en su fila de BASEâ y se le concede por grant explicito. Sin esta linea esa celda
		// de la matriz no tendria ninguna forma de cumplirse: el grant se rechazaria con 400.
		//
		// `hc:read` y `hc:write` entran en AKINE-04.01 (M09). La matriz Â§2 le dice al ORG_ADMIN
		// "No por defecto" en Ver HC y al CONSULTORIO_ADMIN "Segun rol clinico"; la Â§3 traduce las
		// dos a grant explicito sobre la membership. "Segun rol clinico" pide ademas habilitacion
		// profesional vigente a la fecha del evento, y eso NO se evalua todavia: el grant es la
		// mitad implementable, y la otra mitad queda anotada en el registro de cierre de la etapa.
		return Set.of(
				PermissionCode.AUDITORIA_READ_CLINICA,
				PermissionCode.PACIENTE_MANAGE,
				PermissionCode.HC_READ,
				PermissionCode.HC_WRITE);
	}

	private static Map<RoleCode, Map<PermissionCode, PermissionScope>> base() {
		Map<RoleCode, Map<PermissionCode, PermissionScope>> tabla = new EnumMap<>(RoleCode.class);

		// PLATFORM_ADMIN â el rol mas alto del sistema es el que tiene el acceso MAS restringido
		// a los datos de un tenant (matriz Â§1.3), no el mas amplio. Que esto parezca al reves es
		// la intencion.
		//
		// LA CONTRADICCION DE LA MATRIZ, RESUELTA POR EL LADO QUE FALLA CERRADO. La Â§6 le pone
		// "Global" a toda la columna salvo la auditoria clinica; la Â§7 le pone el invariante
		// contrario: "PLATFORM_ADMIN accede a datos de un tenant SOLO por acceso de soporte
		// justificado y auditado" (Â§32, DP-03). Los dos parrafos son vinculantes y no coinciden.
		// Con "Global" en las lecturas, el acceso de soporte era decorativo: nada lo exigia y su
		// unico efecto era encender un flag que el llamador podia ignorar ây cuatro servicios lo
		// ignorabanâ.
		//
		//   - tenant:read y auditoria:read pasan a SOPORTE. Sin un support_access vigente, un
		//     administrador de plataforma no lee el perfil del tenant, sus sedes, su suscripcion
		//     ni su auditoria; con soporte, la decision vuelve con viaSupportAccess = true y el
		//     llamador deja la fila SUPPORT_ACCESS_USED. Las dos mitades del invariante
		//     âjustificado y auditadoâ dejan de ser opcionales.
		//   - tenant:manage sigue GLOBAL, y es deliberado: es el CONTRATO del tenant âalta,
		//     cambio de plan, suspensionâ, no sus datos. Es la capacidad de intervenir en un
		//     incidente, ya deja su propia fila de auditoria nominal, y exigirle soporte dejaria
		//     a la plataforma sin poder suspender un tenant abusivo.
		//
		// La linea que separa GLOBAL de SOPORTE aca es una sola pregunta: Â¿la operacion deja por
		// si misma una fila que diga quien la hizo y por que?
		//
		// Las MUTACIONES la dejan â`SUBSCRIPTION_TRANSITIONED`, `MEMBERSHIP_*`,
		// `CONSULTORIO_*`, todas con actor y motivo obligatorioâ, asi que el hecho queda trazado
		// aunque no se registre ademas como uso de soporte. Las LECTURAS no dejan nada: sin
		// `SUPPORT_ACCESS_USED` no hay ninguna evidencia de que ocurrieron.
		//
		// Por eso `colaborador:read` es SOPORTE desde el 24/08/2026 (decision del usuario) y
		// `tenant:read` y `auditoria:read` ya lo eran: las tres son lectura de datos de un
		// tenant ajeno, que es literalmente lo que la matriz Â§7 protege. Leer el padron de
		// personas de cualquier centro sin dejar rastro era el hueco mas grande que quedaba.
		//
		// `consultorio:manage` y `colaborador:manage` quedan GLOBAL a proposito: son mutaciones,
		// dejan su propia fila nominal, y exigirles soporte sumaria un paso a operaciones de
		// rescate sin agregar trazabilidad que no exista ya. Escrito en la matriz Â§9.7.
		// `espacio:read` entra con SOPORTE por la misma pregunta: leer el catalogo fisico de un
		// centro ajeno no deja por si mismo ninguna fila que diga quien lo hizo ni por que.
		// Aprobado el 25/08/2026, matriz Â§5, Â§6 y Â§10.1.
		//
		// **Esta tabla usa Map.ofEntries y no Map.of**: con `turno:read` (05.01) y `hc:read`
		// (04.01) son ONCE pares, y `Map.of` admite diez como maximo. El limite es de sobrecargas
		// del JDK, no del modelo, y el error que produce â"no suitable method found"â no dice eso
		// en ninguna parte.
		tabla.put(RoleCode.PLATFORM_ADMIN, Map.ofEntries(
				Map.entry(PermissionCode.TENANT_MANAGE, PermissionScope.GLOBAL),
				Map.entry(PermissionCode.TENANT_READ, PermissionScope.SOPORTE),
				Map.entry(PermissionCode.CONSULTORIO_MANAGE, PermissionScope.GLOBAL),
				Map.entry(PermissionCode.ESPACIO_READ, PermissionScope.SOPORTE),
				Map.entry(PermissionCode.COLABORADOR_MANAGE, PermissionScope.GLOBAL),
				Map.entry(PermissionCode.COLABORADOR_READ, PermissionScope.SOPORTE),
				Map.entry(PermissionCode.AUDITORIA_READ, PermissionScope.SOPORTE),
				Map.entry(PermissionCode.AUDITORIA_READ_CLINICA, PermissionScope.RESTRINGIDO),
				// `paciente:manage` entra con SOPORTE en AKINE-03.01: la matriz Â§4 le da
				// literalmente "Soporte" a esta columna en la fila "Gestionar paciente". Es una
				// MUTACION y las mutaciones de esta tabla suelen quedar GLOBAL porque dejan su
				// propia fila nominal; aca no, y la diferencia es el dato: el padron de personas
				// es exactamente lo que Â§7 protege, y la matriz ya lo habia decidido asi. El
				// llamador deja `SUPPORT_ACCESS_USED` cuando la decision vuelve con
				// viaSupportAccess â ver `person.application.PersonaService`.
				Map.entry(PermissionCode.PACIENTE_MANAGE, PermissionScope.SOPORTE),
				// `turno:read` con SOPORTE y no GLOBAL en AKINE-05.01: hoy el buscador de slots no
				// devuelve un solo dato de paciente, pero la misma agenda con 05.02 muestra quien
				// tiene cada turno, y eso es lo que Â§7 protege. Mismo criterio que paciente:manage.
				Map.entry(PermissionCode.TURNO_READ, PermissionScope.SOPORTE),
				// `hc:read` entra con RESTRINGIDO en AKINE-04.01: es literalmente lo que la matriz
				// Â§2 le pone a esta columna en la fila "Ver Historia Clinica", y Â§3 lo traduce a
				// "denegado por defecto; solo con acceso de soporte justificado Y permiso adicional
				// clinico. Nunca implicito". Hoy el evaluador rechaza RESTRINGIDO siempre, asi que
				// la linea no cambia ninguna respuesta: esta para que la celda exista y para que
				// nadie la complete mas adelante con GLOBAL creyendo que falta por descuido.
				Map.entry(PermissionCode.HC_READ, PermissionScope.RESTRINGIDO),
				// `clase:read` con SOPORTE y no GLOBAL en AKINE-08.01, por el mismo motivo que
				// `turno:read`: hoy la clase no devuelve un solo dato de persona, pero 08.02 le
				// cuelga la lista de participantes, y eso es lo que Â§7 protege.
				Map.entry(PermissionCode.CLASE_READ, PermissionScope.SOPORTE),
				// `inscripcion:read` con SOPORTE en AKINE-08.02, y aca la razon deja de ser
				// preventiva: esta lectura SI devuelve nombres y documentos de personas.
				Map.entry(PermissionCode.INSCRIPCION_READ, PermissionScope.SOPORTE),
				// `reporte:read` con SOPORTE en AKINE-G-1 (DP-15). La matriz §2 le dice "Global" en
				// Ver Reportes, y se lee con el mismo criterio de §9.7 que ya bajo a SOPORTE las
				// otras lecturas de esta columna: un reporte es una lectura de datos de un tenant
				// —su agenda, su caja, su actividad— y una lectura no deja por si misma ninguna
				// fila que diga quien la hizo ni por que. Con soporte vigente, ReporteService
				// escribe SUPPORT_ACCESS_USED. Lo que ve de cada seccion lo sigue recortando su
				// permiso de fuente: con esta columna, turnos si; lo clinico y lo economico no.
				Map.entry(PermissionCode.REPORTE_READ, PermissionScope.SOPORTE)));

		// ORG_ADMIN â "tenant:manage" NO esta: la matriz Â§4 acota su "Limitado" a editar su
		// organizacion y ver su suscripcion, y deja el cambio de plan y la suspension para
		// PLATFORM_ADMIN. auditoria:read-clinica tampoco: es "No por defecto (grant)".
		tabla.put(RoleCode.ORG_ADMIN, Map.ofEntries(
				Map.entry(PermissionCode.TENANT_READ, PermissionScope.ORGANIZACION),
				Map.entry(PermissionCode.CONSULTORIO_MANAGE, PermissionScope.ORGANIZACION),
				Map.entry(PermissionCode.ESPACIO_READ, PermissionScope.ORGANIZACION),
				Map.entry(PermissionCode.COLABORADOR_MANAGE, PermissionScope.ORGANIZACION),
				Map.entry(PermissionCode.COLABORADOR_READ, PermissionScope.ORGANIZACION),
				Map.entry(PermissionCode.AUDITORIA_READ, PermissionScope.ORGANIZACION),
				Map.entry(PermissionCode.PACIENTE_MANAGE, PermissionScope.ORGANIZACION),
				Map.entry(PermissionCode.TURNO_READ, PermissionScope.ORGANIZACION),
				Map.entry(PermissionCode.TURNO_MANAGE, PermissionScope.ORGANIZACION),
				// `cobro:register` entra con asignacion base en AKINE-07.01, la etapa que crea la
				// deuda. La matriz Â§2 le dice "Si" al ORG_ADMIN, al CONSULTORIO_ADMIN y al
				// ADMINISTRATIVO en la fila Registrar Cobro; al PROFESIONAL "No por defecto" y al
				// PLATFORM_ADMIN "No" âsoporte mira, no operaâ.
				Map.entry(PermissionCode.COBRO_REGISTER, PermissionScope.ORGANIZACION),
				// `caja:operate` entra con asignacion base en AKINE-07.03, la etapa que crea la
				// caja. La matriz Â§2 le dice "Si" al ORG_ADMIN, al CONSULTORIO_ADMIN y al
				// ADMINISTRATIVO en la fila Operar Caja, y "No" al PROFESIONAL, al PACIENTE y al
				// PLATFORM_ADMIN â soporte mira, no opera, igual que con cobro:register.
				//
				// ES UN PERMISO DISTINTO DE `cobro:register` Y NO UN SINONIMO. Cobrar es un acto
				// comercial; abrir, arquear y cerrar una caja es responsabilidad sobre dinero
				// fisico. Colapsarlos haria que cualquiera que pueda cobrar pudiera tambien
				// declarar un arqueo, que es justo el control que M20 existe para tener. Por la
				// razon simetrica, EL COBRO NO EXIGE `caja:operate`: el movimiento que genera es
				// una consecuencia del cobro, y exigirlo haria que poder cobrar dependiera del
				// medio de pago elegido.
				Map.entry(PermissionCode.CAJA_OPERATE, PermissionScope.ORGANIZACION),
				// `convenio:manage` entra con asignacion base en AKINE-03.03, la etapa que crea el
				// catalogo de financiadores y planes (M15). La matriz Â§2 le dice "Si" al ORG_ADMIN
				// y al CONSULTORIO_ADMIN en la fila Administrar Convenios, y "No" al PROFESIONAL,
				// al ADMINISTRATIVO y al PACIENTE. La enmienda esta en la matriz Â§13.
				//
				// AL PLATFORM_ADMIN NO SE LE DA, Y ESO ES UNA DECISION. Su celda dice "Catalogo
				// global", que la Â§3 define como "solo sobre el catalogo de plataforma
				// (financiadores/planes globales), nunca sobre convenios de un tenant". Ese
				// catalogo global NO EXISTE: 03.03 modela el financiador como dato de la
				// organizacion (V41). Darselo hoy no cumpliria su celda, la violaria â lo dejaria
				// administrar los financiadores de un tenant, que es precisamente lo que su celda
				// excluye. Queda sin cumplirse hasta que exista la poblacion global.
				Map.entry(PermissionCode.CONVENIO_MANAGE, PermissionScope.ORGANIZACION),
				// AKINE-08.01. M28 Â§2 lo nombra entre los actores de una clase, y el alcance es el
				// mismo que el de sus turnos.
				Map.entry(PermissionCode.CLASE_READ, PermissionScope.ORGANIZACION),
				Map.entry(PermissionCode.CLASE_MANAGE, PermissionScope.ORGANIZACION),
				// AKINE-08.02. Mismo alcance que la clase: quien puede programarla puede anotar
				// gente en ella.
				Map.entry(PermissionCode.INSCRIPCION_READ, PermissionScope.ORGANIZACION),
				Map.entry(PermissionCode.INSCRIPCION_MANAGE, PermissionScope.ORGANIZACION),
				// AKINE-08.03. Tomar lista tiene codigo propio âun instructor podria marcarla sin
				// poder anotar ni dar de baja a nadieâ pero el reparto base es el mismo.
				Map.entry(PermissionCode.ASISTENCIA_MANAGE, PermissionScope.ORGANIZACION),
				// AKINE-G-1 (DP-15). La matriz §2 le dice "Si" en Ver Reportes. Lo que ve de cada
				// seccion lo decide el permiso de su fuente: sin hc:read ni sesion:register, lo
				// clinico se omite y se declara en las secciones omitidas.
				Map.entry(PermissionCode.REPORTE_READ, PermissionScope.ORGANIZACION)));

		// CONSULTORIO_ADMIN â todo acotado a SU sede. Sin tenant:read: la matriz no se lo da.
		// `Map.ofEntries` y no `Map.of`: AKINE-07.03 sumo `caja:operate` y este mapa llego a once
		// pares. `Map.of` acepta como mucho diez, y la undecima linea no compila.
		tabla.put(RoleCode.CONSULTORIO_ADMIN, Map.ofEntries(
				Map.entry(PermissionCode.CONSULTORIO_MANAGE, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.ESPACIO_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.COLABORADOR_MANAGE, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.COLABORADOR_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.AUDITORIA_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.PACIENTE_MANAGE, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.TURNO_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.TURNO_MANAGE, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.COBRO_REGISTER, PermissionScope.CONSULTORIO),
				// AKINE-07.03. Su sede, que es el alcance de su membership.
				Map.entry(PermissionCode.CAJA_OPERATE, PermissionScope.CONSULTORIO),
				// AKINE-08.01. Ver el comentario del ORG_ADMIN.
				Map.entry(PermissionCode.CLASE_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.CLASE_MANAGE, PermissionScope.CONSULTORIO),
				// AKINE-08.02. Ver el comentario del ORG_ADMIN.
				Map.entry(PermissionCode.INSCRIPCION_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.INSCRIPCION_MANAGE, PermissionScope.CONSULTORIO),
				// AKINE-08.03. Ver el comentario del ORG_ADMIN.
				Map.entry(PermissionCode.ASISTENCIA_MANAGE, PermissionScope.CONSULTORIO),
				// AKINE-03.03. La matriz Â§2 le dice "Si" en Administrar Convenios, igual que al
				// ORG_ADMIN. El alcance es su sede, que es el de su membership: que el financiador
				// sea de la ORGANIZACION no lo convierte en un permiso de organizacion â el
				// alcance sigue siendo el de la membership con la que se decide, mismo criterio
				// que `paciente:manage` sobre una Persona que tambien es de la organizacion.
				Map.entry(PermissionCode.CONVENIO_MANAGE, PermissionScope.CONSULTORIO),
				// AKINE-G-1 (DP-15). "Si" en Ver Reportes, con el alcance de su sede.
				Map.entry(PermissionCode.REPORTE_READ, PermissionScope.CONSULTORIO)));

		// PROFESIONAL y ADMINISTRATIVO â ven la lista de colaboradores de su sede y, desde la
		// aprobacion del 25/08/2026, el catalogo fisico de esa misma sede: sin `espacio:read` un
		// profesional no puede saber en que box atiende. Todo lo clinico y economico de sus
		// columnas sigue siendo de F4 en adelante.
		//
		// `paciente:manage` los separa, y es la unica fila de la matriz Â§4 donde estos dos roles
		// difieren: al ADMINISTRATIVO le dice "Si" âdar de alta y editar fichas es literalmente
		// su trabajoâ y al PROFESIONAL le dice "Segun permiso", o sea NO por defecto y si por
		// grant explicito. Por eso el profesional no lo tiene aca y si figura en
		// `otorgablesComoGrant()`, que hasta AKINE-03.01 tenia un solo elemento.
		//
		// `hc:read` y `hc:write` entran en AKINE-04.01 y SOLO para el PROFESIONAL. La matriz Â§2 le
		// dice "Si" en las dos filas de Historia Clinica y es el unico rol al que se las dice: es
		// quien atiende. Alcance CONSULTORIO, que es el de su membership; que la historia sea de la
		// organizacion (DP-03) no las convierte en permisos de organizacion â el alcance sigue
		// siendo el de la membership con la que se decide.
		//
		// EL ADMINISTRATIVO NO LAS TIENE, Y ESO ES UNA DECISION, NO UN OLVIDO. Su celda de Ver HC
		// dice "Limitado", y la Â§4 lo define sin ambiguedad: solo metadatos administrativos,
		// NUNCA contenido clinico. Darle el mismo `hc:read` que al profesional y confiar en que la
		// capa de presentacion recorte seria el control del lado equivocado âel backend es la
		// autoridad, AGENT.md Â§1â, y distinguir las dos lecturas exige un codigo de permiso propio
		// que el catalogo de la matriz Â§5 no tiene. La proyeccion existe y esta probada
		// (`HistoriaClinicaView.soloMetadatos`); lo que falta es la decision de producto sobre como
		// se otorga. Hasta entonces, cerrado.
		tabla.put(RoleCode.PROFESIONAL, Map.ofEntries(
				Map.entry(PermissionCode.COLABORADOR_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.ESPACIO_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.TURNO_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.HC_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.HC_WRITE, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.TURNO_MANAGE, PermissionScope.CONSULTORIO),
				// AKINE-08.01: es quien dicta la clase. M28 Â§2 lo nombra como
				// "Profesional / Instructor".
				Map.entry(PermissionCode.CLASE_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.CLASE_MANAGE, PermissionScope.CONSULTORIO),
				// AKINE-08.02: el instructor necesita saber a quien tiene enfrente y dar de baja a
				// quien avisa que no viene.
				Map.entry(PermissionCode.INSCRIPCION_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.INSCRIPCION_MANAGE, PermissionScope.CONSULTORIO),
				// AKINE-08.03. Ver el comentario del ORG_ADMIN.
				Map.entry(PermissionCode.ASISTENCIA_MANAGE, PermissionScope.CONSULTORIO),
				// `sesion:register` entra con asignacion base en AKINE-06.01, la etapa que crea la
				// atencion. La matriz Â§2 le dice "Si" al PROFESIONAL en la fila Registrar Sesion, y
				// "Segun rol clinico" al CONSULTORIO_ADMIN âo sea NO por defectoâ. A los demas les
				// dice "No". Quien no atiende no registra atenciones.
				Map.entry(PermissionCode.SESION_REGISTER, PermissionScope.CONSULTORIO),
				// AKINE-G-1 (DP-15). La matriz §2 le dice "Limitado" en Ver Reportes y la §4 define
				// la restriccion: "solo reportes de su propia actividad". ACTIVIDAD_PROPIA cubre su
				// sede como CONSULTORIO y le avisa a ReporteService que recorte turnos, sesiones y
				// casos a lo que atendio o le fue asignado. Lo economico ni siquiera llega: no
				// tiene cobro:register.
				Map.entry(PermissionCode.REPORTE_READ, PermissionScope.ACTIVIDAD_PROPIA)));
		// Map.ofEntries y no Map.of desde AKINE-08.02: el segundo tiene un tope de diez pares y
		// esta fila lo alcanzo. Mismo cambio que hizo 08.01 con el CONSULTORIO_ADMIN, y por el
		// mismo motivo: no es semantica, es el unico constructor que admite el par once.
		tabla.put(RoleCode.ADMINISTRATIVO, Map.ofEntries(
				Map.entry(PermissionCode.COLABORADOR_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.ESPACIO_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.PACIENTE_MANAGE, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.TURNO_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.TURNO_MANAGE, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.COBRO_REGISTER, PermissionScope.CONSULTORIO),
				// AKINE-08.01: armar la grilla del centro es literalmente su trabajo, igual que
				// dar turnos.
				Map.entry(PermissionCode.CLASE_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.CLASE_MANAGE, PermissionScope.CONSULTORIO),
				// AKINE-08.02: el mostrador es quien anota, cobra y da de baja.
				Map.entry(PermissionCode.INSCRIPCION_READ, PermissionScope.CONSULTORIO),
				Map.entry(PermissionCode.INSCRIPCION_MANAGE, PermissionScope.CONSULTORIO),
				// AKINE-08.03. Ver el comentario del ORG_ADMIN.
				Map.entry(PermissionCode.ASISTENCIA_MANAGE, PermissionScope.CONSULTORIO),
				// AKINE-07.03. La matriz §2 le dice "Si" al ADMINISTRATIVO en Operar Caja, y es
				// coherente: quien esta en el mostrador es quien abre la caja a la manana y la
				// arquea a la noche. El PROFESIONAL no lo tiene —su celda dice "No"—, a diferencia
				// de lo que pasa con turnos, donde si agenda a sus propios pacientes.
				Map.entry(PermissionCode.CAJA_OPERATE, PermissionScope.CONSULTORIO),
				// AKINE-G-1 (DP-15). "Limitado" en Ver Reportes, y la §4 lo define como "solo
				// operativos y de caja, sin contenido clinico". Se cumple SIN un alcance especial:
				// no tiene hc:read ni sesion:register, asi que las secciones clinicas se omiten por
				// su permiso de fuente y quedan declaradas como omitidas. Darle ACTIVIDAD_PROPIA lo
				// dejaria viendo solo los turnos que "atendio", que son cero.
				Map.entry(PermissionCode.REPORTE_READ, PermissionScope.CONSULTORIO)));

		// PACIENTE â ninguna fila de la matriz Â§6 le da nada en F1. Sus celdas ("Propio",
		// "Propia autorizada") viven en acciones de F3 y F4.
		tabla.put(RoleCode.PACIENTE, Map.of());

		// Se copia a EnumMap para que el acceso sea por indice de ordinal y no por hash: esta
		// tabla se consulta en cada evaluacion de permiso, que es el camino mas caliente del
		// sistema despues de la resolucion de contexto.
		//
		// El mapa vacio se maneja aparte y no es un detalle: `new EnumMap<>(unMapaVacio)` lanza
		// IllegalArgumentException porque no puede deducir el tipo del enum sin ninguna clave.
		// PACIENTE no tiene ninguna fila en la matriz Â§6, asi que su mapa esta vacio, y sin esta
		// rama la clase entera falla al inicializarse â un ExceptionInInitializerError que se
		// manifiesta como "no se pudo crear el bean" a tres niveles de distancia de la causa.
		tabla.replaceAll((rol, permisos) -> permisos.isEmpty()
				? Map.of()
				: Collections.unmodifiableMap(new EnumMap<>(permisos)));
		return Collections.unmodifiableMap(tabla);
	}
}
