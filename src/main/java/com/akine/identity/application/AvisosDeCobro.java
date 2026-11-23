package com.akine.identity.application;

import com.akine.notification.spi.NotificationEnqueueCommand;
import com.akine.notification.spi.NotificationOutbox;
import com.akine.notification.spi.NotificationType;
import com.akine.organization.spi.PruebasGratis;
import com.akine.organization.spi.PruebasGratis.PagoQueVence;
import com.akine.organization.spi.ZonaDeOrganizacion;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Correos del vencimiento del periodo pagado (19/11/2026, docs/diseno/AKINE-cobro-mensual.md), a
 * los administradores del centro:
 *
 * <ul>
 *   <li>{@code SUSCRIPCION_POR_VENCER} a 15, 10, 5, 2 y 1 dias ({@link HitosDeAviso});</li>
 *   <li>{@code SUSCRIPCION_VENCIDA} al vencer: quedan 3 dias de gracia;</li>
 *   <li>{@code SUSCRIPCION_SUSPENDIDA} al terminar la gracia: solo lectura hasta el cierre;</li>
 *   <li>{@code SUSCRIPCION_CERRADA} al cierre.</li>
 * </ul>
 *
 * <p>Solo se manda el ultimo hito alcanzado, asi un worker que estuvo caido no larga una rafaga.
 * La clave del outbox lleva el hito y el vencimiento: el worker puede pasar todas las veces que
 * quiera, y un centro que paga y vuelve a vencer recibe los avisos del periodo nuevo. Cuanto dura
 * la gracia y cuando se cierra lo sabe {@code organization}: viaja calculado en
 * {@link PagoQueVence}.
 */
@Service
public class AvisosDeCobro {

	private static final Logger log = LoggerFactory.getLogger(AvisosDeCobro.class);

	/** Un cierre de hace mas de esto ya no se avisa: cubre un worker caido varios dias. */
	static final Duration VENTANA_DE_ATRASO = Duration.ofDays(7);

	/**
	 * Hasta cuanto atras se buscan vencimientos: mas que el cierre (18 dias) mas la ventana. Se
	 * filtra despues con los hitos que trae cada fila, asi que pasarse no manda nada de mas.
	 */
	static final Duration BUSQUEDA_HACIA_ATRAS = Duration.ofDays(45);

	private final NotificationOutbox outbox;
	private final PruebasGratis centros;
	private final AccountIdentityDirectory cuentas;
	private final ZonaDeOrganizacion zonas;

	public AvisosDeCobro(NotificationOutbox outbox, PruebasGratis centros, AccountIdentityDirectory cuentas,
			ZonaDeOrganizacion zonas) {
		this.outbox = outbox;
		this.centros = centros;
		this.cuentas = cuentas;
		this.zonas = zonas;
	}

	/** El aviso que le toca a un periodo pagado: tipo, parte de la clave y dias que faltan. */
	record Aviso(NotificationType tipo, String clave, Long diasRestantes) {
	}

	/** Encola el ultimo aviso alcanzado de cada periodo que vence cerca. Devuelve cuantos centros avisaron. */
	@Transactional
	public int avisarVencimientos(Instant ahora) {
		int avisados = 0;
		Duration primerHito = Duration.ofDays(HitosDeAviso.DIAS.getFirst());
		for (PagoQueVence pago : centros.pagosQueVencenEntre(ahora.minus(BUSQUEDA_HACIA_ATRAS), ahora.plus(primerHito))) {
			Optional<Aviso> aviso = avisoDe(pago, ahora);
			if (aviso.isEmpty()) {
				continue;
			}
			ZoneId zona = zonas.de(pago.organizationId());
			for (AccountIdentity cuenta : AvisosDePruebaGratis.identidades(cuentas,
					centros.administradores(pago.organizationId()))) {
				outbox.enqueue(new NotificationEnqueueCommand(aviso.get().tipo(), cuenta.email(),
						aviso.get().clave() + "-" + cuenta.accountId(), pago.organizationId(), null,
						datos(pago, aviso.get(), cuenta, zona)));
			}
			avisados++;
		}
		if (avisados > 0) {
			log.info("Avisos de cobro revisados: centros={}", avisados);
		}
		return avisados;
	}

	/**
	 * El ultimo hito alcanzado de un periodo pagado, o vacio si todavia no toca ninguno o el cierre
	 * fue hace mas de {@link #VENTANA_DE_ATRASO}.
	 */
	static Optional<Aviso> avisoDe(PagoQueVence pago, Instant ahora) {
		String sufijo = "-" + pago.organizationId() + "-" + pago.pagadoHasta().getEpochSecond();
		if (!ahora.isBefore(pago.cierreEl())) {
			return ahora.isBefore(pago.cierreEl().plus(VENTANA_DE_ATRASO))
					? Optional.of(new Aviso(NotificationType.SUSCRIPCION_CERRADA, "suscripcion-cerrada" + sufijo, null))
					: Optional.empty();
		}
		if (!ahora.isBefore(pago.soloLecturaDesde())) {
			return Optional.of(new Aviso(NotificationType.SUSCRIPCION_SUSPENDIDA, "suscripcion-suspendida" + sufijo,
					null));
		}
		if (!ahora.isBefore(pago.pagadoHasta())) {
			return Optional.of(new Aviso(NotificationType.SUSCRIPCION_VENCIDA, "suscripcion-vencida" + sufijo, null));
		}
		OptionalInt hito = HitosDeAviso.alcanzado(pago.pagadoHasta(), ahora);
		if (hito.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new Aviso(NotificationType.SUSCRIPCION_POR_VENCER,
				"suscripcion-por-vencer-" + hito.getAsInt() + sufijo,
				HitosDeAviso.diasRestantes(pago.pagadoHasta(), ahora)));
	}

	private static Map<String, String> datos(PagoQueVence pago, Aviso aviso, AccountIdentity cuenta, ZoneId zona) {
		Map<String, String> datos = new LinkedHashMap<>();
		String nombre = AvisosDePruebaGratis.primerNombre(cuenta);
		if (nombre != null) {
			datos.put("nombre", nombre);
		}
		datos.put("organizacionNombre", pago.nombreCentro());
		datos.put("suscripcionVencimiento", fecha(pago.pagadoHasta(), zona));
		datos.put("suscripcionSoloLectura", fecha(pago.soloLecturaDesde(), zona));
		datos.put("suscripcionCierre", fecha(pago.cierreEl(), zona));
		if (aviso.diasRestantes() != null) {
			datos.put("diasRestantes", String.valueOf(aviso.diasRestantes()));
		}
		return datos;
	}

	private static String fecha(Instant instante, ZoneId zona) {
		return AvisosDePruebaGratis.FECHA.format(instante.atZone(zona));
	}
}
