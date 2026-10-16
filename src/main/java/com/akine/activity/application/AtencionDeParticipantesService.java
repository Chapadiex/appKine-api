package com.akine.activity.application;

import com.akine.activity.domain.AsistenciaActividad;
import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.InscripcionClase;
import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.exception.ClaseNotAccessibleException;
import com.akine.activity.domain.exception.ConsultorioNoAccesibleException;
import com.akine.activity.domain.exception.InscripcionNotAccessibleException;
import com.akine.activity.domain.exception.OfertaNoClinicaException;
import com.akine.activity.domain.exception.OfertaNotAccessibleException;
import com.akine.activity.domain.exception.ParticipacionSinAsistenciaException;
import com.akine.activity.domain.port.ActivityRepositoryPorts.AsistenciaActividadRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseProgramadaRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.InscripcionClaseRepositoryPort;
import com.akine.encounter.spi.ActorDeAtencion;
import com.akine.encounter.spi.AtencionDeParticipante;
import com.akine.encounter.spi.AtencionGrupal;
import com.akine.encounter.spi.PedidoDeAtencion;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.problem.ConTipoDeProblema;
import com.akine.platform.spi.problem.ProblemType;
import com.akine.platform.spi.transaccion.FilaLockPrevia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * El lado GRUPAL de la atencion clinica de los participantes de una clase (AKINE-08.05,
 * RF-M14-011..013, RN-M14-007..010).
 *
 * <h2>El reparto, el mismo de 08.04</h2>
 *
 * <p>Este servicio valida lo que {@code activity} sabe —la clase y la inscripcion son de la sede,
 * la oferta genera registro clinico (RF-M14-011 paso 5), la persona <b>estuvo</b>— y le pide la
 * atencion a {@code encounter.spi.AtencionGrupal}. {@code encounter} valida lo suyo
 * —{@code sesion:register}, el profesional— y le pregunta a {@code clinical} lo clinico
 * —{@code hc:write} con relacion o justificacion, la derivacion vigente—. {@code activity} no lee
 * ni guarda un solo dato clinico: lo que devuelve son ids y estados.
 *
 * <h2>Una clase no clinica nunca llega a {@code encounter}</h2>
 *
 * <p>RN-M14-008 y RF-M14-014: la oferta se mira primero, con el id de la <b>clase</b>, y una
 * oferta sin {@code generaRegistroClinico} corta con 409 {@code oferta-no-clinica} sin tocar nada
 * clinico. La asistencia de Yoga queda como asistencia y nada mas.
 *
 * <h2>Concurrencia: la fila de la asistencia</h2>
 *
 * <p>Dos inicios simultaneos de la misma participacion —doble clic, dos pestanas, el lote y el
 * boton individual a la vez— se serializan sobre la fila de {@code asistencia_actividad}, leida con
 * {@code FOR UPDATE} en {@code READ_COMMITTED}: el segundo espera, y su relectura ya ve la Sesion que
 * el primero commiteo. Es el mismo lock que toma la derivacion, a proposito: derivar, revertir y
 * atender la misma participacion quedan en fila, y una reversion no se puede colar entre la
 * lectura de la derivacion y el INSERT de la Sesion.
 *
 * <h2>El permiso de este lado: {@code inscripcion:read}</h2>
 *
 * <p>Ver al participante, como en 08.04. El permiso de atender —{@code sesion:register} y
 * {@code hc:write}— lo evaluan sus duenios. Un instructor no clinico ve la clase y no pasa de aca.
 */
@Service
public class AtencionDeParticipantesService {

	private static final Logger log = LoggerFactory.getLogger(AtencionDeParticipantesService.class);

	/** Igual que el lote de asistencia: mas que eso no es una lista compacta. */
	public static final int MAX_ITEMS_LOTE = AsistenciaService.MAX_ITEMS_LOTE;

	private final ClaseProgramadaRepositoryPort clases;
	private final InscripcionClaseRepositoryPort inscripciones;
	private final AsistenciaActividadRepositoryPort asistencias;
	private final OfertaDirectory ofertas;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final AtencionGrupal atenciones;

