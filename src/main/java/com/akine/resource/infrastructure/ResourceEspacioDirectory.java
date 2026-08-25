package com.akine.resource.infrastructure;

import com.akine.resource.domain.Espacio;
import com.akine.resource.domain.port.EspacioRepositoryPort;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Implementacion de {@link EspacioDirectory}: el borde por donde {@code resource} responde
 * preguntas de otros modulos.
 *
 * <p><b>Nunca devuelve entities</b>, solo el record del {@code spi}. Devolver el {@link Espacio}
 * dejaria que el consumidor lo modificara dentro de una transaccion ajena, que es la forma mas
 * silenciosa de romper el ownership de una tabla, y ademas ArchUnit lo rechaza porque la entity
 * vive en {@code domain}.
 *
 * <p>{@code readOnly}: son consultas puras. Se unen a la transaccion del llamador cuando hay
 * una y abren una propia cuando no.
 */
@Component
public class ResourceEspacioDirectory implements EspacioDirectory {

	private final EspacioRepository espacioRepository;

	public ResourceEspacioDirectory(EspacioRepository espacioRepository) {
		this.espacioRepository = espacioRepository;
	}

	/**
	 * <b>Busca por id sin exigir la sede</b>, a diferencia de todas las consultas internas del
	 * modulo, y eso es deliberado: el consumidor —un turno, una sesion— guarda un
	 * {@code espacioId} y no necesariamente sabe de que sede era. El aislamiento de tenant NO se
	 * relaja: {@code organizationId} sigue siendo obligatorio, y la sede real viaja en el
	 * snapshot para que el llamador pueda compararla si le importa.
	 */
	@Override
	@Transactional(readOnly = true)
	public Optional<EspacioSnapshot> find(long organizationId, long espacioId, Instant at) {
		return espacioRepository.findById(espacioId)
				.filter(espacio -> espacio.getOrganizationId() == organizationId)
				.map(espacio -> snapshot(espacio, at));
	}

	@Override
	@Transactional(readOnly = true)
	public List<EspacioSnapshot> enServicio(
			long organizationId, long consultorioId, Instant desde, Instant hasta) {

		return espacioRepository.findEnServicio(organizationId, consultorioId, desde, hasta)
				.stream()
				.map(espacio -> snapshot(espacio, desde))
				.toList();
	}

	/**
	 * {@code enServicio} se calcula aca y no lo recalcula el consumidor: la regla RN-M04-002 es
	 * de este modulo, y una segunda copia en {@code scheduling} divergiria en cuanto alguna de
	 * las dos cambie.
	 */
	private static EspacioSnapshot snapshot(Espacio espacio, Instant at) {
		return new EspacioSnapshot(
				espacio.getId(),
				espacio.getOrganizationId(),
				espacio.getConsultorioId(),
				espacio.getName(),
				espacio.getTipo().name(),
				espacio.getCapacidad(),
				espacio.getValidFrom(),
				espacio.getValidUntil(),
				espacio.isActive(),
				espacio.estaEnServicio(at));
	}
}
