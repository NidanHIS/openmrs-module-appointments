package org.openmrs.module.appointments.nidan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openmrs.Patient;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.PersonService;
import org.openmrs.api.context.Context;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

/**
 * The phone attribute is found by uuid, because its name is not what the code asked for.
 *
 * <p>Verified against the live schema before writing this: the type is called
 * "Telephone Number" and zero types are named "phoneNumber", so both SMS paths in this
 * module returned null for every patient while logging that the patient had no number.
 *
 * @author Dipak Thapa &lt;dipakthapaofficial@gmail.com&gt;
 */
@PowerMockIgnore("javax.management.*")
@RunWith(PowerMockRunner.class)
@PrepareForTest(Context.class)
public class PatientContactTest {

	private PersonService personService;

	private AdministrationService administrationService;

	private PersonAttributeType telephoneType;

	@Before
	public void setUp() {
		mockStatic(Context.class);
		personService = mock(PersonService.class);
		administrationService = mock(AdministrationService.class);
		when(Context.getPersonService()).thenReturn(personService);
		when(Context.getAdministrationService()).thenReturn(administrationService);

		telephoneType = new PersonAttributeType();
		telephoneType.setName("Telephone Number");
		telephoneType.setUuid(PatientContact.DEFAULT_ATTRIBUTE_TYPE_UUID);
	}

	private static Patient patientWith(PersonAttributeType type, String value) {
		Patient patient = new Patient();
		PersonAttribute attribute = new PersonAttribute();
		attribute.setAttributeType(type);
		attribute.setValue(value);
		patient.addAttribute(attribute);
		return patient;
	}

	@Test
	public void theNumberIsFoundEvenThoughTheTypeIsNotCalledPhoneNumber() {
		// The whole defect in one assertion. getAttribute("phoneNumber") returns null
		// against this exact patient, which is what shipped.
		when(personService.getPersonAttributeTypeByUuid(PatientContact.DEFAULT_ATTRIBUTE_TYPE_UUID))
		        .thenReturn(telephoneType);
		Patient patient = patientWith(telephoneType, "9841000001");

		assertNull("the old lookup finds nothing, which is the bug", patient.getAttribute("phoneNumber"));
		assertEquals("9841000001", PatientContact.phoneNumberOf(patient));
	}

	@Test
	public void aPatientWithNoNumberGivesNull() {
		when(personService.getPersonAttributeTypeByUuid(PatientContact.DEFAULT_ATTRIBUTE_TYPE_UUID))
		        .thenReturn(telephoneType);

		assertNull(PatientContact.phoneNumberOf(new Patient()));
	}

	@Test
	public void aBlankNumberIsTreatedAsAbsent() {
		// A blank attribute row exists in real data and reaches this code from
		// Hibernate, not from addAttribute. That distinction is the test:
		// Person.addAttribute() silently DROPS a blank-valued attribute, so building
		// the fixture the obvious way produces a patient with no attributes at all and
		// the assertion passes without exercising anything. Caught by mutation — with
		// the blank guard removed, the obvious version still passed.
		//
		// So the value is blanked after the attribute is attached, which is the state a
		// row loaded from the database actually presents.
		when(personService.getPersonAttributeTypeByUuid(PatientContact.DEFAULT_ATTRIBUTE_TYPE_UUID))
		        .thenReturn(telephoneType);
		Patient patient = patientWith(telephoneType, "9841000001");
		patient.getAttribute(telephoneType).setValue("   ");

		// Passing "" to the SMS gateway sends a message to nowhere and reports success.
		assertNull(PatientContact.phoneNumberOf(patient));
	}

	@Test
	public void theUuidIsConfigurable() {
		PersonAttributeType siteType = new PersonAttributeType();
		siteType.setName("Mobile");
		siteType.setUuid("site-specific-uuid");
		when(administrationService.getGlobalProperty(PatientContact.ATTRIBUTE_TYPE_UUID_PROPERTY))
		        .thenReturn("site-specific-uuid");
		when(personService.getPersonAttributeTypeByUuid("site-specific-uuid")).thenReturn(siteType);

		assertEquals("9800000002", PatientContact.phoneNumberOf(patientWith(siteType, "9800000002")));
	}

	@Test
	public void anUnresolvableUuidFallsBackToTheNameRatherThanGivingUp() {
		// A deployment whose reference data differs must degrade to something, not to
		// silence — silence is how the original defect survived.
		when(personService.getPersonAttributeTypeByUuid(PatientContact.DEFAULT_ATTRIBUTE_TYPE_UUID))
		        .thenReturn(null);

		assertEquals("9841000003",
		    PatientContact.phoneNumberOf(patientWith(telephoneType, "9841000003")));
	}

	@Test
	public void anUnsetPropertyUsesTheKnownUuid() {
		when(administrationService.getGlobalProperty(PatientContact.ATTRIBUTE_TYPE_UUID_PROPERTY))
		        .thenReturn(null);
		assertEquals(PatientContact.DEFAULT_ATTRIBUTE_TYPE_UUID, PatientContact.configuredUuid());

		when(administrationService.getGlobalProperty(PatientContact.ATTRIBUTE_TYPE_UUID_PROPERTY))
		        .thenReturn("   ");
		assertEquals(PatientContact.DEFAULT_ATTRIBUTE_TYPE_UUID, PatientContact.configuredUuid());
	}

	@Test
	public void aNullPatientIsNotAnError() {
		assertNull(PatientContact.phoneNumberOf(null));
	}
}
