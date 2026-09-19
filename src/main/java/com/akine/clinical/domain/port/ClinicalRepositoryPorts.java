package com.akine.clinical.domain.port;

import com.akine.clinical.domain.AntecedenteClinico;
import com.akine.clinical.domain.EntradaClinica;
import com.akine.clinical.domain.EntradaClinicaVersion;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.TipoAntecedente;

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
}
