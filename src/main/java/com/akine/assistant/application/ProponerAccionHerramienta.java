package com.akine.assistant.application;

import com.akine.assistant.application.RespuestaDelAsistente.Resultado;
import com.akine.assistant.domain.AsistenteAjustes;
import com.akine.assistant.domain.PermissionCodes;
import com.akine.assistant.application.RespuestaDelAsistente.OpcionDePersona;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ClavesDeFila;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.DatosDePersona;
import com.akine.assistant.spi.EntradaDeHerramienta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.PedidoDeAccion;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.assistant.spi.TipoDeAccion;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.PermissionQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code proponer_accion}: arma la tarjeta de algo que el usuario cuenta que paso o pide registrar
 * ("Juan pagó 15.000", "vinieron todos menos María"), con {@link AccionesDelAsistente#proponerPedido}.
 * <b>No graba</b>: nada queda hasta que la persona confirma la tarjeta (docs/diseno/AKINE-asistente-agente.md
 * §5.3).
 *
 * <p>Termina el turno y la resuelve el agente, como {@code pedir_aclaracion}: el resultado no es una lista de
 * filas para el modelo sino la respuesta con la tarjeta. Una entrada mal formada vuelve al modelo como
 * error ({@link IllegalArgumentException}) para que la corrija.
 *
 * <p>El nombre de la persona sale del padron por {@code datos_persona} (del modulo {@code person}), nunca de
 * lo que escribio el modelo: un id que no existe o no es de la organizacion no arma tarjeta.
 */
@Component
public class ProponerAccionHerramienta implements HerramientaDelAgente {

	static final String NOMBRE = "proponer_accion";
	static final String DATOS_PERSONA = "datos_persona";
	static final String BUSCAR_PERSONA = "buscar_persona";
	/** Como {@code pedir_aclaracion}: las opciones que se ofrecen cuando el nombre coincide con varias. */
	static final int MAX_OPCIONES = 5;
	static final Set<String> MEDIOS = Set.of("EFECTIVO", "TRANSFERENCIA", "TARJETA_DEBITO", "TARJETA_CREDITO");
	static final Set<String> MODOS = Set.of(PedidoDeAccion.TODOS_MENOS, PedidoDeAccion.SOLO);
	/** {@code LLEGADA} no se ofrece: la decide el permiso de quien habla. */
	static final List<String> TIPOS = Arrays.stream(TipoDeAccion.values()).filter(t -> t != TipoDeAccion.LLEGADA)
			.map(Enum::name).toList();

	private final AsistenteAjustes ajustes;
	private final PermissionEvaluator permisos;
	private final AccionesDelAsistente acciones;
	/** Por proveedor: el catalogo contiene esta misma herramienta. */
	private final ObjectProvider<CatalogoDeHerramientas> catalogo;
	private final Clock clock;

	@Autowired
	ProponerAccionHerramienta(AsistenteAjustes ajustes, PermissionEvaluator permisos, AccionesDelAsistente acciones,
			ObjectProvider<CatalogoDeHerramientas> catalogo) {
		this(ajustes, permisos, acciones, catalogo, Clock.systemUTC());
	}

	ProponerAccionHerramienta(AsistenteAjustes ajustes, PermissionEvaluator permisos, AccionesDelAsistente acciones,
			ObjectProvider<CatalogoDeHerramientas> catalogo, Clock clock) {
		this.clock = clock;
		this.ajustes = ajustes;
		this.permisos = permisos;
		this.acciones = acciones;
		this.catalogo = catalogo;
	}

	@Override
	public String nombre() {
		return NOMBRE;
	}

	@Override
	public String descripcion() {
		return "Prepara una tarjeta para registrar algo que el usuario CUENTA QUE PASÓ o PIDE CARGAR: un pago "
				+ "(\"Juan pagó 15.000 en efectivo\"), la asistencia a una clase de hoy (\"vinieron todos menos "
				+ "María\"), la atención de hoy de un paciente con sus tratamientos, el alta de un paciente nuevo o "
				+ "un cambio de sus datos de contacto. Nunca para una pregunta (\"¿pagó Juan?\", \"¿vino María?\"): "
				+ "eso se responde con las otras herramientas. No graba nada: el usuario revisa la tarjeta y "
				+ "confirma, y recién ahí se registra. Termina el turno. Para pago, atención y cambio de datos "
				+ "necesita la persona: pasá persona_id si ya lo tenés (de buscar_persona) o, si no, persona con "
				+ "el nombre o documento tal como lo dijo el usuario; si coincide con varias, se le pregunta "
				+ "cuál. Poné solo "
				+ "lo que el usuario dijo: lo que falta queda vacío y el usuario lo completa en la tarjeta.";
	}

	@Override
	public String esquemaEntrada() {
		String tipos = TIPOS.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(","));
		return """
				{"type":"object","additionalProperties":false,"properties":{\
				"tipo":{"type":"string","enum":[%s],"description":"ASISTENCIA_CLASE: presentes y ausentes de \
				una clase de hoy; PAGO: un cobro a un paciente; ATENCION: la sesion de hoy de un paciente; \
				PACIENTE_ALTA: una persona nueva; PACIENTE_EDICION: un cambio en sus datos"},\
				"persona_id":{"type":"integer","description":"De buscar_persona; para PAGO, ATENCION y PACIENTE_EDICION"},\
				"persona":{"type":"string","description":"Nombre o documento dicho, si no tenés persona_id; \
				se busca en el padrón"},\
				"clase":{"type":"string","description":"Nombre de la actividad de la clase (ASISTENCIA_CLASE)"},\
				"modo_asistencia":{"type":"string","enum":["TODOS_MENOS","SOLO"],"description":"TODOS_MENOS: \
				vinieron todos menos los nombrados; SOLO: vinieron solo los nombrados"},\
				"nombres":{"type":"array","items":{"type":"string"},"description":"Los nombrados en la asistencia"},\
				"monto":{"type":"number","description":"El importe dicho, en pesos (PAGO)"},\
				"medio":{"type":"string","enum":["EFECTIVO","TRANSFERENCIA","TARJETA_DEBITO","TARJETA_CREDITO"]},\
				"practicas":{"type":"array","items":{"type":"string"},"description":"Tratamientos hechos hoy (ATENCION)"},\
				"datos":{"type":"object","additionalProperties":false,"description":"Datos dichos de la persona \
				(PACIENTE_ALTA, PACIENTE_EDICION)","properties":{\
				"nombre":{"type":"string"},"apellido":{"type":"string"},"documento":{"type":"string"},\
				"telefono":{"type":"string"},"email":{"type":"string"},\
				"fecha_nacimiento":{"type":"string","description":"YYYY-MM-DD"}}}},\
				"required":["tipo"]}""".formatted(tipos);
	}

	@Override
	public boolean disponiblePara(ActorDelAsistente actor) {
		return ajustes.acciones() && permisos.evaluate(new PermissionQuery(actor.accountId(),
				PermissionCodes.ASISTENTE_ACCIONES, actor.organizationId(), actor.consultorioId(), null,
				clock.instant())).granted();
	}

	/** No se ejecuta como lectura: el agente la intercepta y llama a {@link #proponer}. */
	@Override
	public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
			ContextoDeConsulta ctx) {
		throw new IllegalArgumentException("proponer_accion termina el turno; no devuelve filas.");
	}

	/**
	 * La respuesta con la tarjeta (o el texto que explica por que no la hay).
	 *
	 * @param frase la pregunta del usuario: es la nota de la atencion
	 * @throws IllegalArgumentException si la entrada no se entiende o la persona no existe; vuelve al modelo
	 */
	public RespuestaDelAsistente proponer(ActorDelAsistente actor, ContextoDeConsulta ctx, String frase,
			Map<String, Object> entrada) {
		TipoDeAccion tipo = tipo(EntradaDeHerramienta.texto(entrada, "tipo"));
		Long personaId = EntradaDeHerramienta.id(entrada, "persona_id");
		String personaTexto = EntradaDeHerramienta.texto(entrada, "persona");
		if (personaId == null && personaTexto != null && AccionesDelAsistente.usaPersona(tipo)) {
			ResultadoDeHerramienta encontradas = buscarPersona(actor, personaTexto, ctx);
			if (encontradas != null) {
				if (encontradas.sinPermiso()) {
					return new RespuestaDelAsistente(AccionesDelAsistente.SIN_PERMISO,
							Interpretacion.FUERA_DE_ALCANCE, List.of(), List.of(), null, List.of(),
							Resultado.SIN_PERMISO);
				}
				List<OpcionDePersona> candidatas = candidatas(encontradas.filas());
				if (candidatas.isEmpty()) {
					throw new IllegalArgumentException("No encontré a '" + personaTexto + "' en el padrón");
				}
				if (candidatas.size() > 1) {
					// Como pedir_aclaracion: el usuario elige y el cliente reenvia la frase con el personaId.
					return new RespuestaDelAsistente("Hay más de una persona que coincide con '" + personaTexto
							+ "'. ¿Cuál es?", Interpretacion.FUERA_DE_ALCANCE, List.of(),
							candidatas.subList(0, Math.min(MAX_OPCIONES, candidatas.size())), null, List.of(),
							Resultado.ACLARACION);
				}
				personaId = candidatas.getFirst().personaId();
			}
		}
		String personaNombre = null;
		if (personaId != null && AccionesDelAsistente.usaPersona(tipo)) {
			ResultadoDeHerramienta persona = datosDePersona(actor, personaId, ctx);
			if (persona == null) {
				personaId = null;
			}
			else if (persona.sinPermiso()) {
				return new RespuestaDelAsistente(AccionesDelAsistente.SIN_PERMISO, Interpretacion.FUERA_DE_ALCANCE,
						List.of(), List.of(), null, List.of(), Resultado.SIN_PERMISO);
			}
			else if (persona.filas().isEmpty()) {
				throw new IllegalArgumentException("No hay una persona con persona_id " + personaId
						+ " en el padron: buscala con buscar_persona.");
			}
			else {
				Object nombre = persona.filas().getFirst().get("nombre");
				personaNombre = nombre == null ? null : String.valueOf(nombre);
			}
		}
		PedidoDeAccion pedido = new PedidoDeAccion(tipo, personaId, personaNombre,
				EntradaDeHerramienta.texto(entrada, "clase"), enumerado(entrada, "modo_asistencia", MODOS),
				EntradaDeHerramienta.textos(entrada, "nombres"), EntradaDeHerramienta.monto(entrada, "monto"),
				enumerado(entrada, "medio", MEDIOS), EntradaDeHerramienta.textos(entrada, "practicas"),
				datos(entrada.get("datos")), frase, ctx.hoy(), ctx.zona());
		return acciones.proponerPedido(actor, ctx.zona(), pedido);
	}

	private ResultadoDeHerramienta datosDePersona(ActorDelAsistente actor, long personaId, ContextoDeConsulta ctx) {
		CatalogoDeHerramientas herramientas = catalogo.getIfAvailable();
		if (herramientas == null || !herramientas.existe(DATOS_PERSONA)) {
			return null;
		}
		return herramientas.ejecutar(DATOS_PERSONA, actor, Map.of("persona_id", personaId), ctx);
	}

	private ResultadoDeHerramienta buscarPersona(ActorDelAsistente actor, String texto, ContextoDeConsulta ctx) {
		CatalogoDeHerramientas herramientas = catalogo.getIfAvailable();
		if (herramientas == null || !herramientas.existe(BUSCAR_PERSONA)) {
			return null;
		}
		return herramientas.ejecutar(BUSCAR_PERSONA, actor, Map.of("texto", texto), ctx);
	}

	/** Las filas de {@code buscar_persona} como opciones, sin repetir; acepta {@code persona_id} y {@code personaId}. */
	private static List<OpcionDePersona> candidatas(List<Map<String, Object>> filas) {
		List<OpcionDePersona> opciones = new ArrayList<>();
		for (Map<String, Object> fila : filas) {
			Object id = fila.get("persona_id") != null ? fila.get("persona_id") : fila.get(ClavesDeFila.PERSONA_ID);
			if (!(id instanceof Number n) || opciones.stream().anyMatch(o -> o.personaId() == n.longValue())) {
				continue;
			}
			Object nombre = fila.get(ClavesDeFila.NOMBRE);
			Object documento = fila.get(ClavesDeFila.DOCUMENTO);
			opciones.add(new OpcionDePersona(n.longValue(),
					(nombre == null ? "Persona " + n.longValue() : String.valueOf(nombre))
							+ (documento == null ? "" : " · " + documento)));
		}
		return opciones;
	}

	private static TipoDeAccion tipo(String valor) {
		if (valor == null || !TIPOS.contains(valor.toUpperCase(Locale.ROOT))) {
			throw new IllegalArgumentException("'tipo' tiene que ser uno de " + TIPOS);
		}
		return TipoDeAccion.valueOf(valor.toUpperCase(Locale.ROOT));
	}

	private static String enumerado(Map<String, Object> entrada, String clave, Set<String> validos) {
		String valor = EntradaDeHerramienta.texto(entrada, clave);
		if (valor == null) {
			return null;
		}
		String normal = valor.toUpperCase(Locale.ROOT);
		if (!validos.contains(normal)) {
			throw new IllegalArgumentException("'" + clave + "' tiene que ser uno de " + validos);
		}
		return normal;
	}

	private static DatosDePersona datos(Object valor) {
		if (valor == null) {
			return DatosDePersona.VACIO;
		}
		if (!(valor instanceof Map<?, ?> crudo)) {
			throw new IllegalArgumentException("'datos' tiene que ser un objeto");
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> d = (Map<String, Object>) crudo;
		String nacimiento = EntradaDeHerramienta.texto(d, "fecha_nacimiento");
		LocalDate fecha;
		try {
			fecha = nacimiento == null ? null : LocalDate.parse(nacimiento);
		}
		catch (DateTimeParseException e) {
			throw new IllegalArgumentException("'fecha_nacimiento' tiene que ser YYYY-MM-DD");
		}
		return new DatosDePersona(EntradaDeHerramienta.texto(d, "nombre"), EntradaDeHerramienta.texto(d, "apellido"),
				EntradaDeHerramienta.texto(d, "documento"), EntradaDeHerramienta.texto(d, "telefono"),
				EntradaDeHerramienta.texto(d, "email"), fecha);
	}
}
