package com.akine.encounter.application;

import com.akine.clinical.spi.AccesoClinicoConcedido;
import com.akine.clinical.spi.AccesoClinicoDirectory;
import com.akine.clinical.spi.ActorClinico;
import com.akine.clinical.spi.CasoDetalleSnapshot;
import com.akine.clinical.spi.CasoDirectory;
import com.akine.clinical.spi.HistoriaClinicaDirectory;
import com.akine.clinical.spi.HistoriaClinicaSnapshot;
import com.akine.clinical.spi.PlanVigenteDirectory;
import com.akine.clinical.spi.PlanVigenteSnapshot;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.SesionMedicion;
import com.akine.encounter.domain.TratamientoRealizado;
import com.akine.encounter.domain.evolucion.AnalisisDeEvolucion;
import com.akine.encounter.domain.evolucion.ConfiguracionDeEvolucion;
import com.akine.encounter.domain.evolucion.DatosDelCaso;
import com.akine.encounter.domain.evolucion.Hallazgo;
import com.akine.encounter.domain.evolucion.ReglasDeEvolucion;
import com.akine.encounter.domain.exception.CasoNoAsignableException;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.EvolucionDeshabilitadaException;
import com.akine.encounter.domain.port.SesionMedicionRepositoryPort;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.encounter.domain.port.TratamientoRepositoryPorts.TratamientoRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.ZonaDeSede;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.spi.MedicionDefinicionSnapshot;
import com.akine.resource.spi.MedicionDirectory;
import com.akine.scheduling.spi.TurnoDelCasoSnapshot;
import com.akine.scheduling.spi.TurnosDelCasoDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
	private final ConsultorioDirectory consultorios;
	private final CasoDirectory casos;
	private final HistoriaClinicaDirectory historias;
	private final AccesoClinicoDirectory accesoClinico;
	private final PlanVigenteDirectory planes;
	private final TurnosDelCasoDirectory turnos;
	private final SesionRepositoryPort sesiones;
	private final SesionMedicionRepositoryPort mediciones;
	private final TratamientoRepositoryPort tratamientos;
	private final MedicionDirectory definiciones;
	private final AuditTrail auditTrail;
	private final Clock clock;

	@Autowired
	@SuppressWarnings("checkstyle:ParameterNumber")
	public EvolucionDelCasoService(
			ConfiguracionDeEvolucion configuracion,
			ConsultorioDirectory consultorios,
			CasoDirectory casos,
			HistoriaClinicaDirectory historias,
			AccesoClinicoDirectory accesoClinico,
			PlanVigenteDirectory planes,
			TurnosDelCasoDirectory turnos,
			SesionRepositoryPort sesiones,
			SesionMedicionRepositoryPort mediciones,
			TratamientoRepositoryPort tratamientos,
			MedicionDirectory definiciones,
			AuditTrail auditTrail) {
		this(configuracion, consultorios, casos, historias, accesoClinico, planes, turnos, sesiones,
				mediciones, tratamientos, definiciones, auditTrail, Clock.systemUTC());
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	EvolucionDelCasoService(
			ConfiguracionDeEvolucion configuracion,
			ConsultorioDirectory consultorios,
			CasoDirectory casos,
			HistoriaClinicaDirectory historias,
			AccesoClinicoDirectory accesoClinico,
			PlanVigenteDirectory planes,
			TurnosDelCasoDirectory turnos,
			SesionRepositoryPort sesiones,
			SesionMedicionRepositoryPort mediciones,
			TratamientoRepositoryPort tratamientos,
			MedicionDirectory definiciones,
			AuditTrail auditTrail,
			Clock clock) {
		this.configuracion = configuracion;
		this.consultorios = consultorios;
		this.casos = casos;
		this.historias = historias;
		this.accesoClinico = accesoClinico;
		this.planes = planes;
		this.turnos = turnos;
		this.sesiones = sesiones;
		this.mediciones = mediciones;
		this.tratamientos = tratamientos;
		this.definiciones = definiciones;
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
		ConsultorioSnapshot sede = consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
		CasoDetalleSnapshot caso = casos.detalle(organizationId, casoId)
				.orElseThrow(() -> new CasoNoAsignableException(casoId, CasoNoAsignableException.Motivo.NO_ACCESIBLE));
		HistoriaClinicaSnapshot historia = historias.findPorId(organizationId, caso.historiaClinicaId())
				.orElseThrow(() -> new CasoNoAsignableException(casoId, CasoNoAsignableException.Motivo.NO_ACCESIBLE));

		AccesoClinicoConcedido acceso = accesoClinico.exigirLectura(
				new ActorClinico(actor.accountId(), actor.platformAdmin(), organizationId, consultorioId),
				historia.personaId(), justificacion, OPERACION);

		ZoneId zona = ZonaDeSede.de(sede);
		LocalDate hoy = LocalDate.now(clock.withZone(zona));

		List<Sesion> leidas = sesiones.findCerradasDelCaso(organizationId, casoId, TOPE_DE_SESIONES + 1);
		boolean recortada = leidas.size() > TOPE_DE_SESIONES;
		List<Sesion> cerradas = new ArrayList<>(leidas.subList(0, Math.min(leidas.size(), TOPE_DE_SESIONES)));
		Collections.reverse(cerradas);

		Optional<PlanVigenteSnapshot> plan = planes.delCaso(organizationId, casoId);
		DatosDelCaso datos = datos(organizationId, caso, cerradas, plan.orElse(null),
				turnos.delCaso(organizationId, casoId), zona);
		AnalisisDeEvolucion analisis = ReglasDeEvolucion.analizar(datos, configuracion.umbrales(), hoy);

		List<String> advertencias = advertencias(organizationId, caso, cerradas, recortada);
		List<EvolucionDelCasoView.HallazgoView> hallazgos = analisis.hallazgos().stream()
				.map(h -> new EvolucionDelCasoView.HallazgoView(
						h.codigo(), h.severidad(), TextosDeHallazgo.de(h), h.sesiones(), h.turnos()))
				.toList();

		auditar(actor, organizationId, consultorioId, casoId, acceso, analisis);
		log.info("Evolucion del caso analizada: casoId={} sesiones={} hallazgos={}",
				casoId, analisis.sesionesAnalizadas(), hallazgos.size());

		return new EvolucionDelCasoView(
				caso.id(), caso.numeroCaso(), caso.estado(), caso.diagnosticoPresuntivo(),
				caso.objetivoTerapeutico(), analisis, hallazgos, plan.orElse(null), advertencias);
	}

	private DatosDelCaso datos(
			long organizationId,
			CasoDetalleSnapshot caso,
			List<Sesion> cerradas,
			PlanVigenteSnapshot plan,
			List<TurnoDelCasoSnapshot> turnosDelCaso,
			ZoneId zona) {

		List<Long> ids = cerradas.stream().map(Sesion::getId).toList();
		Map<Long, List<DatosDelCaso.Medicion>> medicionesPorSesion = new LinkedHashMap<>();
		Set<Long> definicionIds = new LinkedHashSet<>();
		Map<Long, List<DatosDelCaso.Tratamiento>> tratamientosPorSesion = new LinkedHashMap<>();
		if (!ids.isEmpty()) {
			for (SesionMedicion fila : mediciones.listarDeSesiones(organizationId, ids)) {
				if (fila.getValorNumerico() == null || !fila.getDefinicionTipo().esNumerico()) {
					continue;
				}
				definicionIds.add(fila.getDefinicionId());
				medicionesPorSesion.computeIfAbsent(fila.getSesionId(), k -> new ArrayList<>())
						.add(new DatosDelCaso.Medicion(fila.getDefinicionId(), fila.getDefinicionCodigo(),
								fila.getDefinicionNombre(), fila.getDefinicionUnidad(),
								fila.getLateralidad().name(), fila.getValorNumerico()));
			}
			for (TratamientoRealizado fila : tratamientos.listarVigentesDeSesiones(organizationId, ids)) {
				tratamientosPorSesion.computeIfAbsent(fila.getSesionId(), k -> new ArrayList<>())
						.add(new DatosDelCaso.Tratamiento(fila.getPracticaId(), fila.getPracticaNombre()));
			}
		}

		Map<Long, DatosDelCaso.Definicion> porDefinicion = definiciones
				.findTodas(organizationId, definicionIds).stream()
				.collect(Collectors.toMap(MedicionDefinicionSnapshot::id, d -> new DatosDelCaso.Definicion(
						d.minimo(), d.maximo(), d.mejoraCuando() == null ? null : d.mejoraCuando().name())));

		List<DatosDelCaso.Sesion> sesionesDelCaso = cerradas.stream()
				.map(s -> new DatosDelCaso.Sesion(
						s.getId(),
						s.getCerradaEn().atZone(zona).toLocalDate(),
						s.getCerradaEn(),
						s.getAsistencia() != Asistencia.AUSENTE,
						s.getTurnoId(),
						s.getDolorEva(),
						s.getDolorZona(),
						s.getEvolucion(),
						s.getTolerancia(),
						s.getProximaConducta(),
						medicionesPorSesion.getOrDefault(s.getId(), List.of()),
						tratamientosPorSesion.getOrDefault(s.getId(), List.of())))
				.toList();

		List<DatosDelCaso.Turno> turnosPlanos = turnosDelCaso.stream()
				.map(t -> new DatosDelCaso.Turno(t.turnoId(), t.inicio().atZone(zona).toLocalDate(),
						t.estado(), t.origenCancelacion()))
				.toList();

		DatosDelCaso.Plan planPlano = plan == null ? null : new DatosDelCaso.Plan(
				plan.estado(),
				plan.activadoEn() == null ? null : plan.activadoEn().atZone(zona).toLocalDate(),
				plan.versionVigenteDesde(),
				plan.frecuenciaSemanal(),
				plan.duracionSemanas(),
				plan.completo());

		return new DatosDelCaso(caso.activo(), sesionesDelCaso, turnosPlanos, planPlano, porDefinicion);
	}

	private List<String> advertencias(
			long organizationId, CasoDetalleSnapshot caso, List<Sesion> cerradas, boolean recortada) {

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
