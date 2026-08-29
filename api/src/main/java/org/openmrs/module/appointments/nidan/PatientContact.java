package org.openmrs.module.appointments.nidan;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Patient;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.api.context.Context;

/**
 * The patient's phone number, found by attribute type uuid rather than by name.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Both SMS paths in this module asked for {@code getAttribute("phoneNumber")}.
 * {@code Person.getAttribute(String)} matches on the attribute type's <b>display
 * name</b>, and this database has no type called {@code phoneNumber} — it calls the
 * phone attribute <b>"Telephone Number"</b>, uuid
 * {@code 14d4f066-15f5-102d-96e4-000c29c2a5d7}. Verified against the live schema:
 * zero rows in {@code person_attribute_type} match that name.
 *
 * <p>So both paths returned null for every patient, always, and logged
 * "No mobile number found for the patient" while doing it. Appointment reminders and
 * booking confirmations have never been sent at this site, and the log said the
 * patients had no numbers rather than that the lookup was wrong.
 *
 * <p>A display name is a per-site label an administrator can rename. A uuid is an
 * identity. The same reasoning already governs the CIS contact mapper, which
 * documents this bug in its javadoc; this is the other end of it.
 *
 * @author Dipak Thapa &lt;dipakthapaofficial@gmail.com&gt;
 */
public final class PatientContact {

	private static final Log log = LogFactory.getLog(PatientContact.class);

	/** Same property CIS reads, so one setting governs both ends of the contact path. */
	public static final String ATTRIBUTE_TYPE_UUID_PROPERTY = "nidan.contact.attributeTypeUuid";

	public static final String DEFAULT_ATTRIBUTE_TYPE_UUID = "14d4f066-15f5-102d-96e4-000c29c2a5d7";

	/**
	 * Retained only as a last resort for a site whose uuid genuinely differs.
	 *
	 * <p>Not "phoneNumber": that string matched nothing here and is what caused the
	 * defect. "Telephone Number" is the name the OpenMRS reference data ships with.
	 */
	static final String FALLBACK_ATTRIBUTE_NAME = "Telephone Number";

	private PatientContact() {
	}

	/** The patient's number, or null if they have none recorded. */
	public static String phoneNumberOf(Patient patient) {
		if (patient == null) {
			return null;
		}
		PersonAttribute attribute = attributeFor(patient);
		if (attribute == null || attribute.getValue() == null || attribute.getValue().trim().isEmpty()) {
			return null;
		}
		return attribute.getValue().trim();
	}

	private static PersonAttribute attributeFor(Patient patient) {
		String uuid = configuredUuid();
		PersonAttributeType type = Context.getPersonService().getPersonAttributeTypeByUuid(uuid);
		if (type != null) {
			return patient.getAttribute(type);
		}
		// Logged at warn, not debug. A uuid that resolves to nothing means the
		// deployment is misconfigured or the reference data changed, and the visible
		// symptom otherwise is silence — which is exactly how this bug survived.
		log.warn("No person attribute type with uuid " + uuid + " (property "
		        + ATTRIBUTE_TYPE_UUID_PROPERTY + "); falling back to the name '"
		        + FALLBACK_ATTRIBUTE_NAME + "'");
		return patient.getAttribute(FALLBACK_ATTRIBUTE_NAME);
	}

	static String configuredUuid() {
		try {
			String configured = Context.getAdministrationService()
			        .getGlobalProperty(ATTRIBUTE_TYPE_UUID_PROPERTY);
			return configured == null || configured.trim().isEmpty()
			        ? DEFAULT_ATTRIBUTE_TYPE_UUID
			        : configured.trim();
		}
		catch (Exception e) {
			return DEFAULT_ATTRIBUTE_TYPE_UUID;
		}
	}
}
