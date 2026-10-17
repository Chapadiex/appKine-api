package com.akine.activity.application;

import com.akine.offering.spi.EsquemaDeCobro;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.ZonaDeSede;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Lo que el consumo de creditos y el devengo por clase leen de la sede y de la oferta, <b>una vez
 * por pedido</b> (AKINE-09.05, docs/carga.md §12).
 *
 * <p>La asistencia en lote corre N transacciones, una por participante (RF-M13-008), y cada una
 * volvia a leer la sede para sacar su zona horaria y la oferta para saber si cobra por pack: dos
 * sentencias identicas por item. Ninguna de las dos lecturas toma un lock ni decide una carrera
 * —eran lecturas consistentes sin {@code FOR UPDATE}—, asi que leerlas una vez por pedido no mueve
 * ninguna regla: la ventana en la que un cambio de zona o de esquema de cobro no se ve es el mismo
 * pedido, igual que la sede y los permisos que el lote ya resuelve al entrar.
 *
 * <p>U-39 suma, con el mismo criterio, el precio vigente de la oferta el dia de la clase y su
 * nombre: los dos son iguales para todos los participantes del lote, y el devengo los copia a su
 * propia fila.
 *
 * <p>Vive lo que dura un pedido y <b>no es seguro entre hilos</b>: el lote recorre los items en
 * secuencia. No es una cache: no sobrevive al pedido ni se comparte entre pedidos.
 */
public final class LecturasDeConsumo {

	private final ZoneId zona;
	private final Map<Long, Optional<EsquemaDeCobro>> esquemaPorOferta = new HashMap<>();
	private final Map<PrecioDelDia, Optional<PrecioDeOferta>> precioPorOfertaYDia = new HashMap<>();
	private final Map<Long, Optional<OfertaSnapshot>> ofertaPorId = new HashMap<>();

	/** La zona sale de la sede que el llamador ya leyo para validar el tenant. */
	public LecturasDeConsumo(ConsultorioSnapshot sede) {
		this.zona = ZonaDeSede.de(sede);
	}

	/** La zona de la sede, para el dia de la clase. */
	ZoneId zona() {
		return zona;
	}

	/** El esquema de cobro de la oferta, leido la primera vez que alguien lo pregunta en el pedido. */
	Optional<EsquemaDeCobro> esquema(long ofertaId, Supplier<Optional<EsquemaDeCobro>> leer) {
		return esquemaPorOferta.computeIfAbsent(ofertaId, id -> leer.get());
	}

	/** El precio vigente de la oferta ese dia local, leido una vez por pedido. */
	Optional<PrecioDeOferta> precio(long ofertaId, LocalDate dia, Supplier<Optional<PrecioDeOferta>> leer) {
		return precioPorOfertaYDia.computeIfAbsent(new PrecioDelDia(ofertaId, dia), clave -> leer.get());
	}

	/** La oferta, para el nombre que se congela en la deuda, leida una vez por pedido. */
	Optional<OfertaSnapshot> oferta(long ofertaId, Supplier<Optional<OfertaSnapshot>> leer) {
		return ofertaPorId.computeIfAbsent(ofertaId, id -> leer.get());
	}

	private record PrecioDelDia(long ofertaId, LocalDate dia) {
	}
}
