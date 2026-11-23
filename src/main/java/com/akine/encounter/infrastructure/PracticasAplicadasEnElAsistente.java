package com.akine.encounter.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.EntradaDeHerramienta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.Periodo;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.encounter.application.OperatingActor;
import com.akine.encounter.application.TratamientoService;
import com.akine.encounter.domain.port.TratamientoRepositoryPorts.TratamientoAplicadoEnSede;
import com.akine.person.spi.PacienteDirectory;
import com.akine.platform.spi.listado.TextoDeBusqueda;
import com.akine.resource.spi.CatalogoDirectory;
import com.akine.resource.spi.CatalogoSnapshot;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@code practicas_aplicadas}: a quien se le aplico una practica, o que practicas se le aplicaron a
 * una persona, en sesiones cerradas de la sede activa.
 *
 * <p>Lee con {@link TratamientoService#aplicados}, que exige {@code hc:read} y recorta a la
 * actividad propia si el permiso viene limitado: es el caso que el SQL libre no puede resolver
 * (diseño §10.8), porque las vistas no saben de relacion asistencial.
 *
 * <p>Lo planificado no es lo realizado (RN-M14-004): esto lee tratamientos realmente registrados en
 * una atencion cerrada, no el Plan de Tratamiento ni los turnos.
 */
@Component
class PracticasAplicadasEnElAsistente implements HerramientaDelAgente {

	static final String SOLO_PROPIOS = "Sólo tus pacientes: tu permiso para ver historias clínicas está "
			+ "limitado a tu propia actividad.";
	static final String SIN_PERSONA = "No encontré esa persona";

	private final TratamientoService tratamientos;
	private final CatalogoDirectory catalogo;
	private final PacienteDirectory personas;

	PracticasAplicadasEnElAsistente(TratamientoService tratamientos, CatalogoDirectory catalogo,
			PacienteDirectory personas) {
		this.tratamientos = tratamientos;
		this.catalogo = catalogo;
		this.personas = personas;
	}

	@Override
	public String nombre() {
		return "practicas_aplicadas";
	}

	@Override
	public String descripcion() {
		return "Tratamientos realmente aplicados en sesiones cerradas de la sede: a quién se le aplicó una "
				+ "práctica (por nombre o código) o qué prácticas recibió una persona, en un período (por "
				+ "defecto los últimos 30 días). No responde turnos, planes de tratamiento ni sesiones "
				+ "abiertas. Necesita 'practica' o 'persona_id' (o las dos).";
	}

	@Override
	public String esquemaEntrada() {
		return """
				{"type":"object","additionalProperties":false,"properties":{
				"practica":{"type":"string","description":"Nombre o código de la práctica, sin importar tildes ni mayúsculas (p. ej. TENS)"},
				"persona_id":{"type":"integer","description":"Id de la persona, de buscar_persona"},
				"desde":{"type":"string","description":"YYYY-MM-DD o hoy, ayer, esta_semana, semana_pasada, este_mes, mes_pasado"},
				"hasta":{"type":"string","description":"YYYY-MM-DD o hoy, ayer, esta_semana, semana_pasada, este_mes, mes_pasado"}}}""";
	}

	@Override
	public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
			ContextoDeConsulta ctx) {

		String practica = EntradaDeHerramienta.texto(entrada, "practica");
		Long personaId = EntradaDeHerramienta.id(entrada, "persona_id");
		if (practica == null && personaId == null) {
			throw new IllegalArgumentException("Indicá 'practica' o 'persona_id' (o las dos)");
		}
		Periodo periodo = Periodo.resolver(entrada.get("desde"), entrada.get("hasta"), ctx,
				Periodo.ultimosDias(ctx.hoy(), 30));

		List<String> advertencias = new ArrayList<>();
		if (personaId != null && personas.find(actor.organizationId(), personaId).isEmpty()) {
			return new ResultadoDeHerramienta(List.of(), List.of(SIN_PERSONA), null, false);
		}

		List<Long> practicaIds = List.of();
		if (practica != null) {
			List<CatalogoSnapshot> candidatas = resolverPractica(actor.organizationId(), practica);
			if (candidatas.isEmpty()) {
				return new ResultadoDeHerramienta(List.of(),
						List.of("No encontré la práctica '" + practica + "'"), null, false);
			}
			if (candidatas.size() > 1) {
				advertencias.add("'" + practica + "' coincide con varias prácticas; incluyo todas: "
						+ candidatas.stream().map(c -> c.name() + " (" + c.codigo() + ")")
								.collect(Collectors.joining(", ")));
			}
			practicaIds = candidatas.stream().map(CatalogoSnapshot::id).toList();
		}

		OperatingActor operador = new OperatingActor(actor.accountId(), actor.platformAdmin(),
				actor.organizationId(), actor.consultorioId());
		TratamientoService.Aplicados aplicados = tratamientos.aplicados(operador, actor.consultorioId(),
				periodo.desde(), periodo.hastaExclusivo(), ctx.zona(), practicaIds, personaId);

		if (aplicados.soloPropios()) {
			advertencias.add(SOLO_PROPIOS);
		}
		if (aplicados.total() > aplicados.filas().size()) {
			advertencias.add("Hay " + aplicados.total() + " resultados; muestro los primeros "
					+ aplicados.filas().size());
		}
		List<Map<String, Object>> filas = aplicados.filas().stream()
				.map(PracticasAplicadasEnElAsistente::fila).toList();
		return new ResultadoDeHerramienta(filas, advertencias, null, false);
	}

	/**
	 * Primero las coincidencias exactas de codigo o nombre; si no hay, las que lo contienen. Siempre
	 * sin tildes ni mayusculas.
	 */
	private List<CatalogoSnapshot> resolverPractica(Long organizationId, String texto) {
		String buscado = TextoDeBusqueda.plegar(texto.strip());
		List<CatalogoSnapshot> vigentes = catalogo.practicasVigentes(organizationId, Instant.now());
		List<CatalogoSnapshot> exactas = vigentes.stream()
				.filter(c -> buscado.equals(TextoDeBusqueda.plegar(c.codigo()))
						|| buscado.equals(TextoDeBusqueda.plegar(c.name())))
				.toList();
		if (!exactas.isEmpty()) {
			return exactas;
		}
		return vigentes.stream()
				.filter(c -> TextoDeBusqueda.algunoContiene(texto, c.codigo(), c.name()))
				.toList();
	}

	private static Map<String, Object> fila(TratamientoAplicadoEnSede t) {
		Map<String, Object> fila = new LinkedHashMap<>();
		fila.put("fecha", t.fecha().toString());
		fila.put("persona_id", t.personaId());
		fila.put("persona", t.personaNombre());
		fila.put("practica_id", t.practicaId());
		fila.put("practica", t.practicaNombre());
		fila.put("zona", t.zona());
		fila.put("lateralidad", t.lateralidad());
		fila.put("sesion_id", t.sesionId());
		fila.put("profesional_membership_id", t.profesionalMembershipId());
		fila.values().removeIf(Objects::isNull);
		return fila;
	}
}
