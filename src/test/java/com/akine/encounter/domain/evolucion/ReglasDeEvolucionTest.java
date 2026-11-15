package com.akine.encounter.domain.evolucion;

import static com.akine.encounter.domain.evolucion.CodigoDeHallazgo.BAJA_ADHERENCIA;
import static com.akine.encounter.domain.evolucion.CodigoDeHallazgo.CONDUCTA_SIN_SEGUIR;
import static com.akine.encounter.domain.evolucion.CodigoDeHallazgo.DATOS_INSUFICIENTES;
import static com.akine.encounter.domain.evolucion.CodigoDeHallazgo.DOLOR_EN_AUMENTO;
import static com.akine.encounter.domain.evolucion.CodigoDeHallazgo.DOLOR_SIN_MEJORA;
import static com.akine.encounter.domain.evolucion.CodigoDeHallazgo.EVOLUCION_DESFAVORABLE;
import static com.akine.encounter.domain.evolucion.CodigoDeHallazgo.FRECUENCIA_BAJO_PLAN;
import static com.akine.encounter.domain.evolucion.CodigoDeHallazgo.MALA_TOLERANCIA;
import static com.akine.encounter.domain.evolucion.CodigoDeHallazgo.MEDICION_EMPEORA;
import static com.akine.encounter.domain.evolucion.CodigoDeHallazgo.MEDICION_SIN_CAMBIO;
import static com.akine.encounter.domain.evolucion.CodigoDeHallazgo.PLAN_COMPLETO_ABIERTO;
import static com.akine.encounter.domain.evolucion.CodigoDeHallazgo.PLAN_EXCEDIDO_EN_TIEMPO;
import static com.akine.encounter.domain.evolucion.CodigoDeHallazgo.SIN_MEDICIONES;
import static com.akine.encounter.domain.evolucion.CodigoDeHallazgo.SIN_PLAN_ACTIVO;
import static org.assertj.core.api.Assertions.assertThat;

import com.akine.encounter.domain.Evolucion;
import com.akine.encounter.domain.ProximaConducta;
import com.akine.encounter.domain.Tolerancia;
import com.akine.encounter.domain.evolucion.DatosDelCaso.Definicion;
import com.akine.encounter.domain.evolucion.DatosDelCaso.Medicion;
import com.akine.encounter.domain.evolucion.DatosDelCaso.Plan;
import com.akine.encounter.domain.evolucion.DatosDelCaso.Sesion;
import com.akine.encounter.domain.evolucion.DatosDelCaso.Turno;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** RF-M31-001 y RF-M31-002: una regla por alerta, con su caso y su borde. */
class ReglasDeEvolucionTest {

	private static final LocalDate HOY = LocalDate.of(2026, 11, 15);
	private static final UmbralesDeEvolucion UMBRALES = UmbralesDeEvolucion.porDefecto();
	private static final Plan PLAN_NEUTRO = new Plan("ACTIVO", HOY.minusWeeks(8), instante(HOY.minusWeeks(8)),
			null, null, false);
	private static final long ROM = 50L;

	@Test
	void conMenosDeTresPresentesSoloAvisaDatosInsuficientes() {
		AnalisisDeEvolucion analisis = analizar(caso(sesiones(dolor(8, 8))));

		assertThat(codigos(analisis)).containsExactly(DATOS_INSUFICIENTES);
		assertThat(analisis.indicadores().dolor().puntos()).hasSize(2);
	}

	@Nested
	class Dolor {

		@Test
		void sinMejoraRelevanteYAltoDispara() {
			assertThat(codigos(analizar(caso(sesiones(dolor(7, 7, 6, 6)))))).contains(DOLOR_SIN_MEJORA);
		}

		@Test
		void conDosPuntosDeBajadaNoDispara() {
			assertThat(codigos(analizar(caso(sesiones(dolor(7, 6, 5, 5)))))).doesNotContain(DOLOR_SIN_MEJORA);
		}

