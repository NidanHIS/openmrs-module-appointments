package org.openmrs.module.appointments.nidan;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.appointments.model.Appointment;
import org.springframework.aop.AfterReturningAdvice;

/**
 * Notices appointments changing, and hands them to {@link NidanAppointmentPublisher}.
 *
 * <p>Advises the same three methods the module's own atom-feed advice does —
 * validateAndSave, changeStatus, undoStatusChange — because those are where an
 * appointment's observable state changes, and picking a different set would mean the
 * portal and the atom feed disagreed about what had happened.
 *
 * <p>Two of the three return void and carry the appointment in their first argument.
 * That is the same shape AppointmentAdvice works around, and it is worked around the
 * same way here rather than more cleverly: two adaptations of one awkward signature are
 * easier to keep in step than one clever one.
 *
 * @author Dipak Thapa &lt;dipakthapaofficial@gmail.com&gt;
 */
public class NidanAppointmentAdvice implements AfterReturningAdvice {

    private static final Log log = LogFactory.getLog(NidanAppointmentAdvice.class);

    private static final List<String> ADVISED = Arrays.asList(
            "validateAndSave", "changeStatus", "undoStatusChange");

    /** Methods that return void and put the appointment in argument zero instead. */
    private static final List<String> APPOINTMENT_IN_ARGUMENTS = Arrays.asList(
            "changeStatus", "undoStatusChange");

    private final NidanAppointmentPublisher publisher;

    public NidanAppointmentAdvice() {
        this(new NidanAppointmentPublisher());
    }

    public NidanAppointmentAdvice(NidanAppointmentPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void afterReturning(Object returnValue, Method method, Object[] arguments, Object target) {
        if (!ADVISED.contains(method.getName())) {
            return;
        }
        try {
            Appointment appointment = appointmentFrom(returnValue, method, arguments);
            if (appointment != null) {
                publisher.publishAfterCommit(appointment);
            }
        } catch (Exception e) {
            // An advice that throws fails the method it advised. Nothing this class does
            // is worth failing a clinician's save for.
            log.warn("Nidan appointment advice failed for " + method.getName() + ": " + e.getMessage());
        }
    }

    private static Appointment appointmentFrom(Object returnValue, Method method, Object[] arguments) {
        if (APPOINTMENT_IN_ARGUMENTS.contains(method.getName())
                && arguments != null && arguments.length > 0 && arguments[0] instanceof Appointment) {
            return (Appointment) arguments[0];
        }
        return returnValue instanceof Appointment ? (Appointment) returnValue : null;
    }
}
