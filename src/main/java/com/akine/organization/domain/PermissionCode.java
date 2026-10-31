package com.akine.organization.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Catalogo de permisos granulares de la matriz aprobada
 * ({@code docs/seguridad/matriz-permisos-minima.md} §5, vinculante).
 *
 * <p>El codigo textual {@code <dominio>:<accion>} es lo que viaja por el {@code spi} y lo que
 * ve el frontend: estable, en minusculas y sin significado de UI. El enum es la forma interna
 * y no cruza el borde del modulo, por el mismo motivo documentado en {@code MembershipSnapshot}
 * —un consumidor que usara el enum estaria importando {@code organization.domain}, que ArchUnit
 * rechaza—.
 *
 * <p><b>Estan los de todas las fases, no solo los de F1.</b> Declararlos completos fija el
 * catalogo de una vez y hace que agregar una fase sea sumar filas a {@link RolePermissions}, no
 * rehacer el modelo. Los que todavia no tienen entidad contra la que evaluarse simplemente no
 * aparecen en ninguna fila de la asignacion base, asi que <b>deniegan siempre</b>. Probar que
 * deniegan es tan importante como probar que los de F1 permiten: documenta que faltan por
 * diseño y no por descuido.
 */
public enum PermissionCode {

	/** Gestionar tenant. Fase 1. */
	TENANT_MANAGE("tenant:manage"),

	/** Ver datos y suscripcion de la propia organizacion (el "Limitado" de ORG_ADMIN). Fase 1. */
	TENANT_READ("tenant:read"),

	/**
	 * Editar los datos de la propia organizacion: nombre y zona horaria. <b>DP-43, U-15.</b>
	 *
	 * <p>La matriz se contradecia: la §4 le daba al {@code ORG_ADMIN} la edicion de su organizacion
	 * dentro de su "Limitado" y la §5/§6 no tenian codigo de edicion, asi que el PATCH se autorizaba
	 * con {@code tenant:read} y lo alcanzaba cualquiera que leyera el tenant —incluido el
	 * {@code PLATFORM_ADMIN} con soporte—. La decision del 19/10/2026 fue la regla mas restrictiva:
	 * <b>solo el {@code ORG_ADMIN}</b>, con alcance organizacion. No es otorgable por grant y el
	 * {@code PLATFORM_ADMIN} no lo tiene: lo que la plataforma gestiona es el contrato del tenant
	 * ({@link #TENANT_MANAGE}), no sus datos. Matriz §19.
	 */
	TENANT_UPDATE("tenant:update"),

	/** Gestionar consultorio. Fase 1 (minimo) / F2. Los endpoints que lo consumen son de 02.01. */
	CONSULTORIO_MANAGE("consultorio:manage"),

	/**
	 * Consultar el catalogo fisico y la disponibilidad de una sede. F2.
	 *
	 * <p>Aprobado el 25/08/2026 sobre la propuesta que 02.02 dejo escrita en la §10.1 de la
	 * matriz; la fila vive en la §5 y en la §6 desde entonces. Existe porque el catalogo no
	 * tenia <b>ningun</b> codigo de lectura de espacios: el unico aplicable era
	 * {@link #CONSULTORIO_MANAGE}, que la §6 le niega justamente a {@code PROFESIONAL} y
	 * {@code ADMINISTRATIVO}, que son los que necesitan ver en que box atienden.
	 */
	ESPACIO_READ("espacio:read"),

	/** Gestionar colaboradores: alta, asignar rol, suspender, revocar. Fase 1. */
	COLABORADOR_MANAGE("colaborador:manage"),

	/** Listar colaboradores del alcance. Fase 1. */
	COLABORADOR_READ("colaborador:read"),

	/** Consultar auditoria operativa del alcance. Fase 1. */
	AUDITORIA_READ("auditoria:read"),

	/**
	 * Consultar auditoria de acceso clinico (RN-M24-003). Fase 1 como modelo, F4 en efecto.
	 *
	 * <p>Es el unico "No por defecto (grant)" de F1 y, por lo tanto, <b>el unico codigo que
	 * {@code membership_grant} puede contener hoy</b>. Cualquier otro se rechaza con 400.
	 */
	AUDITORIA_READ_CLINICA("auditoria:read-clinica"),

