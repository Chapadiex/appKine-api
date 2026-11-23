package com.akine.platform.spi.modelo.agente;

/** Una herramienta que el modelo puede pedir; el esquema es JSON Schema serializado. */
public record DefinicionDeHerramienta(String nombre, String descripcion, String esquemaJson) {
}
