package com.akine.knowledge.application;

import java.util.List;

/** Pregunta nueva mas el historial que manda el cliente (no confiable). */
public record ConsultaPedido(String pregunta, List<TurnoDeConsulta> historial) {
}