		@Test
		void subirDosPuntosDisparaAumentoYNoSinMejora() {
			AnalisisDeEvolucion analisis = analizar(caso(sesiones(dolor(5, 3, 4, 5))));

			assertThat(codigos(analisis)).contains(DOLOR_EN_AUMENTO).doesNotContain(DOLOR_SIN_MEJORA);
			assertThat(analisis.indicadores().dolor().diferencia()).isEqualByComparingTo("0");
		}

		@Test
		void subirUnPuntoNoEsAumento() {
			assertThat(codigos(analizar(caso(sesiones(dolor(2, 3, 3, 4)))))).doesNotContain(DOLOR_EN_AUMENTO);
		}
	}

	@Nested
	class EvolucionYTolerancia {

		@Test
		void dosPeorSeguidasAlFinalDisparan() {
			List<Sesion> sesiones = sesiones(evolucion(Evolucion.MEJOR, Evolucion.PEOR, Evolucion.PEOR));
			assertThat(codigos(analizar(caso(sesiones)))).contains(EVOLUCION_DESFAVORABLE);
		}

		@Test
		void unPeorQueYaMejoroNoDispara() {
			List<Sesion> sesiones = sesiones(evolucion(Evolucion.PEOR, Evolucion.PEOR, Evolucion.MEJOR));
			assertThat(codigos(analizar(caso(sesiones)))).doesNotContain(EVOLUCION_DESFAVORABLE);
		}

		@Test
		void cuatroIgualSeguidasDisparanYTresNo() {
			assertThat(codigos(analizar(caso(sesiones(evolucion(
					Evolucion.IGUAL, Evolucion.IGUAL, Evolucion.IGUAL, Evolucion.IGUAL))))))
					.contains(EVOLUCION_DESFAVORABLE);
			assertThat(codigos(analizar(caso(sesiones(evolucion(
					Evolucion.MEJOR, Evolucion.IGUAL, Evolucion.IGUAL, Evolucion.IGUAL))))))
					.doesNotContain(EVOLUCION_DESFAVORABLE);
		}

		@Test
		void dosMalaToleranciaSeguidasDisparanYUnaNo() {
			assertThat(codigos(analizar(caso(sesiones(tolerancia(
					Tolerancia.BUENA, Tolerancia.MALA, Tolerancia.MALA)))))).contains(MALA_TOLERANCIA);
			assertThat(codigos(analizar(caso(sesiones(tolerancia(
					Tolerancia.MALA, Tolerancia.BUENA, Tolerancia.MALA)))))).doesNotContain(MALA_TOLERANCIA);
		}
	}

	@Nested
	class Mediciones {

		@Test
		void unaSerieQuietaDentroDelRangoDisparaSinCambio() {
			DatosDelCaso datos = conDefinicion(sesiones(rom("grados", 90, 91, 90, 92)), null);
			assertThat(codigos(analizar(datos))).contains(MEDICION_SIN_CAMBIO);
		}

		@Test
		void sinRangoDeclaradoNoSeEvalua() {
			DatosDelCaso datos = new DatosDelCaso(true, sesiones(rom("grados", 90, 91, 90, 92)), List.of(),
					PLAN_NEUTRO, Map.of(ROM, new Definicion(null, null, "SUBE")));
			assertThat(codigos(analizar(datos))).doesNotContain(MEDICION_SIN_CAMBIO, MEDICION_EMPEORA);
		}

		@Test
		void conSentidoSubeYValoresQueBajanDisparaEmpeora() {
			DatosDelCaso datos = conDefinicion(sesiones(rom("grados", 100, 95, 90, 85)), "SUBE");
			assertThat(codigos(analizar(datos))).contains(MEDICION_EMPEORA).doesNotContain(MEDICION_SIN_CAMBIO);
		}

		@Test
		void sinSentidoDeclaradoNuncaEmpeora() {
			DatosDelCaso datos = conDefinicion(sesiones(rom("grados", 100, 95, 90, 85)), null);
			assertThat(codigos(analizar(datos))).doesNotContain(MEDICION_EMPEORA);
		}

