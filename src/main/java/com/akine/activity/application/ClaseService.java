package com.akine.activity.application;

import com.akine.activity.domain.ClaseEvento;
import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.EstadoClase;
import com.akine.activity.domain.InscripcionClase;
import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.TipoEventoClase;
import com.akine.activity.domain.exception.CapacidadNoAdmitidaException;
import com.akine.activity.domain.exception.ClaseNoProgramableException;
import com.akine.activity.domain.exception.ClaseNotAccessibleException;
import com.akine.activity.domain.exception.ConsultorioNoAccesibleException;
import com.akine.activity.domain.exception.HorarioNoDisponibleException;
import com.akine.activity.domain.exception.OfertaNotAccessibleException;
import com.akine.activity.domain.exception.RecursoOcupadoException;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseEventoRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseProgramadaRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.InscripcionClaseRepositoryPort;
import com.akine.offering.spi.HabilitacionSnapshot;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.spi.DisponibilidadDirectory;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import com.akine.scheduling.spi.AgendaDeSede;
import com.akine.scheduling.spi.OcupacionDeAgenda;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Programar, reprogramar y cancelar clases (M28, RF-M28-001, RF-M28-005 y RF-M28-006).
 *
 * <h2>Lo unico que decide la correctitud de esta clase: la exclusion contra el turno</h2>
 *
 * <p>Una clase ocupa un espacio y un profesional durante una franja, <b>exactamente igual que un
 * turno</b>. Si la clase no participara del mismo mecanismo de exclusion, se reservaria un turno
 * encima de una clase y <b>nadie se enteraria</b>: ningun unique puede expresar un solapamiento de
 * intervalos —09:00-10:00 y 09:30-10:00 no comparten un valor de columna— y MySQL 8.4 no tiene
 * exclusion constraints.
 *
 * <p>Por eso esta clase <b>se disputa la misma fila de {@code agenda_sede} que las reservas de
 * turno</b>, prestada por {@code scheduling} a traves de {@link AgendaDeSede}. No hay un segundo
 * punto de serializacion: dos locks distintos no se ven entre si, las dos transacciones ganarian
 * y el box quedaria doblemente vendido sin que nada falle.
 *
 * <h2>El orden, que es el mismo de {@code TurnoService} y por los mismos motivos</h2>
 *
 * <pre>
 *   0. asegurar la fila-lock      &lt;- en su PROPIA transaccion (REQUIRES_NEW)
 *   1. LOCK de agenda_sede        &lt;- ANTES de leer una sola clase o un solo turno
 *   2. idempotencia               &lt;- bajo el lock
 *   3. oferta GRUPAL, profesional, espacio
 *   4. capacidad efectiva
 *   5. solapamiento contra TURNOS y contra CLASES
 *   6. INSERT + historial + auditoria
 * </pre>
 *
 * <p><b>El paso 0 no puede ir adentro:</b> crear la fila-lock perezosamente dentro de la
 * transaccion que la bloquea produce un DEADLOCK entre las primeras N escrituras de una sede, y el
 * {@code try/catch} no salva —atrapar una excepcion de persistencia no des-marca la transaccion y
 * Spring lanza {@code UnexpectedRollbackException} al commitear—.
 *
 * <p><b>El paso 1 va primero:</b> leer y despues bloquear es una escalada S -&gt; X sobre las
 * mismas filas y dos transacciones concurrentes se esperan mutuamente.
 *
 * <p><b>Y todo en {@code READ_COMMITTED}:</b> con {@code REPEATABLE READ} InnoDB fija la foto en la
 * primera lectura consistente, que ocurre antes del lock, y el lock deja de servir para lo unico
 * que sirve.
 *
 * <h2>Lo que esta clase NO hace</h2>
 *
 * <p><b>No crea turnos.</b> CA-M28-001-06: la clase existe una sola vez cualquiera sea el numero de
 * participantes. <b>No inscribe a nadie</b> —08.02— ni <b>registra asistencia</b> —08.03— ni
 * <b>devenga nada</b>: una clase programada es reserva de recursos, no prestacion (DP-05,
 * RN-M28-007). Y <b>no notifica</b>: avisar a los inscriptos de una reprogramacion no tiene
 * sentido antes de que existan inscriptos, y es 08.02.
 */
@Service
public class ClaseService {

	private static final Logger log = LoggerFactory.getLogger(ClaseService.class);

