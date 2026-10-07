package com.akine.encounter.infrastructure;

import com.akine.clinical.spi.AutorizacionesDelCaso;
import com.akine.encounter.spi.CierreDeSesionObserver;
import com.akine.encounter.spi.SesionCerrada;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.person.spi.ConsumoDeAutorizaciones;
import com.akine.person.spi.ConsumoPorSesion;
import com.akine.person.spi.ResultadoDeConsumo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/**
 * Convierte el cierre de una atencion en el consumo de una unidad autorizada (RF-M17-004).
 *
 * <h2>1. UN CIERRE SIN SALDO NO HACE FALLAR EL CIERRE. Es la decision de la etapa</h2>
 *
 * <p>Esto va <b>en contra</b> del precedente que {@code billing.ObligacionDevengador} fijo en
 * 07.01, y la diferencia es deliberada. Conviene tenerla a la vista, porque el proximo que lea
 * este archivo va a querer "arreglarlo":
 *
 * <pre>
 *   ObligacionDevengador   si no puede devengar, HACE FALLAR el cierre.
 *                          Esta bien: una prestacion sin deuda no se nota —nadie reclama una
 *                          factura que nunca existio— y el centro descubre el agujero cuando
 *                          cuadra la caja del mes. Un error ruidoso es mejor que perder plata
 *                          en silencio.
 *
 *   esta clase             si no puede consumir, NO hace nada y el cierre sigue.
 *                          LA ATENCION OCURRIO: el kinesiologo atendio, el paciente estuvo.
 *                          Que el financiador no tenga saldo es un problema administrativo que
 *                          se resuelve despues —con otra autorizacion, o facturandole al
 *                          paciente— y bloquear el cierre de una HISTORIA CLINICA por eso es
 *                          exactamente lo que DP-06 prohibe.
 * </pre>
 *
 * <p>Lo que se pierde con esta decision esta asumido y hay que decirlo: una sesion puede quedar
 * cerrada sin haber consumido nada, y <b>nadie se entera salvo por el log y por el saldo que no
 * bajo</b>. La contrapartida —un profesional que no puede terminar su atencion porque a un
 * financiador se le acabo el cupo— es peor, y ademas no es reparable en el momento: el dato que
 * falta no es suyo.
 *
 * <p>Por eso {@code person.spi.ConsumoDeAutorizaciones} devuelve un {@link ResultadoDeConsumo} en
 * vez de lanzar. El desenlace es un valor, no una excepcion.
 *
 * <h2>2. Por que este bean vive en `encounter` y no en `person`</h2>
 *
 * <p>El diseño pedia lo contrario —{@code person.infrastructure} implementando
 * {@code encounter.spi.CierreDeSesionObserver}, igual que hace {@code billing}— y <b>no se
 * puede</b>: cierra un ciclo entre modulos.
 *
 * <pre>
 *   clinical  -&gt; person.spi     ya existe: HistoriaClinicaService usa PacienteDirectory (04.01)
 *   encounter -&gt; clinical.spi   ya existe: SesionService usa CasoDirectory (06.01/04.03)
 *   person    -&gt; encounter.spi  seria la arista nueva
 * </pre>
 *
 * <p>Las tres juntas son el ciclo {@code person -> encounter -> clinical -> person}, y
 * {@code ModuleArchitectureTest.sin_ciclos_entre_modulos} lo detecta: busca ciclos de
 * <b>cualquier</b> longitud, no solo de dos. Que {@code billing} pueda depender de {@code person}
 * y de {@code encounter} a la vez no es analogo: <b>nadie depende de {@code billing}</b>.
 *
 * <p>La arista que si funciona es {@code encounter -> person.spi}, que no cierra nada porque
 * {@code person} no alcanza a {@code encounter} por ningun camino. Lo que se conserva de la forma
 * original es lo que importaba: <b>{@code SesionService} sigue sin saber quien lo escucha</b> —se
 * habla con {@code CierreDeSesionObserver} y nada mas— y <b>{@code person} sigue siendo el unico
 * que escribe sus tablas</b>. Lo que se mueve es en que modulo vive el adaptador, y esta clase no
 * toca ninguna tabla.
 *
 * <p>Hay precedente exacto de esta forma en el mismo modulo: {@code EncounterRealizadoEnElCasoProbe}
 * y {@code SesionEventoContributor} son adaptadores de {@code encounter.infrastructure} que hablan
 * el {@code spi} del vecino.
 *
 * <h2>3. Las dos reglas que decide esta clase</h2>
 *
 * <ol>
 *   <li><b>Sin asistencia no hay consumo.</b> Una ausencia no es una prestacion, y el financiador
 *       no autorizo un no-show. Es la misma regla 1 de {@code ObligacionDevengador} y por el mismo
 *       motivo.</li>
 *   <li><b>El dia se calcula en la zona de la SEDE, no en UTC.</b> El cierre es un instante y la
 *       vigencia de una autorizacion es un dia del calendario; resolverlo en UTC corre el dia
 *       para media Argentina despues de las 21:00 y haria que una autorizacion que vence hoy
 *       rechace una sesion cerrada esta noche. Si la sede no resuelve o su zona esta mal cargada
 *       se cae a UTC y se deja dicho en el log: es preferible consumir con un dia posiblemente
 *       corrido a no consumir nada.</li>
 * </ol>
 *
 * <p>La cantidad fue <b>una unidad por sesion</b> hasta C-4 (ver la seccion 5). Una prestacion
 * que valga dos es una configuracion de oferta que M27 todavia no tiene, y inventarla aca seria
 * decidirla por el usuario.
 *
 * <h2>4. AKINE-06.04: cual autorizacion se consume dejo de ser una loteria</h2>
 *
 * <p>Hasta 06.04 esta clase pedia consumir sin decir <b>que</b> se habia prestado, porque no
 * existia el dato: el registro de tratamientos realizados no existia. {@code person} elegia
 * entonces "la que vence antes" entre las vigentes del paciente, y eso podia gastar <b>la
 * autorizacion equivocada</b> —una unidad de fonoaudiologia por una sesion de kinesiologia—. Era
 * el limite que 04.05 declaro por escrito y delego en esta etapa.
 *
 * <p>Ahora {@code SesionCerrada.practicasRealizadas()} viaja con el hecho y acota la eleccion.
 * <b>La cantidad NO cambia</b>: sigue siendo una unidad por sesion aunque se hayan aplicado cinco
 * practicas. Cobrar una unidad por practica es una decision economica sin RF que la respalde, y
 * cambiarla de callado seria peor que el defecto que esta etapa corrige.
 *
 * <p><b>Y una sesion sin practicas registradas se comporta exactamente como antes.</b> Son todas
 * las anteriores a 06.04. Ver {@code person.spi.ConsumoPorSesion}.
 *
 * <h2>5. AKINE C-4: una unidad por AUTORIZACION involucrada (DP-12), y el caso</h2>
 *
 * <p>DP-12 resolvio la cantidad que 06.04 dejo en una por sesion: la sesion consume <b>una unidad
 * en cada autorizacion involucrada</b>. Varias practicas bajo la misma autorizacion siguen siendo
 * una unidad; practicas de dos autorizaciones distintas gastan una de cada una. El agrupamiento lo
 * hace {@code person}, que es quien sabe que practica cubre cada autorizacion; esta clase solo
 * pasa los hechos y recibe un desenlace por autorizacion.
 *
 * <p>Ademas pregunta a {@code clinical} que autorizaciones estan atadas a planes de <b>otro</b>
 * caso del paciente (RF-M17-007, "no reutilizar autorizacion de otro Caso") y las excluye. Una
 * sesion sin caso no excluye nada: excluir todo lo atado a algun caso apagaria el consumo de las
 * sesiones viejas, que son casi todas. La arista {@code encounter -> clinical.spi} ya existia.
 */
