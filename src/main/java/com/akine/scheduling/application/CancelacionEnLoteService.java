package com.akine.scheduling.application;

import com.akine.platform.spi.problem.ProblemType;
import com.akine.scheduling.domain.EstadoTurno;
import com.akine.scheduling.domain.OrigenDeCancelacion;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.TransicionDeTurnoNoPermitidaException;
import com.akine.scheduling.domain.exception.TurnoConAtencionException;
import com.akine.scheduling.domain.exception.TurnoNotAccessibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cancelacion de varios turnos de una sede con un mismo motivo: los que un bloqueo dejo afuera
 * (checklist de agenda §6.4).
 *
 * <h2>Resultado por item, nunca todo o nada escondido</h2>
 *
 * <p>Cada turno se cancela con {@link CicloDeTurnoService#cancelar}, <b>a traves del proxy de Spring</b>
 * y por eso en su propia transaccion: exactamente los mismos controles, evento, recepcion, auditoria y
 * aviso que una cancelacion suelta, sin una segunda copia que diverja. Un item que falla no hace rollback
 * de los demas, y vuelve con el {@code problemType} que habria recibido solo. Esta clase no es
 * {@code @Transactional} a proposito: si lo fuera, el primer rechazo marcaria la transaccion de todos
 * para rollback y Spring lanzaria {@code UnexpectedRollbackException} al final.
 *
 * <h2>Reintento seguro</h2>
 *
 * <p>Un lote reenviado —el doble click, o un corte de red despues del commit— encuentra los turnos ya
 * cancelados con una version que ya no es la leida. Esos items vuelven {@code YA_CANCELADO} y no como
 * error: el estado que se pidio es el que hay. Un id repetido dentro del mismo lote se procesa una vez.
 */
@Service
public class CancelacionEnLoteService {

	/** Tope de items por pedido: un lote es una pantalla de afectados, no una migracion. */
	public static final int MAXIMO_DE_ITEMS = 200;

	private static final Logger log = LoggerFactory.getLogger(CancelacionEnLoteService.class);

	private final CicloDeTurnoService ciclo;
	private final TurnoRepositoryPort turnos;

	public CancelacionEnLoteService(CicloDeTurnoService ciclo, TurnoRepositoryPort turnos) {
		this.ciclo = ciclo;
		this.turnos = turnos;
	}

	/** Un turno a cancelar, con la version que el cliente leyo. */
	public record Item(long turnoId, long expectedVersion) {
	}

	/**
	 * Lo que paso con un item.
	 *
	 * @param resultado   {@code CANCELADO}, {@code YA_CANCELADO} o {@code RECHAZADO}
	 * @param problemType el {@code type} que habria recibido la cancelacion suelta; solo en
	 *                    {@code RECHAZADO}
	 * @param turno       el turno despues del intento; ausente si no existe o no es accesible
	 */
	public record ResultadoDeItem(long turnoId, String resultado, String problemType, String detalle, TurnoView turno) {

		public boolean ok() {
			return !"RECHAZADO".equals(resultado);
		}
	}

	public List<ResultadoDeItem> cancelar(
			OperatingActor actor, long consultorioId, List<Item> items, String motivo, OrigenDeCancelacion origen) {

		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La cancelacion de turnos requiere un contexto de trabajo activo");
		}
		if (items == null || items.isEmpty()) {
			throw new IllegalArgumentException("El lote no tiene turnos");
		}
		if (items.size() > MAXIMO_DE_ITEMS) {
			throw new IllegalArgumentException("El lote no puede superar los " + MAXIMO_DE_ITEMS + " turnos");
		}
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("El motivo de cancelacion es obligatorio (DP-04)");
		}

		Set<Long> vistos = new LinkedHashSet<>();
		List<ResultadoDeItem> resultados = new ArrayList<>();
		for (Item item : items) {
			if (!vistos.add(item.turnoId())) {
				continue;
			}
			resultados.add(cancelarUno(actor, consultorioId, item, motivo, origen));
		}
		log.info("Cancelacion en lote: consultorioId={} items={} rechazados={}", consultorioId, resultados.size(),
				resultados.stream().filter(r -> !r.ok()).count());
		return resultados;
	}

	private ResultadoDeItem cancelarUno(
			OperatingActor actor, long consultorioId, Item item, String motivo, OrigenDeCancelacion origen) {
		try {
			TurnoView cancelado = ciclo.cancelar(
					actor, consultorioId, item.turnoId(), motivo, origen, item.expectedVersion());
			return new ResultadoDeItem(item.turnoId(), "CANCELADO", null, null, cancelado);
		} catch (ConsultorioNoAccesibleException sedeAjena) {
			// Sede ajena: es del lote entero, no de un item. 404, igual que la cancelacion suelta.
			throw sedeAjena;
		} catch (AccessDeniedException sinPermiso) {
			// Por item y no del lote: con alcance propio (un profesional sobre sus turnos) unos items
			// pueden pasar y otros no.
			return new ResultadoDeItem(item.turnoId(), "RECHAZADO", ProblemType.FORBIDDEN.uri().toString(),
					"Sin permiso para cancelar este turno", null);
		} catch (RuntimeException rechazo) {
			var actual = turnos.findByIdInScope(actor.contextOrganizationId(), consultorioId, item.turnoId());
			if (actual.isPresent() && actual.get().getEstado() == EstadoTurno.CANCELADO
					&& !(rechazo instanceof TurnoNotAccessibleException)) {
				return new ResultadoDeItem(item.turnoId(), "YA_CANCELADO", null, null, TurnoView.de(actual.get()));
			}
			return new ResultadoDeItem(item.turnoId(), "RECHAZADO", problemTypeDe(rechazo), rechazo.getMessage(),
					actual.map(TurnoView::de).orElse(null));
		}
	}

	/** El mismo {@code type} que el advice del modulo o el global le darian a esa excepcion suelta. */
	static String problemTypeDe(RuntimeException rechazo) {
		ProblemType tipo;
		if (rechazo instanceof TurnoNotAccessibleException) {
			tipo = ProblemType.NOT_FOUND;
		} else if (rechazo instanceof TurnoConAtencionException) {
			tipo = ProblemType.TURNO_CON_ATENCION;
		} else if (rechazo instanceof TransicionDeTurnoNoPermitidaException) {
			tipo = ProblemType.TURNO_TRANSICION_NO_PERMITIDA;
		} else if (rechazo instanceof OptimisticLockingFailureException) {
			tipo = ProblemType.CONCURRENT_MODIFICATION;
		} else if (rechazo instanceof IllegalArgumentException) {
			tipo = ProblemType.VALIDATION_ERROR;
		} else {
			tipo = ProblemType.CONFLICT;
		}
		return tipo.uri().toString();
	}
}
