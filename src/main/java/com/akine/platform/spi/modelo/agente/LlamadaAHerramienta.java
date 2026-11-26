package com.akine.platform.spi.modelo.agente;

/** Una herramienta que el modelo pidio ejecutar, con su entrada en JSON. */
public record LlamadaAHerramienta(String id, String nombre, String entradaJson) {
}
