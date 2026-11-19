package com.akine.identity.application;

import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.AltaDeCentro;
import com.akine.organization.spi.Servicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AltaDeCentroPlataformaServiceTest {

	@Test
	@DisplayName("sin correo del duenio no se crea nada")
	void sin_correo_no_crea_nada() {
		AltaDeCentro altaDeCentro = mock(AltaDeCentro.class);
		ColaboradorInvitacionService invitaciones = mock(ColaboradorInvitacionService.class);
		AltaDeCentroPlataformaService service = new AltaDeCentroPlataformaService(altaDeCentro, invitaciones);
		AltaDeCentro.Comando comando = new AltaDeCentro.Comando("Centro", null, null, "BASE",
				EnumSet.of(Servicio.KINESIOLOGIA), EnumSet.noneOf(Adicional.class), true, null, 1L);

		assertThatThrownBy(() -> service.darDeAlta(comando, "  ")).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(altaDeCentro, invitaciones);
	}
}
