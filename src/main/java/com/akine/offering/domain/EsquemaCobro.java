package com.akine.offering.domain;

/**
 * Esquema economico declarado por el centro para una {@link OfertaServicioConsultorio}
 * (RN-M27-006).
 *
 * <h2>Por que NO es un enum</h2>
 *
 * <p>Todos los demas clasificadores de este modulo ({@link Naturaleza}, {@link Modalidad}) son
 * enums porque replican un {@code CHECK ... IN (...)} de la migracion V24. Este NO tiene CHECK:
 * la columna {@code esquema_cobro} es {@code VARCHAR(32) NULL} <b>sin lista cerrada</b>, y la
 * cabecera de V24 dice por que — "es un dato DECLARADO, NO RESUELTO... fijar el enum ahora seria
 * adivinar el vocabulario de un modulo que nadie escribio". Los modulos que definirian ese
 * vocabulario (M15 convenios, M16 aranceles, M18 facturacion) no existen todavia. Convertir esto
 * en un enum hoy seria inventar una lista cerrada que V24 evito a proposito, y el dia que
 * M16/M18 aparezcan con su propio vocabulario, esta lista quedaria mal y migrar un enum ya usado
 * en produccion es mas caro que no haberlo cerrado nunca.
 *
 * <p><b>No se interpreta.</b> Ningun codigo de este modulo ni de ningun otro lee este valor para
 * decidir nada: se guarda y se muestra tal cual. El unico contrato es sintactico —cabe en 32
 * caracteres, columna de la migracion V24— y por eso esta clase valida largo y nada mas.
 */
public record EsquemaCobro(String valor) {

	/** Replica el {@code VARCHAR(32)} de {@code oferta_servicio_consultorio.esquema_cobro}. */
	public static final int LARGO_MAXIMO = 32;

	public EsquemaCobro {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException("El esquema de cobro declarado no puede ser vacio");
		}
		valor = valor.strip();
		if (valor.length() > LARGO_MAXIMO) {
			throw new IllegalArgumentException(
					"El esquema de cobro declarado no puede superar los " + LARGO_MAXIMO
							+ " caracteres");
		}
	}
}
