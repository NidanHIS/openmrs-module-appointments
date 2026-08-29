package org.openmrs.module.appointments.scheduler.tasks;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bahmni.module.communication.service.CommunicationService;
import org.bahmni.module.communication.service.MessageBuilderService;
import org.openmrs.PersonAttribute;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.module.appointments.model.Appointment;
import org.openmrs.module.appointments.nidan.PatientContact;
import org.openmrs.module.appointments.service.AppointmentArgumentsMapper;
import org.openmrs.module.appointments.service.AppointmentsService;
import org.openmrs.scheduler.tasks.AbstractTask;

import java.util.List;

public class ReminderForAppointment extends AbstractTask {
    private Log log = LogFactory.getLog(this.getClass());

    @Override
    public void execute() {
        AdministrationService administrationService = Context.getService(AdministrationService.class);

        boolean scheduleSMS = Boolean.parseBoolean(administrationService.getGlobalProperty("sms.enableAppointmentReminderSMSAlert", "false"));

        if (!scheduleSMS) {
            return;
        }
        AppointmentsService appointmentsService = Context.getService(AppointmentsService.class);
        String schedulerReminderTime = administrationService.getGlobalPropertyObject("SchedulerReminderBeforeHours").getPropertyValue();
        List<Appointment> appointments = appointmentsService.getAllAppointmentsReminder(schedulerReminderTime);
        for (Appointment appointment: appointments) {
            String phoneNumber = PatientContact.phoneNumberOf(appointment.getPatient());
            if (null == phoneNumber) {
                // `continue`, not `return`. This is inside the loop over every
                // appointment due a reminder, so returning here meant one patient with
                // no number recorded silently cancelled the reminders for everybody
                // after them in the batch — and the log line blamed that one patient.
                log.info("No mobile number recorded for the patient of appointment "
                        + appointment.getUuid() + "; skipping this reminder.");
                continue;
            }
            MessageBuilderService smsBuilderService =Context.getService(MessageBuilderService.class);
            AppointmentArgumentsMapper appointmentArgumentsMapper=Context.getService(AppointmentArgumentsMapper.class);
            String message = smsBuilderService.getAppointmentReminderMessage(appointmentArgumentsMapper.createArgumentsMapForAppointmentBooking(appointment),appointmentArgumentsMapper.getProvidersNameInString(appointment));
            CommunicationService communicationService=Context.getService(CommunicationService.class);
            communicationService.sendSMS(phoneNumber, message);
        }
    }
}
