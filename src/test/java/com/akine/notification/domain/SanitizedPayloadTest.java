package com.akine.notification.domain;

import com.akine.notification.domain.exception.SensitiveContentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Datos de render del outbox (RN-M26-002, T-11).
 *
 * <p>Lo que estos tests sostienen: que el outbox NO pueda contener un token ni un enlace,
 * aunque el modulo productor se equivoque. La tabla se consulta para diagnosticar, se reintenta
 * desde una pantalla administrativa y termina en los backups; un token guardado ahi es una
 * credencial persistida, que es exactamente lo que RN-M02-003 prohibe.
 */
class SanitizedPayloadTest {

	@Test
	@DisplayName("La lista blanca de claves es exactamente la del diseno")
	void la_lista_blanca_es_la_del_diseno() {
		// Escrita a mano: agregar una clave tiene que ser una decision visible en el diff de
		// este test, no un descuido que pase inadvertido.
		assertThat(SanitizedPayload.CLAVES_PERMITIDAS)
				.containsExactlyInAnyOrder("nombre", "organizacionNombre", "invitadoPor");
	}

	@ParameterizedTest(name = "clave fuera de la lista blanca: {0}")
	@ValueSource(strings = {"enlace", "url", "token", "link", "activationLink", "Nombre", "NOMBRE"})
	@DisplayName("Una clave fuera de la lista blanca hace fallar el encolado")
	void una_clave_fuera_de_la_lista_blanca_falla(String clave) {
		// No existe ninguna clave "enlace" ni "token" que alguien pueda completar por error, y
		// las variantes de mayusculas tampoco cuelan: la comparacion es exacta.
		assertThatThrownBy(() -> SanitizedPayload.of(Map.of(clave, "algo")))
				.isInstanceOf(SensitiveContentException.class)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(clave);
	}

	@Test
	@DisplayName("El error de contenido sensible nunca incluye el valor ofensivo")
	void el_error_no_incluye_el_valor_ofensivo() {
		String secreto = "https://app.akine.com/activar?token=SUPERSECRETO";

		Throwable error = org.assertj.core.api.Assertions.catchThrowable(
				() -> SanitizedPayload.of(Map.of("nombre", secreto)));

		// Si el mensaje llevara el valor, el token terminaria en el log de errores: justo el
		// lugar del que lo estamos sacando.
		assertThat(error).isInstanceOf(SensitiveContentException.class);
		assertThat(error.getMessage())
				.doesNotContain("SUPERSECRETO")
				.doesNotContain("app.akine.com")
				.contains("nombre");
	}

	@ParameterizedTest(name = "valor con pinta de enlace o secreto: {0}")
	@ValueSource(strings = {
			"https://app.akine.com/activar?token=abc",
			"http://localhost:4200/reset",
			"mailto://algo",
			"activar?token=abc",
			"algo&token=abc",
			"Bearer eyJhbGciOi",
			"un JWT cualquiera",
			"mi password es 1234",
			"el secret del relay",
			"apikey del proveedor",
			"api-key del proveedor"})
	@DisplayName("Un valor con pinta de enlace o secreto se rechaza aunque la clave sea valida")
	void un_valor_sospechoso_se_rechaza(String valor) {
		// Segunda barrera: la lista blanca de claves no alcanza, porque
		// nombre="https://.../activar?token=..." entra por una clave perfectamente legitima.
		assertThatThrownBy(() -> SanitizedPayload.of(Map.of("nombre", valor)))
				.isInstanceOf(SensitiveContentException.class);
	}

	@Test
	@DisplayName("Una cadena opaca de alta entropia se rechaza aunque no diga token")
	void una_cadena_opaca_se_rechaza() {
		// Tercera barrera, la del ultimo recurso: un token pegado sin ninguna etiqueta que lo
		// delate. Un nombre propio nunca es una cadena de 24+ caracteres sin espacios que
		// mezcla mayusculas, minusculas y digitos.
		assertThatThrownBy(() -> SanitizedPayload.of(
				Map.of("nombre", "aB3xY7zQ9wE1rT5yU8iO2pA6s")))
				.isInstanceOf(SensitiveContentException.class)
				.hasMessageContaining("alta entropia");
	}

