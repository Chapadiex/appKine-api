package com.akine.identity.infrastructure;

import com.akine.identity.domain.SessionSettings;
import com.akine.identity.domain.port.IdentityClock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

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

	/**
	 * Reglas de vigencia de la sesion, traducidas de la configuracion al dominio.
	 *
	 * <p>La traduccion ocurre aca y no en el servicio porque {@code application} no puede
	 * depender de {@code infrastructure} (ArchUnit lo verifica): {@link SessionSettings} vive en
	 * {@code domain} y esta clase es la unica que conoce {@link IdentityProperties}.
	 */
	@Bean
	public SessionSettings sessionSettings(IdentityProperties properties) {
		return new SessionSettings(properties.getSession().getRefreshTtl());
	}

	/**
	 * El reloj del modulo.
	 *
	 * <p>Se declara como bean para que un test pueda sustituirlo por uno fijo. La aplicacion usa
	 * el del sistema, en UTC: las fechas se persisten como instantes y la zona local solo
	 * aparece en reglas de negocio que aca no existen.
	 */
	@Bean
	public IdentityClock identityClock() {
		return Instant::now;
	}
}
