package org.openmrs.module.amrsmobileforms;

import java.io.File;
import java.util.Date;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientIdentifierType;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.amrsmobileforms.util.MobileFormEntryUtil;
import org.openmrs.module.amrsmobileforms.util.RelationshipBuider;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Node;


/**
 * Processes Successfully processed patient forms.
 * 
 * It is a temporary solution since most of what is happening here should be done by xforms
 * This processor will accomplish the following tasks
 * (a), Add all HCT IDs as Secondary IDs
 * (b), Insert if available, Economic Survey Id
 * (c), Create relationships between household persons
 * (d), Add Contact Phone Person Attribute
 * 
 * 
 * @author Samuel Mbugua
 *
 */
@Transactional
public class MobileFormPostProcessor {

	private static final Log log = LogFactory.getLog(MobileFormPostProcessor.class);
	private static final DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
	private DocumentBuilder docBuilder;
	private XPathFactory xPathFactory;
	private MobileFormEntryService mobileService;
	// allow only one running instance
	private static Boolean isRunning = false; 

	/**
	 * Default Constructor
	 */
	public MobileFormPostProcessor(){
		try{
			docBuilder = docBuilderFactory.newDocumentBuilder();
			this.getMobileService();
		}
		catch(Exception e){
			log.error("Problem occurred while creating document builder", e);
		}
	}
	
	/**
	 * Process an existing entries in the mobile form post-process queue
	 * @param filePath 
	 * @param queue
	 * @throws APIException
	 */
	private void processPostProcessForm(String filePath, MobileFormQueue queue) throws APIException {
		log.debug("Performing post process, This adds relationships and person attributes");
		boolean canArchive=false;
		try {
			String formData = queue.getFormData();
			docBuilder = docBuilderFactory.newDocumentBuilder();
			XPathFactory xpf = getXPathFactory();
			XPath xp = xpf.newXPath();
			Document doc = docBuilder.parse(IOUtils.toInputStream(formData));
			
			Node curNode=(Node)  xp.evaluate(MobileFormEntryConstants.PATIENT_NODE, doc, XPathConstants.NODE);
			String patientIdentifier = xp.evaluate(MobileFormEntryConstants.PATIENT_IDENTIFIER, curNode); 
			String hctID = xp.evaluate(MobileFormEntryConstants.PATIENT_HCT_IDENTIFIER, curNode);
			String householdIdentifier=xp.evaluate(MobileFormEntryConstants.PATIENT_HOUSEHOLD_IDENTIFIER, curNode);
			String phoneNumber = xp.evaluate(MobileFormEntryConstants.PATIENT_PHONE, curNode);
			
			curNode=(Node)  xp.evaluate(MobileFormEntryConstants.OBS_NODE, doc, XPathConstants.NODE);
			String relationshipToHead = xp.evaluate(MobileFormEntryConstants.OBS_RELATIONSHIP, curNode);
			
			Patient pat;
			
			// First Ensure there is at least a patient identifier in the form
			if (MobileFormEntryUtil.getPatientIdentifier(doc) == null || MobileFormEntryUtil.getPatientIdentifier(doc).trim() == "") {
				// form has no patient identifier : log an error
				log.debug("Patient has no identifier, or the identifier provided is invalid");
				return;
			}else{
				//create the patient
				pat=MobileFormEntryUtil.getPatient(patientIdentifier);
			}
				
			//Check ID and add new HCT ID
			if (hctID != null && hctID.trim() != ""){
				PatientIdentifierType patIdType = Context.getPatientService().getPatientIdentifierType(8);
				Location loc = Context.getLocationService().getLocation(4);
				PatientIdentifier iden = new PatientIdentifier(hctID, patIdType, loc);
				pat.addIdentifier(iden);
			}
			//Check Phone number and add it
			if (phoneNumber != null && phoneNumber.trim() != ""){
				log.error("Adding Phone number");
				PersonAttributeType perAttType = Context.getPersonService().getPersonAttributeType(10);
				PersonAttribute personAttribute = new PersonAttribute(perAttType, phoneNumber);
				pat.addAttribute(personAttribute);
			}
			Context.getPersonService().savePerson(pat);
			
			//For this person attempt to create a relationship.
			if (relationshipToHead != null && relationshipToHead.trim() != ""
				&& householdIdentifier != null && householdIdentifier.trim() != ""){
				canArchive = RelationshipBuider.createRelationship(pat, relationshipToHead, householdIdentifier);
			}
		}
		catch (Throwable t) {
			log.error("Error Post Processing", t);
			canArchive=true;
		}
		
		//put form in archive if ready to archive
		if (canArchive)
			saveFormInArchive(filePath);
	}

	/**
	 * Processes each post process entry. If there are no pending
	 * items in the queue, this method simply returns quietly.
	 */
	public void processPostProcessQueue() {
		synchronized (isRunning) {
			if (isRunning) {
				log.warn("MobileForms Post processor aborting (another processor already running)");
				return;
			}
			isRunning = true;
		}

		try {			
			File postProcessQueueDir = MobileFormEntryUtil.getMobileFormsPostProcessDir();
			for (File file : postProcessQueueDir.listFiles()) {
				MobileFormQueue queue = mobileService.getMobileFormEntryQueue(file.getAbsolutePath());
				processPostProcessForm(file.getAbsolutePath(), queue);
			}
		}
		catch(Exception e){
			log.error("Problem occured while processing post-process queue", e);
		}
		finally {
			isRunning = false;
		}
	}
	
	/**
	 * Archives a mobile form after successful processing
	 */
	private void saveFormInArchive(String formPath){
		String archiveFilePath= MobileFormEntryUtil.getMobileFormsArchiveDir(new Date()).getAbsolutePath() + getFormName(formPath);
		saveForm(formPath, archiveFilePath);
	}

	/**
	 * Stores a form in a specified folder after processing.
	 */
	private void saveForm(String oldFormPath, String newFormPath){
		try{
			if(oldFormPath != null){
				File file=new File(oldFormPath);
				
				//move the file to specified new directory
				file.renameTo(new File(newFormPath));
			}
		}
		catch(Exception e){
			log.error(e.getMessage(),e);
		}

	}
	
	/**
	 * Extracts form name from an absolute file path
	 * @param formPath
	 * @return
	 */
	private String getFormName(String formPath) {
		return formPath.substring(formPath.lastIndexOf(File.separatorChar)); 
	}
	
	/**
	 * @return XPathFactory to be used for obtaining data from the parsed XML
	 */
	private XPathFactory getXPathFactory() {
		if (xPathFactory == null)
			xPathFactory = XPathFactory.newInstance();
		return xPathFactory;
	}
	
	/**
	 * @return MobileFormEntryService to be used by the process
	 */
	private MobileFormEntryService getMobileService() {
		if (mobileService == null) {
			try {
				mobileService= (MobileFormEntryService)Context.getService(MobileFormEntryService.class);
			}catch (APIException e) {
				log.debug("MobileFormEntryService not found");
				return null;
			}
		}
		return mobileService;
	}
	
}