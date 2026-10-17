package com.akine.activity.infrastructure;

import com.akine.clinical.spi.HistoriaClinicaDirectory;
import com.akine.encounter.spi.CierresClinicosDirectory;
import com.akine.encounter.spi.CierresClinicosDirectory.CierreClinico;
import com.akine.offering.spi.ClasificacionDeOferta;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.FilaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;
import com.akine.reporting.spi.ReporteContributor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Continuidad del circuito de salud (RF-M23-008, AKINE-09.01b): de las personas que recibieron el
 * alta clinica en el periodo, cuantas pasan despues a un servicio de cada naturaleza.
 * CA-M23-008-06: <i>"se puede medir cuantas personas pasan de alta clinica a un servicio
 * preventivo"</i>.
 *
 * <h2>Las tres definiciones, y por que esas</h2>
 *
 * <ul>
 *   <li><b>Alta clinica</b>: una sesion cerrada en la sede con proxima conducta {@code ALTA}
 *       (RF-M14-007). Es el unico dato tipado del alta: el motivo de cierre del Caso es texto
 *       libre y no distingue un alta de un abandono (ver {@link CierresClinicosDirectory}).</li>
 *   <li><b>Pasar a un servicio</b>: recibirlo, despues del alta. Una sesion cerrada en la sede o
 *       una asistencia ({@code ASISTIO}) a una clase de la sede, que empezo despues del instante
 *       de la <b>primera</b> alta del periodo de esa persona. Una inscripcion o un pase comprado
 *       sin asistencia no cuentan: muestran intencion, no transito.</li>
 *   <li><b>Naturaleza del servicio</b>: el dato tipado del catalogo ({@code servicio.naturaleza},
 *       RN-M06-005 la reserva para clasificar), nunca el nombre visible (RNF-M23-009).</li>
 * </ul>
 *
 * <h2>Personas, no atenciones, y nunca quien</h2>
 *
 * <p>Una persona que despues del alta fue a diez clases de Pilates cuenta una vez en
 * {@code PREVENTIVO}. Si ademas fue a una sesion clinica, cuenta tambien en {@code CLINICO}: las
 * naturalezas <b>no se suman</b> entre si. Ninguna fila lleva una persona ni un caso: el detalle es
 * por naturaleza y servicio (RN-M23-001, "sin exponer contenido clinico innecesario"). Las historias
 * clinicas no se mezclan: solo se usa a quien pertenece cada una para cruzarla con su asistencia.
 *
 * <h2>Es una lectura clinica</h2>
 *
 * <p>Que alguien recibio el alta es un dato de su atencion. La seccion exige {@code hc:read} y
 * declara {@link #esClinica()}, asi que {@code reporting} audita el acceso. No se recorta a la
 * actividad propia: un {@code PROFESIONAL} veria el transito de pacientes que dio de alta hacia
 * clases de otros, y la matriz no se lo concede; la seccion se omite y se declara.
 *
 * <h2>El corte</h2>
 *
 * <p>El periodo recorta las <b>altas</b>. Lo posterior se mira hasta el momento de la consulta, sin
 * tope: una alta del ultimo dia del mes que continua en el mes siguiente sigue contando como
 * transito. Por eso el mismo periodo puede dar un numero mayor si se consulta mas tarde, y el
 * criterio lo dice. Sede: la del reporte, tanto para el alta como para lo posterior.
 */
@Component
public class ContinuidadEnElReporte implements ReporteContributor {

	static final String SECCION = "continuidad";
	static final String PERMISO_HC_READ = "hc:read";

	private static final String CRITERIO_ALTA =
			"cerrada_en de la sesion con proxima conducta ALTA, instante proyectado a la zona de la sede";
	private static final String CRITERIO_DESTINO = "personas con alta en el periodo que, despues de "
			+ "su primera alta del periodo y hasta el momento de la consulta, cerraron una sesion o "
			+ "asistieron a una clase de un servicio de esta naturaleza en la sede; una persona "
			+ "cuenta una vez por naturaleza y las naturalezas no se suman";

	private final CierresClinicosDirectory cierres;
	private final HistoriaClinicaDirectory historias;
	private final OfertaDirectory ofertas;
	private final ActividadEnElReporteRepository consultas;

	public ContinuidadEnElReporte(
			CierresClinicosDirectory cierres,
			HistoriaClinicaDirectory historias,
			OfertaDirectory ofertas,
			ActividadEnElReporteRepository consultas) {

		this.cierres = cierres;
		this.historias = historias;
		this.ofertas = ofertas;
		this.consultas = consultas;
	}

	@Override
	public Set<ReporteCode> reportes() {
		return Set.of(ReporteCode.CONTINUIDAD);
	}

	@Override
	public String seccion() {
		return SECCION;
	}

	@Override
	public String titulo() {
		return "Continuidad del circuito";
	}

	@Override
	public String permisoRequerido() {
		return PERMISO_HC_READ;
	}

	@Override
	public boolean esClinica() {
		return true;
	}

	@Override
	public AporteDeReporte aportar(ConsultaDeReporte consulta) {
		long org = consulta.organizationId();
		long sede = consulta.consultorioId();

		List<CierreClinico> altas = cierres.altasDelPeriodo(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante());
		Set<Long> historiasConAlta = new HashSet<>();
		altas.forEach(a -> historiasConAlta.add(a.historiaClinicaId()));
		Map<Long, Long> personaDeHistoria = historias.personasDe(org, historiasConAlta);

		// La primera alta del periodo de cada persona es la referencia de "despues".
		Map<Long, Instant> primeraAlta = new HashMap<>();
		for (CierreClinico alta : altas) {
			Long persona = personaDeHistoria.get(alta.historiaClinicaId());
			if (persona != null) {
				primeraAlta.merge(persona, alta.cerradaEn(), (a, b) -> a.isBefore(b) ? a : b);
			}
		}

		Map<Long, Set<Long>> personasPorOferta = new HashMap<>();
		if (!primeraAlta.isEmpty()) {
			Instant desde = Collections.min(primeraAlta.values());
			for (CierreClinico posterior : cierres.cerradasDespuesDe(org, sede, historiasConAlta, desde)) {
				Long persona = personaDeHistoria.get(posterior.historiaClinicaId());
				registrar(personasPorOferta, primeraAlta, persona, posterior.ofertaId(), posterior.cerradaEn());
			}
			for (Object[] fila : consultas.asistenciasDespuesDe(org, sede, primeraAlta.keySet(), desde)) {
				registrar(personasPorOferta, primeraAlta, ((Number) fila[0]).longValue(),
						((Number) fila[1]).longValue(),
						Instant.EPOCH.plus(((Number) fila[2]).longValue(), ChronoUnit.MICROS));
			}
		}

		Map<Long, ClasificacionDeOferta> clasificacion =
				ofertas.clasificar(org, sede, personasPorOferta.keySet());
		Map<String, Set<Long>> porNaturaleza = new HashMap<>();
		Map<String, Map<String, Set<Long>>> porServicio = new HashMap<>();
		personasPorOferta.forEach((oferta, personas) -> {
			ClasificacionDeOferta clase = clasificacion.get(oferta);
			if (clase == null) {
				return;
			}
			porNaturaleza.computeIfAbsent(clase.naturaleza(), n -> new HashSet<>()).addAll(personas);
			porServicio.computeIfAbsent(clase.naturaleza(), n -> new TreeMap<>())
					.computeIfAbsent(clase.servicioNombre(), s -> new HashSet<>()).addAll(personas);
		});

		long conAlta = primeraAlta.size();
		List<IndicadorDeReporte> indicadores = new ArrayList<>();
		indicadores.add(IndicadorDeReporte.contando("continuidad-altas", "Altas clinicas",
				altas.size(), "M14 sesion", CRITERIO_ALTA + "; una por sesion"));
		indicadores.add(IndicadorDeReporte.contando("continuidad-personas-con-alta",
				"Personas con alta", conAlta, "M14 sesion + M09 historia_clinica",
				CRITERIO_ALTA + "; personas distintas"));
		for (String naturaleza : ClasificacionDeOferta.NATURALEZAS) {
			indicadores.add(IndicadorDeReporte.contando(
					"continuidad-a-" + naturaleza.toLowerCase(),
					"Pasan a un servicio " + naturaleza,
					cuantas(porNaturaleza, naturaleza),
					"M14 sesion + M28 inscripcion_clase + M27 servicio.naturaleza",
					CRITERIO_DESTINO));
		}
		indicadores.add(IndicadorDeReporte.porcentaje("continuidad-tasa-preventivo",
				"Continuidad a un servicio preventivo",
				Porcentajes.de(cuantas(porNaturaleza, "PREVENTIVO"), conAlta),
				"M14 sesion + M28 inscripcion_clase + M27 servicio.naturaleza",
				"personas que pasan a PREVENTIVO sobre personas con alta (CA-M23-008-06)"));

		List<FilaDeReporte> filas = new ArrayList<>();
		for (String naturaleza : ClasificacionDeOferta.NATURALEZAS) {
			porServicio.getOrDefault(naturaleza, Map.of()).forEach((servicio, personas) -> {
				if (filas.size() < consulta.limiteFilas()) {
					filas.add(FilaDeReporte.de(naturaleza, servicio, String.valueOf(personas.size())));
				}
			});
		}

		return new AporteDeReporte(SECCION, titulo(), indicadores,
				List.of("Naturaleza", "Servicio", "Personas"), filas, List.of());
	}

	/** Suma a la persona en la oferta solo si lo prestado es posterior a su primera alta. */
	private static void registrar(
			Map<Long, Set<Long>> personasPorOferta, Map<Long, Instant> primeraAlta,
			Long persona, long ofertaId, Instant cuando) {

		Instant alta = persona == null ? null : primeraAlta.get(persona);
		if (alta != null && cuando.isAfter(alta)) {
			personasPorOferta.computeIfAbsent(ofertaId, o -> new HashSet<>()).add(persona);
		}
	}

	private static long cuantas(Map<String, Set<Long>> porNaturaleza, String naturaleza) {
		return porNaturaleza.getOrDefault(naturaleza, Set.of()).size();
	}
}
