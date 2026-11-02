package com.akine;

import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Locale;

/**
 * Aplica el formato de log de este contexto de Spring, aunque la JVM ya tenga el log configurado.
 *
 * <p>El sistema de logs es uno por JVM y lo configura el primer contexto que arranca: los que vienen
 * despues, mientras el anterior siga abierto en la cache de Spring, no lo tocan. Con Spring Boot 4.1.2
 * un test con {@code logging.structured.format.console=logstash} recibia texto si otro IT habia
 * corrido antes en el mismo proceso. En un despliegue hay un solo contexto y no pasa.
 */
public final class LogEstructuradoEnElTest {

	private LogEstructuradoEnElTest() {
	}

	public static void aplicar(ConfigurableEnvironment entorno) {
		LoggingSystem sistema = LoggingSystem.get(LogEstructuradoEnElTest.class.getClassLoader());
		sistema.cleanUp();
		sistema.beforeInitialize();
		sistema.initialize(new LoggingInitializationContext(entorno), null, null);
		// initialize vuelve a los niveles por defecto: los de logging.level los aplica el listener de
		// Spring Boot, que aca no corre. La pasada de un job sin items se loguea en DEBUG.
		sistema.setLogLevel("com.akine",
				LogLevel.valueOf(entorno.getRequiredProperty("logging.level.com.akine").toUpperCase(Locale.ROOT)));
	}
}
