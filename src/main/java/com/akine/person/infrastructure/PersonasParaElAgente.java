package com.akine.person.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.EntradaDeHerramienta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.PersonaBusqueda;
import com.akine.person.application.PersonaEstadoFiltro;
import com.akine.person.application.PersonaPagina;
import com.akine.person.application.PersonaService;
import com.akine.person.application.PersonaView;
import com.akine.platform.spi.listado.TextoDeBusqueda;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code buscar_persona}: resuelve un nombre del padron a un {@code persona_id}. Exige {@code paciente:read}. */
@Component
class PersonasParaElAgente implements HerramientaDelAgente {

	static final int MAXIMO = 10;

	private final PersonaService personas;

	PersonasParaElAgente(PersonaService personas) {
		this.personas = personas;
	}

	@Override
	public String nombre() {
		return "buscar_persona";
	}

	@Override
	public String descripcion() {
		return "Busca personas activas del padron por nombre, apellido o documento y devuelve su persona_id, "
				+ "nombre y documento enmascarado. Usala primero cuando la pregunta nombra a alguien. "
				+ "No devuelve datos de contacto ni clinicos.";
	}

	@Override
	public String esquemaEntrada() {
		return """
				{"type":"object","additionalProperties":false,"properties":{
				"texto":{"type":"string","description":"Nombre, apellido o documento de la persona a buscar"}},
				"required":["texto"]}""";
	}

	@Override
	public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
			ContextoDeConsulta ctx) {
		String texto = EntradaDeHerramienta.texto(entrada, "texto");
		if (texto == null) {
			throw new IllegalArgumentException("Falta 'texto': el nombre o documento a buscar");
		}
		OperatingActor operador = new OperatingActor(actor.accountId(), actor.platformAdmin(),
				actor.organizationId(), actor.consultorioId());
		PersonaPagina pagina = personas.buscar(operador,
				new PersonaBusqueda(texto, PersonaEstadoFiltro.ACTIVO, null), 0, MAXIMO);
		List<PersonaView> encontradas = pagina.contenido();
		long total = pagina.total();
		String[] palabras = TextoDeBusqueda.plegar(texto).trim().split("\\s+");
		if (encontradas.isEmpty() && palabras.length > 1) {
			// "Juan Perez" no coincide con ningun campo entero: se busca por la palabra mas larga y se
			// quedan las personas cuyo nombre completo contiene todas las palabras.
			String ancla = java.util.Arrays.stream(palabras).max(java.util.Comparator.comparingInt(String::length))
					.orElseThrow();
			encontradas = personas.buscar(operador, new PersonaBusqueda(ancla, PersonaEstadoFiltro.ACTIVO, null), 0,
					MAXIMO * 5).contenido().stream()
					.filter(p -> contieneTodas(p, palabras))
					.limit(MAXIMO)
					.toList();
			total = encontradas.size();
		}
		List<Map<String, Object>> filas = encontradas.stream().map(PersonasParaElAgente::fila).toList();
		List<String> advertencias = total > filas.size()
				? List.of("Hay " + total + " resultados; muestro los primeros " + filas.size()
						+ ". Afiná la búsqueda.")
				: List.of();
		return new ResultadoDeHerramienta(filas, advertencias, null, false);
	}

	private static boolean contieneTodas(PersonaView persona, String[] palabras) {
		String completo = TextoDeBusqueda.plegar(persona.nombre() + " " + persona.apellido());
		return java.util.Arrays.stream(palabras).allMatch(completo::contains);
	}

	private static Map<String, Object> fila(PersonaView persona) {
		Map<String, Object> fila = new LinkedHashMap<>();
		fila.put("persona_id", persona.id());
		fila.put("nombre", persona.apellido() + ", " + persona.nombre());
		fila.put("documento", enmascarado(persona.tipoDocumento(), persona.numeroDocumento()));
		return fila;
	}

	/** "DNI ***456". Sin documento, {@code null}. */
	static String enmascarado(String tipo, String numero) {
		if (numero == null || numero.isBlank()) {
			return null;
		}
		String ultimos = numero.length() <= 3 ? numero : numero.substring(numero.length() - 3);
		return (tipo == null ? "" : tipo + " ") + "***" + ultimos;
	}
}
