package com.akine.offering.domain;

import java.util.Set;

/**
 * Esquema economico declarado por el centro para una {@link OfertaServicioConsultorio}
 * (RN-M27-006).
 *
 * <h2>Vocabulario cerrado desde V64</h2>
 *
 * <p>Hasta AKINE-08.06 la columna era texto libre ("dato declarado, no resuelto", V24). {@code V64}
 * cerro el vocabulario con {@code ck_oferta_esquema_cobro} —{@code POR_SESION}, {@code POR_CLASE},
 * {@code POR_PACK}, {@code POR_ABONO}— y agrego {@code momento_devengo} con
 * {@code ck_oferta_politica_devengo}, que exige que el esquema y el momento concuerden.
 *
 * <p><b>Lo que esto corrige.</b> Este record seguia aceptando cualquier texto y la entidad no
 * mapeaba {@code momento_devengo}, asi que declarar <i>cualquier</i> esquema —incluso uno valido
 * como {@code POR_SESION}— violaba el CHECK de V64 en el INSERT y la oferta terminaba en un 500.
 * Ahora el valor fuera de vocabulario es un 400 con mensaje, y el momento se deriva del esquema.
 *
 * <h2>{@code POR_CLASE} se devenga con la asistencia (U-39, decidida el 17/10/2026)</h2>
 *
 * <p>Es el unico esquema con dos momentos posibles en el CHECK de V64 —{@code ASISTENCIA} o
 * {@code INSCRIPCION}—. Hasta el 17/10/2026 declararlo era 400 porque elegir era la decision del
 * usuario que 08.03 delego (U-39). La decision fue <b>asistencia, sin no-show</b>: la deuda nace
 * cuando la persona estuvo ({@code PRESENTE} o {@code PRESENTE_TARDE}) y {@code devenga_no_show}
 * queda en su default {@code 0} (V64). El devengo lo hace {@code activity.DevengoPorClaseService}
 * (RF-M18-008).
 */
public record EsquemaCobro(String valor) {

	/** Replica el {@code VARCHAR(32)} de {@code oferta_servicio_consultorio.esquema_cobro}. */
	public static final int LARGO_MAXIMO = 32;

	public static final String POR_SESION = "POR_SESION";
	public static final String POR_CLASE = "POR_CLASE";
	public static final String POR_PACK = "POR_PACK";
	public static final String POR_ABONO = "POR_ABONO";

	private static final Set<String> VOCABULARIO = Set.of(POR_SESION, POR_CLASE, POR_PACK, POR_ABONO);

	public EsquemaCobro {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException("El esquema de cobro declarado no puede ser vacio");
		}
		valor = valor.strip();
		if (!VOCABULARIO.contains(valor)) {
			throw new IllegalArgumentException(
					"El esquema de cobro debe ser POR_SESION, POR_CLASE, POR_PACK o POR_ABONO");
		}
	}

	/**
	 * El {@code momento_devengo} que {@code ck_oferta_politica_devengo} exige para este esquema.
	 * {@code POR_SESION} y {@code POR_CLASE} devengan con la asistencia (la clase, por U-39);
	 * {@code POR_PACK} y {@code POR_ABONO}, con la venta.
	 */
	public String momentoDevengo() {
		return POR_SESION.equals(valor) || POR_CLASE.equals(valor) ? "ASISTENCIA" : "VENTA";
	}
}
