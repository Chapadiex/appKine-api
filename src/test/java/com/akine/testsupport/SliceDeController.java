package com.akine.testsupport;

import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Lo que comparten los slices {@code @WebMvcTest} de la capa REST de un módulo: la cadena de
 * seguridad mínima (la autorización la decide el servicio), el principal de prueba y la lista de
 * excepciones que el advice del módulo traduce.
 */
public final class SliceDeController {

	private SliceDeController() {
	}

	/** Un miembro autenticado; el contexto de tenant lo pone (o no) el mock del holder. */
	public static RequestPostProcessor miembro() {
		return authentication(new UsernamePasswordAuthenticationToken(
				new PrincipalDePrueba(8L), "n/a", List.of()));
	}

	/**
	 * Las excepciones que maneja un {@code @RestControllerAdvice}, leídas de sus
	 * {@code @ExceptionHandler}: si alguien agrega un handler, el test lo recorre sin tocarlo.
	 */
	public static Stream<Class<? extends Throwable>> excepcionesManejadas(Class<?> advice) {
		return Arrays.stream(advice.getDeclaredMethods())
				.filter(m -> m.isAnnotationPresent(ExceptionHandler.class))
				.filter(m -> ProblemDetail.class.isAssignableFrom(m.getReturnType())
						|| m.getReturnType().getSimpleName().contains("ResponseEntity"))
				.flatMap(SliceDeController::tiposDe)
				.distinct();
	}

	private static Stream<Class<? extends Throwable>> tiposDe(Method metodo) {
		Class<? extends Throwable>[] declarados = metodo.getAnnotation(ExceptionHandler.class).value();
		if (declarados.length > 0) {
			return Arrays.stream(declarados);
		}
		@SuppressWarnings("unchecked")
		Class<? extends Throwable> parametro = (Class<? extends Throwable>) Arrays.stream(metodo.getParameterTypes())
				.filter(Throwable.class::isAssignableFrom).findFirst().orElseThrow();
		return Stream.of(parametro);
	}

	public record PrincipalDePrueba(long accountId) implements AuthenticatedPrincipal {

		@Override
		public boolean platformAdmin() {
			return false;
		}

		@Override
		public Long organizationId() {
			return null;
		}

		@Override
		public Long consultorioId() {
			return null;
		}
	}

	/** Cadena mínima para el slice: {@code permitAll}, la autorización la decide el servicio. */
	@TestConfiguration
	@EnableWebSecurity
	public static class Seguridad {

		@Bean
		SecurityFilterChain sliceSecurityFilterChain(HttpSecurity http) throws Exception {
			http
					.csrf(csrf -> csrf.disable())
					.sessionManagement(session -> session
							.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
					.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
			return http.build();
		}
	}
}
