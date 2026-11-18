package com.akine.knowledge.infrastructure.config;

import com.akine.knowledge.application.BusquedaHibrida;
import com.akine.knowledge.application.ColaDeConsultas;
import com.akine.knowledge.application.Interpretador;
import com.akine.knowledge.application.SondaDeEvidencia;
import com.akine.knowledge.domain.BibliotecaAjustes;
import com.akine.knowledge.domain.BusquedaAjustes;
import com.akine.knowledge.domain.ProcesamientoAjustes;
import com.akine.knowledge.domain.ReindexadoAjustes;
import com.akine.knowledge.domain.Troceador;
import com.akine.knowledge.domain.port.ModeloEmbeddings;
import com.akine.knowledge.infrastructure.PrecargaDelModelo;
import com.akine.knowledge.infrastructure.ModeloEmbeddingsDePrueba;
import com.akine.knowledge.infrastructure.OnnxModeloEmbeddings;
import com.akine.platform.spi.modelo.ConfiguracionDeModelo;
import com.akine.platform.spi.modelo.FabricaDeModelos;
import com.akine.platform.spi.modelo.ModeloDeLenguaje;
import com.akine.platform.spi.texto.DetectorDeInstrucciones;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
	public ProcesamientoAjustes procesamientoAjustes(BibliotecaProperties properties) {
		return new ProcesamientoAjustes(properties.versionProcesamientoVigente());
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

	@Bean
	public BusquedaHibrida.Ajustes busquedaHibridaAjustes(BibliotecaProperties properties) {
		BibliotecaProperties.Consulta.Busqueda b = properties.consulta().busqueda();
		return new BusquedaHibrida.Ajustes(b.topKVector(), b.topKPalabras(), b.rrfK(), b.pasajesBreve(),
				b.pasajesProfundo(), b.bonusLibro(), b.minimoPasajes(), BigDecimal.valueOf(b.corteVector()));
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

	/** Cola de consultas de esta instancia: 1 en curso y {@code max-en-espera} esperando. */
	@Bean
	public ColaDeConsultas colaDeConsultas(BibliotecaProperties properties) {
		return new ColaDeConsultas(properties.consulta().maxEnEspera());
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

	/** Nombre del bean del modelo de lenguaje de la biblioteca: hay otros del mismo tipo. */
	public static final String MODELO = "modeloBiblioteca";

	/**
	 * Modelo de lenguaje local (Ollama o el de prueba), solo con la biblioteca y la consulta
	 * habilitadas. Sin cupo: la cola de consultas ya deja una sola en curso.
	 */
	@Bean(MODELO)
	@ConditionalOnExpression("${akine.biblioteca.enabled:false} and ${akine.biblioteca.consulta.enabled:false}"
			+ " and ('${akine.biblioteca.consulta.tipo:ollama}' == 'ollama'"
			+ " or '${akine.biblioteca.consulta.tipo:ollama}' == 'prueba')")
	public ModeloDeLenguaje modeloBiblioteca(FabricaDeModelos fabrica, BibliotecaProperties properties) {
		return fabrica.crear(configuracionDelModelo(properties));
	}

	static ConfiguracionDeModelo configuracionDelModelo(BibliotecaProperties properties) {
		BibliotecaProperties.Consulta consulta = properties.consulta();
		return new ConfiguracionDeModelo(consulta.tipo(), consulta.url(), consulta.modelo(), consulta.keepAlive(), 0,
				null);
	}

	/** Carga el modelo en Ollama al arrancar, en segundo plano (diseño consulta v2, §4.9). */
	@Bean
	@ConditionalOnExpression("${akine.biblioteca.enabled:false} and ${akine.biblioteca.consulta.enabled:false}"
			+ " and '${akine.biblioteca.consulta.tipo:ollama}' == 'ollama'")
	public PrecargaDelModelo precargaDelModelo(FabricaDeModelos fabrica, BibliotecaProperties properties) {
		ConfiguracionDeModelo configuracion = configuracionDelModelo(properties);
		return new PrecargaDelModelo(plazo -> fabrica.precargar(configuracion, plazo));
	}

	/**
	 * Interpreta la consulta antes de buscar. Pide el modelo de lenguaje en cada llamada: apagada la
	 * consulta no hay bean, y la falla cae en el respaldo del interpretador.
	 */
	@Bean
	public Interpretador interpretador(@Qualifier(MODELO) ObjectProvider<ModeloDeLenguaje> modelo,
			SondaDeEvidencia sonda, BibliotecaProperties properties) {
		return new Interpretador(modelo::getObject, sonda, properties.consulta().busqueda().corteVector());
	}
}
