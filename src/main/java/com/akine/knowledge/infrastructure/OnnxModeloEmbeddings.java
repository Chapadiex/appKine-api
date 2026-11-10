package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.Vectores;
import com.akine.knowledge.domain.port.ModeloEmbeddings;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * multilingual-e5-base local en ONNX, con mean pooling. El modelo y el tokenizer se leen del
 * disco ({@code akine.biblioteca.modelo.ruta-modelo} y {@code ruta-tokenizer}): no se descargan.
 * Si falta alguno, la aplicacion no arranca.
 */
public class OnnxModeloEmbeddings implements ModeloEmbeddings {

	static final int DIMENSION = 768;

	private final String id;
	private final OnnxEmbeddingModel modelo;

	public OnnxModeloEmbeddings(String id, String rutaModelo, String rutaTokenizer) {
		this.id = id;
		Path modeloOnnx = exigirArchivo(rutaModelo, "akine.biblioteca.modelo.ruta-modelo");
		Path tokenizer = exigirArchivo(rutaTokenizer, "akine.biblioteca.modelo.ruta-tokenizer");
		this.modelo = new OnnxEmbeddingModel(modeloOnnx, tokenizer, PoolingMode.MEAN);
		int dimension = modelo.dimension();
		if (dimension != DIMENSION) {
			throw new IllegalStateException("El modelo de embeddings da vectores de " + dimension
					+ " dimensiones y la biblioteca guarda " + DIMENSION);
		}
	}

	private static Path exigirArchivo(String ruta, String propiedad) {
		if (ruta == null || ruta.isBlank()) {
			throw new IllegalStateException("La biblioteca esta habilitada con el modelo onnx y falta "
					+ propiedad + ": hay que apuntarla al archivo local del modelo multilingual-e5-base");
		}
		Path path = Path.of(ruta);
		if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
			throw new IllegalStateException("No se encuentra el archivo de " + propiedad + ": " + ruta);
		}
		return path;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public int dimension() {
		return DIMENSION;
	}

	@Override
	public List<float[]> pasajes(List<String> textos) {
		List<TextSegment> segmentos = textos.stream().map(t -> TextSegment.from("passage: " + t)).toList();
		return modelo.embedAll(segmentos).content().stream()
				.map(Embedding::vector)
				.map(Vectores::normalizar)
				.toList();
	}

	@Override
	public float[] consulta(String texto) {
		return Vectores.normalizar(modelo.embed("query: " + texto).content().vector());
	}
}
