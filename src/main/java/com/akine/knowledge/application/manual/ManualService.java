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

	/** Una ficha y, con el mismo filtro, las relacionadas que la persona puede ver. */
	public record FichaVisible(FichaDelManual ficha, List<FichaDelManual> relacionadas) {
	}

	public FichaVisible ficha(OperatingActor actor, String id) {
		Predicate<FichaDelManual> visible = filtro(actor);
		return indice.ficha(id, visible).map(f -> conRelacionadas(f, visible))
				.orElseThrow(() -> new FichaNoEncontradaException(id));
	}

	public Optional<FichaVisible> deLaRuta(OperatingActor actor, String ruta) {
		Predicate<FichaDelManual> visible = filtro(actor);
		return indice.porRuta(ruta, visible).map(f -> conRelacionadas(f, visible));
	}

	private FichaVisible conRelacionadas(FichaDelManual ficha, Predicate<FichaDelManual> visible) {
		List<FichaDelManual> relacionadas = ficha.relacionadas().stream()
				.map(id -> indice.ficha(id, visible))
				.flatMap(Optional::stream)
				.toList();
		return new FichaVisible(ficha, relacionadas);
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
