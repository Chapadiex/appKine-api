package com.akine.resource.infrastructure;

import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.resource.application.DisponibilidadEfectivaService;
import com.akine.resource.application.DisponibilidadEfectivaView;
import com.akine.resource.spi.DisponibilidadDirectory;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.DisponibilidadExcepcionRepositoryPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Adaptador de {@link DisponibilidadDirectory} sobre el calculo real de M05.
 *
 * <p>Es una traduccion de forma y nada mas: proyecta {@link DisponibilidadEfectivaView} al record
 * mas chico que el motor de agenda necesita. <b>No reimplementa nada</b> — ver el javadoc de la
 * interfaz.
 *
 * <p>Se descarta {@code origen}, {@code recortadoPor} y {@code reglaId}: son la trazabilidad que
 * la pantalla de disponibilidad usa para linkear la regla que produjo cada franja, y la agenda no
 * la muestra. Si algun dia el buscador de turnos tiene que explicar "este hueco sale del bloque
 * de los martes", se agregan aca; hoy serian campos que viajan sin destino.
 */
@Component
public class ResourceDisponibilidadDirectory implements DisponibilidadDirectory {

	private final DisponibilidadEfectivaService disponibilidad;
	private final DisponibilidadExcepcionRepositoryPort excepciones;

	public ResourceDisponibilidadDirectory(
			DisponibilidadEfectivaService disponibilidad,
			DisponibilidadExcepcionRepositoryPort excepciones) {
		this.disponibilidad = disponibilidad;
		this.excepciones = excepciones;
	}

	@Override
	@Transactional(readOnly = true)
	public List<CierreDeAgenda> cierresEnVentana(
			long organizationId, long consultorioId, LocalDate desde, LocalDate hasta) {
		return excepciones.findCierresDeLaSedeEnVentana(organizationId, consultorioId, desde, hasta).stream()
				.map(cierre -> new CierreDeAgenda(
						cierre.getId(), cierre.getMembershipId(), cierre.getMotivo().name(),
						cierre.getFechaDesde(), cierre.getFechaHasta(),
						cierre.getHoraDesde(), cierre.getHoraHasta(), cierre.getNotes()))
				.toList();
	}

	@Override
	public List<DiaDisponible> efectiva(
			long organizationId,
			ConsultorioSnapshot sede,
			long membershipId,
			LocalDate desde,
			LocalDate hasta) {

		DisponibilidadEfectivaView vista =
				disponibilidad.sinAutorizar(organizationId, sede, membershipId, desde, hasta);

		return vista.dias().stream()
				.map(dia -> new DiaDisponible(
						dia.fecha(),
						dia.razonVacio(),
						dia.franjas().stream()
								.map(franja -> new Franja(franja.desde(), franja.hasta()))
								.toList()))
				.toList();
	}

	@Override
	public boolean fueraDelHorarioDeSede(
			long organizationId, ConsultorioSnapshot sede, Instant inicio, Instant fin) {
		return disponibilidad.fueraDelHorarioDeSede(organizationId, sede, inicio, fin);
	}
}
