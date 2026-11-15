package com.akine.encounter.domain.evolucion;

import com.akine.encounter.domain.Evolucion;
import com.akine.encounter.domain.ProximaConducta;
import com.akine.encounter.domain.Tolerancia;
import com.akine.encounter.domain.evolucion.AnalisisDeEvolucion.Adherencia;
import com.akine.encounter.domain.evolucion.AnalisisDeEvolucion.Conteo;
import com.akine.encounter.domain.evolucion.AnalisisDeEvolucion.Frecuencia;
import com.akine.encounter.domain.evolucion.AnalisisDeEvolucion.Indicadores;
import com.akine.encounter.domain.evolucion.AnalisisDeEvolucion.Punto;
import com.akine.encounter.domain.evolucion.AnalisisDeEvolucion.Serie;
import com.akine.encounter.domain.evolucion.AnalisisDeEvolucion.SerieDeMedicion;
import com.akine.encounter.domain.evolucion.AnalisisDeEvolucion.TratamientoResumido;
import com.akine.encounter.domain.evolucion.DatosDelCaso.Definicion;
import com.akine.encounter.domain.evolucion.DatosDelCaso.Medicion;
import com.akine.encounter.domain.evolucion.DatosDelCaso.Plan;
import com.akine.encounter.domain.evolucion.DatosDelCaso.Sesion;
import com.akine.encounter.domain.evolucion.DatosDelCaso.Tratamiento;
import com.akine.encounter.domain.evolucion.DatosDelCaso.Turno;
import com.akine.encounter.domain.evolucion.Hallazgo.Severidad;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * La evolucion de un caso calculada con reglas (RF-M31-001, RF-M31-002).
 *
 * <h2>Funcion pura</h2>
 *
 * <p>Sin Spring, sin reloj, sin repositorios: recibe el caso ya leido, los umbrales y "hoy" en la
 * zona de la sede, y devuelve indicadores y hallazgos. Nada se guarda (RN-M31-002) y nada se
 * modifica (RN-M31-001).
 *
 * <h2>Lo que mira cada regla</h2>
 *
 * <ul>
 *   <li>Las series usan solo sesiones {@code PRESENTE}, en orden de cierre (RN-M31-006).</li>
 *   <li>Las alertas de tendencia miran el <b>final</b> de la serie: lo que pasa ahora, no lo que
 *       paso alguna vez.</li>
 *   <li>La adherencia arma una lista de citas donde cada hecho cuenta una vez (RN-M31-008).</li>
 *   <li>Una medicion solo se califica con sentido de mejora y con rango declarado (RN-M31-009).</li>
 * </ul>
 */
public final class ReglasDeEvolucion {

	private static final int ULTIMOS_EN_CONTEO = 5;
	private static final Set<ProximaConducta> CONDUCTAS_QUE_CAMBIAN_EL_CURSO =
			EnumSet.of(ProximaConducta.ALTA, ProximaConducta.DERIVA, ProximaConducta.REEVALUA);

	private ReglasDeEvolucion() {
	}

	public static AnalisisDeEvolucion analizar(
			DatosDelCaso datos, UmbralesDeEvolucion umbrales, LocalDate hoy) {

		List<Sesion> sesiones = datos.sesiones();
		List<Sesion> presentes = sesiones.stream().filter(Sesion::presente).toList();

		Serie dolor = serieDeDolor(presentes);
		List<SerieDeMedicion> mediciones = seriesDeMedicion(presentes, datos.definiciones());
		List<Cita> citas = citas(datos, hoy);
		Indicadores indicadores = new Indicadores(
				dolor,
				conteo(presentes, Sesion::evolucion),
				conteo(presentes, Sesion::tolerancia),
				mediciones,
				adherencia(citas),
				frecuencia(presentes, datos.plan(), umbrales, hoy),
				tratamientos(presentes));

		List<Hallazgo> hallazgos = presentes.size() < umbrales.minimoSesiones()
				? List.of(new Hallazgo(CodigoDeHallazgo.DATOS_INSUFICIENTES, Severidad.INFO,
						ids(presentes), List.of(), Map.of(
								"presentes", String.valueOf(presentes.size()),
								"minimo", String.valueOf(umbrales.minimoSesiones()))))
				: hallazgos(datos, presentes, dolor, mediciones, citas, indicadores, umbrales, hoy);

		return new AnalisisDeEvolucion(
				sesiones.size(),
				presentes.size(),
				sesiones.isEmpty() ? null : sesiones.getFirst().fecha(),
				sesiones.isEmpty() ? null : sesiones.getLast().fecha(),
				indicadores,
				hallazgos);
	}

