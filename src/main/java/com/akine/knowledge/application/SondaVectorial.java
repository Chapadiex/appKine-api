package com.akine.knowledge.application;

import com.akine.knowledge.domain.port.AlmacenVectorial;
import com.akine.knowledge.domain.port.AlmacenVectorial.Coincidencia;
import com.akine.knowledge.domain.port.ModeloEmbeddings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link SondaDeEvidencia} sobre el almacen vectorial: el mejor coseno de la consulta entre los
 * fragmentos de la organizacion (con los mismos filtros de libro activo, LISTO y no sospechoso que
 * la busqueda). Sin modelo de embeddings o sin fragmentos, {@code -infinito}: sin evidencia.
 */
@Component
public class SondaVectorial implements SondaDeEvidencia {

	private final AlmacenVectorial almacen;
	private final ObjectProvider<ModeloEmbeddings> modelo;

	public SondaVectorial(AlmacenVectorial almacen, ObjectProvider<ModeloEmbeddings> modelo) {
		this.almacen = almacen;
		this.modelo = modelo;
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