		@Test
		void unaSerieQueCambiaDeUnidadNoSeCalculaNiSeEvalua() {
			List<Sesion> sesiones = sesiones(rom("grados", 90, 91, 90));
			sesiones.add(sesion(sesiones.size(), s -> s.medicion = medicion("radianes", 1.6)));
			AnalisisDeEvolucion analisis = analizar(conDefinicion(sesiones, null));

			assertThat(analisis.indicadores().mediciones()).singleElement()
					.satisfies(serie -> {
						assertThat(serie.unidadesDifieren()).isTrue();
						assertThat(serie.serie().diferencia()).isNull();
					});
			assertThat(codigos(analisis)).doesNotContain(MEDICION_SIN_CAMBIO, MEDICION_EMPEORA);
		}

		@Test
		void cuatroSesionesSinDolorNiMedicionesAvisan() {
			assertThat(codigos(analizar(caso(sesiones(4))))).contains(SIN_MEDICIONES);
			assertThat(codigos(analizar(caso(sesiones(dolor(5, 5, 4, 3)))))).doesNotContain(SIN_MEDICIONES);
		}
	}

	@Nested
	class Adherencia {

		@Test
		void dosFaltasEnSeisCitasDisparan() {
			List<Sesion> sesiones = sesionesConTurno(4);
			List<Turno> turnos = turnosDe(sesiones);
			turnos.add(new Turno(901L, HOY.minusDays(3), "AUSENTE", null));
			turnos.add(new Turno(902L, HOY.minusDays(2), "CANCELADO", "PACIENTE"));

			AnalisisDeEvolucion analisis = analizar(new DatosDelCaso(true, sesiones, turnos, PLAN_NEUTRO, Map.of()));

			assertThat(codigos(analisis)).contains(BAJA_ADHERENCIA);
			assertThat(analisis.indicadores().adherencia().inasistencias()).isEqualTo(2);
			assertThat(analisis.indicadores().adherencia().canceladosPorPaciente()).isEqualTo(1);
		}

		@Test
		void lasCancelacionesDeLaInstitucionNoCuentan() {
			List<Sesion> sesiones = sesionesConTurno(4);
			List<Turno> turnos = turnosDe(sesiones);
			turnos.add(new Turno(901L, HOY.minusDays(3), "CANCELADO", "INSTITUCION"));
			turnos.add(new Turno(902L, HOY.minusDays(2), "CANCELADO", "PROFESIONAL"));

			AnalisisDeEvolucion analisis = analizar(new DatosDelCaso(true, sesiones, turnos, PLAN_NEUTRO, Map.of()));

			assertThat(codigos(analisis)).doesNotContain(BAJA_ADHERENCIA);
			assertThat(analisis.indicadores().adherencia().citas()).isEqualTo(4);
		}

		@Test
		void unaAusenciaEnTurnoYEnSesionCuentaUnaVez() {
			List<Sesion> sesiones = sesionesConTurno(4);
			Sesion ausente = sesion(4, s -> {
				s.presente = false;
				s.turnoId = 1004L;
			});
			sesiones.add(ausente);
			List<Turno> turnos = turnosDe(sesiones.subList(0, 4));
			turnos.add(new Turno(1004L, ausente.fecha(), "AUSENTE", null));

			AnalisisDeEvolucion analisis = analizar(new DatosDelCaso(true, sesiones, turnos, PLAN_NEUTRO, Map.of()));

			assertThat(analisis.indicadores().adherencia().inasistencias()).isEqualTo(1);
			assertThat(analisis.indicadores().adherencia().citas()).isEqualTo(5);
		}
	}

	@Nested
	class PlanYConducta {

		@Test
		void unaSesionPorSemanaContraTresPlanificadasDispara() {
			Plan plan = new Plan("ACTIVO", HOY.minusWeeks(8), instante(HOY.minusWeeks(8)), 3, null, false);
			assertThat(codigos(analizar(new DatosDelCaso(true, sesionesRecientes(4), List.of(), plan, Map.of()))))
					.contains(FRECUENCIA_BAJO_PLAN);
		}

