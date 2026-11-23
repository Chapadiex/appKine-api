package com.akine.billing.application;

import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.contracting.spi.ArancelCongelado;
import com.akine.contracting.spi.ArancelDirectory;
import com.akine.contracting.spi.ArancelVigente;
import com.akine.contracting.spi.ConvenioDeLaSede;
import com.akine.contracting.spi.Coseguro;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.offering.spi.PracticaDeOferta;
import com.akine.offering.spi.PracticasDeOfertaDirectory;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ZonaDeSede;
import com.akine.person.spi.CoberturaAplicable;
import com.akine.person.spi.CoberturaNoAplicable;
import com.akine.person.spi.CoberturasAplicablesDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * La resolucion economica de una atencion: que practica, que cobertura, que convenio, cuanto paga
 * el financiador, el coseguro y el tope de sesiones del mes. <b>Es la unica copia de esa regla</b>:
 * la usa el devengo del cierre ({@code billing.infrastructure.ObligacionDevengador}) y la usa el
 * asistente para cotizar sin devengar. Si fueran dos, la cotizacion y la deuda podrian divergir
 * sin que nada lo note ({@code CotizacionIgualAlDevengoIT}).
 *
 * <p><b>No autoriza nada</b>: quien la llama ya exigio el permiso. No escribe nada.
 *
 * <h2>La regla (la de F-4 y obras sociales simples)</h2>
 *
 * <pre>
 *   1. practicas candidatas (DP-11): las realizadas, la principal primero; sin realizadas, la
 *      principal de la oferta
 *   2. por cada candidata, sus coberturas aplicables (la elegida primero, despues la principal)
 *   3. la primera con arancel y sin el tope de sesiones del mes alcanzado
 *   4. coseguro = max(precio de la oferta - lo que paga el financiador, 0)
 * </pre>
 *
 * <p>El arancel lo obtiene un {@link Congelador} que pasa quien llama: el devengo congela la copia
 * que guarda, y la cotizacion pide la misma copia sin guardarla. La decision es la misma.
 */
@Service
public class CotizadorDeAtencion {

	private static final Logger log = LoggerFactory.getLogger(CotizadorDeAtencion.class);
	private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
	private static final String MONEDA_POR_DEFECTO = "ARS";

	private final ObligacionRepositoryPort obligaciones;
	private final CoberturasAplicablesDirectory coberturas;
	private final ArancelDirectory aranceles;
	private final PracticasDeOfertaDirectory practicasDeOferta;
	private final ConsultorioDirectory consultorios;
	private final OfertaDirectory ofertas;

	@Autowired
	public CotizadorDeAtencion(
			ObligacionRepositoryPort obligaciones,
			CoberturasAplicablesDirectory coberturas,
			ArancelDirectory aranceles,
			PracticasDeOfertaDirectory practicasDeOferta,
			ConsultorioDirectory consultorios,
			OfertaDirectory ofertas) {

		this.obligaciones = obligaciones;
		this.coberturas = coberturas;
		this.aranceles = aranceles;
		this.practicasDeOferta = practicasDeOferta;
		this.consultorios = consultorios;
		this.ofertas = ofertas;
	}

	/** Como se obtiene el arancel de una cobertura para una practica. Vacio = no aplica. */
	@FunctionalInterface
	public interface Congelador {
		Optional<ArancelCongelado> congelar(CoberturaAplicable cobertura, long practicaId);
	}

	/** La cobertura que se aplica, su arancel y la alerta de DP-11. */
	public record Cubierta(
			CoberturaAplicable cobertura, ArancelCongelado arancel, boolean alertaPracticaNoHabilitada) {
	}

	/** Un tope de sesiones por mes que se miro, alcanzado o no. */
	public record TopeEvaluado(
			CoberturaAplicable cobertura, long convenioId, int tope, long usadas, boolean alcanzado) {
	}

