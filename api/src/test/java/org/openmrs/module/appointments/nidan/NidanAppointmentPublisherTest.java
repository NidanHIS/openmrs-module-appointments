package org.openmrs.module.appointments.nidan;

import static org.junit.Assert.assertEquals;
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
}
