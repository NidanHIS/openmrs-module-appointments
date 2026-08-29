package org.openmrs.module.appointments.nidan;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.openmrs.api.context.Context;
import org.openmrs.module.appointments.model.Appointment;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tells the middleware that an appointment changed, without making that the clinician's
 * problem.
 *
 * <p>Three things this must never do, and each of them is a way a booking screen becomes
 * unusable when a machine somewhere else is having a bad day.
 *
 * <p><b>It must not publish inside the transaction.</b> An advice's afterReturning runs
 * before the transaction commits, so publishing there announces appointments that may
 * still roll back — and a patient told about an appointment the hospital does not have
 * is worse than one told nothing. The work is registered for after commit, and if the
 * transaction rolls back it never happens.
 *
 * <p><b>It must not block the save.</b> A clinician pressing Save waits for the
 * database, not for an HTTP round trip to another service. The post runs on a small
 * bounded pool, so a middleware that has stopped answering costs a background thread and
 * not the person at the desk.
 *
 * <p><b>It must not fail the save.</b> Every failure here is swallowed and logged. The
 * appointment is already committed and true; failing to announce it is the outbox's
 * problem to solve later, not a reason to reject a booking a clinician has already made.
 *
 * @author Dipak Thapa &lt;dipakthapaofficial@gmail.com&gt;
 */
public class NidanAppointmentPublisher {

    private static final Log log = LogFactory.getLog(NidanAppointmentPublisher.class);

    public static final String GP_ENABLED = "nidan.appointment.sync.enabled";
    public static final String GP_URL = "nidan.appointment.sync.url";
    public static final String GP_SECRET = "nidan.appointment.sync.secret";

    /**
     * Accounts whose writes are already known to the middleware, comma-separated.
     *
     * <p>The middleware writes Odoo's appointments into OpenMRS. Those saves trip this
     * advice like any other, so without this the same appointment reaches the portal
     * twice — once as the Odoo booking it is, and once as an OpenMRS booking it is not,
     * under a different identity, appearing to the patient as two appointments for one
     * slot.
     *
     * <p>The account name is the signal because it is the only one available: the write
     * arrives over the ordinary REST API and carries nothing else to distinguish it. CIS
     * already names this account {@code openmrs-sync-username} in its own configuration
     * for the same purpose, and its comment there says to keep the two the same.
     */
    public static final String GP_IGNORE_USERS = "nidan.appointment.sync.ignoreUsers";

    private static final String DEFAULT_IGNORE_USERS = "nidan-sync";

    private static final String DEFAULT_URL = "http://nidan-cis:8081/openmrs/appointment-sync";

