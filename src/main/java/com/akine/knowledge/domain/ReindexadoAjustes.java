package com.akine.knowledge.domain;

/**
 * Configuracion del reindexado que necesita la capa de aplicacion.
 *
 * @param lote pedazos por lote ({@code akine.biblioteca.worker.reindex-lote})
 */
public record ReindexadoAjustes(int lote) {
}
