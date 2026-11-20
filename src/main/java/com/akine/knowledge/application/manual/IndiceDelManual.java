package com.akine.knowledge.application.manual;

import com.akine.knowledge.domain.manual.FichaDelManual;
import com.akine.knowledge.domain.manual.PasajeDelManual;
import com.akine.knowledge.domain.port.ModeloEmbeddings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * El manual en memoria (docs/diseno/AKINE-manual-de-usuario.md §5.2 y §5.3). Se carga al arrancar y un
 * manual invalido hace fallar el arranque. La busqueda es por palabras y, si la biblioteca esta
 * prendida y hay modelo de embeddings, tambien por vector, fusionadas por RRF. El filtro de la persona
 * se aplica <b>antes</b> de rankear: nada invisible llega a la lista.
 */
@Component
public class IndiceDelManual {

	private static final Logger log = LoggerFactory.getLogger(IndiceDelManual.class);

	/** Constante de RRF, la misma que usa la biblioteca por defecto. */
	private static final int RRF_K = 60;
	/** Plus a la ficha de la pantalla actual: medio puesto de RRF, desempata sin dar vuelta un ranking. */
	/** Cuantos pasajes aporta el ranking por vector a la fusion. */
	private static final int TOPE_POR_VECTOR = 10;
	/**
	 * Peso del ranking por vector frente al de palabras. Con el mismo peso, el coseno de e5 sobre titulos
	 * cortos desplazaba la tarea que las palabras encontraban bien (medido el 20/11: 4 de 33 preguntas).
	 * Asi el vector desempata y suma lo que las palabras no ven, sin pasar por encima de ellas.
	 */
	private static final double PESO_VECTOR = 0.3d;
	private static final double PLUS_RUTA_ACTUAL = 0.5d / (RRF_K + 1);
	private static final Pattern MARKDOWN = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)|:icono\\[[^\\]]*\\]:|[*#>_`]|\\[!\\w+\\]");

	private final List<FichaDelManual> fichas;
	private final Map<String, FichaDelManual> porId;
	private final Map<String, List<String>> tokensPorPasaje = new HashMap<>();
	private final Map<String, List<String>> tokensDeTitulo = new HashMap<>();
	private final ObjectProvider<ModeloEmbeddings> modelo;
	private volatile Map<String, float[]> vectores = Map.of();

	@Autowired
	public IndiceDelManual(ResourcePatternResolver resolver, ObjectProvider<ModeloEmbeddings> modelo) {
		this(CargadorDelManual.cargar(resolver), modelo);
	}

	IndiceDelManual(List<FichaDelManual> fichas, ObjectProvider<ModeloEmbeddings> modelo) {
		this.fichas = List.copyOf(fichas);
		this.modelo = modelo;
		Map<String, FichaDelManual> indice = new LinkedHashMap<>();
		for (FichaDelManual ficha : this.fichas) {
			indice.put(ficha.id(), ficha);
			List<String> extra = TokenizadorDelManual.tokens(ficha.titulo() + " " + String.join(" ", ficha.palabras()));
			for (PasajeDelManual pasaje : ficha.pasajes()) {
				List<String> tokens = new ArrayList<>(TokenizadorDelManual.tokens(pasaje.markdown()));
				tokens.addAll(extra);
				tokensPorPasaje.put(pasaje.id(), tokens);
				tokensDeTitulo.put(pasaje.id(), TokenizadorDelManual.tokens(pasaje.titulo()));
			}
		}
		this.porId = Map.copyOf(indice);
		log.info("Manual cargado: {} fichas, {} pasajes", this.fichas.size(), tokensPorPasaje.size());
	}

	/** Calcula los vectores en segundo plano: mientras tanto, y sin modelo, se busca solo por palabras. */
	@EventListener(ApplicationReadyEvent.class)
	public void calcularVectores() {
		ModeloEmbeddings embeddings = modelo == null ? null : modelo.getIfAvailable();
		if (embeddings == null) {
			log.info("Manual sin modelo de embeddings: busqueda solo por palabras");
			return;
		}
		Thread.ofVirtual().name("manual-vectores").start(() -> calcularVectores(embeddings));
	}

	/** Calcula y publica los vectores de todos los pasajes. Si el modelo falla, sigue solo por palabras. */
	void calcularVectores(ModeloEmbeddings embeddings) {
		try {
			List<PasajeEncontrado> todos = pasajes(f -> true);
			List<float[]> calculados = embeddings.pasajes(todos.stream().map(IndiceDelManual::textoParaVector).toList());
			Map<String, float[]> nuevos = new HashMap<>();
			for (int i = 0; i < todos.size(); i++) {
				nuevos.put(todos.get(i).pasaje().id(), calculados.get(i));
			}
			vectores = Map.copyOf(nuevos);
			log.info("Manual: {} vectores calculados", nuevos.size());
		}
		catch (RuntimeException e) {
			log.warn("Manual: no se pudieron calcular los vectores, sigue solo por palabras", e);
		}
	}

	/** Las fichas visibles, por grupo y titulo. */
	public List<FichaDelManual> fichas(Predicate<FichaDelManual> visible) {
		return fichas.stream().filter(visible).toList();
	}

	public Optional<FichaDelManual> ficha(String id, Predicate<FichaDelManual> visible) {
		return Optional.ofNullable(porId.get(id)).filter(visible);
	}

	/**
	 * La ficha de una ruta del frontend. Quita query y fragmento y compara segmento a segmento; un
	 * segmento {@code :x} del patron acepta cualquiera. Con varias, gana la que tiene menos parametros.
	 */
	public Optional<FichaDelManual> porRuta(String ruta, Predicate<FichaDelManual> visible) {
		if (ruta == null || ruta.isBlank()) {
			return Optional.empty();
		}
		String[] pedida = segmentos(sinQuery(ruta));
		FichaDelManual mejor = null;
		int menosParametros = Integer.MAX_VALUE;
		for (FichaDelManual ficha : fichas) {
			if (!visible.test(ficha)) {
				continue;
			}
			for (String patron : ficha.rutas()) {
				int parametros = coincide(segmentos(patron), pedida);
				if (parametros >= 0 && parametros < menosParametros) {
					mejor = ficha;
					menosParametros = parametros;
				}
			}
		}
		return Optional.ofNullable(mejor);
	}

	/**
	 * Los {@code n} mejores pasajes visibles para la consulta. Palabras (los titulos pesan doble) y,
	 * si hay vectores, coseno; fusionados por RRF, con un plus a la ficha de la pantalla actual.
	 */
	public List<PasajeEncontrado> buscar(String consulta, Predicate<FichaDelManual> visible, String rutaActual, int n) {
		List<String> pedidos = TokenizadorDelManual.tokens(consulta);
		if (pedidos.isEmpty() || n <= 0) {
			return List.of();
		}
		List<PasajeEncontrado> candidatos = pasajes(visible);
		Map<String, Double> rrf = new HashMap<>();
		sumarRrf(rrf, rankingPorPalabras(candidatos, pedidos), 1d);
		Map<String, float[]> vectoresActuales = vectores;
		ModeloEmbeddings embeddings = vectoresActuales.isEmpty() || modelo == null ? null : modelo.getIfAvailable();
		if (embeddings != null) {
			try {
				List<String> porVector = rankingPorVector(candidatos, embeddings.consulta(consulta), vectoresActuales);
				// Solo los mas parecidos: con todos, cualquier consulta "encuentra" el manual entero.
				sumarRrf(rrf, porVector.subList(0, Math.min(TOPE_POR_VECTOR, porVector.size())), PESO_VECTOR);
			}
			catch (RuntimeException e) {
				// Igual que la biblioteca: si el modelo falla, la busqueda sigue por palabras.
				log.warn("Manual: el modelo de embeddings fallo, busqueda solo por palabras ({})",
						e.getClass().getSimpleName());
			}
		}
		String fichaActual = porRuta(rutaActual, visible).map(FichaDelManual::id).orElse(null);
		return candidatos.stream()
				.filter(c -> rrf.containsKey(c.pasaje().id()))
				.map(c -> new PasajeEncontrado(c.pasaje(), c.ficha(), rrf.get(c.pasaje().id())
						+ (c.ficha().id().equals(fichaActual) ? PLUS_RUTA_ACTUAL : 0d)))
				.sorted(Comparator.comparingDouble(PasajeEncontrado::puntaje).reversed())
				.limit(n)
				.toList();
	}

	/** El cuerpo de un pasaje sin marcas de Markdown, para fragmentos y vectores. */
	public static String textoPlano(String markdown) {
		return MARKDOWN.matcher(markdown).replaceAll("").replaceAll("\\s+", " ").strip();
	}

	private List<PasajeEncontrado> pasajes(Predicate<FichaDelManual> visible) {
		List<PasajeEncontrado> todos = new ArrayList<>();
		for (FichaDelManual ficha : fichas) {
			if (visible.test(ficha)) {
				ficha.pasajes().forEach(p -> todos.add(new PasajeEncontrado(p, ficha, 0d)));
			}
		}
		return todos;
	}

	private List<String> rankingPorPalabras(List<PasajeEncontrado> candidatos, List<String> pedidos) {
		Map<String, Integer> puntajes = new HashMap<>();
		for (PasajeEncontrado c : candidatos) {
			String id = c.pasaje().id();
			int puntaje = 0;
			for (String pedido : pedidos) {
				if (tokensDeTitulo.get(id).stream().anyMatch(t -> TokenizadorDelManual.coinciden(pedido, t))) {
					puntaje += 2;
				}
				if (tokensPorPasaje.get(id).stream().anyMatch(t -> TokenizadorDelManual.coinciden(pedido, t))) {
					puntaje += 1;
				}
			}
			if (puntaje > 0) {
				puntajes.put(id, puntaje);
			}
		}
		return ordenar(candidatos, puntajes::get);
	}

	private static List<String> rankingPorVector(List<PasajeEncontrado> candidatos, float[] consulta,
			Map<String, float[]> vectores) {
		Map<String, Double> cosenos = new HashMap<>();
		for (PasajeEncontrado c : candidatos) {
			float[] v = vectores.get(c.pasaje().id());
			if (v != null) {
				cosenos.put(c.pasaje().id(), producto(consulta, v));
			}
		}
		return ordenar(candidatos, cosenos::get);
	}

	/** Los ids con puntaje, de mayor a menor; a igual puntaje, en el orden del manual. */
	private static <N extends Number> List<String> ordenar(List<PasajeEncontrado> candidatos,
			Function<String, N> puntaje) {
		return candidatos.stream()
				.map(c -> c.pasaje().id())
				.filter(id -> puntaje.apply(id) != null)
				.sorted(Comparator.comparingDouble((String id) -> puntaje.apply(id).doubleValue()).reversed())
				.toList();
	}

	private static void sumarRrf(Map<String, Double> rrf, List<String> ranking, double peso) {
		for (int i = 0; i < ranking.size(); i++) {
			rrf.merge(ranking.get(i), peso / (RRF_K + i + 1), Double::sum);
		}
	}

	private static double producto(float[] a, float[] b) {
		double suma = 0d;
		for (int i = 0; i < Math.min(a.length, b.length); i++) {
			suma += a[i] * b[i];
		}
		return suma;
	}

	private static String textoParaVector(PasajeEncontrado c) {
		return c.ficha().titulo() + " — " + c.pasaje().titulo() + "\n" + textoPlano(c.pasaje().markdown());
	}

	/**
	 * La ruta sin query ni fragmento. Sin regex: la ruta la manda el usuario, y un patron como
	 * {@code [?#].*$} es polinomico sobre una entrada con muchos '#' (CodeQL, ReDoS).
	 */
	static String sinQuery(String ruta) {
		int corte = ruta.length();
		int pregunta = ruta.indexOf('?');
		int numeral = ruta.indexOf('#');
		if (pregunta >= 0) {
			corte = pregunta;
		}
		if (numeral >= 0 && numeral < corte) {
			corte = numeral;
		}
		return ruta.substring(0, corte);
	}

	/** Los segmentos no vacios de la ruta, sin regex por el mismo motivo que {@link #sinQuery}. */
	static String[] segmentos(String ruta) {
		List<String> partes = new ArrayList<>();
		for (String parte : ruta.split("/", -1)) {
			if (!parte.isEmpty()) {
				partes.add(parte);
			}
		}
		return partes.toArray(String[]::new);
	}

	/** Cantidad de parametros si el patron coincide, o -1. */
	private static int coincide(String[] patron, String[] pedida) {
		if (patron.length != pedida.length) {
			return -1;
		}
		int parametros = 0;
		for (int i = 0; i < patron.length; i++) {
			if (patron[i].startsWith(":")) {
				parametros++;
			}
			else if (!patron[i].equals(pedida[i])) {
				return -1;
			}
		}
		return parametros;
	}
}
