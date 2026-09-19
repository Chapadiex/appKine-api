package com.akine.clinical.domain;

/**
 * Si el contenido de un adjunto clinico se puede entregar hoy.
 *
 * <p>Existe porque la metadata y el binario viven en almacenamientos distintos (ver la cabecera
 * de {@code V46}) y por lo tanto pueden desincronizarse. La respuesta correcta a "la fila esta y
 * el archivo no" es <b>409, no 404</b>: el adjunto existe, se sigue listando y su historico
 * resuelve; lo que no se puede es descargarlo.
 *
 * <p>En una historia clinica la diferencia pesa mas que en el padron: un 404 le diria al
 * profesional que el estudio no existe y lo empujaria a pedirselo de nuevo al paciente bajo una
 * historia que todavia afirma tenerlo.
 *
 * <p>Es un enum propio y no {@code person.domain.EstadoAdjunto}: son de modulos distintos y
 * ArchUnit rechaza importarlo. Mismo motivo que {@code MarcaTemporal} y {@code OperatingActor}.
 */
public enum EstadoAdjuntoClinico {

	/** El contenido esta en el almacenamiento clinico y se puede descargar. */
	DISPONIBLE,

	/** El almacenamiento no tiene el contenido. La metadata sobrevive; la descarga da 409. */
	NO_DISPONIBLE
}
