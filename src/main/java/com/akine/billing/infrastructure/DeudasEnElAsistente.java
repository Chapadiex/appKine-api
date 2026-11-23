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
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.PacienteDirectory;
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

/**
 * {@code deudas_persona}: la cuenta corriente de una persona para el agente. Lee con
 * {@link ObligacionService#deLaPersona} y {@link CobroService#deLaPersona}, que exigen
 * {@code cobro:register} en la sede; sin el, la {@link AccessDeniedException} sigue hasta el
 * catalogo del agente.
 *
 * <p>La primera fila es el resumen (deuda abierta, saldo a favor y neto); despues una fila por deuda
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
				+ "detalle de los cobros. Necesita persona_id.";
	}

	@Override
	public String esquemaEntrada() {
		return """
				{"type":"object","additionalProperties":false,
				 "properties":{
				  "persona_id":{"type":"integer","description":"Id de la persona (sale de buscar_persona)"}
				 },
				 "required":["persona_id"]}""";
	}

	@Override
	public ResultadoDeHerramienta ejecutar(
			ActorDelAsistente actor, Map<String, Object> entrada, ContextoDeConsulta ctx) {

		Long personaId = EntradaDeHerramienta.id(entrada, "persona_id");
		if (personaId == null) {
			throw new IllegalArgumentException("Falta 'persona_id': buscá primero a la persona");
		}
		if (actor.organizationId() == null || actor.consultorioId() == null) {
			throw new AccessDeniedException("La consulta requiere una sede activa");
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
		BigDecimal saldoAFavor = cobros.deLaPersona(operador, actor.consultorioId(), personaId).stream()
				.filter(c -> CobroView.VIGENTE.equals(c.estado()))
				.map(CobroView::saldoAFavor)
				.filter(s -> s != null)
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		BigDecimal deuda = abiertas.stream().map(ObligacionView::saldo).reduce(BigDecimal.ZERO, BigDecimal::add);
		String moneda = abiertas.isEmpty() ? MONEDA_POR_DEFECTO : abiertas.getFirst().moneda();

		List<Map<String, Object>> filas = new ArrayList<>();
		Map<String, Object> resumen = new LinkedHashMap<>();
		resumen.put("fila", "TOTAL");
		resumen.put("persona_id", personaId);
		resumen.put("deuda_abierta", deuda);
		resumen.put("deudas_abiertas", abiertas.size());
		resumen.put("saldo_a_favor", saldoAFavor);
		resumen.put("neto_a_pagar", deuda.subtract(saldoAFavor).max(BigDecimal.ZERO));
		resumen.put("moneda", moneda);
		filas.add(resumen);

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
		if (abiertas.size() > TOPE) {
			advertencias.add("Hay " + abiertas.size() + " resultados; muestro los primeros " + TOPE);
		}
		if (abiertas.stream().anyMatch(o -> o.consultorioId() != actor.consultorioId())) {
			advertencias.add("Incluye deudas de otra sede: se cobran desde esa sede.");
		}
		return new ResultadoDeHerramienta(filas, advertencias,
				"/pacientes/" + personaId + "/cuenta-corriente", false);
	}
}