	/** Tipo de entidad en la auditoria transversal. */
	private static final String ENTIDAD = "CLASE_PROGRAMADA";

	private final ClaseProgramadaRepositoryPort clases;
	private final ClaseEventoRepositoryPort eventos;
	private final InscripcionClaseRepositoryPort inscripciones;
	private final AvisosDeClase avisos;
	private final AgendaDeSede agenda;
	private final OfertaDirectory ofertas;
	private final DisponibilidadDirectory disponibilidad;
	private final EspacioDirectory espacios;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public ClaseService(
			ClaseProgramadaRepositoryPort clases,
			ClaseEventoRepositoryPort eventos,
			InscripcionClaseRepositoryPort inscripciones,
			AvisosDeClase avisos,
			AgendaDeSede agenda,
			OfertaDirectory ofertas,
			DisponibilidadDirectory disponibilidad,
			EspacioDirectory espacios,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.clases = clases;
		this.eventos = eventos;
		this.inscripciones = inscripciones;
		this.avisos = avisos;
		this.agenda = agenda;
		this.ofertas = ofertas;
		this.disponibilidad = disponibilidad;
		this.espacios = espacios;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Programar (RF-M12-009, RF-M28-001)
	// =================================================================================

	/**
	 * Programa una clase. Ver la cabecera de la clase para el orden y su motivo.
	 *
	 * @throws ConsultorioNoAccesibleException sede inexistente o de otro tenant (404)
	 * @throws OfertaNotAccessibleException    oferta inexistente en esa sede (404)
	 * @throws ClaseNoProgramableException     oferta no grupal, de baja o fuera de vigencia (409)
	 * @throws CapacidadNoAdmitidaException    la capacidad pedida no es sostenible (409)
	 * @throws HorarioNoDisponibleException    el profesional o el espacio no sirven ese horario (409)
	 * @throws RecursoOcupadoException         profesional o espacio ya tomados, por turno o clase (409)
	 * @throws IdempotencyKeyConflictException misma clave, pedido distinto (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ResultadoDeProgramacion programar(
			OperatingActor actor, long consultorioId, long ofertaId, ProgramarClaseCommand command) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		// PASO 0. Corre en su PROPIA transaccion (REQUIRES_NEW, declarado del otro lado del spi) y
		// va ANTES de tomar el lock. Es un bean distinto, asi que el proxy de Spring si aplica la
		// propagacion: un metodo de ESTA clase llamado desde ESTA clase se saltea el proxy y la
		// anotacion no hace nada. Ver la cabecera para por que no puede ir adentro.
		agenda.asegurar(organizationId, consultorioId);

		// PASO 1. Antes de leer nada.
		agenda.bloquear(organizationId, consultorioId);

		// PASO 2. Bajo el lock, para que dos peticiones con la misma clave no creen dos clases.
		// Fuera del lock las dos leerian "no existe" antes de que ninguna inserte.
		Optional<ClaseProgramada> yaCreada = command.idempotencyKey() == null
				? Optional.empty()
				: clases.findByIdempotencyKey(organizationId, command.idempotencyKey());
		if (yaCreada.isPresent()) {
			ClaseProgramada existente = yaCreada.get();
			String huella = command.huella(consultorioId, ofertaId);
			if (!huella.equals(existente.getRequestHash())) {
				throw new IdempotencyKeyConflictException(command.idempotencyKey());
			}
			return new ResultadoDeProgramacion(proyectar(organizationId, consultorioId, existente), false);
		}

		OfertaSnapshot oferta = exigirOfertaGrupalVigente(
				organizationId, consultorioId, sede, ofertaId, command.inicio());

		Long profesionalId = resolverProfesional(
				organizationId, consultorioId, sede, oferta, command.profesionalId(),
				command.inicio(), command.fin());

		Long espacioId = oferta.requiereEspacio()
				? elegirEspacio(organizationId, consultorioId, oferta, command.inicio(),
						command.fin(), null)
				: null;

		exigirCapacidadSostenible(
				organizationId, oferta, espacioId, command.capacidad(), command.inicio(), 0);

		exigirRecursosLibres(
				organizationId, consultorioId, profesionalId, espacioId,
				command.inicio(), command.fin(), null);

		Instant ahora = Instant.now();
		ClaseProgramada clase = clases.save(new ClaseProgramada(
				organizationId, consultorioId, ofertaId, profesionalId, espacioId,
				command.titulo(), command.inicio(), command.fin(), command.capacidad(),
				actor.accountId(), ahora,
				command.idempotencyKey(),
				command.idempotencyKey() == null ? null : command.huella(consultorioId, ofertaId)));

		eventos.registrar(ClaseEvento.de(
				clase, TipoEventoClase.CREACION, null, null, actor.accountId(), ahora));
		auditar(actor, clase, "CLASE_PROGRAMADA", null, null, ahora);

		log.info("Clase programada: claseId={} consultorioId={} ofertaId={} inicio={} capacidad={}",
				clase.getId(), consultorioId, ofertaId, command.inicio(), command.capacidad());

		return new ResultadoDeProgramacion(
				proyectar(organizationId, consultorioId, clase), true);
	}

	// =================================================================================
	// Reprogramar (RF-M12-012, RF-M28-005)
	// =================================================================================

	/**
	 * Mueve una clase. <b>Conserva la clase y sus participantes</b> (CA-M28-005-06).
	 *
	 * <p>Es un {@code UPDATE} sobre la misma fila, no un par cancelada/nueva: las inscripciones de
	 * 08.02 van a colgar de este id y un reemplazo las perderia a todas.
	 *
	 * <p><b>Control optimista por la {@code version} de la propia fila, sin force-increment.</b> La
	 * escritura toca columnas del agregado, asi que la version avanza una sola vez sola. Forzarla
	 * ademas la haria avanzar DOS, la respuesta llevaria {@code leida+1} y el cliente que mande esa
	 * version se come un 409 del que no puede salir: es la reciproca que 04.02 pago.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ClaseView reprogramar(
			OperatingActor actor, long consultorioId, long claseId, ReprogramarClaseCommand command) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		// Mismo orden que programar, y por los mismos motivos. Ver la cabecera.
		agenda.asegurar(organizationId, consultorioId);
		agenda.bloquear(organizationId, consultorioId);

		// FOR UPDATE, y no un SELECT plano (AKINE-08.02). Es lo unico que cierra la carrera entre
		// bajar la capacidad y una inscripcion simultanea: sin el, se lee la ocupacion, otra
		// transaccion toma el ultimo lugar y esta escribe una capacidad menor, dejando
		// cupo_ocupado > capacidad. Con la fila bloqueada, ese tomarCupo espera y despues evalua su
		// LEAST contra la capacidad NUEVA. El lock de agenda_sede no sirve para esto: una
		// inscripcion no lo toma, y no debe tomarlo — seria una inversion de orden.
		ClaseProgramada clase = clases.lockByIdInScope(organizationId, consultorioId, claseId)
				.orElseThrow(() -> new ClaseNotAccessibleException(claseId));
		exigirVersion(clase, command.version());

		OfertaSnapshot oferta = exigirOfertaGrupalVigente(
				organizationId, consultorioId, sede, clase.getOfertaId(), command.inicio());

		Long profesionalId = resolverProfesional(
				organizationId, consultorioId, sede, oferta, command.profesionalId(),
				command.inicio(), command.fin());

		// Se excluye a si misma: sigue viva en su intervalo viejo mientras se la revalida, y sin
		// excluirla una clase que se corre media hora chocaria contra si misma. Es el mismo
		// turnoExcluidoId de RevalidadorDeSlot.
		Long espacioId = oferta.requiereEspacio()
				? elegirEspacio(organizationId, consultorioId, oferta, command.inicio(),
						command.fin(), claseId)
				: null;

		int ocupados = contarOcupacion(clase);
		exigirCapacidadSostenible(
				organizationId, oferta, espacioId, command.capacidad(), command.inicio(), ocupados);

		exigirRecursosLibres(
				organizationId, consultorioId, profesionalId, espacioId,
				command.inicio(), command.fin(), claseId);

		Instant inicioAnterior = clase.getInicio();
		Instant finAnterior = clase.getFin();
		int capacidadAnterior = clase.getCapacidad();

		Instant ahora = Instant.now();
		clase.reprogramar(command.inicio(), command.fin(), profesionalId, espacioId,
				command.capacidad(), ahora);
		// saveAndFlush y no save: con save a secas la version la incrementa Hibernate al commitear,
		// la vista se arma ANTES y sale con la version vieja. El cliente manda esa version en la
		// operacion siguiente y come un 409 que no le echa la culpa a nadie. Ya se pago con M12.
		ClaseProgramada movida = clases.saveAndFlush(clase);

		eventos.registrar(ClaseEvento.reprogramacion(
				movida, inicioAnterior, finAnterior, capacidadAnterior, actor.accountId(), ahora));
		auditar(actor, movida, "CLASE_REPROGRAMADA", movida.getEstado(), null, ahora);

		// RF-M26-006 (AKINE-08.02). Solo si el horario efectivamente se movio: avisar de una
		// reprogramacion que no cambio la hora es ruido, y el ruido hace que se dejen de leer los
		// avisos que si importan.
		if (!inicioAnterior.equals(command.inicio()) || !finAnterior.equals(command.fin())) {
			avisos.avisarCambioDeClase(
					movida, sede.name(), sede.timezone(),
					inscripciones.findVivasDeLaClase(organizationId, claseId), ahora);
		}

		log.info("Clase reprogramada: claseId={} consultorioId={} inicio={} -> {}",
				claseId, consultorioId, inicioAnterior, command.inicio());

		return proyectar(organizationId, consultorioId, movida);
	}

	// =================================================================================
	// Cancelar (RF-M12-012, RF-M28-006)
	// =================================================================================

	/**
	 * Cancela una clase. <b>Idempotente</b> (CA-M28-006-06).
	 *
	 * <p>No toma el lock de la sede, y no es un descuido: cancelar no OCUPA nada, libera. Dos
	 * cancelaciones concurrentes de la misma clase no pueden corromper nada que el control
	 * optimista de la fila no cubra, y serializar toda la sede para liberar un horario solo
	 * agregaria contencion. Es el mismo criterio con el que {@code TurnoService.confirmar} se
	 * abstiene del lock.
	 *
	 * <p><b>La idempotencia no es comodidad de pantalla.</b> Cuando 08.02 y 08.07 cuelguen de esta
	 * operacion la devolucion de creditos y las reversas economicas, una segunda ejecucion que no
	 * fuera idempotente devolveria plata dos veces. La regla se fija aca, antes que el dinero que
	 * protege.
	 */
	@Transactional
	public ClaseView cancelar(
			OperatingActor actor, long consultorioId, long claseId, String motivo) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		// FOR UPDATE por lo mismo que reprogramar (AKINE-08.02): esta operacion pone el contador de
		// cupo en cero, y hacerlo mientras alguien toma el ultimo lugar dejaria un recibo sin
		// contador. Con la fila bloqueada, esa inscripcion espera y despues falla contra
		// estado = 'CANCELADA'.
		ClaseProgramada clase = clases.lockByIdInScope(organizationId, consultorioId, claseId)
				.orElseThrow(() -> new ClaseNotAccessibleException(claseId));

		EstadoClase anterior = clase.getEstado();
		Instant ahora = Instant.now();
		boolean cancelo = clase.cancelar(motivo, actor.accountId(), ahora);
		ClaseProgramada cancelada = clases.saveAndFlush(clase);

		// Solo si hubo transicion. Un evento por cada reintento llenaria el historial de filas que
		// no cuentan ningun hecho nuevo, y haria que una auditoria mostrara dos cancelaciones de
		// algo que se cancelo una vez. Y desde 08.02 hace ademas que una segunda ejecucion no
		// vuelva a notificar a todos los participantes de algo que ya les avisamos.
		if (cancelo) {
			eventos.registrar(ClaseEvento.de(
					cancelada, TipoEventoClase.CANCELACION, anterior, motivo,
					actor.accountId(), ahora));
			auditar(actor, cancelada, "CLASE_CANCELADA", anterior, motivo, ahora);
			resolverInscripcionesDeClaseCancelada(
					actor, sede, cancelada, claseId, motivo, ahora);
			log.info("Clase cancelada: claseId={} consultorioId={}", claseId, consultorioId);
		}

		return proyectarReleyendo(organizationId, consultorioId, cancelada);
	}

