package com.akine.billing.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.EntradaDeHerramienta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.billing.application.CobroService;
import com.akine.billing.application.CobroView;
import com.akine.billing.application.ObligacionService;
import com.akine.billing.application.ObligacionView;
import com.akine.billing.application.OperatingActor;
import com.akine.billing.domain.EstadoObligacion;
import com.akine.billing.domain.PermissionCodes;
import com.akine.billing.domain.port.DeudorDelPaciente;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code deudas_persona}: la cuenta corriente de una persona para el agente. Lee con
 * {@link ObligacionService#deLaPersona} y {@link CobroService#deLaPersona}, que exigen
 * {@code cobro:register} en la sede; sin el, la {@link AccessDeniedException} sigue hasta el
 * catalogo del agente.
 *
 * <p>Las primeras filas son el resumen, una por moneda (deuda abierta, saldo a favor y neto: nunca se
 * suman importes de monedas distintas); despues una fila por deuda
 * abierta, la mas vieja primero. La parte del financiador no aparece: {@code findDeLaPersona} ya la
 * excluye, porque no la debe el paciente. Mismo criterio de suma que el resumen de la persona
 * ({@link EconomiaEnElResumenDePersona}): una anulada no suma.
 */
@Component
class DeudasEnElAsistente implements HerramientaDelAgente {

	static final int TOPE = 50;
	private static final String MONEDA_POR_DEFECTO = "ARS";

	private final ObligacionService obligaciones;
	private final CobroService cobros;
	private final PacienteDirectory pacientes;
	private final PermissionGuard permisos;

	DeudasEnElAsistente(ObligacionService obligaciones, CobroService cobros, PacienteDirectory pacientes,
			PermissionGuard permisos) {
		this.obligaciones = obligaciones;
		this.cobros = cobros;
		this.pacientes = pacientes;
		this.permisos = permisos;
	}

	@Override
	public String nombre() {
		return "deudas_persona";
	}

	@Override
	public String descripcion() {
		return "Deudas abiertas de una persona (lo que debe el paciente: particular y coseguros) y su saldo "
				+ "a favor, con los totales en la primera fila. No muestra lo que debe la obra social ni el "
				+ "detalle de los cobros. Con persona_id, la cuenta de esa persona; sin persona_id lista quiénes "
				+ "deben (\"¿quiénes nos deben?\"): una fila por persona y moneda con el total adeudado y "
				+ "cuántas deudas tiene, el que más debe primero dentro de cada moneda. Los importes de "
				+ "distintas monedas nunca se suman.";
	}

	@Override
	public String esquemaEntrada() {
		return """
				{"type":"object","additionalProperties":false,
				 "properties":{
				  "persona_id":{"type":"integer","description":"Id de la persona (sale de buscar_persona); sin él, la lista de quiénes deben"}
				 }}""";
	}

	@Override
	public ResultadoDeHerramienta ejecutar(
			ActorDelAsistente actor, Map<String, Object> entrada, ContextoDeConsulta ctx) {

		Long personaId = EntradaDeHerramienta.id(entrada, "persona_id");
		if (actor.organizationId() == null || actor.consultorioId() == null) {
			throw new AccessDeniedException("La consulta requiere una sede activa");
		}
		if (personaId == null) {
			return deudores(actor);
		}
		// El permiso va antes de buscar a la persona: sin cobro:register la respuesta es "sin
		// permiso" exista o no el id, y no se confirma quien esta en el padron.
		permisos.requirePermission(new PermissionQuery(actor.accountId(), PermissionCodes.COBRO_REGISTER,
				actor.organizationId(), actor.consultorioId(), null, Instant.now()));
		if (pacientes.find(actor.organizationId(), personaId).isEmpty()) {
			return new ResultadoDeHerramienta(List.of(), List.of("No encontré esa persona"), null, false);
		}
		OperatingActor operador = new OperatingActor(actor.accountId(), actor.platformAdmin(),
				actor.organizationId(), actor.consultorioId());

		List<ObligacionView> abiertas = obligaciones.deLaPersona(operador, actor.consultorioId(), personaId)
				.stream()
				.filter(o -> !EstadoObligacion.ANULADA.name().equals(o.estado()))
				.filter(o -> o.saldo() != null && o.saldo().signum() > 0)
				.sorted(Comparator.comparing(ObligacionView::devengadaEn).thenComparing(ObligacionView::id))
				.toList();
		// Los importes no se suman entre monedas: un total por moneda, tanto de la deuda como del
		// saldo a favor, y el neto sólo dentro de la misma moneda.
		Map<String, BigDecimal> saldoAFavorPorMoneda = new TreeMap<>();
		cobros.deLaPersona(operador, actor.consultorioId(), personaId).stream()
				.filter(c -> CobroView.VIGENTE.equals(c.estado()))
				.filter(c -> c.saldoAFavor() != null && c.saldoAFavor().signum() > 0)
				.forEach(c -> saldoAFavorPorMoneda.merge(monedaDe(c.moneda()), c.saldoAFavor(), BigDecimal::add));
		Map<String, BigDecimal> deudaPorMoneda = new TreeMap<>();
		Map<String, Integer> deudasPorMoneda = new TreeMap<>();
		for (ObligacionView o : abiertas) {
			deudaPorMoneda.merge(monedaDe(o.moneda()), o.saldo(), BigDecimal::add);
			deudasPorMoneda.merge(monedaDe(o.moneda()), 1, Integer::sum);
		}
		Set<String> monedas = new TreeSet<>(deudaPorMoneda.keySet());
		monedas.addAll(saldoAFavorPorMoneda.keySet());
		if (monedas.isEmpty()) {
			monedas.add(MONEDA_POR_DEFECTO);
		}

		List<Map<String, Object>> filas = new ArrayList<>();
		for (String moneda : monedas) {
			BigDecimal deuda = deudaPorMoneda.getOrDefault(moneda, BigDecimal.ZERO);
			BigDecimal saldoAFavor = saldoAFavorPorMoneda.getOrDefault(moneda, BigDecimal.ZERO);
			Map<String, Object> resumen = new LinkedHashMap<>();
			resumen.put("fila", "TOTAL");
			resumen.put("persona_id", personaId);
			resumen.put("deuda_abierta", deuda);
			resumen.put("deudas_abiertas", deudasPorMoneda.getOrDefault(moneda, 0));
			resumen.put("saldo_a_favor", saldoAFavor);
			resumen.put("neto_a_pagar", deuda.subtract(saldoAFavor).max(BigDecimal.ZERO));
			resumen.put("moneda", moneda);
			filas.add(resumen);
		}

		for (ObligacionView o : abiertas.stream().limit(TOPE).toList()) {
			Map<String, Object> fila = new LinkedHashMap<>();
			fila.put("fila", "DEUDA");
			fila.put("obligacion_id", o.id());
			fila.put("sesion_id", o.sesionId());
			fila.put("consultorio_id", o.consultorioId());
			fila.put("concepto", o.concepto());
			fila.put("detalle", o.snapshotNombre());
			fila.put("devengada_el", o.devengadaEn() == null ? null
					: LocalDate.ofInstant(o.devengadaEn(), ctx.zona()).toString());
			fila.put("importe", o.importeOriginal());
			fila.put("saldo", o.saldo());
			fila.put("moneda", o.moneda());
			fila.put("estado", o.estado());
			filas.add(fila);
		}

		List<String> advertencias = new ArrayList<>();
		if (monedas.size() > 1) {
			advertencias.add("Hay importes en más de una moneda: los totales van por moneda y no se suman entre sí.");
		}
		if (abiertas.size() > TOPE) {
			advertencias.add("Hay " + abiertas.size() + " resultados; muestro los primeros " + TOPE);
		}
		if (abiertas.stream().anyMatch(o -> o.consultorioId() != actor.consultorioId())) {
			advertencias.add("Incluye deudas de otra sede: se cobran desde esa sede.");
		}
		return new ResultadoDeHerramienta(filas, advertencias,
				"/pacientes/" + personaId + "/cuenta-corriente", false);
	}

	/**
	 * Sin persona: quienes deben en la organizacion, el que mas debe primero. El permiso lo exige
	 * {@link ObligacionService#deudoresDelPaciente}; el nombre sale del padron del mismo tenant.
	 */
	private ResultadoDeHerramienta deudores(ActorDelAsistente actor) {
		OperatingActor operador = new OperatingActor(actor.accountId(), actor.platformAdmin(),
				actor.organizationId(), actor.consultorioId());
		List<DeudorDelPaciente> deudores = obligaciones.deudoresDelPaciente(operador, actor.consultorioId());
		List<DeudorDelPaciente> mostrados = deudores.stream().limit(TOPE).toList();
		Map<Long, PacienteSnapshot> padron = pacientes.findAll(actor.organizationId(),
				mostrados.stream().map(DeudorDelPaciente::personaId).distinct().toList());
		List<Map<String, Object>> filas = new ArrayList<>();
		for (DeudorDelPaciente d : mostrados) {
			PacienteSnapshot persona = padron.get(d.personaId());
			Map<String, Object> fila = new LinkedHashMap<>();
			fila.put("persona_id", d.personaId());
			fila.put("nombre", persona == null ? null : persona.nombreCompleto());
			fila.put("total_adeudado", d.totalAdeudado());
			fila.put("deudas_abiertas", d.deudas());
			fila.put("moneda", d.moneda());
			filas.add(fila);
		}
		List<String> advertencias = new ArrayList<>();
		if (deudores.isEmpty()) {
			advertencias.add("Nadie tiene deudas abiertas como paciente.");
		}
		else if (deudores.size() > TOPE) {
			advertencias.add("Hay " + deudores.size() + " resultados; muestro los primeros " + TOPE);
		}
		if (mostrados.stream().map(DeudorDelPaciente::moneda).distinct().count() > 1) {
			advertencias.add("Hay deudas en más de una moneda: una fila por persona y moneda, sin sumar entre monedas.");
		}
		return new ResultadoDeHerramienta(filas, advertencias, null, false);
	}

	private static String monedaDe(String moneda) {
		return moneda == null || moneda.isBlank() ? MONEDA_POR_DEFECTO : moneda;
	}
}
