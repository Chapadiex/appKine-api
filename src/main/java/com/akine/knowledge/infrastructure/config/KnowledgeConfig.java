package com.akine.knowledge.infrastructure.config;

import com.akine.knowledge.domain.BibliotecaAjustes;
import com.akine.knowledge.domain.BusquedaAjustes;
import com.akine.knowledge.domain.DetectorDeInstrucciones;
import com.akine.knowledge.domain.ReindexadoAjustes;
import com.akine.knowledge.domain.Troceador;
import com.akine.knowledge.domain.port.ModeloDeLenguaje;
import com.akine.knowledge.domain.port.ModeloEmbeddings;
import com.akine.knowledge.infrastructure.ModeloDeLenguajeDePrueba;
import com.akine.knowledge.infrastructure.OllamaModeloDeLenguaje;
import com.akine.knowledge.infrastructure.ModeloEmbeddingsDePrueba;
import com.akine.knowledge.infrastructure.OnnxModeloEmbeddings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Cableado del modulo {@code knowledge}.
 *
 * <p>El modelo de embeddings existe solo con la biblioteca habilitada: apagada no hay bean de
 * {@link ModeloEmbeddings} y quien lo use lo pide por {@code ObjectProvider}.
 *
 * <p>Sin {@code @EnableScheduling}: los workers corren en el hilo propio de
 * {@code PlanificadorDeBiblioteca}, no en el planificador compartido.
 */
@Configuration
@EnableConfigurationProperties(BibliotecaProperties.class)
public class KnowledgeConfig {

	static final Set<String> TIPOS_DE_MODELO = Set.of("onnx", "prueba");
	static final Set<String> TIPOS_DE_CONSULTA = Set.of("ollama", "prueba");

	@Bean
	public BibliotecaAjustes bibliotecaAjustes(BibliotecaProperties properties) {
		exigirTipoDeModelo(properties);
		exigirTipoDeConsulta(properties);
		return new BibliotecaAjustes(properties.enabled(), properties.maxBytes(), properties.maxPaginas(),
				properties.consulta().enabled());
	}

	@Bean
	public ReindexadoAjustes reindexadoAjustes(BibliotecaProperties properties) {
		return new ReindexadoAjustes(properties.worker().reindexLote());
	}

	/** {@code valueOf(double)} pasa por el texto decimal: {@code 0.818} queda exacto. */
	@Bean
	public BusquedaAjustes busquedaAjustes(BibliotecaProperties properties) {
		BibliotecaProperties.Busqueda busqueda = properties.busqueda();
		return new BusquedaAjustes(
				BigDecimal.valueOf(busqueda.minScore()),
				BigDecimal.valueOf(busqueda.minScorePalabras()),
				busqueda.minPedazos());
	}

	/** Habilitada con un tipo desconocido no habria modelo y nadie se enteraria: no arranca. */
	static void exigirTipoDeModelo(BibliotecaProperties properties) {
		String tipo = properties.modelo().tipo();
		if (properties.enabled() && !TIPOS_DE_MODELO.contains(tipo)) {
			throw new IllegalStateException("akine.biblioteca.modelo.tipo='" + tipo
					+ "' no es valido: tiene que ser onnx o prueba");
		}
	}

	/** La consulta activa con un tipo desconocido no tendria modelo de lenguaje: no arranca. */
	static void exigirTipoDeConsulta(BibliotecaProperties properties) {
		BibliotecaProperties.Consulta consulta = properties.consulta();
		if (properties.enabled() && consulta.enabled() && !TIPOS_DE_CONSULTA.contains(consulta.tipo())) {
			throw new IllegalStateException("akine.biblioteca.consulta.tipo='" + consulta.tipo()
					+ "' no es valido: tiene que ser ollama o prueba");
		}
	}

	@Bean
	public Troceador troceador(BibliotecaProperties properties) {
		BibliotecaProperties.Troceo troceo = properties.troceo();
		return new Troceador(troceo.tamano(), troceo.solape(), troceo.minimo());
	}

	@Bean
	public DetectorDeInstrucciones detectorDeInstrucciones() {
		return DetectorDeInstrucciones.porDefecto();
	}

	/** multilingual-e5-base local. Falla al arrancar si faltan los archivos del modelo. */
	@Bean
	@ConditionalOnExpression("${akine.biblioteca.enabled:false} and '${akine.biblioteca.modelo.tipo:onnx}' == 'onnx'")
	public ModeloEmbeddings onnxModeloEmbeddings(BibliotecaProperties properties) {
		BibliotecaProperties.Modelo modelo = properties.modelo();
		return new OnnxModeloEmbeddings(modelo.id(), modelo.rutaModelo(), modelo.rutaTokenizer(), modelo.hilos());
	}

	/** Determinista, para tests y desarrollo sin el modelo descargado. */
	@Bean
	@ConditionalOnExpression("${akine.biblioteca.enabled:false} and '${akine.biblioteca.modelo.tipo:onnx}' == 'prueba'")
	public ModeloEmbeddings modeloEmbeddingsDePrueba() {
		return new ModeloEmbeddingsDePrueba();
	}

	/** Modelo de lenguaje local sobre Ollama, solo con la biblioteca y la consulta habilitadas. */
	@Bean
	@ConditionalOnExpression("${akine.biblioteca.enabled:false} and ${akine.biblioteca.consulta.enabled:false}"
			+ " and '${akine.biblioteca.consulta.tipo:ollama}' == 'ollama'")
	public ModeloDeLenguaje ollamaModeloDeLenguaje(BibliotecaProperties properties) {
		BibliotecaProperties.Consulta consulta = properties.consulta();
		return new OllamaModeloDeLenguaje(consulta.url(), consulta.modelo(), consulta.keepAlive());
	}

	/** Determinista, para tests y desarrollo sin Ollama. */
	@Bean
	@ConditionalOnExpression("${akine.biblioteca.enabled:false} and ${akine.biblioteca.consulta.enabled:false}"
			+ " and '${akine.biblioteca.consulta.tipo:ollama}' == 'prueba'")
	public ModeloDeLenguaje modeloDeLenguajeDePrueba() {
		return new ModeloDeLenguajeDePrueba();
	}
}
