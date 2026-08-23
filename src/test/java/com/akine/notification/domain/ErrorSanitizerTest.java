package com.akine.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanitizacion del motivo de fallo que termina en {@code error_sanitizado} (T-11, RN-M02-003).
 *
 * <p>Lo que estos tests impiden: que la tabla de diagnostico se convierta en un deposito de
 * credenciales. La columna se lee desde una pantalla administrativa, se copia a los tickets y
 * termina en los backups; un mensaje de JavaMail crudo trae ahi el host del relay, el usuario
 * con el que se autentico, la direccion completa del destinatario y, si el adaptador la colo,
 * la URL con el token de activacion.
 */
class ErrorSanitizerTest {

	/**
	 * El mensaje de fallo tipico de un relay SMTP: todo lo que NO puede quedar guardado, en una
	 * sola linea.
	 */
	private static final String MENSAJE_CRUDO =
			"Could not connect to SMTP host smtp.sendgrid.net:587, user=apikey, "
					+ "password=SG.9x1QeTuVwXyZ0123456789, recipient=juan.perez@clinicasur.com.ar, "
					+ "link https://app.akine.com/activar?token=eyJhbGciOiJIUzI1NiJ9.abcdefgh";

	@Test
	@DisplayName("El motivo persistido no contiene NADA del mensaje crudo que sea sensible")
	void el_motivo_no_contiene_nada_sensible() {
		String sanitizado = ErrorSanitizer.sanitize(
				new IllegalStateException(MENSAJE_CRUDO));

		// Escrito como lista negra a proposito: si alguien vuelve a guardar el mensaje crudo
		// -o afloja una de las reglas del sanitizador- este test falla nombrando exactamente
		// que fue lo que se filtro, en vez de aprobar un texto "parecido al esperado".
		assertThat(sanitizado)
				.doesNotContain("smtp.sendgrid.net:587")
				.doesNotContain("SG.9x1QeTuVwXyZ0123456789")
				.doesNotContain("juan.perez@clinicasur.com.ar")
				.doesNotContain("juan.perez")
				.doesNotContain("https://app.akine.com/activar")
				.doesNotContain("eyJhbGciOiJIUzI1NiJ9")
				.doesNotContain("token=eyJ");

		// Y lo que si tiene que quedar: el tipo de excepcion, que es lo unico realmente util
		// para diagnosticar, y el dominio del destinatario, que permite ver "todos los de este
		// dominio rebotan" sin identificar a una persona.
		assertThat(sanitizado)
				.startsWith("IllegalStateException")
				.contains("clinicasur.com.ar");
	}

