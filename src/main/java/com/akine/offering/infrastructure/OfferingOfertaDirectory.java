package com.akine.offering.infrastructure;

import com.akine.offering.application.OfertaPrecioParticularService;
import com.akine.offering.domain.Habilitacion;
import com.akine.offering.domain.Modalidad;
import com.akine.offering.domain.OfertaDuracionProfesional;
import com.akine.offering.domain.OfertaServicioConsultorio;
import com.akine.offering.domain.Servicio;
import com.akine.offering.spi.ClasificacionDeOferta;
import com.akine.offering.spi.EsquemaDeCobro;
import com.akine.offering.spi.HabilitacionSnapshot;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ZonaDeSede;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
	private final OfertaDuracionProfesionalRepository duraciones;
	private final OfertaPrecioParticularService precios;
	private final ConsultorioDirectory consultorios;
	private final ServicioRepository servicios;

	public OfferingOfertaDirectory(
			OfertaRepository ofertas,
			OfertaProfesionalHabilitadoRepository profesionales,
			OfertaEspacioHabilitadoRepository espacios,
			OfertaDuracionProfesionalRepository duraciones,
			OfertaPrecioParticularService precios,
			ConsultorioDirectory consultorios,
			ServicioRepository servicios) {

		this.ofertas = ofertas;
		this.servicios = servicios;
		this.profesionales = profesionales;
		this.espacios = espacios;
		this.duraciones = duraciones;
		this.precios = precios;
		this.consultorios = consultorios;
	}

	@Override
	public Optional<OfertaSnapshot> find(long organizationId, long consultorioId, long ofertaId) {
		return ofertas
				.findByIdAndOrganizationIdAndConsultorioId(ofertaId, organizationId, consultorioId)
				.map(OfferingOfertaDirectory::proyectar);
	}

	@Override
	public Map<Long, OfertaSnapshot> findTodas(
			long organizationId, long consultorioId, Collection<Long> ofertaIds) {
		if (ofertaIds.isEmpty()) {
			return Map.of();
		}
		return ofertas.findAllByOrganizationIdAndConsultorioIdAndIdIn(
						organizationId, consultorioId, Set.copyOf(ofertaIds))
				.stream()
				.collect(Collectors.toMap(OfertaServicioConsultorio::getId, OfferingOfertaDirectory::proyectar));
	}

	@Override
	public Map<Long, ClasificacionDeOferta> clasificar(
			long organizationId, long consultorioId, Collection<Long> ofertaIds) {
		if (ofertaIds.isEmpty()) {
			return Map.of();
		}
		List<OfertaServicioConsultorio> encontradas = ofertas.findAllByOrganizationIdAndConsultorioIdAndIdIn(
				organizationId, consultorioId, Set.copyOf(ofertaIds));
		// El servicio es del catalogo global: se lee por id, sin tenant, como lo referencia la oferta.
		Map<Long, Servicio> porId = servicios.findAllById(encontradas.stream()
						.map(OfertaServicioConsultorio::getServicioId).collect(Collectors.toSet()))
				.stream()
				.collect(Collectors.toMap(Servicio::getId, s -> s));
		return encontradas.stream()
				.filter(o -> porId.containsKey(o.getServicioId()))
				.collect(Collectors.toMap(OfertaServicioConsultorio::getId, o -> {
					Servicio servicio = porId.get(o.getServicioId());
					return new ClasificacionDeOferta(
							o.getId(), o.getNombreComercial(), o.getModalidad() == Modalidad.GRUPAL,
							servicio.getId(), servicio.getNombre(), servicio.getNaturaleza().name());
				}));
	}

	@Override
	public Optional<PrecioDeOferta> precioDe(long organizationId, long consultorioId, long ofertaId) {
		return ofertas
				.findByIdAndOrganizationIdAndConsultorioId(ofertaId, organizationId, consultorioId)
				.map(OfferingOfertaDirectory::precio);
	}

	@Override
	public Map<Long, PrecioDeOferta> preciosDe(
			long organizationId, long consultorioId, Collection<Long> ofertaIds) {
		if (ofertaIds.isEmpty()) {
			return Map.of();
		}
		return ofertas.findAllByOrganizationIdAndConsultorioIdAndIdIn(
						organizationId, consultorioId, Set.copyOf(ofertaIds))
				.stream()
				.collect(Collectors.toMap(OfertaServicioConsultorio::getId, OfferingOfertaDirectory::precio));
	}

	private static PrecioDeOferta precio(OfertaServicioConsultorio oferta) {
		return new PrecioDeOferta(
				oferta.getId(), oferta.getPrecioBase(), oferta.getMoneda(),
				oferta.isAdmiteObraSocial(), oferta.isExigePrepago());
	}

	@Override
	public Optional<PrecioDeOferta> precioVigenteEl(
			long organizationId, long consultorioId, long ofertaId, LocalDate fecha) {
		return precios.precioVigenteEl(organizationId, consultorioId, ofertaId, fecha);
	}

	@Override
	public Optional<PrecioDeOferta> precioEn(
			long organizationId, long consultorioId, long ofertaId, Instant momento) {
		return precioVigenteEl(organizationId, consultorioId, ofertaId,
				// Si la sede no resuelve o su zona es invalida se cae a UTC, como el devengo de F-4.
				LocalDate.ofInstant(momento,
						ZonaDeSede.de(consultorios, organizationId, consultorioId)));
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

	@Override
	public Map<Long, Integer> duracionesPropias(long organizationId, long consultorioId, long ofertaId) {
		return duraciones.findAllByOrganizationIdAndOfertaIdAndActive(organizationId, ofertaId, true).stream()
				.collect(Collectors.toMap(
						OfertaDuracionProfesional::getMembershipId, OfertaDuracionProfesional::getDuracionMinutos));
	}

	@Override
	public Optional<EsquemaDeCobro> esquemaDeCobro(
			long organizationId, long consultorioId, long ofertaId) {
		return ofertas
				.findByIdAndOrganizationIdAndConsultorioId(ofertaId, organizationId, consultorioId)
				.flatMap(oferta -> EsquemaDeCobro.de(oferta.getEsquemaCobro()));
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
				oferta.isRequiereCasoClinico(),
				oferta.isGeneraRegistroClinico(),
				oferta.getVigenciaDesde(),
				oferta.getVigenciaHasta(),
				oferta.isActive());
	}
}
