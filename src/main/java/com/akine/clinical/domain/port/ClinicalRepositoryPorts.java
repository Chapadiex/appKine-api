package com.akine.clinical.domain.port;

import com.akine.clinical.domain.AdjuntoClinico;
import com.akine.clinical.domain.AntecedenteClinico;
import com.akine.clinical.domain.EntradaClinica;
import com.akine.clinical.domain.EntradaClinicaVersion;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.TipoAntecedente;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Los puertos de persistencia de {@code clinical}, declarados en {@code domain}.
 *
 * <p>Viven aca y no en {@code infrastructure} por la regla que 01.01 dejo fijada: {@code
 * application} consume <b>puertos en {@code domain}</b>, nunca repositorios de
 * {@code infrastructure}. La direccion permitida es {@code infrastructure -> application}, y
 * ArchUnit la verifica.
 *
 * <p><b>Toda firma lleva {@code organizationId}, sin excepcion.</b> No hay ni un metodo que
 * resuelva por id pelado: un {@code findById} en un modulo clinico es una fuga de tenant esperando
 * a que alguien lo llame desde un camino que no filtro antes.
 */
public final class ClinicalRepositoryPorts {

	private ClinicalRepositoryPorts() {
	}

	public interface HistoriaClinicaRepositoryPort {

		HistoriaClinica save(HistoriaClinica historia);

		/**
		 * Persiste y sincroniza con la base en el acto.
		 *
		 * <p>Hace falta para que el choque contra {@code uk_historia_clinica_persona_vigente}
		 * llegue <b>dentro</b> del try del servicio y no al cierre de la transaccion, que es donde
		 * ya no se puede convertir en una respuesta idempotente. Mismo motivo por el que
		 * {@code PerfilPacienteService} usa {@code saveAndFlush}.
		 */
		HistoriaClinica saveAndFlush(HistoriaClinica historia);

		Optional<HistoriaClinica> findByIdAndOrganizationId(Long id, Long organizationId);

		/** La historia vigente de esa persona en esa organizacion, si existe. */
		Optional<HistoriaClinica> buscarVigentePorPersona(Long organizationId, Long personaId);
	}

	public interface AntecedenteClinicoRepositoryPort {

		AntecedenteClinico save(AntecedenteClinico antecedente);

		Optional<AntecedenteClinico> findByIdAndOrganizationId(Long id, Long organizationId);

		/**
		 * Los antecedentes de una historia.
		 *
		 * @param soloVigentes {@code true} devuelve los que siguen aplicando; {@code false}
		 *                     devuelve tambien los dados de baja, que es lo que hace consultable
		 *                     el historico (regla maestra 10)
		 * @param tipo         filtro opcional por clase de antecedente; {@code null} los trae todos
		 */
		List<AntecedenteClinico> buscarDeHistoria(
				Long organizationId, Long historiaClinicaId, TipoAntecedente tipo, boolean soloVigentes);

		/**
		 * Los antecedentes vigentes para una pagina de timeline: los {@code limite} mas recientes
		 * con {@code registradoEn <= hasta}.
		 *
		 * <p>Es una consulta propia y no un filtro mas sobre {@link #buscarDeHistoria}: aquella no
		 * tiene tope ni corte temporal, y el timeline necesita las dos cosas para no traerse la
		 * anamnesis entera de un paciente cronico en cada pagina.
		 */
		List<AntecedenteClinico> buscarParaTimeline(
				Long organizationId, Long historiaClinicaId, Instant hasta, int limite);
	}

	public interface EntradaClinicaRepositoryPort {

		EntradaClinica save(EntradaClinica entrada);

		/**
		 * Persiste y sincroniza con la base en el acto.
		 *
		 * <p>Hace falta en el alta: la version 1 necesita el id de la cabecera para insertarse, y
		 * sin el flush ese id no existe hasta el cierre de la transaccion. Mismo motivo por el
		 * que {@code HistoriaClinicaRepositoryPort} tiene su propio {@code saveAndFlush}.
		 */
		EntradaClinica saveAndFlush(EntradaClinica entrada);

		Optional<EntradaClinica> findByIdAndOrganizationId(Long id, Long organizationId);

		/**
		 * La misma consulta, pero forzando el avance de la version de la cabecera al commitear.
		 *
		 * <p><b>Es lo que serializa la numeracion de las enmiendas</b>, y es la leccion de 02.07
		 * traida hasta aca. Alla el {@code @Version} del padre no protegia nada porque la
		 * escritura solo tocaba tablas hijas; aca la escritura toca al padre —el contador de
		 * versiones es suyo— y el {@code OPTIMISTIC_FORCE_INCREMENT} garantiza que dos enmiendas
		 * concurrentes no puedan las dos creer que leyeron la ultima version. El perdedor recibe
		 * {@code OptimisticLockingFailureException} y reintenta.
		 *
		 * <p>Se usa <b>solo</b> para enmendar. La baja logica no lo necesita: modifica columnas de
		 * la propia fila y JPA le sube la version sola.
		 */
		Optional<EntradaClinica> findWithLockByIdAndOrganizationId(Long id, Long organizationId);

