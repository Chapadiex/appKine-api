package com.akine.person.application;

import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.PermissionCodes;
import com.akine.person.domain.TipoCobertura;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPacienteRepositoryPort;
import com.akine.person.spi.AporteDeResumen;
import com.akine.person.spi.CoberturaDeResumen;
import com.akine.person.spi.ConsultaDeResumen;
import com.akine.person.spi.HitoDeResumen;
import com.akine.person.spi.IndicadorDeResumen;
import com.akine.person.spi.ResumenDePersonaContributor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lo que la cobertura del paciente aporta al Paciente 360 (B-5, RF-M07-004).
 *
 * <h2>Por que vive en {@code person} y no en {@code contracting}</h2>
 *
 * <p>Porque la cobertura del paciente es de {@code person} (AKINE-03.04, tabla
 * {@code cobertura_paciente}) y lleva <b>congelados</b> el financiador y el plan que tenia al
 * firmarse. Leerla no necesita ningun otro modulo, asi que este contribuyente es interno: implementa
 * {@code person.spi} desde adentro de {@code person} y no agrega una sola arista al grafo de
 * modulos. Ponerlo en {@code contracting} si cerraria un ciclo —{@code person} ya depende de
 * {@code contracting.spi} para congelar la referencia— y ademas obligaria a publicar por
 * {@code spi} una lectura que hoy no existe.
 *
 * <h2>Pide {@code paciente:read}, el mismo permiso que {@code GET .../coberturas}</h2>
 *
 * <p>Hasta AKINE-DU-6 {@link #permisoRequerido()} era {@code null}: alcanzaba la pertenencia al
 * tenant, porque la matriz no tenia ningun permiso de lectura del padron. DP-22 lo creo, y la
 * seccion pide exactamente el mismo que {@link CoberturaPacienteService#listar}: nunca mas estricta
 * que el modulo duenio —el profesional veria la seccion omitida y la lista completa, con el numero
 * de afiliado sin enmascarar, un click mas alla— y nunca mas laxa. Como el 360 entero ya exige
 * {@code paciente:read}, hoy la seccion no se omite nunca; declararlo igual deja escrito de que
 * permiso depende y que el 360 lo siga solo si el de las coberturas cambia.
 *
 * <h2>Que muestra</h2>
 *
 * <p>Solo las coberturas <b>vigentes hoy</b> —activas y con la fecha dentro de su ventana—; el
 * historial completo lo sirve {@code GET .../coberturas}. Dos indicadores (cuantas vigentes y
 * cuantas de ellas con la credencial vencida, que es la alerta de mostrador de RNF-M08-005) y un
 * hito por cobertura, la principal primero:
 *
 * <ul>
 *   <li>{@code tipo}: {@code COBERTURA_PRINCIPAL} o {@code COBERTURA}.</li>
 *   <li>{@code ocurrioEn}: el inicio de la vigencia, a medianoche UTC. Es una fecha, no un
 *       instante: el hito la lleva en el unico campo temporal que tiene.</li>
 *   <li>{@code titulo}: financiador · plan · afiliado enmascarado · fin de vigencia.</li>
 *   <li>{@code estado}: {@code VIGENTE} o {@code CREDENCIAL_VENCIDA}.</li>
 * </ul>
 *
 * <p><b>El numero de afiliado viaja enmascarado</b>, con los ultimos cuatro digitos a la vista:
 * alcanzan para que el mostrador reconozca la credencial que tiene en la mano y no exponen el
 * identificador completo en una pantalla que se abre todo el dia. El numero entero sigue en la
 * lista de coberturas, que es donde se lo va a copiar.
 *
 * <p>Viaja en la estructura generica del 360 —indicadores e hitos—: aparece como un elemento mas
 * de {@code secciones}. Desde A-11 (contrato 0.70.0) cada hito trae ademas
 * {@link CoberturaDeResumen} con los mismos datos del titulo como campos —financiador, plan,
 * afiliado enmascarado, vigencia como fechas, principal y estado de la credencial—, para que la
 * pantalla deje de partir el {@code titulo}. Es aditivo: el resto de las secciones lo manda nulo.
 */
@Component
public class CoberturasEnElResumenDePersona implements ResumenDePersonaContributor {

	static final String SECCION = "coberturas";

	private static final int DIGITOS_VISIBLES = 4;
	private static final String MASCARA = "···";

	private final CoberturaPacienteRepositoryPort coberturas;
	private final Clock clock;

	@Autowired
	public CoberturasEnElResumenDePersona(CoberturaPacienteRepositoryPort coberturas) {
		this(coberturas, Clock.systemDefaultZone());
	}

	CoberturasEnElResumenDePersona(CoberturaPacienteRepositoryPort coberturas, Clock clock) {
		this.coberturas = coberturas;
		this.clock = clock;
	}

	@Override
	public String seccion() {
		return SECCION;
	}

	/** {@code paciente:read}, igual que {@code GET .../coberturas}. Ver el javadoc de la clase. */
	@Override
	public String permisoRequerido() {
		return PermissionCodes.PACIENTE_READ;
	}

	@Override
	public AporteDeResumen aportar(ConsultaDeResumen consulta) {
		LocalDate hoy = LocalDate.now(clock);

		// historial filtra por organizacion Y persona: el aislamiento de tenant lo hace la
		// consulta, nunca un id pelado.
		List<CoberturaPaciente> vigentes = coberturas
				.historial(consulta.organizationId(), consulta.personaId()).stream()
				.filter(cobertura -> cobertura.vigenteEl(hoy))
				.sorted(Comparator.comparing((CoberturaPaciente c) -> !c.isPrincipal())
						.thenComparing(CoberturaPaciente::getId))
				.toList();

		long credencialesVencidas = vigentes.stream()
				.filter(cobertura -> cobertura.credencialVencidaEl(hoy))
				.count();

		List<HitoDeResumen> hitos = new ArrayList<>();
		for (CoberturaPaciente cobertura : vigentes) {
			if (hitos.size() >= consulta.limiteHitos()) {
				break;
			}
			hitos.add(new HitoDeResumen(
					SECCION,
					cobertura.isPrincipal() ? "COBERTURA_PRINCIPAL" : "COBERTURA",
					cobertura.getVigenciaDesde().atStartOfDay(ZoneOffset.UTC).toInstant(),
					titulo(cobertura),
					cobertura.credencialVencidaEl(hoy) ? "CREDENCIAL_VENCIDA" : "VIGENTE",
					cobertura.getId(),
					datos(cobertura, hoy)));
		}

		return new AporteDeResumen(
				SECCION,
				List.of(
						IndicadorDeResumen.contando(
								"coberturas-vigentes", "Coberturas vigentes", vigentes.size()),
						IndicadorDeResumen.contando(
								"credenciales-vencidas", "Credenciales vencidas",
								credencialesVencidas)),
				hitos);
	}

	private static String titulo(CoberturaPaciente cobertura) {
		List<String> partes = new ArrayList<>();
		if (cobertura.getTipo() == TipoCobertura.PARTICULAR) {
			partes.add("Particular");
		} else {
			partes.add(cobertura.getFinanciadorNombre());
			partes.add(cobertura.getPlanNombre());
			String afiliado = enmascarar(cobertura.getNumeroAfiliado());
			if (afiliado != null) {
				partes.add("Afiliado " + afiliado);
			}
		}
		partes.add(cobertura.getVigenciaHasta() == null
				? "sin vencimiento"
				: "hasta " + cobertura.getVigenciaHasta());
		return String.join(" · ", partes.stream().filter(p -> p != null && !p.isBlank()).toList());
	}

	/**
	 * Los mismos datos del titulo, como campos (A-11). La pantalla los lee de aca y el titulo
	 * queda para leer: separar financiador, plan y vigencia partiendo el texto por " · " era la
	 * unica forma hasta este cambio, y se rompia con cualquier nombre de plan que tuviera un punto.
	 */
	private static CoberturaDeResumen datos(CoberturaPaciente cobertura, LocalDate hoy) {
		String estadoCredencial;
		if (cobertura.getCredencialVigenciaHasta() == null) {
			estadoCredencial = "SIN_VENCIMIENTO";
		} else {
			estadoCredencial = cobertura.credencialVencidaEl(hoy) ? "VENCIDA" : "VIGENTE";
		}
		return new CoberturaDeResumen(
				cobertura.getTipo().name(),
				cobertura.getFinanciadorId(),
				cobertura.getFinanciadorNombre(),
				cobertura.getPlanId(),
				cobertura.getPlanNombre(),
				enmascarar(cobertura.getNumeroAfiliado()),
				cobertura.getVigenciaDesde(),
				cobertura.getVigenciaHasta(),
				cobertura.isPrincipal(),
				estadoCredencial,
				cobertura.getCredencialVigenciaHasta());
	}

	/**
	 * Los ultimos cuatro caracteres a la vista y el resto tapado. Un numero de cuatro o menos se
	 * tapa entero: mostrarlo "enmascarado" seria mostrarlo completo.
	 */
	static String enmascarar(String numeroAfiliado) {
		if (numeroAfiliado == null || numeroAfiliado.isBlank()) {
			return null;
		}
		String limpio = numeroAfiliado.strip();
		if (limpio.length() <= DIGITOS_VISIBLES) {
			return MASCARA;
		}
		return MASCARA + limpio.substring(limpio.length() - DIGITOS_VISIBLES);
	}
}
