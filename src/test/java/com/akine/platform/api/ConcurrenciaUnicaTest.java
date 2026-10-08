package com.akine.platform.api;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DP-21 hecho ejecutable: <b>la concurrencia optimista tiene un solo mapeo en todo el sistema</b>,
 * el de {@link GlobalExceptionHandler}, que emite {@code concurrent-modification}.
 *
 * <p>Hasta DP-21 {@code OrganizationProblemHandler} interceptaba la subclase de JPA y emitia
 * {@code concurrent-modification} mientras el handler global emitia {@code conflict} para el resto:
 * el mismo hecho salia con dos {@code type} segun el modulo. Los advices de modulo corren con
 * {@code HIGHEST_PRECEDENCE}, asi que uno solo que vuelva a declarar un {@code @ExceptionHandler}
 * para {@code OptimisticLockingFailureException} —o para una subclase, o para un supertipo como
 * {@code DataAccessException}— le gana al global en TODOS los controllers y reabre la divergencia
 * sin que ningun test de modulo lo note. Este test recorre todos los advices y lo prohibe.
 */
@DisplayName("DP-21: un solo mapeo de concurrencia optimista")
class ConcurrenciaUnicaTest {

	@Test
	@DisplayName("ningun advice salvo el global mapea OptimisticLockingFailureException, sus subclases ni sus supertipos")
	void solo_el_global_mapea_la_concurrencia() throws ClassNotFoundException {
		ClassPathScanningCandidateComponentProvider scanner =
				new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(RestControllerAdvice.class));

		List<String> advices = new ArrayList<>();
		List<String> infractores = new ArrayList<>();
		for (BeanDefinition definicion : scanner.findCandidateComponents("com.akine")) {
			Class<?> advice = Class.forName(definicion.getBeanClassName());
			advices.add(advice.getSimpleName());
			if (advice == GlobalExceptionHandler.class) {
				continue;
			}
			for (Method metodo : advice.getDeclaredMethods()) {
				ExceptionHandler handler = metodo.getAnnotation(ExceptionHandler.class);
				if (handler == null) {
					continue;
				}
				for (Class<? extends Throwable> tipo : tiposManejados(handler, metodo)) {
					if (tipo.isAssignableFrom(OptimisticLockingFailureException.class)
							|| OptimisticLockingFailureException.class.isAssignableFrom(tipo)) {
						infractores.add(advice.getSimpleName() + "#" + metodo.getName()
								+ " (" + tipo.getSimpleName() + ")");
					}
				}
			}
		}

		assertThat(advices)
				.as("el escaneo tiene que encontrar los advices de modulo, o el test no prueba nada")
				.contains("GlobalExceptionHandler", "OrganizationProblemHandler",
						"SchedulingProblemHandler", "BillingProblemHandler", "ClinicalProblemHandler");
		assertThat(infractores)
				.as("la concurrencia optimista sale solo por GlobalExceptionHandler (DP-21)")
				.isEmpty();
	}

	@SuppressWarnings("unchecked")
	private static List<Class<? extends Throwable>> tiposManejados(ExceptionHandler handler, Method metodo) {
		if (handler.value().length > 0) {
			return List.of(handler.value());
		}
		// Sin value, Spring infiere el tipo de los parametros del metodo.
		List<Class<? extends Throwable>> tipos = new ArrayList<>();
		for (Class<?> parametro : metodo.getParameterTypes()) {
			if (Throwable.class.isAssignableFrom(parametro)) {
				tipos.add((Class<? extends Throwable>) parametro);
			}
		}
		return tipos;
	}
}