	/**
	 * Gestionar paciente. F3.
	 *
	 * <p><b>Con asignacion base desde AKINE-03.01</b>, que es cuando nacio el modulo que lo
	 * evalua: SOPORTE para {@code PLATFORM_ADMIN}, ORGANIZACION para {@code ORG_ADMIN},
	 * CONSULTORIO para {@code CONSULTORIO_ADMIN} y {@code ADMINISTRATIVO}. El
	 * {@code PROFESIONAL} lo recibe solo por grant explicito —la matriz §4 le dice "Segun
	 * permiso"— y por eso es, junto con {@code auditoria:read-clinica}, uno de los dos codigos
	 * que {@code membership_grant} admite.
	 */
	PACIENTE_MANAGE("paciente:manage"),

	/**
	 * Ver el padron y los datos administrativos de las personas. <b>DP-22, AKINE-DU-6.</b>
	 *
	 * <p>Hasta DU-6 las lecturas de {@code person} —padron, ficha, 360, coberturas, ordenes,
	 * autorizaciones, adjuntos, elegibilidad— se autorizaban por <b>pertenencia al tenant</b>, y
	 * una membership con rol {@code PACIENTE} leia el padron entero de su organizacion. La
	 * decision del 08/10/2026 fue cerrar el hueco con un permiso de lectura <b>solo para el
	 * personal</b> y dejar el alcance {@code OWN} —el autoservicio del paciente sobre sus propios
	 * datos, que necesita el vinculo cuenta↔persona— para despues del MVP.
	 *
	 * <p>Asignacion base: ORGANIZACION para {@code ORG_ADMIN}; CONSULTORIO para
	 * {@code CONSULTORIO_ADMIN}, {@code PROFESIONAL} y {@code ADMINISTRATIVO}. El
	 * {@code PROFESIONAL} lo tiene por base y no por grant, a diferencia de
	 * {@link #PACIENTE_MANAGE}: no puede atender a quien no puede ver. {@code PACIENTE} no lo
	 * tiene. {@code PLATFORM_ADMIN} tampoco: no tiene contexto de tenant en {@code person} y las
	 * lecturas lo exigen; si alguna vez se abre ese camino, entra con SOPORTE y
	 * {@code SUPPORT_ACCESS_USED}, como {@link #PACIENTE_MANAGE}. No es otorgable por grant.
	 */
	PACIENTE_READ("paciente:read"),

	/**
	 * Ver la agenda y buscar turnos disponibles. F5.
	 *
	 * <p><b>Con asignacion base desde AKINE-05.01</b>, la etapa que crea el motor que lo evalua.
	 *
	 * <p><b>La matriz literal de §32 no tiene fila de turnos</b>, asi que el alcance no sale de
	 * ella sino de las filas vecinas, y queda declarado como enmienda §13 pendiente de aprobacion:
	 * <ul>
	 *   <li>{@code PLATFORM_ADMIN} — SOPORTE, no GLOBAL. Hoy el buscador de slots no devuelve un
	 *       solo dato de paciente, pero la misma agenda con 05.02 muestra quien tiene cada turno,
	 *       y eso es exactamente lo que §7 protege. Es el mismo criterio que
	 *       {@link #PACIENTE_MANAGE}.</li>
	 *   <li>{@code ADMINISTRATIVO} — CONSULTORIO. Dar turnos es literalmente su trabajo; la fila
	 *       "Registrar Cobro" le dice "Si" y no habria forma de cobrar un turno que no puede ver.</li>
	 *   <li>{@code PROFESIONAL} — CONSULTORIO, y sin grant. A diferencia de {@code paciente:manage},
	 *       aca no hay una celda "Segun permiso" que respetar: un profesional que no puede ver su
	 *       propia agenda no puede trabajar.</li>
	 *   <li>{@code PACIENTE} — nada. La vista publica del buscador que le corresponderia esta
	 *       fuera del alcance de DP-10, y darle el permiso sin ella solo abriria la agenda interna.</li>
	 * </ul>
	 */
	TURNO_READ("turno:read"),

	/**
	 * Reservar, reprogramar y cancelar turnos. F5.
	 * Reservar, confirmar y —desde 05.03— cancelar turnos. F5.
	 *
	 * <p><b>Con asignacion base desde AKINE-05.02</b>, la etapa que crea la escritura. 05.01 lo
	 * declaro sin asignacion a proposito: el codigo tiene que existir antes de que ningun endpoint
	 * lo nombre, y separar la declaracion de la asignacion evita repetir lo que paso con
	 * {@code paciente:manage}, que vivio en el catalogo desde el dia uno sin que lo tuviera nadie y
	 * sin que ninguna etapa declarara que eso fuera intencional.
	 *
	 * <p><b>{@code PLATFORM_ADMIN} NO lo recibe</b>, a diferencia de {@code turno:read}. Reservar
	 * es una accion operativa del centro, y la matriz §32 le dice "No" a esta columna en todas las
	 * filas operativas —Registrar Cobro, Operar Caja—. Soporte mira, no opera.
	 *
	 * <p>Lo tienen {@code ORG_ADMIN} (organizacion), y con alcance de sede el
	 * {@code CONSULTORIO_ADMIN}, el {@code ADMINISTRATIVO} —dar turnos es literalmente su trabajo—
	 * y el {@code PROFESIONAL}, que agenda a sus propios pacientes. Parte de la enmienda §13, que
	 * sigue pendiente de aprobacion.
	 */
	TURNO_MANAGE("turno:manage"),

