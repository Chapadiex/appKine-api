package com.akine.encounter.application;

import com.akine.clinical.spi.AccesoClinicoConcedido;
import com.akine.clinical.spi.AccesoClinicoDirectory;
import com.akine.clinical.spi.ActorClinico;
import com.akine.clinical.spi.CasoDetalleSnapshot;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.evolucion.AnalisisDeEvolucion;
import com.akine.encounter.domain.evolucion.ConfiguracionDeEvolucion;
import com.akine.encounter.domain.evolucion.Hallazgo;
import com.akine.encounter.domain.exception.EvolucionDeshabilitadaException;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.organization.spi.FuncionIa;
import com.akine.organization.spi.FuncionesIaDelCentro;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * La evolucion de un Caso Clinico (M31, RF-M31-001 y RF-M31-002).
 *
 * <h2>Orden, y por que</h2>
 *
 * <ol>
 *   <li>Interruptor apagado → 404 (RN-M31-010).</li>
 *   <li>Caso de la organizacion del actor, o 404: un 403 confirmaria que existe.</li>
 *   <li>La puerta de la Historia Clinica: {@code hc:read} y relacion asistencial o justificacion
 *       (RN-M31-003). El analisis muestra sesiones de todos los profesionales del caso, asi que no
 *       alcanza con {@code sesion:register}.</li>
 *   <li>Lectura en una cantidad fija de consultas (RNF-M31-002), calculo puro y auditoria.</li>
 * </ol>
 *
 * <h2>La transaccion no es de solo lectura</h2>
 *
 * <p>Porque audita: una lectura {@code readOnly} que escribe en {@code audit_event} es el defecto
 * 14 del 11/10. Nada mas se escribe (RN-M31-001, RN-M31-002).
 */
@Service
public class EvolucionDelCasoService {

	private static final Logger log = LoggerFactory.getLogger(EvolucionDelCasoService.class);

	/** RNF-M31-002: las sesiones mas recientes que entran en el analisis. */
	static final int TOPE_DE_SESIONES = 200;
	static final String EVENTO = "EVOLUCION_DEL_CASO_ANALIZADA";
	static final String ENTIDAD = "CasoClinico";
	private static final String OPERACION = "Ver la evolucion del caso";

	private final ConfiguracionDeEvolucion configuracion;
	private final AccesoClinicoDirectory accesoClinico;
	private final AnalizadorDelCaso analizador;
	private final SesionRepositoryPort sesiones;
	private final AuditTrail auditTrail;
	private final FuncionesIaDelCentro funcionesIa;
	private final Clock clock;

	@Autowired
	public EvolucionDelCasoService(
			ConfiguracionDeEvolucion configuracion,
			AccesoClinicoDirectory accesoClinico,
			AnalizadorDelCaso analizador,
			SesionRepositoryPort sesiones,
			AuditTrail auditTrail,
			FuncionesIaDelCentro funcionesIa) {
		this(configuracion, accesoClinico, analizador, sesiones, auditTrail, funcionesIa, Clock.systemUTC());
	}

	EvolucionDelCasoService(
			ConfiguracionDeEvolucion configuracion,
			AccesoClinicoDirectory accesoClinico,
			AnalizadorDelCaso analizador,
			SesionRepositoryPort sesiones,
			AuditTrail auditTrail,
			FuncionesIaDelCentro funcionesIa,
			Clock clock) {
		this.funcionesIa = funcionesIa;
		this.configuracion = configuracion;
		this.accesoClinico = accesoClinico;
		this.analizador = analizador;
		this.sesiones = sesiones;
		this.auditTrail = auditTrail;
		this.clock = clock;
	}

	@Transactional
	public EvolucionDelCasoView analizar(
			OperatingActor actor, long consultorioId, long casoId, String justificacion) {

		if (!configuracion.habilitada()) {
			throw new EvolucionDeshabilitadaException();
		}
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La evolucion del caso requiere un contexto de trabajo activo");
		}
		long organizationId = actor.contextOrganizationId();
		if (!funcionesIa.prendida(organizationId, FuncionIa.EVOLUCION)) {
			throw new EvolucionDeshabilitadaException();
		}

		// Resolver el caso antes de exigir el acceso: uno de otra organizacion da 404, no 403.
		CasoAnalizado calculo = analizador.analizar(organizationId, consultorioId, casoId);
		CasoDetalleSnapshot caso = calculo.caso();
		AnalisisDeEvolucion analisis = calculo.analisis();

		AccesoClinicoConcedido acceso = accesoClinico.exigirLectura(
				new ActorClinico(actor.accountId(), actor.platformAdmin(), organizationId, consultorioId),
				calculo.historia().personaId(), justificacion, OPERACION);

		List<String> advertencias = advertencias(sesiones, organizationId, calculo);
		List<EvolucionDelCasoView.HallazgoView> hallazgos = analisis.hallazgos().stream()
				.map(h -> new EvolucionDelCasoView.HallazgoView(
						h.codigo(), h.severidad(), TextosDeHallazgo.de(h), h.sesiones(), h.turnos()))
				.toList();

		auditar(actor, organizationId, consultorioId, casoId, acceso, analisis);
		log.info("Evolucion del caso analizada: casoId={} sesiones={} hallazgos={}",
				casoId, analisis.sesionesAnalizadas(), hallazgos.size());

		return new EvolucionDelCasoView(
				caso.id(), caso.numeroCaso(), caso.estado(), caso.diagnosticoPresuntivo(),
				caso.objetivoTerapeutico(), analisis, hallazgos, calculo.plan().orElse(null), advertencias);
	}

	/** Lo que el analisis dejo afuera: el recorte por tope y las sesiones del periodo sin caso. Tambien la fase 2. */
	static List<String> advertencias(SesionRepositoryPort sesiones, long organizationId, CasoAnalizado calculo) {
		CasoDetalleSnapshot caso = calculo.caso();
		List<Sesion> cerradas = calculo.cerradas();
		boolean recortada = calculo.recortada();

		List<String> advertencias = new ArrayList<>();
		if (recortada) {
			advertencias.add("Se analizaron las " + TOPE_DE_SESIONES + " sesiones más recientes del caso.");
		}
		if (!cerradas.isEmpty()) {
			long sinCaso = sesiones.contarCerradasSinCasoDeHistoria(organizationId, caso.historiaClinicaId(),
					cerradas.getFirst().getCerradaEn(), cerradas.getLast().getCerradaEn());
			if (sinCaso > 0) {
				advertencias.add(sinCaso == 1
						? "1 sesión del período no tiene caso asignado y no entra en el análisis."
						: sinCaso + " sesiones del período no tienen caso asignado y no entran en el análisis.");
			}
		}
		return advertencias;
	}

	private void auditar(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long casoId,
			AccesoClinicoConcedido acceso,
			AnalisisDeEvolucion analisis) {

		auditTrail.record(new AuditEntry(
				organizationId,
				consultorioId,
				actor.accountId(),
				EVENTO,
				ENTIDAD,
				casoId,
				null,
				null,
				Map.of("sesiones", String.valueOf(analisis.sesionesAnalizadas()),
						"hallazgos", analisis.hallazgos().stream()
								.map(Hallazgo::codigo).map(Enum::name).collect(Collectors.joining(",")),
						"via", acceso.via()),
				acceso.justificacion(),
				AuditEvents.correlationId(),
				Instant.now(clock)));
	}
}
