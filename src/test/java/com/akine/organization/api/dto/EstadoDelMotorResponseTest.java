package com.akine.organization.api.dto;

import com.akine.platform.spi.modelo.MotorDeModelos;
import com.akine.platform.spi.modelo.agente.ConfiguracionDelAgente;
import com.akine.platform.spi.modelo.agente.ConfiguracionDelAgente.ProveedorConfigurado;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EstadoDelMotorResponseTest {

	private static final MotorDeModelos.EstadoDelMotor ESTADO = new MotorDeModelos.EstadoDelMotor(true,
			"http://ollama:11434", null, List.of(new MotorDeModelos.ModeloDelMotor("qwen3:4b-instruct",
					List.of("ASISTENTE"), true, true, 4200, 4200)));

	private static ConfiguracionDelAgente agente(ProveedorConfigurado principal, ProveedorConfigurado alternativo) {
		return new ConfiguracionDelAgente() {
			@Override
			public ProveedorConfigurado principal() {
				return principal;
			}

			@Override
			public ProveedorConfigurado alternativo() {
				return alternativo;
			}
		};
	}

	@Test
	void mapea_los_modelos_y_no_publica_la_url() {
		EstadoDelMotorResponse r = EstadoDelMotorResponse.from(ESTADO, null);

		assertThat(r.alcanzable()).isTrue();
		assertThat(r.url()).isNull();
		assertThat(r.modelos()).singleElement().satisfies(m -> {
			assertThat(m.nombre()).isEqualTo("qwen3:4b-instruct");
			assertThat(m.funciones()).containsExactly("ASISTENTE");
			assertThat(m.cargado()).isTrue();
			assertThat(m.memoriaGpuMb()).isEqualTo(4200);
			assertThat(m.ejecucion()).isEqualTo("GPU");
		});
		assertThat(r.proveedorAsistente()).isNull();
	}

	@Test
	void sin_principal_no_hay_proveedor() {
		assertThat(EstadoDelMotorResponse.from(ESTADO, agente(null, null)).proveedorAsistente()).isNull();
	}

	@Test
	void principal_sin_alternativo() {
		EstadoDelMotorResponse.ProveedorDelAsistenteResponse p = EstadoDelMotorResponse.from(ESTADO,
				agente(new ProveedorConfigurado("claude", "claude-opus-5-5", true), null)).proveedorAsistente();

		assertThat(p.nombre()).isEqualTo("claude");
		assertThat(p.modelo()).isEqualTo("claude-opus-5-5");
		assertThat(p.tieneKey()).isTrue();
		assertThat(p.alternativo()).isNull();
	}

	@Test
	void principal_con_alternativo() {
		EstadoDelMotorResponse.ProveedorDelAsistenteResponse p = EstadoDelMotorResponse.from(ESTADO,
				agente(new ProveedorConfigurado("claude", null, false),
						new ProveedorConfigurado("compatible", "qwen3:4b-instruct", false))).proveedorAsistente();

		assertThat(p.alternativo().nombre()).isEqualTo("compatible");
		assertThat(p.alternativo().modelo()).isEqualTo("qwen3:4b-instruct");
		assertThat(p.alternativo().tieneKey()).isFalse();
	}
}
