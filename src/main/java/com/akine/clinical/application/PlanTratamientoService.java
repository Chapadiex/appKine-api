package com.akine.clinical.application;

import com.akine.clinical.domain.CasoClinico;
import com.akine.clinical.domain.EstadoPlan;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.PermissionCodes;
import com.akine.clinical.domain.PlanEvento;
import com.akine.clinical.domain.PlanItem;
import com.akine.clinical.domain.PlanTratamiento;
import com.akine.clinical.domain.PlanTratamientoVersion;
import com.akine.clinical.domain.TipoEventoPlan;
import com.akine.clinical.domain.exception.CasoClinicoNotAccessibleException;
import com.akine.clinical.domain.exception.AutorizacionNoVinculableException;
import com.akine.clinical.domain.exception.CasoNoActivoException;
import com.akine.clinical.domain.exception.OfertaNoHabilitadaException;
import com.akine.clinical.domain.exception.PlanTratamientoNotAccessibleException;
import com.akine.clinical.domain.exception.ReferenciaDelPlanNotAccessibleException;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoClinicoRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanEventoRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanItemRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanNumeradorPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanTratamientoRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanTratamientoVersionRepositoryPort;
import com.akine.clinical.spi.RealizadoEnElCasoProbe;
import com.akine.clinical.spi.RealizadoPorOferta;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.AutorizacionDirectory;
import com.akine.person.spi.AutorizacionSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * El Plan de Tratamiento: crear, editar, activar, modificar, suspender, reanudar, finalizar,
 * consultar versiones y consultar avance (M11, RF-M11-001..008).
 *
 * <h2>Planificado no es realizado</h2>
 *
 * <p>Es RN-M11-001, es la regla maestra 2, y este servicio es donde se hace cumplir. <b>No hay ni
 * un metodo que reciba una cantidad realizada</b>, y no porque se valide: es que no existe la
 * columna donde guardarla. El avance se calcula en {@link #avance}, contando sesiones cerradas del
 * Caso por oferta a traves de {@link RealizadoEnElCasoProbe}, que implementa {@code encounter}.
 *
 * <p>La ventaja concreta de derivar, que conviene tener presente cuando alguien proponga "cachear
 * el avance": una sesion que se esta cerrando mientras otro mira el avance <b>no produce lectura
 * sucia</b>, porque no hay contador que actualizar. Entra o no entra segun haya commiteado, y las
 * dos respuestas son correctas.
 *
 * <h2>Este servicio no agenda y no cierra nada</h2>
 *
 * <ul>
 *   <li><b>No crea turnos ni series.</b> La version propone una frecuencia semanal y una duracion
 *       estimada: es una regla de recurrencia sugerida. Agendar es de {@code scheduling}, que este
 *       modulo no importa ni por el {@code spi}. El dia que se agende desde el plan, el que llama
 *       es {@code scheduling}.</li>
 *   <li><b>No finaliza el plan al completar la cantidad</b> (RN-M11-004). {@link #avance} devuelve
 *       {@code completo} y ahi termina su trabajo: avisa, y decide el profesional. Un cierre
 *       automatico por contador es confundir planificado con realizado en la direccion
 *       contraria.</li>
 *   <li><b>No cierra el Caso</b>, por lo mismo.</li>
 * </ul>
 *
 * <h2>Versionar, y la trampa que 04.02 ya pago</h2>
 *
 * <p>Un plan en BORRADOR se edita <b>en el lugar</b>: nunca se activo, asi que no tiene historia
 * que preservar. Uno ACTIVO o SUSPENDIDO <b>versiona</b>, con su propio juego de items, y los de la
 * version anterior quedan intactos (RF-M11-004, RN-M11-003).
 *
 * <p>El numero de version sale del contador de la cabecera y nunca de un {@code MAX + 1}. Eso
 * ensucia la cabecera, asi que el {@code UPDATE ... WHERE version = N} que emite JPA ya serializa
 * dos modificaciones concurrentes: <b>no hace falta {@code OPTIMISTIC_FORCE_INCREMENT} y ponerlo
 * seria contraproducente</b>, porque haria avanzar la version dos veces y la respuesta devolveria
 * {@code leida + 1} — un 409 del que el cliente no puede salir. La regla que quedo:
 * <b>force-increment solo donde la escritura no toca ninguna columna del padre.</b>
 *
 * <h2>Todo acceso se audita, incluida la lectura</h2>
 *
 * <p>DP-03 no distingue entre leer y escribir en una historia clinica, y un plan dice que se le
 * esta haciendo al paciente y por cuanto tiempo. Las cuatro lecturas de este servicio dejan evento.
 * Lo que <b>no</b> queda en la auditoria son los objetivos ni las indicaciones: son contenido
 * clinico y {@code audit_event} se consulta con {@code auditoria:read}, que no es un permiso
 * clinico.
 */
@Service
public class PlanTratamientoService {

	private static final Logger log = LoggerFactory.getLogger(PlanTratamientoService.class);

	private final HistoriaClinicaRepositoryPort historias;
	private final CasoClinicoRepositoryPort casos;
	private final PlanTratamientoRepositoryPort planes;
	private final PlanTratamientoVersionRepositoryPort versiones;
	private final PlanItemRepositoryPort items;
	private final PlanEventoRepositoryPort eventos;
	private final PlanNumeradorPort numerador;
	private final PlanNumeradorIniciador numeradorIniciador;
	private final OfertaDirectory ofertas;
	private final RealizadoEnElCasoProbe realizado;
	private final AutorizacionDirectory autorizaciones;
	private final PermissionGuard permissionGuard;
	private final RelacionAsistencialProbe relaciones;
	private final AuditTrail auditTrail;
	private final ClinicalSupportAccessAuditor supportAccessAuditor;

	@SuppressWarnings("checkstyle:ParameterNumber")
	public PlanTratamientoService(
			HistoriaClinicaRepositoryPort historias,
			CasoClinicoRepositoryPort casos,
			PlanTratamientoRepositoryPort planes,
			PlanTratamientoVersionRepositoryPort versiones,
			PlanItemRepositoryPort items,
			PlanEventoRepositoryPort eventos,
			PlanNumeradorPort numerador,
			PlanNumeradorIniciador numeradorIniciador,
			OfertaDirectory ofertas,
			RealizadoEnElCasoProbe realizado,
			AutorizacionDirectory autorizaciones,
			PermissionGuard permissionGuard,
			RelacionAsistencialProbe relaciones,
			AuditTrail auditTrail,
			ClinicalSupportAccessAuditor supportAccessAuditor) {

		this.historias = historias;
		this.casos = casos;
		this.planes = planes;
		this.versiones = versiones;
		this.items = items;
		this.eventos = eventos;
		this.numerador = numerador;
		this.numeradorIniciador = numeradorIniciador;
		this.ofertas = ofertas;
		this.realizado = realizado;
		this.autorizaciones = autorizaciones;
		this.permissionGuard = permissionGuard;
		this.relaciones = relaciones;
		this.auditTrail = auditTrail;
		this.supportAccessAuditor = supportAccessAuditor;
	}

	// =================================================================================
	// RF-M11-001 — crear, en BORRADOR
	// =================================================================================

	/**
	 * Crea un plan en {@code BORRADOR} con su version 1 y sus items.
	 *
	 * <h2>El orden importa y no es negociable</h2>
	 *
	 * <pre>
	 *   1. caso, historia y autorizacion clinica
	 *   2. el caso tiene que estar ACTIVO
	 *   3. resolver y validar TODAS las ofertas   &lt;- ANTES de pedir un numero
	 *   4. asegurar el numerador                  &lt;- en su PROPIA transaccion
	 *   5. incrementar y leer                     &lt;- toma el lock de fila y serializa
	 *   6. escribir plan, version 1, items y evento
	 * </pre>
	 *
	 * <p><b>El paso 3 va antes del 5</b>, que es la regla 4 del Paquete B: si no, cada alta
	 * rechazada por una oferta dada de baja consume un correlativo que despues nadie usa, y la
	 * numeracion del caso queda con huecos que parecen planes borrados.
	 *
	 * <p><b>El plan nace en BORRADOR y no activo</b>, aunque venga completo. Activar es una decision
	 * explicita porque tiene un efecto que no se deshace: finaliza el plan que estuviera vigente.
	 *
	 * <p>Corre en {@code READ_COMMITTED} y no en el {@code REPEATABLE READ} por defecto: InnoDB fija
	 * la foto en la primera lectura consistente, que ocurre <b>antes</b> del lock del numerador, asi
	 * que bajo {@code REPEATABLE READ} el {@code SELECT} posterior al incremento podria leer el
	 * valor viejo. Es la regla que 05.02 dejo fijada para toda mutacion que serializa.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PlanTratamientoView crear(
			OperatingActor actor,
			long casoClinicoId,
			ContenidoDelPlan contenido,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		CasoClinico caso = exigirCaso(organizationId, casoClinicoId);
		AccesoClinico acceso = autorizarSobre(
				actor, caso, PermissionCodes.HC_WRITE, justificacion, "Crear plan de tratamiento");

		if (!caso.estaActivo()) {
			throw new CasoNoActivoException(casoClinicoId);
		}

		// PASO 3. Todas las ofertas, antes de tocar el numerador.
		Map<Long, OfertaSnapshot> resueltas = resolverOfertas(
				organizationId, actor.consultorioId(), contenido.items());

		// PASOS 4 y 5. La fila se asegura AFUERA; el incremento toma el lock y serializa.
		numeradorIniciador.asegurarPlanes(organizationId, casoClinicoId);
		numerador.incrementar(organizationId, casoClinicoId);
		Integer numero = numerador.leerUltimo(organizationId, casoClinicoId);
		if (numero == null) {
			throw new IllegalStateException(
					"El numerador de planes del caso " + casoClinicoId
							+ " no existe despues de crearlo");
		}

		Instant ahora = Instant.now();

		// saveAndFlush: la version 1 necesita el id de la cabecera, y sin el flush ese id no
		// existe hasta el cierre de la transaccion.
		PlanTratamiento plan = planes.saveAndFlush(new PlanTratamiento(
				organizationId, casoClinicoId, numero, ahora, actor.accountId()));

		PlanTratamientoVersion version = escribirVersion(
				plan, 1, contenido, null, ahora, actor.accountId());
		List<PlanItem> escritos = escribirItems(plan, version, contenido.items(), resueltas);

		asentar(plan, TipoEventoPlan.CREACION, null, EstadoPlan.BORRADOR, null,
				"Plan creado con " + escritos.size() + " practica(s) planificada(s)",
				version.getNumeroVersion(), ahora, actor);

		auditar(AuditEvents.PLAN_TRATAMIENTO_CREATED, plan.getId(), actor, acceso, null, "BORRADOR",
				Map.of("casoClinicoId", String.valueOf(casoClinicoId),
						"numeroPlan", String.valueOf(numero),
						"practicas", String.valueOf(escritos.size())),
				ahora);
		registrarSoporte(acceso, actor, plan, "Crear plan de tratamiento", ahora);

		log.info("Plan de tratamiento creado: planId={} casoClinicoId={} numeroPlan={}",
				plan.getId(), casoClinicoId, numero);
		return PlanTratamientoView.de(plan, version, vistaDe(escritos));
	}

	// =================================================================================
	// RF-M11-004 — editar el borrador o modificar el plan vigente
	// =================================================================================

	/**
	 * Cambia el contenido del plan. <b>Que haga eso o versione lo decide el estado, no el pedido.</b>
	 *
	 * <ul>
	 *   <li><b>BORRADOR</b>: reescribe la version 1 en el lugar, sin versionar. Un plan que nunca
	 *       se activo no tiene historia que preservar, y versionar cada tecleo llenaria la tabla de
	 *       ruido. El motivo <b>no se exige</b>: no hay nada que explicar todavia.</li>
	 *   <li><b>ACTIVO o SUSPENDIDO</b>: escribe una version nueva con su propio juego de items, y
	 *       los de la anterior quedan intactos (RN-M11-003). El motivo <b>es obligatorio</b>, y lo
	 *       exige la propia version: sin el, una modificacion es indistinguible de una correccion de
	 *       tipeo.</li>
	 *   <li><b>FINALIZADO</b>: 409. Lo que corresponde es crear un plan nuevo.</li>
	 * </ul>
	 *
	 * <p>Que el estado decida y no un parametro es deliberado: un {@code versionar=true} en el
	 * cuerpo dejaria al cliente elegir si preservar o no la historia clinica, que es justamente la
	 * decision que RN-M11-003 no delega.
	 *
	 * <p><b>La lectura NO lleva {@code OPTIMISTIC_FORCE_INCREMENT}.</b> Modificar ensucia la
	 * cabecera —el contador de versiones vive ahi— asi que el flush ya emite un {@code UPDATE ...
	 * WHERE version = N} versionado y dos modificaciones concurrentes no pueden commitear las dos.
	 * Forzar el incremento no agregaba garantia: agregaba un segundo incremento, y la vista devolvia
	 * {@code leida + 1} mientras la base quedaba en {@code leida + 2}. Es el defecto que 04.02 pago
	 * y corrigio.
	 *
	 * <p><b>Lo que eso deja sin cubrir, declarado:</b> la edicion de un BORRADOR <b>no</b> ensucia la
	 * cabecera —escribe en la version y en los items— asi que dos ediciones simultaneas del mismo
	 * borrador commitean las dos y gana la ultima. {@code expectedVersion} sigue sirviendo, y para lo
	 * que importa: si alguien <b>activo</b> el plan en el medio, la version avanzo y la edicion
	 * recibe 409 en vez de reescribir el contenido de un plan que acaba de pasar a vigente. Dos
	 * profesionales tecleando el mismo borrador a la vez es el precio explicito de "se edita
	 * libremente sin versionar": un borrador no tiene historia que se pueda perder.
	 */
	@Transactional
	public PlanTratamientoView modificar(
			OperatingActor actor,
			long planId,
			ContenidoDelPlan contenido,
			String motivo,
			long expectedVersion,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		PlanTratamiento plan = exigirPlan(organizationId, planId);
		AccesoClinico acceso = autorizarSobrePlan(actor, plan, PermissionCodes.HC_WRITE,
				justificacion, "Modificar plan de tratamiento");

		plan.exigirEditable();
		exigirVersion(plan, expectedVersion);

		Map<Long, OfertaSnapshot> resueltas = resolverOfertas(
				organizationId, actor.consultorioId(), contenido.items());

		Instant ahora = Instant.now();
		boolean enBorrador = plan.esBorrador();

		PlanTratamientoVersion version;
		List<PlanItem> escritos;
		if (enBorrador) {
			version = exigirVersionVigente(plan);
			version.editarEnBorrador(
					contenido.objetivos(),
					contenido.indicaciones(),
					contenido.frecuenciaSemanal(),
					contenido.duracionSemanas());
			versiones.save(version);

			// El unico borrado fisico del modulo, y esta acotado a esto: los items de la version 1
			// de un plan que NUNCA se activo no son informacion historica, son un formulario a
			// medio llenar. En cuanto el plan pasa a ACTIVO este camino deja de ejecutarse y toda
			// modificacion escribe filas nuevas (regla maestra 10).
			items.borrarDeVersionEnBorrador(organizationId, version.getId());
			escritos = escribirItems(plan, version, contenido.items(), resueltas);

			asentar(plan, TipoEventoPlan.EDICION, EstadoPlan.BORRADOR, EstadoPlan.BORRADOR, null,
					"Se edito el borrador: " + escritos.size() + " practica(s)",
					version.getNumeroVersion(), ahora, actor);
		} else {
			// El numero se resuelve ANTES de escribir contenido y sale del contador de la
			// cabecera: dos MAX(numero_version)+1 simultaneos devuelven el mismo numero.
			int numeroVersion = plan.siguienteNumeroDeVersion();
			version = escribirVersion(
					plan, numeroVersion, contenido, motivo, ahora, actor.accountId());
			escritos = escribirItems(plan, version, contenido.items(), resueltas);

			asentar(plan, TipoEventoPlan.MODIFICACION, plan.getEstado(), plan.getEstado(), motivo,
					"Version " + numeroVersion + " con " + escritos.size() + " practica(s)",
					numeroVersion, ahora, actor);
		}

		// saveAndFlush y NO save. `save` es un merge: deja la escritura pendiente y el UPDATE que
		// sube la version de la cabecera recien sale al cierre de la transaccion, DESPUES de que
		// esta vista ya leyo getVersion(). El cliente se llevaria la version vieja, la mandaria
		// como expectedVersion en la operacion siguiente y comeria un 409 del que no puede salir
		// salvo releyendo el plan. Es la regla "save() antes del flush devuelve la version vieja".
		PlanTratamiento guardado = planes.saveAndFlush(plan);

		auditar(enBorrador ? AuditEvents.PLAN_TRATAMIENTO_UPDATED
						: AuditEvents.PLAN_TRATAMIENTO_AMENDED,
				guardado.getId(), actor, acceso, guardado.getEstado().name(),
				guardado.getEstado().name(),
				Map.of("casoClinicoId", String.valueOf(guardado.getCasoClinicoId()),
						"numeroVersion", String.valueOf(version.getNumeroVersion()),
						"practicas", String.valueOf(escritos.size())),
				ahora);
		registrarSoporte(acceso, actor, guardado, "Modificar plan de tratamiento", ahora);

		return PlanTratamientoView.de(guardado, version, vistaDe(escritos));
	}

	// =================================================================================
	// RF-M11-006 — las cuatro transiciones
	// =================================================================================

	/**
	 * Pone el plan en {@code ACTIVO}, finalizando en la misma transaccion el que estuviera vigente.
	 *
	 * <h2>Un solo plan activo por Caso, y como se garantiza</h2>
	 *
	 * <p>El Caso <b>es</b> el problema terapeutico: dos planes activos para el mismo problema
	 * significan que en realidad son dos problemas, o sea dos Casos. Por eso activar uno nuevo
	 * finaliza el anterior <b>aca adentro</b> y no en dos pedidos: entre un pedido y el otro habria
	 * una ventana con dos vivos o con ninguno, y las dos son incorrectas.
	 *
	 * <p>El anterior se guarda <b>con flush explicito</b> antes de activar este. No es defensivo: el
	 * unique {@code uk_plan_activo_por_caso} se evalua fila por fila, asi que si Hibernate ordenara
	 * el UPDATE de este plan antes que el del anterior, el segundo choca contra el primero dentro de
	 * la misma transaccion. Forzar el orden es lo que hace que la operacion no dependa del orden en
	 * que el ORM decida emitir sus sentencias.
	 *
	 * <p>Y si <b>dos activaciones concurrentes</b> llegan juntas, las dos pueden leer el mismo
	 * anterior: la que pierda choca contra el unique y recibe 409. Es el desenlace correcto — no hay
	 * ventana en la que queden dos vivos.
	 *
	 * <p><b>Activar lo ya activo devuelve 200</b> con el plan tal cual: es el mismo pedido, no un
	 * conflicto. Activar un FINALIZADO es 409: no se reabre, se crea uno nuevo.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PlanTratamientoView activar(
			OperatingActor actor, long planId, long expectedVersion, String justificacion) {

		long organizationId = organizacionDe(actor);
		PlanTratamiento plan = exigirPlan(organizationId, planId);
		AccesoClinico acceso = autorizarSobrePlan(actor, plan, PermissionCodes.HC_WRITE,
				justificacion, "Activar plan de tratamiento");

		Instant ahora = Instant.now();
		if (plan.estaActivo()) {
			log.debug("Plan de tratamiento ya activo: planId={}", planId);
			return vistaCompletaDe(plan);
		}
		exigirVersion(plan, expectedVersion);

		CasoClinico caso = exigirCaso(organizationId, plan.getCasoClinicoId());
		if (!caso.estaActivo()) {
			throw new CasoNoActivoException(caso.getId());
		}

		finalizarElVigente(organizationId, plan, ahora, actor, acceso);

		plan.activar(ahora, actor.accountId());
		PlanTratamiento guardado = planes.saveAndFlush(plan);

		asentar(guardado, TipoEventoPlan.ACTIVACION, EstadoPlan.BORRADOR, EstadoPlan.ACTIVO, null,
				null, guardado.getUltimoNumeroVersion(), ahora, actor);
		auditar(AuditEvents.PLAN_TRATAMIENTO_ACTIVATED, guardado.getId(), actor, acceso, "BORRADOR",
				"ACTIVO", Map.of("casoClinicoId", String.valueOf(guardado.getCasoClinicoId())),
				ahora);
		registrarSoporte(acceso, actor, guardado, "Activar plan de tratamiento", ahora);

		log.info("Plan de tratamiento activado: planId={} casoClinicoId={}",
				guardado.getId(), guardado.getCasoClinicoId());
		return vistaCompletaDe(guardado);
	}

	/**
	 * Discontinua el tratamiento sin cerrarlo (RF-M11-006).
	 *
	 * <p><b>El motivo es obligatorio y lo exige la entidad, no el DTO.</b> Sin motivo, "se freno" es
	 * indistinguible de "lo abandonaron" y el historial deja de servir para lo unico que sirve.
	 *
	 * <p><b>Suspender no libera el lugar del plan activo del Caso.</b> El unique se apoya en
	 * {@code activo_key}, que solo cambia cuando el plan deja de ser el vigente: suspender es frenar
	 * el que hay, no abrir la puerta a otro. Quien quiera empezar otro tratamiento finaliza este.
	 *
	 * <p>Suspender lo ya suspendido devuelve 200 con el motivo <b>original</b> intacto: pisarlo con
	 * el nuevo perderia el que explica la suspension, mismo criterio que el cierre de un caso.
	 */
	@Transactional
	public PlanTratamientoView suspender(
			OperatingActor actor,
			long planId,
			String motivo,
			long expectedVersion,
			String justificacion) {

		return transicionar(actor, planId, expectedVersion, justificacion,
				"Suspender plan de tratamiento", TipoEventoPlan.SUSPENSION,
				AuditEvents.PLAN_TRATAMIENTO_SUSPENDED, motivo,
				(plan, ahora) -> plan.suspender(motivo, ahora));
	}

	/**
	 * Retoma un tratamiento suspendido (RF-M11-006).
	 *
	 * <p>Las columnas de la suspension se limpian —el CHECK no admite un plan ACTIVO con instante de
	 * suspension, y una consulta por {@code suspendido_en IS NOT NULL} devolveria planes vigentes—.
	 * No se pierde nada: la suspension anterior, con su motivo, esta en {@code plan_evento}, que es
	 * append-only.
	 *
	 * <p>Reanudar lo ya activo devuelve 200. Reanudar un BORRADOR o un FINALIZADO es 409: lo que
	 * corresponde en el primer caso es <b>activarlo</b> y en el segundo crear uno nuevo.
	 */
	@Transactional
	public PlanTratamientoView reanudar(
			OperatingActor actor, long planId, long expectedVersion, String justificacion) {

		return transicionar(actor, planId, expectedVersion, justificacion,
				"Reanudar plan de tratamiento", TipoEventoPlan.REANUDACION,
				AuditEvents.PLAN_TRATAMIENTO_RESUMED, null,
				(plan, ahora) -> plan.reanudar(ahora));
	}

	/**
	 * Termina el plan, con motivo (RF-M11-006).
	 *
	 * <p>Es terminal: no se reabre, se crea un plan nuevo. Y es lo que <b>libera el lugar del plan
	 * activo</b> del Caso.
	 *
	 * <p><b>Finalizar no cierra el Caso</b>, aunque sea la tentacion obvia: el Caso puede seguir
	 * abierto con otro plan, o sin ninguno mientras se decide el siguiente. Son dos maquinas de
	 * estado distintas y ninguna manda sobre la otra.
	 *
	 * <p>Finalizar lo ya finalizado devuelve 200 con el motivo original intacto.
	 */
	@Transactional
	public PlanTratamientoView finalizar(
			OperatingActor actor,
			long planId,
			String motivo,
			long expectedVersion,
			String justificacion) {

		return transicionar(actor, planId, expectedVersion, justificacion,
				"Finalizar plan de tratamiento", TipoEventoPlan.FINALIZACION,
				AuditEvents.PLAN_TRATAMIENTO_FINALIZED, motivo,
				(plan, ahora) -> plan.finalizar(motivo, ahora, actor.accountId()));
	}

	// =================================================================================
	// RF-M11-007 — atar el item a una autorizacion real de M17 (AKINE-04.05)
	// =================================================================================

	/**
	 * Reemplaza la cantidad DECLARADA de un item por la de una {@code Autorizacion} real.
	 *
	 * <h2>Es la costura que 04.04 dejo escrita y no cableo</h2>
	 *
	 * <p>{@code OrigenCantidadAutorizada.AUTORIZACION} existe en el enum desde 04.04 y <b>nadie lo
	 * escribia</b>: el registro de esa etapa lo dice con todas las letras —"la costura real es
	 * 04.05"—. Esto lo escribe. La cantidad pasa a salir de M17 y no de lo que alguien tecleo
	 * mirando un carnet.
	 *
	 * <h2>1. NO crea una version del plan, y es deliberado</h2>
	 *
	 * <p>04.04 fijo que modificar un plan vigente escribe una version nueva, porque cambiar lo
	 * <b>planificado</b> reescribiria el avance de hace dos meses contra un plan que entonces no
	 * existia (RN-M11-003). Atar la autorizacion no cambia nada de lo planificado: cambia la
	 * <b>fuente</b> del mismo numero. Versionarlo llenaria el historial clinico de versiones cuya
	 * unica diferencia es administrativa.
	 *
	 * <h2>2. Tampoco deja {@code plan_evento}, y tambien es deliberado</h2>
	 *
	 * <p>{@code plan_evento} registra <b>estados del plan</b> y lo lee el profesional que abre la
	 * ficha; su vocabulario —{@code TipoEventoPlan}— esta cerrado por el CHECK de {@code V49}.
	 * Esto no es una transicion de estado del plan: es un dato administrativo que cambio de
	 * fuente. Meterlo dentro de {@code EDICION} —el unico valor que entraria sin migrar el CHECK—
	 * haria que el historial afirme que alguien edito el contenido del plan, que es falso.
	 *
	 * <p>Queda en {@code audit_event}, que es donde vive lo que hay que poder auditar y que no
	 * tiene vocabulario cerrado. <b>La trazabilidad no se pierde</b>: ademas, el item mismo guarda
	 * el {@code autorizacionId} y M17 guarda su propio ledger.
	 *
	 * <h2>3. Que se valida, y que NO</h2>
	 *
	 * <p>La autorizacion tiene que existir, ser de <b>ese</b> paciente, estar activa y
	 * <b>habilitar hoy</b> —aprobada, vigente y con saldo—. Se pregunta por
	 * {@code person.spi.AutorizacionDirectory}, que es la unica forma en que {@code clinical} puede
	 * mirar una autorizacion sin importar {@code person.domain}.
	 *
	 * <p><b>No se valida que la practica de la autorizacion coincida con la oferta del item</b>, y
	 * no es un olvido: son dos granularidades distintas y no existe ninguna tabla puente entre
	 * Oferta y Practica —V24 la dejo afuera a proposito—. Compararlas hoy seria inventar una
	 * equivalencia. Unificarlas es 06.04. Mientras tanto, elige quien conoce el caso.
	 *
	 * <p><b>Tampoco se exige que la cantidad autorizada alcance la planificada.</b> Planificar mas
	 * de lo que la cobertura cubre es una situacion real —el centro le cobra al paciente la
	 * diferencia— y rechazarla aca le impediria al profesional registrar lo que corresponde
	 * clinicamente.
	 *
	 * <p>Exige {@code hc:write} y acceso clinico, como toda escritura sobre un plan: el numero de
	 * autorizacion queda publicado en la ficha del paciente.
	 */
	@Transactional
	public PlanTratamientoView vincularAutorizacion(
			OperatingActor actor,
			long planId,
			VincularAutorizacionCommand command,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		PlanTratamiento plan = exigirPlan(organizationId, planId);

		AccesoClinico acceso = autorizarSobrePlan(actor, plan, PermissionCodes.HC_WRITE,
				justificacion, "Vincular autorizacion al plan de tratamiento");
		registrarSoporte(acceso, actor, plan, "Vincular autorizacion al plan de tratamiento",
				Instant.now());
		exigirVersion(plan, command.expectedVersion());

		PlanTratamientoVersion vigente = exigirVersionVigente(plan);
		PlanItem item = items.buscarDeVersion(organizationId, vigente.getId()).stream()
				.filter(candidato -> candidato.getId() == command.planItemId())
				.findFirst()
				.orElseThrow(() -> new ReferenciaDelPlanNotAccessibleException("item del plan", command.planItemId()));

		long personaId = personaDelPlan(plan);
		LocalDate hoy = LocalDate.now();

		AutorizacionSnapshot autorizacion = autorizaciones
				.find(organizationId, personaId, command.autorizacionId(), hoy)
				// No existe, es de otro tenant, es de OTRO PACIENTE o esta dada de baja: los
				// cuatro colapsan en 404. Distinguirlos confirmaria que ese id existe, y el caso
				// "es de otro paciente" es ademas el que publicaria en esta ficha un numero de
				// autorizacion que no es suyo.
				.orElseThrow(() -> new ReferenciaDelPlanNotAccessibleException(
						"autorizacion", command.autorizacionId()));

		if (!autorizacion.habilita()) {
			throw new AutorizacionNoVinculableException(
					autorizacion.id(), autorizacion.motivoNoElegible());
		}

		item.vincularAutorizacion(autorizacion.id(), autorizacion.cantidadAutorizada());
		items.saveAll(List.of(item));

		auditar(AuditEvents.PLAN_ITEM_AUTORIZACION_LINKED, plan.getId(), actor, acceso, null, null,
				Map.of("planItemId", String.valueOf(item.getId()),
						"autorizacionId", String.valueOf(autorizacion.id()),
						"cantidadAutorizada", String.valueOf(autorizacion.cantidadAutorizada())),
				Instant.now());

		log.info("Autorizacion vinculada al plan: planId={} planItemId={} autorizacionId={}",
				planId, item.getId(), autorizacion.id());

		return vistaCompletaDe(plan);
	}

	/**
	 * El paciente del plan, por plan -&gt; caso -&gt; historia.
	 *
	 * <p>Se resuelve aca y no se guarda en el plan por lo mismo que la sesion no guarda
	 * {@code persona_id}: duplicarlo habilitaria que los dos discrepen, y no hay ninguna consulta
	 * que lo justifique.
	 */
	private long personaDelPlan(PlanTratamiento plan) {
		return casos.findByIdAndOrganizationId(plan.getCasoClinicoId(), plan.getOrganizationId())
				.flatMap(caso -> historias.findByIdAndOrganizationId(
						caso.getHistoriaClinicaId(), caso.getOrganizationId()))
				.map(HistoriaClinica::getPersonaId)
				.orElseThrow(() -> new PlanTratamientoNotAccessibleException(plan.getId()));
	}

	// =================================================================================
	// Lecturas — las cuatro dejan evento de auditoria
	// =================================================================================

	/** Un plan con su version vigente y los items de esa version. Es lectura clinica. */
	@Transactional
	public PlanTratamientoView ver(OperatingActor actor, long planId, String justificacion) {
		long organizationId = organizacionDe(actor);
		PlanTratamiento plan = exigirPlan(organizationId, planId);
		AccesoClinico acceso = autorizarSobrePlan(
				actor, plan, PermissionCodes.HC_READ, justificacion, "Ver plan de tratamiento");

		Instant ahora = Instant.now();
		auditarLectura(actor, acceso, plan, "PLAN", ahora);
		registrarSoporte(acceso, actor, plan, "Ver plan de tratamiento", ahora);

		return vistaCompletaDe(plan);
	}

	/**
	 * Los planes de un Caso, mas recientes primero.
	 *
	 * @param soloVigentes {@code true} deja afuera los FINALIZADOS; {@code false} los incluye, que
	 *                     es lo que hace consultable el recorrido terapeutico del paciente. Un plan
	 *                     finalizado no es un plan borrado
	 */
	@Transactional
	public List<PlanTratamientoView> listar(
			OperatingActor actor,
			long casoClinicoId,
			boolean soloVigentes,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		CasoClinico caso = exigirCaso(organizationId, casoClinicoId);
		AccesoClinico acceso = autorizarSobre(actor, caso, PermissionCodes.HC_READ, justificacion,
				"Listar planes de tratamiento");

		Instant ahora = Instant.now();
		auditTrail.record(entrada(AuditEvents.PLAN_TRATAMIENTO_ACCESSED,
				AuditEvents.ENTITY_CASO_CLINICO, caso.getId(), actor, acceso, null, null,
				Map.of("alcance", "PLANES", "soloVigentes", String.valueOf(soloVigentes)), ahora));

		return planes.buscarDeCaso(organizationId, casoClinicoId, soloVigentes).stream()
				.map(this::vistaCompletaDe)
				.toList();
	}

	/**
	 * El historico de versiones del plan, de la mas nueva a la mas vieja (RF-M11-004).
	 *
	 * <p>Cada version viene con <b>sus</b> items, y por eso el avance de cada una es un numero
	 * distinto: "8 de 20" en la version 1 y "8 de 24" en la version 2 son los dos correctos.
	 *
	 * <p>Es su propia operacion y su propio evento de auditoria, por lo mismo que el historico de
	 * versiones de una entrada clinica: recorrerlo es ver todo lo que se penso hacerle al paciente,
	 * y colapsarlo dentro de la lectura normal esconderia un acceso que despues alguien quiere
	 * revisar.
	 */
	@Transactional
	public List<PlanVersionView> versiones(
			OperatingActor actor, long planId, String justificacion) {

		long organizationId = organizacionDe(actor);
		PlanTratamiento plan = exigirPlan(organizationId, planId);
		AccesoClinico acceso = autorizarSobrePlan(actor, plan, PermissionCodes.HC_READ,
				justificacion, "Ver el historico de versiones de un plan");

		Instant ahora = Instant.now();
		auditarLectura(actor, acceso, plan, "VERSIONES", ahora);
		registrarSoporte(acceso, actor, plan, "Ver el historico de versiones de un plan", ahora);

		return versiones.buscarDePlan(organizationId, planId).stream()
				.map(version -> PlanVersionView.de(version, itemsDe(version)))
				.toList();
	}

	/**
	 * El avance del plan contra una version de su contenido (RF-M11-005).
	 *
	 * <h2>Todo lo que devuelve es derivado</h2>
	 *
	 * <p>Las realizadas y las canceladas salen de contar sesiones cerradas del Caso por oferta, a
	 * traves de {@link RealizadoEnElCasoProbe}. <b>No hay columna que leer</b>, y eso es lo que hace
	 * que no pueda desincronizarse: la unica forma de que este numero mienta es que {@code encounter}
	 * mienta sobre sus propias sesiones.
	 *
	 * <p>La sonda devuelve una entrada por oferta <b>con al menos una sesion cerrada</b>, asi que las
	 * ofertas planificadas que todavia no se atendieron resuelven en cero. Al reves tambien pasa: una
	 * oferta con sesiones que <b>no</b> esta planificada en esta version no aparece en el avance, y
	 * es correcto — se atendio algo fuera del plan, y quien quiera verlo lo ve en el timeline del
	 * caso, no aca.
	 *
	 * @param numeroVersion version contra la que contar. {@code null} = la vigente. Pedir una
	 *                      version que no existe es <b>400</b> y no 404: el plan existe y lo que
	 *                      esta mal es un parametro del pedido
	 */
	@Transactional
	public AvanceDelPlanView avance(
			OperatingActor actor, long planId, Integer numeroVersion, String justificacion) {

		long organizationId = organizacionDe(actor);
		PlanTratamiento plan = exigirPlan(organizationId, planId);
		AccesoClinico acceso = autorizarSobrePlan(actor, plan, PermissionCodes.HC_READ,
				justificacion, "Ver el avance de un plan de tratamiento");

		PlanTratamientoVersion version = numeroVersion == null
				? exigirVersionVigente(plan)
				: versiones.buscarPorNumero(organizationId, planId, numeroVersion)
						.orElseThrow(() -> new IllegalArgumentException(
								"El plan no tiene una version " + numeroVersion));

		Instant ahora = Instant.now();
		auditarLectura(actor, acceso, plan, "AVANCE", ahora);
		registrarSoporte(acceso, actor, plan, "Ver el avance de un plan de tratamiento", ahora);

		Map<Long, RealizadoPorOferta> porOferta = new LinkedHashMap<>();
		for (RealizadoPorOferta conteo
				: realizado.contarPorOfertaEnElCaso(organizationId, plan.getCasoClinicoId())) {
			porOferta.put(conteo.ofertaId(), conteo);
		}

		List<PlanItemView> planificados = itemsDe(version);
		List<AvanceDeItemView> avances = new ArrayList<>(planificados.size());
		for (PlanItemView item : planificados) {
			RealizadoPorOferta conteo = porOferta.get(item.ofertaId());
			avances.add(AvanceDeItemView.de(item,
					conteo == null ? 0 : conteo.realizadas(),
					conteo == null ? 0 : conteo.canceladas()));
		}

		// Un plan sin items no esta completo: no hay nada planificado contra lo cual estarlo.
		// Y completo NO finaliza nada (RN-M11-004): avisa, y decide el profesional.
		boolean completo = !avances.isEmpty() && avances.stream().allMatch(AvanceDeItemView::completo);

		return new AvanceDelPlanView(
				plan.getId(),
				plan.getCasoClinicoId(),
				plan.getEstado().name(),
				version.getNumeroVersion(),
				avances,
				completo);
	}

	// =================================================================================
	// Internos
	// =================================================================================

	/** Lo que hace una transicion sobre la entidad, para no repetir el andamiaje cuatro veces. */
	@FunctionalInterface
	private interface Transicion {
		boolean aplicar(PlanTratamiento plan, Instant ahora);
	}

	/**
	 * El esqueleto comun de suspender, reanudar y finalizar.
	 *
	 * <p><b>La idempotencia se evalua ANTES de exigir la version</b>, igual que el cierre de un
	 * caso: reintentar una transicion que ya ocurrio no puede fallar por una version que avanzo
	 * justamente por esa transicion. La entidad devuelve {@code false} y aca se responde con el plan
	 * tal como quedo.
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	private PlanTratamientoView transicionar(
			OperatingActor actor,
			long planId,
			long expectedVersion,
			String justificacion,
			String operacion,
			TipoEventoPlan tipo,
			String eventoDeAuditoria,
			String motivo,
			Transicion transicion) {

		long organizationId = organizacionDe(actor);
		PlanTratamiento plan = exigirPlan(organizationId, planId);
		AccesoClinico acceso = autorizarSobrePlan(
				actor, plan, PermissionCodes.HC_WRITE, justificacion, operacion);

		Instant ahora = Instant.now();
		EstadoPlan previo = plan.getEstado();
		if (!transicion.aplicar(plan, ahora)) {
			log.debug("Transicion ya aplicada sobre el plan: planId={} estado={}", planId, previo);
			return vistaCompletaDe(plan);
		}
		// La version se exige DESPUES de descartar el reintento, y antes de guardar: la entidad ya
		// cambio en memoria, pero nada se escribio todavia y la transaccion se deshace entera.
		exigirVersion(plan, expectedVersion);

		PlanTratamiento guardado = planes.saveAndFlush(plan);

		asentar(guardado, tipo, previo, guardado.getEstado(), motivo, null,
				guardado.getUltimoNumeroVersion(), ahora, actor);
		auditar(eventoDeAuditoria, guardado.getId(), actor, acceso, previo.name(),
				guardado.getEstado().name(),
				Map.of("casoClinicoId", String.valueOf(guardado.getCasoClinicoId())), ahora);
		registrarSoporte(acceso, actor, guardado, operacion, ahora);

		log.info("Plan de tratamiento {}: planId={} de {} a {}",
				tipo, guardado.getId(), previo, guardado.getEstado());
		return vistaCompletaDe(guardado);
	}

	/**
	 * Finaliza el plan vigente del Caso, si hay otro distinto de este.
	 *
	 * <p>Su motivo lo escribe el sistema y <b>queda en el historial de ese plan</b>, no solo en el
	 * del que se activa: quien abra el plan viejo dentro de seis meses tiene que poder ver por que
	 * dejo de estar vigente sin que nadie lo cerrara a mano.
	 */
	private void finalizarElVigente(
			long organizationId,
			PlanTratamiento nuevo,
			Instant ahora,
			OperatingActor actor,
			AccesoClinico acceso) {

		Optional<PlanTratamiento> vigente =
				planes.buscarActivoDelCaso(organizationId, nuevo.getCasoClinicoId());
		if (vigente.isEmpty() || vigente.get().getId().equals(nuevo.getId())) {
			return;
		}

		PlanTratamiento anterior = vigente.get();
		anterior.finalizar(
				"Finalizado automaticamente al activar el plan " + nuevo.getNumeroPlan()
						+ " del caso",
				ahora, actor.accountId());

		// Flush explicito: el unique de plan activo se evalua fila por fila, y si el UPDATE del
		// plan nuevo saliera antes que este, chocaria contra el anterior dentro de la misma
		// transaccion. El orden no se le deja al ORM.
		PlanTratamiento finalizado = planes.saveAndFlush(anterior);

		asentar(finalizado, TipoEventoPlan.FINALIZACION, EstadoPlan.ACTIVO, EstadoPlan.FINALIZADO,
				finalizado.getMotivoFinalizacion(), null, finalizado.getUltimoNumeroVersion(),
				ahora, actor);
		auditar(AuditEvents.PLAN_TRATAMIENTO_FINALIZED, finalizado.getId(), actor, acceso, "ACTIVO",
				"FINALIZADO", Map.of("casoClinicoId",
						String.valueOf(finalizado.getCasoClinicoId()),
						"motivo", "ACTIVACION_DE_OTRO_PLAN",
						"planQueLoReemplaza", String.valueOf(nuevo.getId())),
				ahora);

		log.info("Plan de tratamiento finalizado por activacion de otro: planId={} nuevoPlanId={}",
				finalizado.getId(), nuevo.getId());
	}

	/**
	 * Resuelve y valida todas las ofertas declaradas, <b>antes</b> de escribir nada.
	 *
	 * <p>Se rechaza la primera que no este habilitada, con su id adentro: quien carga cinco
	 * practicas tiene que poder ver cual de las cinco es la que no entra.
	 *
	 * <p>La vigencia se evalua contra la fecha de <b>hoy en UTC</b> y no contra la zona de la sede,
	 * con la misma diferencia conocida y asumida que el alta de un Caso: una oferta que vence hoy
	 * podria aceptarse unas horas de mas o de menos segun el huso. La alternativa —traer la zona
	 * IANA de la sede para decidir si se puede planificar— acopla este servicio a
	 * {@code organization} por un borde que no cambia ninguna decision clinica.
	 *
	 * <p>Una misma oferta declarada dos veces se rechaza aca y no en el unique de la base: un 409
	 * de integridad no le dice al profesional cual repitio.
	 */
	private Map<Long, OfertaSnapshot> resolverOfertas(
			long organizationId, Long consultorioId, List<PlanItemPlanificado> planificados) {

		Map<Long, OfertaSnapshot> resueltas = new LinkedHashMap<>();
		Set<Long> vistas = new LinkedHashSet<>();
		LocalDate hoy = LocalDate.now(ZoneOffset.UTC);

		for (PlanItemPlanificado item : planificados) {
			if (!vistas.add(item.ofertaId())) {
				throw new IllegalArgumentException(
						"La oferta " + item.ofertaId() + " esta declarada dos veces en el plan");
			}
			OfertaSnapshot oferta = ofertas.find(organizationId, consultorioId, item.ofertaId())
					.orElseThrow(() -> new OfertaNoHabilitadaException(item.ofertaId()));
			if (!oferta.vigenteEl(hoy)) {
				throw new OfertaNoHabilitadaException(item.ofertaId());
			}
			resueltas.put(item.ofertaId(), oferta);
		}
		return resueltas;
	}

	/** Escribe una version de contenido y devuelve la fila, ya con id. */
	@SuppressWarnings("checkstyle:ParameterNumber")
	private PlanTratamientoVersion escribirVersion(
			PlanTratamiento plan,
			int numeroVersion,
			ContenidoDelPlan contenido,
			String motivo,
			Instant ahora,
			long actorAccountId) {

		// saveAndFlush: los items cuelgan del id de la version, y sin el flush ese id no existe
		// hasta el cierre de la transaccion.
		return versiones.saveAndFlush(new PlanTratamientoVersion(
				plan.getOrganizationId(),
				plan.getId(),
				numeroVersion,
				contenido.objetivos(),
				contenido.indicaciones(),
				contenido.frecuenciaSemanal(),
				contenido.duracionSemanas(),
				motivo,
				ahora,
				actorAccountId));
	}

	/** Escribe los items de esa version, congelando el snapshot de cada oferta. */
	private List<PlanItem> escribirItems(
			PlanTratamiento plan,
			PlanTratamientoVersion version,
			List<PlanItemPlanificado> planificados,
			Map<Long, OfertaSnapshot> resueltas) {

		if (planificados.isEmpty()) {
			return List.of();
		}
		return items.saveAll(planificados.stream()
				.map(item -> {
					OfertaSnapshot oferta = resueltas.get(item.ofertaId());
					return new PlanItem(
							plan.getOrganizationId(),
							version.getId(),
							oferta.id(),
							oferta.consultorioId(),
							oferta.nombreComercial(),
							oferta.servicioId(),
							item.cantidadPlanificada(),
							item.cantidadAutorizada());
				})
				.toList());
	}

	/**
	 * La organizacion del contexto, exigiendo que haya contexto.
	 *
	 * <p>Misma primera condicion que {@link AutorizacionClinica}, repetida por el mismo motivo que
	 * en {@code CasoClinicoService}: las operaciones sobre un plan llegan con el id del plan y no
	 * con el de la persona, asi que hay que resolver plan -&gt; caso -&gt; historia antes de poder
	 * preguntar por relacion asistencial, y esa resolucion ya necesita el tenant.
	 */
	private long organizacionDe(OperatingActor actor) {
		if (actor.contextOrganizationId() == null || actor.consultorioId() == null) {
			log.info("Operacion sobre planes de tratamiento sin contexto validado: accountId={}",
					actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private CasoClinico exigirCaso(long organizationId, long casoClinicoId) {
		return casos.findByIdAndOrganizationId(casoClinicoId, organizationId)
				.orElseThrow(() -> new CasoClinicoNotAccessibleException(casoClinicoId));
	}

	private PlanTratamiento exigirPlan(long organizationId, long planId) {
		return planes.findByIdAndOrganizationId(planId, organizationId)
				.orElseThrow(() -> new PlanTratamientoNotAccessibleException(planId));
	}

	/**
	 * La version vigente del plan.
	 *
	 * <p>Que no exista es imposible por construccion —el alta escribe la version 1 en la misma
	 * transaccion que la cabecera— asi que se falla fuerte en vez de devolver un plan sin contenido:
	 * una cabecera huerfana es un dato roto, no un estado del producto.
	 */
	private PlanTratamientoVersion exigirVersionVigente(PlanTratamiento plan) {
		return versiones.buscarVigente(plan.getOrganizationId(), plan.getId())
				.orElseThrow(() -> new IllegalStateException(
						"El plan " + plan.getId() + " no tiene ninguna version de contenido"));
	}

	/** La autorizacion clinica de una operacion que llego con el id del caso. */
	private AccesoClinico autorizarSobre(
			OperatingActor actor,
			CasoClinico caso,
			String permissionCode,
			String justificacion,
			String operacion) {

		HistoriaClinica historia = historias
				.findByIdAndOrganizationId(caso.getHistoriaClinicaId(), caso.getOrganizationId())
				.filter(HistoriaClinica::isVigente)
				.orElseThrow(() -> new CasoClinicoNotAccessibleException(caso.getId()));

		return AutorizacionClinica.exigir(permissionGuard, relaciones, actor, permissionCode,
				historia.getPersonaId(), justificacion, operacion);
	}

	/**
	 * La autorizacion clinica de una operacion que llego con el id del plan.
	 *
	 * <p>Resuelve plan -&gt; caso -&gt; historia -&gt; persona por si sola, que es lo que permite que
	 * el plan viva en ruta plana. Si el caso del plan no resuelve, el <b>plan</b> no es accesible:
	 * responder 404 sobre el plan es mas honesto que hablar de un caso que el cliente no nombro.
	 */
	private AccesoClinico autorizarSobrePlan(
			OperatingActor actor,
			PlanTratamiento plan,
			String permissionCode,
			String justificacion,
			String operacion) {

		CasoClinico caso = casos
				.findByIdAndOrganizationId(plan.getCasoClinicoId(), plan.getOrganizationId())
				.orElseThrow(() -> new PlanTratamientoNotAccessibleException(plan.getId()));

		HistoriaClinica historia = historias
				.findByIdAndOrganizationId(caso.getHistoriaClinicaId(), caso.getOrganizationId())
				.filter(HistoriaClinica::isVigente)
				.orElseThrow(() -> new PlanTratamientoNotAccessibleException(plan.getId()));

		return AutorizacionClinica.exigir(permissionGuard, relaciones, actor, permissionCode,
				historia.getPersonaId(), justificacion, operacion);
	}

	private PlanTratamientoView vistaCompletaDe(PlanTratamiento plan) {
		PlanTratamientoVersion vigente = versiones
				.buscarVigente(plan.getOrganizationId(), plan.getId())
				.orElse(null);
		return PlanTratamientoView.de(plan, vigente,
				vigente == null ? List.of() : itemsDe(vigente));
	}

	private List<PlanItemView> itemsDe(PlanTratamientoVersion version) {
		return items.buscarDeVersion(version.getOrganizationId(), version.getId()).stream()
				.map(PlanItemView::de)
				.toList();
	}

	private static List<PlanItemView> vistaDe(List<PlanItem> escritos) {
		return escritos.stream().map(PlanItemView::de).toList();
	}

	/**
	 * Escribe la transicion en el historial del plan, <b>dentro</b> de la misma transaccion.
	 *
	 * <p>No es post-commit y no puede serlo: un evento escrito despues del commit puede perderse y
	 * dejar la transicion sin rastro, que es exactamente el agujero que este historial existe para
	 * tapar. Mismo criterio que {@code caso_evento} y {@code turno_evento}.
	 *
	 * <p>{@code detalle} nunca lleva objetivos ni indicaciones. Este historial se lee al lado de la
	 * ficha; el contenido clinico se lee de la version, con su propio acceso auditado.
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	private void asentar(
			PlanTratamiento plan,
			TipoEventoPlan tipo,
			EstadoPlan anterior,
			EstadoPlan nuevo,
			String motivo,
			String detalle,
			Integer numeroVersion,
			Instant ahora,
			OperatingActor actor) {

		eventos.save(new PlanEvento(
				plan.getOrganizationId(),
				plan.getId(),
				tipo,
				anterior,
				nuevo,
				motivo,
				detalle,
				numeroVersion,
				ahora,
				actor.accountId()));
	}

	/**
	 * El control optimista, en un solo lugar.
	 *
	 * <p>Se lanza el mismo tipo que JPA usaria para que el handler global lo mapee igual y el
	 * cliente vea un solo comportamiento; la diferencia es que aca se detecta antes de escribir, con
	 * un mensaje que nombra la situacion.
	 */
	private static void exigirVersion(PlanTratamiento plan, long expectedVersion) {
		if (plan.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"El plan de tratamiento " + plan.getId() + " cambio desde que se leyo: volve a "
							+ "abrirlo antes de guardar");
		}
	}

	private void auditarLectura(
			OperatingActor actor,
			AccesoClinico acceso,
			PlanTratamiento plan,
			String alcance,
			Instant ahora) {

		auditTrail.record(entrada(AuditEvents.PLAN_TRATAMIENTO_ACCESSED,
				AuditEvents.ENTITY_PLAN_TRATAMIENTO, plan.getId(), actor, acceso, null, null,
				Map.of("casoClinicoId", String.valueOf(plan.getCasoClinicoId()),
						"alcance", alcance),
				ahora));
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	private void auditar(
			String eventType,
			Long entityId,
			OperatingActor actor,
			AccesoClinico acceso,
			String previo,
			String nuevo,
			Map<String, String> detalles,
			Instant ahora) {

		auditTrail.record(entrada(eventType, AuditEvents.ENTITY_PLAN_TRATAMIENTO, entityId, actor,
				acceso, previo, nuevo, detalles, ahora));
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	private static AuditEntry entrada(
			String eventType,
			String entityType,
			Long entityId,
			OperatingActor actor,
			AccesoClinico acceso,
			String previo,
			String nuevo,
			Map<String, String> detalles,
			Instant ahora) {

		Map<String, String> conVia = new LinkedHashMap<>(detalles);
		conVia.put("viaDeAcceso", acceso.via());

		return new AuditEntry(
				actor.contextOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				eventType,
				entityType,
				entityId,
				previo,
				nuevo,
				conVia,
				acceso.justificacion(),
				AuditEvents.correlationId(),
				ahora);
	}

	private void registrarSoporte(
			AccesoClinico acceso,
			OperatingActor actor,
			PlanTratamiento plan,
			String operacion,
			Instant ahora) {

		if (!acceso.viaSupportAccess()) {
			return;
		}
		supportAccessAuditor.record(new AuditEntry(
				plan.getOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				"SUPPORT_ACCESS_USED",
				AuditEvents.ENTITY_PLAN_TRATAMIENTO,
				plan.getId(),
				null,
				null,
				Map.of("operacion", operacion),
				acceso.justificacion(),
				AuditEvents.correlationId(),
				ahora));
	}
}
