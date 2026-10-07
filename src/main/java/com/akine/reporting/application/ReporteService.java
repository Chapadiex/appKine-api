package com.akine.reporting.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.reporting.domain.PermissionCodes;
import com.akine.reporting.domain.exception.ConsultorioNoAccesibleException;
import com.akine.reporting.domain.exception.RangoDeReporteInvalidoException;
import com.akine.reporting.spi.AdvertenciaDeReporte;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.ReporteCode;
import com.akine.reporting.spi.ReporteContributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Los reportes del MVP (M23): RF-M23-001 a RF-M23-005.
 *
 * <h2>Este servicio no consulta ninguna tabla</h2>
 *
 * <p>No tiene un solo repositorio, y el modulo no tiene capa {@code infrastructure}. <b>Es la
 * prueba estructural de que la etapa no materializo nada:</b> no hay tabla de KPI, no hay snapshot
 * diario, no hay proyeccion refrescada por un job. Cada seccion la calcula, al momento de la
 * lectura, el modulo propietario de la tabla de la que sale.
 *
 * <p>Es la misma decision que sostienen la disponibilidad efectiva (02.04), el Paciente 360
 * (03.02), el timeline clinico (04.02), el motor de slots (05.01) y la comparacion de mediciones
 * (06.03). El argumento no es de pureza: <b>una tabla de resumen se desincroniza el dia que
 * alguien escribe por otro camino</b>, y en este dominio los caminos que escriben llegan de a poco
 * —el devengado de financiador se va a recablear, 06.04 va a cambiar como avanza el plan, una
 * anulacion retroactiva toca un periodo ya "cerrado"—. Cada uno de esos cambios seria un backfill
 * que alguien tiene que acordarse de correr; cuando no se acuerda, el tablero no se rompe: miente.
 *
 * <h2>Lo que este servicio si hace</h2>
 *
 * <ol>
 *   <li>Resuelve el tenant <b>desde el contexto del actor</b>, nunca desde un parametro.</li>
 *   <li>Valida la sede contra el tenant: cross-tenant es <b>404</b>, nunca 403.</li>
 *   <li>Exige {@code reporte:read}.</li>
 *   <li>Valida el periodo y lo proyecta a la zona <b>de la sede</b>.</li>
 *   <li>Recorta por permiso de seccion y <b>declara lo que omitio</b>.</li>
 *   <li>Recorta a la <b>actividad propia</b> cuando {@code reporte:read} se concedio "Limitado"
 *       (AKINE-G-1, DP-15).</li>
 *   <li>Audita cuando alguna seccion es clinica, y cada uso de un acceso de soporte.</li>
 * </ol>
 *
 * <h2>Los dos "Limitado" de la matriz, y por que se resuelven distinto</h2>
 *
 * <p>La matriz §2 le pone "Limitado" a dos celdas de Ver Reportes y la §4 define cada una:
 *
 * <ul>
 *   <li><b>ADMINISTRATIVO</b> — "solo operativos y de caja, sin contenido clinico". No necesita
 *       nada de esta clase: no tiene {@code hc:read} ni {@code sesion:register}, asi que las
 *       secciones clinicas caen por el recorte por permiso de seccion y quedan declaradas.</li>
 *   <li><b>PROFESIONAL</b> — "solo reportes de su propia actividad". Ese no lo resuelve ningun
 *       permiso de seccion: tiene {@code turno:read} sobre la agenda de toda su sede. Lo resuelve el
 *       alcance {@code ACTIVIDAD_PROPIA} de la decision: la consulta viaja con sus memberships y
 *       cada seccion cuenta solo lo suyo. <b>Una seccion que no sabe recortar se omite</b>, nunca se
 *       calcula entera.</li>
 * </ul>
 */
@Service
public class ReporteService {

	private static final Logger log = LoggerFactory.getLogger(ReporteService.class);

	/**
	 * Ventana maxima del periodo, en dias.
	 *
	 * <p>Un anio mas un dia, para que "todo 2026" entre con sus dos extremos inclusive. Es el tope
	 * que reemplaza al export asincrono que el plan pide "cuando exceda el tiempo interactivo":
	 * sin una medicion contra MySQL real no hay forma de saber cuando eso pasa, y acotar el pedido
	 * es lo unico honesto que se puede hacer mientras tanto.
	 */
	static final int MAXIMO_DIAS = 366;

	/**
	 * Tope de filas de detalle por seccion.
	 *
	 * <p>RNF-M23-004 pide cargar solo lo necesario. El detalle del reporte es una agregacion —por
	 * dia, por estado, por financiador—, asi que quinientas filas son un anio largo de cualquiera
	 * de esos cortes; un reporte que necesite mas no es un reporte, es un listado, y para eso esta
	 * el modulo duenio con su propia paginacion.
	 */
	static final int LIMITE_FILAS = 500;