	/**
	 * Autorizar un turno sobre un horario ya ocupado (TURNO.md §21). Aparte de {@link #TURNO_MANAGE}:
	 * dar turnos es de recepcion y pisar uno es de quien puede autorizarlo. Lo tienen
	 * {@code ORG_ADMIN} y {@code CONSULTORIO_ADMIN}.
	 */
	TURNO_SOBRETURNO("turno:sobreturno"),

	/** Ver Historia Clinica. F4. Sin asignacion base todavia: deniega. */
	HC_READ("hc:read"),

	/** Editar Historia Clinica. F4. Sin asignacion base todavia: deniega. */
	HC_WRITE("hc:write"),

	// `caso:create` (Crear Caso Clinico, F4) YA NO ES UN CODIGO: se absorbio en `hc:write` (U-04,
	// matriz §18.3). Abrir un caso siempre se autorizo con `hc:write`, y desde G-5 eso da
	// exactamente las celdas de la fila "Crear Caso Clinico": PROFESIONAL por base y
	// CONSULTORIO_ADMIN por grant. Un codigo que ningun endpoint evalua y ningun rol tiene solo
	// servia para que alguien creyera que existe un control que no existe.

	/** Registrar Sesion. F6. Sin asignacion base todavia: deniega. */
	SESION_REGISTER("sesion:register"),

	/**
	 * Enmendar una sesion cerrada. <b>U-25 (DP-43).</b>
	 *
	 * <p>Hasta DP-43 la enmienda se autorizaba con {@code sesion:register} mas la propiedad de la
	 * atencion. Separarlo es lo que permite tratar la correccion de un registro clinico cerrado
	 * como lo que es —otra accion, con su ventana— y no como un guardado mas. Se reparte como la
	 * fila "Registrar Sesion" de la matriz §2: base del {@code PROFESIONAL} con alcance de su sede;
	 * el "Segun rol clinico" del {@code CONSULTORIO_ADMIN} no se concede, igual que
	 * {@code sesion:register}. La propiedad sigue mandando: tener el permiso no deja enmendar la
	 * atencion de otro (409 {@code sesion-ajena}).
	 */
	SESION_AMEND("sesion:amend"),

	/**
	 * Reemplazar al profesional de una sesion abierta. <b>U-24 (DP-43).</b>
	 *
	 * <p>Base <b>solo</b> del {@code CONSULTORIO_ADMIN}, con alcance de su sede, y no otorgable. No
	 * es un permiso para escribir en la atencion ajena —eso sigue siendo 409—: es la operacion
	 * explicita, con motivo y auditada, que cambia quien es el dueño de la atencion.
	 */
	SESION_REASSIGN("sesion:reassign"),

	/** Administrar Convenios. F3. Sin asignacion base todavia: deniega. */
	CONVENIO_MANAGE("convenio:manage"),

	/**
	 * Ver financiadores, planes, convenios y aranceles. <b>U-07, mismo criterio que DP-22.</b>
	 *
	 * <p>Hasta U-07 las lecturas de {@code contracting} se autorizaban por pertenencia al tenant, y
	 * una membership con rol {@code PACIENTE} leia la configuracion economica entera del centro.
	 * Asignacion base: ORGANIZACION para {@code ORG_ADMIN}; CONSULTORIO para
	 * {@code CONSULTORIO_ADMIN}, {@code PROFESIONAL} y {@code ADMINISTRATIVO}. {@code PACIENTE} y
	 * {@code PLATFORM_ADMIN} no lo tienen (el catalogo global de financiadores no existe, matriz
	 * §13.2). No es otorgable por grant. Matriz §18.1.
	 */
	CONVENIO_READ("convenio:read"),

	/**
	 * Consultar el catalogo clinico —especialidades, practicas, nomencladores, vigencias y tipos de
	 * medicion—, global y propio. <b>U-08: la propuesta de la matriz §11.1, aprobada tal cual.</b>
	 *
	 * <p>GLOBAL para {@code PLATFORM_ADMIN} (solo ve el catalogo comun, que no es dato de ningun
	 * tenant), ORGANIZACION para {@code ORG_ADMIN}, CONSULTORIO para {@code CONSULTORIO_ADMIN},
	 * {@code PROFESIONAL} y {@code ADMINISTRATIVO}. {@code PACIENTE} no. Matriz §18.2.
	 */
	CATALOGO_READ("catalogo:read"),

