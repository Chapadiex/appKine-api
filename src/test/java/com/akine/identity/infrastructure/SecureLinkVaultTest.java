package com.akine.identity.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Traspaso efimero del enlace entre quien encola y quien envia.
 *
 * <p>Lo que se verifica aca no es una comodidad sino una propiedad de seguridad: el enlace se
 * entrega UNA sola vez y no sobrevive a su vencimiento. Un mapa que retuviera credenciales
 * seria a la vez una fuga de memoria y una bolsa de llaves vivas en el heap.
 */
class SecureLinkVaultTest {

	private static final String REFERENCIA = "500";
	private static final String ENLACE = "https://app.akine.test/activar?token=abc";

	private static SecureLinkVault vault(Duration ttl) {
		IdentityProperties properties = new IdentityProperties();
		properties.getLinks().setTtl(ttl);
		return new SecureLinkVault(properties);
	}

	@Test
	@DisplayName("el enlace se entrega una vez y despues ya no esta")
	void el_enlace_se_entrega_una_sola_vez() {
		SecureLinkVault vault = vault(Duration.ofMinutes(10));
		Instant ahora = Instant.now();

		vault.guardar(REFERENCIA, ENLACE, ahora);

		assertThat(vault.tomar(REFERENCIA, ahora)).contains(ENLACE);
		assertThat(vault.tomar(REFERENCIA, ahora)).isEmpty();
		assertThat(vault.pendientes()).isZero();
	}

	@Test
	@DisplayName("una referencia que nadie guardo devuelve vacio, no una excepcion")
	void una_referencia_desconocida_devuelve_vacio() {
		SecureLinkVault vault = vault(Duration.ofMinutes(10));

		// Es el caso del reinicio del proceso: el enlace se perdio y la notificacion queda
		// fallida con motivo, que es preferible a persistir la credencial para sobrevivirlo.
		assertThat(vault.tomar("no-existe", Instant.now())).isEmpty();
		assertThat(vault.tomar(null, Instant.now())).isEmpty();
	}

	@Test
	@DisplayName("un enlace vencido se descarta y no se entrega")
	void un_enlace_vencido_se_descarta() {
		SecureLinkVault vault = vault(Duration.ofMinutes(10));
		Instant emision = Instant.now();

		vault.guardar(REFERENCIA, ENLACE, emision);

		assertThat(vault.tomar(REFERENCIA, emision.plus(Duration.ofMinutes(11)))).isEmpty();
		assertThat(vault.pendientes())
				.as("el vencido tiene que salir del mapa, no quedarse ocupando memoria")
				.isZero();
	}

	@Test
	@DisplayName("guardar dos veces la misma referencia deja la ultima emision")
	void la_ultima_emision_pisa_a_la_anterior() {
		SecureLinkVault vault = vault(Duration.ofMinutes(10));
		Instant ahora = Instant.now();

		vault.guardar(REFERENCIA, ENLACE, ahora);
		vault.guardar(REFERENCIA, ENLACE + "-nuevo", ahora);

		// Es el mismo criterio que en la base: emitir un token nuevo invalida los anteriores.
		assertThat(vault.tomar(REFERENCIA, ahora)).contains(ENLACE + "-nuevo");
	}

	@Test
	@DisplayName("guardar sin referencia o sin enlace no hace nada")
	void guardar_incompleto_no_hace_nada() {
		SecureLinkVault vault = vault(Duration.ofMinutes(10));

		vault.guardar(null, ENLACE, Instant.now());
		vault.guardar(REFERENCIA, null, Instant.now());

		assertThat(vault.pendientes()).isZero();
	}
}
