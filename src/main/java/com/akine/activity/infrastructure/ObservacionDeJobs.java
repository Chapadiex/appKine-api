package com.akine.activity.infrastructure;

import com.akine.activity.application.PasadaDeJob;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Metricas, traza y log de cada pasada de los jobs de F9 (AKINE-09.03; docs/observabilidad.md §7).
 *
 * <p>Los jobs corren en el hilo del scheduler, sin request: no tienen {@code http_server_requests},
 * ni {@code requestId}, ni traza propia. Sin esto, un vencimiento que falla todas las horas o una
 * renovacion que dejo de correr solo se ven leyendo el log. Cada pasada publica:
 *
 * <ul>
 *   <li><b>{@code akine.job.items}</b> (contador, {@code akine_job_items_total}): candidatos por
 *       {@code job} y {@code resultado} = {@code procesado}, {@code omitido} o {@code fallido}. Los
 *       doce se registran en cero al arrancar, para que una alerta sobre {@code fallido} tenga serie
 *       antes del primer fallo.</li>
 *   <li><b>{@code akine.job}</b> (observacion): un timer {@code akine_job_seconds} por {@code job}
 *       y {@code error}, y un span {@code job <nombre>} que es raiz de su traza —se exporta con el
 *       muestreo de siempre—, con el {@code traceId} en el MDC del log de la pasada.</li>
 *   <li>una linea de log con las cuentas como campos ({@code job}, {@code procesados},
 *       {@code omitidos}, {@code fallidos}, {@code duracionMs}): INFO si hubo algo que hacer, DEBUG
 *       si la pasada no encontro candidatos.</li>
 * </ul>
 *
 * <p><b>Sin PHI y sin cardinalidad:</b> ningun tag lleva ids, y el resumen son solo cuentas. Los ids
 * de un pase que falla van al log de error del propio job, nunca a una metrica.
 */
@Component
public class ObservacionDeJobs {

	private static final Logger log = LoggerFactory.getLogger(ObservacionDeJobs.class);

	static final String VENCIMIENTO_DE_PASES = "pases-vencimiento";
	static final String RENOVACION_DE_ABONOS = "abonos-renovacion";
	static final String AVISO_DE_VENCIMIENTO = "pases-aviso-vencimiento";
	static final String AVISO_DE_SALDO_BAJO = "pases-aviso-saldo-bajo";

	static final List<String> JOBS =
			List.of(VENCIMIENTO_DE_PASES, RENOVACION_DE_ABONOS, AVISO_DE_VENCIMIENTO, AVISO_DE_SALDO_BAJO);

	private final ObservationRegistry observaciones;
	private final Map<String, Contadores> contadores;

	public ObservacionDeJobs(MeterRegistry metricas, ObservationRegistry observaciones) {
		this.observaciones = observaciones;
		this.contadores = JOBS.stream().collect(Collectors.toUnmodifiableMap(
				job -> job, job -> new Contadores(metricas, job)));
	}

	/**
	 * Corre una pasada observada. Lo que la pasada lanza se registra en la observacion (tag
	 * {@code error}) y se relanza: decidir que hacer con el es del worker.
	 */
	public PasadaDeJob observar(String job, Supplier<PasadaDeJob> pasada) {
		Contadores delJob = contadores.get(job);
		if (delJob == null) {
			throw new IllegalArgumentException("Job sin metricas declaradas: " + job);
		}
		return Observation.createNotStarted("akine.job", observaciones)
				.contextualName("job " + job)
				.lowCardinalityKeyValue("job", job)
				.observe(() -> {
					// Adentro de la observacion: la linea de log lleva el traceId de la pasada.
					long inicio = System.nanoTime();
					PasadaDeJob resumen = pasada.get();
					delJob.registrar(resumen);
					(resumen.candidatos() > 0 ? log.atInfo() : log.atDebug())
							.addKeyValue("job", job)
							.addKeyValue("procesados", resumen.procesados())
							.addKeyValue("omitidos", resumen.omitidos())
							.addKeyValue("fallidos", resumen.fallidos())
							.addKeyValue("duracionMs", (System.nanoTime() - inicio) / 1_000_000)
							.log("Pasada de job terminada: {} procesados, {} omitidos, {} fallidos",
									resumen.procesados(), resumen.omitidos(), resumen.fallidos());
					return resumen;
				});
	}

	private static final class Contadores {

		private final Counter procesados;
		private final Counter omitidos;
		private final Counter fallidos;

		Contadores(MeterRegistry metricas, String job) {
			this.procesados = contador(metricas, job, "procesado");
			this.omitidos = contador(metricas, job, "omitido");
			this.fallidos = contador(metricas, job, "fallido");
		}

		private static Counter contador(MeterRegistry metricas, String job, String resultado) {
			return Counter.builder("akine.job.items")
					.description("Candidatos de un job de F9 por resultado")
					.tag("job", job)
					.tag("resultado", resultado)
					.register(metricas);
		}

		void registrar(PasadaDeJob resumen) {
			procesados.increment(resumen.procesados());
			omitidos.increment(resumen.omitidos());
			fallidos.increment(resumen.fallidos());
		}
	}
}
