package com.akine.platform.infrastructure.modelo.agente;

import com.akine.platform.spi.modelo.agente.ConfiguracionDelAgente;

/** {@link ConfiguracionDelAgente} leida de {@link AgenteProperties}. Existe con el asistente prendido o apagado. */
class ConfiguracionDelAgenteDesdePropiedades implements ConfiguracionDelAgente {

	private final AgenteProperties properties;

	ConfiguracionDelAgenteDesdePropiedades(AgenteProperties properties) {
		this.properties = properties;
	}

	@Override
	public ProveedorConfigurado principal() {
		return describir(properties.proveedor());
	}

	@Override
	public ProveedorConfigurado alternativo() {
		String alternativo = properties.alternativo();
		if (alternativo.isBlank() || alternativo.strip().equals(properties.proveedor().strip())) {
			return null;
		}
		return describir(alternativo);
	}

	private ProveedorConfigurado describir(String nombre) {
		String n = nombre == null ? "" : nombre.strip();
		return switch (n) {
			case "claude" -> new ProveedorConfigurado(n, properties.claude().modelo(),
					!properties.claude().apiKey().isEmpty());
			case "compatible" -> new ProveedorConfigurado(n,
					properties.compatible().modelo().isBlank() ? null : properties.compatible().modelo(),
					!properties.compatible().apiKey().isEmpty());
			default -> new ProveedorConfigurado(n, null, false);
		};
	}
}
