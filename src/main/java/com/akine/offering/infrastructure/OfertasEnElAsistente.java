package com.akine.offering.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.EntradaDeHerramienta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.listado.TextoDeBusqueda;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code buscar_oferta}: las ofertas activas de la sede por nombre, con su precio particular vigente.
 *
 * <p>La lectura de ofertas del modulo ({@code OfertaService}) exige {@code consultorio:manage}, que es de
 * quien configura la sede; aca se pide {@code turno:read}, el permiso de quien busca un turno contra la
 * oferta.
 */
@Component
class OfertasEnElAsistente implements HerramientaDelAgente {

	private static final String PERMISO = "turno:read";
	private static final int TOPE = 50;

	private final OfertaDirectory ofertas;
	private final PermissionGuard permissionGuard;

	OfertasEnElAsistente(OfertaDirectory ofertas, PermissionGuard permissionGuard) {
		this.ofertas = ofertas;
		this.permissionGuard = permissionGuard;
	}

	@Override
	public String nombre() {
		return "buscar_oferta";
	}

	@Override
	public String descripcion() {
		return "Busca las ofertas (servicios) activas de la sede por nombre, sin importar tildes ni mayusculas, "
				+ "y devuelve id, nombre, precio particular vigente hoy y si admite obra social. Sin texto "
				+ "lista todas. No devuelve aranceles de obras sociales.";
	}

	@Override
	public String esquemaEntrada() {
		return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
				+ "\"texto\":{\"type\":\"string\",\"description\":\"Parte del nombre de la oferta; opcional\"}}}";
	}

	@Override
	public ResultadoDeHerramienta ejecutar(
			ActorDelAsistente actor, Map<String, Object> entrada, ContextoDeConsulta ctx) {
		if (actor.organizationId() == null || actor.consultorioId() == null) {
			return ResultadoDeHerramienta.denegado();
		}
		long org = actor.organizationId();
		long sede = actor.consultorioId();
		permissionGuard.requirePermission(
				new PermissionQuery(actor.accountId(), PERMISO, org, sede, null, Instant.now()));

		String texto = EntradaDeHerramienta.texto(entrada, "texto");
		String buscado = texto == null ? null : TextoDeBusqueda.plegar(texto);
		List<OfertaSnapshot> encontradas = ofertas.activasDeLaSede(org, sede).stream()
				.filter(o -> buscado == null || TextoDeBusqueda.plegar(o.nombreComercial()).contains(buscado))
				.sorted(Comparator.comparing(OfertaSnapshot::nombreComercial, String.CASE_INSENSITIVE_ORDER))
				.toList();
		List<String> avisos = encontradas.size() > TOPE
				? List.of("Hay " + encontradas.size() + " resultados; muestro los primeros " + TOPE)
				: List.of();
		List<Map<String, Object>> filas = encontradas.stream().limit(TOPE).map(o -> {
			PrecioDeOferta precio = ofertas.precioVigenteEl(org, sede, o.id(), ctx.hoy()).orElse(null);
			Map<String, Object> fila = new LinkedHashMap<>();
			fila.put("oferta_id", o.id());
			fila.put("nombre", o.nombreComercial());
			fila.put("precio_particular", precio == null ? null : precio.precioBase());
			fila.put("moneda", precio == null ? null : precio.moneda());
			fila.put("admite_obra_social", precio != null && precio.admiteObraSocial());
			return fila;
		}).toList();
		return new ResultadoDeHerramienta(filas, avisos, null, false);
	}
}
