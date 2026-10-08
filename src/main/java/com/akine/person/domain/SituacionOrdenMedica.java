package com.akine.person.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * El estado de una orden medica, DERIVADO de su vigencia y de las autorizaciones que la usan
 * (AKINE B-4, M17).
 *
 * <p><b>No se guarda.</b> La orden no tiene ningun acto propio que lo mueva: lo mueven el reloj
 * (vence), el financiador (aprueba o rechaza las autorizaciones que la respaldan) y las sesiones
 * (consumen). Materializarlo exigiria que cada una de esas cosas escriba en la orden, y un camino
 * que se olvida deja una orden "autorizada" que ya se cumplio. Mismo criterio que
 * {@link EstadoAutorizacion}, que tampoco guarda VENCIDA ni AGOTADA.
 *
 * <p>No reemplaza al ciclo de vida ({@code ACTIVA}/{@code INACTIVA}) que la orden ya publica:
 * lo complementa. Precedencia, de la primera que aplica:
 *
 * <pre>
 *   ANULADA            dada de baja
 *   CUMPLIDA           las sesiones prescriptas ya se consumieron en autorizaciones aprobadas
 *   VENCIDA            paso su ultimo dia de vigencia
 *   EN_CURSO           hay consumo, todavia no se cumplio
 *   AUTORIZADA         alguna autorizacion activa APROBADA, sin consumo
 *   EN_TRAMITE         alguna autorizacion activa PENDIENTE u OBSERVADA
 *   RECHAZADA          todas las autorizaciones activas fueron RECHAZADA
 *   SIN_AUTORIZACION   vigente y sin autorizaciones activas que la usen
 * </pre>
 *
 * <p>SIN_AUTORIZACION no es un defecto: una orden de una prestacion que no exige autorizacion
 * (RN-M17-005/006) se queda ahi toda su vida.
 */
public enum SituacionOrdenMedica {

	ANULADA,
	CUMPLIDA,
	VENCIDA,
	EN_CURSO,
	AUTORIZADA,
	EN_TRAMITE,
	RECHAZADA,
	SIN_AUTORIZACION;

	/** Sesiones consumidas por la orden: lo consumido en sus autorizaciones activas y APROBADAS. */
	public static int sesionesConsumidas(OrdenMedica orden, Collection<Autorizacion> todas) {
		return deLaOrden(orden, todas).stream()
				.filter(autorizacion -> autorizacion.getEstado() == EstadoAutorizacion.APROBADA)
				.mapToInt(Autorizacion::getCantidadConsumida)
				.sum();
	}

	/**
	 * La situacion de la orden ese dia.
	 *
	 * @param todas autorizaciones del paciente; se usan solo las activas que apuntan a esta orden
	 */
	public static SituacionOrdenMedica de(
			OrdenMedica orden, Collection<Autorizacion> todas, LocalDate fecha) {

		if (!orden.isActive()) {
			return ANULADA;
		}
		List<Autorizacion> propias = deLaOrden(orden, todas);
		int consumidas = sesionesConsumidas(orden, propias);
		Integer prescriptas = orden.getSesionesPrescriptas();
		if (prescriptas != null && consumidas >= prescriptas) {
			return CUMPLIDA;
		}
		if (orden.getVigenciaHasta() != null && fecha.isAfter(orden.getVigenciaHasta())) {
			return VENCIDA;
		}
		if (consumidas > 0) {
			return EN_CURSO;
		}
		if (propias.isEmpty()) {
			return SIN_AUTORIZACION;
		}
		if (alguna(propias, EstadoAutorizacion.APROBADA)) {
			return AUTORIZADA;
		}
		if (alguna(propias, EstadoAutorizacion.PENDIENTE)
				|| alguna(propias, EstadoAutorizacion.OBSERVADA)) {
			return EN_TRAMITE;
		}
		return RECHAZADA;
	}

	private static List<Autorizacion> deLaOrden(OrdenMedica orden, Collection<Autorizacion> todas) {
		return todas.stream()
				.filter(Autorizacion::isActive)
				// Sin id todavia no la puede usar nadie; y sin el guard, null == null juntaria las
				// autorizaciones que no declaran orden.
				.filter(autorizacion -> orden.getId() != null
						&& Objects.equals(orden.getId(), autorizacion.getOrdenMedicaId()))
				.toList();
	}

	private static boolean alguna(List<Autorizacion> autorizaciones, EstadoAutorizacion estado) {
		return autorizaciones.stream().anyMatch(autorizacion -> autorizacion.getEstado() == estado);
	}
}
