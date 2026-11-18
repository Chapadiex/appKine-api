package com.akine.encounter.infrastructure.config;

import com.akine.encounter.domain.evolucion.sugerencias.AjustesDeSugerencias;
import com.akine.platform.spi.modelo.ConfiguracionDeModelo;
import com.akine.platform.spi.modelo.FabricaDeModelos;
import com.akine.platform.spi.modelo.ModeloDeLenguaje;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Cableado de las sugerencias de evolucion (M31 fase 2).
 *
 * <p>El modelo existe solo con {@code akine.evolucion.sugerencias.enabled}: apagado no hay bean
 * {@link #MODELO}, y el servicio lo pide por {@code ObjectProvider} despues de mirar los flags. Con
 * {@code ollama} va envuelto en un cupo ({@code concurrencia}, al menos 1); el de prueba, sin cupo,
 * igual que el del asistente, para que los tests lo puedan manejar.
 */
@Configuration
@EnableConfigurationProperties(SugerenciasProperties.class)
public class SugerenciasDeEvolucionConfig {

	/** Nombre del bean del modelo de las sugerencias: hay otros del mismo tipo. */
	public static final String MODELO = "modeloEvolucion";

	/** Lo que contesta el modelo de prueba por defecto. */
	static final String SIN_SUGERENCIAS = "{\"sugerencias\":[]}";

	@Bean
	AjustesDeSugerencias ajustesDeSugerencias(
			SugerenciasProperties properties,
			@Value("${akine.evolucion.enabled:false}") boolean evolucion,
			@Value("${akine.biblioteca.enabled:false}") boolean biblioteca) {
		return new AjustesDeSugerencias(properties.enabled() && evolucion && biblioteca,
				properties.modelo().nombre(), Duration.ofSeconds(properties.modelo().plazoSegundos()));
	}

	@Bean(MODELO)
	@ConditionalOnProperty(name = "akine.evolucion.sugerencias.enabled", havingValue = "true")
	public ModeloDeLenguaje modeloEvolucion(FabricaDeModelos fabrica, SugerenciasProperties properties) {
		return fabrica.crear(configuracionDelModelo(properties.modelo()));
	}

	static ConfiguracionDeModelo configuracionDelModelo(SugerenciasProperties.Modelo modelo) {
		boolean ollama = ConfiguracionDeModelo.OLLAMA.equals(modelo.tipo());
		if (ollama && modelo.concurrencia() < 1) {
			throw new IllegalArgumentException("La concurrencia del modelo tiene que ser al menos 1");
		}
		return new ConfiguracionDeModelo(modelo.tipo(), modelo.url(), modelo.nombre(), modelo.keepAlive(),
				ollama ? modelo.concurrencia() : 0, SIN_SUGERENCIAS);
	}
}
