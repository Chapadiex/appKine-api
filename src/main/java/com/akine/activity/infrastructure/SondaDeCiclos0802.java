package com.akine.activity.infrastructure;

import com.akine.notification.spi.NotificationEnqueueCommand;
import com.akine.notification.spi.NotificationOutbox;
import com.akine.notification.spi.NotificationType;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;

/**
 * SONDA TEMPORAL — se borra al escribir el codigo real de AKINE-08.02.
 *
 * <p>Declara las aristas NUEVAS que la etapa va a tomar, para que
 * {@code ModuleArchitectureTest#sin_ciclos_entre_modulos} las evalue ANTES de escribir dominio.
 * {@code SlicesRuleDefinition} busca ciclos de cualquier longitud; la cabeza encuentra los de dos.
 */
@SuppressWarnings("unused")
final class SondaDeCiclos0802 {

	private PacienteDirectory pacientes;
	private PacienteSnapshot paciente;
	private NotificationOutbox outbox;
	private NotificationEnqueueCommand comando;
	private NotificationType tipo;

	private SondaDeCiclos0802() {
	}
}
