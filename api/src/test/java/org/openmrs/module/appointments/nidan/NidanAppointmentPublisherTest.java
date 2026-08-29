package org.openmrs.module.appointments.nidan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import org.junit.Test;
import org.openmrs.Patient;
import org.openmrs.module.appointments.model.Appointment;
import org.openmrs.module.appointments.model.AppointmentStatus;

/**
 * The payload and the timestamp conversion — the two parts that are pure and therefore
 * worth asserting without a running OpenMRS.
 *
 * @author Dipak Thapa &lt;dipakthapaofficial@gmail.com&gt;
 */
public class NidanAppointmentPublisherTest {

    @Test
    public void datesAreConvertedToUtcRatherThanSentInTheServerZone() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kathmandu"));
        cal.clear();
        cal.set(2026, Calendar.SEPTEMBER, 3, 10, 30, 0);

        // 10:30 in Kathmandu is 04:45 UTC. OpenMRS stores a Date with no zone and the
        // server's default is whatever the host was built with, so sending it
        // unqualified would let one appointment mean two different times.
        assertEquals("2026-09-03T04:45:00Z", NidanAppointmentPublisher.utc(cal.getTime()));
    }

    @Test
    public void aNullDateIsNullRatherThanTheEpoch() {
        assertEquals(null, NidanAppointmentPublisher.utc(null));
    }

    @Test
    public void thePayloadCarriesTheFieldsTheReceiverRequires() {
        Appointment appointment = new Appointment();
        appointment.setUuid("appt-1");
        Patient patient = new Patient();
        patient.setUuid("pu-1001");
        appointment.setPatient(patient);
        appointment.setStatus(AppointmentStatus.Scheduled);

        String json = new NidanAppointmentPublisher().toJson(appointment);

        assertTrue(json, json.contains("\"appointment_uuid\":\"appt-1\""));
        assertTrue(json, json.contains("\"patient_uuid\":\"pu-1001\""));
        assertTrue(json, json.contains("\"status\":\"Scheduled\""));
        assertTrue(json, json.contains("\"voided\":false"));
    }

    @Test
    public void absentAssociationsAreNullRatherThanOmitted() {
        // The receiver rejects a payload with no patient. It must be able to tell "the
        // appointment has no provider" from "this sender does not send providers", and
        // an omitted key says the second while meaning the first.
        Appointment appointment = new Appointment();
        appointment.setUuid("appt-2");

        String json = new NidanAppointmentPublisher().toJson(appointment);

        assertTrue(json, json.contains("\"provider_uuid\":null"));
        assertTrue(json, json.contains("\"location_uuid\":null"));
        assertTrue(json, json.contains("\"start_datetime_utc\":null"));
    }

    @Test
    public void aWriteByTheIntegrationAccountIsNotRepublished() {
        // The middleware writes Odoo's appointments into OpenMRS over the ordinary REST
        // API, which trips this advice like any other save. Without the guard the same
        // appointment reaches the portal twice — once as the Odoo booking it is and once
        // as an OpenMRS booking it is not, under a different identity, showing a patient
        // two appointments for one slot.
        NidanAppointmentPublisher publisher = new NidanAppointmentPublisher() {
            @Override
            String currentUsername() {
                return "nidan-sync";
            }

            @Override
            String globalProperty(String name, String fallback) {
                return GP_IGNORE_USERS.equals(name) ? "nidan-sync" : fallback;
            }
        };
        assertTrue(publisher.writtenByTheMiddleware());
    }

    @Test
    public void anOrdinaryClinicianSaveIsPublished() {
        NidanAppointmentPublisher publisher = new NidanAppointmentPublisher() {
            @Override
            String currentUsername() {
                return "dr-sharma";
            }

            @Override
            String globalProperty(String name, String fallback) {
                return GP_IGNORE_USERS.equals(name) ? "nidan-sync" : fallback;
            }
        };
        assertFalse(publisher.writtenByTheMiddleware());
    }

    @Test
    public void anUnknownUserIsPublishedRatherThanDropped() {
        // No session to ask. A missed appointment is worse for a patient than a
        // duplicate, and the duplicate is at least visible to somebody.
        NidanAppointmentPublisher publisher = new NidanAppointmentPublisher() {
            @Override
            String currentUsername() {
                return null;
            }
        };
        assertFalse(publisher.writtenByTheMiddleware());
    }

    /** Records whether the publish path was actually reached. */
    private static class RecordingPublisher extends NidanAppointmentPublisher {
        boolean submitted = false;
        private final String username;

        RecordingPublisher(String username) {
            this.username = username;
        }

        @Override
        String currentUsername() {
            return username;
        }

        @Override
        String globalProperty(String name, String fallback) {
            if (GP_ENABLED.equals(name)) {
                return "true";
            }
            return GP_IGNORE_USERS.equals(name) ? "nidan-sync" : fallback;
        }

        @Override
        void submit(String uuid, String payload) {
            submitted = true;
        }
    }

    private static Appointment anAppointment() {
        Appointment appointment = new Appointment();
        appointment.setUuid("appt-echo");
        Patient patient = new Patient();
        patient.setUuid("pu-1001");
        appointment.setPatient(patient);
        return appointment;
    }

    @Test
    public void theGuardIsActuallyWiredIntoThePublishPath() {
        // Asserting the predicate alone was not enough: removing the call to it from
        // publishAfterCommit left every test passing. A guard that is correct and not
        // called is the same as no guard.
        RecordingPublisher middleware = new RecordingPublisher("nidan-sync");
        middleware.publishAfterCommit(anAppointment());
        assertFalse("a middleware write must not be republished", middleware.submitted);
    }

    @Test
    public void anOrdinarySaveStillReachesThePublishPath() {
        RecordingPublisher clinician = new RecordingPublisher("dr-sharma");
        clinician.publishAfterCommit(anAppointment());
        assertTrue("a clinician's booking must reach the portal", clinician.submitted);
    }

    @Test
    public void aBackfilledPayloadSaysSo() {
        // A consumer needs to tell a burst of history from a burst of activity. They
        // look identical otherwise, and only one is worth waking somebody for.
        Appointment appointment = new Appointment();
        appointment.setUuid("a-1");

        assertTrue(new NidanAppointmentPublisher().toJson(appointment, true).contains("\"backfill\":true"));
        assertTrue(new NidanAppointmentPublisher().toJson(appointment, false).contains("\"backfill\":false"));
    }

    @Test
    public void thePayloadCarriesWhenTheAppointmentLastChanged() {
        // The consumer's deduplication key is built from this. Without it a second
        // backfill looks like a set of new events and every document is rewritten for
        // appointments that have not changed.
        Appointment appointment = new Appointment();
        appointment.setUuid("a-1");
        appointment.setDateCreated(new java.util.Date(0L));

        String json = new NidanAppointmentPublisher().toJson(appointment, true);
        assertTrue(json, json.contains("\"date_changed\":\"1970-01-01T00:00:00Z\""));
    }

    @Test
    public void theCancellationReasonIsCarriedVerbatim() {
        Appointment appointment = new Appointment();
        appointment.setUuid("a-1");
        appointment.setStatus(AppointmentStatus.Cancelled);
        appointment.setComments("patient request");

        String json = new NidanAppointmentPublisher().toJson(appointment);

        assertTrue(json, json.contains("\"status\":\"Cancelled\""));
        assertTrue(json, json.contains("\"reason\":\"patient request\""));
    }

    @Test
    public void aQuoteInTheReasonDoesNotBreakThePayload() {
        // The only free-text field on the wire, and therefore the only one that can
        // produce JSON the middleware cannot parse. A clinician typing a quote would
        // otherwise fail every appointment from that moment until somebody found and
        // edited the comment.
        assertEquals("she said \\\"not this week\\\"",
                NidanAppointmentPublisher.escape("she said \"not this week\""));
        assertEquals("line one\\nline two", NidanAppointmentPublisher.escape("line one\nline two"));
        assertEquals(null, NidanAppointmentPublisher.escape(null));
    }
}
