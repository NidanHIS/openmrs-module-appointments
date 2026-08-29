package org.openmrs.module.appointments.scheduler.tasks;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.appointments.model.Appointment;
import org.openmrs.module.appointments.nidan.NidanAppointmentPublisher;
import org.openmrs.module.appointments.service.AppointmentsService;
import org.openmrs.scheduler.tasks.AbstractTask;

/**
 * Publishes appointments that already existed when the portal was switched on.
 *
 * <p>The advice only sees appointments that change from now on. A hospital enabling the
 * portal on a Tuesday would show its patients an empty diary until each of them happened
 * to be rescheduled, which reads as a broken portal rather than a new one.
 *
 * <p>Run from Manage Scheduler, on demand. Not on a timer: it is a catch-up, and a
 * catch-up that runs nightly forever is just an expensive way of resending everything.
 *
 * <h2>Safe to run twice</h2>
 *
 * <p>Every event carries an id derived from the appointment and its last modification,
 * so a consumer that has already seen an unchanged appointment recognises it and does
 * nothing. That is the property ST-2.2.4 asks for, and it lives in the id rather than
 * here — this task is deliberately dumb about what has been sent before, because a task
 * that remembered would be one more piece of state to get wrong.
 *
 * @author Dipak Thapa &lt;dipakthapaofficial@gmail.com&gt;
 */
public class NidanAppointmentBackfillTask extends AbstractTask {

    private static final Log log = LogFactory.getLog(NidanAppointmentBackfillTask.class);

    /** How far ahead to look. A year covers annual reviews without sweeping the table. */
    private static final int MONTHS_AHEAD = 12;

    private final NidanAppointmentPublisher publisher;

    public NidanAppointmentBackfillTask() {
        this(new NidanAppointmentPublisher());
    }

    NidanAppointmentBackfillTask(NidanAppointmentPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void execute() {
        Date now = new Date();
        Calendar horizon = Calendar.getInstance();
        horizon.setTime(now);
        horizon.add(Calendar.MONTH, MONTHS_AHEAD);

        // Future only. A patient's past appointments are already history to them, and
        // republishing years of them would move a great deal of data to tell nobody
        // anything they can act on.
        List<Appointment> appointments = Context.getService(AppointmentsService.class)
                .getAllAppointmentsInDateRange(now, horizon.getTime());

        int published = 0;
        for (Appointment appointment : appointments) {
            if (Boolean.TRUE.equals(appointment.getVoided())) {
                continue;
            }
            publisher.publishAfterCommit(appointment, true);
            published++;
        }
        log.info("Nidan appointment backfill queued " + published + " of " + appointments.size()
                + " appointments between now and " + horizon.getTime());
    }
}