	// =================================================================================
	// Hallazgos
	// =================================================================================

	@SuppressWarnings("java:S107")
	private static List<Hallazgo> hallazgos(
			DatosDelCaso datos,
			List<Sesion> presentes,
			Serie dolor,
			List<SerieDeMedicion> mediciones,
			List<Cita> citas,
			Indicadores indicadores,
			UmbralesDeEvolucion umbrales,
			LocalDate hoy) {

		List<Hallazgo> hallazgos = new ArrayList<>();
		dolor(dolor, umbrales, hallazgos);
		rachaFinal(presentes, Sesion::evolucion, Evolucion.PEOR, umbrales.evolucionPeorSeguidas(),
				CodigoDeHallazgo.EVOLUCION_DESFAVORABLE, hallazgos);
		if (hallazgos.stream().noneMatch(h -> h.codigo() == CodigoDeHallazgo.EVOLUCION_DESFAVORABLE)) {
			rachaFinal(presentes, Sesion::evolucion, Evolucion.IGUAL, umbrales.evolucionIgualSeguidas(),
					CodigoDeHallazgo.EVOLUCION_DESFAVORABLE, hallazgos);
		}
		rachaFinal(presentes, Sesion::tolerancia, Tolerancia.MALA, umbrales.toleranciaMalaSeguidas(),
				CodigoDeHallazgo.MALA_TOLERANCIA, hallazgos);
		for (SerieDeMedicion serie : mediciones) {
			medicion(serie, datos.definiciones().get(serie.definicionId()), umbrales, hallazgos);
		}
		adherencia(citas, umbrales, hallazgos);
		plan(datos, indicadores.frecuencia(), hoy, hallazgos);
		conducta(datos, presentes, hallazgos);
		sinMediciones(presentes, umbrales, hallazgos);

		hallazgos.sort(Comparator.comparing((Hallazgo h) -> h.severidad() == Severidad.ATENCION ? 0 : 1));
		return hallazgos;
	}

	private static void dolor(Serie dolor, UmbralesDeEvolucion umbrales, List<Hallazgo> hallazgos) {
		List<Punto> puntos = dolor.puntos();
		BigDecimal cambio = BigDecimal.valueOf(umbrales.dolorCambioRelevante());

		if (puntos.size() >= umbrales.dolorVentanaAumento()) {
			List<Punto> ultimos = ultimos(puntos, umbrales.dolorVentanaAumento());
			BigDecimal subida = ultimos.getLast().valor().subtract(ultimos.getFirst().valor());
			if (subida.compareTo(cambio) >= 0) {
				hallazgos.add(new Hallazgo(CodigoDeHallazgo.DOLOR_EN_AUMENTO, Severidad.ATENCION,
						puntos(ultimos), List.of(), desdeHasta(ultimos)));
				return;
			}
		}
		if (puntos.size() >= umbrales.dolorVentana()) {
			List<Punto> ultimos = ultimos(puntos, umbrales.dolorVentana());
			BigDecimal bajada = ultimos.getFirst().valor().subtract(ultimos.getLast().valor());
			boolean sigueAlto = ultimos.getLast().valor()
					.compareTo(BigDecimal.valueOf(umbrales.dolorPiso())) >= 0;
			if (bajada.compareTo(cambio) < 0 && sigueAlto) {
				hallazgos.add(new Hallazgo(CodigoDeHallazgo.DOLOR_SIN_MEJORA, Severidad.ATENCION,
						puntos(ultimos), List.of(), desdeHasta(ultimos)));
			}
		}
	}

	/** Dispara si las ultimas {@code minimo} sesiones con dato tienen todas {@code valor}. */
	private static <T> void rachaFinal(
			List<Sesion> presentes,
			Function<Sesion, T> campo,
			T valor,
			int minimo,
			CodigoDeHallazgo codigo,
			List<Hallazgo> hallazgos) {

		List<Long> racha = new ArrayList<>();
		for (int i = presentes.size() - 1; i >= 0; i--) {
			if (!valor.equals(campo.apply(presentes.get(i)))) {
				break;
			}
			racha.addFirst(presentes.get(i).sesionId());
		}
		if (racha.size() >= minimo) {
			hallazgos.add(new Hallazgo(codigo, Severidad.ATENCION, racha, List.of(), Map.of(
					"valor", valor.toString(),
					"seguidas", String.valueOf(racha.size()))));
		}
	}