	/**
	 * La transaccion de cada item del lote, en {@code READ_COMMITTED} como {@link #iniciar}: la
	 * anotacion no aplica a una llamada interna, y sin esto el lote correria sin transaccion
	 * (el defecto 13 del 06/10, en {@code AsistenciaService}).
	 */
	private final TransactionTemplate transaccionPorItem;

	public AtencionDeParticipantesService(
			ClaseProgramadaRepositoryPort clases,
			InscripcionClaseRepositoryPort inscripciones,
			AsistenciaActividadRepositoryPort asistencias,
			OfertaDirectory ofertas,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			AtencionGrupal atenciones,
			PlatformTransactionManager transactionManager) {

		this.clases = clases;
		this.inscripciones = inscripciones;
		this.asistencias = asistencias;
		this.ofertas = ofertas;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.atenciones = atenciones;
		this.transaccionPorItem =
				FilaLockPrevia.transaccionDeNegocio(transactionManager, Isolation.READ_COMMITTED);
	}

	/**
	 * Abre la atencion clinica del participante, o devuelve la que ya existe. RF-M14-012.
	 *
	 * <p>Orden de los gates: contexto, sede, {@code inscripcion:read}, clase e inscripcion de la
	 * sede; la oferta de la clase es clinica; la asistencia existe y la persona estuvo (con lock);
	 * despues {@code encounter} y {@code clinical}. Todo en una transaccion.
	 *
	 * @param derivacionId la derivacion elegida, o {@code null} si hay una sola vigente
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public AtencionDeParticipanteView iniciar(
			OperatingActor actor,
			long consultorioId,
			long claseId,
			long inscripcionId,
			Long derivacionId,
			String justificacion) {

		Clase c = resolverClase(actor, consultorioId, claseId);
		exigirOfertaClinica(c);
		return iniciarEnClase(actor, c, inscripcionId, derivacionId, justificacion);
	}

	/**
	 * Abre la atencion de varios participantes, con <b>resultados parciales explicitos</b>.
	 *
	 * <p><b>No es transaccional, y es lo que hace que "independiente" signifique algo</b>: cada item
	 * abre y cierra su propia transaccion, y un fallo —un participante sin derivacion, otro sin
	 * justificacion— no revierte ni oculta a los demas. Es el patron del lote de asistencia de
	 * 08.03, con la misma trampa evitada: atrapar la excepcion adentro de una transaccion abierta no
	 * la des-marca.
	 *
	 * <p>Lo que vale para todo el lote se valida <b>una vez, antes del primer item</b> y sale como
	 * error del pedido: contexto, sede, permiso, clase y oferta clinica. Un lote de una clase de
	 * Yoga no es un lote con N fallos: es un pedido que no corresponde.
	 *
	 * <p><b>Abre, no cierra.</b> Cerrar en lote obligaria a mandar en un cuerpo la evolucion de
	 * varios pacientes, que es la "evolucion clinica global" que RF-M14-013 prohibe: cada cierre
	 * lleva lo de su paciente y va por la Sesion.
	 */
	public ResultadoDeAtencionGrupal iniciarLote(
			OperatingActor actor,
			long consultorioId,
			long claseId,
			List<PedidoDeAtencionDeParticipante> pedidos,
			String justificacion) {

		if (pedidos.size() > MAX_ITEMS_LOTE) {
			throw new IllegalArgumentException("Un lote admite hasta " + MAX_ITEMS_LOTE
					+ " participantes, y llegaron " + pedidos.size());
		}
		Clase c = resolverClase(actor, consultorioId, claseId);
		exigirOfertaClinica(c);

		List<ItemDeAtencionGrupal> items = new ArrayList<>(pedidos.size());
		for (PedidoDeAtencionDeParticipante pedido : pedidos) {
			items.add(ejecutarItem(actor, c, pedido, justificacion));
		}
		return ResultadoDeAtencionGrupal.de(items);
	}

