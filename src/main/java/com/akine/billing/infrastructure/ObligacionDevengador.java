package com.akine.billing.infrastructure;

import com.akine.billing.domain.ConceptoObligacion;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.Responsable;
import com.akine.billing.domain.SnapshotDeConvenio;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.contracting.spi.ArancelCongelado;
import com.akine.contracting.spi.ArancelDirectory;
import com.akine.encounter.spi.CierreDeSesionObserver;
import com.akine.encounter.spi.SesionCerrada;
import com.akine.offering.spi.PracticaDeOferta;
import com.akine.offering.spi.PracticasDeOfertaDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.person.spi.CoberturaAplicable;
import com.akine.person.spi.CoberturasAplicablesDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Convierte el cierre de una atencion en la deuda que le corresponde (RF-M18-001, RF-M18-002,
 * RN-M18-001, RN-M18-002).
 *
 * <p>La direccion de la dependencia es {@code billing -> encounter}: {@code encounter} declara el
 * contrato y no sabe que existe este bean. Poner la llamada al reves —que la sesion invoque a
 * facturacion— haria que el modulo clinico dependa del economico, que es exactamente el acoplamiento
 * que el UML de 2019 tenia y que el modelo actual deshace.
 *
 * <h2>Que se devenga (AKINE F-4). Diseno: docs/diseno/AKINE-F-4-obligacion-financiador.md</h2>
 *
 * <pre>
 *   1. sin asistencia                         -&gt; nada
 *   2. la sesion ya tiene deuda               -&gt; nada (idempotencia por hecho de origen)
 *   3. la oferta no admite obra social, o la recepcion del turno se resolvio como
 *      Particular (E-7, RF-M08-007)          -&gt; PARTICULAR
 *   4. practicas candidatas (DP-11)
 *   5. primera practica con cobertura aplicable (B-2) y arancel congelado
 *                                             -&gt; FINANCIADOR (importe_financiador)
 *                                                + COSEGURO  (coseguro), cada una si es &gt; 0
 *   6. ninguna                                -&gt; PARTICULAR (precio de la oferta, como 07.01)
 * </pre>
 *
 * <p><b>B-3:</b> el arancel se resuelve CON la oferta de la sesion: si el convenio pacto un arancel
 * especifico para esa oferta (RF-M16-008), manda sobre el general de la practica, y el snapshot
 * congela ese. El precio particular es el vigente el dia del cierre (RF-M16-009), y lo trae
 * {@code encounter} en {@link SesionCerrada#precioDeLaOferta()}.
 *
 * <p><b>Una obligacion por responsable y por sesion, no una por practica.</b> No hay RF que diga si
 * el financiador paga por sesion o por practica; se toma la mas conservadora: facturar de mas a un
 * financiador es un debito en cada lote, facturar de menos es plata que se reclama despues. Es
 * decision a revisar, y cambiarla cambia el unique de V36.
 *
 * <h2>Las reglas de 07.01 que siguen</h2>
 *
 * <ol>
 *   <li><b>Sin asistencia no hay deuda.</b> Una ausencia no es una prestacion, y cobrar un no-show
 *       es una politica de centro que no existe.</li>
 *   <li><b>Sin precio no hay deuda particular.</b> Se loguea y no se lanza: dejar al profesional
 *       sin cerrar su atencion por un dato administrativo ajeno seria peor. La parte del convenio
 *       no necesita el precio de la oferta: sale del arancel.</li>
 *   <li><b>Un observador que falla hace fallar el cierre</b> (contrapartida asumida en 07.01 y que
 *       F-4 mantiene). Por eso <b>ningun desenlace de negocio lanza</b>: sin cobertura, sin
 *       convenio, sin arancel o sin practica, el devengo cae a particular. Lo unico que propaga es
 *       un fallo tecnico, que tiene que revertir el cierre entero.</li>
 * </ol>
 */
@Component
public class ObligacionDevengador implements CierreDeSesionObserver {

	private static final Logger log = LoggerFactory.getLogger(ObligacionDevengador.class);

	private final ObligacionRepositoryPort obligaciones;
	private final CoberturasAplicablesDirectory coberturas;
	private final ArancelDirectory aranceles;
	private final PracticasDeOfertaDirectory practicasDeOferta;
	private final ConsultorioDirectory consultorios;

	public ObligacionDevengador(
			ObligacionRepositoryPort obligaciones,
			CoberturasAplicablesDirectory coberturas,
			ArancelDirectory aranceles,
			PracticasDeOfertaDirectory practicasDeOferta,
			ConsultorioDirectory consultorios) {

		this.obligaciones = obligaciones;
		this.coberturas = coberturas;
		this.aranceles = aranceles;
		this.practicasDeOferta = practicasDeOferta;
		this.consultorios = consultorios;
	}

	@Override
	public void alCerrar(SesionCerrada cierre) {
		if (!cierre.asistio()) {
			log.info("Sesion cerrada sin asistencia: no se devenga deuda. sesionId={}", cierre.sesionId());
			return;
		}

		// Cualquier responsable, no solo el paciente: ver ObligacionRepositoryPort#findDeLaSesion.
		if (!obligaciones.findDeLaSesion(cierre.sesionId()).isEmpty()) {
			return;
		}

		// AKINE E-7 (RF-M08-007): la recepcion lo resolvio como Particular -> no se busca
		// cobertura. Ni financiador ni coseguro: solo la deuda del paciente al precio del dia.
		Optional<Cubierta> cubierta = cierre.ofertaAdmiteObraSocial()
				&& !cierre.particularPorRecepcion()
				? cubierta(cierre)
				: Optional.empty();

		if (cubierta.isPresent()) {
			devengarPorConvenio(cierre, cubierta.get());
		} else {
			devengarParticular(cierre);
		}
	}

	// =================================================================================
	// Particular: 07.01 tal cual
	// =================================================================================

	private void devengarParticular(SesionCerrada cierre) {
		BigDecimal precio = cierre.precioDeLaOferta();
		if (precio == null || precio.signum() <= 0) {
			// Se registra y no se lanza: hacer fallar el cierre clinico porque falta un precio
			// dejaria al profesional sin poder terminar su atencion por un dato administrativo que
			// no es suyo. Queda en el log para que se note, que es el punto medio honesto.
			log.warn("Sesion cerrada sobre una oferta sin precio: no se devenga deuda. "
					+ "sesionId={} ofertaId={}", cierre.sesionId(), cierre.ofertaId());
			return;
		}

		Obligacion obligacion = obligaciones.save(new Obligacion(
				cierre.organizationId(),
				cierre.consultorioId(),
				cierre.sesionId(),
				cierre.personaId(),
				Responsable.PACIENTE,
				precio,
				cierre.moneda(),
				cierre.ofertaId(),
				nombre(cierre),
				cierre.cerradaEn()));

		log.info("Obligacion devengada: obligacionId={} sesionId={} personaId={} concepto=PARTICULAR "
						+ "importe={} {}",
				obligacion.getId(), cierre.sesionId(), cierre.personaId(), precio, cierre.moneda());
	}

	// =================================================================================
	// Convenio: la parte del financiador y el coseguro, de un arancel congelado
	// =================================================================================

	private void devengarPorConvenio(SesionCerrada cierre, Cubierta cubierta) {
		ArancelCongelado arancel = cubierta.arancel();
		SnapshotDeConvenio snapshot = new SnapshotDeConvenio(
				arancel.convenioId(),
				arancel.convenioCodigo(),
				arancel.convenioNombre(),
				arancel.planId(),
				arancel.arancelId(),
				arancel.practicaId(),
				cubierta.cobertura().coberturaId(),
				arancel.importeTotal(),
				arancel.importeFinanciador(),
				arancel.coseguro(),
				arancel.requeriaOrden(),
				arancel.requeriaAutorizacion(),
				arancel.requeriaCredencial(),
				cubierta.cobertura().credencialVencida(),
				arancel.vigenteEl(),
				arancel.capturadoEl());

		if (snapshot.importeTotal().signum() == 0) {
			// Total cero es una practica sin cargo bajo ese convenio (V43 lo admite): no hay deuda
			// de nadie, y devengar una particular encima seria cobrarle al paciente lo que el
			// convenio dice que no se cobra.
			log.info("Practica sin cargo bajo el convenio: no se devenga deuda. sesionId={} "
					+ "convenioId={} arancelId={}", cierre.sesionId(), arancel.convenioId(),
					arancel.arancelId());
			return;
		}

		if (cubierta.alertaPracticaNoHabilitada()) {
			// DP-11: alerta, nunca rechazo. Queda ademas en la fila, que es donde se ve.
			log.warn("Practica facturada que la oferta no declara: sesionId={} ofertaId={} "
					+ "practicaId={}", cierre.sesionId(), cierre.ofertaId(), arancel.practicaId());
		}

		devengarParte(cierre, ConceptoObligacion.FINANCIADOR, arancel.financiadorId(), cubierta,
				snapshot);
		devengarParte(cierre, ConceptoObligacion.COSEGURO, null, cubierta, snapshot);
	}

	/** Una parte en cero no genera fila: V36 no admite deudas de cero. */
	private void devengarParte(
			SesionCerrada cierre, ConceptoObligacion concepto, Long financiadorId,
			Cubierta cubierta, SnapshotDeConvenio snapshot) {

		BigDecimal parte = snapshot.parteDe(concepto);
		if (parte.signum() <= 0) {
			return;
		}
		Obligacion obligacion = obligaciones.save(Obligacion.porConvenio(
				cierre.organizationId(),
				cierre.consultorioId(),
				cierre.sesionId(),
				cierre.personaId(),
				concepto,
				financiadorId,
				cubierta.arancel().moneda(),
				cierre.ofertaId(),
				nombre(cierre),
				cierre.cerradaEn(),
				snapshot,
				cubierta.alertaPracticaNoHabilitada()));

		log.info("Obligacion devengada: obligacionId={} sesionId={} personaId={} concepto={} "
						+ "financiadorId={} convenioId={} importe={} {}",
				obligacion.getId(), cierre.sesionId(), cierre.personaId(), concepto, financiadorId,
				snapshot.convenioId(), parte, cubierta.arancel().moneda());
	}

	// =================================================================================
	// Que practica y que cobertura
	// =================================================================================

	/**
	 * La primera practica candidata con una cobertura aplicable y un arancel que se pueda congelar.
	 *
	 * <p>{@code aplicables} es una lectura viva y {@code congelar} otra: si entre las dos alguien
	 * da de baja el arancel, {@code congelar} devuelve vacio y se prueba la siguiente. En el peor
	 * caso se cae a particular. Nunca se lanza: ver la regla 3 de la cabecera.
	 */
	private Optional<Cubierta> cubierta(SesionCerrada cierre) {
		List<PracticaDeOferta> declaradas = practicasDeOferta.practicasHabilitadas(
				cierre.organizationId(), cierre.consultorioId(), cierre.ofertaId());
		List<Long> candidatas = candidatas(cierre, declaradas);
		if (candidatas.isEmpty()) {
			return Optional.empty();
		}

		LocalDate dia = diaLocalDeLaSede(cierre);
		for (long practicaId : candidatas) {
			List<CoberturaAplicable> aplicables = coberturas.aplicables(
					cierre.organizationId(), cierre.consultorioId(), cierre.personaId(), practicaId,
					cierre.ofertaId(), dia);
			for (CoberturaAplicable cobertura : aplicables) {
				Optional<ArancelCongelado> arancel = aranceles.congelar(
						cierre.organizationId(), cierre.consultorioId(),
						cobertura.referencia().financiadorId(), cobertura.referencia().planId(),
						practicaId, cierre.ofertaId(), dia);
				if (arancel.isPresent()) {
					boolean noHabilitada = !declaradas.isEmpty() && declaradas.stream()
							.noneMatch(p -> p.practicaId() == practicaId);
					return Optional.of(new Cubierta(cobertura, arancel.get(), noHabilitada));
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * DP-11: manda la practica realizada; la principal de la oferta solo si la sesion cerro sin
	 * tratamientos.
	 *
	 * <p>Con tratamientos, la principal va primero si esta entre las realizadas y el resto por id
	 * ascendente: el orden es determinista para que el mismo hecho elija siempre lo mismo.
	 */
	static List<Long> candidatas(SesionCerrada cierre, List<PracticaDeOferta> declaradas) {
		Optional<Long> principal = declaradas.stream()
				.filter(PracticaDeOferta::principal)
				.map(PracticaDeOferta::practicaId)
				.findFirst();

		Set<Long> realizadas = cierre.practicasRealizadas();
		if (realizadas.isEmpty()) {
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
	 * El dia del calendario en que ocurrio el cierre, en la zona de la sede. Mismo criterio que el
	 * consumo de autorizaciones (C-4): la vigencia de un convenio es un dia, y resolverla en UTC
	 * corre el dia despues de las 21 h. Si la sede no resuelve se cae a UTC y se deja dicho.
	 */
	private LocalDate diaLocalDeLaSede(SesionCerrada cierre) {
		ZoneId zona = consultorios.find(cierre.organizationId(), cierre.consultorioId())
				.map(ConsultorioSnapshot::timezone)
				.map(declarada -> zonaDe(declarada, cierre))
				.orElseGet(() -> {
					log.warn("Sede no resoluble al devengar: se usa UTC. sesionId={} "
							+ "consultorioId={}", cierre.sesionId(), cierre.consultorioId());
					return ZoneOffset.UTC;
				});
		return LocalDate.ofInstant(cierre.cerradaEn(), zona);
	}

	private static ZoneId zonaDe(String zona, SesionCerrada cierre) {
		try {
			return ZoneId.of(zona);
		} catch (DateTimeException zonaInvalida) {
			log.warn("Zona horaria invalida en la sede: se usa UTC. sesionId={} zona={}",
					cierre.sesionId(), zona);
			return ZoneOffset.UTC;
		}
	}

	private static String nombre(SesionCerrada cierre) {
		return "Sesion " + cierre.numeroSesion();
	}

	/** La cobertura que se aplico, su arancel congelado y la alerta de DP-11. */
	private record Cubierta(
			CoberturaAplicable cobertura, ArancelCongelado arancel, boolean alertaPracticaNoHabilitada) {
	}
}
