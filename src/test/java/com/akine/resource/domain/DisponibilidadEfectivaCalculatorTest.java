package com.akine.resource.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El nucleo de la etapa. Cada test de aca clava una regla de negocio concreta: si alguno se
 * pone en verde por casualidad, el motor de slots de F5 hereda el error sin que nadie lo vea.
 *
 * <p>Los dos que mas importan son {@code un_cierre_recorta_lo_que_abrio_una_apertura} —fija el
 * orden que exige RN-M05-002— y
 * {@code el_resultado_no_depende_del_orden_de_las_excepciones_de_entrada}, que es la mitad
 * "determinista" del criterio de aceptacion.
 *
 * <p>Las entidades se construyen a mano y se les inyecta el id por reflexion: sin id no se
 * puede afirmar nada sobre la trazabilidad, que es la otra mitad del criterio.
 */
class DisponibilidadEfectivaCalculatorTest {

	private static final long ORG = 1L;
	private static final long SEDE = 10L;
	private static final long PROFESIONAL = 100L;
	private static final long OTRO_PROFESIONAL = 200L;

	private static final LocalDate LUNES = LocalDate.of(2026, 3, 2);
	private static final LocalDate MARTES = LUNES.plusDays(1);
	private static final LocalDate MIERCOLES = LUNES.plusDays(2);
	private static final LocalDate JUEVES = LUNES.plusDays(3);
	private static final LocalDate SABADO = LUNES.plusDays(5);

	private static final int DIA_LUNES = 1;
	private static final int DIA_MARTES = 2;
	private static final int DIA_MIERCOLES = 3;
	private static final int DIA_JUEVES = 4;

	private static final LocalTime OCHO = LocalTime.of(8, 0);
	private static final LocalTime NUEVE = LocalTime.of(9, 0);
	private static final LocalTime ONCE = LocalTime.of(11, 0);
	private static final LocalTime DOCE = LocalTime.of(12, 0);
	private static final LocalTime TRECE = LocalTime.of(13, 0);
	private static final LocalTime CATORCE = LocalTime.of(14, 0);
	private static final LocalTime QUINCE = LocalTime.of(15, 0);
	private static final LocalTime DIECISEIS = LocalTime.of(16, 0);
	private static final LocalTime DIECIOCHO = LocalTime.of(18, 0);

	private final DisponibilidadEfectivaCalculator calculator = new DisponibilidadEfectivaCalculator();

	// =================================================================================
	// horario base
	// =================================================================================

	@Nested
	class HorarioBase {

		@Test
		void un_bloque_del_lunes_no_aparece_el_martes() {
			BloqueDisponibilidad lunes = bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES.minusMonths(1), null);

			Map<LocalDate, DiaCalculado> efectiva = calcular(LUNES, MIERCOLES, List.of(lunes), List.of(), Set.of());

			assertThat(intervalos(efectiva.get(LUNES))).containsExactly(new IntervaloLocal(OCHO, DOCE));
			assertThat(efectiva.get(MARTES).estaVacio()).isTrue();
			assertThat(efectiva.get(MARTES).razonVacio()).isNull();
		}

		@Test
		void un_bloque_cuya_vigencia_empieza_despues_no_aparece() {
			BloqueDisponibilidad futuro =
					bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES.plusWeeks(1), null);

			Map<LocalDate, DiaCalculado> efectiva =
					calcular(LUNES, LUNES.plusWeeks(2), List.of(futuro), List.of(), Set.of());

