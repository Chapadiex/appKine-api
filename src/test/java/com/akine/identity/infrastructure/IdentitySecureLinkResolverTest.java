package com.akine.identity.infrastructure;

import com.akine.identity.domain.TipoTokenVerificacion;
import com.akine.identity.domain.TokenVerificacion;
import com.akine.notification.spi.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static com.akine.identity.IdentityFixtures.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Reconstruccion del enlace en el momento del envio.
 *
 * <p>Las dos comprobaciones que hace tienen consecuencia de seguridad y estan testeadas por
 * separado: que el token siga vivo, y que el tipo del correo coincida con el tipo del token. Sin
 * la segunda, un correo de activacion podria entregar un enlace de reset, que es un camino de
 * toma de cuenta que esquiva el flujo que revoca sesiones.
 */
@ExtendWith(MockitoExtension.class)
class IdentitySecureLinkResolverTest {

	private static final String ENLACE = "https://app.akine.test/activar?token=abc";

	@Mock
	private TokenVerificacionRepository tokenRepository;

	private final SecureLinkVault vault = vaultDePrueba();

	private static SecureLinkVault vaultDePrueba() {
		IdentityProperties properties = new IdentityProperties();
		properties.getLinks().setTtl(Duration.ofMinutes(10));
		return new SecureLinkVault(properties);
	}

	private IdentitySecureLinkResolver resolver() {
		return new IdentitySecureLinkResolver(tokenRepository, vault);
	}

	@Test
	@DisplayName("con el token vivo y el enlace disponible, devuelve el enlace")
	void con_el_token_vivo_devuelve_el_enlace() {
		given(tokenRepository.findById(500L))
				.willReturn(Optional.of(token(TipoTokenVerificacion.ACTIVACION, "plano")));
		vault.guardar("500", ENLACE, Instant.now());

		assertThat(resolver().resolveLink(NotificationType.ACTIVACION_CUENTA, "500"))
				.contains(ENLACE);
	}

	@Test
	@DisplayName("un token ya consumido no entrega enlace")
	void un_token_consumido_no_entrega_enlace() {
		TokenVerificacion consumido = token(TipoTokenVerificacion.ACTIVACION, "plano");
		consumido.consumir(Instant.now());
		given(tokenRepository.findById(500L)).willReturn(Optional.of(consumido));
		vault.guardar("500", ENLACE, Instant.now());

		assertThat(resolver().resolveLink(NotificationType.ACTIVACION_CUENTA, "500")).isEmpty();
		assertThat(vault.pendientes())
				.as("el enlace no se consume cuando el token ya no sirve")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("el tipo del correo tiene que coincidir con el tipo del token")
	void el_tipo_tiene_que_coincidir() {
		given(tokenRepository.findById(500L))
				.willReturn(Optional.of(token(TipoTokenVerificacion.RESET, "plano")));
		vault.guardar("500", ENLACE, Instant.now());

		// Un correo de activacion pidiendo el enlace de un token de reset: no se entrega.
		assertThat(resolver().resolveLink(NotificationType.ACTIVACION_CUENTA, "500")).isEmpty();
	}

	@Test
	@DisplayName("un token inexistente no entrega enlace")
	void un_token_inexistente_no_entrega_enlace() {
		given(tokenRepository.findById(500L)).willReturn(Optional.empty());

		assertThat(resolver().resolveLink(NotificationType.RECUPERACION_PASSWORD, "500")).isEmpty();
	}

	@Test
	@DisplayName("si el enlace ya no esta en memoria, no se puede reconstruir")
	void sin_enlace_en_memoria_no_hay_reconstruccion() {
		given(tokenRepository.findById(500L))
				.willReturn(Optional.of(token(TipoTokenVerificacion.ACTIVACION, "plano")));

		// La base guarda el SHA-256 del token y de un digest no se vuelve: el correo queda
		// fallido y la persona pide uno nuevo.
		assertThat(resolver().resolveLink(NotificationType.ACTIVACION_CUENTA, "500")).isEmpty();
	}

	@Test
	@DisplayName("los tipos sin enlace y las referencias invalidas devuelven vacio sin consultar")
	void los_tipos_sin_enlace_devuelven_vacio() {
		IdentitySecureLinkResolver resolver = resolver();

		assertThat(resolver.resolveLink(NotificationType.CUENTA_YA_REGISTRADA, "500")).isEmpty();
		assertThat(resolver.resolveLink(NotificationType.INVITACION_COLABORADOR, "500")).isEmpty();
		assertThat(resolver.resolveLink(null, "500")).isEmpty();
		assertThat(resolver.resolveLink(NotificationType.ACTIVACION_CUENTA, null)).isEmpty();
		assertThat(resolver.resolveLink(NotificationType.ACTIVACION_CUENTA, "no-es-un-id")).isEmpty();
	}
}
