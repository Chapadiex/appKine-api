package com.akine.platform.infrastructure.tenant;

import java.time.Clock;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.akine.platform.spi.tenant.MembershipDirectory;
import com.akine.platform.spi.tenant.TenantContextHolder;

/**
 * Cableado del tenancy tecnico.
 *
 * <p>Vive en {@code infrastructure} porque {@code configuracion_en_infrastructure} lo exige, y
 * separado de {@code SecurityConfig} porque son dos responsabilidades: {@code SecurityConfig}
 * arma la cadena, esto construye la pieza que se le inserta.
 *
 * <p><b>Por que {@link TenantContextFilter} se declara aca y no con {@code @Component}.</b>
 * Spring Boot registra automaticamente en el contenedor de servlets cualquier bean de tipo
 * {@code Filter}. Si este filtro fuera un {@code @Component}, correria dos veces: una en la
 * cadena de Spring Security —donde tiene sentido, porque ahi ya hay principal autenticado— y
 * otra suelta antes de esa cadena, donde no lo hay. Por eso se lo registra explicitamente y se
 * desactiva su registro automatico con {@link #tenantContextFilterRegistration}.
 */
@Configuration
public class TenantConfig {

	/**
	 * El filtro de contexto, listo para insertarse en la cadena de seguridad.
	 *
	 * <p>El {@link Clock} se pasa explicito y no se lee de {@code Instant.now()} dentro del
	 * filtro: la vigencia de una membership se evalua contra un reloj, y un reloj que no se
	 * puede sustituir vuelve intesteable el caso "membership vencida hace un segundo".
	 */
	@Bean
	public TenantContextFilter tenantContextFilter(
			MembershipDirectory membershipDirectory, TenantContextHolder tenantContextHolder) {
		return new TenantContextFilter(membershipDirectory, tenantContextHolder, Clock.systemUTC());
	}

	/**
	 * Impide que el contenedor de servlets registre el filtro por su cuenta.
	 *
	 * <p>Sin esto el filtro correria ademas fuera de la cadena de seguridad, antes de que exista
	 * principal: veria a todo el mundo como no autenticado, dejaria pasar el request sin
	 * contexto y despues la cadena de seguridad lo volveria a procesar. Dos ejecuciones con
	 * criterios distintos sobre el mismo request es como se cuelan los agujeros.
	 */
	@Bean
	public FilterRegistrationBean<TenantContextFilter> tenantContextFilterRegistration(
			TenantContextFilter tenantContextFilter) {
		FilterRegistrationBean<TenantContextFilter> registration =
				new FilterRegistrationBean<>(tenantContextFilter);
		registration.setEnabled(false);
		return registration;
	}
}
