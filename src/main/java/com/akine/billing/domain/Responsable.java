package com.akine.billing.domain;

/**
 * Quien debe.
 *
 * <p><b>Hoy solo existe PACIENTE.</b> DP-10 fijo cobertura PARTICULAR unicamente y dejo afuera
 * financiadores (03.03) y convenios (03.05). {@link #FINANCIADOR} se declara igual porque la regla
 * del recorte es cortar alcance y no modelo: cuando esas etapas lleguen, la obligacion mixta
 * —parte el paciente, parte la obra social— es DOS filas con el mismo {@code sesion_id} y
 * responsables distintos, que es exactamente lo que el unique de V36 permite.
 */
public enum Responsable {
	PACIENTE,
	FINANCIADOR
}