	/**
	 * Administrar los conceptos del catalogo clinico y las solicitudes de alta. <b>U-08.</b>
	 *
	 * <p>GLOBAL para {@code PLATFORM_ADMIN} —y solo sobre conceptos globales: la plataforma no
	 * crea conceptos de un tenant—, ORGANIZACION para {@code ORG_ADMIN}, CONSULTORIO para
	 * {@code CONSULTORIO_ADMIN}. Reemplaza la autorizacion interina con {@code consultorio:manage}
	 * de 02.05. Matriz §18.2.
	 */
	CATALOGO_MANAGE("catalogo:manage"),

	/** Registrar Cobro. F7. Sin asignacion base todavia: deniega. */
	COBRO_REGISTER("cobro:register"),

	/** Operar Caja. F7. Sin asignacion base todavia: deniega. */
	CAJA_OPERATE("caja:operate"),

	/**
	 * Ver Reportes. F8.
	 *
	 * <p><b>Con asignacion base desde AKINE-G-1 (DP-15)</b>: ORG_ADMIN, CONSULTORIO_ADMIN y
	 * ADMINISTRATIVO con el alcance de su membership, PROFESIONAL con
	 * {@link PermissionScope#ACTIVIDAD_PROPIA} y PLATFORM_ADMIN con SOPORTE. Antes no lo tenia nadie
	 * y los tres endpoints de {@code reporting} eran inalcanzables.
	 */
	REPORTE_READ("reporte:read"),

	/**
	 * Ver clases programadas y su historial. F9, M28.
	 *
	 * <p><b>Con asignacion base desde AKINE-08.01</b>, la etapa que crea la clase. Mismo reparto
	 * que {@link #TURNO_READ}, incluido {@code PLATFORM_ADMIN} con alcance SOPORTE: soporte mira.
	 *
	 * <p>Es un codigo propio y no se reusa {@code turno:read}, aunque hoy los reparta igual: una
	 * clase y un turno son entidades distintas con reglas distintas, y 08.02 va a colgar de esta
	 * lectura la lista de participantes — que es exactamente el dato que alguien podria querer
	 * cerrar sin cerrar la agenda de turnos. Fusionar los dos codigos hoy hace imposible separarlos
	 * despues sin un cambio incompatible.
	 */
	CLASE_READ("clase:read"),

	/**
	 * Programar, reprogramar y cancelar clases. F9, M28.
	 *
	 * <p><b>Con asignacion base desde AKINE-08.01.</b> Mismo reparto que {@link #TURNO_MANAGE}:
	 * {@code ORG_ADMIN} con alcance de organizacion, y con alcance de sede el
	 * {@code CONSULTORIO_ADMIN}, el {@code ADMINISTRATIVO} —armar la grilla es su trabajo— y el
	 * {@code PROFESIONAL}, que programa las clases que el mismo dicta. Los actores de M28 §2 son
	 * exactamente esos.
	 *
	 * <p><b>{@code PLATFORM_ADMIN} NO lo recibe</b>, igual que con {@code turno:manage}: programar
	 * una clase compromete un recurso del centro y la matriz §32 le dice "No" a todas las filas
	 * operativas. Soporte mira, no opera.
	 */
	CLASE_MANAGE("clase:manage"),

	/**
	 * Ver la lista de participantes de una clase. F9, M28.
	 *
	 * <p><b>Con asignacion base desde AKINE-08.02</b>, y es un codigo propio y no {@code clase:read}
	 * justamente por lo que aquel javadoc anticipaba: 08.01 decidio que ninguna respuesta de clase
	 * lleva participantes, y la lista vive en su propio endpoint. Si el mismo permiso abriera la
	 * grilla y la lista, esa decision seria decorativa — quien puede ver que hay una clase de
	 * Pilates a las 9 no necesita saber quien esta anotado.
	 *
	 * <p>{@code PLATFORM_ADMIN} lo recibe con alcance SOPORTE, igual que {@code clase:read}: es el
	 * dato de persona que §7 de la matriz protege.
	 */
	INSCRIPCION_READ("inscripcion:read"),