	/**
	 * El panel de la clase: cada participante con su asistencia y sus atenciones. RF-M14-011.
	 *
	 * <p><b>Sin una sola lectura de derivaciones</b>: eso es una lectura clinica auditada por
	 * persona, y el panel la haria en loop. Lo que muestra sale de {@code encounter} en una sola
	 * consulta, auditada una vez: que Sesiones hay, en que estado, con que numero. Para ver a que
	 * Caso esta derivado alguien que todavia no tiene atencion, la pantalla pide sus derivaciones
	 * (08.04), que es la lectura que corresponde.
	 *
	 * <p>Una clase no clinica responde con las asistencias y sin atenciones, sin preguntar a
	 * {@code encounter}: no hay nada que pueda existir (RN-M14-008).
	 *
	 * <p>No es {@code readOnly}: la auditoria de {@code encounter} se une a esta transaccion.
	 */
	@Transactional
	public PanelDeAtencionGrupalView panel(
			OperatingActor actor, long consultorioId, long claseId) {

		Clase c = resolverClase(actor, consultorioId, claseId);
		OfertaSnapshot oferta = exigirOferta(c);
		List<AsistenciaActividad> presentes = asistencias.findDeLaClase(c.organizationId(), claseId);

		Map<Long, List<AtencionDeParticipante>> porParticipacion = !oferta.generaRegistroClinico()
				? Map.of()
				: atenciones.deLaClase(actorDeAtencion(actor, c, null), claseId).stream()
						.collect(Collectors.groupingBy(AtencionDeParticipante::participacionId));

		List<ParticipanteEnAtencionView> participantes = presentes.stream()
				.map(asistencia -> new ParticipanteEnAtencionView(
						asistencia.getInscripcionId(),
						asistencia.getPersonaId(),
						asistencia.getId(),
						asistencia.getResultado().name(),
						asistencia.getResultado().estuvo(),
						porParticipacion.getOrDefault(asistencia.getId(), List.of())))
				.toList();
		return new PanelDeAtencionGrupalView(claseId, oferta.generaRegistroClinico(), participantes);
	}

	// =================================================================================

	private AtencionDeParticipanteView iniciarEnClase(
			OperatingActor actor, Clase c, long inscripcionId, Long derivacionId,
			String justificacion) {

		InscripcionClase inscripcion = inscripciones
				.findByIdInScope(c.organizationId(), c.clase().getId(), inscripcionId)
				.orElseThrow(() -> new InscripcionNotAccessibleException(inscripcionId));

		// FOR UPDATE: ver la cabecera de la clase. El mismo lock que la derivacion.
		AsistenciaActividad asistencia = asistencias
				.findDeInscripcionParaDerivar(c.organizationId(), inscripcionId)
				.orElseThrow(() -> new ParticipacionSinAsistenciaException(inscripcionId, null));
		if (!asistencia.getResultado().estuvo()) {
			// No se atiende a quien no vino. Si el mostrador se equivoco, se corrige la asistencia.
			throw new ParticipacionSinAsistenciaException(
					inscripcionId, asistencia.getResultado().name());
		}

		AtencionDeParticipante atencion = atenciones.iniciar(new PedidoDeAtencion(
				actorDeAtencion(actor, c, justificacion),
				c.clase().getId(),
				asistencia.getId(),
				inscripcion.getPersonaId(),
				derivacionId));

		log.info("Atencion de participante: claseId={} inscripcionId={} sesionId={} yaExistia={}",
				c.clase().getId(), inscripcionId, atencion.sesionId(), atencion.yaExistia());
		return new AtencionDeParticipanteView(inscripcionId, atencion);
	}

