package com.akine.resource.infrastructure;

import com.akine.resource.domain.CatalogoConcepto;
import com.akine.resource.spi.CatalogoDirectory;
import com.akine.resource.spi.CatalogoSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Implementacion del puerto que otros modulos consumen (M14, M16).
 *
 * <p>Traduce el {@code organizationId} del llamador a la lista de <b>duenios visibles</b> —el
 * centinela {@code 0} de lo global mas su propio tenant— que es la forma en la que este modulo
 * expresa el aislamiento. Ver {@code CatalogoRepositoryPorts}.
 *
 * <p>Vive en {@code infrastructure} y no en {@code application} porque es un adaptador de
 * salida sin ninguna regla propia: cada metodo es una consulta y un mapeo. Meterle una decision
 * aca la escondería del servicio, que es donde tiene que estar.
 */
@Component
public class ResourceCatalogoDirectory implements CatalogoDirectory {

	private static final long OWNER_PLATAFORMA = 0L;

	private final EspecialidadRepository especialidades;
	private final PracticaRepository practicas;
	private final NomencladorItemRepository vigencias;

	public ResourceCatalogoDirectory(
			EspecialidadRepository especialidades,
			PracticaRepository practicas,
			NomencladorItemRepository vigencias) {

		this.especialidades = especialidades;
		this.practicas = practicas;
		this.vigencias = vigencias;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CatalogoSnapshot> findEspecialidad(
			Long organizationId, long especialidadId, Instant at) {

		return especialidades.findVisible(especialidadId, owners(organizationId))
				.map(especialidad -> snapshot(especialidad, at, null, null));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CatalogoSnapshot> findPractica(
			Long organizationId, long practicaId, Instant at) {

		return practicas.findVisible(practicaId, owners(organizationId))
				.map(practica -> snapshot(practica, at, practica.getEspecialidadId(), null));
	}

	@Override
	@Transactional(readOnly = true)
	public List<CatalogoSnapshot> practicasVigentes(Long organizationId, Instant at) {
		// El filtro por estado lo hace la base (activo = 1); el de la ventana de vigencia se
		// resuelve en memoria con la MISMA regla que la entidad ya publica, en vez de escribirla
		// otra vez en SQL. Dos copias de una condicion temporal divergen: esta ya se rompio una
		// vez con los limites exclusivos.
		return practicas.buscar(owners(organizationId), "%", 1, -1L).stream()
				.filter(practica -> practica.estaVigente(at))
				.map(practica -> snapshot(practica, at, practica.getEspecialidadId(), null))
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CatalogoSnapshot> resolverCodigo(
			Long organizationId, long nomencladorId, String codigo, Instant at) {

		return vigencias.resolverEn(nomencladorId, codigo, at)
				// El nomenclador puede ser global y sus vigencias tambien: la comprobacion de
				// duenio se hace sobre la fila devuelta y no en la consulta, que resuelve por
				// (nomenclador, codigo, instante) usando ix_nomenclador_item_resolucion.
				.filter(item -> owners(organizationId).contains(
						item.getOrganizationId() == null
								? OWNER_PLATAFORMA
								: item.getOrganizationId()))
				.map(item -> snapshot(
						item, at, null, item.getValorReferencia()));
	}

	private static List<Long> owners(Long organizationId) {
		return organizationId == null
				? List.of(OWNER_PLATAFORMA)
				: List.of(OWNER_PLATAFORMA, organizationId);
	}

	private static CatalogoSnapshot snapshot(
			CatalogoConcepto concepto,
			Instant at,
			Long especialidadId,
			java.math.BigDecimal valorReferencia) {

		return new CatalogoSnapshot(
				concepto.getId(),
				concepto.getOrganizationId(),
				concepto.getCodigo(),
				concepto.getName(),
				concepto.getValidFrom(),
				concepto.getValidUntil(),
				concepto.isActive(),
				concepto.estaVigente(at),
				especialidadId,
				valorReferencia,
				concepto.getVersion());
	}
}