@Component
public class ConsumoDeAutorizacionEnCierre implements CierreDeSesionObserver {

	private static final Logger log =
			LoggerFactory.getLogger(ConsumoDeAutorizacionEnCierre.class);

	/**
	 * Cada autorizacion involucrada gasta una unidad (DP-12). Mas de una por autorizacion seria una
	 * configuracion de oferta que M27 todavia no tiene.
	 */
	private static final int UNIDADES_POR_AUTORIZACION = 1;

	private final ConsumoDeAutorizaciones consumo;
	private final ConsultorioDirectory consultorios;
	private final AutorizacionesDelCaso autorizacionesDelCaso;

	public ConsumoDeAutorizacionEnCierre(
			ConsumoDeAutorizaciones consumo,
			ConsultorioDirectory consultorios,
			AutorizacionesDelCaso autorizacionesDelCaso) {

		this.consumo = consumo;
		this.consultorios = consultorios;
		this.autorizacionesDelCaso = autorizacionesDelCaso;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p><b>No lanza por falta de saldo ni por falta de autorizacion.</b> Ver la cabecera: es la
	 * decision central de AKINE-04.05 y va contra el precedente de {@code billing}.
	 *
	 * <p>Lo que si puede propagar es un fallo tecnico —la base caida, por ejemplo—, igual que
	 * cualquier otro observador. No se atrapa {@code Exception} a proposito: tragarse una
	 * excepcion de persistencia dentro de la transaccion del cierre no la des-marca, y Spring
	 * lanzaria {@code UnexpectedRollbackException} al commitear con un mensaje que no culpa a
	 * nadie. Es la trampa que este repositorio ya pago cuatro veces.
	 */
	@Override
	public void alCerrar(SesionCerrada cierre) {
		if (!cierre.asistio()) {
			log.debug("Sesion cerrada sin asistencia: no se consume autorizacion. sesionId={}",
					cierre.sesionId());
			return;
		}

		Set<Long> deOtroCaso = cierre.casoId() == null
				? Set.of()
				: autorizacionesDelCaso.deOtrosCasos(cierre.organizationId(), cierre.casoId());

		List<ResultadoDeConsumo> resultados = consumo.consumirPorSesion(new ConsumoPorSesion(
				cierre.organizationId(),
				cierre.personaId(),
				cierre.consultorioId(),
				cierre.sesionId(),
				diaLocalDeLaSede(cierre),
				UNIDADES_POR_AUTORIZACION,
				cierre.cerradaPorCuentaId(),
				// AKINE-06.04: acota contra que autorizaciones se puede imputar. Vacio conserva
				// el comportamiento anterior — ver ConsumoPorSesion.
				cierre.practicasRealizadas(),
				deOtroCaso));

		for (ResultadoDeConsumo resultado : resultados) {
			if (resultado.descontoEfectivo()) {
				log.info("Autorizacion consumida al cerrar: sesionId={} autorizacionId={} "
								+ "saldoRestante={}",
						cierre.sesionId(), resultado.autorizacionId(), resultado.saldoRestante());
			} else {
				// El resto de los desenlaces NO son errores y no interrumpen nada.
				// SIN_AUTORIZACION_ELEGIBLE es ademas el caso mas frecuente: todo paciente
				// particular cierra asi.
				log.debug("Cierre sin consumo de autorizacion: sesionId={} autorizacionId={} "
								+ "desenlace={}",
						cierre.sesionId(), resultado.autorizacionId(), resultado.desenlace());
			}
		}
	}

	/**
	 * El dia del calendario en que ocurrio el cierre, en la zona de la sede.
	 *
	 * <p>Cae a UTC si la sede no resuelve o su zona esta mal cargada. Es una decision consciente:
	 * un dia posiblemente corrido es mejor que no consumir nada, porque lo segundo deja la unidad
	 * sin gastar y a nadie enterado.
	 */
	private LocalDate diaLocalDeLaSede(SesionCerrada cierre) {
		ZoneId zona = consultorios.find(cierre.organizationId(), cierre.consultorioId())
				.map(ConsultorioSnapshot::timezone)
				.map(declarada -> zonaDe(declarada, cierre))
				.orElseGet(() -> {
					log.warn("Sede no resoluble al consumir autorizacion: se usa UTC. "
									+ "sesionId={} consultorioId={}",
							cierre.sesionId(), cierre.consultorioId());
					return ZoneOffset.UTC;
				});
		return LocalDate.ofInstant(cierre.cerradaEn(), zona);
	}

	private static ZoneId zonaDe(String zona, SesionCerrada cierre) {
		try {
			return ZoneId.of(zona);
		} catch (DateTimeException zonaInvalida) {
			log.warn("Zona horaria invalida en la sede: se usa UTC. sesionId={} zona={}",
					cierre.sesionId(), zona);
			return ZoneOffset.UTC;
		}
	}
}
