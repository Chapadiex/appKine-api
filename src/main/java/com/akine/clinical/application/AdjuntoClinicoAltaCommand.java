package com.akine.clinical.application;

import com.akine.clinical.domain.CategoriaAdjuntoClinico;

/**
 * Una subida de adjunto clinico, en la forma que {@code application} entiende.
 *
 * <p><b>Trae {@code byte[]} y no un {@code MultipartFile}.</b> No es purismo: ArchUnit prohibe
 * que {@code application} importe {@code org.springframework.web..}, y la regla existe porque las
 * reglas de negocio tienen que poder ejecutarse desde un job o un consumidor de eventos y no solo
 * desde un controller. Traducir la parte multipart a bytes es trabajo de {@code api}.
 *
 * <p>{@code contentTypeDeclarado} viaja pero <b>no se usa para decidir nada</b>. Esta para poder
 * dejarlo en el log de diagnostico cuando un archivo se rechaza —"dijo PDF y era otra cosa" es
 * informacion util— y para que quede claro, leyendo el codigo, que se lo recibio y se lo
 * descarto. El tipo que se guarda es el que detecta {@code TipoDeContenidoClinico} de los bytes.
 *
 * @param entradaClinicaId entrada que el documento respalda, o {@code null}. Si viene, el
 *                         servicio verifica que sea de ESTA historia antes de insertar: la base
 *                         no lo puede exigir con una FK simple
 * @param nombreArchivo    nombre original. Se guarda para devolverlo al descargar y NUNCA
 *                         participa de la ruta en disco
 */
public record AdjuntoClinicoAltaCommand(
		CategoriaAdjuntoClinico categoria,
		Long entradaClinicaId,
		String titulo,
		String nombreArchivo,
		String contentTypeDeclarado,
		byte[] contenido) {
}
