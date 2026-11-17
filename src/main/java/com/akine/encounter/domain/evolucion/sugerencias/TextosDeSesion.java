package com.akine.encounter.domain.evolucion.sugerencias;

/** Los textos libres de una sesion que viajan al modelo (los de las ultimas 3). */
public record TextosDeSesion(String respuestaTratamiento, String limitacionFuncional, String notaDeCierre) {
}
