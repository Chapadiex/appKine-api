package com.akine.clinical.spi;

/**
 * El borde por donde {@code clinical} recibe y responde derivaciones al circuito clinico
 * (RF-M28-008, RF-M10-008, RF-M11-007).
 *
 * <h2>Este puerto SI autoriza, y es el primero del repositorio que lo hace</h2>
 *
 * <p>{@link PacienteDirectory}, {@link CasoDirectory}, {@link HistoriaClinicaDirectory} y
 * {@code OfertaDirectory} dicen los cuatro, en su javadoc, que <b>no autorizan nada</b>: confian en
 * que el llamador ya evaluo su permiso. Esta interfaz se aparta a proposito, y el motivo no es
 * comodidad.
 *
 * <p>El control de acceso clinico de DP-03 no es "un permiso": son <b>cuatro cosas acopladas</b>
 * —permiso, relacion asistencial, justificacion declarada y auditoria con la via por la que se
 * entro— y las cuatro viven en clases package-private de {@code clinical.application}. Si este
 * puerto no autorizara, el llamador tendria que evaluar {@code hc:write}, resolver la relacion
 * asistencial y escribir el evento de auditoria clinico por su cuenta. Eso es <b>DP-03
 * implementada dos veces</b>, y la segunda implementacion envejece sola: la primera vez que
 * alguien agregue una condicion, se la agrega a una de las dos.
 *
 * <p><b>El reparto, que es la regla que 08.05 hereda:</b> el llamador autoriza lo suyo —ver la
 * clase, ver el participante— y {@code clinical} autoriza lo clinico. Ninguna decision se toma dos
 * veces, y ninguna se toma en el modulo equivocado.
 *
 * <h2>Por que la orquestacion entra por afuera y no al reves</h2>
 *
 * <p>{@code clinical} no puede leer una clase ni una asistencia: seria {@code clinical -> activity}
 * y cerraria el ciclo con la arista {@code activity -> clinical.spi} que esta etapa introduce.
 * Medido con sonda y control negativo antes de escribir dominio —challenge pregunta 2—, no
 * razonado de memoria: {@code SlicesRuleDefinition} busca ciclos de cualquier longitud y el control
 * negativo destapo tres, uno de ellos por {@code scheduling}, que ninguna sonda habia nombrado.
 *
 * <p>Es el mismo patron con el que {@code identity} orquesta hacia {@code organization} desde
 * 01.02, y con el que {@code encounter} llama a {@code person.spi} para consumir autorizaciones.
 *
 * <h2>Lo que este puerto NO hace</h2>
 *
 * <ol>
 *   <li><b>No crea Sesiones.</b> Ni una. Derivar no es atender: es 08.05.</li>
 *   <li><b>No abre Casos.</b> El {@code casoClinicoId} viene elegido. Ver
 *       {@link RegistroDeDerivacion}.</li>
 *   <li><b>No numera nada dentro del Caso.</b> {@code CasoDirectory#siguienteNumeroDeSesion} no se
 *       llama desde aca.</li>
 *   <li><b>No consume autorizaciones.</b> Las mira. Consumir es de {@code person} y lo dispara el
 *       cierre de una Sesion.</li>
 *   <li><b>No publica en el timeline clinico.</b> Una derivacion es metadata de procedencia, no un
 *       evento clinico: ponerla en el timeline dejaria "vino a Pilates" al lado de una
 *       evolucion.</li>
 * </ol>
 */
public interface DerivacionClinicaRegistry {

	/**
	 * Que sabe {@code clinical} de esa participacion. Lectura clinica: autoriza y audita.
	 */
	EstadoClinicoDeParticipacion consultar(
			ActorDeDerivacion actor, OrigenDeParticipacion origen, long participacionId,
			long personaId);

	/**
	 * Vincula la participacion al Caso. <b>Idempotente</b>.
	 *
	 * <p>Un doble submit no produce un 500 ni un 409 seco: devuelve la derivacion que ya existe con
	 * {@code yaExistia = true}. La garantia tiene <b>dos capas y las dos hacen falta</b>, igual que
	 * en {@code PerfilPacienteService}: el pre-chequeo resuelve el caso comun sin intentar el
	 * {@code INSERT}, y el unique {@code uk_derivacion_destino} cierra la ventana de carrera que
	 * ningun {@code SELECT} previo cierra.
	 *
	 * <p><b>La idempotencia es por DESTINO.</b> La misma participacion derivada a <i>otro</i> Caso
	 * crea una fila nueva, y es deliberado: un paciente con dos Casos abiertos —rodilla y hombro—
	 * puede trabajar los dos en la misma clase. Lo que se garantiza es que sean dos filas
	 * explicitas y no una que cambia de Caso en silencio.
	 */
	DerivacionSnapshot registrar(RegistroDeDerivacion registro);

	/**
	 * Deshace el vinculo, con motivo obligatorio. La fila queda.
	 *
	 * <p>Revertir dos veces devuelve la misma fila sin tocar nada: el reintento tras un timeout no
	 * es un error y el segundo motivo no pisa al primero.
	 */
	DerivacionSnapshot revertir(ReversionDeDerivacion reversion);
}