	@Test
	@DisplayName("Un valor demasiado largo se rechaza: no es un dato de render")
	void un_valor_demasiado_largo_se_rechaza() {
		assertThatThrownBy(() -> SanitizedPayload.of(Map.of("nombre", "a".repeat(201))))
				.isInstanceOf(SensitiveContentException.class)
				.hasMessageContaining("200");

		assertThatCode(() -> SanitizedPayload.of(Map.of("nombre", "a".repeat(200))))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("Los nombres propios normales pasan sin problema")
	void los_nombres_normales_pasan() {
		// Si la heuristica fuera demasiado agresiva, encolar una invitacion legitima fallaria
		// y la persona nunca la recibiria: el falso positivo tambien tiene costo.
		SanitizedPayload payload = SanitizedPayload.of(new LinkedHashMap<>(Map.of(
				"nombre", "Maria Jose Perez-Gutierrez")));

		assertThat(payload.get("nombre")).isEqualTo("Maria Jose Perez-Gutierrez");
	}

	@Test
	@DisplayName("Un payload nulo o vacio equivale al payload vacio")
	void un_payload_nulo_es_vacio() {
		assertThat(SanitizedPayload.of(null).asMap()).isEmpty();
		assertThat(SanitizedPayload.of(Map.of()).asMap()).isEmpty();
		assertThat(SanitizedPayload.vacio().toJson()).isEqualTo("{}");
	}

	@Test
	@DisplayName("Un valor nulo se guarda como cadena vacia y no como la palabra null")
	void un_valor_nulo_se_guarda_vacio() {
		Map<String, String> datos = new LinkedHashMap<>();
		datos.put("nombre", null);

		SanitizedPayload payload = SanitizedPayload.of(datos);

		// "Hola null," en el asunto de un mail a una persona real es el bug clasico de esto.
		assertThat(payload.get("nombre")).isEmpty();
		assertThat(payload.getOrDefault("nombre", "Hola")).isEqualTo("Hola");
	}

	@Test
	@DisplayName("getOrDefault cubre la clave ausente y la vacia por igual")
	void get_or_default_cubre_ausente_y_vacia() {
		SanitizedPayload payload = SanitizedPayload.of(Map.of("nombre", "Ana"));

		assertThat(payload.getOrDefault("nombre", "X")).isEqualTo("Ana");
		assertThat(payload.getOrDefault("organizacionNombre", "AKINE")).isEqualTo("AKINE");
		assertThat(payload.get("organizacionNombre")).isNull();
	}

	@Test
	@DisplayName("El JSON conserva el orden de declaracion de las claves")
	void el_json_conserva_el_orden() {
		Map<String, String> datos = new LinkedHashMap<>();
		datos.put("nombre", "Ana");
		datos.put("organizacionNombre", "Kine Sur");

		assertThat(SanitizedPayload.of(datos).toJson())
				.isEqualTo("{\"nombre\":\"Ana\",\"organizacionNombre\":\"Kine Sur\"}");
	}

	@Test
	@DisplayName("Ida y vuelta a JSON con comillas, comas y saltos de linea")
	void ida_y_vuelta_con_caracteres_dificiles() {
		// La serializacion es a mano: si el escapado se rompiera, un apostrofo en un apellido
		// dejaria la fila con un JSON invalido que nadie puede releer, y la notificacion no
		// saldria nunca.
		Map<String, String> datos = new LinkedHashMap<>();
		datos.put("nombre", "Ana \"la jefa\", O'Connor\\Smith");
		datos.put("invitadoPor", "linea1\nlinea2\ttab");

		SanitizedPayload original = SanitizedPayload.of(datos);
		SanitizedPayload releido = SanitizedPayload.fromJson(original.toJson());

		assertThat(releido.asMap()).isEqualTo(original.asMap());
	}

	@Test
	@DisplayName("Una coma dentro de un valor no parte el JSON en dos claves")
	void una_coma_dentro_del_valor_no_parte_el_json() {
		SanitizedPayload releido = SanitizedPayload.fromJson(
				"{\"nombre\":\"Perez, Ana\",\"invitadoPor\":\"Dr. Ruiz\"}");

		assertThat(releido.get("nombre")).isEqualTo("Perez, Ana");
		assertThat(releido.get("invitadoPor")).isEqualTo("Dr. Ruiz");
	}

	@Test
	@DisplayName("Releer un payload vacio o nulo devuelve el payload vacio")
	void releer_vacio() {
		assertThat(SanitizedPayload.fromJson(null).asMap()).isEmpty();
		assertThat(SanitizedPayload.fromJson("   ").asMap()).isEmpty();
		assertThat(SanitizedPayload.fromJson("{}").asMap()).isEmpty();
		assertThat(SanitizedPayload.fromJson("{ }").asMap()).isEmpty();
	}

	@ParameterizedTest(name = "json invalido: {0}")
	@ValueSource(strings = {
			"no soy json",
			"[\"nombre\"]",
			"{\"nombre\":123}",
			"{nombre:\"Ana\"}",
			"{\"nombre\"}"})
	@DisplayName("Un JSON que no es un objeto plano de textos se rechaza al releerlo")
	void un_json_invalido_se_rechaza(String json) {
		assertThatThrownBy(() -> SanitizedPayload.fromJson(json))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("Una fila vieja con contenido hoy prohibido se rechaza al leerla, no se envia")
	void una_fila_vieja_con_contenido_prohibido_se_rechaza() {
		// La validacion en la lectura es lo que hace que endurecer las reglas alcance tambien
		// a lo que ya estaba guardado: si solo se validara al escribir, una fila encolada
		// antes del cambio seguiria saliendo con el enlace adentro.
		assertThatThrownBy(() -> SanitizedPayload.fromJson(
				"{\"nombre\":\"https://app.akine.com/activar\"}"))
				.isInstanceOf(SensitiveContentException.class);

		assertThatThrownBy(() -> SanitizedPayload.fromJson("{\"enlace\":\"algo\"}"))
				.isInstanceOf(SensitiveContentException.class);
	}

	@Test
	@DisplayName("El mapa expuesto es de solo lectura")
	void el_mapa_es_de_solo_lectura() {
		SanitizedPayload payload = SanitizedPayload.of(Map.of("nombre", "Ana"));

		// Si fuera mutable, alguien podria agregarle una clave con el enlace despues de que
		// las tres barreras ya corrieron.
		assertThatThrownBy(() -> payload.asMap().put("nombre", "otro"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("toString muestra las claves y jamas los valores")
	void to_string_no_muestra_valores() {
		// Un payload logueado entero devolveria al log lo que la clase existe para mantener
		// acotado; con las claves alcanza para diagnosticar.
		SanitizedPayload payload = SanitizedPayload.of(Map.of("nombre", "Ana Perez"));

		assertThat(payload.toString()).contains("nombre").doesNotContain("Ana Perez");
	}
}