		@Test
		void unaSesionPorSemanaContraDosPlanificadasNoDispara() {
			Plan plan = new Plan("ACTIVO", HOY.minusWeeks(8), instante(HOY.minusWeeks(8)), 2, null, false);
			assertThat(codigos(analizar(new DatosDelCaso(true, sesionesRecientes(4), List.of(), plan, Map.of()))))
					.doesNotContain(FRECUENCIA_BAJO_PLAN);
		}

		@Test
		void planCompletoYVencidoAvisaSoloSiEstaActivo() {
			Plan activo = new Plan("ACTIVO", HOY.minusWeeks(10), instante(HOY.minusWeeks(10)), null, 8, true);
			Plan suspendido = new Plan("SUSPENDIDO", HOY.minusWeeks(10), instante(HOY.minusWeeks(10)), null, 8, true);

			assertThat(codigos(analizar(new DatosDelCaso(true, sesiones(3), List.of(), activo, Map.of()))))
					.contains(PLAN_COMPLETO_ABIERTO, PLAN_EXCEDIDO_EN_TIEMPO);
			assertThat(codigos(analizar(new DatosDelCaso(true, sesiones(3), List.of(), suspendido, Map.of()))))
					.doesNotContain(PLAN_COMPLETO_ABIERTO, PLAN_EXCEDIDO_EN_TIEMPO, SIN_PLAN_ACTIVO);
		}

		@Test
		void casoActivoSinPlanAvisa() {
			assertThat(codigos(analizar(new DatosDelCaso(true, sesiones(3), List.of(), null, Map.of()))))
					.contains(SIN_PLAN_ACTIVO);
			assertThat(codigos(analizar(new DatosDelCaso(false, sesiones(3), List.of(), null, Map.of()))))
					.doesNotContain(SIN_PLAN_ACTIVO);
		}

		@Test
		void altaSeguidaDeOtraSesionSinCambiarElPlanDispara() {
			List<Sesion> sesiones = sesiones(3);
			sesiones.set(1, sesion(1, s -> s.conducta = ProximaConducta.ALTA));

			assertThat(codigos(analizar(caso(sesiones)))).contains(CONDUCTA_SIN_SEGUIR);
		}

		@Test
		void altaSeguidaDeUnCambioDePlanNoDispara() {
			List<Sesion> sesiones = sesiones(3);
			sesiones.set(1, sesion(1, s -> s.conducta = ProximaConducta.ALTA));
			Plan cambiado = new Plan("ACTIVO", HOY.minusWeeks(8), instante(sesiones.get(2).fecha()), null, null, false);

			assertThat(codigos(analizar(new DatosDelCaso(true, sesiones, List.of(), cambiado, Map.of()))))
					.doesNotContain(CONDUCTA_SIN_SEGUIR);
		}

		@Test
		void reevaluarNecesitaDosSesionesDespues() {
			List<Sesion> sesiones = sesiones(3);
			sesiones.set(1, sesion(1, s -> s.conducta = ProximaConducta.REEVALUA));
			assertThat(codigos(analizar(caso(sesiones)))).doesNotContain(CONDUCTA_SIN_SEGUIR);

			sesiones.set(0, sesion(0, s -> s.conducta = ProximaConducta.REEVALUA));
			sesiones.set(1, sesion(1, s -> { }));
			assertThat(codigos(analizar(caso(sesiones)))).contains(CONDUCTA_SIN_SEGUIR);
		}
	}

	// =================================================================================
	// Armado
	// =================================================================================

	private static final class Borrador {
		boolean presente = true;
		Long turnoId;
		Integer eva;
		Evolucion evolucion;
		Tolerancia tolerancia;
		ProximaConducta conducta;
		Medicion medicion;
	}

	private interface Ajuste {
		void aplicar(Borrador borrador);
	}

	/** La sesion {@code i}, una por semana desde seis semanas atras: despues de la version del plan neutro. */
	private static Sesion sesion(int i, Ajuste ajuste) {
		Borrador b = new Borrador();
		ajuste.aplicar(b);
		LocalDate fecha = HOY.minusWeeks(6).plusWeeks(i);
		return new Sesion(100L + i, fecha, instante(fecha), b.presente, b.turnoId, b.eva, "rodilla",
				b.evolucion, b.tolerancia, b.conducta,
				b.medicion == null ? List.of() : List.of(b.medicion), List.of());
	}

