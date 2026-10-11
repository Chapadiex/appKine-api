package com.akine.platform.spi.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * La IP del cliente que origino el request, resuelta con la misma regla que usa el rate limit.
 *
 * <p>Existe para que otros modulos —hoy {@code identity}, que guarda la IP en cada sesion— no
 * lean {@code getRemoteAddr()} por su cuenta: detras del nginx de la imagen del frontend esa es la
 * IP del proxy para <b>todos</b> los requests. La implementacion vive en
 * {@code platform.infrastructure.security} y la configura {@code akine.security.rate-limit.trusted-proxies}
 * ({@code AKINE_TRUSTED_PROXIES}).
 *
 * <p>Sin proxies de confianza declarados —el default— devuelve la IP del socket, y
 * {@code X-Forwarded-For} se ignora: esa cabecera la escribe el cliente. Solo cuando la conexion
 * llega desde un proxy declarado se lee la cabecera, de derecha a izquierda.
 */
public interface IpDeCliente {

	/** La IP del cliente, nunca {@code null} si el request tiene direccion remota. */
	String resolver(HttpServletRequest request);
}
