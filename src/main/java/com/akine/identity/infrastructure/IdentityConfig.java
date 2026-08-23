package com.akine.identity.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Cableado del modulo {@code identity}.
 *
 * <p>Hoy solo habilita el binding de {@link IdentityProperties}: los adaptadores del modulo son
 * {@code @Component} y Spring los descubre solo. Existe igual porque es el lugar donde va a
 * declararse el cableado que no puede resolverse por anotacion —la eleccion de un emisor de
 * tokens, por ejemplo— y tenerlo desde ahora evita que aparezca disperso por el modulo.
 */
@Configuration
@EnableConfigurationProperties(IdentityProperties.class)
public class IdentityConfig {
}