	@Test
	@DisplayName("Una URL completa se reemplaza entera, no solo su query")
	void una_url_se_reemplaza_entera() {
		// Reemplazar solo el "?token=..." dejaria la ruta, y la ruta ya dice de que enlace se
		// trata; peor, un token en el path sobreviviria.
		String sanitizado = ErrorSanitizer.sanitize(
				"fallo al abrir https://app.akine.com/invitacion/abc123 desde el worker");

		assertThat(sanitizado)
				.doesNotContain("app.akine.com")
				.doesNotContain("abc123")
				.contains("[redactado]")
				.contains("fallo al abrir")
				.contains("desde el worker");
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"token=abcdef123456",
			"password=Sup3rS3cr3t",
			"api_key=xyz",
			"API-KEY=xyz",
			"Authorization: Bearer abc",
			"secret = 12345"})
	@DisplayName("Los pares clave-valor con pinta de credencial se redactan")
	void las_credenciales_clave_valor_se_redactan(String fragmento) {
		String sanitizado = ErrorSanitizer.sanitize("fallo con " + fragmento);

		assertThat(sanitizado).contains("[redactado]");
		assertThat(sanitizado)
				.doesNotContain("abcdef123456")
				.doesNotContain("Sup3rS3cr3t")
				.doesNotContain("xyz")
				.doesNotContain("12345");
	}

	@Test
	@DisplayName("Una cadena opaca larga se borra aunque no venga con etiqueta")
	void una_cadena_opaca_larga_se_borra() {
		// Es el ultimo recurso: un token pegado suelto en el mensaje, sin "token=" delante,
		// no tiene ninguna otra regla que lo atrape.
		String sanitizado = ErrorSanitizer.sanitize("rechazado 9aF3kZ0qWeRtYuIoPaSdFgHjKlZx");

		assertThat(sanitizado)
				.doesNotContain("9aF3kZ0qWeRtYuIoPaSdFgHjKlZx")
				.contains("[redactado]");
	}

	@Test
	@DisplayName("El motivo no lleva saltos de linea, que romperian el log estructurado")
	void el_motivo_es_una_sola_linea() {
		// Un stack trace multilinea guardado en la columna parte el registro del agregador en
		// varias entradas y hace ilegible el diagnostico que la columna existe para dar.
		String sanitizado = ErrorSanitizer.sanitize("primera linea\nsegunda\r\ntercera");

		assertThat(sanitizado).doesNotContain("\n").doesNotContain("\r");
		assertThat(sanitizado).isEqualTo("primera linea segunda tercera");
	}

	@Test
	@DisplayName("El motivo se recorta al largo de la columna")
	void el_motivo_se_recorta() {
		// Si no se recortara, la insercion fallaria con un error de base al guardar el fallo,
		// y la notificacion quedaria PROCESANDO para siempre por no poder registrar por que
		// fallo.
		String sanitizado = ErrorSanitizer.sanitize("relay rechazo el mensaje ".repeat(80));

		assertThat(sanitizado).hasSizeLessThanOrEqualTo(ErrorSanitizer.LARGO_MAXIMO);
		assertThat(sanitizado).endsWith("...");
	}

	@Test
	@DisplayName("La excepcion sanitizada tambien se recorta con el prefijo del tipo incluido")
	void la_excepcion_larga_tambien_se_recorta() {
		String sanitizado = ErrorSanitizer.sanitize(new IllegalStateException("y".repeat(2000)));

		assertThat(sanitizado).hasSizeLessThanOrEqualTo(ErrorSanitizer.LARGO_MAXIMO);
	}

	@Test
	@DisplayName("La causa encadenada no se incluye")
	void la_causa_encadenada_no_se_incluye() {
		// Cada nivel de causa multiplica la superficie de fuga y aporta poco: la causa suele
		// ser justo la que trae el host y las credenciales del driver.
		Throwable causa = new IllegalStateException("conexion rechazada por relay.interno:2525");
		String sanitizado = ErrorSanitizer.sanitize(
				new RuntimeException("no se pudo enviar", causa));

		assertThat(sanitizado).isEqualTo("RuntimeException: no se pudo enviar");
		assertThat(sanitizado).doesNotContain("relay.interno");
	}

	@Test
	@DisplayName("Una excepcion sin mensaje deja solo el tipo")
	void una_excepcion_sin_mensaje_deja_el_tipo() {
		assertThat(ErrorSanitizer.sanitize(new IllegalStateException()))
				.isEqualTo("IllegalStateException");
		assertThat(ErrorSanitizer.sanitize(new IllegalStateException("   ")))
				.isEqualTo("IllegalStateException");
	}

	@Test
	@DisplayName("Un error nulo no explota: devuelve un motivo generico")
	void un_error_nulo_no_explota() {
		// El sanitizador se invoca justo cuando algo ya salio mal; que el falle ahi dejaria la
		// fila sin motivo y sin estado.
		assertThat(ErrorSanitizer.sanitize((Throwable) null)).isEqualTo("Fallo desconocido");
		assertThat(ErrorSanitizer.sanitize((String) null)).isEmpty();
		assertThat(ErrorSanitizer.sanitize("   ")).isEmpty();
	}

	@Test
	@DisplayName("Sanitizar dos veces no degrada el motivo")
	void sanitizar_dos_veces_es_estable() {
		// La entity vuelve a sanitizar lo que el servicio ya sanitizo: si la segunda pasada
		// cambiara el texto, el motivo guardado no seria el que se logueo.
		String unaVez = ErrorSanitizer.sanitize(
				"Could not connect to smtp.host.com:587 for juan@clinicasur.com");

		assertThat(unaVez).isEqualTo("Could not connect to [redactado] for j***@clinicasur.com");
		assertThat(ErrorSanitizer.sanitize(unaVez)).isEqualTo(unaVez);
	}

	@Test
	@DisplayName("maskEmail deja la inicial y el dominio, y nada mas")
	void mask_email_deja_inicial_y_dominio() {
		assertThat(ErrorSanitizer.maskEmail("juan.perez@clinicasur.com"))
				.isEqualTo("j***@clinicasur.com");
	}

	@ParameterizedTest(name = "email invalido: \"{0}\"")
	@ValueSource(strings = {"", "   ", "sinarroba", "@sinlocal.com"})
	@DisplayName("maskEmail redacta entero lo que no puede enmascarar")
	void mask_email_redacta_lo_que_no_puede_enmascarar(String email) {
		// Si ante una entrada rara devolviera el valor tal cual, alcanzaria con un
		// destinatario mal formado para que el email completo terminara en el log.
		assertThat(ErrorSanitizer.maskEmail(email)).isEqualTo("[redactado]");
	}

	@Test
	@DisplayName("maskEmail con null devuelve el marcador, no la palabra null")
	void mask_email_con_null() {
		assertThat(ErrorSanitizer.maskEmail(null)).isEqualTo("[redactado]");
	}

	@Test
	@DisplayName("Un mensaje inocuo llega entero: sanitizar no es censurar")
	void un_mensaje_inocuo_llega_entero() {
		// Si el sanitizador redactara de mas, la columna dejaria de servir para diagnosticar y
		// alguien terminaria logueando el error crudo "para poder ver algo".
		assertThat(ErrorSanitizer.sanitize("El template no existe para el tipo pedido"))
				.isEqualTo("El template no existe para el tipo pedido");
	}
}
