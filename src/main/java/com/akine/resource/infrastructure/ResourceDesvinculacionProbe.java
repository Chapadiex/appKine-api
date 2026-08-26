package com.akine.resource.infrastructure;

import com.akine.organization.spi.ColaboradorDesvinculacionProbe;
import com.akine.resource.domain.DisponibilidadExcepcion;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Implementacion de {@code organization.spi.ColaboradorDesvinculacionProbe} desde
 * {@code resource} (AKINE-02.04): que le queda a un profesional en disponibilidad cuando se lo
 * desvincula.
 *
 * <h2>No confundir con {@link com.akine.resource.spi.DisponibilidadImpactProbe}</h2>
 *
 * <p>Son dos sondas de este mismo modulo con formas de {@code Impacto} distintas y que miran
 * cosas distintas. Esta ({@code ColaboradorDesvinculacionProbe}, definida por
 * {@code organization}) cuenta BLOQUES y EXCEPCIONES de disponibilidad —filas que ya existen
 * hoy en este modulo— para la pantalla de desvinculacion de un colaborador. La otra
 * ({@code DisponibilidadImpactProbe}, definida por este modulo) cuenta TURNOS de
 * {@code scheduling}, que no existe todavia, para la pantalla de edicion de disponibilidad. Que
 * las dos empiecen con "cuenta lo que queda pendiente" no las hace intercambiables: una tiene
 * datos reales para contar hoy y la otra, por diseno, siempre cero.
 *
 * <h2>Que cuenta, y por que no bloquea nada</h2>
 *
 * <p>RN-M05-003 ya establece que un bloque de disponibilidad sobrevive a la desvinculacion: no
 * se borra, y el calculo de disponibilidad efectiva lo ignora porque la membership dejo de
 * estar vigente, no porque el bloque se haya dado de baja. Esta sonda no cambia esa regla ni la
 * anticipa con un rechazo: solo informa, para que quien desvincula vea, antes de confirmar,
 * cuantos bloques recurrentes y cuantas excepciones puntuales de este profesional quedarian sin
 * efecto util. Es la misma distincion que ya explica el javadoc de
 * {@code ColaboradorDesvinculacionProbe}: la sonda de sedes ({@code ConsultorioDeactivationProbe})
 * IMPIDE por RN-M04-002; esta INFORMA por RN-M05-004. Si algun dia esto necesitara bloquear, es
 * una regla nueva con su propio RF, no un {@code throw} agregado aca adentro.
 *
 * <h2>Que NO cuenta</h2>
 *
 * <p>Las excepciones de alcance SEDE ENTERA ({@code membershipId IS NULL}) quedan afuera a
 * proposito: no son "de" este profesional, y la sede sigue existiendo despues de que el se
 * desvincule, asi que contarlas en el impacto de esta persona seria sumar algo que no le
 * pertenece y no queda huerfano por su baja.
 *
 * <h2>El {@code desde} que se reporta, y por que es una aproximacion declarada</h2>
 *
 * <p>Un bloque recurrente no tiene una unica "proxima ocurrencia" barata de calcular sin repetir
 * la aritmetica de {@code DisponibilidadEfectivaCalculator} (dia de semana + hora + zona horaria
 * de la sede) solo para una pantalla informativa. En vez de eso, esta sonda reporta {@code at}
 * —el instante que ya recibe como parametro— como el "primero" cuando hay al menos un bloque
 * activo: un bloque activo esta, por definicion, vigente ahora mismo, asi que decir "desde
 * ahora" no es impreciso, es exacto. Cuando NO hay bloques y solo hay excepciones futuras, el
 * primero es la fecha de la excepcion mas temprana, llevada a {@code Instant} en UTC a
 * medianoche: esta sonda es informativa, no alimenta el calculo de disponibilidad efectiva (que
 * si usa la zona horaria real de la sede), asi que una fecha calendario en UTC alcanza para que
 * la interfaz diga "desde el martes" sin pretender una precision que el dato no necesita.
 */
@Component
public class ResourceDesvinculacionProbe implements ColaboradorDesvinculacionProbe {

	/** Vocabulario estable, en lenguaje de usuario y plural, como pide la interfaz. */
	private static final String TIPO_SOLO_BLOQUES = "bloques de disponibilidad";
	private static final String TIPO_SOLO_EXCEPCIONES = "excepciones de disponibilidad";
	private static final String TIPO_AMBOS = "bloques y excepciones de disponibilidad";

	private final BloqueDisponibilidadRepository bloqueRepository;
	private final DisponibilidadExcepcionRepository excepcionRepository;

	public ResourceDesvinculacionProbe(
			BloqueDisponibilidadRepository bloqueRepository,
			DisponibilidadExcepcionRepository excepcionRepository) {
		this.bloqueRepository = bloqueRepository;
		this.excepcionRepository = excepcionRepository;
	}

	/**
	 * {@code accountId} no se usa: los bloques y las excepciones de este modulo se indexan por
	 * {@code membershipId} (RN-M05-001), nunca por cuenta. El puerto lo pide igual porque
	 * {@code scheduling} si puede necesitarlo.
	 */
	@Override
	@Transactional(readOnly = true)
	public Impacto pendingWorkOn(long organizationId, long membershipId, long accountId, Instant at) {
		long bloquesActivos =
				bloqueRepository.countByOrganizationIdAndMembershipIdAndActiveTrue(organizationId, membershipId);

		LocalDate fecha = LocalDate.ofInstant(at, ZoneOffset.UTC);
		List<DisponibilidadExcepcion> excepcionesFuturas =
				excepcionRepository.findFuturasDeLaMembership(organizationId, membershipId, fecha);

		long total = bloquesActivos + excepcionesFuturas.size();
		if (total == 0) {
			return Impacto.ninguno();
		}

		String tipo = tipoDe(bloquesActivos, excepcionesFuturas.size());
		Instant primero = bloquesActivos > 0 ? at : primeraFechaComo(excepcionesFuturas.get(0));
		return new Impacto(tipo, total, primero);
	}

	private static String tipoDe(long bloquesActivos, long excepcionesFuturas) {
		if (bloquesActivos > 0 && excepcionesFuturas > 0) {
			return TIPO_AMBOS;
		}
		return bloquesActivos > 0 ? TIPO_SOLO_BLOQUES : TIPO_SOLO_EXCEPCIONES;
	}

	private static Instant primeraFechaComo(DisponibilidadExcepcion excepcion) {
		return excepcion.getFechaDesde().atStartOfDay(ZoneOffset.UTC).toInstant();
	}
}
