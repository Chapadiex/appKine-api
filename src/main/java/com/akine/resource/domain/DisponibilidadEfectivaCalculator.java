package com.akine.resource.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resuelve la disponibilidad efectiva dia por dia.
 *
 * <h2>El orden de las etapas lo fija RN-M05-002</h2>
 *
 * <p>"Las excepciones prevalecen sobre el horario base". Por eso CIERRE se aplica ULTIMO y
 * puede recortar incluso lo que abrio una APERTURA. Invertir las dos ultimas etapas haria que
 * una apertura tape una ausencia, que es exactamente al reves de lo que la regla dice: un
 * profesional con una licencia cargada apareceria atendiendo porque alguien habia ampliado ese
 * sabado. Las cuatro etapas —BASE, APERTURA, FERIADO, CIERRE— no son intercambiables, y el
 * test {@code un_cierre_recorta_lo_que_abrio_una_apertura} existe unicamente para clavar ese
 * orden.
 *
 * <p>El FERIADO va tercero y no ultimo por una razon distinta: un feriado no es una decision
 * operativa sino un hecho del calendario (diseno §1), y la forma en que un centro declara que
 * ese dia atiende es cargando una APERTURA explicita. Por eso el feriado solo cierra cuando no
 * hay ninguna apertura para esa fecha, y por eso se evalua despues de juntar las aperturas.
 *
 * <h2>Como se garantiza el determinismo</h2>
 *
 * <p>El resultado no depende del orden de insercion de las filas ni del orden en que la base
 * las devuelva: bloques y excepciones se ordenan con un comparador total y explicito antes de
 * usarse, las franjas resultantes se ordenan por hora, y el mapa de salida es un
 * {@link LinkedHashMap} construido recorriendo la ventana por fecha ascendente. En ninguna
 * parte se itera un {@code HashSet} ni un {@code HashMap}: su orden es estable dentro de una
 * JVM pero no es una garantia del contrato, y la unica entrada desordenada
 * —{@code feriadosQueCierran}— solo se consulta con {@code contains}, nunca se recorre.
 *
 * <p>Esa es la mitad "determinista" del criterio de aceptacion; la otra mitad —"explica que
 * regla la afecta"— son {@code origen} y {@code recortadoPor} de cada franja, mas
 * {@link DiaCalculado#razonVacio()} para el dia que quedo en blanco.
 *
 * <h2>Que NO hace esta clase</h2>
 *
 * <p>No convierte a {@code Instant} y no conoce el huso de la sede: trabaja en hora local de
 * punta a punta. La conversion la hace {@code DisponibilidadEfectivaService}, y esta separada a
 * proposito para que el horario de verano no se mezcle con la aritmetica de intervalos. Un
 * hueco o un solapamiento de DST metido adentro de la resta de intervalos produce un error que
 * despues es imposible de aislar en un test.
 *
 * <p>Tampoco consulta nada: no toca repositorios, no abre transacciones y no sabe de Spring ni
 * de la sesion de JPA. Recibe todo resuelto —los bloques y las excepciones ya leidos, y las
 * fechas de feriado ya cruzadas contra {@code consultorio_calendario.cierra_por_feriado}— y
 * devuelve un mapa plano. Que sea pura es lo que hace que sus tests prueben algo.
 *
 * <p>No valida la vigencia de la membership: si el profesional no estaba vinculado en la
 * ventana, la disponibilidad efectiva es vacia y eso lo decide el servicio antes de llamar
 * (diseno §4, paso 2). Tampoco fusiona franjas contiguas ni solapadas: dos bloques que se
 * pisan son un dato del negocio —y un conflicto que §5 resuelve al escribir—, no algo que el
 * calculo deba disimular al leer.
 */
public final class DisponibilidadEfectivaCalculator {

	/** El intervalo que cubre el dia entero, para las excepciones sin horario. */
	private static final IntervaloLocal DIA_ENTERO =
			new IntervaloLocal(LocalTime.MIN, IntervaloLocal.FIN_DE_DIA);

	/**
	 * Orden total de bloques. Por hora primero, para que las franjas salgan naturalmente
	 * ordenadas; el id desempata, y se tolera nulo porque un bloque recien construido todavia
	 * no lo tiene.
	 */
	private static final Comparator<BloqueDisponibilidad> ORDEN_BLOQUES =
			Comparator.comparing(BloqueDisponibilidad::getHoraDesde)
					.thenComparing(BloqueDisponibilidad::getHoraHasta)
					.thenComparing(BloqueDisponibilidad::getId,
							Comparator.nullsLast(Comparator.naturalOrder()));

	/**
	 * Orden total de excepciones. {@code horaDesde} nula —dia completo— va primero: es la mas
	 * amplia, y aplicarla antes deja al resto de las restas sin nada que hacer, que es
	 * justamente lo que la semantica dice.
	 */
	private static final Comparator<DisponibilidadExcepcion> ORDEN_EXCEPCIONES =
			Comparator.comparing(DisponibilidadExcepcion::getHoraDesde,
							Comparator.nullsFirst(Comparator.naturalOrder()))
					.thenComparing(DisponibilidadExcepcion::getHoraHasta,
							Comparator.nullsFirst(Comparator.naturalOrder()))
					.thenComparing(DisponibilidadExcepcion::getFechaDesde)
					.thenComparing(DisponibilidadExcepcion::getFechaHasta)
					.thenComparing(DisponibilidadExcepcion::getId,
							Comparator.nullsLast(Comparator.naturalOrder()));

	private static final Comparator<FranjaEfectiva> ORDEN_FRANJAS =
			Comparator.<FranjaEfectiva, LocalTime>comparing(franja -> franja.intervalo().desde())
					.thenComparing(franja -> franja.intervalo().hasta());

	/**
	 * Calcula la disponibilidad efectiva de un profesional en una sede, dia por dia.
	 *
	 * @param membershipId       profesional del que se calcula. Decide el alcance: se aplican
	 *                           las excepciones de la sede entera ({@code membershipId} nulo) y
	 *                           las suyas, y se ignoran las de sus companeros
	 * @param desde              primera fecha local incluida
	 * @param hasta              fecha local EXCLUSIVA
	 * @param bloques            bloques recurrentes candidatos; los no operables y los de otra
	 *                           membership se descartan aca
	 * @param excepciones        cierres y aperturas candidatos, de sede y de profesional
	 * @param feriadosQueCierran fechas en las que hay feriado <b>y</b> la sede cierra por
	 *                           feriado. Ya cruzadas por el servicio: aca no se decide politica
	 * @return una entrada por cada fecha de la ventana, en orden ascendente. Nunca omite un dia:
	 *         el dia sin atencion viaja igual, con su razon
	 */
	public Map<LocalDate, DiaCalculado> calcular(
			long membershipId,
			LocalDate desde,
			LocalDate hasta,
			List<BloqueDisponibilidad> bloques,
			List<DisponibilidadExcepcion> excepciones,
			Set<LocalDate> feriadosQueCierran) {

		if (desde == null || hasta == null) {
			throw new IllegalArgumentException("La ventana de calculo exige un inicio y un fin");
		}
		List<BloqueDisponibilidad> propios = bloquesDelProfesional(membershipId, bloques);
		List<DisponibilidadExcepcion> aplicables = excepcionesAplicables(membershipId, excepciones);
		Set<LocalDate> feriados = feriadosQueCierran == null ? Set.of() : feriadosQueCierran;

		Map<LocalDate, DiaCalculado> resultado = new LinkedHashMap<>();
		for (LocalDate fecha = desde; fecha.isBefore(hasta); fecha = fecha.plusDays(1)) {
			resultado.put(fecha, calcularDia(fecha, propios, aplicables, feriados));
		}
		return resultado;
	}

	private DiaCalculado calcularDia(
			LocalDate fecha,
			List<BloqueDisponibilidad> bloques,
			List<DisponibilidadExcepcion> aplicables,
			Set<LocalDate> feriadosQueCierran) {

		// Etapa 1: el horario base del dia de la semana, con su vigencia.
		List<FranjaEfectiva> abierto = new ArrayList<>();
		int diaSemana = fecha.getDayOfWeek().getValue();
		for (BloqueDisponibilidad bloque : bloques) {
			if (bloque.getDiaSemana() == diaSemana && bloque.vigenteEn(fecha)) {
				abierto.add(new FranjaEfectiva(
						bloque.intervalo(), OrigenFranja.BLOQUE, null, bloque.getId()));
			}
		}

		// Etapa 2: las aperturas suman atencion sobre el horario base.
		List<DisponibilidadExcepcion> aperturas = delTipo(aplicables, TipoExcepcion.APERTURA, fecha);
		for (DisponibilidadExcepcion apertura : aperturas) {
			abierto.add(new FranjaEfectiva(
					apertura.intervalo().orElse(DIA_ENTERO),
					OrigenFranja.APERTURA,
					null,
					apertura.getId()));
		}

		// Etapa 3: el feriado cierra el dia, salvo que una apertura explicita diga lo contrario.
		if (feriadosQueCierran.contains(fecha) && aperturas.isEmpty()) {
			return DiaCalculado.vacio(OrigenFranja.FERIADO, null);
		}

		// Etapa 4, y va ultima por RN-M05-002: los cierres recortan todo lo anterior.
		return aplicarCierres(abierto, delTipo(aplicables, TipoExcepcion.CIERRE, fecha));
	}

	private DiaCalculado aplicarCierres(
			List<FranjaEfectiva> abierto, List<DisponibilidadExcepcion> cierres) {

		List<FranjaEfectiva> vigentes = abierto;
		Long primerCierreQueRecorto = null;
		boolean huboRecorte = false;

		for (DisponibilidadExcepcion cierre : cierres) {
			IntervaloLocal tapado = cierre.intervalo().orElse(DIA_ENTERO);
			List<FranjaEfectiva> sobrevivientes = new ArrayList<>(vigentes.size());
			boolean recorto = false;
			for (FranjaEfectiva franja : vigentes) {
				if (!franja.intervalo().solapaCon(tapado)) {
					sobrevivientes.add(franja);
					continue;
				}
				recorto = true;
				for (IntervaloLocal resto : franja.intervalo().restar(tapado)) {
					sobrevivientes.add(new FranjaEfectiva(
							resto, franja.origen(), OrigenFranja.CIERRE, franja.reglaId()));
				}
			}
			if (recorto && !huboRecorte) {
				huboRecorte = true;
				primerCierreQueRecorto = cierre.getId();
			}
			vigentes = sobrevivientes;
		}

		if (vigentes.isEmpty()) {
			// Un dia que quedo en blanco porque nadie lo abrio no tiene regla que lo explique;
			// uno que vacio un cierre, si, y la UI necesita poder linkearla. Lo que distingue
			// los dos casos es si algun cierre llego a recortar algo, no si habia cierres
			// cargados: un cierre que no solapa con nada no explica un dia vacio.
			return huboRecorte
					? DiaCalculado.vacio(OrigenFranja.CIERRE, primerCierreQueRecorto)
					: DiaCalculado.sinReglas();
		}
		vigentes.sort(ORDEN_FRANJAS);
		return DiaCalculado.con(vigentes);
	}

	private static List<BloqueDisponibilidad> bloquesDelProfesional(
			long membershipId, List<BloqueDisponibilidad> bloques) {

		if (bloques == null) {
			return List.of();
		}
		return bloques.stream()
				.filter(bloque -> bloque.getMembershipId() != null
						&& bloque.getMembershipId() == membershipId)
				.filter(BloqueDisponibilidad::isOperable)
				.sorted(ORDEN_BLOQUES)
				.toList();
	}

	/**
	 * Se quedan las excepciones de la sede entera y las de este profesional. La de un companero
	 * se descarta aca y no solo en la consulta: que el alcance lo decida el calculo lo hace
	 * testeable sin base de datos, y evita que un repositorio distraido filtre de menos.
	 */
	private static List<DisponibilidadExcepcion> excepcionesAplicables(
			long membershipId, List<DisponibilidadExcepcion> excepciones) {

		if (excepciones == null) {
			return List.of();
		}
		return excepciones.stream()
				.filter(DisponibilidadExcepcion::isOperable)
				.filter(excepcion -> excepcion.esDeSede()
						|| excepcion.getMembershipId() == membershipId)
				.sorted(ORDEN_EXCEPCIONES)
				.toList();
	}

	private static List<DisponibilidadExcepcion> delTipo(
			List<DisponibilidadExcepcion> aplicables, TipoExcepcion tipo, LocalDate fecha) {

		return aplicables.stream()
				.filter(excepcion -> excepcion.getTipo() == tipo)
				.filter(excepcion -> excepcion.cubreFecha(fecha))
				.toList();
	}
}
