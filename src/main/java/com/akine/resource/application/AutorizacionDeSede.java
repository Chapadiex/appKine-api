package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.resource.domain.exception.ConsultorioNotAccessibleException;
import com.akine.resource.domain.exception.ConsultorioNotOperableException;
import com.akine.resource.domain.exception.ProfesionalNoVinculadoException;
import com.akine.resource.domain.exception.ProfesionalNotAccessibleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Set;

/**
 * Los controles de acceso que comparten los cuatro servicios de M05, en un solo lugar.
 *
 * <h2>Por que se extrajo</h2>
 *
 * <p>{@code DisponibilidadService}, {@code ExcepcionService}, {@code CalendarioService} y
 * {@code DisponibilidadEfectivaService} tenian cada uno su copia privada de estos metodos, con
 * javadocs que decian "mismo control, mismo motivo" — que es una descripcion exacta de una copia.
 * Todas eran correctas, y esa es justamente la forma que produce "lo arreglamos en tres de los
 * cuatro lugares" un anio despues. Es el mismo argumento con el que se extrajo {@link ZonaSede}.
 *
 * <h2>Por que utilidad estatica con dependencias por parametro</h2>
 *
 * <p>Podria ser un bean colaborador, pero eso le sumaria un constructor a los cuatro servicios y
 * ninguno gana nada: aca no hay estado que guardar. Las tres dependencias —directorio de sedes,
 * directorio de vinculos y evaluador de permisos— viajan por parametro, asi que esta clase no
 * conoce a ningun servicio, ningun servicio conoce a otro, y los tests de cada uno siguen
 * inyectando sus propios dobles sin enterarse de que existe este archivo.
 *
 * <p>{@code operacion} es un texto para el log. No participa de ninguna decision: lo unico que
 * hace es que "sin contexto validado" siga diciendo de que pantalla vino.
 *
 * <h2>El orden, que no es cosmetico</h2>
 *
 * <p>Pertenencia primero, permiso despues. Una sede de otro tenant sale por <b>404</b> antes de
 * que el evaluador pueda contestar 403: un 403 confirmaria que esa sede existe y bastaria
 * recorrer ids para mapear el SaaS. Falta de contexto es <b>403 y nunca 401</b>, porque el
 * interceptor del frontend borra el token ante cualquier 401 y deja al usuario en un bucle de
 * login del que no sale.
 */
final class AutorizacionDeSede {

	private static final Logger log = LoggerFactory.getLogger(AutorizacionDeSede.class);

	/**
	 * Roles cuyo vinculo NO puede ser sujeto de disponibilidad (ruling R18).
	 *
	 * <h2>Por que una lista de EXCLUIDOS y no {@code roleCode == PROFESIONAL}</h2>
	 *
	 * <p>Es el fix que alguien va a querer escribir aca dentro de seis meses, y esta mal. La
	 * matriz de permisos §1.2 lo dice con todas las letras: <b>rol de seguridad ≠ disciplina ≠
	 * especialidad ≠ habilitacion para prestar un servicio</b> (RN-M05-005), y §1.2 ademas
	 * PROHIBE crear roles por profesion. En un centro chico el duenio atiende: un
	 * {@code CONSULTORIO_ADMIN} o un {@code ORG_ADMIN} con agenda propia es lo normal, no una
	 * anomalia, y filtrar por {@code PROFESIONAL} le impediria cargarse el horario a la persona
	 * que abrio el centro.
	 *
	 * <p>Por eso el backend es DELIBERADAMENTE mas amplio que la pantalla. El frontend ofrece
	 * solo los {@code PROFESIONAL} de la sede en su selector: eso es un <b>default de UX</b>, no
	 * la regla, y hasta este ruling era el unico lugar del sistema donde la regla existia — con
	 * el resultado de que un POST con curl cargaba disponibilidad para la recepcionista y
	 * {@code /efectiva} despues devolvia franjas reales contra ella.
	 *
	 * <p>Lo que si es definitorio es al reves: {@code ADMINISTRATIVO} y {@code PACIENTE} <b>no
	 * atienden pacientes por definicion del rol</b>, y disponibilidad significa exactamente
	 * "horas en las que esta persona atiende pacientes". Un turno reservado contra la recepcion
	 * no es un caso raro que haya que tolerar: es un dato que el motor de agenda de F5 iba a
	 * ofrecer como reservable.
	 *
	 * <p>Y por eso es lista de excluidos y no lista de admitidos: si maniana la matriz suma un
	 * rol clinico nuevo, una lista de admitidos lo dejaria afuera en silencio —sin error visible,
	 * solo un 409 que nadie entiende—, mientras que una lista de excluidos lo admite, que es la
	 * respuesta correcta por defecto para un rol que nadie declaro como "no atiende".
	 */
	private static final Set<String> ROLES_QUE_NO_ATIENDEN = Set.of("ADMINISTRATIVO", "PACIENTE");

