package org.openmrs.module.amrsmobileforms.web.controller;

import java.io.File;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Vector;

import javax.servlet.http.HttpSession;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.amrsmobileforms.MobileFormEntryConstants;
import org.openmrs.module.amrsmobileforms.MobileFormEntryError;
import org.openmrs.module.amrsmobileforms.MobileFormEntryErrorModel;
import org.openmrs.module.amrsmobileforms.MobileFormEntryService;
import org.openmrs.module.amrsmobileforms.MobileFormQueue;
import org.openmrs.module.amrsmobileforms.util.MobileFormEntryFileUploader;
import org.openmrs.module.amrsmobileforms.util.MobileFormEntryUtil;
import org.openmrs.module.amrsmobileforms.util.XFormEditor;
import org.openmrs.web.WebConstants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * @author Samuel Mbugua
 *
 */
@Controller
public class ResolveErrorsController {
	private static final Log log = LogFactory.getLog(ResolveErrorsController.class);
	
	/**
	 * Controller for Error list jsp page
	 */
	@ModelAttribute("formEntryErrors")
	@RequestMapping(value="/module/amrsmobileforms/resolveErrors", method=RequestMethod.GET)
	public List<MobileFormEntryError> populateForm() {
		if (Context.isAuthenticated()) {
			MobileFormEntryService mfs = (MobileFormEntryService)Context.getService(MobileFormEntryService.class);
			return mfs.getAllMobileFormEntryErrors();
		}
		return null;
	}
	
	/**
	 * Controller for commentOnError jsp Page
	 */
	@ModelAttribute("errorFormComment")
	@RequestMapping(value="/module/amrsmobileforms/commentOnError", method=RequestMethod.GET)
	public List<MobileFormEntryErrorModel> populateCommentForm(@RequestParam Integer errorId) {
		return getErrorObject(errorId);
	}
	
	/**
	 * Controller for commentOnError post jsp Page
	 */
	@RequestMapping(value="/module/amrsmobileforms/commentOnError", method=RequestMethod.POST)
	public String saveComment(HttpSession httpSession, @RequestParam Integer errorId, @RequestParam String comment) {
		if (comment.trim().length() > 0) {
			MobileFormEntryService mfs = (MobileFormEntryService)Context.getService(MobileFormEntryService.class);
			MobileFormEntryError error=mfs.getErrorById(errorId);
			error.setComment(comment);
			error.setCommentedBy(Context.getAuthenticatedUser());
			error.setDateCommented(new Date());
			mfs.saveErrorInDatabase(error);
		}else
			httpSession.setAttribute(WebConstants.OPENMRS_ERROR_ATTR, "Invalid Comment" );
		return "redirect:resolveErrors.list";		
	}
	
	/**
	 * Controller for resolveError jsp Page
	 */
	@ModelAttribute("errorFormResolve")
	@RequestMapping(value="/module/amrsmobileforms/resolveError", method=RequestMethod.GET)
	public List<MobileFormEntryErrorModel> populateErrorForm(@RequestParam Integer errorId) {
		return getErrorObject(errorId);	
	}