	private static void medicion(
			SerieDeMedicion serie,
			Definicion definicion,
			UmbralesDeEvolucion umbrales,
			List<Hallazgo> hallazgos) {

		List<Punto> puntos = serie.serie().puntos();
		if (serie.unidadesDifieren() || definicion == null || definicion.rango() == null
				|| puntos.size() < umbrales.medicionMinimoPuntos()) {
			return;
		}
		List<Punto> ultimos = ultimos(puntos, umbrales.medicionMinimoPuntos());
		BigDecimal umbral = definicion.rango()
				.multiply(BigDecimal.valueOf(umbrales.medicionVariacionMinimaPorcentaje()))
				.divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
		Map<String, String> valores = new HashMap<>(desdeHasta(ultimos));
		valores.put("medicion", serie.nombre());
		valores.put("lateralidad", serie.lateralidad());
		valores.put("unidad", Objects.toString(serie.unidad(), ""));

		String mejora = definicion.mejoraCuando();
		if (mejora != null) {
			BigDecimal delta = ultimos.getLast().valor().subtract(ultimos.getFirst().valor());
			boolean vaEnContra = "SUBE".equals(mejora) ? delta.signum() < 0 : delta.signum() > 0;
			if (vaEnContra && delta.abs().compareTo(umbral) > 0) {
				hallazgos.add(new Hallazgo(CodigoDeHallazgo.MEDICION_EMPEORA, Severidad.ATENCION,
						puntos(ultimos), List.of(), valores));
				return;
			}
		}
		BigDecimal maximo = ultimos.stream().map(Punto::valor).max(BigDecimal::compareTo).orElseThrow();
		BigDecimal minimo = ultimos.stream().map(Punto::valor).min(BigDecimal::compareTo).orElseThrow();
		if (maximo.subtract(minimo).compareTo(umbral) < 0) {
			hallazgos.add(new Hallazgo(CodigoDeHallazgo.MEDICION_SIN_CAMBIO, Severidad.INFO,
					puntos(ultimos), List.of(), valores));
		}
	}

	private static void adherencia(List<Cita> citas, UmbralesDeEvolucion umbrales, List<Hallazgo> hallazgos) {
		if (citas.size() < umbrales.minimoSesiones()) {
			return;
		}
		List<Cita> ultimas = ultimos(citas, umbrales.adherenciaVentanaCitas());
		List<Cita> faltas = ultimas.stream().filter(Cita::inasistencia).toList();
		int porcentaje = faltas.size() * 100 / ultimas.size();
		if (porcentaje >= umbrales.adherenciaInasistenciasPorcentaje()) {
			hallazgos.add(new Hallazgo(CodigoDeHallazgo.BAJA_ADHERENCIA, Severidad.ATENCION,
					faltas.stream().map(Cita::sesionId).filter(Objects::nonNull).toList(),
					faltas.stream().map(Cita::turnoId).filter(Objects::nonNull).toList(),
					Map.of("inasistencias", String.valueOf(faltas.size()),
							"citas", String.valueOf(ultimas.size()),
							"porcentaje", String.valueOf(porcentaje))));
		}
	}

	private static void plan(DatosDelCaso datos, Frecuencia frecuencia, LocalDate hoy, List<Hallazgo> hallazgos) {
		Plan plan = datos.plan();
		if (plan == null) {
			if (datos.casoActivo()) {
				hallazgos.add(new Hallazgo(CodigoDeHallazgo.SIN_PLAN_ACTIVO, Severidad.ATENCION,
						List.of(), List.of(), Map.of()));
			}
			return;
		}
		if (!plan.activo()) {
			return;
		}
		Integer planificada = frecuencia.planificadaPorSemana();
		if (planificada != null && planificada > 0 && frecuencia.realPorSemana() != null
				&& frecuencia.realPorSemana().multiply(BigDecimal.TWO)
						.compareTo(BigDecimal.valueOf(planificada)) < 0) {
			hallazgos.add(new Hallazgo(CodigoDeHallazgo.FRECUENCIA_BAJO_PLAN, Severidad.ATENCION,
					List.of(), List.of(), Map.of(
							"real", texto(frecuencia.realPorSemana()),
							"planificada", String.valueOf(planificada),
							"semanas", String.valueOf(frecuencia.semanas()))));
		}
		if (plan.completo()) {
			hallazgos.add(new Hallazgo(CodigoDeHallazgo.PLAN_COMPLETO_ABIERTO, Severidad.INFO,
					List.of(), List.of(), Map.of()));
		}
		if (plan.duracionSemanas() != null && plan.activadoEl() != null) {
			LocalDate fin = plan.activadoEl().plusWeeks(plan.duracionSemanas());
			if (hoy.isAfter(fin)) {
				hallazgos.add(new Hallazgo(CodigoDeHallazgo.PLAN_EXCEDIDO_EN_TIEMPO, Severidad.INFO,
						List.of(), List.of(), Map.of(
								"semanas", String.valueOf(plan.duracionSemanas()),
								"activadoEl", plan.activadoEl().toString(),
								"semanasTranscurridas",
								String.valueOf(ChronoUnit.WEEKS.between(plan.activadoEl(), hoy)))));
			}
		}
	}

