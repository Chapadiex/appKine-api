package com.akine.platform.spi.archivo;

import java.util.Optional;

/**
 * Almacen de binarios compartido por los modulos que lo necesiten.
 *
 * <p>Es la extraccion prevista en el Javadoc de {@code ContenidoClinicoStoragePort}: el tercer
 * consumidor (la biblioteca) lo usa, y {@code person} y {@code clinical} siguen con los suyos
 * (migrarlos es deuda declarada). Cada consumidor usa su propio {@code espacio}, que es una
 * carpeta distinta bajo la raiz.
 *
 * <p>{@code espacio} y {@code clave} los genera el servidor, nunca el cliente: la implementacion
 * igual los valida y falla ruidosamente si no tienen la forma esperada (IllegalArgumentException).
 */
public interface AlmacenDeArchivos {

	/** Escribe el contenido de forma atomica. Si la clave existe, la reemplaza. */
	void guardar(String espacio, String clave, byte[] contenido);

	/** Vacio si no existe (o no se pudo leer). */
	Optional<byte[]> leer(String espacio, String clave);

	/** Borra el archivo; si no existe no hace nada. */
	void borrar(String espacio, String clave);
}
