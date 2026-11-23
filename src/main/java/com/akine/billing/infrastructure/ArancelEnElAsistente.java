package com.akine.billing.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.EntradaDeHerramienta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.Periodo;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.billing.application.Cotizacion;
import com.akine.billing.application.CotizadorDeAtencion;
import com.akine.contracting.spi.ArancelDirectory;
import com.akine.contracting.spi.ArancelVigente;
import com.akine.contracting.spi.CoberturaCatalogoDirectory;
import com.akine.contracting.spi.ConvenioDeLaSede;
import com.akine.contracting.spi.Coseguro;
import com.akine.contracting.spi.PlanCoberturaSnapshot;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.offering.spi.PracticasDeOfertaDirectory;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.PacienteDirectory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code arancel_vigente}: cuanto sale una actividad y como esta cargado. Sin persona, el precio
 * particular y lo que paga cada plan con convenio en la sede; con persona, la {@link Cotizacion} de
 * {@link CotizadorDeAtencion}, que es la misma regla con la que el cierre devenga.
 *
 * <p>Exige {@code convenio:read} (la lectura del catalogo economico, la de
 * {@code contracting.ArancelService}) y, con persona, {@code paciente:read}. Los dos se evaluan con
 * la sede del contexto, igual que en la pantalla.
 */
@Component
class ArancelEnElAsistente implements HerramientaDelAgente {

	static final int TOPE = 50;
	private static final String CONVENIO_READ = "convenio:read";
	private static final String PACIENTE_READ = "paciente:read";

	private final CotizadorDeAtencion cotizador;
	private final OfertaDirectory ofertas;
	private final PracticasDeOfertaDirectory practicasDeOferta;
	private final ArancelDirectory aranceles;
	private final CoberturaCatalogoDirectory catalogo;
	private final PacienteDirectory pacientes;
	private final PermissionGuard permisos;

	@SuppressWarnings("java:S107")
	ArancelEnElAsistente(
			CotizadorDeAtencion cotizador, OfertaDirectory ofertas,
			PracticasDeOfertaDirectory practicasDeOferta, ArancelDirectory aranceles,
			CoberturaCatalogoDirectory catalogo, PacienteDirectory pacientes, PermissionGuard permisos) {

		this.cotizador = cotizador;
		this.ofertas = ofertas;
		this.practicasDeOferta = practicasDeOferta;
		this.aranceles = aranceles;
		this.catalogo = catalogo;
		this.pacientes = pacientes;
		this.permisos = permisos;
	}

	@Override
	public String nombre() {
		return "arancel_vigente";
	}

	@Override
	public String descripcion() {
		return "Precio de una actividad de la sede: el particular y lo que paga cada obra social o plan con "
				+ "convenio, con el coseguro y el tope de sesiones. Con persona_id calcula lo que le "
				+ "correspondería a esa persona igual que al cerrar la sesión, y explica paso a paso cómo está "
				+ "cargado. No devuelve deudas ni cobros. Necesita la actividad por nombre (oferta) u oferta_id.";
	}

	@Override
	public String esquemaEntrada() {
		return """
				{"type":"object","additionalProperties":false,
				 "properties":{
				  "oferta":{"type":"string","description":"Nombre de la actividad, por ejemplo Kinesiología"},
				  "oferta_id":{"type":"integer","description":"Id de la actividad, si ya se conoce"},
				  "persona_id":{"type":"integer","description":"Id de la persona para calcular su caso con su cobertura"},
				  "cobertura_id":{"type":"integer","description":"Cobertura elegida de la persona; si falta, la principal"},
				  "fecha":{"type":"string","description":"YYYY-MM-DD o hoy, ayer, esta_semana, semana_pasada, este_mes, mes_pasado"}
				 }}""";
	}

	@Override
	public ResultadoDeHerramienta ejecutar(
			ActorDelAsistente actor, Map<String, Object> entrada, ContextoDeConsulta ctx) {

		if (actor.organizationId() == null || actor.consultorioId() == null) {
			throw new AccessDeniedException("La consulta requiere una sede activa");
		}
		long org = actor.organizationId();
		long sede = actor.consultorioId();
		Long personaId = EntradaDeHerramienta.id(entrada, "persona_id");
		Long coberturaId = EntradaDeHerramienta.id(entrada, "cobertura_id");
		LocalDate fecha = Periodo.resolver(entrada.get("fecha"), null, ctx, Periodo.delDia(ctx.hoy())).desde();

		exigir(actor, CONVENIO_READ);
		if (personaId != null) {
			exigir(actor, PACIENTE_READ);
		}
		OfertaSnapshot oferta = oferta(org, sede, entrada, fecha);
		String precios = "/servicios/ofertas/" + oferta.id() + "/precios";

		if (personaId != null) {
			if (pacientes.find(org, personaId).isEmpty()) {
				return new ResultadoDeHerramienta(List.of(), List.of("No encontré esa persona"), null, false);
			}
			Cotizacion c = cotizador.cotizar(org, sede, personaId, oferta.id(), coberturaId, fecha);
			return new ResultadoDeHerramienta(List.of(fila(c, personaId, fecha, precios)), List.of(), precios,
					false);
		}
		return sinPersona(org, sede, oferta, fecha, precios);
	}

