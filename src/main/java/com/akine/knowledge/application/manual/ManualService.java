package com.akine.knowledge.application.manual;

import com.akine.knowledge.application.OperatingActor;
import com.akine.knowledge.domain.manual.FichaDelManual;
import com.akine.knowledge.domain.manual.FichaNoEncontradaException;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Lectura del manual para la persona que mira: siempre con su filtro (§5.2). Sin organizacion y sede
 * en el contexto no hay permisos para filtrar: 403, como el resto de la aplicacion.
 */
@Service
public class ManualService {

	/** Tope de resultados de la busqueda del panel. */
	public static final int RESULTADOS = 10;

	private final IndiceDelManual indice;
	private final VisibilidadDelManual visibilidad;

	public ManualService(IndiceDelManual indice, VisibilidadDelManual visibilidad) {
		this.indice = indice;
		this.visibilidad = visibilidad;
	}

	public List<FichaDelManual> indice(OperatingActor actor) {
		return indice.fichas(filtro(actor));
	}

	public FichaDelManual ficha(OperatingActor actor, String id) {
		return indice.ficha(id, filtro(actor)).orElseThrow(() -> new FichaNoEncontradaException(id));
	}

	public Optional<FichaDelManual> deLaRuta(OperatingActor actor, String ruta) {
		return indice.porRuta(ruta, filtro(actor));
	}

	public List<PasajeEncontrado> buscar(OperatingActor actor, String consulta, String rutaActual) {
		return indice.buscar(consulta, filtro(actor), rutaActual, RESULTADOS);
	}

	private Predicate<FichaDelManual> filtro(OperatingActor actor) {
		if (actor.contextOrganizationId() == null || actor.contextConsultorioId() == null) {
			throw new AccessDeniedException("El manual requiere una organizacion y una sede en contexto");
		}
		return visibilidad.para(actor.accountId(), actor.contextOrganizationId(), actor.contextConsultorioId());
	}
}
