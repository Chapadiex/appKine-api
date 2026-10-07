package com.akine.identity.infrastructure;

import com.akine.identity.application.PlatformAdminBootstrapService;
import com.akine.identity.application.PlatformAdminBootstrapService.ResultadoBootstrap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PlatformAdminBootstrapRunnerTest {

	private final PlatformAdminBootstrapService service = mock(PlatformAdminBootstrapService.class);

	@ParameterizedTest
	@EnumSource(ResultadoBootstrap.class)
	@DisplayName("pasa la propiedad al servicio y registra cualquier resultado sin lanzar")
	void delega_y_registra(ResultadoBootstrap resultado) {
		given(service.ejecutar("ops@ejemplo.test")).willReturn(resultado);

		assertThatCode(() -> new PlatformAdminBootstrapRunner(service, "ops@ejemplo.test").run(null))
				.doesNotThrowAnyException();
		verify(service).ejecutar("ops@ejemplo.test");
	}

	@Test
	@DisplayName("un fallo del bootstrap no tumba el arranque")
	void un_fallo_no_tumba_el_arranque() {
		given(service.ejecutar("ops@ejemplo.test"))
				.willThrow(new OptimisticLockingFailureException("otra instancia gano"));

		assertThatCode(() -> new PlatformAdminBootstrapRunner(service, "ops@ejemplo.test").run(null))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("el email se loguea enmascarado")
	void enmascara() {
		assertThat(PlatformAdminBootstrapRunner.enmascarar(" juana@akine.app ")).isEqualTo("j***@akine.app");
		assertThat(PlatformAdminBootstrapRunner.enmascarar("sin-arroba")).isEqualTo("***");
		assertThat(PlatformAdminBootstrapRunner.enmascarar("@dominio")).isEqualTo("***");
		assertThat(PlatformAdminBootstrapRunner.enmascarar(null)).isEqualTo("(sin email)");
		assertThat(PlatformAdminBootstrapRunner.enmascarar(" ")).isEqualTo("(sin email)");
	}
}