	private static final String EVENTO_REPORTE_CONSULTADO = "REPORTE_CONSULTADO";
	private static final String ENTIDAD_REPORTE = "Reporte";
	private static final String MDC_TRACE_ID = "traceId";

	private static final String EVENTO_SOPORTE_USADO = "SUPPORT_ACCESS_USED";

	/**
	 * Lo que se declara en {@code omitidas} para una seccion que no sabe recortarse a la actividad
	 * propia: le falta {@code reporte:read} sin limitar, con el alcance de la sede.
	 */
	static final String PERMISO_SIN_LIMITAR = PermissionCodes.REPORTE_READ;

	static final String ADVERTENCIA_ACTIVIDAD_PROPIA = "alcance-actividad-propia";

	private final ConsultorioDirectory consultorios;
	private final ConsultorioMembershipDirectory memberships;
	private final PermissionGuard permissionGuard;
	private final PermissionEvaluator permissionEvaluator;
	private final AuditTrail auditTrail;
	private final ReportingSupportAccessAuditor supportAccessAuditor;
	private final List<ReporteContributor> contribuyentes;

	public ReporteService(
			ConsultorioDirectory consultorios,
			ConsultorioMembershipDirectory memberships,
			PermissionGuard permissionGuard,
			PermissionEvaluator permissionEvaluator,
			AuditTrail auditTrail,
			ReportingSupportAccessAuditor supportAccessAuditor,
			List<ReporteContributor> contribuyentes) {

		this.consultorios = consultorios;
		this.memberships = memberships;
		this.permissionGuard = permissionGuard;
		this.permissionEvaluator = permissionEvaluator;
		this.auditTrail = auditTrail;
		this.supportAccessAuditor = supportAccessAuditor;
		// Spring inyecta la lista vacia cuando no hay ninguna implementacion, que es un estado
		// valido aunque hoy no ocurra: un reporte sin secciones sigue siendo una respuesta.
		this.contribuyentes = List.copyOf(contribuyentes);
	}

