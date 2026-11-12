package com.akine.knowledge.application;

import com.akine.knowledge.domain.ReindexadoAjustes;
import com.akine.knowledge.domain.Vectores;
import com.akine.knowledge.domain.port.BibliotecaVersionPort;
import com.akine.knowledge.domain.port.LibroFragmentoRepositoryPort;
import com.akine.knowledge.domain.port.LibroFragmentoRepositoryPort.TextoDeFragmento;
import com.akine.knowledge.domain.port.ModeloEmbeddings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Vectoriza los pedazos que no tienen vector o lo tienen de otro modelo (por ejemplo tras cambiar
 * de modelo). Un lote es UNA transaccion: reclama con SKIP LOCKED, lee los textos, calcula y
 * escribe, asi los locks de fila impiden que dos nodos hagan lo mismo. Si el modelo falla la
 * transaccion se deshace y los pedazos siguen pendientes.
 *
 * <p>Despues del commit, y sin tener ningun lock de fragmento, avanza la version de cada
 * organizacion tocada (el orden de locks es version, luego filas). Nunca loguea texto.
 */
@Service
public class ReindexadoService {

	private final LibroFragmentoRepositoryPort fragmentos;
	private final BibliotecaVersionPort version;
	private final ObjectProvider<ModeloEmbeddings> modelos;
	private final int lote;
	private final TransactionTemplate transaccionLote;
	private final TransactionTemplate transaccion;

	public ReindexadoService(LibroFragmentoRepositoryPort fragmentos, BibliotecaVersionPort version,
			ObjectProvider<ModeloEmbeddings> modelos, ReindexadoAjustes ajustes,
			PlatformTransactionManager transactionManager) {
		this.fragmentos = fragmentos;
		this.version = version;
		this.modelos = modelos;
		this.lote = ajustes.lote();
		this.transaccionLote = new TransactionTemplate(transactionManager);
		this.transaccionLote.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.transaccionLote.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.transaccion = new TransactionTemplate(transactionManager);
	}

	/** Reindexa hasta un lote y devuelve cuantos pedazos quedaron con el vector vigente. */
	public int reindexarLote() {
		return reindexar(1);
	}

	/**
	 * Reindexa hasta {@code maxLotes} lotes (para antes si uno vuelve vacio) y despues avanza la
	 * version de cada organizacion tocada UNA vez, no una por lote: cada avance invalida la cache
	 * de busqueda de esa organizacion. Si un lote falla, las organizaciones de los lotes ya
	 * confirmados avanzan igual antes de propagar.
	 *
	 * @return pedazos que quedaron con el vector vigente
	 */
	public int reindexar(int maxLotes) {
		ModeloEmbeddings modelo = modelos.getIfAvailable();
		if (modelo == null) {
			return 0;
		}
		Set<Long> organizaciones = new LinkedHashSet<>();
		int total = 0;
		try {
			for (int i = 0; i < maxLotes; i++) {
				int hechos = lote(modelo, organizaciones);
				if (hechos == 0) {
					break;
				}
				total += hechos;
			}
		} finally {
			for (long orgId : organizaciones) {
				// La fila se crea aparte; recien despues se la bloquea para sumar.
				transaccion.executeWithoutResult(status -> version.asegurarFila(orgId));
				transaccion.executeWithoutResult(status -> version.avanzar(orgId));
			}
		}
		return total;
	}

	/** Un lote en su transaccion; anota en {@code organizaciones} las que toco. */
	private int lote(ModeloEmbeddings modelo, Set<Long> organizaciones) {
		Integer hechos = transaccionLote.execute(status -> {
			List<Long> ids = fragmentos.reclamarSinVector(modelo.id(), lote);
			if (ids.isEmpty()) {
				return 0;
			}
			List<TextoDeFragmento> textos = fragmentos.textosDe(ids);
			if (textos.isEmpty()) {
				return 0;
			}
			List<String> pasajes = new ArrayList<>(textos.size());
			for (TextoDeFragmento t : textos) {
				pasajes.add(t.texto());
			}
			List<float[]> vectores = modelo.pasajes(pasajes);
			if (vectores.size() != textos.size()) {
				throw new IllegalStateException("El modelo devolvio " + vectores.size()
						+ " vectores para " + textos.size() + " pasajes");
			}
			for (int i = 0; i < textos.size(); i++) {
				TextoDeFragmento t = textos.get(i);
				fragmentos.actualizarVector(t.id(), Vectores.aBytes(vectores.get(i)), modelo.id());
				organizaciones.add(t.organizationId());
			}
			return textos.size();
		});
		return hechos == null ? 0 : hechos;
	}
}
