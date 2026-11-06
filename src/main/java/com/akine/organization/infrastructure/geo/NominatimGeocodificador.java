package com.akine.organization.infrastructure.geo;

import com.akine.organization.application.Geocodificador;
import com.akine.organization.application.GeocodificadorNoDisponibleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Geocodificador sobre Nominatim (OpenStreetMap), V140.
 *
 * <h2>La politica de uso, hecha cumplir aca</h2>
 *
 * <p>El Nominatim publico es gratis con condiciones (operations.osmfoundation.org/policies/nominatim),
 * y cada una tiene su mecanismo en esta clase:
 *
 * <ul>
 *   <li><b>Una consulta por segundo, para todo el despliegue.</b> {@link #CANDADO} serializa las
 *       llamadas al proveedor y {@link #esperarTurno} duerme lo que falte para el segundo. Es un
 *       lock de PROCESO: con mas de una instancia el cupo se multiplica, igual que el rate limit
 *       de login. Para ese caso, Nominatim propio.</li>
 *   <li><b>Identificarse.</b> {@code User-Agent} configurable con un contacto; Nominatim bloquea a
 *       los clientes genericos.</li>
 *   <li><b>Cachear.</b> LRU en memoria de {@link #TAMANO_CACHE} entradas por (texto plegado, pais)
 *       con TTL de un dia: la misma direccion buscada dos veces no vuelve a salir.</li>
 *   <li><b>Sin autocompletado.</b> No se puede hacer cumplir desde aca; lo dice el contrato del
 *       endpoint y lo respeta la pantalla.</li>
 *   <li><b>Atribucion.</b> Viaja en cada respuesta ({@link #atribucion}).</li>
 * </ul>
 *
 * <p>Cualquier falla del proveedor —timeout, 5xx, 429, cuerpo que no se entiende— es
 * {@link GeocodificadorNoDisponibleException}: la sede se guarda igual sin coordenadas.
 */
public final class NominatimGeocodificador implements Geocodificador {

	private static final Logger log = LoggerFactory.getLogger(NominatimGeocodificador.class);

	/** Minimo entre dos consultas al proveedor: la politica dice una por segundo. */
	static final Duration INTERVALO_MINIMO = Duration.ofSeconds(1);

	static final int TAMANO_CACHE = 500;

	static final Duration VIGENCIA_CACHE = Duration.ofHours(24);

	private static final int MAXIMO_RESULTADOS = 5;

	/** Serializa las salidas al proveedor en todo el proceso, no por instancia. */
	private static final Object CANDADO = new Object();

	private static long ultimaConsultaNanos;

	private final RestClient restClient;

	private final Map<Clave, Entrada> cache = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Clave, Entrada> eldest) {
			return size() > TAMANO_CACHE;
		}
	};

	/**
	 * @param builder con el {@code requestFactory} (y sus timeouts) ya puesto por quien lo cablea;
	 *                aca se le agrega la base y el {@code User-Agent}. Asi un test puede atarle un
	 *                {@code MockRestServiceServer} antes
	 */
	public NominatimGeocodificador(RestClient.Builder builder, GeoProperties properties) {
		this.restClient = builder
				.baseUrl(properties.nominatim().baseUrl())
				.defaultHeader(HttpHeaders.USER_AGENT, properties.nominatim().userAgent())
				.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
				.build();
	}

	@Override
	public List<DireccionEncontrada> buscar(String q, String pais) {
		Clave clave = new Clave(q.strip().toLowerCase(Locale.ROOT), pais == null ? "" : pais.toUpperCase(Locale.ROOT));
		List<DireccionEncontrada> cacheado = leerCache(clave);
		if (cacheado != null) {
			return cacheado;
		}
		List<DireccionEncontrada> resultados = consultar(q.strip(), pais);
		guardarCache(clave, resultados);
		return resultados;
	}

	@Override
	public String proveedor() {
		return "NOMINATIM";
	}

	@Override
	public String atribucion() {
		return "(c) OpenStreetMap contributors";
	}

	private List<DireccionEncontrada> consultar(String q, String pais) {
		JsonNode cuerpo;
		synchronized (CANDADO) {
			esperarTurno();
			try {
				cuerpo = restClient.get()
						.uri(uri -> {
							uri.path("/search")
									.queryParam("q", q)
									.queryParam("format", "jsonv2")
									.queryParam("addressdetails", "1")
									.queryParam("limit", MAXIMO_RESULTADOS)
									.queryParam("accept-language", "es");
							if (pais != null && !pais.isBlank()) {
								uri.queryParam("countrycodes", pais.toLowerCase(Locale.ROOT));
							}
							return uri.build();
						})
						.retrieve()
						.body(JsonNode.class);
			} catch (RestClientException falla) {
				// La causa va al log; a la respuesta solo que el proveedor no esta.
				log.warn("Nominatim no respondio: {}", falla.getMessage());
				throw new GeocodificadorNoDisponibleException("Nominatim no respondio", falla);
			} finally {
				ultimaConsultaNanos = System.nanoTime();
			}
		}
		if (cuerpo == null || !cuerpo.isArray()) {
			throw new GeocodificadorNoDisponibleException("Nominatim devolvio un cuerpo que no se entiende");
		}
		List<DireccionEncontrada> resultados = new ArrayList<>();
		for (JsonNode item : cuerpo) {
			DireccionEncontrada direccion = mapear(item);
			if (direccion != null) {
				resultados.add(direccion);
			}
		}
		return List.copyOf(resultados);
	}

	/**
	 * Duerme lo que falte para que pase {@link #INTERVALO_MINIMO} desde la ultima salida. Acotado al
	 * intervalo entero: nunca espera mas de un segundo, por mas que el reloj haga algo raro.
	 */
	private static void esperarTurno() {
		if (ultimaConsultaNanos == 0L) {
			return;
		}
		long transcurrido = System.nanoTime() - ultimaConsultaNanos;
		long faltante = INTERVALO_MINIMO.toNanos() - transcurrido;
		if (faltante <= 0) {
			return;
		}
		try {
			Thread.sleep(Duration.ofNanos(Math.min(faltante, INTERVALO_MINIMO.toNanos())));
		} catch (InterruptedException interrumpido) {
			Thread.currentThread().interrupt();
			throw new GeocodificadorNoDisponibleException("La espera por el cupo del proveedor se interrumpio");
		}
	}

	private static DireccionEncontrada mapear(JsonNode item) {
		BigDecimal lat = decimal(item, "lat");
		BigDecimal lon = decimal(item, "lon");
		String etiqueta = texto(item, "display_name");
		if (lat == null || lon == null || etiqueta == null) {
			return null;
		}
		JsonNode address = item.path("address");
		String ciudad = primero(address, "city", "town", "village", "municipality");
		String pais = texto(address, "country_code");
		return new DireccionEncontrada(
				etiqueta,
				lat,
				lon,
				texto(address, "road"),
				texto(address, "house_number"),
				ciudad,
				texto(address, "state"),
				texto(address, "postcode"),
				pais == null ? null : pais.toUpperCase(Locale.ROOT));
	}

	private static String primero(JsonNode nodo, String... claves) {
		for (String clave : claves) {
			String valor = texto(nodo, clave);
			if (valor != null) {
				return valor;
			}
		}
		return null;
	}

	private static String texto(JsonNode nodo, String clave) {
		JsonNode valor = nodo.path(clave);
		if (valor.isMissingNode() || valor.isNull()) {
			return null;
		}
		String texto = valor.asString();
		return texto == null || texto.isBlank() ? null : texto;
	}

	private static BigDecimal decimal(JsonNode nodo, String clave) {
		String texto = texto(nodo, clave);
		if (texto == null) {
			return null;
		}
		try {
			return new BigDecimal(texto);
		} catch (NumberFormatException noEsNumero) {
			return null;
		}
	}

	private List<DireccionEncontrada> leerCache(Clave clave) {
		synchronized (cache) {
			Entrada entrada = cache.get(clave);
			if (entrada == null) {
				return null;
			}
			if (entrada.vencida()) {
				cache.remove(clave);
				return null;
			}
			return entrada.resultados();
		}
	}

	private void guardarCache(Clave clave, List<DireccionEncontrada> resultados) {
		synchronized (cache) {
			cache.put(clave, new Entrada(resultados, System.nanoTime() + VIGENCIA_CACHE.toNanos()));
		}
	}

	private record Clave(String texto, String pais) {
	}

	private record Entrada(List<DireccionEncontrada> resultados, long venceNanos) {

		boolean vencida() {
			return System.nanoTime() - venceNanos > 0;
		}
	}
}