	private ResultadoDeHerramienta sinPersona(
			long org, long sede, OfertaSnapshot oferta, LocalDate fecha, String precios) {

		Optional<PrecioDeOferta> precioDeOferta = ofertas.precioVigenteEl(org, sede, oferta.id(), fecha);
		BigDecimal precio = precioDeOferta.map(PrecioDeOferta::precioBase).orElse(null);
		boolean admite = precioDeOferta.map(PrecioDeOferta::admiteObraSocial).orElse(false);

		List<Map<String, Object>> filas = new ArrayList<>();
		List<String> advertencias = new ArrayList<>();
		Map<String, Object> particular = new LinkedHashMap<>();
		particular.put("fila", "PARTICULAR");
		particular.put("oferta_id", oferta.id());
		particular.put("oferta", oferta.nombreComercial());
		particular.put("fecha", fecha.toString());
		particular.put("precio_particular", precio);
		particular.put("moneda", precioDeOferta.map(PrecioDeOferta::moneda).orElse("ARS"));
		particular.put("admite_obra_social", admite);
		particular.put("donde_se_carga", precios);
		filas.add(particular);
		if (precio == null || precio.signum() <= 0) {
			advertencias.add("La actividad no tiene precio particular ese día: sin cobertura, la sesión no "
					+ "se puede cerrar hasta cargarlo.");
		}
		if (!admite) {
			advertencias.add("La actividad no admite obra social: se cobra siempre como particular.");
			return new ResultadoDeHerramienta(filas, advertencias, precios, false);
		}
		List<Long> candidatas = CotizadorDeAtencion.candidatas(Set.of(),
				practicasDeOferta.practicasHabilitadas(org, sede, oferta.id()));
		if (candidatas.isEmpty()) {
			advertencias.add("La actividad no declara una práctica principal: no se le puede facturar a una "
					+ "obra social.");
			return new ResultadoDeHerramienta(filas, advertencias, precios, false);
		}
		long practica = candidatas.getFirst();

		List<ConvenioDeLaSede> convenios = aranceles.conveniosActivos(org, sede);
		Set<String> vistos = new HashSet<>();
		int planes = 0;
		int sinPrecio = 0;
		for (ConvenioDeLaSede convenio : convenios) {
			// El de la sede viene primero y manda sobre el del plan: un plan, una fila.
			if (!vistos.add(convenio.financiadorId() + ":" + convenio.planId())) {
				continue;
			}
			Optional<ArancelVigente> vigente = aranceles.resolver(org, sede, convenio.financiadorId(),
					convenio.planId(), practica, oferta.id(), fecha).valor();
			if (vigente.isEmpty()) {
				sinPrecio++;
				continue;
			}
			planes++;
			// La fila PARTICULAR cuenta dentro del tope de 50.
			if (filas.size() >= TOPE) {
				continue;
			}
			filas.add(filaDePlan(org, oferta, vigente.get(), convenios, precio));
		}
		int resultados = 1 + planes;
		if (resultados > TOPE) {
			advertencias.add("Hay " + resultados + " resultados; muestro los primeros " + TOPE);
		}
		if (sinPrecio > 0) {
			advertencias.add(sinPrecio + " plan(es) con convenio no tienen precio vigente para esta actividad "
					+ "ese día: esas personas van como particular.");
		}
		return new ResultadoDeHerramienta(filas, advertencias, precios, false);
	}

	private Map<String, Object> filaDePlan(
			long org, OfertaSnapshot oferta, ArancelVigente a, List<ConvenioDeLaSede> convenios,
			BigDecimal precio) {

		Optional<PlanCoberturaSnapshot> plan = catalogo.findPlan(org, a.planId());
		boolean delPlan = convenios.stream()
				.anyMatch(c -> c.convenioId() == a.convenioId() && c.delPlan());
		BigDecimal coseguro = Coseguro.de(precio, a.importeFinanciador());
		Map<String, Object> fila = new LinkedHashMap<>();
		fila.put("fila", "PLAN");
		fila.put("oferta_id", oferta.id());
		fila.put("financiador_id", a.financiadorId());
		fila.put("financiador", plan.map(PlanCoberturaSnapshot::financiadorNombre).orElse(null));
		fila.put("plan_id", a.planId());
		fila.put("plan", plan.map(PlanCoberturaSnapshot::nombre).orElse(null));
		fila.put("convenio", a.convenioNombre());
		fila.put("alcance_convenio", delPlan ? "PLAN" : "SEDE");
		fila.put("paga_la_obra_social", a.importeFinanciador());
		fila.put("coseguro", precio == null ? null : coseguro);
		fila.put("moneda", a.moneda());
		fila.put("tope_mensual", a.limiteSesionesMensual());
		fila.put("vigente_desde", a.arancelVigenciaDesde() == null ? null : a.arancelVigenciaDesde().toString());
		fila.put("vigente_hasta", a.arancelVigenciaHasta() == null ? null : a.arancelVigenciaHasta().toString());
		fila.put("donde_se_carga", "/contratacion/financiadores/" + a.financiadorId() + "/planes");
		return fila;
	}

