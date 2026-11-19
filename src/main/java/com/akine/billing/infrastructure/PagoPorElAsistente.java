package com.akine.billing.infrastructure;

import com.akine.assistant.spi.AccionDelAsistente;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.PedidoDeAccion;
import com.akine.assistant.spi.PropuestaDeAccion;
import com.akine.assistant.spi.ResultadoDeAccion;
import com.akine.assistant.spi.Tarjeta;
import com.akine.assistant.spi.Tarjeta.ImputacionEnTarjeta;
import com.akine.assistant.spi.Tarjeta.TarjetaDePago;
import com.akine.assistant.spi.TipoDeAccion;
import com.akine.billing.application.CobroCommand;
import com.akine.billing.application.CobroService;
import com.akine.billing.application.CobroView;
import com.akine.billing.application.IdempotencyKeyConflictException;
import com.akine.billing.application.ObligacionService;
import com.akine.billing.application.ObligacionView;
import com.akine.billing.application.OperatingActor;
import com.akine.billing.domain.EstadoObligacion;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.Responsable;
import com.akine.billing.domain.exception.CajaCerradaException;
import com.akine.billing.domain.exception.CajaMonedaDistintaException;
import com.akine.billing.domain.exception.CajaNoAbiertaException;
import com.akine.billing.domain.exception.CobroInvalidoException;
import com.akine.billing.domain.exception.ConsultorioNoAccesibleException;
import com.akine.billing.domain.exception.ImputacionesNoSumanException;
import com.akine.billing.domain.exception.MediosNoSumanException;
import com.akine.billing.domain.exception.ObligacionNoCobrableException;
import com.akine.billing.domain.exception.PersonaNoAccesibleException;
import com.akine.billing.domain.exception.SaldoInsuficienteException;
import com.akine.billing.domain.port.JornadaCajaRepositoryPort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * El pago dicho al asistente ("Juan Perez pago 15.000 en efectivo"): AKINE-asistente-acciones §6.2.
 *
 * <p>{@link #preparar} solo lee: las deudas del paciente, la imputacion propuesta y si la caja de la
 * sede esta abierta. {@link #ejecutar} no confia en la propuesta: arma el pedido con lo que quedo en
 * la tarjeta y lo manda por {@link CobroService#registrar}, que revalida todo.
 *
 * <h2>Sin transaccion, a proposito</h2>
 *
 * <p>{@code CobroService.registrar} asegura la fila del numerador de comprobantes antes de abrir la
 * suya y se niega a correr dentro de otra. Esta clase no es {@code @Transactional} ni abre ninguna.
 *
 * <h2>Un doble clic no cobra dos veces</h2>
 *
 * <p>La clave de idempotencia sale del id de la accion: si el proceso cae despues de cobrar y antes
 * de marcar la propuesta como confirmada, el reintento devuelve el mismo cobro.
 */
@Component
class PagoPorElAsistente implements AccionDelAsistente {

	static final String MONEDA_POR_DEFECTO = "ARS";
	static final String CLAVE = "asistente-accion-";

	private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
	private static final String CAJA_CERRADA =
			"La caja de la sede está cerrada: abrila para cobrar en efectivo.";

	private final ObligacionService obligaciones;
	private final CobroService cobros;
	private final JornadaCajaRepositoryPort jornadas;

	PagoPorElAsistente(ObligacionService obligaciones, CobroService cobros, JornadaCajaRepositoryPort jornadas) {
		this.obligaciones = obligaciones;
		this.cobros = cobros;
		this.jornadas = jornadas;
	}

	/** Una deuda del paciente tal como entra al reparto. */
	record Deuda(long obligacionId, String concepto, LocalDate fecha, BigDecimal saldo) {
	}

	/** El reparto propuesto: una fila por deuda y lo que sobra, a favor. */
	record Imputado(List<ImputacionEnTarjeta> imputaciones, BigDecimal anticipo) {
	}

	@Override
	public TipoDeAccion tipo() {
		return TipoDeAccion.PAGO;
	}

	@Override
	public PropuestaDeAccion preparar(ActorDelAsistente actor, PedidoDeAccion pedido) {
		if (pedido.personaId() == null) {
			return PropuestaDeAccion.sinTarjeta("Decime de quién es el pago.");
		}
		long personaId = pedido.personaId();
		long sedeId = sede(actor);
		OperatingActor operador = operador(actor);

		// Exige cobro:register: sin el, AccessDeniedException sigue de largo hasta el asistente.
		// findDeLaPersona ya excluye la parte del financiador; el filtro se repite porque es la regla.
		List<ObligacionView> deLaOrganizacion = obligaciones.deLaPersona(operador, sedeId, personaId).stream()
				.filter(o -> Responsable.PACIENTE.name().equals(o.responsable()))
				.filter(o -> EstadoObligacion.PENDIENTE.name().equals(o.estado())
						|| EstadoObligacion.PARCIAL.name().equals(o.estado()))
				.filter(o -> o.saldo() != null && o.saldo().signum() > 0)
				.sorted(Comparator.comparing(ObligacionView::devengadaEn).thenComparing(ObligacionView::id))
				.toList();
		// La cuenta corriente es de la organizacion, pero el cobro es de una sede: CobroService rechaza
		// imputar a una deuda de otra, y con una sola fila ajena en la tarjeta se caia el cobro entero.
		List<ObligacionView> abiertas = deLaSede(deLaOrganizacion, sedeId);

		List<String> avisos = new ArrayList<>();
		List<String> bloqueos = new ArrayList<>();
		if (abiertas.size() < deLaOrganizacion.size()) {
			avisos.add("Tiene deudas en otra sede que no entran en este cobro.");
		}

		String moneda = abiertas.isEmpty() ? MONEDA_POR_DEFECTO : abiertas.get(0).moneda();
		List<String> otrasMonedas = abiertas.stream()
				.map(ObligacionView::moneda)
				.filter(m -> !moneda.equals(m))
				.distinct()
				.toList();
		if (!otrasMonedas.isEmpty()) {
			avisos.add("Tiene deudas en otra moneda (" + String.join(", ", otrasMonedas)
					+ ") que no entran en este cobro.");
		}

		ZoneId zona = pedido.zona() == null ? ZoneOffset.UTC : pedido.zona();
		List<Deuda> deudas = abiertas.stream()
				.filter(o -> moneda.equals(o.moneda()))
				.map(o -> new Deuda(o.id(), concepto(o), LocalDate.ofInstant(o.devengadaEn(), zona), o.saldo()))
				.toList();

		BigDecimal monto = pedido.monto() != null && pedido.monto().signum() > 0 ? pedido.monto() : null;
		MedioDePago medio = medio(pedido.medio());
		Imputado imputado = imputar(deudas, monto);
		// Lectura sin permiso propio: el cobro en efectivo tampoco exige caja:operate, y la sede ya
		// la valido contra el tenant la consulta de deudas de arriba.
		boolean cajaAbierta = jornadas.findAbierta(operador.contextOrganizationId(), sedeId).isPresent();

		if (deudas.isEmpty() && monto != null) {
			avisos.add("No tiene deudas pendientes: todo queda como saldo a favor.");
		}
		if (monto == null) {
			bloqueos.add("Falta el monto.");
		}
		if (medio == null) {
			bloqueos.add("Falta elegir el medio de pago.");
		}
		if (medio == MedioDePago.EFECTIVO && !cajaAbierta) {
			bloqueos.add(CAJA_CERRADA);
		}

		TarjetaDePago tarjeta = new TarjetaDePago(personaId, pedido.personaNombre(), monto,
				medio == null ? null : medio.name(), moneda, imputado.imputaciones(), imputado.anticipo(),
				cajaAbierta);
		return new PropuestaDeAccion("Pago de " + pedido.personaNombre(), tarjeta,
				Map.of(PropuestaDeAccion.PERSONA, personaId), avisos, bloqueos, null);
	}

	/** Solo las deudas que nacieron en la sede donde se cobra. */
	static List<ObligacionView> deLaSede(List<ObligacionView> deudas, long sedeId) {
		return deudas.stream().filter(o -> o.consultorioId() == sedeId).toList();
	}

	/**
	 * Reparte el monto de la deuda mas vieja a la mas nueva hasta agotarlo; lo que sobra es anticipo.
	 * Sin monto, todas las filas van en cero para que la tarjeta muestre igual cada deuda y su saldo.
	 *
	 * @param deudas ya ordenadas de la mas vieja a la mas nueva y en una sola moneda
	 */
	static Imputado imputar(List<Deuda> deudas, BigDecimal monto) {
		BigDecimal resto = monto == null ? BigDecimal.ZERO : monto.max(BigDecimal.ZERO);
		List<ImputacionEnTarjeta> imputaciones = new ArrayList<>();
		for (Deuda deuda : deudas) {
			BigDecimal importe = resto.min(deuda.saldo()).max(BigDecimal.ZERO);
			resto = resto.subtract(importe);
			imputaciones.add(new ImputacionEnTarjeta(deuda.obligacionId(), deuda.concepto(), deuda.fecha(),
					deuda.saldo(), importe));
		}
		return new Imputado(List.copyOf(imputaciones), resto);
	}

	@Override
	public ResultadoDeAccion ejecutar(ActorDelAsistente actor, long accionId, Tarjeta confirmada) {
		if (!(confirmada instanceof TarjetaDePago pago)) {
			return ResultadoDeAccion.rechazada("Falta el monto o el medio de pago.");
		}
		BigDecimal total = pago.total();
		MedioDePago medio = medio(pago.medio());
		if (total == null || total.signum() <= 0 || medio == null) {
			return ResultadoDeAccion.rechazada("Falta el monto o el medio de pago.");
		}

		List<CobroCommand.ImputacionPedida> imputaciones = pago.imputaciones().stream()
				.filter(i -> i.importe() != null && i.importe().signum() > 0)
				.map(i -> new CobroCommand.ImputacionPedida(i.obligacionId(), i.importe()))
				.toList();
		BigDecimal imputado = imputaciones.stream()
				.map(CobroCommand.ImputacionPedida::importe)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal anticipo = pago.anticipo() == null ? total.subtract(imputado) : pago.anticipo();
		if (anticipo.signum() < 0) {
			// El dominio lo rechaza con IllegalArgumentException, que no es un rechazo de negocio.
			return ResultadoDeAccion.rechazada("Lo imputado a las deudas supera el monto del cobro.");
		}
		String moneda = pago.moneda() == null || pago.moneda().isBlank() ? null : pago.moneda();
		if (imputaciones.isEmpty() && moneda == null) {
			return ResultadoDeAccion.rechazada("Falta la moneda del cobro.");
		}

		CobroCommand comando = new CobroCommand(pago.personaId(), total,
				List.of(new CobroCommand.MedioPedido(medio, total, null)),
				imputaciones, CLAVE + accionId, anticipo, moneda);

		CobroView cobro;
		try {
			// Sin transaccion abierta: lo exige el numerador de comprobantes.
			cobro = cobros.registrar(operador(actor), sede(actor), comando);
		} catch (AccessDeniedException sinPermiso) {
			throw sinPermiso;
		} catch (RuntimeException rechazo) {
			String motivo = motivoDe(rechazo);
			if (motivo == null) {
				throw rechazo;
			}
			return ResultadoDeAccion.rechazada(motivo);
		}

		return ResultadoDeAccion.confirmada(
				"Registré el cobro de $ " + enPesos(total) + " a " + pago.nombre() + ".",
				"/pacientes/" + pago.personaId(),
				Map.of("cobroId", cobro.id(), "personaId", pago.personaId()));
	}

	/**
	 * El motivo legible de un rechazo de negocio de billing, o {@code null} si la excepcion no lo es
	 * (y entonces sigue su camino). Texto propio y no {@code getMessage()}: los mensajes del dominio
	 * nombran ids de obligacion y de sede, que a quien dicta no le dicen nada.
	 */
	static String motivoDe(RuntimeException rechazo) {
		if (rechazo instanceof CajaNoAbiertaException || rechazo instanceof CajaCerradaException) {
			return CAJA_CERRADA;
		}
		if (rechazo instanceof SaldoInsuficienteException) {
			return "Una de las deudas ya no tiene ese saldo: puede que se haya cobrado recién. Volvé a pedirlo.";
		}
		if (rechazo instanceof ObligacionNoCobrableException) {
			return "Una de las deudas ya no admite este cobro: puede estar pagada, anulada, en otra moneda "
					+ "o ser de otra persona. Volvé a pedirlo.";
		}
		if (rechazo instanceof ImputacionesNoSumanException || rechazo instanceof MediosNoSumanException) {
			return "Lo imputado a las deudas más el saldo a favor no suma el monto del cobro.";
		}
		if (rechazo instanceof CobroInvalidoException) {
			return "El cobro no es válido: revisá el monto, las deudas y la moneda.";
		}
		if (rechazo instanceof CajaMonedaDistintaException) {
			return "La caja de la sede está en otra moneda que este cobro.";
		}
		if (rechazo instanceof IdempotencyKeyConflictException) {
			return "Este pago ya se registró con otros datos. Volvé a pedirlo.";
		}
		if (rechazo instanceof PersonaNoAccesibleException) {
			return "No encuentro a esa persona como paciente del centro.";
		}
		if (rechazo instanceof ConsultorioNoAccesibleException) {
			return "La sede no está disponible para registrar el cobro.";
		}
		return null;
	}

	/** El importe con separador de miles es-AR y hasta dos decimales: {@code 15.000}, {@code 1.250,5}. */
	static String enPesos(BigDecimal importe) {
		NumberFormat formato = NumberFormat.getNumberInstance(ES_AR);
		formato.setMinimumFractionDigits(0);
		formato.setMaximumFractionDigits(2);
		return formato.format(importe);
	}

	private static String concepto(ObligacionView obligacion) {
		return obligacion.snapshotNombre() != null && !obligacion.snapshotNombre().isBlank()
				? obligacion.snapshotNombre()
				: obligacion.concepto();
	}

	/** El medio del modulo, o {@code null} si no se dijo o no es uno de los que existen. */
	private static MedioDePago medio(String dicho) {
		if (dicho == null || dicho.isBlank()) {
			return null;
		}
		try {
			return MedioDePago.valueOf(dicho.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException desconocido) {
			return null;
		}
	}

	private static OperatingActor operador(ActorDelAsistente actor) {
		return new OperatingActor(actor.accountId(), actor.platformAdmin(), actor.organizationId(),
				actor.consultorioId());
	}

	/** Sin sede o sin organizacion en el contexto no hay donde cobrar: 403, como en los servicios. */
	private static long sede(ActorDelAsistente actor) {
		if (actor.consultorioId() == null || actor.organizationId() == null) {
			throw new AccessDeniedException("El cobro requiere un contexto de trabajo activo");
		}
		return actor.consultorioId();
	}
}
