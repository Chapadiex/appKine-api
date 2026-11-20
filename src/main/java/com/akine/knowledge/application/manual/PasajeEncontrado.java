package com.akine.knowledge.application.manual;

import com.akine.knowledge.domain.manual.FichaDelManual;
import com.akine.knowledge.domain.manual.PasajeDelManual;

/** Un pasaje que encontro la busqueda, con su ficha y el puntaje de la fusion. */
public record PasajeEncontrado(PasajeDelManual pasaje, FichaDelManual ficha, double puntaje) {
}