			assertThat(efectiva.get(LUNES).estaVacio()).isTrue();
			assertThat(intervalos(efectiva.get(LUNES.plusWeeks(1))))
					.containsExactly(new IntervaloLocal(OCHO, DOCE));
		}

		@Test
		void un_bloque_cuya_vigencia_termino_no_aparece() {
			BloqueDisponibilidad vencido =
					bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES.minusWeeks(2), LUNES.minusWeeks(1));

			Map<LocalDate, DiaCalculado> efectiva =
					calcular(LUNES.minusWeeks(2), LUNES.plusDays(1), List.of(vencido), List.of(), Set.of());

			assertThat(intervalos(efectiva.get(LUNES.minusWeeks(2))))
					.as("estaba vigente antes de que terminara")
					.containsExactly(new IntervaloLocal(OCHO, DOCE));
			assertThat(efectiva.get(LUNES.minusWeeks(1)).estaVacio()).isTrue();
			assertThat(efectiva.get(LUNES).estaVacio()).isTrue();
		}

		@Test
		void vigencia_hasta_es_exclusivo() {
			LocalDate segundoMartes = MARTES.plusWeeks(1);
			BloqueDisponibilidad bloque =
					bloque(1L, PROFESIONAL, DIA_MARTES, NUEVE, TRECE, MARTES, segundoMartes);

			Map<LocalDate, DiaCalculado> efectiva =
					calcular(MARTES, segundoMartes.plusDays(1), List.of(bloque), List.of(), Set.of());

			assertThat(efectiva.get(MARTES).estaVacio()).as("el primer martes esta adentro").isFalse();
			assertThat(efectiva.get(segundoMartes).estaVacio())
					.as("el dia del fin de vigencia ya esta afuera")
					.isTrue();
		}

		@Test
		void vigencia_hasta_null_significa_sin_fin() {
			BloqueDisponibilidad eterno =
					bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES, null);
			LocalDate lunesLejano = LUNES.plusWeeks(260);

			Map<LocalDate, DiaCalculado> efectiva =
					calcular(lunesLejano, lunesLejano.plusDays(1), List.of(eterno), List.of(), Set.of());

			assertThat(intervalos(efectiva.get(lunesLejano)))
					.containsExactly(new IntervaloLocal(OCHO, DOCE));
		}

		@Test
		void dos_bloques_el_mismo_dia_aparecen_los_dos() {
			BloqueDisponibilidad manana = bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES, null);
			BloqueDisponibilidad tarde = bloque(2L, PROFESIONAL, DIA_LUNES, CATORCE, DIECIOCHO, LUNES, null);

			DiaCalculado dia = unDia(LUNES, List.of(tarde, manana), List.of(), Set.of());

			assertThat(intervalos(dia))
					.containsExactly(new IntervaloLocal(OCHO, DOCE), new IntervaloLocal(CATORCE, DIECIOCHO));
			assertThat(dia.franjas()).extracting(FranjaEfectiva::reglaId).containsExactly(1L, 2L);
		}

		@Test
		void un_bloque_hasta_medianoche_llega_a_fin_de_dia() {
			BloqueDisponibilidad nocturno = bloque(
					1L, PROFESIONAL, DIA_LUNES, DIECIOCHO, IntervaloLocal.FIN_DE_DIA, LUNES, null);

			DiaCalculado dia = unDia(LUNES, List.of(nocturno), List.of(), Set.of());

			assertThat(dia.franjas()).singleElement()
					.extracting(FranjaEfectiva::intervalo)
					.isEqualTo(new IntervaloLocal(DIECIOCHO, IntervaloLocal.FIN_DE_DIA));
		}
	}

	// =================================================================================
	// cierres
	// =================================================================================

	@Nested
	class Cierres {

		@Test
		void un_cierre_de_dia_completo_deja_el_dia_vacio() {
			BloqueDisponibilidad bloque = bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES, null);
			DisponibilidadExcepcion licencia = excepcion(
					50L, PROFESIONAL, TipoExcepcion.CIERRE, MotivoExcepcion.LICENCIA,
					LUNES, MARTES, null, null);

			DiaCalculado dia = unDia(LUNES, List.of(bloque), List.of(licencia), Set.of());

			assertThat(dia.estaVacio()).isTrue();
			assertThat(dia.razonVacio()).isEqualTo(OrigenFranja.CIERRE);
			assertThat(dia.reglaVacio()).isEqualTo(50L);
		}

		@Test
		void un_cierre_parcial_del_medio_parte_la_franja_en_dos() {
			BloqueDisponibilidad jornada = bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DIECIOCHO, LUNES, null);
			DisponibilidadExcepcion almuerzo = excepcion(
					50L, PROFESIONAL, TipoExcepcion.CIERRE, MotivoExcepcion.BLOQUEO,
					LUNES, MARTES, DOCE, TRECE);

			DiaCalculado dia = unDia(LUNES, List.of(jornada), List.of(almuerzo), Set.of());

			assertThat(intervalos(dia)).containsExactly(
					new IntervaloLocal(OCHO, DOCE), new IntervaloLocal(TRECE, DIECIOCHO));
			assertThat(dia.franjas()).allSatisfy(franja -> {
				assertThat(franja.origen()).isEqualTo(OrigenFranja.BLOQUE);
				assertThat(franja.recortadoPor()).isEqualTo(OrigenFranja.CIERRE);
			});
		}

		@Test
		void un_cierre_de_la_sede_afecta_a_todos_los_profesionales() {
			BloqueDisponibilidad uno = bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES, null);
			BloqueDisponibilidad otro = bloque(2L, OTRO_PROFESIONAL, DIA_LUNES, NUEVE, TRECE, LUNES, null);
			DisponibilidadExcepcion feriadoPuente = excepcion(
					50L, null, TipoExcepcion.CIERRE, MotivoExcepcion.BLOQUEO, LUNES, MARTES, null, null);

			List<BloqueDisponibilidad> bloques = List.of(uno, otro);
			List<DisponibilidadExcepcion> excepciones = List.of(feriadoPuente);

			DiaCalculado delPrimero = calculator
					.calcular(PROFESIONAL, LUNES, MARTES, bloques, excepciones, Set.of()).get(LUNES);
			DiaCalculado delSegundo = calculator
					.calcular(OTRO_PROFESIONAL, LUNES, MARTES, bloques, excepciones, Set.of()).get(LUNES);

			assertThat(delPrimero.estaVacio()).isTrue();
			assertThat(delSegundo.estaVacio()).isTrue();
			assertThat(delPrimero.razonVacio()).isEqualTo(OrigenFranja.CIERRE);
			assertThat(delSegundo.razonVacio()).isEqualTo(OrigenFranja.CIERRE);
		}

		@Test
		void un_cierre_del_profesional_no_afecta_a_otro() {
			BloqueDisponibilidad uno = bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES, null);
			BloqueDisponibilidad otro = bloque(2L, OTRO_PROFESIONAL, DIA_LUNES, NUEVE, TRECE, LUNES, null);
			DisponibilidadExcepcion ausenciaAjena = excepcion(
					50L, OTRO_PROFESIONAL, TipoExcepcion.CIERRE, MotivoExcepcion.AUSENCIA,
					LUNES, MARTES, null, null);

			List<BloqueDisponibilidad> bloques = List.of(uno, otro);
			List<DisponibilidadExcepcion> excepciones = List.of(ausenciaAjena);

			DiaCalculado propio = calculator
					.calcular(PROFESIONAL, LUNES, MARTES, bloques, excepciones, Set.of()).get(LUNES);
			DiaCalculado ajeno = calculator
					.calcular(OTRO_PROFESIONAL, LUNES, MARTES, bloques, excepciones, Set.of()).get(LUNES);

			assertThat(intervalos(propio))
					.as("la ausencia del companero no toca mi agenda")
					.containsExactly(new IntervaloLocal(OCHO, DOCE));
			assertThat(ajeno.estaVacio()).isTrue();
		}

		@Test
		void un_cierre_de_varios_dias_cubre_los_dias_del_medio() {
			List<BloqueDisponibilidad> semana = List.of(
					bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES, null),
					bloque(2L, PROFESIONAL, DIA_MARTES, OCHO, DOCE, LUNES, null),
					bloque(3L, PROFESIONAL, DIA_MIERCOLES, OCHO, DOCE, LUNES, null),
					bloque(4L, PROFESIONAL, DIA_JUEVES, OCHO, DOCE, LUNES, null));
			DisponibilidadExcepcion licencia = excepcion(
					50L, PROFESIONAL, TipoExcepcion.CIERRE, MotivoExcepcion.LICENCIA,
					LUNES, JUEVES, null, null);

			Map<LocalDate, DiaCalculado> efectiva =
					calcular(LUNES, JUEVES.plusDays(1), semana, List.of(licencia), Set.of());

			assertThat(efectiva.get(LUNES).estaVacio()).isTrue();
			assertThat(efectiva.get(MARTES).estaVacio()).as("el dia del medio tambien").isTrue();
			assertThat(efectiva.get(MIERCOLES).estaVacio()).isTrue();
			assertThat(intervalos(efectiva.get(JUEVES)))
					.as("fecha_hasta es exclusiva: el jueves ya no esta cerrado")
					.containsExactly(new IntervaloLocal(OCHO, DOCE));
		}

		@Test
		void un_cierre_que_no_solapa_deja_la_franja_intacta() {
			BloqueDisponibilidad manana = bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES, null);
			DisponibilidadExcepcion tarde = excepcion(
					50L, PROFESIONAL, TipoExcepcion.CIERRE, MotivoExcepcion.BLOQUEO,
					LUNES, MARTES, CATORCE, DIECISEIS);

			DiaCalculado dia = unDia(LUNES, List.of(manana), List.of(tarde), Set.of());

			assertThat(dia.franjas()).singleElement().satisfies(franja -> {
				assertThat(franja.intervalo()).isEqualTo(new IntervaloLocal(OCHO, DOCE));
				assertThat(franja.recortadoPor())
						.as("un cierre que no la toca no la marca como recortada")
						.isNull();
			});
		}
	}

	// =================================================================================
	// aperturas
	// =================================================================================

	@Nested
	class Aperturas {

		@Test
		void una_apertura_agrega_una_franja_donde_no_habia_bloque() {
			DisponibilidadExcepcion ampliacion = excepcion(
					50L, PROFESIONAL, TipoExcepcion.APERTURA, MotivoExcepcion.AMPLIACION,
					SABADO, SABADO.plusDays(1), NUEVE, TRECE);

			DiaCalculado dia = unDia(SABADO, List.of(), List.of(ampliacion), Set.of());

			assertThat(dia.franjas()).singleElement().satisfies(franja -> {
				assertThat(franja.intervalo()).isEqualTo(new IntervaloLocal(NUEVE, TRECE));
				assertThat(franja.origen()).isEqualTo(OrigenFranja.APERTURA);
				assertThat(franja.reglaId()).isEqualTo(50L);
			});
		}

		@Test
		void una_apertura_de_dia_completo_abre_el_dia_entero() {
			DisponibilidadExcepcion ampliacion = excepcion(
					50L, PROFESIONAL, TipoExcepcion.APERTURA, MotivoExcepcion.AMPLIACION,
					SABADO, SABADO.plusDays(1), null, null);

			DiaCalculado dia = unDia(SABADO, List.of(), List.of(ampliacion), Set.of());

			assertThat(intervalos(dia))
					.containsExactly(new IntervaloLocal(LocalTime.MIN, IntervaloLocal.FIN_DE_DIA));
		}
	}

	// =================================================================================
	// la regla que decide el orden (RN-M05-002)
	// =================================================================================

	@Nested
	class OrdenDeLasEtapas {

		/**
		 * El caso que fija el orden. Si esto falla, las etapas 2 y 4 estan invertidas: la
		 * apertura estaria tapando al cierre y el resultado seria 09:00-13:00 entero.
		 */
		@Test
		void un_cierre_recorta_lo_que_abrio_una_apertura() {
			DisponibilidadExcepcion ampliacion = excepcion(
					50L, PROFESIONAL, TipoExcepcion.APERTURA, MotivoExcepcion.AMPLIACION,
					SABADO, SABADO.plusDays(1), NUEVE, TRECE);
			DisponibilidadExcepcion ausencia = excepcion(
					51L, PROFESIONAL, TipoExcepcion.CIERRE, MotivoExcepcion.AUSENCIA,
					SABADO, SABADO.plusDays(1), ONCE, TRECE);

			DiaCalculado dia = unDia(SABADO, List.of(), List.of(ampliacion, ausencia), Set.of());

			assertThat(dia.franjas()).singleElement().satisfies(franja -> {
				assertThat(franja.intervalo())
						.as("RN-M05-002: el cierre prevalece sobre lo que abrio la apertura")
						.isEqualTo(new IntervaloLocal(NUEVE, ONCE));
				assertThat(franja.origen()).isEqualTo(OrigenFranja.APERTURA);
				assertThat(franja.recortadoPor()).isEqualTo(OrigenFranja.CIERRE);
			});
		}
	}

	// =================================================================================
	// feriados
	// =================================================================================

	@Nested
	class Feriados {

		@Test
		void un_feriado_que_cierra_deja_el_dia_vacio_aunque_haya_bloque() {
			BloqueDisponibilidad bloque = bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES, null);

			DiaCalculado dia = unDia(LUNES, List.of(bloque), List.of(), Set.of(LUNES));

			assertThat(dia.estaVacio()).isTrue();
			assertThat(dia.razonVacio()).isEqualTo(OrigenFranja.FERIADO);
		}

		@Test
		void un_feriado_no_cierra_si_la_sede_no_cierra_por_feriado() {
			BloqueDisponibilidad bloque = bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES, null);

			// La sede que no cierra por feriado no aporta la fecha: el cruce lo hace el servicio.
			DiaCalculado dia = unDia(LUNES, List.of(bloque), List.of(), Set.of());

			assertThat(intervalos(dia)).containsExactly(new IntervaloLocal(OCHO, DOCE));
			assertThat(dia.razonVacio()).isNull();
		}

		@Test
		void una_apertura_explicita_hace_atender_en_un_feriado() {
			BloqueDisponibilidad bloque = bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES, null);
			DisponibilidadExcepcion atiendeIgual = excepcion(
					50L, PROFESIONAL, TipoExcepcion.APERTURA, MotivoExcepcion.AMPLIACION,
					LUNES, MARTES, CATORCE, DIECIOCHO);

			DiaCalculado dia = unDia(LUNES, List.of(bloque), List.of(atiendeIgual), Set.of(LUNES));

			assertThat(dia.estaVacio()).as("la apertura explicita gana al feriado").isFalse();
			assertThat(intervalos(dia)).containsExactly(
					new IntervaloLocal(OCHO, DOCE), new IntervaloLocal(CATORCE, DIECIOCHO));
			assertThat(dia.razonVacio()).isNull();
		}
	}

	// =================================================================================
	// trazabilidad: la mitad "explicable" del CA
	// =================================================================================

	@Nested
	class Trazabilidad {

		@Test
		void cada_franja_declara_la_regla_que_la_produjo() {
			BloqueDisponibilidad base = bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES, null);
			DisponibilidadExcepcion ampliacion = excepcion(
					50L, PROFESIONAL, TipoExcepcion.APERTURA, MotivoExcepcion.AMPLIACION,
					LUNES, MARTES, CATORCE, DIECISEIS);

			DiaCalculado dia = unDia(LUNES, List.of(base), List.of(ampliacion), Set.of());

			assertThat(dia.franjas())
					.extracting(FranjaEfectiva::origen, FranjaEfectiva::reglaId)
					.containsExactly(
							org.assertj.core.groups.Tuple.tuple(OrigenFranja.BLOQUE, 1L),
							org.assertj.core.groups.Tuple.tuple(OrigenFranja.APERTURA, 50L));
		}

		@Test
		void una_franja_recortada_declara_que_la_recorto() {
			BloqueDisponibilidad base = bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES, null);
			DisponibilidadExcepcion recorte = excepcion(
					50L, PROFESIONAL, TipoExcepcion.CIERRE, MotivoExcepcion.AUSENCIA,
					LUNES, MARTES, ONCE, DOCE);

			DiaCalculado dia = unDia(LUNES, List.of(base), List.of(recorte), Set.of());

			assertThat(dia.franjas()).singleElement().satisfies(franja -> {
				assertThat(franja.intervalo()).isEqualTo(new IntervaloLocal(OCHO, ONCE));
				assertThat(franja.recortadoPor()).isEqualTo(OrigenFranja.CIERRE);
				assertThat(franja.reglaId())
						.as("reglaId sigue siendo la de quien la produjo, no la del recorte")
						.isEqualTo(1L);
			});
		}

		@Test
		void un_dia_vaciado_por_feriado_lo_declara() {
			BloqueDisponibilidad base = bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES, null);
			BloqueDisponibilidad miercoles = bloque(2L, PROFESIONAL, DIA_MIERCOLES, OCHO, DOCE, LUNES, null);

			Map<LocalDate, DiaCalculado> efectiva =
					calcular(LUNES, MIERCOLES, List.of(base, miercoles), List.of(), Set.of(LUNES));

			assertThat(efectiva.get(LUNES).franjas()).isEmpty();
			assertThat(efectiva.get(LUNES).razonVacio()).isEqualTo(OrigenFranja.FERIADO);
			assertThat(efectiva.get(MARTES).razonVacio())
					.as("un dia vacio porque nadie lo abrio no inventa una regla")
					.isNull();
		}
	}

	// =================================================================================
	// determinismo
	// =================================================================================

	@Nested
	class Determinismo {

		@Test
		void el_resultado_no_depende_del_orden_de_las_excepciones_de_entrada() {
			List<BloqueDisponibilidad> bloques = new ArrayList<>(List.of(
					bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DIECIOCHO, LUNES, null),
					bloque(2L, PROFESIONAL, DIA_MARTES, NUEVE, DIECISEIS, LUNES, null),
					bloque(3L, OTRO_PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES, null)));
			List<DisponibilidadExcepcion> excepciones = new ArrayList<>(List.of(
					excepcion(50L, PROFESIONAL, TipoExcepcion.CIERRE, MotivoExcepcion.BLOQUEO,
							LUNES, MARTES, DOCE, TRECE),
					excepcion(51L, null, TipoExcepcion.CIERRE, MotivoExcepcion.BLOQUEO,
							LUNES, MIERCOLES, QUINCE, DIECISEIS),
					excepcion(52L, PROFESIONAL, TipoExcepcion.APERTURA, MotivoExcepcion.AMPLIACION,
							MARTES, MIERCOLES, DIECISEIS, DIECIOCHO),
					excepcion(53L, OTRO_PROFESIONAL, TipoExcepcion.CIERRE, MotivoExcepcion.AUSENCIA,
							LUNES, MIERCOLES, null, null)));

			Map<LocalDate, DiaCalculado> esperado =
					calcular(LUNES, JUEVES, bloques, excepciones, Set.of(MIERCOLES));

			assertThat(esperado.get(LUNES).franjas())
					.as("el escenario tiene que producir algo, si no el test es vacuo")
					.isNotEmpty();

			Random random = new Random(20260826L);
			for (int intento = 0; intento < 50; intento++) {
				Collections.shuffle(bloques, random);
				Collections.shuffle(excepciones, random);
				assertThat(calcular(LUNES, JUEVES, bloques, excepciones, Set.of(MIERCOLES)))
						.as("permutacion %d de la entrada", intento)
						.isEqualTo(esperado);
			}
		}
	}

	// =================================================================================
	// bordes
	// =================================================================================

	@Nested
	class Bordes {

		@Test
		void una_ventana_de_un_solo_dia_devuelve_un_solo_dia() {
			BloqueDisponibilidad base = bloque(1L, PROFESIONAL, DIA_LUNES, OCHO, DOCE, LUNES, null);

			Map<LocalDate, DiaCalculado> efectiva =
					calcular(LUNES, MARTES, List.of(base), List.of(), Set.of());

			assertThat(efectiva).hasSize(1).containsOnlyKeys(LUNES);
		}

		@Test
		void hasta_es_exclusivo_en_la_ventana() {
			Map<LocalDate, DiaCalculado> efectiva =
					calcular(LUNES, JUEVES, List.of(), List.of(), Set.of());

			assertThat(efectiva.keySet()).containsExactly(LUNES, MARTES, MIERCOLES);
			assertThat(calcular(LUNES, LUNES, List.of(), List.of(), Set.of()))
					.as("una ventana vacia no devuelve ningun dia")
					.isEmpty();
		}

		@Test
		void sin_bloques_ni_excepciones_devuelve_todos_los_dias_vacios() {
			Map<LocalDate, DiaCalculado> efectiva =
					calcular(LUNES, LUNES.plusWeeks(1), List.of(), List.of(), Set.of());

			assertThat(efectiva).hasSize(7);
			assertThat(efectiva.values()).allSatisfy(dia -> {
				assertThat(dia.estaVacio()).isTrue();
				assertThat(dia.razonVacio()).isNull();
				assertThat(dia.reglaVacio()).isNull();
			});
		}
	}

	// =================================================================================
	// helpers
	// =================================================================================

	private Map<LocalDate, DiaCalculado> calcular(
			LocalDate desde,
			LocalDate hasta,
			List<BloqueDisponibilidad> bloques,
			List<DisponibilidadExcepcion> excepciones,
			Set<LocalDate> feriados) {

		return calculator.calcular(PROFESIONAL, desde, hasta, bloques, excepciones, feriados);
	}

	private DiaCalculado unDia(
			LocalDate fecha,
			List<BloqueDisponibilidad> bloques,
			List<DisponibilidadExcepcion> excepciones,
			Set<LocalDate> feriados) {

		return calcular(fecha, fecha.plusDays(1), bloques, excepciones, feriados).get(fecha);
	}

	private static List<IntervaloLocal> intervalos(DiaCalculado dia) {
		return dia.franjas().stream().map(FranjaEfectiva::intervalo).toList();
	}

	private static BloqueDisponibilidad bloque(
			long id,
			long membershipId,
			int diaSemana,
			LocalTime horaDesde,
			LocalTime horaHasta,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		BloqueDisponibilidad bloque = new BloqueDisponibilidad(
				ORG, SEDE, membershipId, diaSemana, horaDesde, horaHasta, vigenciaDesde, vigenciaHasta);
		ReflectionTestUtils.setField(bloque, "id", id);
		return bloque;
	}

	private static DisponibilidadExcepcion excepcion(
			long id,
			Long membershipId,
			TipoExcepcion tipo,
			MotivoExcepcion motivo,
			LocalDate fechaDesde,
			LocalDate fechaHasta,
			LocalTime horaDesde,
			LocalTime horaHasta) {

		DisponibilidadExcepcion excepcion = new DisponibilidadExcepcion(
				ORG, SEDE, membershipId, tipo, motivo, fechaDesde, fechaHasta,
				horaDesde, horaHasta, null, null);
		ReflectionTestUtils.setField(excepcion, "id", id);
		return excepcion;
	}
}
