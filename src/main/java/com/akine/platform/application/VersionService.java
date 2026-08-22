package com.akine.platform.application;

import com.akine.platform.domain.BuildVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Expone la identidad tecnica del backend.
 *
 * <p>Capa {@code application}: no conoce HTTP. Recibe configuracion y devuelve dominio.
 */
@Service
public class VersionService {

	private final BuildVersion buildVersion;

	public VersionService(
			@Value("${spring.application.name}") String applicationName,
			@Value("${akine.application.version}") String applicationVersion,
			@Value("${akine.contract.version}") String contractVersion) {
		this.buildVersion = new BuildVersion(applicationName, applicationVersion, contractVersion);
	}

	public BuildVersion current() {
		return buildVersion;
	}
}