	/**
	 * Una sesion cerro con alta, derivacion o reevaluacion y el caso siguio igual. Para
	 * {@code REEVALUA} hacen falta dos sesiones despues: la siguiente puede ser la reevaluacion.
	 */
	private static void conducta(DatosDelCaso datos, List<Sesion> presentes, List<Hallazgo> hallazgos) {
		if (!datos.casoActivo()) {
			return;
		}
		for (int i = presentes.size() - 1; i >= 0; i--) {
			Sesion sesion = presentes.get(i);
			if (!CONDUCTAS_QUE_CAMBIAN_EL_CURSO.contains(sesion.proximaConducta())) {
				continue;
			}
			int despues = presentes.size() - 1 - i;
			int necesarias = sesion.proximaConducta() == ProximaConducta.REEVALUA ? 2 : 1;
			Plan plan = datos.plan();
			boolean planSinCambio = plan == null || plan.versionVigenteDesde() == null
					|| !plan.versionVigenteDesde().isAfter(sesion.cerradaEn());
			if (despues >= necesarias && planSinCambio) {
				hallazgos.add(new Hallazgo(CodigoDeHallazgo.CONDUCTA_SIN_SEGUIR, Severidad.ATENCION,
						ids(presentes.subList(i, presentes.size())), List.of(), Map.of(
								"conducta", sesion.proximaConducta().name(),
								"fecha", sesion.fecha().toString(),
								"despues", String.valueOf(despues))));
			}
			return;
		}
	}

	private static void sinMediciones(List<Sesion> presentes, UmbralesDeEvolucion umbrales, List<Hallazgo> hallazgos) {
		if (presentes.size() < umbrales.sinMedicionesVentana()) {
			return;
		}
		List<Sesion> ultimas = ultimos(presentes, umbrales.sinMedicionesVentana());
		boolean ninguna = ultimas.stream()
				.allMatch(s -> s.dolorEva() == null && s.mediciones().isEmpty());
		if (ninguna) {
			hallazgos.add(new Hallazgo(CodigoDeHallazgo.SIN_MEDICIONES, Severidad.INFO,
					ids(ultimas), List.of(), Map.of("sesiones", String.valueOf(ultimas.size()))));
		}
	}

	// =================================================================================
	// Indicadores
	// =================================================================================

	private static Serie serieDeDolor(List<Sesion> presentes) {
		return serie(presentes.stream()
				.filter(s -> s.dolorEva() != null)
				.map(s -> new Punto(s.sesionId(), s.fecha(), BigDecimal.valueOf(s.dolorEva()), s.dolorZona()))
				.toList(), true);
	}

	private record ClaveDeSerie(long definicionId, String lateralidad) {
	}

	private static List<SerieDeMedicion> seriesDeMedicion(List<Sesion> presentes, Map<Long, Definicion> definiciones) {
		Map<ClaveDeSerie, List<Medicion>> porSerie = new LinkedHashMap<>();
		Map<ClaveDeSerie, List<Punto>> puntos = new LinkedHashMap<>();
		for (Sesion sesion : presentes) {
			for (Medicion medicion : sesion.mediciones()) {
				if (medicion.valor() == null) {
					continue;
				}
				ClaveDeSerie clave = new ClaveDeSerie(medicion.definicionId(), medicion.lateralidad());
				porSerie.computeIfAbsent(clave, k -> new ArrayList<>()).add(medicion);
				puntos.computeIfAbsent(clave, k -> new ArrayList<>())
						.add(new Punto(sesion.sesionId(), sesion.fecha(), medicion.valor(), null));
			}
		}
		List<SerieDeMedicion> series = new ArrayList<>(porSerie.size());
		for (Map.Entry<ClaveDeSerie, List<Medicion>> entrada : porSerie.entrySet()) {
			List<Medicion> tomadas = entrada.getValue();
			Medicion ultima = tomadas.getLast();
			boolean unidadesDifieren = tomadas.stream().map(Medicion::unidad).distinct().count() > 1;
			Definicion definicion = definiciones.get(entrada.getKey().definicionId());
			series.add(new SerieDeMedicion(
					ultima.definicionId(),
					ultima.codigo(),
					ultima.nombre(),
					unidadesDifieren ? null : ultima.unidad(),
					ultima.lateralidad(),
					definicion == null ? null : definicion.mejoraCuando(),
					unidadesDifieren,
					serie(puntos.get(entrada.getKey()), !unidadesDifieren)));
		}
		return series;
	}