		/**
		 * Las entradas de una historia, mas recientes primero por {@code ocurrioEn}.
		 *
		 * @param soloVigentes {@code true} devuelve las que estan en el timeline; {@code false}
		 *                     devuelve tambien las dadas de baja, que siguen siendo consultables
		 *                     (regla maestra 10)
		 */
		List<EntradaClinica> buscarDeHistoria(
				Long organizationId, Long historiaClinicaId, boolean soloVigentes);

		/**
		 * Las entradas vigentes para una pagina de timeline: las {@code limite} mas recientes con
		 * {@code ocurrioEn <= hasta}.
		 *
		 * <p>Solo vigentes, sin parametro que lo negocie: una entrada dada de baja <b>sale del
		 * timeline</b> y sigue siendo consultable por su id, que es lo que distingue "no lo
		 * muestres" de "no existio". Un flag aca dejaria que un llamador la volviera a indexar.
		 */
		List<EntradaClinica> buscarParaTimeline(
				Long organizationId, Long historiaClinicaId, Instant hasta, int limite);
	}

	public interface EntradaClinicaVersionRepositoryPort {

		EntradaClinicaVersion save(EntradaClinicaVersion version);

		/** Todas las versiones de una entrada, de la mas nueva a la mas vieja (RF-M09-006). */
		List<EntradaClinicaVersion> buscarDeEntrada(Long organizationId, Long entradaClinicaId);

		/**
		 * La version vigente —la de numero mas alto— de cada una de esas entradas.
		 *
		 * <p>Es una sola consulta y no una por entrada a proposito: listar las entradas de un
		 * paciente cronico con 400 hechos haria 400 viajes a la base para mostrar una pantalla.
		 *
		 * @param entradaIds vacio devuelve vacio sin consultar: una {@code IN ()} vacia es un
		 *                   error de sintaxis en varios motores y una consulta inutil en todos
		 */
		List<EntradaClinicaVersion> buscarVigentesDe(
				Long organizationId, Collection<Long> entradaIds);
	}

	/**
	 * Metadata de los adjuntos clinicos. El binario vive detras de
	 * {@link ContenidoClinicoStoragePort}.
	 *
	 * <p>Toda firma acota por {@code organizationId} <b>y</b> por {@code historiaClinicaId}. Lo
	 * segundo no es redundante con lo primero: un adjunto resuelto solo por tenant e id dejaria
	 * que un pedido sobre la historia A entregue el estudio de la historia B del mismo centro,
	 * saltandose la autorizacion que ya se evaluo contra A.
	 */
	public interface AdjuntoClinicoRepositoryPort {

		AdjuntoClinico save(AdjuntoClinico adjunto);

		/**
		 * Persiste y sincroniza con la base en el acto.
		 *
		 * <p>Hace falta para que el choque contra {@code uk_adjunto_clinico_contenido_vigente}
		 * llegue <b>dentro</b> del try del servicio y no al cierre de la transaccion, que es donde
		 * ya no se puede convertir en una respuesta idempotente. Y para que el unique decida
		 * <b>antes</b> de que se escriba el binario en disco.
		 */
		AdjuntoClinico saveAndFlush(AdjuntoClinico adjunto);

		/** Un adjunto de esa historia. Devuelve vacio si es de otra, aunque el id exista. */
		Optional<AdjuntoClinico> buscarDeLaHistoria(
				Long organizationId, Long historiaClinicaId, Long adjuntoId);

		/**
		 * El adjunto VIGENTE con ese contenido en esa historia, si ya lo hay.
		 *
		 * <p>Es el pre-chequeo que hace idempotente al reintento de una subida. <b>No es el que
		 * garantiza el invariante</b> —eso lo hace el unique de {@code V46}—: esta para ahorrar
		 * escribir el binario en el camino feliz.
		 */
		Optional<AdjuntoClinico> buscarVigentePorChecksum(
				Long organizationId, Long historiaClinicaId, String checksumSha256);

		/**
		 * Los adjuntos de una historia, paginados en la base.
		 *
		 * @param categoria     filtro opcional; {@code null} los trae todos
		 * @param entradaId     filtro opcional por la entrada que respaldan; {@code null} no filtra
		 * @param activoFiltro  {@code 1} solo vigentes, {@code 0} solo dados de baja, {@code -1}
		 *                      todos. Es un {@code int} y no un {@code Boolean} nullable porque
		 *                      son tres estados y no dos con ausencia, mismo criterio que el
		 *                      listado del padron
		 */
		@SuppressWarnings("checkstyle:ParameterNumber")
		List<AdjuntoClinico> listar(
				Long organizationId,
				Long historiaClinicaId,
				String categoria,
				Long entradaId,
				int activoFiltro,
				int offset,
				int limite);

		/**
		 * Los adjuntos vigentes para una pagina de timeline: los {@code limite} mas recientes con
		 * {@code subidoEn <= hasta}.
		 *
		 * <p>Lo que el timeline indexa es el <b>alta</b> del adjunto y por eso ordena por
		 * {@code subidoEn}. Un adjunto reclasificado o dado de baja despues no cambia de lugar en
		 * la linea de tiempo: el hecho datado es que ese dia entro un documento a la historia.
		 */
		List<AdjuntoClinico> buscarParaTimeline(
				Long organizationId, Long historiaClinicaId, Instant hasta, int limite);

		/** El total del mismo filtro, para que la pagina sepa cuantas hay. */
		long contar(
				Long organizationId,
				Long historiaClinicaId,
				String categoria,
				Long entradaId,
				int activoFiltro);
	}
}