	/**
	 * Un item del lote, en su propia transaccion. La excepcion sale de la plantilla <b>despues</b>
	 * del rollback, asi que atraparla aca no deja nada marcado a medio camino.
	 *
	 * <p>El error viaja con el <b>mismo</b> {@code problemType} que el camino individual. Las
	 * excepciones de {@code encounter} y {@code clinical} no se pueden importar desde aca (AGENT.md
	 * seccion 4, regla 2): declaran su tipo por {@link ConTipoDeProblema}.
	 */
	private ItemDeAtencionGrupal ejecutarItem(
			OperatingActor actor, Clase c, PedidoDeAtencionDeParticipante pedido,
			String justificacion) {

		long inscripcionId = pedido.inscripcionId();
		try {
			AtencionDeParticipanteView vista = transaccionPorItem.execute(estado -> iniciarEnClase(
					actor, c, inscripcionId, pedido.derivacionId(), justificacion));
			return ItemDeAtencionGrupal.ok(inscripcionId, vista.atencion());
		} catch (InscripcionNotAccessibleException excepcion) {
			// 404 y nunca "no es tuyo": un lote no puede ser un enumerador de inscripciones ajenas.
			return ItemDeAtencionGrupal.error(inscripcionId, ProblemType.NOT_FOUND,
					"La inscripcion no existe en esta clase.");
		} catch (ParticipacionSinAsistenciaException excepcion) {
			return ItemDeAtencionGrupal.error(inscripcionId,
					ProblemType.PARTICIPACION_SIN_ASISTENCIA, excepcion.getMessage());
		} catch (AccessDeniedException excepcion) {
			return ItemDeAtencionGrupal.error(inscripcionId, ProblemType.FORBIDDEN,
					"Sin permiso para atender a este participante.");
		} catch (OptimisticLockingFailureException excepcion) {
			return ItemDeAtencionGrupal.error(inscripcionId, ProblemType.CONCURRENT_MODIFICATION,
					"Otro pedido modifico este participante a la vez. Reintentar.");
		} catch (DataIntegrityViolationException excepcion) {
			// El respaldo del unique: con el lock no deberia pasar, y si pasa no es un 500.
			return ItemDeAtencionGrupal.error(inscripcionId, ProblemType.CONFLICT,
					"Otro pedido abrio esta atencion a la vez. Reintentar devuelve la existente.");
		} catch (RuntimeException excepcion) {
			if (excepcion instanceof ConTipoDeProblema tipada) {
				return ItemDeAtencionGrupal.error(
						inscripcionId, tipada.tipoDeProblema(), excepcion.getMessage());
			}
			// Un fallo no previsto no puede ocultar a los demas, y tampoco puede filtrar su
			// mensaje: se registra con su causa y viaja generico.
			log.error("Fallo no previsto en el lote de atencion grupal: claseId={} inscripcionId={}",
					c.clase().getId(), inscripcionId, excepcion);
			return ItemDeAtencionGrupal.error(inscripcionId, ProblemType.INTERNAL_ERROR,
					"Error no previsto al abrir esta atencion.");
		}
	}

	private Clase resolverClase(OperatingActor actor, long consultorioId, long claseId) {
		if (actor == null || actor.contextOrganizationId() == null) {
			// 403 y nunca 401: un 401 deja al frontend en bucle de login.
			throw new AccessDeniedException(
					"La atencion de participantes requiere un contexto de trabajo activo");
		}
		long organizationId = actor.contextOrganizationId();
		consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), PermissionCodes.INSCRIPCION_READ, organizationId, consultorioId,
				null, Instant.now()));
		ClaseProgramada clase = clases.findByIdInScope(organizationId, consultorioId, claseId)
				.orElseThrow(() -> new ClaseNotAccessibleException(claseId));
		return new Clase(organizationId, consultorioId, clase);
	}

	/** La oferta se resuelve con el id de la CLASE, nunca con uno del cliente. */
	private OfertaSnapshot exigirOferta(Clase c) {
		long ofertaId = c.clase().getOfertaId();
		return ofertas.find(c.organizationId(), c.consultorioId(), ofertaId)
				.orElseThrow(() -> new OfertaNotAccessibleException(ofertaId));
	}

	/** RN-M14-008: una clase no clinica nunca genera una Sesion. Se decide antes de todo lo demas. */
	private void exigirOfertaClinica(Clase c) {
		OfertaSnapshot oferta = exigirOferta(c);
		if (!oferta.generaRegistroClinico()) {
			throw new OfertaNoClinicaException(oferta.id());
		}
	}

	private static ActorDeAtencion actorDeAtencion(
			OperatingActor actor, Clase c, String justificacion) {
		return new ActorDeAtencion(actor.accountId(), actor.platformAdmin(), c.organizationId(),
				c.consultorioId(), justificacion);
	}

	private record Clase(long organizationId, long consultorioId, ClaseProgramada clase) {
	}
}
