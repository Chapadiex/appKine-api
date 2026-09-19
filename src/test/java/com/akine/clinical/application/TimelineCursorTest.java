package com.akine.clinical.application;

import com.akine.clinical.domain.exception.CursorInvalidoException;
import com.akine.clinical.spi.EventoClinico;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El cursor del timeline: que sobreviva la ida y vuelta, y que lo roto sea 400.
 *
 * <p>Lo que estos tests fijan es el orden total de tres columnas —sin el, dos eventos del mismo
 * instante se ordenan distinto entre una pagina y la siguiente y el cursor saltea uno— y que un
 * cursor ilegible <b>no</b> se degrade a "primera pagina".
 */
@DisplayName("TimelineCursor")
class TimelineCursorTest {

	private static final Instant T = Instant.parse("2026-09-19T10:00:00Z");

	@Test
	@DisplayName("sobrevive la ida y vuelta con sus tres campos")
	void round_trip() {
		TimelineCursor original = new TimelineCursor(T, "SESION", 41L);

		TimelineCursor recuperado = TimelineCursor.decodificar(original.codificar());

		assertThat(recuperado).isEqualTo(original);
	}

	@Test
	@DisplayName("sin cursor es la primera pagina, no un error")
	void sin_cursor() {
		assertThat(TimelineCursor.decodificar(null)).isNull();
		assertThat(TimelineCursor.decodificar("   ")).isNull();
	}

	@Nested
	@DisplayName("rechaza con 400 todo lo que no decodifique")
	class Invalido {

		@Test
		@DisplayName("lo que no es base64")
		void no_es_base64() {
			assertThatThrownBy(() -> TimelineCursor.decodificar("no-es-base64-%%%"))
					.isInstanceOf(CursorInvalidoException.class);
		}

		@Test
		@DisplayName("base64 valido con la cantidad de campos equivocada")
		void campos_de_menos() {
			assertThatThrownBy(() -> TimelineCursor.decodificar(base64("2026-09-19T10:00:00Z|SESION")))
					.isInstanceOf(CursorInvalidoException.class);
		}

		@Test
		@DisplayName("instante que no parsea")
		void instante_roto() {
			assertThatThrownBy(() -> TimelineCursor.decodificar(base64("ayer|SESION|41")))
					.isInstanceOf(CursorInvalidoException.class);
		}

		@Test
		@DisplayName("referencia que no es un numero")
		void referencia_rota() {
			assertThatThrownBy(
					() -> TimelineCursor.decodificar(base64("2026-09-19T10:00:00Z|SESION|x")))
					.isInstanceOf(CursorInvalidoException.class);
		}

		private String base64(String plano) {
			return Base64.getUrlEncoder().withoutPadding()
					.encodeToString(plano.getBytes(StandardCharsets.UTF_8));
		}
	}

	@Test
	@DisplayName("ordena por instante descendente, desempatando por origen y por referencia")
	void orden_total() {
		EventoClinico viejo = evento(T.minusSeconds(60), "SESION", 1L);
		EventoClinico adjunto = evento(T, "ADJUNTO_CLINICO", 5L);
		EventoClinico sesionVieja = evento(T, "SESION", 7L);
		EventoClinico sesionNueva = evento(T, "SESION", 9L);

		List<EventoClinico> revueltos =
				new ArrayList<>(List.of(sesionVieja, viejo, sesionNueva, adjunto));
		revueltos.sort(TimelineCursor.ORDEN);

		assertThat(revueltos).containsExactly(adjunto, sesionNueva, sesionVieja, viejo);
	}

	@Test
	@DisplayName("el evento del cursor no vuelve a entrar, pero si los posteriores del mismo instante")
	void precede_a() {
		TimelineCursor cursor = new TimelineCursor(T, "SESION", 9L);

		assertThat(cursor.precedeA(evento(T, "SESION", 9L))).isFalse();
		assertThat(cursor.precedeA(evento(T, "SESION", 7L))).isTrue();
		assertThat(cursor.precedeA(evento(T, "ADJUNTO_CLINICO", 5L))).isFalse();
		assertThat(cursor.precedeA(evento(T.minusSeconds(1), "ADJUNTO_CLINICO", 5L))).isTrue();
	}

	private static EventoClinico evento(Instant cuando, String origen, long referencia) {
		return new EventoClinico(cuando, origen, "TIPO", "Etiqueta", referencia);
	}
}
