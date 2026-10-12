package com.akine.platform.infrastructure.pedido;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

import com.akine.platform.spi.pedido.MemoriaDelPedido;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Delimita la {@link MemoriaDelPedido}: la abre al entrar el pedido y la descarta al salir,
 * pase lo que pase.
 *
 * <p>Corre antes de la cadena de Spring Security, porque los filtros de autenticacion y de
 * contexto son los primeros en leer membership y rol de plataforma, y lo que leen ahi es lo que
 * la evaluacion de permisos reutiliza despues. El despacho de error no abre alcance propio: lee
 * de la base, como cualquier codigo fuera de un pedido.
 */
public class AlcanceDelPedidoFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		try (MemoriaDelPedido.Alcance alcance = MemoriaDelPedido.abrir()) {
			filterChain.doFilter(request, response);
		}
	}
}
