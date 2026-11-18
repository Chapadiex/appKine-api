package com.akine.encounter.application;

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
import com.akine.encounter.domain.evolucion.ReglasDeEvolucion;
import com.akine.encounter.domain.exception.CasoNoAsignableException;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.port.SesionMedicionRepositoryPort;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.encounter.domain.port.TratamientoRepositoryPorts.TratamientoRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.ZonaDeSede;
import com.akine.resource.spi.MedicionDefinicionSnapshot;
import com.akine.resource.spi.MedicionDirectory;
import com.akine.scheduling.spi.TurnoDelCasoSnapshot;
import com.akine.scheduling.spi.TurnosDelCasoDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
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
 * El calculo de la evolucion de un Caso Clinico, sin su puerta: no autoriza ni audita.
 *
 * <p>Lo usan {@link EvolucionDelCasoService} (que exige el acceso clinico despues de resolver el caso, y
 * audita) y la fase 2. No abre transaccion propia: corre dentro de la del llamador.
 */
@Component
class AnalizadorDelCaso {

	private final ConfiguracionDeEvolucion configuracion;
	private final ConsultorioDirectory consultorios;
	private final CasoDirectory casos;
	private final HistoriaClinicaDirectory historias;
	private final PlanVigenteDirectory planes;
	private final TurnosDelCasoDirectory turnos;
	private final SesionRepositoryPort sesiones;
	private final SesionMedicionRepositoryPort mediciones;
	private final TratamientoRepositoryPort tratamientos;
	private final MedicionDirectory definiciones;
	private final Clock clock;

	@Autowired
	@SuppressWarnings("checkstyle:ParameterNumber")
	AnalizadorDelCaso(
			ConfiguracionDeEvolucion configuracion,
			ConsultorioDirectory consultorios,
			CasoDirectory casos,
			HistoriaClinicaDirectory historias,
			PlanVigenteDirectory planes,
			TurnosDelCasoDirectory turnos,
			SesionRepositoryPort sesiones,
			SesionMedicionRepositoryPort mediciones,
			TratamientoRepositoryPort tratamientos,
			MedicionDirectory definiciones) {
		this(configuracion, consultorios, casos, historias, planes, turnos, sesiones, mediciones,
				tratamientos, definiciones, Clock.systemUTC());
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	AnalizadorDelCaso(
			ConfiguracionDeEvolucion configuracion,
			ConsultorioDirectory consultorios,
			CasoDirectory casos,
			HistoriaClinicaDirectory historias,
			PlanVigenteDirectory planes,
			TurnosDelCasoDirectory turnos,
			SesionRepositoryPort sesiones,
			SesionMedicionRepositoryPort mediciones,
			TratamientoRepositoryPort tratamientos,
			MedicionDirectory definiciones,
			Clock clock) {
		this.configuracion = configuracion;
		this.consultorios = consultorios;
		this.casos = casos;
		this.historias = historias;
		this.planes = planes;
		this.turnos = turnos;
		this.sesiones = sesiones;
		this.mediciones = mediciones;
		this.tratamientos = tratamientos;
		this.definiciones = definiciones;
		this.clock = clock;
	}

	/** Sin autorizar ni auditar. Lanza las mismas excepciones de 404 que siempre. */
	CasoAnalizado analizar(long organizationId, long consultorioId, long casoId) {
		ConsultorioSnapshot sede = consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
		CasoDetalleSnapshot caso = casos.detalle(organizationId, casoId)
				.orElseThrow(() -> new CasoNoAsignableException(casoId, CasoNoAsignableException.Motivo.NO_ACCESIBLE));
		HistoriaClinicaSnapshot historia = historias.findPorId(organizationId, caso.historiaClinicaId())
				.orElseThrow(() -> new CasoNoAsignableException(casoId, CasoNoAsignableException.Motivo.NO_ACCESIBLE));

		ZoneId zona = ZonaDeSede.de(sede);
		LocalDate hoy = LocalDate.now(clock.withZone(zona));

		int tope = EvolucionDelCasoService.TOPE_DE_SESIONES;
		List<Sesion> leidas = sesiones.findCerradasDelCaso(organizationId, casoId, tope + 1);
		boolean recortada = leidas.size() > tope;
		List<Sesion> cerradas = new ArrayList<>(leidas.subList(0, Math.min(leidas.size(), tope)));
		Collections.reverse(cerradas);

		Optional<PlanVigenteSnapshot> plan = planes.delCaso(organizationId, casoId);
		DatosDelCaso datos = datos(organizationId, caso, cerradas, plan.orElse(null),
				turnos.delCaso(organizationId, casoId), zona);
		AnalisisDeEvolucion analisis = ReglasDeEvolucion.analizar(datos, configuracion.umbrales(), hoy);
		return new CasoAnalizado(sede, caso, historia, cerradas, recortada, plan, datos, analisis);
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
}
