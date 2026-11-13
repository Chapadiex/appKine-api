-- Datos declarados por el administrador al preparar el alta manual de un colaborador.
-- La invitacion sigue siendo la autoridad para crear la cuenta y la membership al aceptarla.
-- El JSON es una foto del formulario, no una fuente de permisos: los alcances se revalidan al
-- emitir y las memberships se vuelven a validar cuando la persona acepta.
ALTER TABLE colaborador_invitacion
    ADD COLUMN datos_alta_manual JSON NULL
        COMMENT 'Datos personales y profesionales capturados antes de emitir la invitacion; NULL para invitaciones anteriores';
