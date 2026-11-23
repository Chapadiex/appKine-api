package com.akine.organization.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Donde esta un centro en la linea de tiempo de su periodo pagado (19/11/2026,
 * docs/diseno/AKINE-cobro-mensual.md).
 *
 * <p>{@code V} es {@code subscription.pagado_hasta}. Se calcula al leer, como la prueba vencida:
 * ninguna tarea mueve el estado, el reloj alcanza. Es el UNICO lugar que sabe cuanto dura la
 * gracia y cuando se cierra el acceso; el estado efectivo de la suscripcion, las respuestas y los
 * avisos lo toman de aca.
 *
 * <table>
 *   <caption>Linea de tiempo</caption>
 *   <tr><td>sin {@code V}</td><td>{@link #SIN_CONTROL}</td></tr>
 *   <tr><td>antes de {@code V - 15 d}</td><td>{@link #AL_DIA}</td></tr>
 *   <tr><td>{@code [V - 15 d, V)}</td><td>{@link #POR_VENCER}</td></tr>
 *   <tr><td>{@code [V, V + 3 d)}</td><td>{@link #EN_GRACIA}</td></tr>
 *   <tr><td>{@code [V + 3 d, V + 18 d)}</td><td>{@link #SOLO_LECTURA}</td></tr>
 *   <tr><td>desde {@code V + 18 d}</td><td>{@link #SIN_ACCESO}</td></tr>
 * </table>
 */
public enum SituacionDeCobro {

	/** Sin {@code pagado_hasta}: el centro no esta bajo control de cobro (los anteriores al 19/11/2026). */
	SIN_CONTROL,

	/** Pagado y lejos del vencimiento. */
	AL_DIA,

	/** Vence en {@link #AVISO} o menos: todo sigue andando, con cartel. */
	POR_VENCER,

	/** Vencido hace menos de {@link #GRACIA}: todo sigue andando, con cartel. */
	EN_GRACIA,

	/** Pasada la gracia y antes del cierre: solo lectura, como una suspension. */
	SOLO_LECTURA,

	/** Desde el cierre: el contexto del centro se rechaza. */
	SIN_ACCESO;

	/** Desde cuanto antes del vencimiento se avisa. */
	public static final Duration AVISO = Duration.ofDays(15);

	/** Cuanto despues del vencimiento el centro sigue operando normalmente. */
	public static final Duration GRACIA = Duration.ofDays(3);

	/** Cuanto despues del vencimiento se cierra el acceso. */
	public static final Duration CIERRE = Duration.ofDays(18);

	/** La situacion de un centro pagado hasta {@code pagadoHasta}, en {@code ahora}. */
	public static SituacionDeCobro de(Instant pagadoHasta, Instant ahora) {
		if (pagadoHasta == null) {
			return SIN_CONTROL;
		}
		if (ahora.isBefore(pagadoHasta.minus(AVISO))) {
			return AL_DIA;
		}
		if (ahora.isBefore(pagadoHasta)) {
			return POR_VENCER;
		}
		if (ahora.isBefore(soloLecturaDesde(pagadoHasta))) {
			return EN_GRACIA;
		}
		if (ahora.isBefore(cierreEl(pagadoHasta))) {
			return SOLO_LECTURA;
		}
		return SIN_ACCESO;
	}

	/** Instante en que termina la gracia y el centro pasa a solo lectura; {@code null} sin control. */
	public static Instant soloLecturaDesde(Instant pagadoHasta) {
		return pagadoHasta == null ? null : pagadoHasta.plus(GRACIA);
	}

	/** Instante en que se cierra el acceso; {@code null} sin control. */
	public static Instant cierreEl(Instant pagadoHasta) {
		return pagadoHasta == null ? null : pagadoHasta.plus(CIERRE);
	}

	/** Si en esta situacion el centro ya no puede cargar nada. */
	public boolean soloLectura() {
		return this == SOLO_LECTURA || this == SIN_ACCESO;
	}
}
