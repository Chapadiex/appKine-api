package com.akine.billing.application;

import com.akine.billing.domain.PresentacionItem;
import com.akine.billing.domain.port.PresentacionItemRepositoryPort;
import com.akine.platform.spi.transaccion.AlTerminarLaTransaccion;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * Averigua en que lote quedo viva una obligacion, en una transaccion <b>aparte</b>.
 *
 * <p>Existe para el perdedor de la carrera de RN-M21-003: dos administrativos agregan la misma
 * obligacion a dos lotes a la vez, los dos pasan la consulta previa y el segundo choca contra
 * {@code uk_presentacion_item_ocupa}. Para responderle el 409 {@code obligacion-ya-presentada} con
 * el lote que la tiene —que es lo que el contrato promete— hay que leer el item del ganador, y esa
 * lectura <b>no puede hacerse en la sesion JPA que acaba de fallar el insert</b>: la especificacion
 * lo prohibe y la transaccion ya esta marcada para rollback. Por eso corre <b>despues</b> de que
 * esa transaccion revirtio y devolvio su conexion, en una nueva que ve el commit del ganador, y
 * falla si alguien la llama con una transaccion abierta. Hasta el 11/10/2026 era un
 * {@code REQUIRES_NEW} desde el {@code catch}, que retenia dos conexiones por pedido
 * (docs/carga.md §8).
 */
@Component
public class ObligacionOcupadaConsulta {

	private final PresentacionItemRepositoryPort items;

	private final TransactionTemplate lectura;

	public ObligacionOcupadaConsulta(
			PresentacionItemRepositoryPort items, PlatformTransactionManager transactionManager) {
		this.items = items;
		this.lectura = new TransactionTemplate(transactionManager);
		this.lectura.setReadOnly(true);
	}

	public Optional<Long> loteQueLaOcupa(long organizationId, long obligacionId) {
		AlTerminarLaTransaccion.exigirSinTransaccion("La lectura del lote que ocupa la obligacion");
		return lectura.execute(estado -> items.findVivoDeLaObligacion(organizationId, obligacionId)
				.map(PresentacionItem::getPresentacionId));
	}
}
