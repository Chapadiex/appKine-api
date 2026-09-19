package com.akine.clinical.application;

import com.akine.clinical.domain.AdjuntoClinico;

import java.time.Instant;

/**
 * Lo que sale de {@code application} sobre un adjunto clinico.
 *
 * <p><b>No lleva {@code storageKey} y eso es la mitad de RN-M25-002.</b> La clave de
 * almacenamiento es la unica pieza con la que alguien podria empezar a inferir como esta
 * organizado el disco clinico, y no hay ninguna razon por la que un cliente la necesite: para
 * descargar usa el id del adjunto, que ya se autoriza. La otra mitad de la regla es que el
 * endpoint de descarga tampoco redirige a una ruta interna.
 *
 * <p>El checksum SI viaja, y no es una fuga: es el hash de un archivo que quien lo pide ya puede
 * descargar entero. Sirve para que un cliente verifique integridad sin volver a bajar el
 * contenido, y para que el profesional entienda por que una segunda subida devolvio el mismo
 * adjunto.
 *
 * <p>Tampoco lleva contenido clinico. Un adjunto es un binario: lo unico que esta vista dice de
 * el es como esta clasificado y de donde cuelga — el timeline es un indice, no un visor, y esta
 * vista tampoco.
 */
public record AdjuntoClinicoView(
		long id,
		long historiaClinicaId,
		Long entradaClinicaId,
		Long consultorioId,
		String categoria,
		String titulo,
		String nombreArchivo,
		String contentType,
		long tamanoBytes,
		String checksumSha256,
		String estado,
		long subidoPor,
		Instant subidoEn,
		String estadoCicloDeVida,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	public static AdjuntoClinicoView de(AdjuntoClinico adjunto) {
		return new AdjuntoClinicoView(
				adjunto.getId(),
				adjunto.getHistoriaClinicaId(),
				adjunto.getEntradaClinicaId(),
				adjunto.getConsultorioId(),
				adjunto.getCategoria().name(),
				adjunto.getTitulo(),
				adjunto.getNombreArchivo(),
				adjunto.getContentType(),
				adjunto.getTamanoBytes(),
				adjunto.getChecksumSha256(),
				adjunto.getEstado().name(),
				adjunto.getSubidoPor(),
				adjunto.getSubidoEn(),
				adjunto.isActive() ? "ACTIVO" : "INACTIVO",
				adjunto.getDeletedAt(),
				adjunto.getDeactivationReason(),
				adjunto.getVersion());
	}
}