	/** El resultado de {@link #resolver}, con el rastro que la cotizacion explica. */
	public record Resolucion(
			Optional<Cubierta> cubierta, List<Long> candidatas, List<TopeEvaluado> topes,
			boolean huboAplicables) {
	}

	// =================================================================================
	// La regla compartida con el devengo
	// =================================================================================

	/**
	 * La primera practica candidata con una cobertura aplicable y un arancel que el
	 * {@code congelador} devuelva, sin el tope del mes alcanzado.
	 *
	 * <p>{@code aplicables} es una lectura viva y {@code congelar} otra: si entre las dos alguien
	 * da de baja el arancel, el congelador devuelve vacio y se prueba la siguiente. Nunca lanza por
	 * un desenlace de negocio: sin nada que cubra, la cubierta viene vacia (particular).
	 *
	 * @param practicasRealizadas vacio = la sesion cerro sin tratamientos (o es una cotizacion)
	 * @param dia                 dia local de la sede
	 */
	@SuppressWarnings("java:S107")
	public Resolucion resolver(
			long organizationId, long consultorioId, long personaId, long ofertaId,
			Set<Long> practicasRealizadas, Long coberturaElegidaId, LocalDate dia,
			Congelador congelador) {

		List<PracticaDeOferta> declaradas =
				practicasDeOferta.practicasHabilitadas(organizationId, consultorioId, ofertaId);
		List<Long> candidatas = candidatas(practicasRealizadas, declaradas);
		List<TopeEvaluado> topes = new ArrayList<>();
		boolean huboAplicables = false;

		for (long practicaId : candidatas) {
			List<CoberturaAplicable> aplicables = elegidaPrimero(coberturaElegidaId, coberturas.aplicables(
					organizationId, consultorioId, personaId, practicaId, ofertaId, dia));
			huboAplicables |= !aplicables.isEmpty();
			for (CoberturaAplicable cobertura : aplicables) {
				Optional<ArancelCongelado> arancel = congelador.congelar(cobertura, practicaId);
				if (arancel.isEmpty()) {
					continue;
				}
				TopeEvaluado tope = evaluarTope(organizationId, consultorioId, personaId, cobertura,
						arancel.get(), dia);
				if (tope != null) {
					topes.add(tope);
					if (tope.alcanzado()) {
						// Pasado el tope de sesiones del mes, el plan ya no cubre: se prueba la
						// cobertura siguiente y, si ninguna cubre, la sesion va como particular.
						continue;
					}
				}
				boolean noHabilitada = !declaradas.isEmpty() && declaradas.stream()
						.noneMatch(p -> p.practicaId() == practicaId);
				return new Resolucion(Optional.of(new Cubierta(cobertura, arancel.get(), noHabilitada)),
						candidatas, topes, huboAplicables);
			}
		}
		return new Resolucion(Optional.empty(), candidatas, topes, huboAplicables);
	}

	/**
	 * El tope de sesiones por mes del convenio y cuantas ya uso la persona con ese plan en el mes
	 * del dia (en la zona de la sede, en todas las sedes), o {@code null} si el convenio no tiene.
	 *
	 * <p>Lo que no hace: serializar dos cierres simultaneos de la misma persona en la ultima
	 * sesion del tope. Es un caso raro y el peor resultado es una sesion de mas presentada, que la
	 * obra social debita.
	 */
	private TopeEvaluado evaluarTope(
			long organizationId, long consultorioId, long personaId, CoberturaAplicable cobertura,
			ArancelCongelado arancel, LocalDate dia) {

		Integer tope = aranceles.topeDeSesionesMensual(organizationId, arancel.convenioId());
		if (tope == null || tope <= 0) {
			return null;
		}
		ZoneId zona = zonaDeLaSede(organizationId, consultorioId);
		LocalDate primero = dia.withDayOfMonth(1);
		long usadas = obligaciones.contarSesionesCubiertas(
				organizationId, personaId, arancel.planId(),
				primero.atStartOfDay(zona).toInstant(),
				primero.plusMonths(1).atStartOfDay(zona).toInstant());
		// El log del tope alcanzado lo escribe el devengo, que conoce la sesion.
		return new TopeEvaluado(cobertura, arancel.convenioId(), tope, usadas, usadas >= tope);
	}