	/**
	 * Resuelve las inscripciones de una clase que se acaba de cancelar (RF-M28-006 paso 6, que
	 * 08.01 difirio a esta etapa).
	 *
	 * <p>Cancela a todos —los que tenian lugar y los que esperaban—, pone el contador en cero para
	 * que el invariante siga cerrando, y avisa a cada uno por separado (RF-M26-006).
	 *
	 * <p><b>No promueve a nadie</b>: no hay clase a la que promover. Y <b>no devuelve creditos ni
	 * plata</b> —paso 7 de RF-M28-006—: eso es 08.07, y lo que esta etapa le deja es una operacion
	 * idempotente de la que colgarlo sin devolver dos veces.
	 *
	 * <p>La lista de afectados se lee ANTES del {@code UPDATE} masivo, que es lo unico que permite
	 * saber a quien avisarle: despues ya estan todos en {@code CANCELADA} y son indistinguibles de
	 * los que se habian dado de baja por su cuenta.
	 */
	private void resolverInscripcionesDeClaseCancelada(
			OperatingActor actor,
			ConsultorioSnapshot sede,
			ClaseProgramada cancelada,
			long claseId,
			String motivo,
			Instant ahora) {

		long organizationId = sede.organizationId();
		List<InscripcionClase> afectadas = inscripciones.findVivasDeLaClase(organizationId, claseId);
		if (afectadas.isEmpty()) {
			return;
		}
		// El aviso se encola ANTES del UPDATE masivo porque ese UPDATE limpia el contexto de
		// persistencia, y las entidades que la notificacion necesita quedarian detachadas.
		avisos.avisarCambioDeClase(cancelada, sede.name(), sede.timezone(), afectadas, ahora);

		int canceladas = inscripciones.cancelarTodasPorClaseCancelada(
				organizationId, claseId, "Clase cancelada: " + motivo, actor.accountId(), ahora);
		clases.vaciarCupo(organizationId, claseId);

		log.info("Inscripciones resueltas por cancelacion de clase: claseId={} canceladas={}",
				claseId, canceladas);
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/** Detalle de una clase. Exige {@code clase:read}: mirar la grilla no es programar. */
	@Transactional(readOnly = true)
	public ClaseView ver(OperatingActor actor, long consultorioId, long claseId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirLectura(actor, organizationId, consultorioId);

		ClaseProgramada clase = clases.findByIdInScope(organizationId, consultorioId, claseId)
				.orElseThrow(() -> new ClaseNotAccessibleException(claseId));
		return proyectar(organizationId, consultorioId, clase);
	}

	/**
	 * Clases de la sede en una ventana. <b>Incluye las canceladas</b>: alguien puede presentarse a
	 * una clase que se cancelo, y esconderla deja al mostrador sin nada que decirle.
	 */
	@Transactional(readOnly = true)
	public List<ClaseView> listar(
			OperatingActor actor, long consultorioId, Instant desde, Instant hasta) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirLectura(actor, organizationId, consultorioId);

		return clases.findDeLaSedeEnVentana(organizationId, consultorioId, desde, hasta).stream()
				.map(clase -> proyectar(organizationId, consultorioId, clase))
				.toList();
	}

