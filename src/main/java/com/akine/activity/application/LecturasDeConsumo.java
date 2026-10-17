package com.akine.activity.application;

import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.ZonaDeSede;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * Lo que el consumo de creditos lee de la sede y de la oferta, <b>una vez por pedido</b>
 * (AKINE-09.05, docs/carga.md §12).
 *
 * <p>La asistencia en lote corre N transacciones, una por participante (RF-M13-008), y cada una
 * volvia a leer la sede para sacar su zona horaria y la oferta para saber si cobra por pack: dos
 * sentencias identicas por item. Ninguna de las dos lecturas toma un lock ni decide una carrera
 * —eran lecturas consistentes sin {@code FOR UPDATE}—, asi que leerlas una vez por pedido no mueve
 * ninguna regla: la ventana en la que un cambio de zona o de esquema de cobro no se ve es el mismo
 * pedido, igual que la sede y los permisos que el lote ya resuelve al entrar.
 *
 * <p>Vive lo que dura un pedido y <b>no es seguro entre hilos</b>: el lote recorre los items en
 * secuencia. No es una cache: no sobrevive al pedido ni se comparte entre pedidos.
 */
public final class LecturasDeConsumo {

	private final ZoneId zona;
	private final Map<Long, Boolean> consumeCreditoPorOferta = new HashMap<>();

	/** La zona sale de la sede que el llamador ya leyo para validar el tenant. */
	public LecturasDeConsumo(ConsultorioSnapshot sede) {
		this.zona = ZonaDeSede.de(sede);
	}

	/** La zona de la sede, para el dia de la clase. */
	ZoneId zona() {
		return zona;
	}

	/** Si la oferta consume credito, leido la primera vez que alguien lo pregunta en el pedido. */
	boolean consumeCredito(long ofertaId, LongPredicate leer) {
		return consumeCreditoPorOferta.computeIfAbsent(ofertaId, leer::test);
	}
}
