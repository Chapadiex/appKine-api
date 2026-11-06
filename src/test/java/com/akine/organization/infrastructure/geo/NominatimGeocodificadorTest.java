package com.akine.organization.infrastructure.geo;

import com.akine.organization.application.Geocodificador;
import com.akine.organization.application.GeocodificadorNoDisponibleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NominatimGeocodificadorTest {

	private static final String RESPUESTA = """
			[{"place_id": 1, "lat": "-31.4135000", "lon": "-64.1923000",
			  "display_name": "Avenida Colon 1234, Centro, Cordoba, Argentina",
			  "address": {"house_number": "1234", "road": "Avenida Colon", "city": "Cordoba",
			              "state": "Cordoba", "postcode": "X5000", "country_code": "ar"}},
			 {"place_id": 2, "lat": "no-es-numero", "lon": "1", "display_name": "basura"}]""";

	private MockRestServiceServer servidor;
	private NominatimGeocodificador geocodificador;

	@BeforeEach
	void prepararCliente() {
		RestClient.Builder builder = RestClient.builder();
		servidor = MockRestServiceServer.bindTo(builder).build();
		geocodificador = new NominatimGeocodificador(builder, new GeoProperties(
				GeoProperties.Proveedor.NOMINATIM,
				Duration.ofSeconds(5),
				new GeoProperties.Nominatim("https://nominatim.test", "AKINE-test/1 (prueba)")));
	}

	@Test
	@DisplayName("mapea la respuesta de Nominatim, se identifica, acota por pais y descarta lo que no tiene coordenadas")
	void mapea_un_resultado() {
		servidor.expect(once(), requestTo(startsWith("https://nominatim.test/search?")))
				.andExpect(header(HttpHeaders.USER_AGENT, "AKINE-test/1 (prueba)"))
				.andExpect(queryParam("q", "Colon"))
				.andExpect(queryParam("format", "jsonv2"))
				.andExpect(queryParam("addressdetails", "1"))
				.andExpect(queryParam("limit", "5"))
				.andExpect(queryParam("countrycodes", "ar"))
				.andRespond(withSuccess(RESPUESTA, MediaType.APPLICATION_JSON));

		List<Geocodificador.DireccionEncontrada> resultados = geocodificador.buscar("Colon", "AR");

		assertThat(resultados).hasSize(1);
		Geocodificador.DireccionEncontrada direccion = resultados.get(0);
		assertThat(direccion.etiqueta()).isEqualTo("Avenida Colon 1234, Centro, Cordoba, Argentina");
		assertThat(direccion.latitud()).isEqualByComparingTo("-31.4135");
		assertThat(direccion.longitud()).isEqualByComparingTo("-64.1923");
		assertThat(direccion.calle()).isEqualTo("Avenida Colon");
		assertThat(direccion.numero()).isEqualTo("1234");
		assertThat(direccion.ciudad()).isEqualTo("Cordoba");
		assertThat(direccion.provincia()).isEqualTo("Cordoba");
		assertThat(direccion.codigoPostal()).isEqualTo("X5000");
		assertThat(direccion.pais()).isEqualTo("AR");
		assertThat(geocodificador.atribucion()).contains("OpenStreetMap");

		// La misma busqueda otra vez sale de la cache: el servidor espero UNA sola llamada.
		assertThat(geocodificador.buscar(" colon ", "ar")).hasSize(1);
		servidor.verify();
	}

	@Test
	@DisplayName("un 5xx del proveedor es 'no disponible', no un 500 propio")
	void error_del_proveedor() {
		servidor.expect(once(), requestTo(startsWith("https://nominatim.test/search?")))
				.andRespond(withServerError());

		assertThatThrownBy(() -> geocodificador.buscar("Otra calle 99", null))
				.isInstanceOf(GeocodificadorNoDisponibleException.class);
		servidor.verify();
	}
}