	private static Map<String, Object> fila(Cotizacion c, long personaId, LocalDate fecha, String precios) {
		Map<String, Object> fila = new LinkedHashMap<>();
		fila.put("fila", "COTIZACION");
		fila.put("persona_id", personaId);
		fila.put("oferta_id", c.ofertaId());
		fila.put("oferta", c.ofertaNombre());
		fila.put("fecha", fecha.toString());
		fila.put("condicion", c.condicion());
		fila.put("motivo_particular", c.motivoParticular());
		fila.put("precio_particular", c.precioParticular());
		fila.put("moneda", c.moneda());
		fila.put("cobertura_id", c.coberturaId());
		fila.put("financiador", c.financiador());
		fila.put("plan", c.plan());
		fila.put("convenio", c.convenio());
		fila.put("alcance_convenio", c.alcanceConvenio());
		fila.put("paga_la_obra_social", c.importeFinanciador());
		fila.put("coseguro", c.coseguro());
		fila.put("a_cobrar_al_paciente", c.aCobrarAlPaciente());
		fila.put("tope_mensual", c.topeMensual());
		fila.put("sesiones_cubiertas_en_el_mes", c.sesionesCubiertasEnElMes());
		fila.put("vigente_desde", c.vigenteDesde() == null ? null : c.vigenteDesde().toString());
		fila.put("vigente_hasta", c.vigenteHasta() == null ? null : c.vigenteHasta().toString());
		fila.put("pasos", c.pasos());
		List<String> donde = new ArrayList<>();
		donde.add(precios);
		// La ruta del plan necesita el financiador; la cotizacion trae el nombre, asi que se indica
		// la pantalla de financiadores.
		if (c.financiador() != null) {
			donde.add("/contratacion/financiadores");
		}
		fila.put("donde_se_carga", donde);
		return fila;
	}

	/** Por id, o por nombre entre las activas y vigentes de la sede. */
	private OfertaSnapshot oferta(long org, long sede, Map<String, Object> entrada, LocalDate fecha) {
		Long ofertaId = EntradaDeHerramienta.id(entrada, "oferta_id");
		if (ofertaId != null) {
			return ofertas.find(org, sede, ofertaId)
					.orElseThrow(() -> new IllegalArgumentException("No encontré la actividad " + ofertaId
							+ " en la sede"));
		}
		String texto = EntradaDeHerramienta.texto(entrada, "oferta");
		if (texto == null) {
			throw new IllegalArgumentException("Falta la actividad: pasá 'oferta' (nombre) u 'oferta_id'");
		}
		List<OfertaSnapshot> activas = ofertas.activasDeLaSede(org, sede).stream()
				.filter(o -> o.vigenteEl(fecha))
				.toList();
		String buscado = clave(texto);
		List<OfertaSnapshot> exactas = activas.stream()
				.filter(o -> clave(o.nombreComercial()).equals(buscado)).toList();
		if (exactas.size() == 1) {
			return exactas.getFirst();
		}
		List<OfertaSnapshot> parecidas = activas.stream()
				.filter(o -> clave(o.nombreComercial()).contains(buscado)).toList();
		if (parecidas.size() == 1) {
			return parecidas.getFirst();
		}
		List<String> nombres = (parecidas.isEmpty() ? activas : parecidas).stream()
				.map(o -> o.nombreComercial() + " (oferta_id " + o.id() + ")")
				.limit(20)
				.toList();
		throw new IllegalArgumentException((parecidas.isEmpty()
				? "No encontré la actividad '" + texto + "'. Las activas son: "
				: "Hay varias actividades que coinciden con '" + texto + "': ")
				+ String.join(", ", nombres) + ". Pasá oferta_id.");
	}

	private static String clave(String texto) {
		return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
				.toLowerCase(Locale.ROOT).strip();
	}

	private void exigir(ActorDelAsistente actor, String permiso) {
		permisos.requirePermission(new PermissionQuery(actor.accountId(), permiso, actor.organizationId(),
				actor.consultorioId(), null, Instant.now()));
	}
}
