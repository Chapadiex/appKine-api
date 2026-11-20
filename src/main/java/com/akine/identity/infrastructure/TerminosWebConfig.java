package com.akine.identity.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registra {@link TerminosPendientesInterceptor} sobre toda la API. */
@Configuration
class TerminosWebConfig implements WebMvcConfigurer {

	private final TerminosPendientesInterceptor interceptor;

	TerminosWebConfig(TerminosPendientesInterceptor interceptor) {
		this.interceptor = interceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(interceptor).addPathPatterns("/api/**");
	}
}