	private static Serie serie(List<Punto> puntos, boolean conDiferencia) {
		if (puntos.isEmpty()) {
			return new Serie(List.of(), null, null, null);
		}
		BigDecimal primero = puntos.getFirst().valor();
		BigDecimal ultimo = puntos.getLast().valor();
		return new Serie(puntos, primero, ultimo, conDiferencia ? ultimo.subtract(primero) : null);
	}

	private static <T extends Enum<T>> Conteo conteo(List<Sesion> presentes, Function<Sesion, T> campo) {
		Map<String, Integer> porValor = new LinkedHashMap<>();
		List<String> valores = new ArrayList<>();
		for (Sesion sesion : presentes) {
			T valor = campo.apply(sesion);
			if (valor != null) {
				porValor.merge(valor.name(), 1, Integer::sum);
				valores.add(valor.name());
			}
		}
		return new Conteo(porValor, ultimos(valores, ULTIMOS_EN_CONTEO));
	}

	private static Adherencia adherencia(List<Cita> citas) {
		int ausentesEnTurno = 0;
		int canceladosPorPaciente = 0;
		int ausentesEnSesion = 0;
		for (Cita cita : citas) {
			switch (cita.tipo()) {
				case AUSENTE_EN_TURNO -> ausentesEnTurno++;
				case CANCELADO_POR_PACIENTE -> canceladosPorPaciente++;
				case AUSENTE_EN_SESION -> ausentesEnSesion++;
				case ASISTIDA -> { }
			}
		}
		int inasistencias = ausentesEnTurno + canceladosPorPaciente + ausentesEnSesion;
		return new Adherencia(citas.size(), inasistencias, ausentesEnTurno, canceladosPorPaciente,
				ausentesEnSesion, citas.isEmpty() ? null : inasistencias * 100 / citas.size());
	}

	/**
	 * Sesiones presentes por semana en la ventana. La ventana no empieza antes que el plan ni que
	 * la primera sesion: un plan de hace una semana no se mide contra cuatro.
	 */
	private static Frecuencia frecuencia(
			List<Sesion> presentes, Plan plan, UmbralesDeEvolucion umbrales, LocalDate hoy) {

		Integer planificada = plan == null ? null : plan.frecuenciaSemanal();
		if (presentes.isEmpty()) {
			return new Frecuencia(0, null, planificada);
		}
		LocalDate inicio = presentes.getFirst().fecha();
		if (plan != null && plan.activadoEl() != null && plan.activadoEl().isAfter(inicio)) {
			inicio = plan.activadoEl();
		}
		long dias = Math.max(1, ChronoUnit.DAYS.between(inicio, hoy) + 1);
		int semanas = (int) Math.min(umbrales.frecuenciaVentanaSemanas(), Math.max(1, (dias + 6) / 7));
		LocalDate desde = hoy.minusWeeks(semanas);
		long enVentana = presentes.stream()
				.filter(s -> s.fecha().isAfter(desde) && !s.fecha().isAfter(hoy))
				.count();
		BigDecimal real = BigDecimal.valueOf(enVentana)
				.divide(BigDecimal.valueOf(semanas), 1, RoundingMode.HALF_UP);
		return new Frecuencia(semanas, real, planificada);
	}

	private static List<TratamientoResumido> tratamientos(List<Sesion> presentes) {
		Map<String, TratamientoResumido> porPractica = new LinkedHashMap<>();
		for (Sesion sesion : presentes) {
			Set<String> enEstaSesion = new java.util.HashSet<>();
			for (Tratamiento tratamiento : sesion.tratamientos()) {
				String clave = tratamiento.practicaId() == null
						? "n:" + tratamiento.practicaNombre() : "p:" + tratamiento.practicaId();
				if (tratamiento.practicaNombre() == null || !enEstaSesion.add(clave)) {
					continue;
				}
				porPractica.merge(clave,
						new TratamientoResumido(tratamiento.practicaId(), tratamiento.practicaNombre(),
								1, sesion.fecha()),
						(viejo, nuevo) -> new TratamientoResumido(viejo.practicaId(), viejo.practica(),
								viejo.sesiones() + 1, nuevo.ultima()));
			}
		}
		return List.copyOf(porPractica.values());
	}