	/**
	 * DP-11: manda la practica realizada; la principal de la oferta solo si no hay realizadas.
	 *
	 * <p>Con realizadas, la principal va primero si esta entre ellas y el resto por id ascendente:
	 * el orden es determinista para que el mismo hecho elija siempre lo mismo.
	 */
	public static List<Long> candidatas(Set<Long> realizadas, List<PracticaDeOferta> declaradas) {
		Optional<Long> principal = declaradas.stream()
				.filter(PracticaDeOferta::principal)
				.map(PracticaDeOferta::practicaId)
				.findFirst();

		if (realizadas == null || realizadas.isEmpty()) {
			return principal.map(List::of).orElse(List.of());
		}

		List<Long> orden = new ArrayList<>(realizadas.stream().sorted().toList());
		principal.filter(realizadas::contains).ifPresent(p -> {
			orden.remove(p);
			orden.addFirst(p);
		});
		return orden;
	}

	/**
	 * La cobertura elegida en la recepcion o al reservar va primero; el resto conserva el orden de
	 * {@code aplicables} (la principal primero). Si la elegida no aplica, la lista queda igual.
	 */
	private static List<CoberturaAplicable> elegidaPrimero(
			Long elegida, List<CoberturaAplicable> aplicables) {
		if (elegida == null) {
			return aplicables;
		}
		List<CoberturaAplicable> ordenadas = new ArrayList<>(aplicables.size());
		aplicables.stream().filter(c -> c.coberturaId() == elegida).forEach(ordenadas::add);
		aplicables.stream().filter(c -> c.coberturaId() != elegida).forEach(ordenadas::add);
		return ordenadas;
	}

	private ZoneId zonaDeLaSede(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.map(ZonaDeSede::de)
				.orElseGet(() -> {
					log.warn("Sede no resoluble al resolver el arancel: se usa UTC. consultorioId={}",
							consultorioId);
					return ZoneOffset.UTC;
				});
	}

	// =================================================================================
	// La cotizacion: la misma regla, sin devengar, con la explicacion
	// =================================================================================

