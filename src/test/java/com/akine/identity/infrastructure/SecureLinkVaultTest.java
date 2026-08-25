package com.akine.identity.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Traspaso efimero del enlace entre quien encola y quien envia.
 *
 * <p>Lo que se verifica aca son dos propiedades en tension. La de seguridad: el enlace se borra
 * cuando el envio termina y no sobrevive a su vencimiento —un mapa que retuviera credenciales
 * seria a la vez una fuga de memoria y una bolsa de llaves vivas en el heap—. Y la de
 * disponibilidad: leerlo NO lo borra, porque el outbox lo resuelve antes de enviar y el
 * reintento tiene que encontrarlo. Consumir es un paso aparte.
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
	@DisplayName("leer no consume: el enlace sigue disponible para el reintento")
	void leer_no_consume() {
		SecureLinkVault vault = vault(Duration.ofMinutes(10));
		Instant ahora = Instant.now();

		vault.guardar(REFERENCIA, ENLACE, ahora);

		// Si leer borrara, el segundo intento del outbox —el que existe porque el relay se
		// cayo— se quedaria sin enlace y la fila moriria por "token invalido" con el token vivo.
		assertThat(vault.leer(REFERENCIA, ahora)).contains(ENLACE);
		assertThat(vault.leer(REFERENCIA, ahora)).contains(ENLACE);
		assertThat(vault.pendientes()).isEqualTo(1);
	}

	@Test
	@DisplayName("consumir borra el enlace y es idempotente")
	void consumir_borra_y_es_idempotente() {
		SecureLinkVault vault = vault(Duration.ofMinutes(10));
		Instant ahora = Instant.now();
		vault.guardar(REFERENCIA, ENLACE, ahora);

		vault.consumir(REFERENCIA);

		assertThat(vault.leer(REFERENCIA, ahora)).isEmpty();
		assertThat(vault.pendientes()).isZero();

		// Reprocesar un resultado ya escrito no puede ser un error.
		vault.consumir(REFERENCIA);
		vault.consumir("no-existe");
		vault.consumir(null);
		assertThat(vault.pendientes()).isZero();
	}

	@Test
	@DisplayName("una referencia que nadie guardo devuelve vacio, no una excepcion")
	void una_referencia_desconocida_devuelve_vacio() {
		SecureLinkVault vault = vault(Duration.ofMinutes(10));

		// Es el caso del reinicio del proceso: el enlace se perdio y la notificacion queda
		// fallida con motivo, que es preferible a persistir la credencial para sobrevivirlo.
		assertThat(vault.leer("no-existe", Instant.now())).isEmpty();
		assertThat(vault.leer(null, Instant.now())).isEmpty();
	}

	@Test
	@DisplayName("un enlace vencido se descarta y no se entrega")
	void un_enlace_vencido_se_descarta() {
		SecureLinkVault vault = vault(Duration.ofMinutes(10));
		Instant emision = Instant.now();

		vault.guardar(REFERENCIA, ENLACE, emision);

		assertThat(vault.leer(REFERENCIA, emision.plus(Duration.ofMinutes(11)))).isEmpty();
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
		assertThat(vault.leer(REFERENCIA, ahora)).contains(ENLACE + "-nuevo");
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
