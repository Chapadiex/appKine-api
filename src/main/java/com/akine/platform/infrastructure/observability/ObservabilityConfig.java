package com.akine.platform.infrastructure.observability;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import jakarta.servlet.DispatcherType;

/**
 * Cableado del id de correlacion (G-4).
 *
 * <p>El resto de la observabilidad —log JSON, metricas Prometheus, trazas OTLP— es
 * autoconfiguracion de Spring Boot gobernada por {@code application.yml}; lo unico que necesita
 * codigo es el {@link CorrelationIdFilter} y la cadena que protege el scrape
 * ({@link MetricsScrapeSecurityConfig}). Ver {@code docs/observabilidad.md}.
 */
@Configuration
public class ObservabilityConfig {

	/**
	 * Orden: inmediatamente despues de {@code ServerHttpObservationFilter}, que Spring Boot
	 * registra en {@code HIGHEST_PRECEDENCE + 1} (verificado en
	 * {@code WebMvcObservationAutoConfiguration} de Boot 4.1.1). Despues, porque el filtro
	 * agrega el request id al span que ese filtro abre; y muy antes de la cadena de Spring
	 * Security ({@code -100}), para que un 401, un 403 o un 429 tambien lleven el header y su
	 * log tambien lleve el id.
	 */
	static final int ORDEN = Ordered.HIGHEST_PRECEDENCE + 2;

	@Bean
	public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
		FilterRegistrationBean<CorrelationIdFilter> registro =
				new FilterRegistrationBean<>(new CorrelationIdFilter());
		registro.setOrder(ORDEN);
		registro.setDispatcherTypes(
				DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC);
		return registro;
	}
}
