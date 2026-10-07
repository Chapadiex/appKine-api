package com.akine.contracting.infrastructure;

import com.akine.contracting.domain.Convenio;
import com.akine.contracting.domain.ResolutorDeArancel;
import com.akine.contracting.spi.ArancelCongelado;
import com.akine.contracting.spi.ArancelDirectory;
import com.akine.contracting.spi.ResolucionDeArancel;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Adaptador de {@link ArancelDirectory} sobre las tablas de M16.
 *
 * <h2>Por que la resolucion no se escribe aca</h2>
 *
 * <p>Vive en {@code ResolutorDeArancel}, en el dominio, y este adaptador solo trae los conjuntos y
 * la llama. La misma resolucion la necesita {@code ArancelService} para el endpoint de
 * administracion: si cada uno la escribiera, el importe que la pantalla muestra y el que
 * {@code billing} devenga podrian divergir sin que ninguna prueba lo note.
 *
 * <h2>{@code resolver} y {@code congelar} hacen cosas OPUESTAS</h2>
 *
 * <p>La primera es una lectura viva —para decidir y mostrar—; la segunda es la copia que el
 * consumidor guarda. Ver {@link com.akine.contracting.spi.ArancelCongelado}: confundirlas es lo que
 * RN-M16-003 prohibe.
 *
 * <p><b>Las dos leen SIN lock</b>, y eso es correcto: no escriben nada, y el lock existe para
 * serializar escrituras. Una lectura concurrente con una escritura puede devolver el estado de
 * antes o el de despues, y las dos respuestas son legitimas — lo que no puede pasar es que existan
 * dos convenios solapados para que la lectura elija mal, y eso lo garantiza la escritura.
 */
@Component
public class ContractingArancelDirectory implements ArancelDirectory {

	private final ConvenioRepository convenios;
	private final ConvenioArancelRepository aranceles;

	public ContractingArancelDirectory(
			ConvenioRepository convenios, ConvenioArancelRepository aranceles) {

		this.convenios = convenios;
		this.aranceles = aranceles;
	}

	@Override
	@Transactional(readOnly = true)
	@SuppressWarnings("java:S107")
	public ResolucionDeArancel resolver(
			long organizationId,
			long consultorioId,
			long financiadorId,
			long planId,
			long practicaId,
			Long ofertaId,
			LocalDate fecha) {

		List<Convenio> candidatos =
				convenios.findActivosPorAlcance(organizationId, consultorioId, financiadorId, planId);

		// Los aranceles se leen solo si hay convenio: en el caso mas frecuente —paciente
		// particular, sin convenio con ese plan— esa segunda consulta no se usaria para nada.
		return ResolutorDeArancel.convenioAplicable(candidatos, fecha)
				.map(convenio -> ResolutorDeArancel.resolver(
						List.of(convenio),
						aranceles.findActivosPorPractica(organizationId, convenio.getId(), practicaId),
						practicaId,
						ofertaId,
						fecha))
				.orElseGet(() -> ResolutorDeArancel.resolver(List.of(), List.of(), practicaId, fecha));
	}

	@Override
	@Transactional(readOnly = true)
	@SuppressWarnings("java:S107")
	public Optional<ArancelCongelado> congelar(
			long organizationId,
			long consultorioId,
			long financiadorId,
			long planId,
			long practicaId,
			Long ofertaId,
			LocalDate fecha) {

		return resolver(
				organizationId, consultorioId, financiadorId, planId, practicaId, ofertaId, fecha)
				.valor()
				.map(vigente -> ResolutorDeArancel.congelar(vigente, Instant.now()));
	}
}
