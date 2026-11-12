package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.Vectores;
import com.akine.knowledge.domain.port.ModeloEmbeddings;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import dev.langchain4j.model.embedding.onnx.AbstractInProcessEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxBertBiEncoder;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * multilingual-e5-base local en ONNX, con mean pooling. El modelo y el tokenizer se leen del
 * disco ({@code akine.biblioteca.modelo.ruta-modelo} y {@code ruta-tokenizer}): no se descargan.
 * Si falta alguno, la aplicacion no arranca.
 *
 * <p>Con {@code hilos > 0} ({@code akine.biblioteca.modelo.hilos}) la sesion de onnxruntime se crea
 * con ese tope de hilos intra-op y los textos de un lote se calculan uno detras de otro en el
 * hilo que llama: el total queda cerca de {@code hilos}. {@code OnnxEmbeddingModel} no expone
 * las opciones de sesion, por eso ese caso arma su propio {@link AbstractInProcessEmbeddingModel}.
 * Con {@code 0} se usa la clase de la libreria tal cual (un hilo por nucleo y un pool por nucleo).
 */
public class OnnxModeloEmbeddings implements ModeloEmbeddings {

	static final int DIMENSION = 768;

	private final String id;
	private final AbstractInProcessEmbeddingModel modelo;

	public OnnxModeloEmbeddings(String id, String rutaModelo, String rutaTokenizer) {
		this(id, rutaModelo, rutaTokenizer, 0);
	}

	public OnnxModeloEmbeddings(String id, String rutaModelo, String rutaTokenizer, int hilos) {
		this.id = id;
		Path modeloOnnx = exigirArchivo(rutaModelo, "akine.biblioteca.modelo.ruta-modelo");
		Path tokenizer = exigirArchivo(rutaTokenizer, "akine.biblioteca.modelo.ruta-tokenizer");
		this.modelo = hilos > 0
				? new ModeloAcotado(modeloOnnx, tokenizer, hilos)
				: new OnnxEmbeddingModel(modeloOnnx, tokenizer, PoolingMode.MEAN);
		int dimension = modelo.dimension();
		if (dimension != DIMENSION) {
			throw new IllegalStateException("El modelo de embeddings da vectores de " + dimension
					+ " dimensiones y la biblioteca guarda " + DIMENSION);
		}
	}

	/** Sesion con tope de hilos intra-op; el lote corre en el hilo que llama. */
	private static final class ModeloAcotado extends AbstractInProcessEmbeddingModel {

		private final OnnxBertBiEncoder encoder;

		ModeloAcotado(Path modeloOnnx, Path tokenizer, int hilos) {
			super(Runnable::run);
			try (OrtSession.SessionOptions opciones = new OrtSession.SessionOptions();
					InputStream tokens = Files.newInputStream(tokenizer)) {
				opciones.setIntraOpNumThreads(hilos);
				opciones.setInterOpNumThreads(1);
				OrtEnvironment entorno = OrtEnvironment.getEnvironment();
				this.encoder = new OnnxBertBiEncoder(entorno,
						entorno.createSession(modeloOnnx.toString(), opciones), tokens, PoolingMode.MEAN);
			} catch (OrtException | IOException e) {
				throw new IllegalStateException("No se pudo cargar el modelo de embeddings", e);
			}
		}

		@Override
		protected OnnxBertBiEncoder model() {
			return encoder;
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