	/**
	 * Cuanto saldria una atencion de la oferta para la persona ese dia, y por que. Toma la practica
	 * que el devengo tomaria si la sesion cerrara sin tratamientos (la principal de la oferta).
	 *
	 * @param coberturaId la cobertura elegida, o {@code null} (la principal primero)
	 * @param fecha       dia local de la sede
	 * @throws IllegalArgumentException si la oferta no es de esa sede
	 */
	@Transactional(readOnly = true)
	public Cotizacion cotizar(
			long organizationId, long consultorioId, long personaId, long ofertaId,
			Long coberturaId, LocalDate fecha) {

		OfertaSnapshot oferta = ofertas.find(organizationId, consultorioId, ofertaId)
				.orElseThrow(() -> new IllegalArgumentException("No encontré esa oferta en la sede"));
		Optional<PrecioDeOferta> precioDeOferta =
				ofertas.precioVigenteEl(organizationId, consultorioId, ofertaId, fecha);
		BigDecimal precio = precioDeOferta.map(PrecioDeOferta::precioBase).orElse(null);
		String moneda = precioDeOferta.map(PrecioDeOferta::moneda).orElse(MONEDA_POR_DEFECTO);
		boolean admiteObraSocial = precioDeOferta.map(PrecioDeOferta::admiteObraSocial).orElse(false);

		Explicacion e = new Explicacion();
		String nombre = oferta.nombreComercial();
		if (precio != null && precio.signum() > 0) {
			e.paso("Precio particular de " + nombre + ": " + pesos(precio) + " (vigente el "
					+ fecha.format(DIA) + ").");
		}
		else {
			e.paso(nombre + " no tiene precio particular cargado para el " + fecha.format(DIA) + ".");
		}

		if (!admiteObraSocial) {
			e.paso(nombre + " no admite obra social: se cobra como particular.");
			return particular(oferta, precio, moneda, Cotizacion.NO_ADMITE_OS, null, e);
		}

		Resolucion resolucion = resolver(organizationId, consultorioId, personaId, ofertaId, Set.of(),
				coberturaId, fecha, (cobertura, practicaId) -> aranceles.congelar(
						organizationId, consultorioId, cobertura.referencia().financiadorId(),
						cobertura.referencia().planId(), practicaId, ofertaId, fecha));

		for (TopeEvaluado tope : resolucion.topes()) {
			if (tope.alcanzado()) {
				e.paso("Tope de " + tope.tope() + " sesiones por mes de " + planDe(tope.cobertura())
						+ ": lleva " + tope.usadas() + ", esta va como particular.");
			}
		}

		if (resolucion.cubierta().isPresent()) {
			return cubierta(organizationId, consultorioId, oferta, precio, coberturaId, resolucion, e);
		}

		Optional<TopeEvaluado> alcanzado = resolucion.topes().stream()
				.filter(TopeEvaluado::alcanzado).findFirst();
		if (alcanzado.isPresent()) {
			return particular(oferta, precio, moneda, Cotizacion.TOPE_MENSUAL, alcanzado.get(), e);
		}
		if (resolucion.candidatas().isEmpty()) {
			e.paso(nombre + " no declara una práctica principal: no se le puede facturar a una "
					+ "obra social.");
			return particular(oferta, precio, moneda, Cotizacion.SIN_CONVENIO, null, e);
		}
		long practica = resolucion.candidatas().getFirst();
		List<CoberturaNoAplicable> sinConvenio = coberturas.noAplicables(
				organizationId, consultorioId, personaId, practica, ofertaId, fecha);
		if (resolucion.huboAplicables() || !sinConvenio.isEmpty()) {
			sinConvenio.forEach(c -> e.paso(c.referencia().financiadorNombre() + " "
					+ c.referencia().planNombre() + " no tiene convenio vigente para " + nombre + "."));
			if (sinConvenio.isEmpty()) {
				e.paso("La cobertura de la persona no tiene arancel para " + nombre + " ese día.");
			}
			return particular(oferta, precio, moneda, Cotizacion.SIN_CONVENIO, null, e);
		}
		e.paso("La persona no tiene una cobertura vigente ese día: va como particular.");
		return particular(oferta, precio, moneda, Cotizacion.SIN_COBERTURA, null, e);
	}

