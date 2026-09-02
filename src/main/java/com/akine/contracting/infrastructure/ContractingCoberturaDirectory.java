package com.akine.contracting.infrastructure;

import com.akine.contracting.domain.Financiador;
import com.akine.contracting.domain.PlanCobertura;
import com.akine.contracting.spi.CoberturaCatalogoDirectory;
import com.akine.contracting.spi.FinanciadorSnapshot;
import com.akine.contracting.spi.PlanCoberturaSnapshot;
import com.akine.contracting.spi.ReferenciaDeCobertura;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Adaptador de {@link CoberturaCatalogoDirectory} sobre las tablas de M15.
 *
 * <h2>Por que el estado del financiador viaja dentro del snapshot del plan</h2>
 *
 * <p>Porque sin el, la pregunta "¿puedo elegir este plan?" no se puede contestar, y dejarla a
 * cargo del llamador garantiza que antes o despues alguien ofrezca un plan impecable de un
 * financiador dado de baja. Cuesta una segunda lectura por plan resuelto —el financiador— y esa
 * es una lectura por id sobre la clave primaria, no un problema de rendimiento a esta escala.
 *
 * <h2>Que hace {@link #congelar} que las otras no</h2>
 *
 * <p>Las lecturas devuelven el estado de hoy. {@code congelar} devuelve una <b>copia</b>
 * —{@link ReferenciaDeCobertura}— que el consumidor escribe en sus propias columnas y no vuelve a
 * pedir. Es la diferencia entre decidir y guardar, y es lo que hace que renombrar un plan manana
 * no reescriba una cobertura firmada hoy. Ver el javadoc de {@link ReferenciaDeCobertura}.
 */
@Component
public class ContractingCoberturaDirectory implements CoberturaCatalogoDirectory {

	private final FinanciadorRepository financiadores;
	private final PlanCoberturaRepository planes;

	public ContractingCoberturaDirectory(
			FinanciadorRepository financiadores, PlanCoberturaRepository planes) {

		this.financiadores = financiadores;
		this.planes = planes;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<FinanciadorSnapshot> findFinanciador(long organizationId, long financiadorId) {
		return financiadores.findByIdAndOrganizationId(financiadorId, organizationId)
				.map(ContractingCoberturaDirectory::proyectar);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PlanCoberturaSnapshot> findPlan(long organizationId, long planId) {
		return planes.findByIdAndOrganizationId(planId, organizationId)
				.flatMap(plan -> conFinanciador(organizationId, plan));
	}

	@Override
	@Transactional(readOnly = true)
	public List<PlanCoberturaSnapshot> planesSeleccionables(
			long organizationId, long financiadorId, LocalDate fecha) {

		Optional<Financiador> financiador =
				financiadores.findByIdAndOrganizationId(financiadorId, organizationId);
		if (financiador.isEmpty()) {
			return List.of();
		}
		Financiador duenio = financiador.get();

		return planes
				.findAllByOrganizationIdAndFinanciadorIdOrderByNombreAsc(organizationId, financiadorId)
				.stream()
				.map(plan -> proyectar(plan, duenio))
				.filter(snapshot -> snapshot.seleccionableEl(fecha))
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ReferenciaDeCobertura> congelar(
			long organizationId, long planId, LocalDate fecha) {

		Optional<PlanCobertura> encontrado = planes.findByIdAndOrganizationId(planId, organizationId);
		if (encontrado.isEmpty()) {
			return Optional.empty();
		}
		PlanCobertura plan = encontrado.get();

		Optional<Financiador> duenio =
				financiadores.findByIdAndOrganizationId(plan.getFinanciadorId(), organizationId);
		if (duenio.isEmpty()) {
			return Optional.empty();
		}
		Financiador financiador = duenio.get();

		// Empty y no excepcion: quien firma la cobertura decide que responder. Ver el javadoc del
		// spi.
		if (!plan.seleccionableEl(fecha, financiador.isOperable())) {
			return Optional.empty();
		}

		return Optional.of(new ReferenciaDeCobertura(
				financiador.getId(),
				financiador.getCodigo(),
				financiador.getNombre(),
				financiador.getTipo().name(),
				plan.getId(),
				plan.getCodigo(),
				plan.getNombre(),
				plan.isRequiereAutorizacion(),
				plan.isRequiereCredencial(),
				plan.getCopago(),
				plan.getMoneda(),
				fecha,
				Instant.now()));
	}

	private Optional<PlanCoberturaSnapshot> conFinanciador(long organizationId, PlanCobertura plan) {
		return financiadores.findByIdAndOrganizationId(plan.getFinanciadorId(), organizationId)
				.map(financiador -> proyectar(plan, financiador));
	}

	private static FinanciadorSnapshot proyectar(Financiador financiador) {
		return new FinanciadorSnapshot(
				financiador.getId(),
				financiador.getOrganizationId(),
				financiador.getCodigo(),
				financiador.getNombre(),
				financiador.getTipo().name(),
				financiador.getCuit(),
				financiador.isOperable());
	}

	private static PlanCoberturaSnapshot proyectar(PlanCobertura plan, Financiador financiador) {
		return new PlanCoberturaSnapshot(
				plan.getId(),
				plan.getOrganizationId(),
				plan.getFinanciadorId(),
				financiador.getNombre(),
				financiador.isOperable(),
				plan.getCodigo(),
				plan.getNombre(),
				plan.getVigenciaDesde(),
				plan.getVigenciaHasta(),
				plan.isRequiereAutorizacion(),
				plan.isRequiereCredencial(),
				plan.getCopago(),
				plan.getMoneda(),
				plan.isOperable());
	}
}
