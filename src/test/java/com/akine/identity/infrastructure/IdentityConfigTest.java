package com.akine.identity.infrastructure;

import java.time.Duration;
import java.time.Instant;

import com.akine.identity.domain.SessionSettings;
import com.akine.identity.domain.port.IdentityClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El cableado del modulo.
 *
 * <p>Poco codigo y una garantia concreta: que la vigencia de la sesion que usa
 * {@code SessionService} sea <b>la configurada</b> y no un default escondido en el servicio.
 * Es donde la configuracion cruza de {@code infrastructure} a {@code domain}, que es el unico
 * sentido en el que ArchUnit permite que crucen.
 */
class IdentityConfigTest {

	@Test
	@DisplayName("la vigencia de la sesion sale de la configuracion, no de una constante oculta")
	void la_vigencia_sale_de_la_configuracion() {
		IdentityProperties properties = new IdentityProperties();
		properties.getSession().setRefreshTtl(Duration.ofHours(4));

		SessionSettings settings = new IdentityConfig().sessionSettings(properties);

		assertThat(settings.refreshTtl()).isEqualTo(Duration.ofHours(4));
	}

	@Test
	@DisplayName("sin configurar nada, el modulo arranca con la jornada de un consultorio")
	void el_default_es_la_jornada() {
		SessionSettings settings = new IdentityConfig().sessionSettings(new IdentityProperties());

		assertThat(settings.refreshTtl()).isEqualTo(Duration.ofHours(12));
	}

	@Test
	@DisplayName("el reloj del modulo avanza con el del sistema")
	void el_reloj_avanza() {
		IdentityClock clock = new IdentityConfig().identityClock();

		Instant antes = Instant.now().minusSeconds(1);
		assertThat(clock.now()).isAfter(antes);
	}
}
