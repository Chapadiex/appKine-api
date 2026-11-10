package com.akine.platform.infrastructure.archivo;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registra las propiedades del almacen compartido de archivos. */
@Configuration
@EnableConfigurationProperties(ArchivosProperties.class)
public class ArchivosConfig {
}
