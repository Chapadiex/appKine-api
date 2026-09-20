package com.akine.offering.infrastructure;

import com.akine.offering.domain.Habilitacion;
import com.akine.offering.domain.Modalidad;
import com.akine.offering.domain.OfertaServicioConsultorio;
import com.akine.offering.spi.HabilitacionSnapshot;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.offering.spi.PrecioDeOferta;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Adaptador de {@link OfertaDirectory} sobre las tablas de M27.
 *
 * <h2>Por que las habilitaciones no filtran por sede aunque la firma la reciba</h2>
 *
 * <p>Los repositorios de habilitacion filtran por {@code (organization_id, oferta_id)} y no por
 * {@code consultorio_id}, aunque la columna existe. No es un hueco: la oferta se resuelve
 * <b>primero</b> con {@link #find}, que si lleva la sede en el {@code WHERE}, asi que una oferta
 * de otra sede devuelve {@code empty} y las habilitaciones no se leen nunca. La sede viaja en las
 * tres firmas para que el llamador no pueda pedir habilitaciones sin haber resuelto la oferta —si
 * la firma no la pidiera, saltearse ese paso seria facil y silencioso—.
 *
 * <p>Se devuelven las habilitaciones <b>activas</b>, pero sin filtrar por vigencia: quien decide
 * si una vigencia cubre un dia es el motor, que lo evalua dia por dia. Ver
 * {@link OfertaDirectory#profesionalesHabilitados}.
 */
@Component
public class OfferingOfertaDirectory implements OfertaDirectory {

	private final OfertaRepository ofertas;
	private final OfertaProfesionalHabilitadoRepository profesionales;
	private final OfertaEspacioHabilitadoRepository espacios;

	public OfferingOfertaDirectory(
			OfertaRepository ofertas,
			OfertaProfesionalHabilitadoRepository profesionales,
			OfertaEspacioHabilitadoRepository espacios) {

		this.ofertas = ofertas;
		this.profesionales = profesionales;
		this.espacios = espacios;
	}

	@Override
	public Optional<OfertaSnapshot> find(long organizationId, long consultorioId, long ofertaId) {
		return ofertas
				.findByIdAndOrganizationIdAndConsultorioId(ofertaId, organizationId, consultorioId)
				.map(OfferingOfertaDirectory::proyectar);
	}

	@Override
	public Optional<PrecioDeOferta> precioDe(long organizationId, long consultorioId, long ofertaId) {
		return ofertas
				.findByIdAndOrganizationIdAndConsultorioId(ofertaId, organizationId, consultorioId)
				.map(oferta -> new PrecioDeOferta(
						oferta.getId(), oferta.getPrecioBase(), oferta.getMoneda()));
	}

	@Override
	public List<HabilitacionSnapshot> profesionalesHabilitados(
			long organizationId, long consultorioId, long ofertaId) {

		return proyectar(profesionales
				.findAllByOrganizationIdAndOfertaIdAndActive(organizationId, ofertaId, true));
	}

	@Override
	public List<HabilitacionSnapshot> espaciosHabilitados(
			long organizationId, long consultorioId, long ofertaId) {

		return proyectar(espacios
				.findAllByOrganizationIdAndOfertaIdAndActive(organizationId, ofertaId, true));
	}

	private static List<HabilitacionSnapshot> proyectar(List<? extends Habilitacion> habilitaciones) {
		return habilitaciones.stream()
				.map(h -> new HabilitacionSnapshot(
						h.getId(), h.getRecursoId(), h.getValidFrom(), h.getValidUntil(), h.isActive()))
				.toList();
	}

	private static OfertaSnapshot proyectar(OfertaServicioConsultorio oferta) {
		return new OfertaSnapshot(
				oferta.getId(),
				oferta.getOrganizationId(),
				oferta.getConsultorioId(),
				oferta.getServicioId(),
				oferta.getNombreComercial(),
				oferta.getDuracionMinutos(),
				oferta.getCapacidad(),
				oferta.getModalidad() == Modalidad.GRUPAL,
				oferta.isRequiereProfesional(),
				oferta.isRequiereEspacio(),
				oferta.getVigenciaDesde(),
				oferta.getVigenciaHasta(),
				oferta.isActive());
	}
}
