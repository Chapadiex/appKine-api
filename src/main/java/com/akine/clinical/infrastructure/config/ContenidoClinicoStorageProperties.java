package com.akine.clinical.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracion del almacenamiento de adjuntos CLINICOS (M09 / M25).
 *
 * <h2>Por que es otra raiz y no la misma de los adjuntos administrativos</h2>
 *
 * <p>Porque separar los binarios clinicos de los administrativos en el sistema de archivos es un
 * <b>control real</b> y no una consecuencia del codigo: habilita otro volumen, otro cifrado en
 * reposo, otra politica de backup y otra de retencion, sin tocar una linea. Compartir la raiz
 * haria que cualquiera de esas decisiones se aplique a los dos o a ninguno.
 *
 * <p>El default {@code ./var/adjuntos-clinicos} es hermano del de {@code person} y no un
 * subdirectorio suyo, a proposito: un subdirectorio se lleva puesta la separacion en cuanto
 * alguien monta un volumen sobre el padre.
 *
 * <p><b>{@code baseDir} SI tiene default</b>, misma decision que {@code AdjuntoStorageProperties}
 * y por el mismo motivo: un default comodo es peligroso cuando su ausencia oculta un agujero de
 * seguridad, y aca no oculta nada — un despliegue que se olvide de definirlo escribe en el
 * directorio de trabajo del proceso, que es visible y que el primer redeploy con volumen efimero
 * hace evidente.
 *
 * @param baseDir  raiz del almacenamiento clinico local. El adaptador crea los subdirectorios que
 *                 necesita y NUNCA compone la ruta con datos de origen externo (RN-M25-002)
 * @param maxBytes tope de un archivo. <b>Tiene que quedar por DEBAJO de
 *                 {@code spring.servlet.multipart.max-file-size}</b>, que hoy vale 15 MB: si el
 *                 tope del contenedor fuera el mas chico, el rechazo llegaria como
 *                 {@code MaxUploadSizeExceededException} —un error de plataforma— y el
 *                 profesional recibiria un 500 generico en vez del 400 que explica que su archivo
 *                 pesa demasiado. El default son los mismos 10 MB del administrativo, y no porque
 *                 un estudio pese lo mismo que la foto de una credencial: subir de ahi exige
 *                 subir primero el tope del contenedor, que es una decision de despliegue que
 *                 afecta a los dos modulos y no se toma desde esta etapa
 */
@ConfigurationProperties(prefix = "akine.clinical.adjuntos")
public record ContenidoClinicoStorageProperties(
		@DefaultValue("./var/adjuntos-clinicos") String baseDir,
		@DefaultValue("10485760") long maxBytes) {
}
