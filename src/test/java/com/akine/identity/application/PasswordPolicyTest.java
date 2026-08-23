package com.akine.identity.application;

import com.akine.identity.domain.exception.PasswordPolicyViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Politica de contrasenas (NIST 800-63B, RNF-M02-008).
 *
 * <p>Lo que NO se testea es tan significativo como lo que si: no hay ningun test que exija
 * mayusculas, numeros ni simbolos, porque la politica no lo pide. Las reglas de composicion
 * empujan a todo el mundo hacia {@code Password1!} y no achican el espacio de busqueda.
 */
class PasswordPolicyTest {

	private final PasswordPolicy policy = new PasswordPolicy();

	@ParameterizedTest
	@ValueSource(strings = {
			"kinesiologia-2026",
			"una frase larga con espacios",
			"1234567890abcdef",
			"todo en minusculas y sin numeros"
	})
	@DisplayName("una contrasena larga y no filtrada se acepta, sin exigir composicion")
	void una_contrasena_larga_se_acepta(String password) {
		assertThatCode(() -> policy.validar(password)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("el minimo es 10 caracteres")
	void el_minimo_es_diez() {
		assertThatThrownBy(() -> policy.validar("9caracter"))
				.isInstanceOf(PasswordPolicyViolationException.class)
				.hasMessageContaining("10");

		assertThatCode(() -> policy.validar("10caracter")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("el maximo es 128, y es un limite de recursos, no de seguridad")
	void el_maximo_es_128() {
		// Sin tope, mandar 10 MB en el campo contrasena obliga a hashear 10 MB con Argon2id:
		// una denegacion de servicio barata de ejecutar.
		assertThatCode(() -> policy.validar("a".repeat(PasswordPolicy.LARGO_MAXIMO)))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> policy.validar("a".repeat(PasswordPolicy.LARGO_MAXIMO + 1)))
				.isInstanceOf(PasswordPolicyViolationException.class);
	}

	@Test
	@DisplayName("una contrasena vacia o nula se rechaza")
	void una_contrasena_vacia_se_rechaza() {
		assertThatThrownBy(() -> policy.validar(null))
				.isInstanceOf(PasswordPolicyViolationException.class);
		assertThatThrownBy(() -> policy.validar(""))
				.isInstanceOf(PasswordPolicyViolationException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"password123", "PASSWORD123", "Kinesiologia", "welcome123"})
	@DisplayName("las de la denylist se rechazan sin importar como se escriban")
	void las_de_la_denylist_se_rechazan(String password) {
		assertThatThrownBy(() -> policy.validar(password))
				.isInstanceOf(PasswordPolicyViolationException.class)
				.hasMessageContaining("listas publicas");
	}

	@Test
	@DisplayName("el mensaje habla de lo que se tipeo, nunca de lo que hay en la base")
	void el_mensaje_no_filtra_nada() {
		assertThatThrownBy(() -> policy.validar("corta"))
				.hasMessageNotContainingAny("@", "cuenta", "existe");
	}
}
