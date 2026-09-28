package com.akine.activity.application;

import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.EstadoClase;
import com.akine.activity.domain.EstadoInscripcion;
import com.akine.activity.domain.InscripcionClase;
import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.exception.ClaseCompletaException;
import com.akine.activity.domain.exception.ClaseNotAccessibleException;
import com.akine.activity.domain.exception.ConsultorioNoAccesibleException;
import com.akine.activity.domain.exception.InscripcionDuplicadaException;
import com.akine.activity.domain.exception.InscripcionNotAccessibleException;
import com.akine.activity.domain.exception.PersonaNoAccesibleException;
import com.akine.activity.domain.exception.TransicionDeClaseNoPermitidaException;
import com.akine.activity.domain.exception.TransicionDeInscripcionNoPermitidaException;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseProgramadaRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.InscripcionClaseRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Inscripciones, cupos y lista de espera (M28, RF-M28-002, RF-M28-003 y RF-M28-004).
 *
 * <h2>Lo unico que decide la correctitud de esta clase: el cupo es una reserva atomica</h2>
 *
 * <p>N inscripciones simultaneas sobre la ultima vacante es el caso que define si la etapa sirve.
 * <b>Ningun unique puede expresar "quedan vacantes"</b>: no es un valor de columna, es un conteo
 * contra un tope. Contar con un {@code SELECT} y despues insertar deja una ventana entre las dos
 * sentencias, y <b>la ventana es el bug</b>: dos transacciones cuentan 7 de 8, las dos insertan y
 * la clase queda con 9 personas en 8 lugares <b>sin que nada falle</b>. Releer no salva: con
 * {@code REPEATABLE READ} la segunda lectura devuelve la misma foto.
 *
 * <p>Por eso el lugar lo otorga
 * {@link ClaseProgramadaRepositoryPort#tomarCupo}, una sola sentencia condicional cuyo
 * <b>cero filas afectadas significa "no hay lugar"</b>. Es el patron que 07.02 uso al imputar,
 * 04.05 con la ultima unidad de una autorizacion, 07.03 con el saldo de caja y 07.04 con el del
 * lote.
 *
 * <h2>Y la misma carrera al reves: la promocion desde la lista de espera</h2>
 *
 * <p>Dos bajas simultaneas no pueden promover dos veces a la misma persona ni dejar una vacante sin
 * promover. La baja <b>libera el lugar ANTES de leer la cola</b>: ese {@code UPDATE} toma el lock
 * exclusivo de la fila de la clase y lo retiene hasta el commit, asi que la segunda baja espera y
 * lee una cola de la que ya salio el promovido por la primera. Leer la cola antes de liberar es
 * leer una foto vieja — el mismo error que leer antes de bloquear.
 *
 * <p>Encima, {@link InscripcionClaseRepositoryPort#promover} es condicional y su cero significa "ya
 * la promovio otro": defensa en profundidad para cualquier camino futuro que promueva sin pasar por
 * la liberacion.
 *
 * <h2>El orden de locks, que no se negocia</h2>
 *
 * <pre>
 *   agenda_sede  ->  clase_programada  ->  inscripcion_clase
 * </pre>
 *
 * <p><b>Esta clase NO toma el lock de {@code agenda_sede}</b>, y no es por ahorrar: inscribir no
 * ocupa un box ni un profesional —la clase ya los ocupo en 08.01— y tomarlo DESPUES de la fila de
 * la clase seria una inversion de orden contra {@code ClaseService}, o sea un deadlock en
 * produccion que ningun test unitario reproduce.
 *
 * <h2>Lo que esta clase NO hace</h2>
 *
 * <p><b>No crea turnos</b> (CA-M28-001-06). <b>No convierte a nadie en paciente</b>: se inscribe una
 * Persona, y crear un perfil de paciente es de {@code PerfilPacienteService} (RF-M07-010); la
 * derivacion clinica es 08.04. <b>No devenga nada</b>: reservar un lugar no prueba que nadie haya
 * entrenado (RN-M28-007, DP-05), asi que no toca {@code billing} — el cobro de la clase es 08.09 y
 * los creditos 08.07. Y <b>no registra asistencia</b>: eso es 08.03.
 */
@Service
public class InscripcionService {

	private static final Logger log = LoggerFactory.getLogger(InscripcionService.class);

	private static final String ENTIDAD = "INSCRIPCION_CLASE";

	private final InscripcionClaseRepositoryPort inscripciones;
	private final ClaseProgramadaRepositoryPort clases;
	private final CapacidadDeClase capacidad;
	private final AvisosDeClase avisos;
	private final PacienteDirectory personas;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public InscripcionService(
			InscripcionClaseRepositoryPort inscripciones,
			ClaseProgramadaRepositoryPort clases,
			CapacidadDeClase capacidad,
			AvisosDeClase avisos,
			PacienteDirectory personas,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.inscripciones = inscripciones;
		this.clases = clases;
		this.capacidad = capacidad;
		this.avisos = avisos;
		this.personas = personas;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Inscribir (RF-M28-002)
	// =================================================================================

	/**
	 * Inscribe a una persona. Ver la cabecera para el porque del orden.
	 *
	 * @throws ConsultorioNoAccesibleException         sede inexistente o de otro tenant (404)
	 * @throws ClaseNotAccessibleException             clase inexistente o de otro tenant (404)
	 * @throws PersonaNoAccesibleException             persona fuera del padron del tenant (404)
	 * @throws TransicionDeClaseNoPermitidaException   la clase esta cancelada o ya empezo (409)
	 * @throws InscripcionDuplicadaException           esa persona ya esta anotada (409)
	 * @throws ClaseCompletaException                  no hay lugar y no acepto la espera (409)
	 * @throws IdempotencyKeyConflictException         misma clave, pedido distinto (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ResultadoDeInscripcion inscribir(
			OperatingActor actor, long consultorioId, long claseId, InscribirCommand command) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		// LA IDEMPOTENCIA SE EVALUA ANTES DE PEDIR LUGAR Y ANTES DE PEDIR NUMERO. Es la regla del
		// repositorio y aca tiene consecuencia directa: un reintento que pasara primero por
		// tomarCupo consumiria una vacante para despues descubrir que ya existia la inscripcion, y
		// esa vacante no volveria nunca.
		Optional<InscripcionClase> yaInscripta = command.idempotencyKey() == null
				? Optional.empty()
				: inscripciones.findByIdempotencyKey(organizationId, command.idempotencyKey());
		if (yaInscripta.isPresent()) {
			InscripcionClase existente = yaInscripta.get();
			if (!command.huella(claseId).equals(existente.getRequestHash())) {
				throw new IdempotencyKeyConflictException(command.idempotencyKey());
			}
			return new ResultadoDeInscripcion(
					InscripcionView.de(existente),
					cupos(organizationId, consultorioId, claseId),
					false);
		}

		ClaseProgramada clase = exigirClaseInscribible(organizationId, consultorioId, claseId);
		exigirPersonaDelPadron(organizationId, command.personaId());
		exigirNoDuplicada(organizationId, claseId, command.personaId());

		int capacidadEfectiva = capacidad.efectiva(organizationId, consultorioId, clase);
		Instant ahora = Instant.now();

		// EL UNICO LUGAR DONDE SE DECIDE SI HAY CUPO. Una fila = el lugar es suyo; cero = no hay.
		boolean conLugar = clases.tomarCupo(organizationId, claseId, capacidadEfectiva) == 1;

		InscripcionClase nueva;
		if (conLugar) {
			nueva = InscripcionClase.conLugar(
					organizationId, consultorioId, claseId, command.personaId(),
					actor.accountId(), ahora,
					command.idempotencyKey(),
					command.idempotencyKey() == null ? null : command.huella(claseId));
		} else if (command.aceptaListaEspera()) {
			// RN-M28-005: la espera NO consume cupo, y por eso esto va en la rama del cero.
			int posicion = clases.siguientePosicionDeEspera(organizationId, claseId);
			nueva = InscripcionClase.enEspera(
					organizationId, consultorioId, claseId, command.personaId(), posicion,
					actor.accountId(), ahora,
					command.idempotencyKey(),
					command.idempotencyKey() == null ? null : command.huella(claseId));
		} else {
			throw new ClaseCompletaException(
					claseId, capacidadEfectiva, ocupados(organizationId, consultorioId, claseId));
		}

		InscripcionClase guardada = inscripciones.saveAndFlush(nueva);
		auditar(actor, guardada, sede, conLugar ? "INSCRIPCION_RESERVADA" : "INSCRIPCION_EN_ESPERA",
				null, null, ahora);

		log.info("Inscripcion creada: inscripcionId={} claseId={} personaId={} estado={}",
				guardada.getId(), claseId, command.personaId(), guardada.getEstado());

		return new ResultadoDeInscripcion(
				InscripcionView.de(guardada),
				cupos(organizationId, consultorioId, claseId),
				true);
	}

	// =================================================================================
	// Confirmar (RN-M28-004)
	// =================================================================================

	/**
	 * {@code RESERVADA} -> {@code CONFIRMADA}.
	 *
	 * <p><b>No toca el cupo y no toma ningun lock</b>: los dos estados consumen lugar, asi que la
	 * transicion no otorga ni libera nada y no hay nada que serializar. Confirmar tampoco prueba que
	 * nadie haya venido — eso es {@code ASISTIO}, y es de 08.03.
	 */
	@Transactional
	public InscripcionView confirmar(
			OperatingActor actor, long consultorioId, long claseId, long inscripcionId) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		InscripcionClase inscripcion = exigirInscripcion(organizationId, claseId, inscripcionId);
		EstadoInscripcion anterior = inscripcion.getEstado();

		Instant ahora = Instant.now();
		boolean confirmo = inscripcion.confirmar(ahora);
		InscripcionClase confirmada = inscripciones.saveAndFlush(inscripcion);

		if (confirmo) {
			auditar(actor, confirmada, sede, "INSCRIPCION_CONFIRMADA", anterior, null, ahora);
		}
		return InscripcionView.de(confirmada);
	}

	// =================================================================================
	// Cancelar (RF-M28-003) y promover (RF-M28-004)
	// =================================================================================

	/**
	 * Cancela una inscripcion, libera su lugar y promueve a la cabeza de la cola.
	 *
	 * <p><b>El orden de las dos escrituras de cupo no es casual</b> y esta explicado en la cabecera:
	 * liberar ANTES de leer la cola es lo que serializa dos bajas simultaneas.
	 *
	 * <p><b>Es idempotente</b>: cancelar una cancelada devuelve la misma fila sin liberar un segundo
	 * lugar y sin promover a nadie. Cuando 08.07 cuelgue de aca la devolucion de creditos, eso es lo
	 * que impide devolver dos veces.
	 *
	 * <p><b>Cancelar una inscripcion no cancela la clase ni a los demas</b> (CA-M28-003-06).
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ResultadoDeCancelacion cancelar(
			OperatingActor actor,
			long consultorioId,
			long claseId,
			long inscripcionId,
			String motivo) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		InscripcionClase inscripcion = exigirInscripcion(organizationId, claseId, inscripcionId);
		if (inscripcion.getEstado() == EstadoInscripcion.CANCELADA) {
			return new ResultadoDeCancelacion(
					InscripcionView.de(inscripcion), null,
					cupos(organizationId, consultorioId, claseId));
		}

		EstadoInscripcion anterior = inscripcion.getEstado();
		boolean liberaLugar = inscripcion.consumeCupo();

		Instant ahora = Instant.now();
		inscripcion.cancelar(motivo, actor.accountId(), ahora);
		InscripcionClase cancelada = inscripciones.saveAndFlush(inscripcion);
		InscripcionView vistaCancelada = InscripcionView.de(cancelada);
		auditar(actor, cancelada, sede, "INSCRIPCION_CANCELADA", anterior, motivo, ahora);

		InscripcionView promovida = null;
		if (liberaLugar) {
			// PASO 1: liberar. Toma el lock exclusivo de la fila de la clase y lo retiene hasta el
			// commit — es lo que hace que dos bajas simultaneas se serialicen aca.
			clases.liberarCupo(organizationId, claseId);
			// PASO 2: recien ahora leer la cola. Antes seria leer una foto vieja.
			promovida = promoverSiguiente(actor, sede, consultorioId, claseId, ahora);
		}

		log.info("Inscripcion cancelada: inscripcionId={} claseId={} liberoLugar={} promovio={}",
				inscripcionId, claseId, liberaLugar, promovida != null);

		return new ResultadoDeCancelacion(
				vistaCancelada, promovida, cupos(organizationId, consultorioId, claseId));
	}

	/**
	 * Le da el lugar liberado a quien espera hace mas tiempo (RF-M28-004, RF-M26-007).
	 *
	 * <p>Corre <b>bajo el lock que {@code liberarCupo} acaba de tomar</b>, asi que la cola que lee
	 * ya refleja las promociones de cualquier baja anterior.
	 *
	 * <p>Las dos salidas sin promocion son distintas y las dos son correctas:
	 * <ul>
	 *   <li>{@code tomarCupo} devuelve cero: la capacidad efectiva bajo por debajo de lo ocupado
	 *       —el box se cambio por uno mas chico— y el lugar que se libero no existe. Prometerselo a
	 *       alguien seria peor que no promover.</li>
	 *   <li>{@code promover} devuelve cero: otro ya la saco de la cola. Se <b>devuelve</b> el lugar
	 *       tomado y no se promueve a nadie, para que quede libre para la proxima inscripcion.</li>
	 * </ul>
	 */
	private InscripcionView promoverSiguiente(
			OperatingActor actor,
			ConsultorioSnapshot sede,
			long consultorioId,
			long claseId,
			Instant ahora) {

		long organizationId = sede.organizationId();
		Optional<InscripcionClase> cabeza = inscripciones.siguienteEnEspera(organizationId, claseId);
		if (cabeza.isEmpty()) {
			return null;
		}
		ClaseProgramada clase = clases.findByIdInScope(organizationId, consultorioId, claseId)
				.orElseThrow(() -> new ClaseNotAccessibleException(claseId));
		if (clase.getEstado() != EstadoClase.PROGRAMADA) {
			return null;
		}

		int capacidadEfectiva = capacidad.efectiva(organizationId, consultorioId, clase);
		if (clases.tomarCupo(organizationId, claseId, capacidadEfectiva) != 1) {
			return null;
		}

		long candidataId = cabeza.get().getId();
		if (inscripciones.promover(organizationId, candidataId, ahora) != 1) {
			clases.liberarCupo(organizationId, claseId);
			log.info("Promocion ya resuelta por otra operacion: inscripcionId={}", candidataId);
			return null;
		}

		InscripcionClase promovida = exigirInscripcion(organizationId, claseId, candidataId);
		auditar(actor, promovida, sede, "INSCRIPCION_PROMOVIDA",
				EstadoInscripcion.LISTA_ESPERA, null, ahora);
		avisos.avisarCupoLiberado(clase, sede.name(), sede.timezone(), promovida);

		log.info("Promovida desde lista de espera: inscripcionId={} claseId={} posicion={}",
				candidataId, claseId, promovida.getPosicionEspera());

		return InscripcionView.de(promovida);
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * La lista de participantes. Exige {@code inscripcion:read}, <b>que no es {@code clase:read}</b>:
	 * quien mira la grilla del dia no necesita saber quien esta anotado.
	 */
	@Transactional(readOnly = true)
	public List<ParticipanteView> participantes(
			OperatingActor actor, long consultorioId, long claseId) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.INSCRIPCION_READ);
		exigirClaseDelTenant(organizationId, consultorioId, claseId);

		List<InscripcionClase> todas = inscripciones.findDeLaClase(organizationId, claseId);
		// Un solo batch: resolver los nombres de a uno convierte la lista en tantas consultas como
		// participantes haya, que crece justo con lo llena que este la clase.
		Map<Long, PacienteSnapshot> porPersona = personas.findAll(
				organizationId, todas.stream().map(InscripcionClase::getPersonaId).toList());

		return todas.stream()
				.map(inscripcion -> participante(inscripcion, porPersona.get(inscripcion.getPersonaId())))
				.toList();
	}

	/** Los cupos de una clase (RF-M12-010). Exige {@code clase:read}: no devuelve ninguna persona. */
	@Transactional(readOnly = true)
	public CuposView cupos(OperatingActor actor, long consultorioId, long claseId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.CLASE_READ);

		return cupos(organizationId, consultorioId, claseId);
	}

	// =================================================================================
	// Reglas
	// =================================================================================

	private ClaseProgramada exigirClaseInscribible(
			long organizationId, long consultorioId, long claseId) {

		ClaseProgramada clase = exigirClaseDelTenant(organizationId, consultorioId, claseId);
		if (clase.getEstado() != EstadoClase.PROGRAMADA || !clase.estaViva()) {
			throw new TransicionDeClaseNoPermitidaException(
					claseId, "esta cancelada y no admite inscripciones");
		}
		// Una clase que ya empezo no se sigue llenando desde el sistema: quien llega tarde entra
		// por la puerta, no por la API. 08.03 registra lo que paso adentro.
		if (!clase.getInicio().isAfter(Instant.now())) {
			throw new TransicionDeClaseNoPermitidaException(claseId, "ya empezo");
		}
		return clase;
	}

	private ClaseProgramada exigirClaseDelTenant(
			long organizationId, long consultorioId, long claseId) {

		return clases.findByIdInScope(organizationId, consultorioId, claseId)
				.orElseThrow(() -> new ClaseNotAccessibleException(claseId));
	}

	/**
	 * <b>Persona, no paciente.</b> No se exige {@code esPacienteVigente}: anotarse en una clase no
	 * es entrar al circuito clinico (RF-M07-010).
	 *
	 * <p>La ficha dada de baja si es un conflicto, y no un 404: el padron devuelve las fichas de
	 * baja justamente para que el consumidor pueda distinguir "no es tuya" de "no esta vigente".
	 */
	private void exigirPersonaDelPadron(long organizationId, long personaId) {
		PacienteSnapshot persona = personas.find(organizationId, personaId)
				.orElseThrow(() -> new PersonaNoAccesibleException(personaId));
		if (!persona.activa()) {
			throw new TransicionDeInscripcionNoPermitidaException(
					null, "la ficha de la persona esta dada de baja");
		}
	}

	/**
	 * Validacion obligatoria de RF-M28-002: no duplicar inscripcion activa.
	 *
	 * <p>Esta consulta da el 409 explicable; el unique {@code uk_inscripcion_clase_persona} cubre la
	 * carrera entre dos altas simultaneas de la misma persona. <b>Hacen falta las dos</b>: una sola
	 * consulta deja la ventana, y un unique solo deja un error de constraint que la pantalla no sabe
	 * traducir.
	 */
	private void exigirNoDuplicada(long organizationId, long claseId, long personaId) {
		inscripciones.findVivaDePersona(organizationId, claseId, personaId)
				.ifPresent(existente -> {
					throw new InscripcionDuplicadaException(claseId, personaId, existente.getId());
				});
	}

	private InscripcionClase exigirInscripcion(
			long organizationId, long claseId, long inscripcionId) {

		return inscripciones.findByIdInScope(organizationId, claseId, inscripcionId)
				.orElseThrow(() -> new InscripcionNotAccessibleException(inscripcionId));
	}

	private CuposView cupos(long organizationId, long consultorioId, long claseId) {
		ClaseProgramada clase = exigirClaseDelTenant(organizationId, consultorioId, claseId);
		return CuposView.de(
				claseId,
				clase.getCapacidad(),
				capacidad.efectiva(organizationId, consultorioId, clase),
				clase.getCupoOcupado(),
				inscripciones.contarEnEspera(organizationId, claseId));
	}

	private int ocupados(long organizationId, long consultorioId, long claseId) {
		return exigirClaseDelTenant(organizationId, consultorioId, claseId).getCupoOcupado();
	}

	private static ParticipanteView participante(
			InscripcionClase inscripcion, PacienteSnapshot persona) {

		// Una persona que no vuelve del padron no rompe la lista: la inscripcion existe y hay que
		// poder verla. Que falte el nombre es un dato, no un error.
		return new ParticipanteView(
				InscripcionView.de(inscripcion),
				persona == null ? null : persona.apellido(),
				persona == null ? null : persona.nombre(),
				persona == null ? null : persona.tipoDocumento(),
				persona == null ? null : persona.numeroDocumento());
	}

	// =================================================================================
	// Auditoria
	// =================================================================================

	/** Se escribe DENTRO de la transaccion: un registro post-commit que falla deja la transicion sin rastro. */
	private void auditar(
			OperatingActor actor,
			InscripcionClase inscripcion,
			ConsultorioSnapshot sede,
			String evento,
			EstadoInscripcion anterior,
			String motivo,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				inscripcion.getOrganizationId(),
				sede.id(),
				actor.accountId(),
				evento,
				ENTIDAD,
				inscripcion.getId(),
				anterior == null ? null : anterior.name(),
				inscripcion.getEstado().name(),
				Map.of("claseId", String.valueOf(inscripcion.getClaseId()),
						"personaId", String.valueOf(inscripcion.getPersonaId())),
				motivo,
				null,
				ahora));
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException(
					"La gestion de inscripciones requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	private void exigirGestion(OperatingActor actor, long organizationId, long consultorioId) {
		exigir(actor, organizationId, consultorioId, PermissionCodes.INSCRIPCION_MANAGE);
	}

	private void exigir(
			OperatingActor actor, long organizationId, long consultorioId, String permiso) {

		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), permiso, organizationId, consultorioId, null, Instant.now()));
	}
}
