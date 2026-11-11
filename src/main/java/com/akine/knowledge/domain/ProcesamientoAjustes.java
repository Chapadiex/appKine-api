package com.akine.knowledge.domain;

/**
 * Lo que el procesamiento de libros necesita de la configuracion.
 *
 * @param versionVigente version de la ingesta ({@code akine.biblioteca.version-procesamiento-vigente}):
 *     un libro LISTO con una version menor se reprocesa en segundo plano
 */
public record ProcesamientoAjustes(int versionVigente) {

	public ProcesamientoAjustes {
		if (versionVigente < 1) {
			throw new IllegalArgumentException("versionVigente debe ser al menos 1");
		}
	}
}
