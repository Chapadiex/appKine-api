package com.akine.encounter.application;

import com.akine.encounter.domain.evolucion.Hallazgo;

import java.util.Map;

/**
 * El texto de cada hallazgo, por plantilla (RF-M31-002). Dice <b>que se observo</b>; nunca indica
 * conducta. Los numeros salen de los valores que calculo el dominio, no se recalculan aca.
 */
final class TextosDeHallazgo {

	private TextosDeHallazgo() {
	}

	static String de(Hallazgo hallazgo) {
		Map<String, String> v = hallazgo.valores();
		return switch (hallazgo.codigo()) {
			case DATOS_INSUFICIENTES -> "Hay " + v.get("presentes") + " sesiones con asistencia; "
					+ "hacen falta al menos " + v.get("minimo") + " para evaluar alertas.";
			case DOLOR_SIN_MEJORA -> "El dolor pasó de " + v.get("desde") + " a " + v.get("hasta")
					+ " en las últimas " + v.get("sesiones") + " sesiones y sigue alto.";
			case DOLOR_EN_AUMENTO -> "El dolor subió de " + v.get("desde") + " a " + v.get("hasta")
					+ " en las últimas " + v.get("sesiones") + " sesiones.";
			case EVOLUCION_DESFAVORABLE -> "La evolución se registró como "
					+ evolucion(v.get("valor")) + " en las últimas " + v.get("seguidas")
					+ " sesiones seguidas.";
			case MALA_TOLERANCIA -> "La tolerancia fue mala en las últimas " + v.get("seguidas")
					+ " sesiones seguidas.";
			case MEDICION_SIN_CAMBIO -> medicion(v) + " no varió en las últimas " + v.get("sesiones")
					+ " sesiones (" + v.get("desde") + " a " + v.get("hasta") + unidad(v) + ").";
			case MEDICION_EMPEORA -> medicion(v) + " pasó de " + v.get("desde") + " a "
					+ v.get("hasta") + unidad(v) + " en las últimas " + v.get("sesiones")
					+ " sesiones, en sentido contrario al de mejora.";
			case BAJA_ADHERENCIA -> "Faltó a " + v.get("inasistencias") + " de las últimas "
					+ v.get("citas") + " citas (" + v.get("porcentaje") + " %).";
			case FRECUENCIA_BAJO_PLAN -> "En las últimas " + v.get("semanas") + " semanas asistió "
					+ v.get("real") + " veces por semana; el plan prevé " + v.get("planificada") + ".";
			case PLAN_COMPLETO_ABIERTO -> "Todas las prácticas del plan alcanzaron la cantidad "
					+ "planificada y el plan sigue activo.";
			case PLAN_EXCEDIDO_EN_TIEMPO -> "El plan preveía " + v.get("semanas")
					+ " semanas y lleva " + v.get("semanasTranscurridas") + " desde que se activó.";
			case SIN_PLAN_ACTIVO -> "El caso está activo y no tiene un plan de tratamiento activo.";
			case CONDUCTA_SIN_SEGUIR -> "La sesión del " + v.get("fecha") + " cerró con "
					+ conducta(v.get("conducta")) + " y después hubo " + v.get("despues")
					+ " sesiones sin cambios en el plan.";
			case SIN_MEDICIONES -> "Las últimas " + v.get("sesiones")
					+ " sesiones no registran dolor ni mediciones.";
		};
	}

	private static String medicion(Map<String, String> v) {
		String lado = v.get("lateralidad");
		String nombre = v.get("medicion");
		return switch (lado == null ? "NO_APLICA" : lado) {
			case "IZQUIERDA" -> nombre + " (izquierda)";
			case "DERECHA" -> nombre + " (derecha)";
			default -> nombre;
		};
	}

	private static String unidad(Map<String, String> v) {
		String unidad = v.get("unidad");
		return unidad == null || unidad.isBlank() ? "" : " " + unidad;
	}

	private static String evolucion(String valor) {
		return "PEOR".equals(valor) ? "peor" : "igual";
	}

	private static String conducta(String valor) {
		return switch (valor) {
			case "ALTA" -> "indicación de alta";
			case "DERIVA" -> "indicación de derivación";
			default -> "indicación de reevaluar";
		};
	}
}
