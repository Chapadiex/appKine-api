package com.akine.encounter.api.dto;

import com.akine.clinical.spi.PlanVigenteSnapshot;
import com.akine.encounter.application.EvolucionDelCasoView;
import com.akine.encounter.domain.evolucion.AnalisisDeEvolucion;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * La evolucion de un Caso Clinico (M31). Se calcula al leer y no se guarda.
 *
 * <p>Los tipos anidados llevan el prefijo {@code EvolucionDelCaso}: springdoc los publica por su
 * nombre simple, y {@code SchemaNameCollisionTest} falla si dos se llaman igual.
 */
@Schema(description = "Evolucion de un caso clinico: indicadores y alertas calculados sobre sus "
		+ "sesiones cerradas. Sugerencia para revisar, no indicacion")
public record EvolucionDelCasoResponse(

		@Schema(description = "Caso analizado", example = "41")
		long casoId,

		@Schema(description = "Numero del caso dentro de la historia", example = "2")
		int numeroCaso,

		@Schema(description = "ACTIVO o CERRADO", example = "ACTIVO")
		String estadoCaso,

		@Schema(description = "Diagnostico presuntivo del caso")
		String diagnosticoPresuntivo,

		@Schema(description = "Objetivo terapeutico del caso")
		String objetivoTerapeutico,

		@Schema(description = "Sesiones cerradas que entraron en el analisis", example = "9")
		int sesionesAnalizadas,

		@Schema(description = "De esas, las de asistencia PRESENTE", example = "8")
		int sesionesPresentes,

		@Schema(description = "Fecha de la primera sesion analizada, en la zona de la sede")
		LocalDate desde,

		@Schema(description = "Fecha de la ultima sesion analizada, en la zona de la sede")
		LocalDate hasta,

		@Schema(description = "Alertas, primero las de ATENCION. Con menos de tres sesiones "
				+ "presentes trae solo DATOS_INSUFICIENTES")
		List<EvolucionDelCasoHallazgoResponse> hallazgos,

		@Schema(description = "Indicadores calculados")
		EvolucionDelCasoIndicadoresResponse indicadores,

		@Schema(description = "Plan ACTIVO o SUSPENDIDO del caso. Ausente si no tiene")
		EvolucionDelCasoPlanResponse plan,

		@Schema(description = "Lo que el analisis dejo afuera: sesiones sin caso, recorte por tope")
		List<String> advertencias) {

	public static EvolucionDelCasoResponse de(EvolucionDelCasoView vista) {
		AnalisisDeEvolucion analisis = vista.analisis();
		AnalisisDeEvolucion.Indicadores ind = analisis.indicadores();
		return new EvolucionDelCasoResponse(
				vista.casoId(),
				vista.numeroCaso(),
				vista.estadoCaso(),
				vista.diagnosticoPresuntivo(),
				vista.objetivoTerapeutico(),
				analisis.sesionesAnalizadas(),
				analisis.sesionesPresentes(),
				analisis.desde(),
				analisis.hasta(),
				vista.hallazgos().stream()
						.map(h -> new EvolucionDelCasoHallazgoResponse(h.codigo().name(),
								h.severidad().name(), h.texto(), h.sesiones(), h.turnos()))
						.toList(),
				new EvolucionDelCasoIndicadoresResponse(
						EvolucionDelCasoSerieResponse.de(ind.dolor()),
						EvolucionDelCasoConteoResponse.de(ind.evolucion()),
						EvolucionDelCasoConteoResponse.de(ind.tolerancia()),
						ind.mediciones().stream().map(EvolucionDelCasoMedicionResponse::de).toList(),
						EvolucionDelCasoAdherenciaResponse.de(ind.adherencia()),
						new EvolucionDelCasoFrecuenciaResponse(ind.frecuencia().semanas(),
								ind.frecuencia().realPorSemana(), ind.frecuencia().planificadaPorSemana()),
						ind.tratamientos().stream()
								.map(t -> new EvolucionDelCasoTratamientoResponse(t.practicaId(),
										t.practica(), t.sesiones(), t.ultima()))
								.toList()),
				EvolucionDelCasoPlanResponse.de(vista.plan()),
				vista.advertencias());
	}

	@Schema(description = "Una alerta de evolucion con lo que la origina (RF-M31-002)")
	public record EvolucionDelCasoHallazgoResponse(
			@Schema(description = "Codigo de la alerta", example = "DOLOR_SIN_MEJORA",
					allowableValues = {"DATOS_INSUFICIENTES", "DOLOR_SIN_MEJORA", "DOLOR_EN_AUMENTO",
							"EVOLUCION_DESFAVORABLE", "MALA_TOLERANCIA", "MEDICION_SIN_CAMBIO",
							"MEDICION_EMPEORA", "BAJA_ADHERENCIA", "FRECUENCIA_BAJO_PLAN",
							"PLAN_COMPLETO_ABIERTO", "PLAN_EXCEDIDO_EN_TIEMPO", "SIN_PLAN_ACTIVO",
							"CONDUCTA_SIN_SEGUIR", "SIN_MEDICIONES"})
			String codigo,
			@Schema(description = "INFO o ATENCION", example = "ATENCION",
					allowableValues = {"INFO", "ATENCION"})
			String severidad,
			@Schema(description = "Que se observo. No indica conducta")
			String texto,
			@Schema(description = "Sesiones que la disparan")
			List<Long> sesiones,
			@Schema(description = "Turnos que la disparan (inasistencias)")
			List<Long> turnos) {
	}

	@Schema(description = "Indicadores de evolucion del caso")
	public record EvolucionDelCasoIndicadoresResponse(
			@Schema(description = "Dolor EVA por sesion presente")
			EvolucionDelCasoSerieResponse dolor,
			@Schema(description = "Evolucion declarada: MEJOR, IGUAL, PEOR, SIN_REFERENCIA")
			EvolucionDelCasoConteoResponse evolucion,
			@Schema(description = "Tolerancia: BUENA, REGULAR, MALA")
			EvolucionDelCasoConteoResponse tolerancia,
			@Schema(description = "Una serie por medida numerica y lado")
			List<EvolucionDelCasoMedicionResponse> mediciones,
			EvolucionDelCasoAdherenciaResponse adherencia,
			EvolucionDelCasoFrecuenciaResponse frecuencia,
			@Schema(description = "Practicas realizadas en el caso")
			List<EvolucionDelCasoTratamientoResponse> tratamientos) {
	}

	@Schema(description = "Un valor de una serie, con la sesion de donde sale")
	public record EvolucionDelCasoPuntoResponse(long sesionId, LocalDate fecha, BigDecimal valor,
			@Schema(description = "Zona del dolor, solo en la serie de dolor") String zona) {
	}

	@Schema(description = "Serie de valores con su primer valor, el ultimo y la diferencia")
	public record EvolucionDelCasoSerieResponse(
			List<EvolucionDelCasoPuntoResponse> puntos,
			BigDecimal primero,
			BigDecimal ultimo,
			@Schema(description = "Ultimo menos primero. Ausente si no corresponde restar")
			BigDecimal diferencia) {

		static EvolucionDelCasoSerieResponse de(AnalisisDeEvolucion.Serie serie) {
			return new EvolucionDelCasoSerieResponse(
					serie.puntos().stream()
							.map(p -> new EvolucionDelCasoPuntoResponse(p.sesionId(), p.fecha(), p.valor(), p.zona()))
							.toList(),
					serie.primero(), serie.ultimo(), serie.diferencia());
		}
	}

	@Schema(description = "Serie de una medida por definicion y lado")
	public record EvolucionDelCasoMedicionResponse(
			long definicionId,
			String codigo,
			String nombre,
			@Schema(description = "Ausente si la unidad cambio dentro de la serie")
			String unidad,
			@Schema(description = "IZQUIERDA, DERECHA o NO_APLICA")
			String lateralidad,
			@Schema(description = "SUBE o BAJA. Ausente si la definicion no lo declara")
			String mejoraCuando,
			@Schema(description = "La unidad cambio entre sesiones: no se resta ni se evalua")
			boolean unidadesDifieren,
			EvolucionDelCasoSerieResponse serie) {

		static EvolucionDelCasoMedicionResponse de(AnalisisDeEvolucion.SerieDeMedicion s) {
			return new EvolucionDelCasoMedicionResponse(s.definicionId(), s.codigo(), s.nombre(),
					s.unidad(), s.lateralidad(), s.mejoraCuando(), s.unidadesDifieren(),
					EvolucionDelCasoSerieResponse.de(s.serie()));
		}
	}

	@Schema(description = "Cuantas veces aparece cada valor y los ultimos cinco en orden")
	public record EvolucionDelCasoConteoResponse(Map<String, Integer> porValor, List<String> ultimos) {

		static EvolucionDelCasoConteoResponse de(AnalisisDeEvolucion.Conteo conteo) {
			return new EvolucionDelCasoConteoResponse(conteo.porValor(), conteo.ultimos());
		}
	}

	@Schema(description = "Adherencia sobre las citas del caso. Las cancelaciones del profesional "
			+ "o de la institucion no cuentan")
	public record EvolucionDelCasoAdherenciaResponse(
			int citas,
			int inasistencias,
			@Schema(description = "Turnos AUSENTE") int ausentesEnTurno,
			@Schema(description = "Turnos cancelados por el paciente") int canceladosPorPaciente,
			@Schema(description = "Sesiones AUSENTE cuyo turno no estaba ya contado") int ausentesEnSesion,
			@Schema(description = "Inasistencias sobre citas. Ausente sin citas") Integer porcentaje) {

		static EvolucionDelCasoAdherenciaResponse de(AnalisisDeEvolucion.Adherencia a) {
			return new EvolucionDelCasoAdherenciaResponse(a.citas(), a.inasistencias(),
					a.ausentesEnTurno(), a.canceladosPorPaciente(), a.ausentesEnSesion(), a.porcentaje());
		}
	}

	@Schema(description = "Sesiones presentes por semana en la ventana, contra el plan")
	public record EvolucionDelCasoFrecuenciaResponse(
			int semanas,
			BigDecimal realPorSemana,
			@Schema(description = "Ausente si no hay plan o no declara frecuencia")
			Integer planificadaPorSemana) {
	}

	@Schema(description = "Una practica realizada en el caso")
	public record EvolucionDelCasoTratamientoResponse(
			Long practicaId, String practica, int sesiones, LocalDate ultima) {
	}

	@Schema(description = "Plan vigente del caso con su avance")
	public record EvolucionDelCasoPlanResponse(
			long planId,
			@Schema(description = "ACTIVO o SUSPENDIDO") String estado,
			int versionVigente,
			String objetivos,
			Integer frecuenciaSemanal,
			Integer duracionSemanas,
			boolean completo,
			List<EvolucionDelCasoItemDePlanResponse> items) {

		static EvolucionDelCasoPlanResponse de(PlanVigenteSnapshot plan) {
			if (plan == null) {
				return null;
			}
			return new EvolucionDelCasoPlanResponse(plan.planId(), plan.estado(), plan.versionVigente(),
					plan.objetivos(), plan.frecuenciaSemanal(), plan.duracionSemanas(), plan.completo(),
					plan.items().stream()
							.map(i -> new EvolucionDelCasoItemDePlanResponse(i.ofertaId(), i.ofertaNombre(),
									i.planificadas(), i.realizadas(), i.canceladas(), i.completo()))
							.toList());
		}
	}

	@Schema(description = "Avance de una oferta del plan")
	public record EvolucionDelCasoItemDePlanResponse(
			long ofertaId, String ofertaNombre, int planificadas, int realizadas, int canceladas,
			boolean completo) {
	}
}
