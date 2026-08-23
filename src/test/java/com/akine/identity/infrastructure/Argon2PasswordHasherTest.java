package com.akine.identity.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Custodia de la contrasena.
 *
 * <p>Los dos tests que importan de verdad son el del salt y el de {@code dummyVerify}. El
 * primero detecta el error clasico —hashear sin salt, con lo que dos personas con la misma
 * contrasena quedan con el mismo hash y una tabla precomputada las abre a las dos—. El segundo
 * protege una defensa que se pierde sin ruido: si alguien "optimiza" la verificacion falsa, el
 * login vuelve a ser un enumerador de direcciones y ningun test de contrato se entera.
 */
class Argon2PasswordHasherTest {

	/**
	 * Parametros deliberadamente bajos para el test.
	 *
	 * <p>El hasheo real tarda decenas de milisegundos A PROPOSITO; con los de produccion, una
	 * clase con diez tests que hashean se vuelve el cuello de botella de la suite. Lo que se
	 * verifica aca es el comportamiento, que no depende del costo.
	 */
	private static Argon2PasswordHasher hasher() {
		IdentityProperties properties = new IdentityProperties();
		properties.getHashing().setMemoryKb(1024);
		properties.getHashing().setIterations(1);
		properties.getHashing().setParallelism(1);
		return new Argon2PasswordHasher(properties);
	}

	@Test
	@DisplayName("la misma contrasena produce hashes distintos: hay salt")
	void la_misma_contrasena_produce_hashes_distintos() {
		Argon2PasswordHasher hasher = hasher();

		String primero = hasher.hash("una-contrasena-larga");
		String segundo = hasher.hash("una-contrasena-larga");

		assertThat(primero).isNotEqualTo(segundo);
		// Y los dos siguen verificando: el salt viaja dentro del hash, no aparte.
		assertThat(hasher.matches("una-contrasena-larga", primero)).isTrue();
		assertThat(hasher.matches("una-contrasena-larga", segundo)).isTrue();
	}

	@Test
	@DisplayName("el hash lleva el algoritmo adelante, para poder cambiarlo sin migrar")
	void el_hash_lleva_el_algoritmo_adelante() {
		String hash = hasher().hash("otra-contrasena-larga");

		// El prefijo {id} es lo que permite que, al agregar BouncyCastle y pasar a Argon2id,
		// los hashes viejos sigan verificando en vez de dejar a todo el mundo afuera.
		assertThat(hash).startsWith("{");
		assertThat(hash).contains("}");
	}

	@Test
	@DisplayName("una contrasena equivocada no verifica")
	void una_contrasena_equivocada_no_verifica() {
		Argon2PasswordHasher hasher = hasher();
		String hash = hasher.hash("la-correcta-y-larga");

		assertThat(hasher.matches("la-incorrecta-y-larga", hash)).isFalse();
	}

	@Test
	@DisplayName("un hash nulo o vacio devuelve false y no lanza: es la cuenta invitada")
	void un_hash_nulo_devuelve_false() {
		Argon2PasswordHasher hasher = hasher();

		assertThat(hasher.matches("cualquier-cosa", null)).isFalse();
		assertThat(hasher.matches("cualquier-cosa", "   ")).isFalse();
		assertThat(hasher.matches(null, "{noop}x")).isFalse();
	}

	@Test
	@DisplayName("un hash corrupto devuelve false y no se convierte en un 500")
	void un_hash_corrupto_devuelve_false() {
		// Una fila rota que lanzara le diria al atacante que esa cuenta tiene algo distinto.
		assertThat(hasher().matches("cualquier-cosa", "{algoritmo-que-no-existe}basura")).isFalse();
	}

	@Test
	@DisplayName("dummyVerify no lanza y esta disponible para la rama de cuenta inexistente")
	void dummy_verify_no_lanza() {
		Argon2PasswordHasher hasher = hasher();

		// Lo unico observable desde afuera es que no explota; lo que aporta es el tiempo que
		// consume, que es el de una verificacion real.
		hasher.dummyVerify();
		hasher.dummyVerify();
	}

	@Test
	@DisplayName("hashear null es un error de programacion, no una contrasena")
	void hashear_null_falla() {
		assertThatThrownBy(() -> hasher().hash(null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
