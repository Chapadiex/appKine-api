package com.akine.platform.infrastructure.pedido;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import jakarta.servlet.DispatcherType;

/** Registra {@link AlcanceDelPedidoFilter} antes de la cadena de Spring Security ({@code -100}). */
@Configuration
public class AlcanceDelPedidoConfig {

	/** Despues del id de correlacion ({@code HIGHEST_PRECEDENCE + 2}), lejos antes de seguridad. */
	static final int ORDEN = Ordered.HIGHEST_PRECEDENCE + 3;

	@Bean
	public FilterRegistrationBean<AlcanceDelPedidoFilter> alcanceDelPedidoFilterRegistration() {
		FilterRegistrationBean<AlcanceDelPedidoFilter> registro =
				new FilterRegistrationBean<>(new AlcanceDelPedidoFilter());
		registro.setOrder(ORDEN);
		registro.setDispatcherTypes(DispatcherType.REQUEST);
		return registro;
	}
}
