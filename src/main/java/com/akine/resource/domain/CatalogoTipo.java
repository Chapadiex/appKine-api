package com.akine.resource.domain;

import java.util.Locale;

/**
 * Los tres conceptos administrables del catalogo clinico (M06).
 *
 * <p>Existe porque las tres rutas de administracion son la misma operacion sobre tres tablas:
 * publicar tres arboles de endpoints identicos habria triplicado el contrato sin agregar
 * ninguna capacidad. El tipo viaja como segmento de ruta en plural
 * —{@code /catalogos/especialidades}— y {@link #desdeRuta(String)} lo resuelve.
 *
 * <p><b>Por que el segmento es plural y el enum singular.</b> El plural es la convencion de
 * las colecciones REST del resto de la API ({@code /organizations}, {@code /espacios}); el
 * singular es como se llama el concepto. Traducir en un solo lugar es mas barato que renombrar
 * el dominio para que coincida con la URL.
 */
public enum CatalogoTipo {

	ESPECIALIDAD("especialidades"),

	PRACTICA("practicas"),

	NOMENCLADOR("nomencladores");

	private final String segmento;

	CatalogoTipo(String segmento) {
		this.segmento = segmento;
	}

	/** Segmento de ruta que representa a la coleccion de este tipo. */
	public String segmento() {
		return segmento;
	}

	/**
	 * Resuelve el segmento de ruta al tipo, sin distinguir mayusculas.
	 *
	 * @throws IllegalArgumentException si el segmento no pertenece al catalogo. El manejador de
	 *                                  errores lo traduce a 400: es un valor invalido del
	 *                                  cliente, no un recurso ajeno, asi que no corresponde 404
	 */
	public static CatalogoTipo desdeRuta(String segmento) {
		if (segmento != null) {
			String normalizado = segmento.strip().toLowerCase(Locale.ROOT);
			for (CatalogoTipo tipo : values()) {
				if (tipo.segmento.equals(normalizado)) {
					return tipo;
				}
			}
		}
		throw new IllegalArgumentException(
				"Tipo de catalogo desconocido: se espera especialidades, practicas o "
						+ "nomencladores");
	}
}
