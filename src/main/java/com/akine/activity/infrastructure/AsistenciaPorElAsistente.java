package com.akine.activity.infrastructure;

import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.ClaseView;
import com.akine.activity.application.ItemDeLote;
import com.akine.activity.application.OperatingActor;
import com.akine.activity.application.ParticipanteOperativoView;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.application.ResultadoDeLote;
import com.akine.activity.domain.EstadoClase;
import com.akine.activity.domain.EstadoInscripcion;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.activity.domain.exception.ClaseNotAccessibleException;
import com.akine.activity.domain.exception.LotesDeAsistenciaSaturadosException;
import com.akine.activity.domain.exception.TransicionDeClaseNoPermitidaException;
import com.akine.assistant.spi.AccionDelAsistente;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.PedidoDeAccion;
import com.akine.assistant.spi.PropuestaDeAccion;
import com.akine.assistant.spi.ResultadoDeAccion;
import com.akine.assistant.spi.Tarjeta;
import com.akine.assistant.spi.Tarjeta.ClaseEnTarjeta;
import com.akine.assistant.spi.Tarjeta.ItemDeAsistencia;
import com.akine.assistant.spi.Tarjeta.TarjetaDeAsistencia;
import com.akine.assistant.spi.TipoDeAccion;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.PermissionQuery;
import com.akine.organization.spi.ZonaDeSede;
import com.akine.platform.spi.problem.ProblemType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Vinieron todos menos Maria": la asistencia de una clase de hoy, propuesta en una tarjeta y
 * grabada recien al confirmar (AKINE-asistente-acciones §6.1).
 *
 * <p>{@link #preparar} solo lee: las clases de hoy de la sede con {@link ClaseService#listar} y sus
 * inscriptos con lugar con {@link AsistenciaService#detalleOperativo}. {@link #ejecutar} no confia
 * en la tarjeta: relee la clase y lo ya registrado, inicia la clase si hace falta y manda el lote.
 *
 * <p><b>No abre transaccion.</b> {@link AsistenciaService#registrarLote} corre cada item en la suya
 * y una transaccion de afuera convertiria el primer fallo en el rollback de todos.
 */
@Component
class AsistenciaPorElAsistente implements AccionDelAsistente {

	static final int MAX_CLASES = 5;
	static final String ENLACE = "/asistencia";
	static final String MOTIVO_DE_CORRECCION = "Corrección desde el asistente";
	static final String PRESENTE = ResultadoAsistencia.PRESENTE.name();
	static final String AUSENTE = ResultadoAsistencia.AUSENTE.name();
	private static final String PREFIJO_DE_AMBIGUO = "Hay más de una persona que coincide con ";

	private final ClaseService clases;
	private final AsistenciaService asistencias;
	private final PermissionEvaluator permisos;
	private final ConsultorioDirectory consultorios;

	AsistenciaPorElAsistente(ClaseService clases, AsistenciaService asistencias, PermissionEvaluator permisos,
			ConsultorioDirectory consultorios) {
		this.clases = clases;
		this.asistencias = asistencias;
		this.permisos = permisos;
		this.consultorios = consultorios;
	}

	/** Un inscripto con lugar, sin las vistas del modulo: lo que {@link #marcar} necesita. */
	record Participante(long inscripcionId, String nombre, String apellido, String yaRegistrado) {
	}

	@Override
	public TipoDeAccion tipo() {
		return TipoDeAccion.ASISTENCIA_CLASE;
	}

	// =================================================================================
	// Preparar
	// =================================================================================

	@Override
	public PropuestaDeAccion preparar(ActorDelAsistente actor, PedidoDeAccion pedido) {
		OperatingActor operador = operador(actor);
		Instant desde = pedido.hoy().atStartOfDay(pedido.zona()).toInstant();
		Instant hasta = pedido.hoy().plusDays(1).atStartOfDay(pedido.zona()).toInstant();
		boolean conNombre = pedido.clase() != null && !pedido.clase().isBlank();
		String buscado = conNombre ? ClasesParaElAgente.normal(pedido.clase()) : null;

		List<ClaseView> deHoy = clases.listar(operador, actor.consultorioId(), desde, hasta).stream()
				.filter(c -> !EstadoClase.CANCELADA.name().equals(c.estado()))
				.filter(c -> buscado == null || (ClasesParaElAgente.nombre(c) != null
						&& ClasesParaElAgente.normal(ClasesParaElAgente.nombre(c)).contains(buscado)))
				.sorted(Comparator.comparing((ClaseView c) -> !EstadoClase.EN_CURSO.name().equals(c.estado()))
						.thenComparing(ClaseView::inicio))
				.limit(MAX_CLASES)
				.toList();
		if (deHoy.isEmpty()) {
			return PropuestaDeAccion.sinTarjeta("No encontré una clase de hoy"
					+ (conNombre ? " de " + pedido.clase().strip() : "") + " en esta sede.");
		}

		List<ClaseEnTarjeta> enTarjeta = new ArrayList<>(deHoy.size());
		List<List<String>> avisosPorClase = new ArrayList<>(deHoy.size());
		for (ClaseView clase : deHoy) {
			List<String> avisosDeLaClase = new ArrayList<>();
			List<ItemDeAsistencia> items = marcar(participantes(operador, actor.consultorioId(), clase.id()),
					pedido.modoAsistencia(), pedido.nombres(), avisosDeLaClase);
			enTarjeta.add(new ClaseEnTarjeta(clase.id(), nombreDe(clase), clase.inicio(), clase.estado(), items));
			avisosPorClase.add(avisosDeLaClase);
		}

		List<String> avisos = new ArrayList<>(reunir(avisosPorClase));
		List<String> bloqueos = new ArrayList<>();
		List<ClaseView> sinIniciar = deHoy.stream()
				.filter(c -> EstadoClase.PROGRAMADA.name().equals(c.estado()))
				.toList();
		if (!sinIniciar.isEmpty() && !puedeIniciar(actor)) {
			if (sinIniciar.size() == deHoy.size()) {
				bloqueos.add("La clase todavía no se inició y no tenés permiso para iniciarla.");
			} else {
				avisos.add("Hay clases que todavía no se iniciaron y no tenés permiso para iniciarlas: "
						+ String.join(", ", sinIniciar.stream().map(AsistenciaPorElAsistente::nombreDe).toList())
						+ ".");
			}
		}

		boolean unica = deHoy.size() == 1;
		return new PropuestaDeAccion(
				unica ? "Asistencia de " + nombreDe(deHoy.get(0)) : "Asistencia de clase",
				new TarjetaDeAsistencia(unica ? deHoy.get(0).id() : null, enTarjeta),
				Map.of(), avisos, bloqueos, null);
	}

	/**
	 * La regla de marcado, pura. {@code TODOS_MENOS}: presentes todos, ausentes los nombrados.
	 * {@code SOLO}: presentes los nombrados, ausentes el resto. Sin modo: nadie marcado.
	 *
	 * <p>Un nombre dicho casa con un participante si, plegado, es igual a su nombre, a su apellido o
	 * a "nombre apellido". Si casa con dos o mas, <b>esas filas quedan sin marcar</b> aunque el modo
	 * las marcaria: el asistente no elige entre dos Marias. Los avisos se agregan a {@code avisos}.
	 */
	static List<ItemDeAsistencia> marcar(
			List<Participante> participantes, String modo, List<String> nombres, List<String> avisos) {

		boolean todosMenos = PedidoDeAccion.TODOS_MENOS.equals(modo);
		boolean solo = PedidoDeAccion.SOLO.equals(modo);
		Set<Long> nombrados = new HashSet<>();
		Set<Long> ambiguos = new HashSet<>();
		if ((todosMenos || solo) && nombres != null) {
			Set<String> vistos = new HashSet<>();
			for (String dicho : nombres) {
				String buscado = dicho == null ? "" : ClasesParaElAgente.normal(dicho);
				if (buscado.isEmpty() || !vistos.add(buscado)) {
					continue;
				}
				List<Long> coinciden = participantes.stream()
						.filter(p -> casa(p, buscado))
						.map(Participante::inscripcionId)
						.toList();
				if (coinciden.isEmpty()) {
					avisos.add("«" + dicho.strip() + "» no está entre los inscriptos.");
				} else if (coinciden.size() > 1) {
					avisos.add(PREFIJO_DE_AMBIGUO + "«" + dicho.strip() + "»: marcala a mano.");
					ambiguos.addAll(coinciden);
				} else {
					nombrados.add(coinciden.get(0));
				}
			}
		}

		List<ItemDeAsistencia> items = new ArrayList<>(participantes.size());
		for (Participante participante : participantes) {
			String resultado = null;
			if ((todosMenos || solo) && !ambiguos.contains(participante.inscripcionId())) {
				boolean nombrado = nombrados.contains(participante.inscripcionId());
				resultado = nombrado == solo ? PRESENTE : AUSENTE;
			}
			items.add(new ItemDeAsistencia(participante.inscripcionId(), etiqueta(participante), resultado,
					participante.yaRegistrado()));
		}
		return items;
	}

	private static boolean casa(Participante participante, String buscado) {
		String nombre = plegado(participante.nombre());
		String apellido = plegado(participante.apellido());
		return buscado.equals(nombre) || buscado.equals(apellido)
				|| buscado.equals((nombre + " " + apellido).strip());
	}

	private static String plegado(String texto) {
		return texto == null ? "" : ClasesParaElAgente.normal(texto);
	}

	private static String etiqueta(Participante participante) {
		String apellido = participante.apellido() == null ? "" : participante.apellido().strip();
		String nombre = participante.nombre() == null ? "" : participante.nombre().strip();
		if (apellido.isEmpty() || nombre.isEmpty()) {
			return apellido + nombre;
		}
		return apellido + ", " + nombre;
	}

	/**
	 * Los avisos de varias clases en una sola lista, sin repetir. Con mas de una clase, "no esta
	 * entre los inscriptos" solo se conserva si no esta en <b>ninguna</b>: Maria puede ser de la otra.
	 */
	private static Set<String> reunir(List<List<String>> avisosPorClase) {
		Set<String> reunidos = new LinkedHashSet<>();
		for (List<String> deLaClase : avisosPorClase) {
			for (String aviso : deLaClase) {
				if (aviso.startsWith(PREFIJO_DE_AMBIGUO)
						|| avisosPorClase.stream().allMatch(otra -> otra.contains(aviso))) {
					reunidos.add(aviso);
				}
			}
		}
		return reunidos;
	}

	/** Los inscriptos con lugar: ni cancelados ni en lista de espera. */
	private List<Participante> participantes(OperatingActor operador, long consultorioId, long claseId) {
		return asistencias
				.detalleOperativo(operador, consultorioId, claseId, 0, ClasesParaElAgente.MAX_PARTICIPANTES)
				.contenido().stream()
				.filter(AsistenciaPorElAsistente::tieneLugar)
				.map(p -> new Participante(p.inscripcion().id(), p.nombre(), p.apellido(),
						p.asistencia() == null ? null : p.asistencia().resultado()))
				.toList();
	}

	private static boolean tieneLugar(ParticipanteOperativoView participante) {
		return EstadoInscripcion.valueOf(participante.inscripcion().estado()).consumeCupo();
	}

	/**
	 * La misma pregunta que {@link AsistenciaService#iniciar} le hace al guard, en modo consulta: no
	 * tener {@code clase:manage} aca es un bloqueo en la tarjeta, no un rechazo.
	 */
	private boolean puedeIniciar(ActorDelAsistente actor) {
		return permisos.evaluate(new PermissionQuery(actor.accountId(), PermissionCodes.CLASE_MANAGE,
				actor.organizationId(), actor.consultorioId(), null, Instant.now())).granted();
	}

	// =================================================================================
	// Ejecutar
	// =================================================================================

	@Override
	public ResultadoDeAccion ejecutar(ActorDelAsistente actor, long accionId, Tarjeta confirmada) {
		if (!(confirmada instanceof TarjetaDeAsistencia tarjeta) || tarjeta.claseId() == null) {
			return ResultadoDeAccion.rechazada("Elegí la clase.");
		}
		long claseId = tarjeta.claseId();
		ClaseEnTarjeta elegida = tarjeta.clases().stream()
				.filter(c -> c.claseId() == claseId)
				.findFirst()
				.orElse(null);
		if (elegida == null) {
			return ResultadoDeAccion.rechazada("Elegí la clase.");
		}

		// Una fila por inscripcion: si la tarjeta la trae dos veces, vale la ultima.
		Map<Long, ResultadoAsistencia> marcados = new LinkedHashMap<>();
		Map<Long, String> nombres = new HashMap<>();
		for (ItemDeAsistencia item : elegida.items()) {
			if (item.resultado() == null || item.resultado().isBlank()) {
				continue;
			}
			try {
				marcados.put(item.inscripcionId(), ResultadoAsistencia.valueOf(item.resultado().strip()));
			} catch (IllegalArgumentException noEsUnResultado) {
				return ResultadoDeAccion.rechazada("Hay una fila con un resultado que no reconozco: "
						+ "marcala como presente o ausente.");
			}
			nombres.put(item.inscripcionId(), item.nombre());
		}
		if (marcados.isEmpty()) {
			return ResultadoDeAccion.rechazada("No hay nadie marcado.");
		}

		OperatingActor operador = operador(actor);
		long consultorioId = actor.consultorioId();
		try {
			// La clase se relee por el servicio, que valida tenant y sede: la tarjeta no prueba nada.
			ClaseView clase = clases.ver(operador, consultorioId, claseId);
			if (EstadoClase.CANCELADA.name().equals(clase.estado())) {
				return ResultadoDeAccion.rechazada("La clase está cancelada: no se le puede tomar asistencia.");
			}
			// La misma ventana que `preparar`: el id de la tarjeta puede ser el de una clase de otro dia.
			ZoneId zona = ZonaDeSede.de(consultorios, actor.organizationId(), consultorioId);
			if (!LocalDate.ofInstant(clase.inicio(), zona).equals(LocalDate.now(zona))) {
				return ResultadoDeAccion.rechazada("Esa clase no es de hoy.");
			}
			if (EstadoClase.PROGRAMADA.name().equals(clase.estado())) {
				asistencias.iniciar(operador, consultorioId, claseId);
			}

			// Lo ya registrado tambien se relee: decide que se saltea y que es una correccion.
			Map<Long, ResultadoAsistencia> registrados = new HashMap<>();
			for (ParticipanteOperativoView p : asistencias.detalleOperativo(operador, consultorioId, claseId, 0,
					ClasesParaElAgente.MAX_PARTICIPANTES).contenido()) {
				nombres.put(p.inscripcion().id(),
						etiqueta(new Participante(p.inscripcion().id(), p.nombre(), p.apellido(), null)));
				if (p.asistencia() != null) {
					registrados.put(p.inscripcion().id(), ResultadoAsistencia.valueOf(p.asistencia().resultado()));
				}
			}

			int presentes = 0;
			int ausentes = 0;
			List<RegistrarAsistenciaCommand> comandos = new ArrayList<>();
			for (Map.Entry<Long, ResultadoAsistencia> marcado : marcados.entrySet()) {
				ResultadoAsistencia previo = registrados.get(marcado.getKey());
				ResultadoAsistencia pedido = marcado.getValue();
				// Mismo hecho ya afirmado: no se manda. "Presente" sobre un "presente tarde" tampoco:
				// la tarjeta no distingue la tardanza y corregirlo la perderia.
				if (previo != null && previo.estuvo() == pedido.estuvo()) {
					if (pedido.estuvo()) {
						presentes++;
					} else {
						ausentes++;
					}
					continue;
				}
				comandos.add(new RegistrarAsistenciaCommand(marcado.getKey(), pedido, OrigenAsistencia.LOTE, null,
						previo == null ? null : MOTIVO_DE_CORRECCION));
			}

			List<String> detalles = new ArrayList<>();
			if (!comandos.isEmpty()) {
				ResultadoDeLote lote = asistencias.registrarLote(operador, consultorioId, claseId, comandos);
				for (ItemDeLote item : lote.items()) {
					if (!item.exitoso()) {
						String nombre = nombres.get(item.inscripcionId());
						detalles.add((nombre == null || nombre.isBlank() ? "Una fila" : nombre) + ": "
								+ motivoDelFallo(item.problemType()));
					} else if (marcados.get(item.inscripcionId()).estuvo()) {
						presentes++;
					} else {
						ausentes++;
					}
				}
			}
			if (presentes + ausentes == 0) {
				return new ResultadoDeAccion(ResultadoDeAccion.Estado.RECHAZADA,
						"No pude registrar la asistencia de nadie.", null, detalles, Map.of());
			}
			return new ResultadoDeAccion(ResultadoDeAccion.Estado.CONFIRMADA,
					"Registré " + cuantos(presentes, "presente") + " y " + cuantos(ausentes, "ausente") + " en "
							+ nombreDe(clase) + ".",
					ENLACE, detalles, Map.of("claseId", claseId));
		} catch (ClaseNotAccessibleException noExiste) {
			return ResultadoDeAccion.rechazada("Esa clase no existe en esta sede.");
		} catch (TransicionDeClaseNoPermitidaException noAdmite) {
			return ResultadoDeAccion.rechazada("La clase ya no admite esta operación: puede que la hayan cancelado.");
		} catch (LotesDeAsistenciaSaturadosException saturado) {
			return ResultadoDeAccion.rechazada(
					"Se están cargando muchas asistencias a la vez en esta sede. Probá de nuevo en un momento.");
		}
	}

	/** El {@code problemType} de un item fallido del lote, dicho para una persona. */
	private static String motivoDelFallo(String problemType) {
		if (ProblemType.NOT_FOUND.value().equals(problemType)) {
			return "ya no está inscripto en esta clase";
		}
		if (ProblemType.INSCRIPCION_TRANSICION_NO_PERMITIDA.value().equals(problemType)) {
			return "su inscripción no tiene lugar en la clase";
		}
		if (ProblemType.CLASE_TRANSICION_NO_PERMITIDA.value().equals(problemType)) {
			return "la clase no admite asistencia";
		}
		return "no se pudo registrar";
	}

	private static String cuantos(int cantidad, String singular) {
		return cantidad + " " + singular + (cantidad == 1 ? "" : "s");
	}

	private static String nombreDe(ClaseView clase) {
		String nombre = ClasesParaElAgente.nombre(clase);
		return nombre == null || nombre.isBlank() ? "la clase" : nombre.strip();
	}

	private static OperatingActor operador(ActorDelAsistente actor) {
		return new OperatingActor(actor.accountId(), actor.platformAdmin(), actor.organizationId(),
				actor.consultorioId());
	}
}
