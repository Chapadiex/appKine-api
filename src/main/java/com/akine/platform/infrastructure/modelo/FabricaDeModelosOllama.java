package com.akine.platform.infrastructure.modelo;

import com.akine.platform.spi.modelo.ConfiguracionDeModelo;
import com.akine.platform.spi.modelo.FabricaDeModelos;
import com.akine.platform.spi.modelo.ModeloDeLenguaje;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Arma {@link OllamaModeloDeLenguaje} o {@link ModeloDeLenguajeDePrueba}, con cupo si se pide. */
@Component
public class FabricaDeModelosOllama implements FabricaDeModelos {

	@Override
	public ModeloDeLenguaje crear(ConfiguracionDeModelo configuracion) {
		ModeloDeLenguaje modelo = switch (configuracion.tipo()) {
			case ConfiguracionDeModelo.OLLAMA -> ollama(configuracion);
			case ConfiguracionDeModelo.PRUEBA -> new ModeloDeLenguajeDePrueba(configuracion.jsonDePrueba());
			default -> throw new IllegalArgumentException(
					"Tipo de modelo desconocido: '" + configuracion.tipo() + "' (ollama o prueba)");
		};
		return configuracion.concurrencia() > 0 ? new ModeloConCupo(modelo, configuracion.concurrencia()) : modelo;
	}

	@Override
	public CompletableFuture<Integer> precargar(ConfiguracionDeModelo configuracion, Duration plazo) {
		if (ConfiguracionDeModelo.PRUEBA.equals(configuracion.tipo())) {
			return CompletableFuture.completedFuture(200);
		}
		return ollama(configuracion).precargar(plazo);
	}

	private static OllamaModeloDeLenguaje ollama(ConfiguracionDeModelo configuracion) {
		return new OllamaModeloDeLenguaje(configuracion.url(), configuracion.nombre(), configuracion.keepAlive());
	}
}
