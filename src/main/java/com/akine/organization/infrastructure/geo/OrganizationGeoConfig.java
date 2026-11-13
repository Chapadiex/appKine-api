package com.akine.organization.infrastructure.geo;

import com.akine.organization.application.Geocodificador;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Cablea el {@link Geocodificador} segun {@code akine.geo.proveedor} (V140).
 *
 * <p>Registra {@link GeoProperties} aca y no por escaneo global, igual que {@code ClinicalConfig}
 * y las demas: cada modulo habilita las suyas para que el contexto de un test cargue solo lo que
 * necesita.
 */
@Configuration
@EnableConfigurationProperties(GeoProperties.class)
public class OrganizationGeoConfig {

	/**
	 * Nominatim, el default. {@code matchIfMissing}: sin la propiedad, funciona de entrada.
	 *
	 * <p>Los timeouts van en el {@code requestFactory} del builder y no dentro del adaptador: asi un
	 * test puede atarle un {@code MockRestServiceServer} al builder antes de construirlo.
	 */
	@Bean
	@ConditionalOnProperty(name = "akine.geo.proveedor", havingValue = "NOMINATIM", matchIfMissing = true)
	public Geocodificador nominatimGeocodificador(GeoProperties properties) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
		requestFactory.setReadTimeout(properties.timeout());
		return new NominatimGeocodificador(RestClient.builder().requestFactory(requestFactory), properties);
	}

	/** Apagado a proposito: la busqueda responde 503 y la sede se ubica a mano. */
	@Bean
	@ConditionalOnProperty(name = "akine.geo.proveedor", havingValue = "NINGUNO")
	public Geocodificador geocodificadorApagado() {
		return new GeocodificadorApagado();
	}
}
