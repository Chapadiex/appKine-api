package com.akine.billing.infrastructure;

import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.Responsable;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.encounter.spi.CierreDeSesionObserver;
import com.akine.encounter.spi.SesionCerrada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Convierte el cierre de una atencion en la deuda que le corresponde (RF-M18-001, RN-M18-001).
 *
 * <p>La direccion de la dependencia es {@code billing -> encounter}: {@code encounter} declara el
 * contrato y no sabe que existe este bean. Poner la llamada al reves —que la sesion invoque a
 * facturacion— haria que el modulo clinico dependa del economico, que es exactamente el acoplamiento
 * que el UML de 2019 tenia y que el modelo actual deshace.
 *
 * <h2>Las tres reglas que decide esta clase</h2>
 *
 * <ol>
 *   <li><b>Sin asistencia no hay deuda.</b> M18 devenga "al concretar la prestacion", y una
 *       ausencia no es una prestacion. Cobrar un no-show es una politica de centro que necesita su
 *       propia configuracion —cuanto, a partir de cuando, con cuanto aviso— y no existe: cobrarlo
 *       por defecto seria decidirlo por el usuario.</li>
 *   <li><b>Sin precio no hay deuda.</b> Una oferta sin {@code precio_base} —M27 lo permite— es una
 *       prestacion que el centro todavia no tarifo. Devengar cero seria ensuciar la cuenta
 *       corriente con filas que nadie va a pagar, y devengar un importe inventado es peor.</li>
 *   <li><b>Idempotencia por prestacion.</b> Cerrar dos veces es idempotente por RN-M14-005, asi que
 *       este observador tiene que serlo tambien. Se consulta antes de insertar: sin eso, el segundo
 *       cierre chocaria contra el unique de V36 y haria fallar un cierre que deberia no hacer
 *       nada.</li>
 * </ol>
 *
 * <p>Hoy devenga <b>una sola</b> obligacion, a nombre del paciente: DP-10 fijo cobertura PARTICULAR
 * y dejo afuera financiadores y convenios. Cuando lleguen, la obligacion mixta son dos filas con el
 * mismo {@code sesion_id} y responsables distintos, y este es el lugar donde se decide el reparto.
 */
@Component
public class ObligacionDevengador implements CierreDeSesionObserver {

	private static final Logger log = LoggerFactory.getLogger(ObligacionDevengador.class);

	private final ObligacionRepositoryPort obligaciones;

	public ObligacionDevengador(ObligacionRepositoryPort obligaciones) {
		this.obligaciones = obligaciones;
	}

	@Override
	public void alCerrar(SesionCerrada cierre) {
		if (!cierre.asistio()) {
			log.info("Sesion cerrada sin asistencia: no se devenga deuda. sesionId={}", cierre.sesionId());
			return;
		}

		BigDecimal precio = cierre.precioDeLaOferta();
		if (precio == null || precio.signum() <= 0) {
			// Se registra y no se lanza: hacer fallar el cierre clinico porque falta un precio
			// dejaria al profesional sin poder terminar su atencion por un dato administrativo que
			// no es suyo. Queda en el log para que se note, que es el punto medio honesto.
			log.warn("Sesion cerrada sobre una oferta sin precio: no se devenga deuda. "
					+ "sesionId={} ofertaId={}", cierre.sesionId(), cierre.ofertaId());
			return;
		}

		if (obligaciones.findPorPrestacion(cierre.sesionId(), Responsable.PACIENTE).isPresent()) {
			return;
		}

		Obligacion obligacion = obligaciones.save(new Obligacion(
				cierre.organizationId(),
				cierre.consultorioId(),
				cierre.sesionId(),
				cierre.personaId(),
				Responsable.PACIENTE,
				precio,
				cierre.moneda(),
				cierre.ofertaId(),
				"Sesion " + cierre.numeroSesion(),
				cierre.cerradaEn()));

		log.info("Obligacion devengada: obligacionId={} sesionId={} personaId={} importe={} {}",
				obligacion.getId(), cierre.sesionId(), cierre.personaId(), precio, cierre.moneda());
	}
}
