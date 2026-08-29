package org.openmrs.module.appointments.scheduler.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.openmrs.module.appointments.model.Appointment;
import org.openmrs.module.appointments.nidan.NidanAppointmentPublisher;

/**
 * What the backfill sends, and what it declines to.
 *
 * @author Dipak Thapa &lt;dipakthapaofficial@gmail.com&gt;
 */
public class NidanAppointmentBackfillTaskTest {

    private static class Recorder extends NidanAppointmentPublisher {
        final List<String> published = new ArrayList<String>();
        final List<Boolean> flags = new ArrayList<Boolean>();

        @Override
        public void publishAfterCommit(Appointment appointment, boolean backfill) {
            published.add(appointment.getUuid());
            flags.add(backfill);
        }
    }

    private static Appointment appointment(String uuid, boolean voided) {
        Appointment a = new Appointment();
        a.setUuid(uuid);
        a.setVoided(voided);
        return a;
    }

    @Test
    public void everyAppointmentIsMarkedAsBackfill() {
        // A consumer needs to tell a burst of history from a burst of activity. They
        // look identical otherwise, and only one of them is worth waking somebody for.
        Recorder recorder = new Recorder();
        recorder.publishAfterCommit(appointment("a-1", false), true);

        assertEquals(1, recorder.flags.size());
        assertTrue(recorder.flags.get(0));
    }

    @Test
    public void aVoidedAppointmentIsNotBackfilled() {
        // A cancelled appointment from before the portal existed is not news. Sending it
        // would put a cancellation on a patient's screen for something they were never
        // told about in the first place.
        Recorder recorder = new Recorder();
        List<Appointment> all = new ArrayList<Appointment>();
        all.add(appointment("a-1", false));
        all.add(appointment("a-voided", true));

        for (Appointment a : all) {
            if (Boolean.TRUE.equals(a.getVoided())) {
                continue;
            }
            recorder.publishAfterCommit(a, true);
        }

        assertEquals(1, recorder.published.size());
        assertFalse(recorder.published.contains("a-voided"));
    }
}
