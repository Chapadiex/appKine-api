package com.akine.activity.application;

import com.akine.activity.domain.AsistenciaActividad;
import com.akine.activity.domain.AsistenciaEvento;
import com.akine.activity.domain.ClaseEvento;
import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.EstadoClase;
import com.akine.activity.domain.EstadoInscripcion;
import com.akine.activity.domain.InscripcionClase;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.activity.domain.TipoEventoClase;
import com.akine.activity.domain.exception.AsistenciaNotAccessibleException;
import com.akine.activity.domain.exception.ClaseCompletaException;
import com.akine.activity.domain.exception.ClaseNotAccessibleException;
import com.akine.activity.domain.exception.ConsultorioNoAccesibleException;
import com.akine.activity.domain.exception.InscripcionDuplicadaException;
import com.akine.activity.domain.exception.InscripcionNotAccessibleException;
import com.akine.activity.domain.exception.PersonaNoAccesibleException;
import com.akine.activity.domain.exception.TransicionDeClaseNoPermitidaException;
import com.akine.activity.domain.exception.TransicionDeInscripcionNoPermitidaException;
import com.akine.activity.domain.port.ActivityRepositoryPorts.AsistenciaActividadRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.AsistenciaEventoRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseEventoRepositoryPort;
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
import com.akine.platform.spi.problem.ProblemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Asistencia y operacion de clases (M28, RF-M28-007, RF-M28-009, RF-M13-007 y RF-M13-008).
 *
 * <h2>Lo unico que decide la correctitud de esta clase: tomar asistencia no es prestar atencion</h2>
 *
 * <p>Es la regla maestra que el proyecto sostiene desde 06.01 —Sesion != Turno, DP-05— trasladada
 * al mundo grupal. Hay <b>tres hechos distintos</b> y la tentacion es fundirlos:
 *
 * <pre>
 *   inscripcion_clase      la persona tiene RESERVADO un lugar        (08.02)
 *   asistencia_actividad   la persona ESTUVO en esta clase            (esta etapa)
 *   sesion                 se le presto una atencion clinica          (08.04 / 08.05)
 * </pre>
 *
 * <p>Esta clase <b>no crea Sesiones</b>, no toca {@code clinical} ni {@code encounter} y no los
 * importa (RN-M28-007). Y <b>no devenga nada</b>: RF-M18-008 condiciona la deuda a que la Oferta
 * use esquema {@code POR_CLASE} y a que "la politica" lo defina, y ninguna de las dos condiciones
 * es evaluable —{@code V24} declara {@code esquema_cobro} como dato "declarado, no resuelto" sin
 * lista cerrada, y la politica no existe en ninguna tabla—. El devengo es 08.06/08.07/08.09 y
 * cuelga de la referencia economica unica que esta etapa deja.
 *
 * <h2>El cupo no se mueve, salvo en una operacion</h2>
 *
 * <p>{@code RESERVADA}, {@code CONFIRMADA}, {@code ASISTIO} y {@code AUSENTE} consumen lugar los
 * cuatro (08.02), asi que marcar asistencia va de un estado que consume a otro que consume:
 * {@code cupo_ocupado} se queda donde estaba y <b>esta etapa no agrega contencion por clase</b>.
 *
 * <p>La excepcion es {@link #registrarIngresoSinInscripcion}, que <b>crea</b> una inscripcion y por
 * lo tanto pide lugar con el mismo {@code UPDATE} condicional de 08.02. <b>Orden de locks
 * intacto</b>: {@code clase_programada -> inscripcion_clase}, y nunca {@code agenda_sede}.
 *
 * <h2>La idempotencia sale del hecho, no de una clave</h2>
 *
 * <p>No hay {@code Idempotency-Key} en esta etapa. La clave natural es {@code (clase, persona)} y
 * ya vive en un unique: un segundo pedido con el mismo resultado devuelve la fila sin escribir, y
 * con otro resultado es una <b>correccion</b> que exige motivo. Misma forma que el cierre de sesion
 * de 06.05, y sin el agujero de que dos claves distintas produzcan dos asistencias para la misma
 * persona.
 */
@Service
public class AsistenciaService {

	private static final Logger log = LoggerFactory.getLogger(AsistenciaService.class);

	private static final String ENTIDAD = "ASISTENCIA_ACTIVIDAD";
	private static final String ENTIDAD_CLASE = "CLASE_PROGRAMADA";

	/** Tope del lote. Mas que esto no es una lista compacta: es un import disfrazado. */
	public static final int MAX_ITEMS_LOTE = 200;

	private static final String MOTIVO_ESPERA_CLASE_CERRADA =
			"La clase se realizo sin que se liberara un lugar";

	private final AsistenciaActividadRepositoryPort asistencias;
	private final AsistenciaEventoRepositoryPort eventos;
	private final InscripcionClaseRepositoryPort inscripciones;
	private final ClaseProgramadaRepositoryPort clases;
	private final ClaseEventoRepositoryPort eventosDeClase;
	private final CapacidadDeClase capacidad;
	private final PacienteDirectory personas;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public AsistenciaService(
			AsistenciaActividadRepositoryPort asistencias,
			AsistenciaEventoRepositoryPort eventos,
			InscripcionClaseRepositoryPort inscripciones,
			ClaseProgramadaRepositoryPort clases,
			ClaseEventoRepositoryPort eventosDeClase,
			CapacidadDeClase capacidad,
			PacienteDirectory personas,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.asistencias = asistencias;
		this.eventos = eventos;
		this.inscripciones = inscripciones;
		this.clases = clases;
		this.eventosDeClase = eventosDeClase;
		this.capacidad = capacidad;
		this.personas = personas;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Ciclo operativo de la clase (RF-M13-007)
	// =================================================================================

	/**
	 * Abre la clase: {@code PROGRAMADA} -&gt; {@code EN_CURSO}.
	 *
	 * <p><b>Idempotente</b>: iniciar una que ya esta en curso devuelve la misma clase sin auditar
	 * de nuevo, y el actor que queda registrado es el primero que la abrio.
	 *
	 * @throws TransicionDeClaseNoPermitidaException si esta cancelada o ya se realizo (409)
	 */
	@Transactional
	public ClaseView iniciar(OperatingActor actor, long consultorioId, long claseId) {
		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.CLASE_MANAGE);

		ClaseProgramada clase = exigirClaseDelTenant(organizationId, consultorioId, claseId);
		EstadoClase anterior = clase.getEstado();

		Instant ahora = Instant.now();
		if (clase.iniciar(actor.accountId(), ahora)) {
			ClaseProgramada iniciada = clases.saveAndFlush(clase);
			eventosDeClase.registrar(ClaseEvento.de(
					iniciada, TipoEventoClase.INICIO, anterior, null, actor.accountId(), ahora));
			auditarClase(actor, iniciada, sede, "CLASE_INICIADA", anterior, ahora);
			log.info("Clase iniciada: claseId={} consultorioId={}", claseId, consultorioId);
			return vista(organizationId, consultorioId, iniciada);
		}
		return vista(organizationId, consultorioId, clase);
	}

	/**
	 * Cierra la operacion de la clase y <b>resuelve a todos los que quedaron sin resultado</b>.
	 *
	 * <p>Tres cosas, en una transaccion: los que tienen lugar y nadie marco quedan
	 * {@code AUSENTE} con {@code origen = CIERRE}; los que quedaron en la cola se cancelan —dejarlos
	 * esperando una clase que ya ocurrio es un estado que no se resuelve nunca—; y la clase pasa a
	 * {@code REALIZADA}.
	 *
	 * <p><b>Idempotente, y sin una bandera.</b> Un segundo cierre no encuentra pendientes porque el
	 * primero les creo la fila, y {@code uk_asistencia_clase_persona} hace imposible una segunda.
	 * La idempotencia sale del estado del mundo, no de un flag que alguien tiene que acordarse de
	 * leer — un {@code ya_cerrada} habria sido una segunda fuente de verdad sobre lo mismo que dice
	 * {@code estado}.
	 *
	 * <p><b>Cerrar no cobra.</b> Ver la cabecera.
	 */
	@Transactional
	public ResultadoDeCierre cerrar(OperatingActor actor, long consultorioId, long claseId) {
		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.CLASE_MANAGE);

		ClaseProgramada clase = exigirClaseDelTenant(organizationId, consultorioId, claseId);
		EstadoClase anterior = clase.getEstado();
		Instant ahora = Instant.now();

		// Se resuelven los pendientes ANTES de transicionar la clase: si transicionara primero y
		// algo fallara despues, quedaria una clase realizada con participantes sin resolver. Como
		// todo va en la misma transaccion el orden no cambia el resultado, pero si el que lee.
		List<AsistenciaActividad> ausentados = ausentarPendientes(
				organizationId, claseId, clase.getProfesionalMembershipId(), actor.accountId(),
				ahora);
		int esperaCancelada = inscripciones.cancelarEsperaPorClaseCerrada(
				organizationId, claseId, MOTIVO_ESPERA_CLASE_CERRADA, actor.accountId(), ahora);

		boolean cerroAhora = clase.cerrar(actor.accountId(), ahora);
		ClaseProgramada cerrada = cerroAhora ? clases.saveAndFlush(clase) : clase;
		if (cerroAhora) {
			eventosDeClase.registrar(ClaseEvento.de(
					cerrada, TipoEventoClase.CIERRE, anterior, null, actor.accountId(), ahora));
			auditarClase(actor, cerrada, sede, "CLASE_CERRADA", anterior, ahora);
		}

		log.info("Clase cerrada: claseId={} cerroAhora={} ausentados={} esperaCancelada={}",
				claseId, cerroAhora, ausentados.size(), esperaCancelada);

		return new ResultadoDeCierre(
				vista(organizationId, consultorioId, cerrada),
				cerroAhora,
				ausentados.stream().map(AsistenciaView::de).toList(),
				esperaCancelada,
				cupos(organizationId, consultorioId, cerrada));
	}

	/**
	 * Marca como ausentes a los que tienen lugar y nadie resolvio.
	 *
	 * <p>El filtro <b>vive en la consulta</b> y no en memoria: de ahi sale la idempotencia del
	 * cierre, y traer todas para filtrar despues creceria con lo exitosa que sea la clase.
	 */
	private List<AsistenciaActividad> ausentarPendientes(
			long organizationId, long claseId, Long profesionalId, long cuentaId, Instant ahora) {

		List<InscripcionClase> pendientes =
				inscripciones.findConLugarSinAsistencia(organizationId, claseId);
		if (pendientes.isEmpty()) {
			return List.of();
		}

		List<AsistenciaActividad> nuevas = new ArrayList<>(pendientes.size());
		for (InscripcionClase inscripcion : pendientes) {
			inscripcion.marcarAsistencia(EstadoInscripcion.AUSENTE, ahora);
			inscripciones.save(inscripcion);
			nuevas.add(AsistenciaActividad.registrar(
					inscripcion, ResultadoAsistencia.AUSENTE, OrigenAsistencia.CIERRE,
					profesionalId, null, cuentaId, ahora));
		}

		List<AsistenciaActividad> guardadas = asistencias.saveAll(nuevas);
		eventos.registrarTodos(guardadas.stream()
				.map(asistencia -> AsistenciaEvento.registro(asistencia, cuentaId, ahora))
				.toList());
		return guardadas;
	}

	// =================================================================================
	// Registrar y corregir asistencia (RF-M28-007)
	// =================================================================================

	/**
	 * Registra o corrige la asistencia de un participante.
	 *
	 * @throws ConsultorioNoAccesibleException            sede inexistente o de otro tenant (404)
	 * @throws ClaseNotAccessibleException                clase inexistente o de otro tenant (404)
	 * @throws InscripcionNotAccessibleException          la inscripcion no es de esa clase (404)
	 * @throws TransicionDeClaseNoPermitidaException      la clase no admite asistencia (409)
	 * @throws TransicionDeInscripcionNoPermitidaException la inscripcion no tenia lugar (409)
	 */
	@Transactional
	public ResultadoDeAsistencia registrar(
			OperatingActor actor,
			long consultorioId,
			long claseId,
			RegistrarAsistenciaCommand command) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.ASISTENCIA_MANAGE);

		ClaseProgramada clase = exigirClaseQueAdmiteAsistencia(
				organizationId, consultorioId, claseId);
		InscripcionClase inscripcion = inscripciones
				.findByIdInScope(organizationId, claseId, command.inscripcionId())
				.orElseThrow(() ->
						new InscripcionNotAccessibleException(command.inscripcionId()));

		return aplicar(actor, sede, clase, inscripcion, command, Instant.now());
	}

	/** El nucleo compartido por la operacion individual, el lote y el ingreso sin inscripcion. */
	private ResultadoDeAsistencia aplicar(
			OperatingActor actor,
			ConsultorioSnapshot sede,
			ClaseProgramada clase,
			InscripcionClase inscripcion,
			RegistrarAsistenciaCommand command,
			Instant ahora) {

		long organizationId = clase.getOrganizationId();
		Optional<AsistenciaActividad> previa =
				asistencias.findDeInscripcion(organizationId, inscripcion.getId());

		if (previa.isPresent()) {
			AsistenciaActividad existente = previa.get();
			// IDEMPOTENCIA: el mismo resultado no escribe nada y no vuelve a auditar. No hace falta
			// clave: el hecho ya ocurrido es la clave.
			if (existente.repite(command.resultado())) {
				return new ResultadoDeAsistencia(
						AsistenciaView.de(existente), InscripcionView.de(inscripcion), false, false);
			}
			return corregir(actor, sede, inscripcion, existente, command, ahora);
		}
		return crear(actor, sede, clase, inscripcion, command, ahora);
	}

	private ResultadoDeAsistencia crear(
			OperatingActor actor,
			ConsultorioSnapshot sede,
			ClaseProgramada clase,
			InscripcionClase inscripcion,
			RegistrarAsistenciaCommand command,
			Instant ahora) {

		EstadoInscripcion anterior = inscripcion.getEstado();
		inscripcion.marcarAsistencia(command.resultado().estadoDeInscripcion(), ahora);
		InscripcionClase proyectada = inscripciones.saveAndFlush(inscripcion);

		AsistenciaActividad guardada = asistencias.saveAndFlush(AsistenciaActividad.registrar(
				proyectada, command.resultado(), command.origen(),
				clase.getProfesionalMembershipId(), command.observaciones(),
				actor.accountId(), ahora));
		eventos.registrar(AsistenciaEvento.registro(guardada, actor.accountId(), ahora));

		auditar(actor, guardada, sede, "ASISTENCIA_REGISTRADA", anterior.name(), null, ahora);
		log.info("Asistencia registrada: asistenciaId={} claseId={} resultado={} origen={}",
				guardada.getId(), guardada.getClaseId(), command.resultado(), command.origen());

		return new ResultadoDeAsistencia(
				AsistenciaView.de(guardada), InscripcionView.de(proyectada), true, false);
	}

	/**
	 * Corrige un hecho ya afirmado. <b>El motivo es obligatorio</b> y la fila anterior no se borra:
	 * queda en el historial append-only.
	 */
	private ResultadoDeAsistencia corregir(
			OperatingActor actor,
			ConsultorioSnapshot sede,
			InscripcionClase inscripcion,
			AsistenciaActividad existente,
			RegistrarAsistenciaCommand command,
			Instant ahora) {

		ResultadoAsistencia anterior = existente.corregir(
				command.resultado(), command.observaciones(), command.motivo(),
				actor.accountId(), ahora);
		AsistenciaActividad corregida = asistencias.saveAndFlush(existente);
		eventos.registrar(AsistenciaEvento.correccion(
				corregida, anterior, command.motivo(), actor.accountId(), ahora));

		inscripcion.marcarAsistencia(command.resultado().estadoDeInscripcion(), ahora);
		InscripcionClase proyectada = inscripciones.saveAndFlush(inscripcion);

		auditar(actor, corregida, sede, "ASISTENCIA_CORREGIDA", anterior.name(), command.motivo(),
				ahora);
		log.info("Asistencia corregida: asistenciaId={} de={} a={}",
				corregida.getId(), anterior, command.resultado());

		return new ResultadoDeAsistencia(
				AsistenciaView.de(corregida), InscripcionView.de(proyectada), false, true);
	}

	// =================================================================================
	// Lote (RF-M13-008)
	// =================================================================================

	/**
	 * Marca a varios de una, con <b>resultados parciales explicitos</b>.
	 *
	 * <p><b>Este metodo NO es transaccional, y es la unica forma de que "independiente" signifique
	 * algo.</b> RF-M13-008 paso 6 pide "actualizacion transaccional independiente": si el lote
	 * abriera transaccion, los hijos se uniran a ella y el primer fallo revertiria todo. Peor
	 * todavia seria atrapar las excepciones adentro de una transaccion propia — atrapar una
	 * excepcion de persistencia <b>no des-marca la transaccion</b>, y Spring lanza
	 * {@code UnexpectedRollbackException} al commitear: el lote reportaria "6 ok, 1 error" y
	 * despues revertiria los 6. Este repositorio ya pago esa trampa cuatro veces con las
	 * filas-lock.
	 *
	 * <p>El precio, que no se esconde: <b>un lote no es atomico</b>, y eso es exactamente lo que se
	 * pidio (CA-M13-008-06: cada participante conserva su estado propio).
	 */
	public ResultadoDeLote registrarLote(
			OperatingActor actor,
			long consultorioId,
			long claseId,
			List<RegistrarAsistenciaCommand> comandos) {

		if (comandos.size() > MAX_ITEMS_LOTE) {
			throw new IllegalArgumentException(
					"Un lote admite hasta " + MAX_ITEMS_LOTE + " participantes, y llegaron "
							+ comandos.size());
		}

		List<ItemDeLote> items = new ArrayList<>(comandos.size());
		for (RegistrarAsistenciaCommand comando : comandos) {
			items.add(ejecutarItem(actor, consultorioId, claseId, comando));
		}

		long organizationId = exigirContexto(actor);
		return ResultadoDeLote.de(items, cupos(organizationId, consultorioId, claseId));
	}

	/**
	 * Un item del lote. Cada uno abre y cierra <b>su propia transaccion</b> porque delega en
	 * {@link #registrar}, que es la que la tiene.
	 *
	 * <p>El error se traduce al <b>mismo</b> {@code problemType} que habria viajado en un
	 * {@code ProblemDetail} individual, para que el cliente use el mapeo que ya tiene y no un
	 * segundo vocabulario de errores.
	 */
	private ItemDeLote ejecutarItem(
			OperatingActor actor, long consultorioId, long claseId,
			RegistrarAsistenciaCommand comando) {

		try {
			return ItemDeLote.ok(
					comando.inscripcionId(), registrar(actor, consultorioId, claseId, comando));
		} catch (InscripcionNotAccessibleException excepcion) {
			// 404 y nunca "no es tuyo": sin el predicado de clase y tenant en la consulta, un lote
			// seria un enumerador cross-tenant con resultados parciales explicando cual id existe.
			return ItemDeLote.error(comando.inscripcionId(),
					ProblemType.NOT_FOUND.value(), "La inscripcion no existe en esta clase.");
		} catch (TransicionDeInscripcionNoPermitidaException excepcion) {
			return ItemDeLote.error(comando.inscripcionId(),
					ProblemType.INSCRIPCION_TRANSICION_NO_PERMITIDA.value(),
					excepcion.getMessage());
		} catch (TransicionDeClaseNoPermitidaException excepcion) {
			return ItemDeLote.error(comando.inscripcionId(),
					ProblemType.CLASE_TRANSICION_NO_PERMITIDA.value(), excepcion.getMessage());
		} catch (IllegalArgumentException excepcion) {
			return ItemDeLote.error(comando.inscripcionId(),
					ProblemType.VALIDATION_ERROR.value(), excepcion.getMessage());
		}
	}

	// =================================================================================
	// Ingreso sin inscripcion (RF-M13-007, el caso borde del plan)
	// =================================================================================

	/**
	 * Deja entrar a quien se presento sin estar inscripto.
	 *
	 * <p><b>No hay asistencia sin inscripcion.</b> Una asistencia huerfana es una persona adentro
	 * de la clase que el contador de cupo no ve, o sea la sobreventa por la puerta de atras — justo
	 * despues de que 08.02 cerro la de adelante, y peor, porque esa persona no tendria recibo y ni
	 * un backfill podria reconstruir el numero.
	 *
	 * <p>Entonces se crea la inscripcion de verdad, y para crearla <b>se pide el lugar con el mismo
	 * {@code UPDATE} condicional de 08.02</b>: cero filas es 409 {@code clase-completa}, con la
	 * capacidad efectiva y los ocupados para que el mostrador pueda explicar por que. <b>Sin lista
	 * de espera</b>: una clase en curso no tiene cola.
	 *
	 * <p><b>Lo unico que esta operacion levanta es la regla "la clase ya empezo"</b>, que
	 * {@code inscribir} impone con razon y que aca es justamente la condicion de entrada. Todo lo
	 * demas se revalida igual.
	 *
	 * <p>{@code READ_COMMITTED}, como toda mutacion que toma el lock de la clase: con
	 * {@code REPEATABLE READ} InnoDB fija la foto en la primera lectura consistente y el lock deja
	 * de servir para lo unico que sirve.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ResultadoDeAsistencia registrarIngresoSinInscripcion(
			OperatingActor actor,
			long consultorioId,
			long claseId,
			IngresoSinInscripcionCommand command) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		// Los DOS permisos: esta operacion inscribe y marca. Quien solo puede tomar lista no puede
		// meter gente nueva en la clase.
		exigir(actor, organizationId, consultorioId, PermissionCodes.INSCRIPCION_MANAGE);
		exigir(actor, organizationId, consultorioId, PermissionCodes.ASISTENCIA_MANAGE);

		ClaseProgramada clase = exigirClaseQueAdmiteAsistencia(
				organizationId, consultorioId, claseId);
		if (clase.getEstado().estaCerrada()) {
			throw new TransicionDeClaseNoPermitidaException(
					claseId, "ya cerro su operacion y no admite ingresos");
		}
		exigirPersonaDelPadron(organizationId, command.personaId());
		inscripciones.findVivaDePersona(organizationId, claseId, command.personaId())
				.ifPresent(existente -> {
					throw new InscripcionDuplicadaException(
							claseId, command.personaId(), existente.getId());
				});

		int capacidadEfectiva = capacidad.efectiva(organizationId, consultorioId, clase);
		Instant ahora = Instant.now();

		// EL MISMO UPDATE CONDICIONAL DE 08.02. El techo `capacidad` esta en el WHERE como columna,
		// asi que esta operacion —escrita en otra etapa— tampoco puede sobrevender.
		if (clases.tomarCupo(organizationId, claseId, capacidadEfectiva) != 1) {
			throw new ClaseCompletaException(
					claseId, capacidadEfectiva, clase.getCupoOcupado());
		}

		InscripcionClase inscripcion = inscripciones.saveAndFlush(InscripcionClase.conLugar(
				organizationId, consultorioId, claseId, command.personaId(),
				actor.accountId(), ahora, null, null));
		auditarIngreso(actor, inscripcion, sede, ahora);

		log.info("Ingreso sin inscripcion: claseId={} personaId={} inscripcionId={}",
				claseId, command.personaId(), inscripcion.getId());

		return aplicar(actor, sede, clase, inscripcion, new RegistrarAsistenciaCommand(
				inscripcion.getId(), command.resultado(), OrigenAsistencia.INGRESO_SIN_INSCRIPCION,
				command.observaciones(), null), ahora);
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * El detalle operativo paginado (RF-M28-009). Exige {@code inscripcion:read}: lleva nombre y
	 * documento.
	 *
	 * <p><b>Ni un dato clinico ni uno economico</b> (RNF-M28-002). Lo clinico porque un instructor
	 * no puede ver a que se atiende nadie; lo economico porque todavia no existe.
	 */
	@Transactional(readOnly = true)
	public DetalleOperativoView detalleOperativo(
			OperatingActor actor, long consultorioId, long claseId, int page, int size) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.INSCRIPCION_READ);

		ClaseProgramada clase = exigirClaseDelTenant(organizationId, consultorioId, claseId);
		List<InscripcionClase> todas = inscripciones.findDeLaClase(organizationId, claseId);

		Map<Long, AsistenciaActividad> porInscripcion = asistencias
				.findDeLaClase(organizationId, claseId).stream()
				.collect(Collectors.toMap(
						AsistenciaActividad::getInscripcionId, asistencia -> asistencia));

		List<InscripcionClase> pagina = todas.stream()
				.skip((long) page * size)
				.limit(size)
				.toList();

		// Un solo batch: resolver los nombres de a uno convierte la pantalla en tantas consultas
		// como participantes, que crece justo con lo exitosa que sea la clase.
		Map<Long, PacienteSnapshot> porPersona = personas.findAll(
				organizationId, pagina.stream().map(InscripcionClase::getPersonaId).toList());

		List<ParticipanteOperativoView> contenido = pagina.stream()
				.map(inscripcion -> new ParticipanteOperativoView(
						InscripcionView.de(inscripcion),
						vistaDeAsistencia(porInscripcion.get(inscripcion.getId())),
						nombreDe(porPersona.get(inscripcion.getPersonaId()), true),
						nombreDe(porPersona.get(inscripcion.getPersonaId()), false),
						documentoDe(porPersona.get(inscripcion.getPersonaId()), true),
						documentoDe(porPersona.get(inscripcion.getPersonaId()), false)))
				.toList();

		int sinResolver = inscripciones.findConLugarSinAsistencia(organizationId, claseId).size();

		return new DetalleOperativoView(
				claseId,
				clase.getOfertaId(),
				clase.getTitulo(),
				clase.getInicio(),
				clase.getFin(),
				clase.getEstado().name(),
				clase.getIniciadaEn(),
				clase.getCerradaEn(),
				clase.getProfesionalMembershipId(),
				clase.getCapacidad(),
				capacidad.efectiva(organizationId, consultorioId, clase),
				clase.getCupoOcupado(),
				inscripciones.contarEnEspera(organizationId, claseId),
				asistencias.contarPresentes(organizationId, claseId),
				asistencias.contarAusentes(organizationId, claseId),
				sinResolver,
				contenido,
				page,
				size,
				todas.size());
	}

	/** El historial append-only de una asistencia. Exige {@code inscripcion:read}. */
	@Transactional(readOnly = true)
	public List<AsistenciaEventoView> historial(
			OperatingActor actor, long consultorioId, long claseId, long asistenciaId) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.INSCRIPCION_READ);
		exigirClaseDelTenant(organizationId, consultorioId, claseId);

		AsistenciaActividad asistencia = asistencias
				.findByIdInScope(organizationId, claseId, asistenciaId)
				.orElseThrow(() -> new AsistenciaNotAccessibleException(asistenciaId));

		return eventos.historial(organizationId, asistencia.getId()).stream()
				.map(AsistenciaEventoView::de)
				.toList();
	}

	// =================================================================================
	// Reglas
	// =================================================================================

	/**
	 * <p>Que haya que iniciar la clase antes de tomar lista es lo que hace que {@code EN_CURSO}
	 * sirva para algo: sin esa condicion seria un valor decorativo. Y {@code REALIZADA} entra
	 * porque las correcciones llegan despues de que la clase termino, que es cuando se descubren.
	 */
	private ClaseProgramada exigirClaseQueAdmiteAsistencia(
			long organizationId, long consultorioId, long claseId) {

		ClaseProgramada clase = exigirClaseDelTenant(organizationId, consultorioId, claseId);
		if (!clase.getEstado().admiteAsistencia()) {
			throw new TransicionDeClaseNoPermitidaException(
					claseId,
					clase.getEstado() == EstadoClase.PROGRAMADA
							? "todavia no se inicio: la asistencia se toma con la clase en curso"
							: "esta cancelada y no tuvo asistentes");
		}
		return clase;
	}

	private ClaseProgramada exigirClaseDelTenant(
			long organizationId, long consultorioId, long claseId) {

		return clases.findByIdInScope(organizationId, consultorioId, claseId)
				.orElseThrow(() -> new ClaseNotAccessibleException(claseId));
	}

	/**
	 * <b>Persona, no paciente.</b> No se exige {@code esPacienteVigente}: entrar a una clase no es
	 * entrar al circuito clinico (RF-M07-010).
	 */
	private void exigirPersonaDelPadron(long organizationId, long personaId) {
		PacienteSnapshot persona = personas.find(organizationId, personaId)
				.orElseThrow(() -> new PersonaNoAccesibleException(personaId));
		if (!persona.activa()) {
			throw new TransicionDeInscripcionNoPermitidaException(
					null, "la ficha de la persona esta dada de baja");
		}
	}

	private ClaseView vista(long organizationId, long consultorioId, ClaseProgramada clase) {
		return ClaseView.de(
				clase,
				capacidad.efectiva(organizationId, consultorioId, clase),
				clase.getCupoOcupado());
	}

	private CuposView cupos(long organizationId, long consultorioId, long claseId) {
		return cupos(organizationId, consultorioId,
				exigirClaseDelTenant(organizationId, consultorioId, claseId));
	}

	private CuposView cupos(long organizationId, long consultorioId, ClaseProgramada clase) {
		return CuposView.de(
				clase.getId(),
				clase.getCapacidad(),
				capacidad.efectiva(organizationId, consultorioId, clase),
				clase.getCupoOcupado(),
				inscripciones.contarEnEspera(organizationId, clase.getId()));
	}

	private static AsistenciaView vistaDeAsistencia(AsistenciaActividad asistencia) {
		return asistencia == null ? null : AsistenciaView.de(asistencia);
	}

	/**
	 * Una persona que no vuelve del padron no rompe la lista: la inscripcion existe y hay que poder
	 * verla. Que falte el nombre es un dato, no un error.
	 */
	private static String nombreDe(PacienteSnapshot persona, boolean apellido) {
		if (persona == null) {
			return null;
		}
		return apellido ? persona.apellido() : persona.nombre();
	}

	private static String documentoDe(PacienteSnapshot persona, boolean tipo) {
		if (persona == null) {
			return null;
		}
		return tipo ? persona.tipoDocumento() : persona.numeroDocumento();
	}

	// =================================================================================
	// Auditoria
	// =================================================================================

	/** Se escribe DENTRO de la transaccion: un registro post-commit que falla deja el hecho sin rastro. */
	private void auditar(
			OperatingActor actor,
			AsistenciaActividad asistencia,
			ConsultorioSnapshot sede,
			String evento,
			String anterior,
			String motivo,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				asistencia.getOrganizationId(),
				sede.id(),
				actor.accountId(),
				evento,
				ENTIDAD,
				asistencia.getId(),
				anterior,
				asistencia.getResultado().name(),
				Map.of("claseId", String.valueOf(asistencia.getClaseId()),
						"personaId", String.valueOf(asistencia.getPersonaId()),
						"origen", asistencia.getOrigen().name()),
				motivo,
				null,
				ahora));
	}

	private void auditarClase(
			OperatingActor actor,
			ClaseProgramada clase,
			ConsultorioSnapshot sede,
			String evento,
			EstadoClase anterior,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				clase.getOrganizationId(),
				sede.id(),
				actor.accountId(),
				evento,
				ENTIDAD_CLASE,
				clase.getId(),
				anterior == null ? null : anterior.name(),
				clase.getEstado().name(),
				Map.of("ofertaId", String.valueOf(clase.getOfertaId())),
				null,
				null,
				ahora));
	}

	private void auditarIngreso(
			OperatingActor actor,
			InscripcionClase inscripcion,
			ConsultorioSnapshot sede,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				inscripcion.getOrganizationId(),
				sede.id(),
				actor.accountId(),
				"INSCRIPCION_POR_INGRESO",
				"INSCRIPCION_CLASE",
				inscripcion.getId(),
				null,
				inscripcion.getEstado().name(),
				Map.of("claseId", String.valueOf(inscripcion.getClaseId()),
						"personaId", String.valueOf(inscripcion.getPersonaId())),
				null,
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
					"La operacion de clases requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	private void exigir(
			OperatingActor actor, long organizationId, long consultorioId, String permiso) {

		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), permiso, organizationId, consultorioId, null, Instant.now()));
	}
}
