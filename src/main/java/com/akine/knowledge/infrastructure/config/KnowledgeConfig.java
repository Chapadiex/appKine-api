package com.akine.knowledge.infrastructure.config;

import com.akine.knowledge.domain.BibliotecaAjustes;
import com.akine.knowledge.domain.DetectorDeInstrucciones;
import com.akine.knowledge.domain.Troceador;
import com.akine.knowledge.domain.port.ModeloEmbeddings;
import com.akine.knowledge.infrastructure.ModeloEmbeddingsDePrueba;
import com.akine.knowledge.infrastructure.OnnxModeloEmbeddings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Set;

/**
 * Cableado del modulo {@code knowledge}.
 *
 * <p>El modelo de embeddings existe solo con la biblioteca habilitada: apagada no hay bean de
 * {@link ModeloEmbeddings} y quien lo use lo pide por {@code ObjectProvider}.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(BibliotecaProperties.class)
public class KnowledgeConfig {

	static final Set<String> TIPOS_DE_MODELO = Set.of("onnx", "prueba");

	@Bean
	public BibliotecaAjustes bibliotecaAjustes(BibliotecaProperties properties) {
		exigirTipoDeModelo(properties);
		return new BibliotecaAjustes(properties.enabled(), properties.maxBytes(), properties.maxPaginas());
	}

	/** Habilitada con un tipo desconocido no habria modelo y nadie se enteraria: no arranca. */
	static void exigirTipoDeModelo(BibliotecaProperties properties) {
		String tipo = properties.modelo().tipo();
		if (properties.enabled() && !TIPOS_DE_MODELO.contains(tipo)) {
			throw new IllegalStateException("akine.biblioteca.modelo.tipo='" + tipo
					+ "' no es valido: tiene que ser onnx o prueba");
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
		return new OnnxModeloEmbeddings(modelo.id(), modelo.rutaModelo(), modelo.rutaTokenizer());
	}

	/** Determinista, para tests y desarrollo sin el modelo descargado. */
	@Bean
	@ConditionalOnExpression("${akine.biblioteca.enabled:false} and '${akine.biblioteca.modelo.tipo:onnx}' == 'prueba'")
	public ModeloEmbeddings modeloEmbeddingsDePrueba() {
		return new ModeloEmbeddingsDePrueba();
	}
}
