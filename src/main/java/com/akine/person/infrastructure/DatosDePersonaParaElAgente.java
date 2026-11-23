package com.akine.person.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.EntradaDeHerramienta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.person.application.CoberturaEstadoFiltro;
import com.akine.person.application.CoberturaPacienteService;
import com.akine.person.application.CoberturaView;
import com.akine.person.application.MascaraDeAfiliado;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.PersonaService;
import com.akine.person.application.PersonaView;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code datos_persona}: datos permitidos del padron (nombre, edad de nacimiento, si es paciente) y sus
 * coberturas vigentes, con el afiliado enmascarado. Sin telefono, correo, notas ni documento completo.
 */
@Component
class DatosDePersonaParaElAgente implements HerramientaDelAgente {

	private final PersonaService personas;
	private final CoberturaPacienteService coberturas;

	DatosDePersonaParaElAgente(PersonaService personas, CoberturaPacienteService coberturas) {
		this.personas = personas;
		this.coberturas = coberturas;
	}

	@Override
	public String nombre() {
		return "datos_persona";
	}

	@Override
	public String descripcion() {
		return "Devuelve los datos de una persona del padron por persona_id: nombre, documento enmascarado, "
				+ "fecha de nacimiento, si es paciente, y sus coberturas vigentes (financiador, plan, afiliado "
				+ "enmascarado, principal). No devuelve contacto ni datos clinicos. Necesita persona_id (usa "
				+ "buscar_persona antes).";
	}

	@Override
	public String esquemaEntrada() {
		return """
				{"type":"object","additionalProperties":false,"properties":{
				"persona_id":{"type":"integer","description":"Id de la persona, de buscar_persona"}},
				"required":["persona_id"]}""";
	}

	@Override
	public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
			ContextoDeConsulta ctx) {
		Long personaId = EntradaDeHerramienta.id(entrada, "persona_id");
		if (personaId == null) {
			throw new IllegalArgumentException("Falta 'persona_id'");
		}
		OperatingActor operador = new OperatingActor(actor.accountId(), actor.platformAdmin(),
				actor.organizationId(), actor.consultorioId());
		PersonaView persona;
		try {
			persona = personas.ver(operador, personaId);
		}
		catch (PersonaNotAccessibleException e) {
			return new ResultadoDeHerramienta(List.of(), List.of("No encontré esa persona"), null, false);
		}
		Map<String, Object> fila = new LinkedHashMap<>();
		fila.put("persona_id", persona.id());
		fila.put("nombre", persona.apellido() + ", " + persona.nombre());
		fila.put("documento", PersonasParaElAgente.enmascarado(persona.tipoDocumento(), persona.numeroDocumento()));
		fila.put("fecha_nacimiento", persona.fechaNacimiento());
		fila.put("es_paciente", persona.esPaciente());
		fila.put("estado", persona.estado());
		List<Map<String, Object>> vigentes = new ArrayList<>();
		for (CoberturaView c : coberturas.listar(operador, personaId, CoberturaEstadoFiltro.ACTIVA, null)) {
			if (!c.vigente()) {
				continue;
			}
			Map<String, Object> cobertura = new LinkedHashMap<>();
			cobertura.put("tipo", c.tipo());
			cobertura.put("financiador", c.financiadorNombre());
			cobertura.put("plan", c.planNombre());
			cobertura.put("afiliado", MascaraDeAfiliado.enmascarar(c.numeroAfiliado()));
			cobertura.put("principal", c.principal());
			cobertura.put("vigencia_hasta", c.vigenciaHasta());
			cobertura.put("credencial_vencida", c.credencialVencida());
			vigentes.add(cobertura);
		}
		fila.put("coberturas_vigentes", vigentes);
		return new ResultadoDeHerramienta(List.of(fila), List.of(), null, false);
	}
}
