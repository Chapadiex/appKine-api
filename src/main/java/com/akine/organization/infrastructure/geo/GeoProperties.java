package com.akine.organization.infrastructure.geo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuracion de la geocodificacion de direcciones (M03, V140).
 *
 * <h2>Nominatim, y lo que implica</h2>
 *
 * <p>El default es el Nominatim publico de OpenStreetMap: gratis, sin clave y suficiente para
 * ubicar sedes, que es una operacion administrativa poco frecuente. Su politica de uso
 * (operations.osmfoundation.org/policies/nominatim) exige: <b>como maximo una consulta por
 * segundo</b>, identificar la aplicacion con un {@code User-Agent} propio, <b>no usarlo como
 * autocompletado</b>, cachear los resultados y mostrar la atribucion "(c) OpenStreetMap
 * contributors". {@link NominatimGeocodificador} hace cumplir las cuatro primeras; la quinta viaja
 * en la respuesta para que la pantalla la muestre.
 *
 * <p><b>No sirve para volumen</b>: un despliegue con muchas altas de sede por minuto, o cualquier
 * uso comercial intensivo, tiene que apuntar {@code baseUrl} a un Nominatim propio (es software
 * libre y se levanta con Docker) o cambiar de proveedor. Con {@code NINGUNO} la busqueda responde
 * 503 y la sede se ubica a mano.
 *
 * @param proveedor {@code NOMINATIM} (default) o {@code NINGUNO}
 * @param timeout   tope de conexion y de lectura por consulta. Nominatim publico suele responder
 *                  en menos de un segundo; mas de cinco es que no esta
 * @param nominatim base y {@code User-Agent} del Nominatim a usar
 */
@ConfigurationProperties(prefix = "akine.geo")
public record GeoProperties(
		@DefaultValue("NOMINATIM") Proveedor proveedor,
		@DefaultValue("5s") Duration timeout,
		@DefaultValue Nominatim nominatim) {

	public enum Proveedor {
		NOMINATIM,
		NINGUNO
	}

	/**
	 * @param baseUrl   raiz del servicio, sin barra final. El publico, o uno propio
	 * @param userAgent identificacion de la aplicacion con un contacto. La politica de Nominatim lo
	 *                  exige y bloquea a los clientes genericos
	 */
	public record Nominatim(
			@DefaultValue("https://nominatim.openstreetmap.org") String baseUrl,
			@DefaultValue("AKINE/0.1 (akine-tesis; contacto@akine.app)") String userAgent) {
	}
}
