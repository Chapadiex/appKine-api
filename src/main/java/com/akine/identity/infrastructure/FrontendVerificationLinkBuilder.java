package com.akine.identity.infrastructure;

import com.akine.identity.domain.TipoTokenVerificacion;
import com.akine.identity.domain.port.VerificationLinkBuilder;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Arma el enlace que recibe la persona por correo, apuntando al frontend.
 *
 * <p>La base y las rutas vienen de {@link IdentityProperties}: son del entorno —local,
 * staging, produccion— y por eso el constructor del enlace es un puerto y no una constante.
 *
 * <p><b>El enlace apunta al frontend, no al backend.</b> La pantalla que consume el token
 * necesita pedir la contrasena nueva antes de llamar al endpoint; un enlace directo al API
 * consumiria el token de un solo uso con el primer GET —incluido el que hace el prefetch del
 * cliente de correo— y la persona llegaria a un token ya gastado.
 *
 * <p>El token se codifica para URL. No hace falta con el alfabeto Base64-url que produce
 * {@code RandomTokenGenerator}, pero el builder no puede asumir quien lo llama: un token con
 * un {@code +} o un {@code &} sin escapar produciria un enlace que llega roto y un soporte
 * imposible de diagnosticar.
 */
@Component
public class FrontendVerificationLinkBuilder implements VerificationLinkBuilder {

	private final IdentityProperties.Links links;

	public FrontendVerificationLinkBuilder(IdentityProperties properties) {
		this.links = properties.getLinks();
	}

	@Override
	public String enlaceDe(TipoTokenVerificacion tipo, String tokenPlano) {
		if (tipo == null) {
			throw new IllegalArgumentException("El tipo de token es obligatorio para armar el enlace");
		}
		if (tokenPlano == null || tokenPlano.isBlank()) {
			throw new IllegalArgumentException("No se puede armar un enlace sin token");
		}

		String base = sinBarraFinal(links.getBaseUrl());
		String ruta = conBarraInicial(rutaDe(tipo));
		String token = URLEncoder.encode(tokenPlano, StandardCharsets.UTF_8);

		return base + ruta + "?" + links.getTokenParam() + "=" + token;
	}

	/**
	 * Ruta del frontend segun el tipo.
	 *
	 * <p>El {@code switch} es exhaustivo sobre el enum: si manana aparece un tercer tipo de
	 * token, esto deja de compilar en vez de mandar a la gente a la pantalla equivocada.
	 */
	private String rutaDe(TipoTokenVerificacion tipo) {
		return switch (tipo) {
			case ACTIVACION -> links.getActivationPath();
			case RESET -> links.getResetPath();
			case INVITACION -> links.getInvitationPath();
		};
	}

	private static String sinBarraFinal(String valor) {
		String limpio = valor == null ? "" : valor.strip();
		return limpio.endsWith("/") ? limpio.substring(0, limpio.length() - 1) : limpio;
	}

	private static String conBarraInicial(String valor) {
		String limpio = valor == null ? "" : valor.strip();
		return limpio.startsWith("/") ? limpio : "/" + limpio;
	}
}
