package com.akine.person.infrastructure;

import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.TipoMovimientoAutorizacion;
import com.akine.person.domain.TipoOrigenMovimiento;
import com.akine.person.spi.DatosDeLiquidacionDirectory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Adaptador de {@link DatosDeLiquidacionDirectory}: dos consultas en lote, de solo lectura. */
@Component
public class PersonDatosDeLiquidacionDirectory implements DatosDeLiquidacionDirectory {

	@PersistenceContext
	private EntityManager entityManager;

	@Override
	@Transactional(readOnly = true)
	public Map<Long, CoberturaDeLiquidacion> coberturas(
			long organizationId, Collection<Long> coberturaIds) {

		if (coberturaIds == null || coberturaIds.isEmpty()) {
			return Map.of();
		}
		return entityManager.createQuery("""
						SELECT c FROM CoberturaPaciente c
						 WHERE c.organizationId = :organizationId
						   AND c.id IN :ids
						""", CoberturaPaciente.class)
				.setParameter("organizationId", organizationId)
				.setParameter("ids", coberturaIds)
				.getResultStream()
				.collect(Collectors.toMap(
						CoberturaPaciente::getId,
						c -> new CoberturaDeLiquidacion(c.getId(), c.getNumeroAfiliado(),
								c.getFinanciadorNombre(), c.getPlanNombre())));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Long, String> autorizacionesPorSesion(
			long organizationId, Collection<Long> sesionIds) {

		if (sesionIds == null || sesionIds.isEmpty()) {
			return Map.of();
		}
		// Un consumo revertido no cuenta: su reversion lo apunta por movimientoOrigenId.
		Map<Long, String> porSesion = new LinkedHashMap<>();
		entityManager.createQuery("""
						SELECT m.referenciaOrigen, a.numero
						  FROM AutorizacionMovimiento m, Autorizacion a
						 WHERE a.id = m.autorizacionId
						   AND a.organizationId = :organizationId
						   AND m.organizationId = :organizationId
						   AND m.tipo = :consumo
						   AND m.tipoOrigen = :sesion
						   AND m.referenciaOrigen IN :sesiones
						   AND NOT EXISTS (
						       SELECT r.id FROM AutorizacionMovimiento r
						        WHERE r.movimientoOrigenId = m.id
						          AND r.tipo = :reversion)
						 ORDER BY m.referenciaOrigen, m.id
						""", Object[].class)
				.setParameter("organizationId", organizationId)
				.setParameter("consumo", TipoMovimientoAutorizacion.CONSUMO)
				.setParameter("sesion", TipoOrigenMovimiento.SESION)
				.setParameter("reversion", TipoMovimientoAutorizacion.REVERSION)
				.setParameter("sesiones", sesionIds)
				.getResultList()
				.forEach(fila -> {
					long sesionId = ((Number) fila[0]).longValue();
					String numero = fila[1] == null ? "" : (String) fila[1];
					porSesion.merge(sesionId, numero,
							(previo, nuevo) -> previo.isEmpty() ? nuevo : previo + ", " + nuevo);
				});
		return porSesion;
	}
}