	/** Historial de una clase (RN-M28-009). Append-only. */
	@Transactional(readOnly = true)
	public List<EventoDeClaseView> historial(
			OperatingActor actor, long consultorioId, long claseId) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirLectura(actor, organizationId, consultorioId);

		clases.findByIdInScope(organizationId, consultorioId, claseId)
				.orElseThrow(() -> new ClaseNotAccessibleException(claseId));

		return eventos.historial(organizationId, claseId).stream()
				.map(EventoDeClaseView::de)
				.toList();
	}

	// =================================================================================
	// Reglas
	// =================================================================================

	/** RN-M28-001: la clase cuelga de una oferta GRUPAL, activa y vigente ESE dia. */
	private OfertaSnapshot exigirOfertaGrupalVigente(
			long organizationId,
			long consultorioId,
			ConsultorioSnapshot sede,
			long ofertaId,
			Instant inicio) {

		OfertaSnapshot oferta = ofertas.find(organizationId, consultorioId, ofertaId)
				.orElseThrow(() -> new OfertaNotAccessibleException(ofertaId));

		// GRUPAL por modalidad y no por capacidad: V24 obliga a que una GRUPAL tenga capacidad
		// mayor a 1, pero NO la reciproca. Una oferta individual en un box de dos camillas puede
		// tener capacidad 2 y sigue siendo individual; deducirlo del cupo dejaria programar clases
		// sobre ofertas individuales.
		if (!oferta.grupal()) {
			throw new ClaseNoProgramableException(ofertaId, "no es una oferta grupal");
		}

		// Dia por dia y no contra la ventana: ruling R13 de 02.04. Una oferta que vence el 15 no
		// puede sostener una clase el 20 porque la consulta abarco todo el mes.
		LocalDate fecha = inicio.atZone(ZoneId.of(sede.timezone())).toLocalDate();
		if (!oferta.vigenteEl(fecha)) {
			throw new ClaseNoProgramableException(ofertaId, oferta.active()
					? "no esta vigente el " + fecha
					: "esta dada de baja");
		}
		return oferta;
	}

	/**
	 * Resuelve y revalida el profesional.
	 *
	 * <p>Los mismos tres controles que {@code RevalidadorDeSlot} aplica a un turno: habilitado para
	 * la oferta, vigente ese dia (02.07) y con el intervalo DENTRO de una franja de su
	 * disponibilidad efectiva. No se reusa aquella clase porque vive en {@code scheduling.application},
	 * que es privado de su modulo; lo que si se reusa son las reglas que la sostienen, que viven en
	 * {@code offering.spi} y {@code resource.spi}.
	 */
	private Long resolverProfesional(
			long organizationId,
			long consultorioId,
			ConsultorioSnapshot sede,
			OfertaSnapshot oferta,
			Long profesionalId,
			Instant inicio,
			Instant fin) {

		if (!oferta.requiereProfesional()) {
			return null;
		}
		if (profesionalId == null) {
			throw new HorarioNoDisponibleException(
					"la oferta exige profesional y no se indico ninguno");
		}

		// LISTA VACIA SIGNIFICA TODOS, NO NINGUNO. Es la regla de V28 y la decision con mas
		// consecuencias de 02.07: una oferta recien creada no tiene filas de habilitacion, y si
		// eso significara "nadie puede prestarla", toda clase contra ella se rechazaria. Se pierde
		// con facilidad al copiar el patron, porque un anyMatch sobre una lista vacia da false y
		// parece correcto.
		List<HabilitacionSnapshot> habilitaciones = ofertas.profesionalesHabilitados(
				organizationId, consultorioId, oferta.id());
		boolean habilitado = habilitaciones.isEmpty()
				|| habilitaciones.stream()
						.filter(habilitacion -> habilitacion.recursoId() == profesionalId)
						.anyMatch(habilitacion -> habilitacion.vigenteEn(inicio));
		if (!habilitado) {
			throw new HorarioNoDisponibleException(
					"el profesional no esta habilitado para esta oferta en esa fecha");
		}

		LocalDate fecha = inicio.atZone(ZoneId.of(sede.timezone())).toLocalDate();
		boolean dentroDeFranja = disponibilidad
				.efectiva(organizationId, sede, profesionalId, fecha, fecha.plusDays(1))
				.stream()
				.flatMap(dia -> dia.franjas().stream())
				// Contiene, no se cruza: media clase fuera del horario no es una clase valida.
				.anyMatch(franja -> !franja.desde().isAfter(inicio) && !franja.hasta().isBefore(fin));
		if (!dentroDeFranja) {
			throw new HorarioNoDisponibleException("el profesional no atiende en ese horario");
		}

		return profesionalId;
	}

	/**
	 * Elige el primer espacio habilitado, en servicio y libre.
	 *
	 * <p><b>El primero y no el mejor</b>, igual que en M12: cualquier criterio de reparto exige
	 * leer mas estado dentro del lock que serializa toda la sede, y ninguna regla de negocio lo
	 * pide. Un criterio se agrega despues; la contencion no se saca.
	 *
	 * <p>Que el box este libre lo decide {@link #exigirRecursosLibres}, que mira turnos <b>y</b>
	 * clases. Aca solo se descartan los que ya tienen algo encima, para no elegir uno condenado.
	 */
	private Long elegirEspacio(
			long organizationId,
			long consultorioId,
			OfertaSnapshot oferta,
			Instant inicio,
			Instant fin,
			Long claseExcluidaId) {

		List<HabilitacionSnapshot> habilitados = ofertas.espaciosHabilitados(
				organizationId, consultorioId, oferta.id());

		List<Long> candidatos = habilitados.isEmpty()
				// Lista vacia significa TODOS: misma regla de V28 que para los profesionales.
				? espacios.enServicio(organizationId, consultorioId, inicio, fin).stream()
						.filter(EspacioSnapshot::active)
						.filter(EspacioSnapshot::enServicio)
						.map(EspacioSnapshot::id)
						.toList()
				: habilitados.stream()
						.filter(habilitacion -> habilitacion.vigenteEn(inicio))
						.map(HabilitacionSnapshot::recursoId)
						.filter(espacioId -> espacios.find(organizationId, espacioId, inicio)
								.filter(EspacioSnapshot::active)
								.filter(EspacioSnapshot::enServicio)
								.filter(candidato -> candidato.consultorioId() == consultorioId)
								.isPresent())
						.toList();

		return candidatos.stream()
				.filter(espacioId -> espacioLibre(
						organizationId, espacioId, inicio, fin, claseExcluidaId))
				.findFirst()
				// Se distingue de horario-no-disponible: el hueco existe y el profesional atiende;
				// lo que falta es un box. La pantalla puede ofrecer otro horario en vez de mandar
				// a recargar la grilla.
				.orElseThrow(() -> new RecursoOcupadoException("espacio"));
	}

	/**
	 * RN-M28-002: la clase tiene capacidad propia <b>limitada ademas</b> por la del espacio, y por
	 * la de la oferta que la presta.
	 *
	 * <p>La efectiva se calcula al leer y no se persiste: guardarla haria que cambiar el box deje
	 * clases prometiendo lugares que ya no existen.
	 *
	 * <p>El control contra {@code ocupados} es la validacion obligatoria de RF-M12-012 —"no mover a
	 * espacio con capacidad inferior a ocupacion confirmada"—. <b>Hoy lee cero</b> porque las
	 * inscripciones son de 08.02, y se escribe igual: escribirla despues es escribirla en el
	 * momento en que empieza a poder romperse.
	 */
	private void exigirCapacidadSostenible(
			long organizationId,
			OfertaSnapshot oferta,
			Long espacioId,
			int capacidadPedida,
			Instant at,
			int ocupados) {

		int maxima = capacidadEfectiva(organizationId, oferta, espacioId, at, capacidadPedida);
		if (capacidadPedida > maxima) {
			throw new CapacidadNoAdmitidaException(capacidadPedida, maxima,
					"supera el limite de la oferta o del espacio");
		}
		if (capacidadPedida < ocupados) {
			throw new CapacidadNoAdmitidaException(capacidadPedida, ocupados,
					"quedaria por debajo de los " + ocupados + " participantes confirmados");
		}
	}

	/** El minimo entre la capacidad propia, la de la oferta y la del espacio. RN-M28-002. */
	private int capacidadEfectiva(
			long organizationId,
			OfertaSnapshot oferta,
			Long espacioId,
			Instant at,
			int capacidadPropia) {

		int efectiva = Math.min(capacidadPropia, oferta.capacidad());
		if (espacioId == null) {
			return efectiva;
		}
		return espacios.find(organizationId, espacioId, at)
				.map(espacio -> Math.min(efectiva, espacio.capacidad()))
				.orElse(efectiva);
	}

	/**
	 * <b>La exclusion mutua.</b> Mira turnos Y clases, y corre bajo el lock de la sede.
	 *
	 * <p>Sin la mitad de los turnos, una clase se programa encima de un turno ya vendido. Sin la
	 * mitad de las clases, dos clases se pisan. Las dos consultas son necesarias y ninguna alcanza
	 * sola.
	 */
	private void exigirRecursosLibres(
			long organizationId,
			long consultorioId,
			Long profesionalId,
			Long espacioId,
			Instant inicio,
			Instant fin,
			Long claseExcluidaId) {

		if (profesionalId != null) {
			List<OcupacionDeAgenda> turnos = agenda.turnosDeProfesionalQueCruzan(
					organizationId, profesionalId, inicio, fin);
			boolean otraClase = clases
					.findVivasDeProfesionalQueCruzan(organizationId, profesionalId, inicio, fin)
					.stream()
					.anyMatch(otra -> !otra.getId().equals(claseExcluidaId));
			if (!turnos.isEmpty() || otraClase) {
				throw new RecursoOcupadoException("profesional");
			}
		}

		if (espacioId != null && !espacioLibre(
				organizationId, espacioId, inicio, fin, claseExcluidaId)) {

			throw new RecursoOcupadoException("espacio");
		}

		log.debug("Recursos libres para la clase en {} de la sede {}", inicio, consultorioId);
	}

	private boolean espacioLibre(
			long organizationId, long espacioId, Instant inicio, Instant fin, Long claseExcluidaId) {

		if (!agenda.turnosDeEspacioQueCruzan(organizationId, espacioId, inicio, fin).isEmpty()) {
			return false;
		}
		return clases.findVivasDeEspacioQueCruzan(organizationId, espacioId, inicio, fin).stream()
				.allMatch(otra -> otra.getId().equals(claseExcluidaId));
	}

	/**
	 * Cuantos lugares consumen inscripciones.
	 *
	 * <p><b>Devolvia 0 en 08.01 y ahora devuelve el numero real</b>, y el cambio es de una linea
	 * porque 08.01 lo dejo en un unico lugar a proposito. Con el, la regla de RF-M12-012 —no bajar
	 * la capacidad por debajo de la ocupacion confirmada— <b>se enciende sin escribir nada
	 * nuevo</b>.
	 *
	 * <p>Sale de la columna y no de un {@code COUNT} sobre las inscripciones: esa columna es quien
	 * OTORGA el lugar, no un resumen de quienes lo tienen. Ver el punto 2 de la cabecera de
	 * {@code V60}.
	 */
	private int contarOcupacion(ClaseProgramada clase) {
		return clase.getCupoOcupado();
	}

	private ClaseView proyectar(long organizationId, long consultorioId, ClaseProgramada clase) {
		int efectiva = ofertas.find(organizationId, consultorioId, clase.getOfertaId())
				.map(oferta -> capacidadEfectiva(organizationId, oferta, clase.getEspacioId(),
						clase.getInicio(), clase.getCapacidad()))
				.orElse(clase.getCapacidad());
		return ClaseView.de(clase, efectiva, contarOcupacion(clase));
	}

	/**
	 * Relee la clase para proyectarla despues de una escritura nativa sobre su fila.
	 *
	 * <p>{@code vaciarCupo} y {@code cancelarTodasPorClaseCancelada} son {@code UPDATE} nativos con
	 * {@code clearAutomatically}: la entidad que quedo en memoria tiene el {@code cupo_ocupado}
	 * viejo, y devolver ese numero le mostraria al mostrador participantes en una clase que acaba de
	 * cancelar. El {@code orElse} cubre el caso en que no hubo escritura nativa.
	 */
	private ClaseView proyectarReleyendo(
			long organizationId, long consultorioId, ClaseProgramada enMemoria) {

		ClaseProgramada fresca = clases
				.findByIdInScope(organizationId, consultorioId, enMemoria.getId())
				.orElse(enMemoria);
		return proyectar(organizationId, consultorioId, fresca);
	}

	// =================================================================================
	// Concurrencia
	// =================================================================================

	/**
	 * <p>Se compara la version ANTES de mutar. Dejarselo a JPA funcionaria igual, pero el error
	 * llegaria al commitear —lejos de la causa— y con la clase ya modificada en la sesion.
	 */
	private static void exigirVersion(ClaseProgramada clase, long version) {
		if (clase.getVersion() != version) {
			throw new OptimisticLockingFailureException(
					"La clase " + clase.getId() + " fue modificada por otra operacion");
		}
	}

	// =================================================================================
	// Auditoria
	// =================================================================================

	/**
	 * <p>Se escribe DENTRO de la transaccion, como todo el resto del sistema: un registro
	 * post-commit que falla deja la transicion hecha y sin rastro.
	 *
	 * <p>Convive con {@code clase_evento} y no lo duplica: aquella tabla es del dominio de M28 y la
	 * consulta la pantalla de la clase; esta es transversal y la consulta un administrador buscando
	 * que hizo una cuenta.
	 */
	private void auditar(
			OperatingActor actor,
			ClaseProgramada clase,
			String evento,
			EstadoClase anterior,
			String motivo,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				clase.getOrganizationId(),
				clase.getConsultorioId(),
				actor.accountId(),
				evento,
				ENTIDAD,
				clase.getId(),
				anterior == null ? null : anterior.name(),
				clase.getEstado().name(),
				Map.of("ofertaId", String.valueOf(clase.getOfertaId()),
						"inicio", String.valueOf(clase.getInicio()),
						"capacidad", String.valueOf(clase.getCapacidad())),
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
					"La gestion de clases requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	private void exigirGestion(OperatingActor actor, long organizationId, long consultorioId) {
		exigir(actor, organizationId, consultorioId, PermissionCodes.CLASE_MANAGE);
	}

	private void exigirLectura(OperatingActor actor, long organizationId, long consultorioId) {
		exigir(actor, organizationId, consultorioId, PermissionCodes.CLASE_READ);
	}

	private void exigir(
			OperatingActor actor, long organizationId, long consultorioId, String permiso) {

		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), permiso, organizationId, consultorioId, null, Instant.now()));
	}
}
