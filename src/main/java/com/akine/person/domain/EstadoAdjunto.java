package com.akine.person.domain;

/**
 * Si el contenido de un adjunto se puede entregar hoy.
 *
 * <p>Existe porque la metadata y el binario viven en almacenamientos distintos (ver la cabecera
 * de {@code V40}) y por lo tanto pueden desincronizarse. La respuesta correcta a "la fila esta y
 * el archivo no" es <b>409, no 404</b>: el adjunto existe, se sigue listando y su historico
 * resuelve; lo que no se puede es descargarlo. Un 404 le diria al operador que borre y vuelva a
 * subir un documento que el sistema todavia afirma tener.
 */
public enum EstadoAdjunto {

	/** El contenido esta en el almacenamiento y se puede descargar. */
	DISPONIBLE,

	/** El almacenamiento no tiene el contenido. La metadata sobrevive; la descarga da 409. */
	NO_DISPONIBLE
}
