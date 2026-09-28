package com.akine.resource.infrastructure;

import com.akine.resource.application.MedicionDefinicionView;
import com.akine.resource.spi.MedicionDefinicionSnapshot;
import com.akine.resource.spi.MedicionDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Implementacion del puerto que {@code encounter} consume para registrar mediciones (M14).
 *
 * <p>Traduce el {@code organizationId} del llamador a la lista de <b>duenios visibles</b> —el
 * centinela {@code 0} de lo global mas su propio tenant— que es la forma en la que este modulo
 * expresa el aislamiento. Filtrar por {@code organization_id} dejaria fuera el catalogo de
 * plataforma entero (ADR-0021).
 *
 * <p>Vive en {@code infrastructure} y no en {@code application} porque es un adaptador de salida
 * sin ninguna regla propia: cada metodo es una consulta y un mapeo. La decision de si una
 * definicion inactiva sirve o no la toma <b>el consumidor</b>, y por eso {@link #find} no filtra
 * por estado — esconderla aca convertiria cada medicion vieja en un dato ilegible.
 */
@Component
public class ResourceMedicionDirectory implements MedicionDirectory {

	private static final long OWNER_PLATAFORMA = 0L;

	/** Centinela de "solo las activas" del puerto de persistencia. Ver su javadoc. */
	private static final int SOLO_ACTIVAS = 1;

	/** Patron del {@code LIKE} que no recorta nada. */
	private static final String TODO = "%";

	private final MedicionDefinicionRepository definiciones;

	public ResourceMedicionDirectory(MedicionDefinicionRepository definiciones) {
		this.definiciones = definiciones;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<MedicionDefinicionSnapshot> find(Long organizationId, long definicionId) {
		return definiciones.findVisible(definicionId, owners(organizationId))
				.map(MedicionDefinicionView::snapshotDe);
	}

	@Override
	@Transactional(readOnly = true)
	public List<MedicionDefinicionSnapshot> definicionesVigentes(Long organizationId) {
		return definiciones.buscar(owners(organizationId), TODO, SOLO_ACTIVAS).stream()
				.map(MedicionDefinicionView::snapshotDe)
				.toList();
	}

	/**
	 * Lo global, mas lo propio si el llamador trae tenant.
	 *
	 * <p>{@code organizationId} nulo deja solo el catalogo de plataforma, que es lo correcto para
	 * un actor sin contexto de tenant: nunca "todo", que seria filtrar por nada.
	 */
	private static List<Long> owners(Long organizationId) {
		return organizationId == null
				? List.of(OWNER_PLATAFORMA)
				: List.of(OWNER_PLATAFORMA, organizationId);
	}
}
