package com.akine.knowledge.application;

import com.akine.knowledge.spi.CaminoBusqueda;

import java.util.List;

/**
 * @param camino {@code VECTOR} si el vector aporto candidatos; {@code PALABRAS} si solo hubo texto completo
 * @param pasajes por RRF descendente, acotados al mayor de {@code pasajes-breve} y {@code pasajes-profundo}
 * @param hayEvidencia hay material suficiente para responder (ver {@link BusquedaHibrida})
 */
public record ResultadoHibrido(CaminoBusqueda camino, List<PasajeFusionado> pasajes, boolean hayEvidencia) {
}
