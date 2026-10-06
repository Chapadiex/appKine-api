package com.akine.billing.application;

import com.akine.billing.domain.PresentacionItem;
import com.akine.billing.domain.port.PresentacionItemRepositoryPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Averigua en que lote quedo viva una obligacion, en una transaccion <b>aparte</b>.
 *
 * <p>Existe para el perdedor de la carrera de RN-M21-003: dos administrativos agregan la misma
 * obligacion a dos lotes a la vez, los dos pasan la consulta previa y el segundo choca contra
 * {@code uk_presentacion_item_ocupa}. Para responderle el 409 {@code obligacion-ya-presentada} con
 * el lote que la tiene —que es lo que el contrato promete— hay que leer el item del ganador, y esa
 * lectura <b>no puede hacerse en la sesion JPA que acaba de fallar el insert</b>: la especificacion
 * lo prohibe y la transaccion ya esta marcada para rollback. {@code REQUIRES_NEW} abre otra
 * conexion, que ve el commit del ganador. Mismo criterio que los {@code *EscrituraAparte} de
 * {@code clinical}.
 */
@Component
public class ObligacionOcupadaConsulta {

	private final PresentacionItemRepositoryPort items;

	public ObligacionOcupadaConsulta(PresentacionItemRepositoryPort items) {
		this.items = items;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<Long> loteQueLaOcupa(long organizationId, long obligacionId) {
		return items.findVivoDeLaObligacion(organizationId, obligacionId)
				.map(PresentacionItem::getPresentacionId);
	}
}
