package com.akine.resource.infrastructure;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ZonaDeSede;
import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.BloqueDisponibilidadRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.DisponibilidadExcepcionRepositoryPort;
import com.akine.resource.spi.CierresDeSedeDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adaptador de {@link CierresDeSedeDirectory}: dos lecturas (los cierres de las sedes y todos los
 * bloques de las memberships) y el cruce en memoria. Los dos conjuntos son chicos —unos pocos cierres
 * por sede y año, unos pocos bloques por persona—, y el cruce por dia de la semana no se expresa en
 * una consulta sin calendario.
 */
@Component
public class ResourceCierresDeSedeDirectory implements CierresDeSedeDirectory {

	private final DisponibilidadExcepcionRepositoryPort excepciones;
	private final BloqueDisponibilidadRepositoryPort bloques;
	private final ConsultorioDirectory consultorios;

	public ResourceCierresDeSedeDirectory(
			DisponibilidadExcepcionRepositoryPort excepciones,
			BloqueDisponibilidadRepositoryPort bloques,
			ConsultorioDirectory consultorios) {
		this.excepciones = excepciones;
		this.bloques = bloques;
		this.consultorios = consultorios;
	}

	@Override
	@Transactional(readOnly = true)
	public List<CierreDeSede> cierresDeSede(long organizationId, Map<Long, Set<Long>> membershipsPorSede) {
		if (membershipsPorSede.isEmpty()) {
			return List.of();
		}
		List<DisponibilidadExcepcion> cierres =
				excepciones.findCierresDeSedeDeLasSedes(organizationId, membershipsPorSede.keySet());
		if (cierres.isEmpty()) {
			return List.of();
		}
		Set<Long> todas = new HashSet<>();
		membershipsPorSede.values().forEach(todas::addAll);
		List<BloqueDisponibilidad> horario = todas.isEmpty()
				? List.of()
				: bloques.findTodosDeLasMemberships(organizationId, todas);

		Map<Long, ZoneId> zonas = new HashMap<>();
		return cierres.stream().map(cierre -> {
			ZoneId zona = zonas.computeIfAbsent(cierre.getConsultorioId(),
					sede -> ZonaDeSede.de(consultorios, organizationId, sede));
			Set<Long> deEstaSede = membershipsPorSede.getOrDefault(cierre.getConsultorioId(), Set.of());
			Set<Long> conHorario = new HashSet<>();
			for (BloqueDisponibilidad bloque : horario) {
				if (deEstaSede.contains(bloque.getMembershipId())
						&& bloque.getConsultorioId().equals(cierre.getConsultorioId())
						&& tocaba(bloque, cierre, zona)) {
					conHorario.add(bloque.getMembershipId());
				}
			}
			return new CierreDeSede(cierre.getId(), cierre.getConsultorioId(), cierre.getMotivo().name(),
					cierre.getFechaDesde(), cierre.getFechaHasta(), cierre.getHoraDesde(), cierre.getHoraHasta(),
					cierre.isActive(), conHorario);
		}).toList();
	}

	/**
	 * El bloque atendia algun dia del cierre: vigencia cruzada (las dos {@code hasta} son exclusivas),
	 * mismo dia de la semana, horas cruzadas si el cierre es parcial, y no dado de baja antes de que el
	 * cierre empezara (en la zona de la sede).
	 */
	static boolean tocaba(BloqueDisponibilidad bloque, DisponibilidadExcepcion cierre, ZoneId zona) {
		Instant baja = bloque.getDeletedAt();
		if (baja != null && baja.isBefore(cierre.getFechaDesde().atStartOfDay(zona).toInstant())) {
			return false;
		}
		if (!bloque.isActive() && baja == null) {
			return false;
		}
		if (cierre.getHoraDesde() != null
				&& !(bloque.getHoraDesde().isBefore(cierre.getHoraHasta())
						&& cierre.getHoraDesde().isBefore(bloque.getHoraHasta()))) {
			return false;
		}
		LocalDate desde = max(cierre.getFechaDesde(), bloque.getVigenciaDesde());
		LocalDate hasta = bloque.getVigenciaHasta() == null
				? cierre.getFechaHasta()
				: min(cierre.getFechaHasta(), bloque.getVigenciaHasta());
		// Una semana alcanza: si en siete dias del cruce no esta su dia, no esta en ninguno.
		for (LocalDate dia = desde; dia.isBefore(hasta) && dia.isBefore(desde.plusDays(7)); dia = dia.plusDays(1)) {
			if (dia.getDayOfWeek().getValue() == bloque.getDiaSemana()) {
				return true;
			}
		}
		return false;
	}

	private static LocalDate max(LocalDate a, LocalDate b) {
		return a.isAfter(b) ? a : b;
	}

	private static LocalDate min(LocalDate a, LocalDate b) {
		return a.isBefore(b) ? a : b;
	}
}