    /**
     * Two threads, a queue of fifty, and callers never run the work themselves.
     *
     * <p>Bounded on purpose and at both ends. An unbounded queue turns a middleware
     * outage into an OutOfMemoryError in the EHR — the clinical system dying of a
     * reporting system's illness. CallerRunsPolicy would be worse still: it hands the
     * work back to the thread that was saving the appointment, which is exactly the
     * blocking this class exists to avoid. So the policy is to discard, loudly.
     */
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            1, 2, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(50),
            new ThreadPoolExecutor.DiscardPolicy());

    private final CloseableHttpClient httpClient;

    public NidanAppointmentPublisher() {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(3000)
                .setSocketTimeout(5000)
                .setConnectionRequestTimeout(3000)
                .build();
        this.httpClient = HttpClients.custom().setDefaultRequestConfig(config).build();
    }

    /** Queue this appointment for publication once the transaction has committed. */
    public void publishAfterCommit(final Appointment appointment) {
        publishAfterCommit(appointment, false);
    }

    /**
     * @param backfill true when this is the one-shot catch-up rather than a live save.
     *     Carried on the wire so a consumer can tell a burst of history from a burst of
     *     activity — the two look identical otherwise, and one of them is worth waking
     *     somebody for.
     */
    public void publishAfterCommit(final Appointment appointment, final boolean backfill) {
        if (appointment == null || !enabled()) {
            return;
        }
        if (writtenByTheMiddleware()) {
            // Already on the topic, from the system that owns it. Publishing again would
            // put two appointments on a patient's screen for one slot.
            log.debug("skipping appointment " + appointment.getUuid()
                    + ": written by an account the middleware already publishes for");
            return;
        }
        final String payload = toJson(appointment, backfill);
        final String uuid = appointment.getUuid();

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No transaction to wait for. Rare, and publishing immediately is right:
            // there is nothing that could still roll back underneath us.
            submit(uuid, payload);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                submit(uuid, payload);
            }
        });
    }

    void submit(final String uuid, final String payload) {
        final int queued = EXECUTOR.getQueue().size();
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                post(uuid, payload);
            }
        });
        if (queued >= 40) {
            // Said before anything is dropped, not after. A queue that is filling is the
            // only warning available that the next appointment will be discarded, and a
            // silent discard is indistinguishable from an appointment nobody booked.
            log.warn("Nidan appointment publish queue is at " + queued
                    + " of 50; appointments will be dropped if the middleware does not recover");
        }
    }

    private void post(String uuid, String payload) {
        CloseableHttpResponse response = null;
        try {
            HttpPost request = new HttpPost(url());
            request.setHeader("Content-Type", "application/json");
            request.setHeader("X-Nidan-Webhook-Secret", secret());
            request.setEntity(new StringEntity(payload, StandardCharsets.UTF_8));
            response = httpClient.execute(request);
            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                log.warn("Nidan appointment sync for " + uuid + " returned HTTP " + status);
            }
        } catch (Exception e) {
            // Swallowed deliberately. The appointment is committed and true; the booking
            // must not fail because a downstream service is unreachable.
            log.warn("Nidan appointment sync for " + uuid + " failed: " + e.getMessage());
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (Exception ignored) {
                    // closing a response that already failed is not worth a second log line
                }
            }
        }
    }

    /**
     * Hand-built rather than reached for a JSON library.
     *
     * <p>Every value here is a uuid, an enum name or an ISO timestamp, so there is
     * nothing that needs escaping beyond what those already guarantee — and this module
     * ships into an OpenMRS distribution whose classpath is somebody else's to change.
     */
    String toJson(Appointment appointment) {
        return toJson(appointment, false);
    }

    String toJson(Appointment appointment, boolean backfill) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "appointment_uuid", appointment.getUuid());
        field(sb, "patient_uuid", appointment.getPatient() == null ? null : appointment.getPatient().getUuid());
        field(sb, "provider_uuid", appointment.getProvider() == null ? null : appointment.getProvider().getUuid());
        field(sb, "service_uuid", appointment.getService() == null ? null : appointment.getService().getUuid());
        field(sb, "location_uuid", appointment.getLocation() == null ? null : appointment.getLocation().getUuid());
        field(sb, "start_datetime_utc", utc(appointment.getStartDateTime()));
        field(sb, "end_datetime_utc", utc(appointment.getEndDateTime()));
        field(sb, "status", appointment.getStatus() == null ? null : appointment.getStatus().name());
        field(sb, "date_changed", utc(appointment.getDateChanged() == null
                ? appointment.getDateCreated()
                : appointment.getDateChanged()));
        sb.append("\"backfill\":").append(backfill).append(',');
        sb.append("\"voided\":").append(Boolean.TRUE.equals(appointment.getVoided()));
        sb.append('}');
        return sb.toString();
    }

    private static void field(StringBuilder sb, String name, String value) {
        sb.append('"').append(name).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(value).append('"');
        }
        sb.append(',');
    }

    /**
     * UTC, always.
     *
     * <p>OpenMRS stores a Date with no zone and the server's default is whatever the
     * host was built with. Sending that unqualified would let the same appointment mean
     * two different times depending on which machine read it.
     */
    static String utc(Date date) {
        if (date == null) {
            return null;
        }
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        return iso.format(date);
    }

    /** Did this save come from the middleware writing somebody else's appointment in? */
    boolean writtenByTheMiddleware() {
        String current = currentUsername();
        if (current == null || current.trim().isEmpty()) {
            return false;
        }
        for (String ignored : globalProperty(GP_IGNORE_USERS, DEFAULT_IGNORE_USERS).split(",")) {
            if (ignored.trim().equalsIgnoreCase(current.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Overridable so the guard can be tested without an OpenMRS session. */
    String currentUsername() {
        try {
            return Context.getAuthenticatedUser() == null
                    ? null
                    : Context.getAuthenticatedUser().getUsername();
        } catch (Exception e) {
            // No session to ask — during startup, or in a test. Publishing is the safer
            // answer: a missed appointment is worse for a patient than a duplicate.
            return null;
        }
    }

    private boolean enabled() {
        return "true".equalsIgnoreCase(globalProperty(GP_ENABLED, "false"));
    }

    private String url() {
        return globalProperty(GP_URL, DEFAULT_URL);
    }

    private String secret() {
        return globalProperty(GP_SECRET, "");
    }

    String globalProperty(String name, String fallback) {
        try {
            String value = Context.getAdministrationService().getGlobalProperty(name);
            return value == null || value.trim().isEmpty() ? fallback : value.trim();
        } catch (Exception e) {
            // Reading a global property can fail outside a session — during startup, or
            // in a test. Defaulting is right; throwing here would break a save.
            return fallback;
        }
    }
}
