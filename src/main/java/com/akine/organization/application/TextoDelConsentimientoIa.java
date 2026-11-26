package com.akine.organization.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * El texto del consentimiento de IA y su version. Viajan juntos: cambiar el texto de
 * {@code ia/consentimiento.md} y subir {@code akine.ia.consentimiento.version} van en el mismo
 * despliegue, y todo centro vuelve a aceptar. Version vacia = no se exige (solo en tests).
 */
@Component
public class TextoDelConsentimientoIa {

	private static final String RECURSO = "ia/consentimiento.md";

	private final String version;
	private final String texto;

	public TextoDelConsentimientoIa(@Value("${akine.ia.consentimiento.version:}") String version) {
		this.version = version == null ? "" : version.trim();
		if (this.version.length() > 32) {
			// La columna es varchar(32): una version mas larga dejaria la IA frenada para siempre.
			throw new IllegalStateException("akine.ia.consentimiento.version no puede superar los 32 caracteres");
		}
		this.texto = leer();
	}

	/** Version vigente; vacia si el despliegue no exige consentimiento. */
	public String version() {
		return version;
	}

	public boolean exigido() {
		return !version.isEmpty();
	}

	/** El texto sin el comentario de borrador del encabezado. */
	public String texto() {
		return texto;
	}

	private static String leer() {
		try (var in = new ClassPathResource(RECURSO).getInputStream()) {
			String crudo = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			return crudo.replaceAll("(?s)<!--.*?-->", "").strip();
		} catch (IOException e) {
			throw new UncheckedIOException("No se pudo leer " + RECURSO, e);
		}
	}
}