	/**
	 * Inscribir, confirmar y cancelar inscripciones. F9, M28.
	 *
	 * <p><b>Con asignacion base desde AKINE-08.02.</b> Mismo reparto que {@link #CLASE_MANAGE}: los
	 * actores que M28 §2 nombra son el administrativo, el profesional/instructor y el administrador
	 * de consultorio. {@code PLATFORM_ADMIN} no lo recibe: anotar a alguien en una clase es
	 * operacion del centro y la matriz §32 le dice "No" a todas las filas operativas.
	 *
	 * <p><b>El autoservicio del paciente queda afuera, y no por olvido.</b> M28 §2 lo nombra como
	 * actor "cuando exista autoservicio", y no existe: no hay vinculo entre {@code cuenta} y
	 * {@code persona} en ningun lado del sistema —es el mismo hueco por el que el alcance
	 * {@code OWN} no esta implementado—. Sin ese vinculo, "inscribirme a mi mismo" no se puede
	 * autorizar sin abrir "inscribir a cualquiera".
	 */
	INSCRIPCION_MANAGE("inscripcion:manage"),

	/**
	 * Registrar, corregir y cerrar asistencia de una clase. F9, M28.
	 *
	 * <p><b>Con asignacion base desde AKINE-08.03</b>, con el mismo reparto que
	 * {@link #INSCRIPCION_MANAGE}. Es un codigo propio y no el de inscripciones porque anotar a
	 * alguien y decir que vino son decisiones distintas: en un centro con instructores, el que da
	 * la clase marca asistencia y no deberia poder inscribir ni cancelar inscripciones ajenas. Un
	 * permiso que no se puede separar convierte esa politica en imposible, y separarlo despues es
	 * un cambio incompatible.
	 *
	 * <p>{@code PLATFORM_ADMIN} no lo recibe: tomar lista es operacion del centro, y la matriz §32
	 * le dice "No" a todas las filas operativas.
	 */
	ASISTENCIA_MANAGE("asistencia:manage"),

	/**
	 * Ver el catalogo de productos de pack de una sede. F9, M29 (AKINE-08.06).
	 *
	 * <p>La matriz §32 no tiene fila de productos: el reparto sale de la mas cercana. Lo tienen
	 * {@code ORG_ADMIN}, {@code CONSULTORIO_ADMIN} y el {@code ADMINISTRATIVO}, que tiene que ver el
	 * catalogo para vender.
	 */
	PRODUCTO_READ("producto:read"),

	/**
	 * Crear, editar y dar de baja productos de pack. F9, M29 (AKINE-08.06).
	 *
	 * <p>Sin fila en la matriz; se reparte como {@code convenio:manage} —fijar que se vende y a cuanto
	 * es decision comercial del centro—: {@code ORG_ADMIN} y {@code CONSULTORIO_ADMIN}, no el
	 * mostrador. El plan de 08.06 pide administracion de producto y operador economico separados.
	 */
	PRODUCTO_MANAGE("producto:manage"),

	/**
	 * Ver los pases vendidos, su saldo y su ledger. F9, M29 (AKINE-08.06). Dato economico: el
	 * {@code PROFESIONAL} no lo recibe ("No por defecto" en las filas economicas de la matriz §2).
	 */
	PASE_READ("pase:read"),

	/**
	 * Vender un pase. F9, M29 (AKINE-08.06). Mismo reparto que {@code cobro:register}: vender un
	 * pack es trabajo del mostrador. {@code PLATFORM_ADMIN} no lo recibe.
	 */
	PASE_MANAGE("pase:manage"),

	/**
	 * Ajustar el saldo de un pase y anularlo. F9, M29 (AKINE-08.07, RF-M29-005).
	 *
	 * <p>RF-M29-005 pide "permiso especifico" y el plan de 08.07 "permiso reforzado": corregir lo
	 * vendido no es venderlo. Sin fila en la matriz §32; lo tienen {@code ORG_ADMIN} y
	 * {@code CONSULTORIO_ADMIN}, no el mostrador, con el mismo criterio que {@code producto:manage}.
	 */
	PASE_ADJUST("pase:adjust");

	private final String code;

	PermissionCode(String code) {
		this.code = code;
	}

	/** Codigo textual estable. Es lo unico que sale del modulo. */
	public String code() {
		return code;
	}

	/**
	 * Traduce un codigo textual al enum.
	 *
	 * <p>Devuelve vacio ante un codigo desconocido en vez de lanzar: quien recibe un codigo del
	 * exterior tiene que poder responder <b>400</b> con un mensaje util, y no un 500. Un
	 * {@code valueOf} pelado convertiria un error de cliente en un error de servidor.
	 */
	public static Optional<PermissionCode> desde(String code) {
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		return Arrays.stream(values()).filter(p -> p.code.equals(code)).findFirst();
	}
}