	// =================================================================================
	// Citas
	// =================================================================================

	private enum TipoDeCita {
		ASISTIDA,
		AUSENTE_EN_TURNO,
		CANCELADO_POR_PACIENTE,
		AUSENTE_EN_SESION
	}

	private record Cita(LocalDate fecha, TipoDeCita tipo, Long sesionId, Long turnoId) {

		boolean inasistencia() {
			return tipo != TipoDeCita.ASISTIDA;
		}
	}

	/**
	 * Cada hecho cuenta una vez (RN-M31-008):
	 *
	 * <ul>
	 *   <li>un turno cancelado por el profesional o la institucion no es cita; uno cancelado por el
	 *       paciente es una inasistencia, si su fecha ya paso;</li>
	 *   <li>un turno {@code AUSENTE} es una inasistencia, aunque su sesion tambien diga ausente;</li>
	 *   <li>un turno vigente con sesion es una cita que decide la sesion; sin sesion, no se cuenta:
	 *       o todavia no ocurrio o nadie registro que paso;</li>
	 *   <li>una sesion sin turno del caso es una cita propia.</li>
	 * </ul>
	 */
	private static List<Cita> citas(DatosDelCaso datos, LocalDate hoy) {
		Map<Long, Sesion> sesionPorTurno = new HashMap<>();
		Set<Long> turnosDelCaso = new java.util.HashSet<>();
		for (Turno turno : datos.turnos()) {
			turnosDelCaso.add(turno.turnoId());
		}
		for (Sesion sesion : datos.sesiones()) {
			if (sesion.turnoId() != null && turnosDelCaso.contains(sesion.turnoId())) {
				sesionPorTurno.put(sesion.turnoId(), sesion);
			}
		}

		List<Cita> citas = new ArrayList<>();
		for (Turno turno : datos.turnos()) {
			Sesion sesion = sesionPorTurno.get(turno.turnoId());
			Long sesionId = sesion == null ? null : sesion.sesionId();
			switch (turno.estado()) {
				case "CANCELADO" -> {
					if ("PACIENTE".equals(turno.origenCancelacion()) && !turno.fecha().isAfter(hoy)) {
						citas.add(new Cita(turno.fecha(), TipoDeCita.CANCELADO_POR_PACIENTE, sesionId, turno.turnoId()));
					}
				}
				case "AUSENTE" -> citas.add(
						new Cita(turno.fecha(), TipoDeCita.AUSENTE_EN_TURNO, sesionId, turno.turnoId()));
				default -> {
					if (sesion != null) {
						citas.add(new Cita(sesion.fecha(),
								sesion.presente() ? TipoDeCita.ASISTIDA : TipoDeCita.AUSENTE_EN_SESION,
								sesionId, turno.turnoId()));
					}
				}
			}
		}
		for (Sesion sesion : datos.sesiones()) {
			if (sesion.turnoId() == null || !turnosDelCaso.contains(sesion.turnoId())) {
				citas.add(new Cita(sesion.fecha(),
						sesion.presente() ? TipoDeCita.ASISTIDA : TipoDeCita.AUSENTE_EN_SESION,
						sesion.sesionId(), null));
			}
		}
		citas.sort(Comparator.comparing(Cita::fecha));
		return citas;
	}

	// =================================================================================
	// Auxiliares
	// =================================================================================

	private static <T> List<T> ultimos(List<T> lista, int cantidad) {
		return lista.subList(Math.max(0, lista.size() - cantidad), lista.size());
	}

	private static List<Long> ids(List<Sesion> sesiones) {
		return sesiones.stream().map(Sesion::sesionId).toList();
	}

	private static List<Long> puntos(List<Punto> puntos) {
		return puntos.stream().map(Punto::sesionId).toList();
	}

	private static Map<String, String> desdeHasta(List<Punto> puntos) {
		return Map.of(
				"desde", texto(puntos.getFirst().valor()),
				"hasta", texto(puntos.getLast().valor()),
				"sesiones", String.valueOf(puntos.size()));
	}

	private static String texto(BigDecimal valor) {
		return valor.stripTrailingZeros().toPlainString();
	}
}
