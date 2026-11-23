package com.akine.billing.application;

import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.SnapshotDeConvenio;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.person.spi.DatosDeLiquidacionDirectory;
import com.akine.person.spi.DatosDeLiquidacionDirectory.CoberturaDeLiquidacion;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.resource.spi.CatalogoDirectory;
import com.akine.resource.spi.CatalogoSnapshot;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Completa los renglones de un lote con lo que la obra social pide para liquidar: quien fue
 * (nombre y documento), su afiliado y plan, la practica con su codigo y la autorizacion que
 * consumio (docs/diseno/AKINE-obras-sociales-simple.md §6).
 *
 * <p>Se calcula al leer y no se persiste. Una consulta por fuente y en lote: personas, obligaciones,
 * coberturas y consumos. Las practicas, que son pocas y distintas, una por cada una.
 *
 * <p>Lo que falta (una persona dada de baja del padron, una cobertura particular) viaja nulo: el
 * renglon sigue saliendo con lo que tiene.
 */
@Component
public class RenglonesLegibles {

	private final ObligacionRepositoryPort obligaciones;
	private final PacienteDirectory pacientes;
	private final DatosDeLiquidacionDirectory datos;
	private final CatalogoDirectory catalogo;

	public RenglonesLegibles(
			ObligacionRepositoryPort obligaciones,
			PacienteDirectory pacientes,
			DatosDeLiquidacionDirectory datos,
			CatalogoDirectory catalogo) {
		this.obligaciones = obligaciones;
		this.pacientes = pacientes;
		this.datos = datos;
		this.catalogo = catalogo;
	}

	public List<PresentacionItemView> completar(long organizationId, List<PresentacionItemView> items) {
		if (items.isEmpty()) {
			return items;
		}

		Map<Long, PacienteSnapshot> personas = pacientes.findAll(
				organizationId, items.stream().map(PresentacionItemView::personaId).distinct().toList());
		Map<Long, Obligacion> porObligacion = obligaciones.findAllDeLaOrganizacion(
						organizationId,
						items.stream().map(PresentacionItemView::obligacionId).distinct().toList())
				.stream()
				.collect(Collectors.toMap(Obligacion::getId, Function.identity()));
		Map<Long, CoberturaDeLiquidacion> coberturas = datos.coberturas(
				organizationId,
				porObligacion.values().stream()
						.map(Obligacion::getSnapshotDeConvenio)
						.flatMap(Optional::stream)
						.map(SnapshotDeConvenio::coberturaId)
						.distinct()
						.toList());
		Map<Long, String> autorizaciones = datos.autorizacionesPorSesion(
				organizationId, items.stream().map(PresentacionItemView::sesionId).distinct().toList());
		Map<Long, Optional<CatalogoSnapshot>> practicas = new HashMap<>();
		Instant ahora = Instant.now();

		return items.stream().map(item -> {
			PacienteSnapshot persona = personas.get(item.personaId());
			Obligacion obligacion = porObligacion.get(item.obligacionId());
			CoberturaDeLiquidacion cobertura = Optional.ofNullable(obligacion)
					.flatMap(Obligacion::getSnapshotDeConvenio)
					.map(snapshot -> coberturas.get(snapshot.coberturaId()))
					.orElse(null);
			Optional<CatalogoSnapshot> practica = Optional.ofNullable(obligacion)
					.map(Obligacion::getPracticaId)
					.flatMap(id -> practicas.computeIfAbsent(
							id, clave -> catalogo.findPractica(organizationId, clave, ahora)));
			return item.conDatos(
					persona == null ? null : persona.nombreCompleto(),
					persona == null ? null : documento(persona),
					cobertura == null ? null : cobertura.numeroAfiliado(),
					cobertura == null ? null : cobertura.planNombre(),
					practica.map(CatalogoSnapshot::codigo).orElse(null),
					practica.map(CatalogoSnapshot::name).orElse(null),
					autorizaciones.get(item.sesionId()));
		}).toList();
	}

	private static String documento(PacienteSnapshot persona) {
		return persona.numeroDocumento() == null ? null
				: Objects.toString(persona.tipoDocumento(), "") + " " + persona.numeroDocumento();
	}
}
