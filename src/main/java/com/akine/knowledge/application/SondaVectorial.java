package com.akine.knowledge.application;

import com.akine.knowledge.domain.port.AlmacenVectorial;
import com.akine.knowledge.domain.port.AlmacenVectorial.Coincidencia;
import com.akine.knowledge.domain.port.ModeloEmbeddings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * {@link SondaDeEvidencia} sobre el almacen vectorial: el mejor coseno de la consulta entre los
 * fragmentos de la organizacion (con los mismos filtros de libro activo, LISTO y no sospechoso que
 * la busqueda). Sin modelo de embeddings o sin fragmentos, {@code -infinito}: sin evidencia.
 */
@Component
public class SondaVectorial implements SondaDeEvidencia {

	private final AlmacenVectorial almacen;
	private final ObjectProvider<ModeloEmbeddings> modelo;
	private final BusquedaHibrida busqueda;

	public SondaVectorial(AlmacenVectorial almacen, ObjectProvider<ModeloEmbeddings> modelo, BusquedaHibrida busqueda) {
		this.almacen = almacen;
		this.modelo = modelo;
		this.busqueda = busqueda;
	}

	@Override
	public boolean nombraElTema(long orgId, String tema) {
		return busqueda.buscar(orgId, List.of(tema), tema, Set.of()).hayEvidencia();
	}

	@Override
	public double mejorPuntaje(long orgId, String consulta) {
		ModeloEmbeddings m = modelo.getIfAvailable();
		if (m == null) {
			return Double.NEGATIVE_INFINITY;
		}
		List<Coincidencia> mejores = almacen.porVector(orgId, m.id(), m.consulta(consulta), 1);
		return mejores.isEmpty() ? Double.NEGATIVE_INFINITY : mejores.getFirst().puntaje().doubleValue();
	}
}
