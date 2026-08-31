package com.akine.clinical.domain.port;

import com.akine.clinical.domain.AntecedenteClinico;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.TipoAntecedente;

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
}
