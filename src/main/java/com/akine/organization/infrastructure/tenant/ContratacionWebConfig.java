package com.akine.organization.infrastructure.tenant;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registra {@link ServicioContratadoInterceptor} en toda la API. */
@Configuration
class ContratacionWebConfig implements WebMvcConfigurer {

	private final ServicioContratadoInterceptor interceptor;

	ContratacionWebConfig(ServicioContratadoInterceptor interceptor) {
		this.interceptor = interceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(interceptor).addPathPatterns("/api/**");
	}
}
