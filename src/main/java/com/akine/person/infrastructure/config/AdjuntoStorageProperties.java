package com.akine.person.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracion del almacenamiento de adjuntos administrativos (M25).
 *
 * <p><b>{@code baseDir} SI tiene default, al reves que el secreto del JWT.</b> No es una
 * inconsistencia: un default comodo es peligroso cuando su ausencia oculta un agujero de
 * seguridad —una firma con secreto conocido, un relay ajeno—, y aca la ausencia no oculta nada.
 * Un despliegue que se olvide de definirlo escribe en {@code ./var/adjuntos} <b>del propio
 * directorio de trabajo del proceso</b>: es visible, es local, y el primer redeploy con volumen
 * efimero lo hace evidente. Obligar a definirlo, en cambio, impediria arrancar la aplicacion en
 * cualquier maquina de desarrollo por una etapa que la mayoria de las sesiones no toca.
 *
 * @param baseDir  raiz del almacenamiento local. El adaptador crea los subdirectorios que
 *                 necesita y NUNCA compone la ruta con datos de origen externo
 * @param maxBytes tope de un archivo. El default de 10 MB cubre un PDF escaneado y una foto de
 *                 telefono con holgura. <b>Tiene que quedar por DEBAJO de
 *                 {@code spring.servlet.multipart.max-file-size}</b>: si el tope del contenedor
 *                 fuera el mas chico, el rechazo llegaria como error de plataforma y el operador
 *                 recibiria un 500 en vez del 400 que explica que su archivo pesa demasiado
 */
@ConfigurationProperties(prefix = "akine.person.adjuntos")
public record AdjuntoStorageProperties(
		@DefaultValue("./var/adjuntos") String baseDir,
		@DefaultValue("10485760") long maxBytes) {
}
