package com.akine.person.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuracion del modulo {@code person}.
 *
 * <p>Existe solo para registrar {@link AdjuntoStorageProperties}: este proyecto no usa
 * {@code @ConfigurationPropertiesScan} —el escaneo global registraria las properties de todos los
 * modulos aunque el contexto de un test cargue uno solo— y cada modulo habilita las suyas, mismo
 * criterio que {@code IdentityConfig} y {@code NotificationConfig}.
 */
@Configuration
@EnableConfigurationProperties(AdjuntoStorageProperties.class)
public class PersonConfig {
}
