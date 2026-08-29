package org.openmrs.module.appointments.nidan;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.appointments.AppointmentsActivatorComponent;
import org.openmrs.module.appointments.service.AppointmentsService;
import org.springframework.stereotype.Component;

/**
 * Registers the Nidan advice at startup, through the module's own extension point.
 *
 * <p>Registered unconditionally and gated at publish time by the
 * {@code nidan.appointment.sync.enabled} global property, rather than only being
 * registered when that property is on. Advice cannot be added and removed while OpenMRS
 * runs, so gating registration would mean an administrator turning the setting on and
 * seeing nothing happen until someone restarted the EHR — a switch that needs a restart
 * is a switch people learn not to trust.
 *
 * @author Dipak Thapa &lt;dipakthapaofficial@gmail.com&gt;
 */
@Component
public class NidanAdviceActivatorComponent implements AppointmentsActivatorComponent {

    private static final Log log = LogFactory.getLog(NidanAdviceActivatorComponent.class);

    private final NidanAppointmentAdvice advice;

    public NidanAdviceActivatorComponent() {
        this(new NidanAppointmentAdvice());
    }

    public NidanAdviceActivatorComponent(NidanAppointmentAdvice advice) {
        this.advice = advice;
    }

    @Override
    public void started() {
        Context.addAdvice(AppointmentsService.class, advice);
        log.info("Nidan appointment advice registered; publishing is governed by "
                + NidanAppointmentPublisher.GP_ENABLED);
    }

    @Override
    public void willStop() {
        Context.removeAdvice(AppointmentsService.class, advice);
    }
}
