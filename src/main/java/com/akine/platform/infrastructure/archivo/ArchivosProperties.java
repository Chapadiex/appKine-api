package com.akine.platform.infrastructure.archivo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Raiz del almacen compartido de archivos ({@code akine.archivos.*}). */
@ConfigurationProperties(prefix = "akine.archivos")
public record ArchivosProperties(@DefaultValue("./var/archivos") String baseDir) {
}