	private AutorizacionDeSede() {
		// Utilidad de autorizacion.
	}

	// =================================================================================
	// Contexto
	// =================================================================================

	/**
	 * Exige que el request traiga contexto de organizacion y devuelve esa organizacion.
	 *
	 * <p>Es el control de las LECTURAS: la organizacion nunca viaja por parametro, sale del
	 * contexto que {@code TenantContextFilter} revalido en este request.
	 */
	static long exigirContexto(OperatingActor actor, String operacion) {
		if (actor.contextOrganizationId() == null) {
			log.info("{} sin contexto validado: accountId={}", operacion, actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	/**
	 * Exige ademas que la sede de la RUTA sea la del contexto ya validado. Control de las
	 * MUTACIONES.
	 *
	 * <p>Comparar la sede es mas estricto de lo que la matriz exige —un {@code ORG_ADMIN} tiene
	 * alcance organizacion— y es deliberado, por el mismo motivo que en {@code EspacioService}:
	 * la sede del contexto es la unica que el sistema revalido contra la base, y sin la
	 * comparacion un administrador con contexto en la sede A mutaria el horario de la B
	 * escribiendo otro numero en la URL.
	 */
	static long exigirContextoDeLaSede(OperatingActor actor, long consultorioId, String operacion) {
		if (actor.contextOrganizationId() == null || actor.consultorioId() == null) {
			log.info("{} sin contexto validado: accountId={}", operacion, actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		if (actor.consultorioId() != consultorioId) {
			log.info("{} fuera del contexto del request: accountId={} consultorioId={}",
					operacion, actor.accountId(), consultorioId);
			throw new ConsultorioNotAccessibleException(consultorioId);
		}
		return actor.contextOrganizationId();
	}

	// =================================================================================
	// Sede
	// =================================================================================

	/** La sede del tenant, activa o no. 404 si no existe o es de otro tenant. */
	static ConsultorioSnapshot exigirSedeDelTenant(
			ConsultorioDirectory consultorios, long organizationId, long consultorioId) {

		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNotAccessibleException(consultorioId));
	}

	/**
	 * La sede tiene que estar ACTIVA para recibir horario o excepciones nuevas.
	 *
	 * <p>409 y no 404: la sede existe y el actor la puede leer; lo que no admite la operacion es
	 * su estado. Es RN-M03-003 un nivel mas abajo — una sede inactiva no origina hechos nuevos.
	 * La EDICION y la BAJA no lo exigen a proposito: son las dos operaciones con las que se
	 * ordena el horario de una sede que se esta cerrando, y prohibirlas la dejaria congelada.
	 */
	static ConsultorioSnapshot exigirSedeOperable(
			ConsultorioDirectory consultorios, long organizationId, long consultorioId) {

		ConsultorioSnapshot sede = exigirSedeDelTenant(consultorios, organizationId, consultorioId);
		if (!sede.active()) {
			throw new ConsultorioNotOperableException(consultorioId);
		}
		return sede;
	}

	// =================================================================================
	// Permisos
	// =================================================================================

	/**
	 * Exige un permiso CON LA SEDE COMO ALCANCE.
	 *
	 * <p>Que el alcance sea la sede es lo que hace que no haga falta ningun caso especial
	 * escrito: un {@code CONSULTORIO_ADMIN} pasa sobre la suya y un {@code ORG_ADMIN} sobre
	 * todas, que es lo que dice la matriz §6, y es la formula del evaluador operando. Con
	 * {@code consultorio:manage}, esta sola linea es tambien la que implementa "el profesional no
	 * edita su propia disponibilidad", sin inventar ningun codigo nuevo.
	 */
	static void exigirPermiso(
			PermissionGuard permissionGuard,
			OperatingActor actor,
			String permissionCode,
			long organizationId,
			long consultorioId) {

		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				permissionCode,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}

	// =================================================================================
	// Vinculo
	// =================================================================================

	/**
	 * Exige solo que la membership EXISTA en el tenant, sin mirar si habilita.
	 *
	 * <p>Es la comprobacion de las operaciones que miran hacia atras —listar el horario, darlo de
	 * baja— y la diferencia con {@link #exigirProfesionalQueAtiende} es RN-M05-003: desvincular a
	 * un profesional no borra sus bloques ni su autoria. Exigir un vinculo vigente para LEER
	 * dejaria al administrador sin poder revisar el horario de quien acaba de irse, y exigirlo
	 * para dar de baja lo dejaria sin poder ordenar lo que quedo.
	 */
	static ConsultorioMembershipSnapshot exigirVinculoDelTenant(
			ConsultorioMembershipDirectory vinculos, long organizationId, long membershipId) {

		return vinculos.find(organizationId, membershipId)
				.orElseThrow(() -> new ProfesionalNotAccessibleException(membershipId));
	}

	/**
	 * Exige que la membership exista en el tenant, HABILITE en esa sede y sea de alguien que
	 * ATIENDE PACIENTES.
	 *
	 * <p>Es el control de todo lo que pone disponibilidad en efecto hacia adelante: el alta y la
	 * edicion de un bloque, y el alta de una excepcion con alcance de profesional. Son tres
	 * preguntas y las tres hacen falta:
	 *
	 * <ol>
	 *   <li>Que el vinculo RESUELVA. Si no, 404, como cualquier id ajeno o inexistente.</li>
	 *   <li>Que HABILITE —vigencia, baja logica y estado— y que CUBRA esa sede. Si no, 409.</li>
	 *   <li>Que el rol no sea uno de los que definitivamente no atienden. Si lo es, 409.</li>
	 * </ol>
	 *
	 * <p>Las tres responden con el mismo par de excepciones a proposito: el remedio del
	 * administrador es siempre el mismo —revisar el vinculo del colaborador— y separarlas
	 * convertiria este endpoint en un lector del estado interno de {@code organization} por la
	 * puerta de atras.
	 *
	 * <p>El criterio del punto 3 y su justificacion completa estan en
	 * {@link #ROLES_QUE_NO_ATIENDEN}. En una linea: <b>la lista de solo-{@code PROFESIONAL} del
	 * frontend es un default de UX y no la regla</b>, el backend admite a proposito tambien a
	 * {@code CONSULTORIO_ADMIN} y {@code ORG_ADMIN} —en un centro chico el duenio atiende— y
	 * excluye a {@code ADMINISTRATIVO} y {@code PACIENTE} porque disponibilidad significa "horas
	 * en las que esta persona atiende pacientes".
	 */
	static ConsultorioMembershipSnapshot exigirProfesionalQueAtiende(
			ConsultorioMembershipDirectory vinculos,
			long organizationId,
			long consultorioId,
			long membershipId,
			Instant ahora) {

		ConsultorioMembershipSnapshot vinculo =
				exigirVinculoDelTenant(vinculos, organizationId, membershipId);

		if (!vinculo.validAt(ahora) || !vinculo.cubreConsultorio(consultorioId)) {
			throw new ProfesionalNoVinculadoException(membershipId, consultorioId);
		}
		if (noAtiende(vinculo)) {
			log.info("Disponibilidad rechazada por rol que no atiende: membershipId={} "
					+ "consultorioId={} roleCode={}", membershipId, consultorioId, vinculo.roleCode());
			throw new ProfesionalNoVinculadoException(membershipId, consultorioId);
		}
		return vinculo;
	}

	/** Ver {@link #ROLES_QUE_NO_ATIENDEN}: lista de excluidos, no de admitidos. */
	private static boolean noAtiende(ConsultorioMembershipSnapshot vinculo) {
		return vinculo.roleCode() != null
				&& ROLES_QUE_NO_ATIENDEN.contains(vinculo.roleCode().toUpperCase(java.util.Locale.ROOT));
	}
}