	/**
	 * Controller for resolveError post jsp Page
	 */
	@RequestMapping(value="/module/amrsmobileforms/resolveError", method=RequestMethod.POST)
	public String resolveError(HttpSession httpSession, @RequestParam String householdId,
								@RequestParam Integer errorId, @RequestParam String errorItemAction,
								@RequestParam String birthDate, @RequestParam String patientIdentifier,
								@RequestParam String providerId){
		MobileFormEntryService mobileService;
		String filePath;
		
		// user must be authenticated (avoids authentication errors)
		if (Context.isAuthenticated()) {
			if (!Context.getAuthenticatedUser().hasPrivilege(
					MobileFormEntryConstants.PRIV_RESOLVE_MOBILE_FORM_ENTRY_ERROR)) {
				httpSession.setAttribute(WebConstants.OPENMRS_ERROR_ATTR, "amrsmobileforms.action.noRights");
				return "redirect:resolveErrors.list";
			}
				
			mobileService=Context.getService(MobileFormEntryService.class);
			
			// fetch the MobileFormEntryError item from the database
			MobileFormEntryError errorItem = mobileService.getErrorById(errorId);
			filePath= MobileFormEntryUtil.getMobileFormsErrorDir().getAbsolutePath() + errorItem.getFormName();
			if ("linkHousehold".equals(errorItemAction)) {
				if (mobileService.getHousehold(householdId)==null) {
					httpSession.setAttribute(WebConstants.OPENMRS_ERROR_ATTR, "amrsmobileforms.resolveErrors.action.createLink.error");
					return "redirect:resolveErrors.list";
				}
				else {
					if (XFormEditor.editNode(filePath, 
							MobileFormEntryConstants.PATIENT_NODE + "/" + MobileFormEntryConstants.HOUSEHOLD_IDENTIFIER, householdId)) {
						// put form in queue for normal processing
						saveForm(filePath, MobileFormEntryUtil.getMobileFormsSplitQueueDir().getAbsolutePath() + errorItem.getFormName());
						// delete the mobileformentry error queue item
						mobileService.deleteError(errorItem);
					}
				}
			}
			else if ("assignBirthdate".equals(errorItemAction)) {
				if (birthDate!=null && birthDate.trim()!="") {
					DateFormat df=new SimpleDateFormat();
					try {
						birthDate = (String) df.parseObject(birthDate);
						if (XFormEditor.editNode(filePath, 
								MobileFormEntryConstants.PATIENT_NODE + "/" + MobileFormEntryConstants.PATIENT_BIRTHDATE, birthDate)) {
							// put form in queue for normal processing
							saveForm(filePath, MobileFormEntryUtil.getMobileFormsSplitQueueDir().getAbsolutePath() + errorItem.getFormName());
							// delete the mobileformentry error queue item
							mobileService.deleteError(errorItem);
						}
					} catch (ParseException e) {
						e.printStackTrace();
					}
				}else {
					httpSession.setAttribute(WebConstants.OPENMRS_ERROR_ATTR, "Birthdate was not assigned, Null object entered");
					return "redirect:resolveErrors.list";
				}
			}
			else if ("newIdentifier".equals(errorItemAction)) {
				if (patientIdentifier != null && patientIdentifier.trim() != "") {
					if (XFormEditor.editNode(filePath, 
							MobileFormEntryConstants.PATIENT_NODE + "/" + MobileFormEntryConstants.PATIENT_IDENTIFIER, patientIdentifier)) {
						// put form in queue for normal processing
						saveForm(filePath, MobileFormEntryUtil.getMobileFormsSplitQueueDir().getAbsolutePath() + errorItem.getFormName());
						// delete the mobileformentry error queue item
						mobileService.deleteError(errorItem);
					}
				}
				else {
					httpSession.setAttribute(WebConstants.OPENMRS_ERROR_ATTR, "amrsmobileforms.resolveErrors.action.newIdentifier.error");
					return "redirect:resolveErrors.list";
				}
			}else if ("linkProvider".equals(errorItemAction)) {
				if (providerId != null && providerId.trim() != "") {
					providerId = Context.getUserService().getUser(Integer.parseInt(providerId)).getSystemId();
					if (XFormEditor.editNode(filePath, 
							MobileFormEntryConstants.ENCOUNTER_NODE + "/" + MobileFormEntryConstants.ENCOUNTER_PROVIDER, providerId)) {
						// put form in queue for normal processing
						saveForm(filePath, MobileFormEntryUtil.getMobileFormsSplitQueueDir().getAbsolutePath() + errorItem.getFormName());
						// delete the mobileformentry error queue item
						mobileService.deleteError(errorItem);
					}
				}
				else {
					httpSession.setAttribute(WebConstants.OPENMRS_ERROR_ATTR, "(Null) Invalid provider ID");
					return "redirect:resolveErrors.list";
				}
			}
			else if ("createPatient".equals(errorItemAction)) {
				// process the form 
				try {
					MobileFormEntryFileUploader.submitXFormFile(filePath);
					
					// delete the mobileformentry error queue item
					mobileService.deleteError(errorItem);
				} catch (Exception e) {
					log.debug("Error submitting to XForms", e);
					httpSession.setAttribute(WebConstants.OPENMRS_ERROR_ATTR, "amrsmobileforms.resolveErrors.action.createPatient.error"); 
					return "redirect:resolveErrors.list";
				}
				
			}
			else if ("deleteError".equals(errorItemAction)) {
				// delete the mobileformentry error queue item
				mobileService.deleteError(errorItem);
				
			}
			else if ("deleteComment".equals(errorItemAction)) {
				//set comment to null and save
				errorItem.setComment(null);
				mobileService.saveErrorInDatabase(errorItem);
			}
			else if ("noChange".equals(errorItemAction)) {
				// do nothing here
			}
			else
				throw new APIException("Invalid action selected for: " + errorId);
		}
		
		httpSession.setAttribute(WebConstants.OPENMRS_MSG_ATTR, "amrsmobileforms.resolveErrors.action.success"); 
		return "redirect:resolveErrors.list";		
	}
	
	/**
	 * Given an id, this method  creates an error model
	 * @param errorId
	 * @return List of errors
	 */
	private static List<MobileFormEntryErrorModel>  getErrorObject(Integer errorId) {
		MobileFormEntryService mfs = (MobileFormEntryService)Context.getService(MobileFormEntryService.class);
		List<MobileFormEntryErrorModel> list= new Vector<MobileFormEntryErrorModel>();
		MobileFormEntryError error= mfs.getErrorById(errorId);
		if (error !=null) {
			String filePath=getAbsoluteFilePath(error.getFormName(), mfs);
			error.setFormName(createFormData(error.getFormName(), mfs));
			MobileFormEntryErrorModel errorForm = new MobileFormEntryErrorModel(error);
			errorForm.setFormPath(filePath);
			list.add(errorForm);
		}
		return list;
	}
	
	/**
	 * Converts an xml file specified by <b>formPath</b> to a string
	 * @param formPath
	 * @param mfs
	 * @return String representation of the file
	 */
	private static String createFormData (String formName, MobileFormEntryService mfs) {
		
		MobileFormQueue queue= mfs.getMobileFormEntryQueue(MobileFormEntryUtil.getMobileFormsErrorDir().getAbsolutePath()
								+ formName);
		return queue.getFormData();
	}
	
	/**
	 * Takes in Mobile Queue and returns an absolute Path
	 * @param formPath
	 * @param mfs
	 * @return String absolute path of the file
	 */
	private static String getAbsoluteFilePath (String formName, MobileFormEntryService mfs) {
		
		MobileFormQueue queue= mfs.getMobileFormEntryQueue(MobileFormEntryUtil.getMobileFormsErrorDir().getAbsolutePath()
								+ formName);
		return queue.getFileSystemUrl();
	}

	/**
	 * Stores a form in a specified folder
	 */
	private static void saveForm(String oldFormPath, String newFormPath){
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
}	