	/**
	 * Genera un reporte.
	 *
	 * <p><b>{@code @Transactional} sin {@code readOnly} a proposito.</b> Un reporte con seccion
	 * clinica escribe un evento de auditoria —AKINE-04.01: toda lectura clinica se audita, no solo
	 * las mutaciones— y una transaccion de solo lectura no puede escribirlo. El costo es un INSERT
	 * por consulta clinica; la alternativa es un acceso clinico sin rastro.
	 */
	@Transactional
	public ReporteView generar(
			OperatingActor actor, long consultorioId, ReporteCode reporte,
			LocalDate desde, LocalDate hasta) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);

		// El gate del tablero va ANTES de validar el rango: un actor sin permiso no debe poder
		// distinguir un rango bueno de uno malo, porque eso ya es informacion sobre el sistema.
		PermissionDecision decision = exigirReporteRead(actor, organizationId, consultorioId);
		registrarSoporte(decision, actor, organizationId, consultorioId, "generar", reporte);

		// El recorte se resuelve ANTES de construir la consulta y ANTES de invocar a nadie: una
		// consulta limitada que saliera sin sus memberships seria el reporte de toda la sede.
		Set<Long> actividadPropiaDe = decision.limitadaAActividadPropia()
				? membershipsDelActor(actor, organizationId)
				: null;

		ZoneId zona = ZoneId.of(sede.timezone());
		ConsultaDeReporte consulta = construirConsulta(
				organizationId, consultorioId, desde, hasta, zona, actividadPropiaDe);

		// Los permisos se resuelven UNA vez, no uno por seccion: con cinco secciones serian cinco
		// resoluciones de memberships y grants para responder una sola pantalla.
		Set<String> permisos = permissionEvaluator.effectivePermissions(
				actor.accountId(), organizationId, consultorioId);

		List<AporteDeReporte> secciones = new ArrayList<>();
		List<ReporteView.SeccionOmitida> omitidas = new ArrayList<>();
		List<AdvertenciaDeReporte> advertencias = new ArrayList<>();
		boolean alcanzaDatosClinicos = false;

		for (ReporteContributor contribuyente : contribuyentes) {
			if (!contribuyente.reportes().contains(reporte)) {
				continue;
			}
			String permiso = contribuyente.permisoRequerido();
			if (permiso != null && !permisos.contains(permiso)) {
				omitidas.add(new ReporteView.SeccionOmitida(contribuyente.seccion(), permiso));
				continue;
			}
			if (consulta.recortadaAActividadPropia() && !contribuyente.filtraPorActividadPropia()) {
				// No sabe contar solo lo del actor, asi que no cuenta nada. Calcularla entera le
				// mostraria al profesional la actividad de todo el equipo; omitirla en silencio le
				// haria leer "cero". Se declara, como cualquier otra omision.
				omitidas.add(new ReporteView.SeccionOmitida(
						contribuyente.seccion(), PERMISO_SIN_LIMITAR));
				continue;
			}
			alcanzaDatosClinicos |= contribuyente.esClinica();

			AporteDeReporte aporte = contribuyente.aportar(consulta);
			if (aporte == null) {
				aporte = AporteDeReporte.vacio(contribuyente.seccion());
			}
			secciones.add(aporte);
			advertencias.addAll(aporte.advertencias());
		}

		if (consulta.recortadaAActividadPropia()) {
			// Sin esto el profesional lee "12 turnos" y entiende que son los de la sede. Va como
			// advertencia y no como campo nuevo para no cambiar el contrato: la pantalla ya
			// muestra las advertencias, y el CSV tambien.
			advertencias.add(0, new AdvertenciaDeReporte(
					null,
					ADVERTENCIA_ACTIVIDAD_PROPIA,
					"Reporte limitado a tu propia actividad: turnos, sesiones y casos que "
							+ "atendiste o te fueron asignados. No incluye al resto del equipo."));
		}

		if (alcanzaDatosClinicos) {
			auditarAccesoClinico(actor, organizationId, consultorioId, reporte, consulta);
		}

		log.debug("Reporte resuelto: reporte={} sede={} secciones={} omitidas={} advertencias={}",
				reporte, consultorioId, secciones.size(), omitidas.size(), advertencias.size());

		return new ReporteView(
				reporte, consultorioId, consulta.desde(), consulta.hasta(), zona.getId(),
				Instant.now(), secciones, omitidas, advertencias);
	}

	/**
	 * El catalogo de reportes con sus secciones y el permiso que cada una pide.
	 *
	 * <p>Existe para que la pantalla pueda dibujar el menu sin ejecutar cinco reportes, y para que
	 * pueda explicar de antemano por que una seccion no va a aparecer. Se autoriza con el mismo
	 * {@code reporte:read}: es metadato del tablero, no un dato del negocio.
	 */
	@Transactional(readOnly = true)
	public Map<ReporteCode, List<SeccionDisponible>> catalogo(
			OperatingActor actor, long consultorioId) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		PermissionDecision decision = exigirReporteRead(actor, organizationId, consultorioId);
		registrarSoporte(decision, actor, organizationId, consultorioId, "catalogo", null);

		Map<ReporteCode, List<SeccionDisponible>> catalogo = new LinkedHashMap<>();
		for (ReporteCode reporte : ReporteCode.values()) {
			List<SeccionDisponible> disponibles = new ArrayList<>();
			for (ReporteContributor contribuyente : contribuyentes) {
				if (contribuyente.reportes().contains(reporte)) {
					disponibles.add(new SeccionDisponible(
							contribuyente.seccion(),
							contribuyente.titulo(),
							contribuyente.permisoRequerido(),
							contribuyente.esClinica()));
				}
			}
			catalogo.put(reporte, disponibles);
		}
		return catalogo;
	}

	// =================================================================================
	// Precondiciones
	// =================================================================================

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("Los reportes requieren un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	/** Cross-tenant es <b>404 y nunca 403</b>: un 403 confirma que la sede existe. */
	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	/**
	 * Exige {@code reporte:read} con la sede como alcance y devuelve la decision.
	 *
	 * <p>La decision importa, no solo que no lance: su alcance dice si hay que recortar a la
	 * actividad propia, y {@code viaSupportAccess} si hay que dejar {@code SUPPORT_ACCESS_USED}.
	 */
	private PermissionDecision exigirReporteRead(
			OperatingActor actor, long organizationId, long consultorioId) {

		return permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.REPORTE_READ,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}

	/**
	 * Las memberships de la cuenta en la organizacion: la "actividad propia" se cuenta contra
	 * cualquiera de ellas.
	 *
	 * <p>No se filtra por vigencia ni por sede: lo atendido con una membership que despues se cerro
	 * sigue siendo actividad del actor, y la sede ya la recorta cada consulta. Si no hay ninguna
	 * —imposible si el evaluador acaba de conceder por una de ellas, salvo una revocacion en el
	 * medio— se corta con 403: un recorte vacio no es "sin actividad", es un actor sin vinculo.
	 */
	private Set<Long> membershipsDelActor(OperatingActor actor, long organizationId) {
		Set<Long> ids = memberships.findByAccount(organizationId, actor.accountId()).stream()
				.map(ConsultorioMembershipSnapshot::membershipId)
				.collect(Collectors.toUnmodifiableSet());
		if (ids.isEmpty()) {
			throw new AccessDeniedException(
					"La cuenta no tiene un vinculo con la organizacion para limitar el reporte");
		}
		return ids;
	}

	/**
	 * Deja {@code SUPPORT_ACCESS_USED} cuando {@code reporte:read} se concedio por un acceso de
	 * soporte (matriz §7: cada operacion amparada, no solo el otorgamiento).
	 */
	private void registrarSoporte(
			PermissionDecision decision, OperatingActor actor, long organizationId,
			long consultorioId, String operacion, ReporteCode reporte) {

		if (decision == null || !decision.viaSupportAccess()) {
			return;
		}
		Map<String, String> detalle = new LinkedHashMap<>();
		detalle.put("permiso", PermissionCodes.REPORTE_READ);
		detalle.put("operacion", operacion);
		if (reporte != null) {
			detalle.put("reporte", reporte.name());
		}
		supportAccessAuditor.record(new AuditEntry(
				organizationId,
				consultorioId,
				actor.accountId(),
				EVENTO_SOPORTE_USADO,
				ENTIDAD_REPORTE,
				null,
				null,
				null,
				detalle,
				null,
				MDC.get(MDC_TRACE_ID),
				Instant.now()));
	}

	/**
	 * Valida el periodo y lo proyecta a instantes con la zona <b>de la sede</b>.
	 *
	 * <p>La conversion se hace <b>una sola vez, aca</b>, y viaja resuelta en la consulta. Si cada
	 * contribuyente la hiciera por su cuenta, el primero que use la zona del servidor imputaria un
	 * cobro de las 21:30 en Ushuaia al dia siguiente, y el reporte no fallaria: daria otro numero.
	 *
	 * <p>{@code hastaInstante} es el dia siguiente a las 00:00 y es <b>exclusivo</b>. Comparar con
	 * {@code <=} contra el ultimo instante del dia perderia lo que haya ocurrido en el ultimo
	 * microsegundo y, peor, haria que el resultado dependiera de la precision de la columna.
	 */
	private static ConsultaDeReporte construirConsulta(
			long organizationId, long consultorioId, LocalDate desde, LocalDate hasta, ZoneId zona,
			Set<Long> actividadPropiaDe) {

		if (desde == null || hasta == null) {
			throw new RangoDeReporteInvalidoException(
					"El reporte necesita un periodo: desde y hasta son obligatorios", MAXIMO_DIAS);
		}
		if (hasta.isBefore(desde)) {
			throw new RangoDeReporteInvalidoException(
					"El periodo esta invertido: 'hasta' es anterior a 'desde'", MAXIMO_DIAS);
		}
		long dias = ChronoUnit.DAYS.between(desde, hasta) + 1;
		if (dias > MAXIMO_DIAS) {
			throw new RangoDeReporteInvalidoException(
					"El periodo pedido son " + dias + " dias y el maximo es " + MAXIMO_DIAS,
					MAXIMO_DIAS);
		}

		Instant desdeInstante = desde.atStartOfDay(zona).toInstant();
		Instant hastaInstante = hasta.plusDays(1).atStartOfDay(zona).toInstant();

		return new ConsultaDeReporte(
				organizationId, consultorioId, desde, hasta, zona,
				desdeInstante, hastaInstante, LIMITE_FILAS, actividadPropiaDe);
	}

	/**
	 * Registra el acceso clinico, <b>dentro de la transaccion del negocio</b>.
	 *
	 * <p>No es un listener post-commit y no debe convertirse en uno: si el registro falla, la
	 * consulta no se confirma. AKINE-04.01 dejo fijado que toda lectura clinica se audita, y el
	 * hecho de que el aporte sea agregado no cambia nada — lo que la regla protege es el acceso.
	 *
	 * <p>El detalle lleva el periodo y el reporte, <b>nunca contenido clinico</b>: la tabla de
	 * auditoria termina en backups y meter ahi un dato sensible lo multiplica sin control.
	 */
	private void auditarAccesoClinico(
			OperatingActor actor, long organizationId, long consultorioId,
			ReporteCode reporte, ConsultaDeReporte consulta) {

		auditTrail.record(new AuditEntry(
				organizationId,
				consultorioId,
				actor.accountId(),
				EVENTO_REPORTE_CONSULTADO,
				ENTIDAD_REPORTE,
				null,
				null,
				null,
				Map.of(
						"reporte", reporte.name(),
						"desde", consulta.desde().toString(),
						"hasta", consulta.hasta().toString(),
						// G-1: quien audite tiene que poder distinguir el tablero de la sede del
						// de la actividad propia de un profesional.
						"alcance", consulta.recortadaAActividadPropia()
								? PermissionDecision.ALCANCE_ACTIVIDAD_PROPIA
								: "SEDE"),
				null,
				MDC.get(MDC_TRACE_ID),
				Instant.now()));
	}

	/** Una seccion que un reporte puede traer, con el permiso que hace falta para verla. */
	public record SeccionDisponible(
			String seccion, String titulo, String permisoRequerido, boolean clinica) {
	}
}