	private static List<Sesion> sesiones(int cantidad) {
		List<Sesion> sesiones = new ArrayList<>();
		for (int i = 0; i < cantidad; i++) {
			sesiones.add(sesion(i, s -> { }));
		}
		return sesiones;
	}

	private static List<Sesion> sesiones(List<Ajuste> ajustes) {
		List<Sesion> sesiones = new ArrayList<>();
		for (int i = 0; i < ajustes.size(); i++) {
			sesiones.add(sesion(i, ajustes.get(i)));
		}
		return sesiones;
	}

	/** Una sesion por semana, la ultima ayer: lo que mide la ventana de frecuencia. */
	private static List<Sesion> sesionesRecientes(int cantidad) {
		List<Sesion> sesiones = new ArrayList<>();
		for (int i = 0; i < cantidad; i++) {
			LocalDate fecha = HOY.minusDays(1).minusWeeks(cantidad - 1L - i);
			sesiones.add(new Sesion(200L + i, fecha, instante(fecha), true, null, null, null,
					null, null, null, List.of(), List.of()));
		}
		return sesiones;
	}

	private static List<Sesion> sesionesConTurno(int cantidad) {
		List<Sesion> sesiones = new ArrayList<>();
		for (int i = 0; i < cantidad; i++) {
			long turno = 1000L + i;
			sesiones.add(sesion(i, s -> s.turnoId = turno));
		}
		return sesiones;
	}

	private static List<Turno> turnosDe(List<Sesion> sesiones) {
		List<Turno> turnos = new ArrayList<>();
		for (Sesion sesion : sesiones) {
			turnos.add(new Turno(sesion.turnoId(), sesion.fecha(), "CONFIRMADO", null));
		}
		return turnos;
	}

	private static List<Ajuste> dolor(int... valores) {
		List<Ajuste> ajustes = new ArrayList<>();
		for (int valor : valores) {
			ajustes.add(s -> s.eva = valor);
		}
		return ajustes;
	}

	private static List<Ajuste> evolucion(Evolucion... valores) {
		List<Ajuste> ajustes = new ArrayList<>();
		for (Evolucion valor : valores) {
			ajustes.add(s -> s.evolucion = valor);
		}
		return ajustes;
	}

	private static List<Ajuste> tolerancia(Tolerancia... valores) {
		List<Ajuste> ajustes = new ArrayList<>();
		for (Tolerancia valor : valores) {
			ajustes.add(s -> s.tolerancia = valor);
		}
		return ajustes;
	}

	private static List<Ajuste> rom(String unidad, double... valores) {
		List<Ajuste> ajustes = new ArrayList<>();
		for (double valor : valores) {
			ajustes.add(s -> s.medicion = medicion(unidad, valor));
		}
		return ajustes;
	}

	private static Medicion medicion(String unidad, double valor) {
		return new Medicion(ROM, "ROM_RODILLA", "ROM de rodilla", unidad, "DERECHA", BigDecimal.valueOf(valor));
	}

	private static DatosDelCaso caso(List<Sesion> sesiones) {
		return new DatosDelCaso(true, sesiones, List.of(), PLAN_NEUTRO, Map.of());
	}

	private static DatosDelCaso conDefinicion(List<Sesion> sesiones, String mejoraCuando) {
		return new DatosDelCaso(true, sesiones, List.of(), PLAN_NEUTRO,
				Map.of(ROM, new Definicion(BigDecimal.ZERO, new BigDecimal("160"), mejoraCuando)));
	}

	private static AnalisisDeEvolucion analizar(DatosDelCaso datos) {
		return ReglasDeEvolucion.analizar(datos, UMBRALES, HOY);
	}

	private static List<CodigoDeHallazgo> codigos(AnalisisDeEvolucion analisis) {
		return analisis.hallazgos().stream().map(Hallazgo::codigo).toList();
	}

	private static Instant instante(LocalDate fecha) {
		return fecha.atTime(12, 0).toInstant(ZoneOffset.UTC);
	}
}
