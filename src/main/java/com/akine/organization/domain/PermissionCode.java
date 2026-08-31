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
	 *
	 * <p><b>Sin asignacion base todavia: deniega.</b> La activa AKINE-05.02, que es la etapa que
	 * crea la escritura. Declararlo aca y no alli es a proposito: el codigo tiene que existir
	 * antes de que ningun endpoint lo nombre, y separar la declaracion de la asignacion evita
	 * repetir lo que paso con {@code paciente:manage}, que vivio en el catalogo desde el dia uno
	 * sin que lo tuviera nadie y sin que ninguna etapa declarara que eso era intencional.
	 */
	TURNO_MANAGE("turno:manage"),

	/** Ver Historia Clinica. F4. Sin asignacion base todavia: deniega. */
	HC_READ("hc:read"),

	/** Editar Historia Clinica. F4. Sin asignacion base todavia: deniega. */
	HC_WRITE("hc:write"),

	/** Crear Caso Clinico. F4. Sin asignacion base todavia: deniega. */
	CASO_CREATE("caso:create"),

	/** Registrar Sesion. F6. Sin asignacion base todavia: deniega. */
	SESION_REGISTER("sesion:register"),

	/** Administrar Convenios. F3. Sin asignacion base todavia: deniega. */
	CONVENIO_MANAGE("convenio:manage"),

	/** Registrar Cobro. F7. Sin asignacion base todavia: deniega. */
	COBRO_REGISTER("cobro:register"),

	/** Operar Caja. F7. Sin asignacion base todavia: deniega. */
	CAJA_OPERATE("caja:operate"),

	/** Ver Reportes. F8. Sin asignacion base todavia: deniega. */
	REPORTE_READ("reporte:read");

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