	private Cotizacion cubierta(
			long organizationId, long consultorioId, OfertaSnapshot oferta, BigDecimal precio,
			Long coberturaElegidaId, Resolucion resolucion, Explicacion e) {

		Cubierta cubierta = resolucion.cubierta().orElseThrow();
		CoberturaAplicable cobertura = cubierta.cobertura();
		ArancelCongelado arancel = cubierta.arancel();
		if (coberturaElegidaId != null && cobertura.coberturaId() != coberturaElegidaId) {
			e.paso("La cobertura elegida no aplica a " + oferta.nombreComercial() + ": se usa "
					+ planDe(cobertura) + ".");
		}

		String alcance = aranceles.conveniosActivos(organizationId, consultorioId).stream()
				.filter(c -> c.convenioId() == arancel.convenioId())
				.findFirst()
				.map(c -> c.delPlan() ? "PLAN" : "SEDE")
				.orElse(null);
		BigDecimal financiador = arancel.importeFinanciador();
		e.paso(planDe(cobertura) + " tiene convenio " + ("PLAN".equals(alcance) ? "del plan"
				: "de la sede") + " (" + arancel.convenioNombre() + "): paga " + pesos(financiador) + ".");

		BigDecimal coseguro = Coseguro.de(precio, financiador);
		if (precio == null) {
			e.paso("Sin precio particular, el coseguro es $ 0.");
		}
		else {
			e.paso("Coseguro = " + numero(precio) + " − " + numero(financiador) + " = "
					+ pesos(coseguro) + (coseguro.signum() == 0 ? " (nunca negativo)." : "."));
		}
		if (financiador.add(coseguro).signum() == 0) {
			e.paso("Práctica sin cargo bajo el convenio: no se devenga deuda.");
		}

		Optional<TopeEvaluado> tope = resolucion.topes().stream()
				.filter(t -> !t.alcanzado() && t.convenioId() == arancel.convenioId()
						&& t.cobertura().coberturaId() == cobertura.coberturaId())
				.findFirst();
		tope.ifPresent(t -> e.paso("Tope de " + t.tope() + " sesiones por mes: lleva " + t.usadas()
				+ ", esta entra en el plan."));
		e.paso("A cobrar al paciente: " + pesos(coseguro) + ".");

		Optional<ArancelVigente> vigente = cobertura.resolucion() == null ? Optional.empty()
				: cobertura.resolucion().valor();
		return new Cotizacion(
				oferta.id(), oferta.nombreComercial(), precio, arancel.moneda(),
				Cotizacion.COBERTURA, null,
				cobertura.coberturaId(), cobertura.referencia().financiadorNombre(),
				cobertura.referencia().planNombre(), arancel.convenioNombre(), alcance,
				financiador, coseguro, coseguro,
				tope.map(TopeEvaluado::tope).orElse(null),
				tope.map(t -> (int) t.usadas()).orElse(null),
				vigente.map(ArancelVigente::arancelVigenciaDesde).orElse(null),
				vigente.map(ArancelVigente::arancelVigenciaHasta).orElse(null),
				e.pasos);
	}

	private static Cotizacion particular(
			OfertaSnapshot oferta, BigDecimal precio, String moneda, String motivo,
			TopeEvaluado tope, Explicacion e) {

		BigDecimal aCobrar = precio != null && precio.signum() > 0 ? precio : null;
		if (aCobrar == null) {
			e.paso("Sin precio particular, el cierre de la sesión se bloquea (oferta sin precio) "
					+ "hasta cargarlo.");
		}
		else {
			e.paso("A cobrar al paciente: " + pesos(aCobrar) + " (particular).");
		}
		if (oferta.grupal()) {
			e.paso("Si la atención es de una clase, sin cobertura no se devenga particular: la clase "
					+ "se cobra por pase o abono.");
		}
		CoberturaAplicable cobertura = tope == null ? null : tope.cobertura();
		return new Cotizacion(
				oferta.id(), oferta.nombreComercial(), precio, moneda,
				Cotizacion.PARTICULAR, motivo,
				cobertura == null ? null : cobertura.coberturaId(),
				cobertura == null ? null : cobertura.referencia().financiadorNombre(),
				cobertura == null ? null : cobertura.referencia().planNombre(),
				null, null, null, null, aCobrar,
				tope == null ? null : tope.tope(),
				tope == null ? null : (int) tope.usadas(),
				null, null, e.pasos);
	}

	private static String planDe(CoberturaAplicable cobertura) {
		return cobertura.referencia().financiadorNombre() + " " + cobertura.referencia().planNombre();
	}

	/** {@code $ 12.000} o {@code $ 12.000,50}. */
	public static String pesos(BigDecimal importe) {
		return "$ " + numero(importe);
	}

	public static String numero(BigDecimal importe) {
		DecimalFormatSymbols simbolos = new DecimalFormatSymbols(Locale.of("es", "AR"));
		simbolos.setGroupingSeparator('.');
		simbolos.setDecimalSeparator(',');
		boolean conCentavos = importe.stripTrailingZeros().scale() > 0;
		return new DecimalFormat(conCentavos ? "#,##0.00" : "#,##0", simbolos).format(importe);
	}

	private static final class Explicacion {
		private final List<String> pasos = new ArrayList<>();

		void paso(String texto) {
			pasos.add(texto);
		}
	}
}
