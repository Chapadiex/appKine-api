package com.akine.knowledge.application;

import com.akine.knowledge.domain.BibliotecaAjustes;
import com.akine.knowledge.domain.BusquedaAjustes;
import com.akine.knowledge.domain.port.AlmacenVectorial;
import com.akine.knowledge.domain.port.AlmacenVectorial.Coincidencia;
import com.akine.knowledge.domain.port.AlmacenVectorial.FragmentoEncontrado;
import com.akine.knowledge.domain.port.ModeloEmbeddings;
import com.akine.knowledge.spi.BibliotecaDirectory;
import com.akine.knowledge.spi.CaminoBusqueda;
import com.akine.knowledge.spi.PasajeRecuperado;
import com.akine.knowledge.spi.ResultadoBusqueda;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Busqueda en la biblioteca de una organizacion (M30).
 *
 * <p>Camino {@code VECTOR} con el modelo de embeddings vigente; si el modelo no esta o falla al
 * calcular la consulta, camino {@code PALABRAS} (texto completo de MySQL). Cada camino tiene su
 * corte, porque sus puntajes no son comparables.
 *
 * <p>No se audita cada consulta: desde la API es la prueba del administrador sobre su propia
 * biblioteca, sin datos clinicos. Quien use {@link BibliotecaDirectory} con datos de pacientes
 * audita su propio acceso.
 */
@Service
public class BusquedaService implements BibliotecaDirectory {

	private static final Logger log = LoggerFactory.getLogger(BusquedaService.class);

	public static final int K_POR_DEFECTO = 5;
	public static final int K_MAXIMO = 20;
	public static final int CONSULTA_MAXIMA = 600;

	private final BibliotecaAcceso acceso;
	private final BibliotecaAjustes biblioteca;
	private final BusquedaAjustes ajustes;
	private final AlmacenVectorial almacen;
	private final ObjectProvider<ModeloEmbeddings> modelo;

	public BusquedaService(
			BibliotecaAcceso acceso,
			BibliotecaAjustes biblioteca,
			BusquedaAjustes ajustes,
			AlmacenVectorial almacen,
			ObjectProvider<ModeloEmbeddings> modelo) {
		this.acceso = acceso;
		this.biblioteca = biblioteca;
		this.ajustes = ajustes;
		this.almacen = almacen;
		this.modelo = modelo;
	}

	/** Desde la API: interruptor, tenant y {@code biblioteca:manage} primero. */
	public ResultadoBusqueda buscar(OperatingActor actor, long orgId, String consulta, int k) {
		acceso.exigir(actor, orgId);
		return ejecutar(orgId, consulta, k);
	}

	/** Para otros modulos: sin permiso (lo exige quien llama), pero siempre acotada a {@code orgId}. */
	@Override
	public ResultadoBusqueda buscar(long orgId, String consulta, int k) {
		if (orgId <= 0) {
			throw new IllegalArgumentException("La organizacion es obligatoria");
		}
		if (!biblioteca.habilitada()) {
			validar(consulta, k);
			return new ResultadoBusqueda(CaminoBusqueda.PALABRAS,
					ajustes.cortePalabras().doubleValue(), false, List.of());
		}
		return ejecutar(orgId, consulta, k);
	}

	private ResultadoBusqueda ejecutar(long orgId, String consulta, int k) {
		String texto = validar(consulta, k);
		CaminoBusqueda camino = CaminoBusqueda.PALABRAS;
		List<Coincidencia> coincidencias = null;
		ModeloEmbeddings m = modelo.getIfAvailable();
		if (m != null) {
			float[] vector = null;
			try {
				vector = m.consulta(texto);
			} catch (RuntimeException falla) {
				// Solo la clase: el mensaje podria repetir la consulta.
				log.warn("El modelo de embeddings fallo al calcular la consulta ({}); se busca por palabras",
						falla.getClass().getSimpleName());
			}
			if (vector != null) {
				coincidencias = almacen.porVector(orgId, m.id(), vector, k);
				camino = CaminoBusqueda.VECTOR;
			}
		}
		if (coincidencias == null) {
			coincidencias = almacen.porPalabras(orgId, texto, k);
		}
		BigDecimal corte = camino == CaminoBusqueda.VECTOR ? ajustes.corteVector() : ajustes.cortePalabras();
		List<PasajeRecuperado> pasajes = hidratar(orgId, coincidencias, corte);
		long sobreElCorte = pasajes.stream().filter(PasajeRecuperado::superaCorte).count();
		return new ResultadoBusqueda(camino, corte.doubleValue(), sobreElCorte >= ajustes.minPedazos(), pasajes);
	}

	/** Una sola consulta por los ids; conserva el orden de las coincidencias. */
	private List<PasajeRecuperado> hidratar(long orgId, List<Coincidencia> coincidencias, BigDecimal corte) {
		if (coincidencias.isEmpty()) {
			return List.of();
		}
		Map<Long, FragmentoEncontrado> porId = almacen
				.fragmentos(orgId, coincidencias.stream().map(Coincidencia::fragmentoId).toList())
				.stream()
				.collect(Collectors.toMap(FragmentoEncontrado::fragmentoId, Function.identity()));
		List<PasajeRecuperado> pasajes = new ArrayList<>(coincidencias.size());
		for (Coincidencia c : coincidencias) {
			FragmentoEncontrado f = porId.get(c.fragmentoId());
			if (f != null) {
				pasajes.add(new PasajeRecuperado(f.libroId(), f.titulo(), f.paginaDesde(), f.paginaHasta(),
						f.texto(), c.puntaje().doubleValue(), c.puntaje().compareTo(corte) >= 0));
			}
		}
		return pasajes;
	}

	private static String validar(String consulta, int k) {
		String texto = consulta == null ? "" : consulta.strip();
		if (texto.isEmpty()) {
			throw new IllegalArgumentException("La consulta es obligatoria");
		}
		if (texto.length() > CONSULTA_MAXIMA) {
			throw new IllegalArgumentException(
					"La consulta admite hasta " + CONSULTA_MAXIMA + " caracteres");
		}
		if (k < 1 || k > K_MAXIMO) {
			throw new IllegalArgumentException("La cantidad de pasajes (k) va de 1 a " + K_MAXIMO);
		}
		return texto;
	}
}
